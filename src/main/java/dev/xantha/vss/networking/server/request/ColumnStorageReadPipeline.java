package dev.xantha.vss.networking.server.request;


import dev.xantha.vss.networking.server.diagnostics.ServerRequestStats;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.NbtSectionSerializer;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ColumnStorageReadPipeline {
    private final PlayerRequestRegistry playerRegistry;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final PersistentColumnLodStore persistentStore;
    private final PersistentColumnWriter persistentWriter;
    private final ServerRequestStats requestStats;
    private final DiskTaskRuntime diskRuntime;

    public ColumnStorageReadPipeline(
            PlayerRequestRegistry playerRegistry,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            PersistentColumnLodStore persistentStore,
            PersistentColumnWriter persistentWriter,
            ServerRequestStats requestStats,
            DiskTaskRuntime diskRuntime) {
        this.playerRegistry = playerRegistry;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.persistentStore = persistentStore;
        this.persistentWriter = persistentWriter;
        this.requestStats = requestStats;
        this.diskRuntime = diskRuntime;
    }

    public boolean submitLoadedColumn(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            LevelChunk chunk,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        return generationService.submitLoadedColumn(
                player.getUUID(),
                state,
                requestId,
                level,
                chunk,
                cx,
                cz,
                minimumTimestamp,
                priority);
    }

    public void submitStorageRead(
            ServerPlayer player,
            PlayerRequestState state,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            long dirtyTimestamp,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean priority) {
        if (VSSServerNetworking.isServerStopping()) {
            state.clearRequest(requestId);
            return;
        }
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        DiskReadContext readContext = new DiskReadContext(
                VSSServerNetworking.lifecycleEpoch(),
                playerId,
                level,
                state,
                requestId,
                cx,
                cz,
                columnTimestamp,
                preferLoadedColumn,
                allowGeneration,
                priority);
        boolean submitted = diskRuntime.submitManualRead(
                VSSServerConfig.CONFIG.diskReadQueueLimit,
                pendingRead -> readFromDisk(server, readContext, pendingRead, dirtyTimestamp),
                e -> {
                    readContext.requestState().clearRequest(readContext.requestId());
                    sendBackpressured(player, readContext.requestId());
                });
        if (submitted) {
            requestStats.recordDiskReadSubmitted();
        }
    }

    private void readFromDisk(
            MinecraftServer server,
            DiskReadContext readContext,
            DiskTaskRuntime.PendingDiskTask pendingRead,
            long dirtyTimestamp) {
        boolean handedOffToServer = false;
        try {
            if (VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
                return;
            }

            PersistentColumnLodStore.Entry storedData = persistentStore.read(
                    server,
                    readContext.level().dimension(),
                    readContext.cx(),
                    readContext.cz(),
                    dirtyTimestamp > 0L ? dirtyTimestamp : 0L);
            if (VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
                return;
            }

            DiskNbtReadResult diskNbtRead = readExistingChunkNbt(readContext, storedData);
            if (VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
                return;
            }

            server.execute(() -> finishDiskRead(
                    readContext,
                    pendingRead,
                    storedData,
                    diskNbtRead.columnData(),
                    diskNbtRead.failed()));
            handedOffToServer = true;
        } catch (RejectedExecutionException e) {
            readContext.requestState().clearRequest(readContext.requestId());
        } catch (Exception e) {
            readContext.requestState().clearRequest(readContext.requestId());
            VSSLogger.warn("Failed to finish VSS disk read at "
                    + readContext.cx() + ", " + readContext.cz() + ": " + e.getMessage());
        } finally {
            if (!handedOffToServer) {
                pendingRead.complete();
            }
        }
    }

    private DiskNbtReadResult readExistingChunkNbt(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData) {
        if (storedData != null
                || readContext.preferLoadedColumn()
                || !shouldReadExistingChunkNbt(readContext.allowGeneration())) {
            return DiskNbtReadResult.empty();
        }
        try {
            LoadedColumnData rawDiskData = NbtSectionSerializer.readAndSerializeSections(
                    readContext.level(),
                    readContext.level().getChunkSource().chunkMap,
                    readContext.cx(),
                    readContext.cz(),
                    VSSServerConfig.CONFIG.diskReadTimeoutMillis);
            if (rawDiskData != null && rawDiskData.sectionBytes() != null && rawDiskData.sizeBytes() > 0) {
                return new DiskNbtReadResult(EncodedColumnData.encode(rawDiskData, readContext.columnTimestamp()), false);
            }
            return DiskNbtReadResult.empty();
        } catch (Exception e) {
            VSSLogger.warn("Failed to read chunk NBT from disk at "
                    + readContext.cx() + ", " + readContext.cz() + ": " + e.getMessage());
            return new DiskNbtReadResult(null, true);
        }
    }

    private static boolean shouldReadExistingChunkNbt(boolean allowGeneration) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return shouldReadExistingChunkNbt(
                config.enableChunkNbtColumnSync,
                allowGeneration,
                config.enableChunkGeneration);
    }

    public static boolean shouldReadExistingChunkNbt(
            boolean enableChunkNbtColumnSync,
            boolean allowGeneration,
            boolean enableChunkGeneration) {
        return enableChunkNbtColumnSync || !allowGeneration || !enableChunkGeneration;
    }

    private void finishDiskRead(
            DiskReadContext readContext,
            DiskTaskRuntime.PendingDiskTask pendingRead,
            PersistentColumnLodStore.Entry storedData,
            EncodedColumnData diskData,
            boolean readFailed) {
        pendingRead.complete();
        if (VSSServerNetworking.isLifecycleStale(readContext.lifecycleEpoch())) {
            return;
        }
        if (readFailed) {
            requestStats.recordDiskReadFailure();
        }
        ServerLevel level = readContext.level();
        PlayerRequestState requestState = readContext.requestState();
        int requestId = readContext.requestId();
        int cx = readContext.cx();
        int cz = readContext.cz();
        long columnTimestamp = readContext.columnTimestamp();
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(readContext.playerId());
        if (player == null || !playerRegistry.isCurrent(readContext.playerId(), requestState)
                || requestState.consumeCancelled(requestId)) {
            return;
        }
        if (!player.serverLevel().dimension().equals(level.dimension())) {
            requestState.clearRequest(requestId);
            return;
        }
        if (!VSSServerNetworking.isColumnStillRelevant(player, level.dimension(), cx, cz)) {
            requestState.clearRequest(requestId);
            return;
        }
        long latestDirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), cx, cz);
        long effectiveColumnTimestamp = Math.max(columnTimestamp, latestDirtyTimestamp);
        if (storedData != null && storedData.columnData() != null && storedData.timestamp() >= latestDirtyTimestamp) {
            sendStoredColumn(readContext, storedData, player, effectiveColumnTimestamp);
            return;
        }
        if (readContext.preferLoadedColumn()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                if (submitLoadedColumn(player, requestState, level, chunk, requestId, cx, cz, effectiveColumnTimestamp, readContext.priority())) {
                    return;
                }
                requestState.clearRequest(requestId);
                sendRateLimited(player, requestId);
                return;
            }
        }
        if (latestDirtyTimestamp > columnTimestamp) {
            handleMissingDiskColumn(readContext, player, effectiveColumnTimestamp);
            return;
        }
        if (diskData == null || !diskData.hasBody()) {
            handleMissingDiskColumn(readContext, player, effectiveColumnTimestamp);
            return;
        }
        sendDiskColumn(readContext, player, diskData, effectiveColumnTimestamp);
    }

    private void sendStoredColumn(
            DiskReadContext readContext,
            PersistentColumnLodStore.Entry storedData,
            ServerPlayer player,
            long columnTimestamp) {
        ServerLevel level = readContext.level();
        EncodedColumnData columnData = storedData.columnData().withColumnStamp(
                Math.max(storedData.timestamp(), columnTimestamp));
        requestStats.recordDiskReadHit();
        VSSServerNetworking.queueColumn(player, readContext.requestState(), new VoxelColumnS2CPayload(
                readContext.requestId(),
                level.dimension(),
                columnData), readContext.priority());
        columnCache.put(level.dimension(), columnData);
    }

    private void handleMissingDiskColumn(DiskReadContext readContext, ServerPlayer player, long minimumTimestamp) {
        requestStats.recordDiskReadMiss();
        if (readContext.allowGeneration() && VSSServerConfig.CONFIG.enableChunkGeneration) {
            submitGeneration(
                    player,
                    readContext.requestState(),
                    readContext.level(),
                    readContext.requestId(),
                    readContext.cx(),
                    readContext.cz(),
                    minimumTimestamp);
            return;
        }
        readContext.requestState().clearRequest(readContext.requestId());
        sendNotGenerated(player, readContext.requestId());
    }

    private void sendDiskColumn(
            DiskReadContext readContext,
            ServerPlayer player,
            EncodedColumnData diskData,
            long columnTimestamp) {
        ServerLevel level = readContext.level();
        requestStats.recordDiskReadHit();
        EncodedColumnData encodedDiskData = diskData.withColumnStamp(columnTimestamp);
        VSSServerNetworking.queueColumn(player, readContext.requestState(), new VoxelColumnS2CPayload(
                readContext.requestId(),
                level.dimension(),
                encodedDiskData), readContext.priority());
        columnCache.put(level.dimension(), encodedDiskData);
        persistentWriter.write(level.getServer(), level.dimension(), encodedDiskData);
    }

    private void submitGeneration(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp) {
        if (VSSServerNetworking.isServerStopping()) {
            state.clearRequest(requestId);
            return;
        }
        boolean accepted = generationService.submitGeneration(player.getUUID(), state, requestId, level, cx, cz, minimumTimestamp);
        if (!accepted) {
            state.clearRequest(requestId);
            sendRateLimited(player, requestId);
        }
    }

    private static void sendRateLimited(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                        new int[] {requestId},
                        1));
    }

    private static void sendBackpressured(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_BACKPRESSURE},
                        new int[] {requestId},
                        1));
    }

    private static void sendNotGenerated(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {requestId},
                        1));
    }

    private record DiskReadContext(
            long lifecycleEpoch,
            UUID playerId,
            ServerLevel level,
            PlayerRequestState requestState,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean priority) {
    }

    private record DiskNbtReadResult(EncodedColumnData columnData, boolean failed) {
        static DiskNbtReadResult empty() {
            return new DiskNbtReadResult(null, false);
        }
    }
}
