package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;

public final class VSSServerNetworking {
    private static final Map<UUID, PlayerRequestState> PLAYER_STATES = new HashMap<>();
    private static final ChunkGenerationService GENERATION_SERVICE = new ChunkGenerationService(VSSServerConfig.CONFIG);
    private static final ColumnLodCache COLUMN_CACHE = new ColumnLodCache(VSSServerConfig.CONFIG);
    private static final PersistentColumnLodStore PERSISTENT_COLUMN_STORE = new PersistentColumnLodStore(VSSServerConfig.CONFIG);
    private static final AtomicLong totalColumnRequests = new AtomicLong();
    private static final AtomicLong totalDuplicateRequests = new AtomicLong();
    private static final AtomicLong totalDistanceRejectedRequests = new AtomicLong();
    private static final AtomicLong totalUpToDateResponses = new AtomicLong();
    private static final AtomicLong totalCacheHits = new AtomicLong();
    private static final AtomicLong totalDiskReadsSubmitted = new AtomicLong();
    private static final AtomicLong totalDiskReadHits = new AtomicLong();
    private static final AtomicLong totalDiskReadMisses = new AtomicLong();
    private static final AtomicLong totalDiskReadFailures = new AtomicLong();
    private static final AtomicInteger pendingDiskReads = new AtomicInteger();
    private static final AtomicInteger pendingPersistentWrites = new AtomicInteger();
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final int PRIORITY_SEND_COLUMNS_PER_TICK = 4;
    private static final long PRIORITY_SEND_BYTES_PER_TICK = 256L * 1024L;
    private static volatile long lastRequestDiagnosticNanos;
    private static volatile long lastSendDiagnosticNanos;
    private static boolean idleMemoryReleased = true;
    private static final ExecutorService DISK_EXECUTOR = Executors.newFixedThreadPool(
            VSSServerConfig.CONFIG.diskReaderThreads,
            task -> {
                Thread thread = new Thread(task, "VSS-DiskReader");
                thread.setDaemon(true);
                return thread;
            });

    private VSSServerNetworking() {
    }

    public static boolean isRegistered(ServerPlayer player) {
        return PLAYER_STATES.containsKey(player.getUUID());
    }

    static boolean hasRegisteredPlayers() {
        return !PLAYER_STATES.isEmpty();
    }

    static boolean hasRegisteredPlayers(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (isRegistered(player)) {
                return true;
            }
        }
        return false;
    }

    public static void refreshSessionConfigs(MinecraftServer server) {
        for (UUID uuid : PLAYER_STATES.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                sendSessionConfig(player);
            }
        }
    }

    public static SessionConfigS2CPayload registerIntegratedHost(ServerPlayer player, int clientProtocolVersion, int clientCapabilities) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean compatible = clientProtocolVersion == VSSConstants.PROTOCOL_VERSION;
        boolean enabled = config.enabled && compatible;
        if (compatible && enabled) {
            PlayerRequestState state = new PlayerRequestState();
            state.setClientCapabilities(clientCapabilities);
            PLAYER_STATES.put(player.getUUID(), state);
            idleMemoryReleased = false;
            VSSLogger.info("Integrated host " + player.getGameProfile().getName() + " registered for VSS LOD sync");
        } else if (!compatible) {
            VSSLogger.warn("Integrated host " + player.getGameProfile().getName()
                    + " has incompatible VSS protocol " + clientProtocolVersion);
        }
        return createSessionConfig(enabled);
    }

    static String generationDiagnostics() {
        return GENERATION_SERVICE.diagnostics();
    }

    static Component generationDiagnosticsComponent() {
        return GENERATION_SERVICE.diagnosticsComponent(storageDiagnosticsComponent());
    }

    private static Component storageDiagnosticsComponent() {
        return Component.translatable(
                "vss.command.generation.storage.details",
                totalColumnRequests.get(),
                totalDuplicateRequests.get(),
                totalDistanceRejectedRequests.get(),
                totalUpToDateResponses.get(),
                totalCacheHits.get(),
                totalDiskReadsSubmitted.get(),
                pendingDiskReads.get(),
                totalDiskReadHits.get(),
                totalDiskReadMisses.get(),
                totalDiskReadFailures.get());
    }

    static String diagnostics() {
        int queuedPayloads = 0;
        int priorityQueuedPayloads = 0;
        long queuedBytes = 0L;
        for (PlayerRequestState state : PLAYER_STATES.values()) {
            queuedPayloads += state.queuedPayloadCount();
            priorityQueuedPayloads += state.priorityQueuedPayloadCount();
            queuedBytes += state.queuedBytes();
        }
        return String.format(
                "players=%d, queuedColumns=%d, priorityQueuedColumns=%d, queuedBytes=%.2f MiB, generation={%s}, storage={requests=%d, duplicates=%d, distanceRejected=%d, upToDate=%d, cacheHits=%d, diskSubmitted=%d, diskPending=%d, diskHits=%d, diskMisses=%d, diskFailures=%d}, dirty={%s}, cache={%s}",
                PLAYER_STATES.size(),
                queuedPayloads,
                priorityQueuedPayloads,
                queuedBytes / (double) VSSServerConfig.BYTES_PER_MIB,
                GENERATION_SERVICE.diagnostics(),
                totalColumnRequests.get(),
                totalDuplicateRequests.get(),
                totalDistanceRejectedRequests.get(),
                totalUpToDateResponses.get(),
                totalCacheHits.get(),
                totalDiskReadsSubmitted.get(),
                pendingDiskReads.get(),
                totalDiskReadHits.get(),
                totalDiskReadMisses.get(),
                totalDiskReadFailures.get(),
                DirtyColumnBroadcaster.diagnostics(),
                COLUMN_CACHE.diagnostics() + ", " + PERSISTENT_COLUMN_STORE.diagnostics()
                        + ", persistentWritePending=" + pendingPersistentWrites.get());
    }

    static Component diagnosticsComponent() {
        int queuedPayloads = 0;
        long queuedBytes = 0L;
        for (PlayerRequestState state : PLAYER_STATES.values()) {
            queuedPayloads += state.queuedPayloadCount();
            queuedBytes += state.queuedBytes();
        }
        return Component.translatable(
                "vss.command.stats.details",
                PLAYER_STATES.size(),
                queuedPayloads,
                String.format(Locale.ROOT, "%.2f", queuedBytes / (double) VSSServerConfig.BYTES_PER_MIB),
                GENERATION_SERVICE.diagnosticsComponent(storageDiagnosticsComponent()),
                DirtyColumnBroadcaster.diagnosticsComponent());
    }

    public static void handleHandshake(HandshakeC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }

        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean compatible = payload.protocolVersion() == VSSConstants.PROTOCOL_VERSION;
        boolean enabled = config.enabled && compatible;
        sendSessionConfig(player, enabled);

        if (!compatible) {
            VSSLogger.warn("Player " + player.getGameProfile().getName() + " has incompatible VSS protocol " + payload.protocolVersion());
            return;
        }
        if (enabled) {
            PlayerRequestState state = new PlayerRequestState();
            state.setClientCapabilities(payload.capabilities());
            PLAYER_STATES.put(player.getUUID(), state);
            idleMemoryReleased = false;
            VSSLogger.info("Player " + player.getGameProfile().getName() + " registered for VSS LOD sync");
        }
    }

    private static void sendSessionConfig(ServerPlayer player) {
        sendSessionConfig(player, VSSServerConfig.CONFIG.enabled);
    }

    private static void sendSessionConfig(ServerPlayer player, boolean enabled) {
        VSSNetworking.sendToPlayer(player, createSessionConfig(enabled));
    }

    private static SessionConfigS2CPayload createSessionConfig(boolean enabled) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return new SessionConfigS2CPayload(
                VSSConstants.PROTOCOL_VERSION,
                enabled,
                config.lodDistanceChunks,
                VSSConstants.CAPABILITY_VOXEL_COLUMNS,
                config.syncOnLoadRateLimitPerPlayer,
                config.syncOnLoadConcurrencyLimitPerPlayer,
                config.generationRateLimitPerPlayer,
                config.generationConcurrencyLimitPerPlayer,
                config.enableChunkGeneration,
                config.bytesPerSecondLimitPerPlayer);
    }

    public static void handleBatchRequest(BatchChunkRequestC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        handleBatchRequest(player, payload);
    }

    public static void handleIntegratedBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        if (player == null || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        handleBatchRequest(player, payload);
    }

    private static void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state == null) {
            return;
        }

        ServerLevel level = player.serverLevel();
        byte[] responseTypes = new byte[payload.count()];
        int[] responseIds = new int[payload.count()];
        int responseCount = 0;
        logRequestBatch(player, payload.count());

        for (int i = 0; i < payload.count(); i++) {
            totalColumnRequests.incrementAndGet();
            int requestId = payload.requestIds()[i];
            if (state.consumeCancelled(requestId)) {
                continue;
            }

            long packed = payload.packedPositions()[i];
            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            if (i == 0) {
                VSSLogger.debug("Processing batch request from " + player.getGameProfile().getName()
                        + ": count=" + payload.count() + ", first chunk=" + cx + "," + cz);
            }

            if (!state.beginRequest(requestId, packed)) {
                totalDuplicateRequests.incrementAndGet();
                continue;
            }
            int playerCx = player.getBlockX() >> 4;
            int playerCz = player.getBlockZ() >> 4;
            int maxDistance = VSSServerConfig.CONFIG.lodDistanceChunks + VSSConstants.LOD_DISTANCE_BUFFER;
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDistance) {
                totalDistanceRejectedRequests.incrementAndGet();
                responseTypes[responseCount] = VSSConstants.RESPONSE_RATE_LIMITED;
                responseIds[responseCount++] = requestId;
                state.clearRequest(requestId);
                continue;
            }

            long clientTimestamp = payload.clientTimestamps()[i];
            boolean allowGeneration = i < payload.allowGeneration().length && payload.allowGeneration()[i];
            long dirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), cx, cz);
            boolean priorityRefresh = clientTimestamp > 0L;
            ColumnLodCache.Entry cachedColumn = COLUMN_CACHE.get(level.dimension(), cx, cz);
            if (cachedColumn != null && !cachedColumn.completeColumn()) {
                COLUMN_CACHE.invalidate(level.dimension(), cx, cz);
                cachedColumn = null;
            }
            if (cachedColumn != null) {
                long requiredTimestamp = Math.max(cachedColumn.timestamp(), dirtyTimestamp);
                if (clientTimestamp >= requiredTimestamp) {
                    totalUpToDateResponses.incrementAndGet();
                    responseTypes[responseCount] = VSSConstants.RESPONSE_UP_TO_DATE;
                    responseIds[responseCount++] = requestId;
                    state.clearRequest(requestId);
                } else if (dirtyTimestamp > cachedColumn.timestamp()) {
                    COLUMN_CACHE.invalidate(level.dimension(), cx, cz);
                } else {
                    totalCacheHits.incrementAndGet();
                    queueColumn(player, state, new VoxelColumnS2CPayload(
                            requestId,
                            cachedColumn.chunkX(),
                            cachedColumn.chunkZ(),
                            level.dimension(),
                            requiredTimestamp,
                            cachedColumn.sectionBytes(),
                            cachedColumn.completeColumn()), priorityRefresh);
                    continue;
                }
                if (clientTimestamp >= requiredTimestamp || dirtyTimestamp <= cachedColumn.timestamp()) {
                    continue;
                }
            }

            long columnTimestamp = Math.max(VSSConstants.epochMillis(), dirtyTimestamp);
            if (clientTimestamp >= columnTimestamp) {
                totalUpToDateResponses.incrementAndGet();
                responseTypes[responseCount] = VSSConstants.RESPONSE_UP_TO_DATE;
                responseIds[responseCount++] = requestId;
                state.clearRequest(requestId);
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                if (submitLoadedColumn(player, state, level, chunk, requestId, cx, cz, columnTimestamp, priorityRefresh)) {
                    if (i == 0) {
                        VSSLogger.debug("Submitted loaded column for " + cx + "," + cz);
                    }
                    continue;
                }
                submitStorageRead(player, state, requestId, cx, cz, columnTimestamp, dirtyTimestamp, true, allowGeneration, priorityRefresh);
                continue;
            }

            if (allowGeneration && i == 0) {
                VSSLogger.debug("Chunk not loaded at " + cx + "," + cz + ", allowGeneration=" + allowGeneration);
            }
            submitStorageRead(player, state, requestId, cx, cz, columnTimestamp, dirtyTimestamp, false, allowGeneration, priorityRefresh);
        }

        if (responseCount > 0) {
            byte[] trimmedTypes = new byte[responseCount];
            int[] trimmedIds = new int[responseCount];
            System.arraycopy(responseTypes, 0, trimmedTypes, 0, responseCount);
            System.arraycopy(responseIds, 0, trimmedIds, 0, responseCount);
            VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(trimmedTypes, trimmedIds, responseCount));
        }
    }

    private static boolean submitLoadedColumn(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            LevelChunk chunk,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        return GENERATION_SERVICE.submitLoadedColumn(
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

    private static void submitStorageRead(
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
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        try {
            totalDiskReadsSubmitted.incrementAndGet();
            pendingDiskReads.incrementAndGet();
            DISK_EXECUTOR.execute(() -> {
                PersistentColumnLodStore.Entry storedData = PERSISTENT_COLUMN_STORE.read(
                        server,
                        level.dimension(),
                        cx,
                        cz,
                        dirtyTimestamp > 0L ? dirtyTimestamp : 0L);
                LoadedColumnData diskData = null;
                boolean failed = false;
                if (storedData == null && !preferLoadedColumn && VSSServerConfig.CONFIG.enableChunkNbtColumnSync) {
                    try {
                        diskData = NbtSectionSerializer.readAndSerializeSections(
                                level,
                                level.getChunkSource().chunkMap,
                                cx,
                                cz,
                                VSSServerConfig.CONFIG.diskReadTimeoutMillis);
                    } catch (Exception e) {
                        failed = true;
                        VSSLogger.warn("Failed to read chunk NBT from disk at " + cx + ", " + cz + ": " + e.getMessage());
                    }
                }

                LoadedColumnData result = diskData;
                PersistentColumnLodStore.Entry storedResult = storedData;
                boolean readFailed = failed;
                server.execute(() -> finishDiskRead(
                        playerId,
                        level,
                        state,
                        requestId,
                        cx,
                        cz,
                        columnTimestamp,
                        storedResult,
                        result,
                        readFailed,
                        preferLoadedColumn,
                        allowGeneration,
                        priority));
            });
        } catch (RejectedExecutionException e) {
            pendingDiskReads.decrementAndGet();
            state.clearRequest(requestId);
            VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(new byte[] {VSSConstants.RESPONSE_RATE_LIMITED}, new int[] {requestId}, 1));
        }
    }

    private static void finishDiskRead(
            UUID playerId,
            ServerLevel level,
            PlayerRequestState requestState,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            PersistentColumnLodStore.Entry storedData,
            LoadedColumnData diskData,
            boolean readFailed,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean priority) {
        pendingDiskReads.decrementAndGet();
        if (readFailed) {
            totalDiskReadFailures.incrementAndGet();
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null || PLAYER_STATES.get(playerId) != requestState || requestState.consumeCancelled(requestId)) {
            return;
        }
        if (!player.serverLevel().dimension().equals(level.dimension())) {
            requestState.clearRequest(requestId);
            return;
        }
        if (storedData != null && storedData.columnData() != null) {
            LoadedColumnData columnData = storedData.columnData();
            long storedTimestamp = Math.max(storedData.timestamp(), columnTimestamp);
            queueColumn(player, requestState, new VoxelColumnS2CPayload(
                    requestId,
                    columnData.chunkX(),
                    columnData.chunkZ(),
                    level.dimension(),
                    storedTimestamp,
                    columnData.sectionBytes(),
                    columnData.completeColumn()), priority);
            COLUMN_CACHE.put(level.dimension(), columnData, storedTimestamp);
            return;
        }
        if (preferLoadedColumn) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                if (submitLoadedColumn(player, requestState, level, chunk, requestId, cx, cz, columnTimestamp, priority)) {
                    return;
                }
                requestState.clearRequest(requestId);
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                        new int[] {requestId},
                        1));
                return;
            }
        }
        if (diskData == null || diskData.sectionBytes() == null || diskData.sizeBytes() == 0 || !diskData.completeColumn()) {
            totalDiskReadMisses.incrementAndGet();
            if (allowGeneration && VSSServerConfig.CONFIG.enableChunkGeneration) {
                submitGeneration(player, requestState, level, requestId, cx, cz);
            } else {
                requestState.clearRequest(requestId);
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(new byte[] {VSSConstants.RESPONSE_NOT_GENERATED}, new int[] {requestId}, 1));
            }
            return;
        }
        totalDiskReadHits.incrementAndGet();
        queueColumn(player, requestState, new VoxelColumnS2CPayload(
                requestId,
                cx,
                cz,
                level.dimension(),
                columnTimestamp,
                diskData.sectionBytes(),
                diskData.completeColumn()), priority);
        COLUMN_CACHE.put(level.dimension(), diskData, columnTimestamp);
        writePersistentColumn(level.getServer(), level.dimension(), diskData, columnTimestamp);
    }

    private static void submitGeneration(ServerPlayer player, PlayerRequestState state, ServerLevel level, int requestId, int cx, int cz) {
        boolean accepted = GENERATION_SERVICE.submitGeneration(player.getUUID(), state, requestId, level, cx, cz);
        if (!accepted) {
            state.clearRequest(requestId);
            VSSNetworking.sendToPlayer(
                    player,
                    new BatchResponseS2CPayload(
                            new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                            new int[] {requestId},
                            1));
        }
    }

    private static void flushGeneratedColumns(MinecraftServer server) {
        for (ChunkGenerationService.GenerationResult result : GENERATION_SERVICE.tick(server)) {
            PlayerRequestState state = PLAYER_STATES.get(result.playerUuid());
            ServerPlayer player = server.getPlayerList().getPlayer(result.playerUuid());
            if (state == null || state != result.requestState() || player == null || state.consumeCancelled(result.requestId())) {
                continue;
            }

            if (!player.serverLevel().dimension().equals(result.dimension())) {
                state.clearRequest(result.requestId());
                continue;
            }

            LoadedColumnData columnData = result.columnData();
            if (result.notGenerated() || columnData == null) {
                state.clearRequest(result.requestId());
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {result.requestId()},
                        1));
                continue;
            }

            if (columnData.sectionBytes() == null || columnData.sizeBytes() == 0 || !columnData.completeColumn()) {
                state.clearRequest(result.requestId());
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {result.requestId()},
                        1));
                continue;
            }

            queueColumn(player, state, new VoxelColumnS2CPayload(
                    result.requestId(),
                    columnData.chunkX(),
                    columnData.chunkZ(),
                    result.dimension(),
                    result.columnTimestamp(),
                    columnData.sectionBytes(),
                    columnData.completeColumn()), result.priority());
            COLUMN_CACHE.put(result.dimension(), columnData, result.columnTimestamp());
            writePersistentColumn(server, result.dimension(), columnData, result.columnTimestamp());
        }
    }

    static void invalidateCachedColumn(ServerLevel level, int cx, int cz) {
        COLUMN_CACHE.invalidate(level.dimension(), cx, cz);
        invalidatePersistentColumn(level.getServer(), level.dimension(), cx, cz);
    }

    private static void writePersistentColumn(MinecraftServer server, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, LoadedColumnData columnData, long timestamp) {
        if (!PERSISTENT_COLUMN_STORE.enabled()) {
            return;
        }
        if (pendingPersistentWrites.get() >= VSSServerConfig.CONFIG.persistentColumnCacheWriteQueueLimit) {
            return;
        }
        pendingPersistentWrites.incrementAndGet();
        try {
            DISK_EXECUTOR.execute(() -> {
                try {
                    PERSISTENT_COLUMN_STORE.write(server, dimension, columnData, timestamp);
                } finally {
                    pendingPersistentWrites.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            pendingPersistentWrites.decrementAndGet();
            VSSLogger.debug("Persistent LOD write rejected: " + e.getMessage());
        }
    }

    private static void invalidatePersistentColumn(MinecraftServer server, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int cx, int cz) {
        if (!PERSISTENT_COLUMN_STORE.enabled()) {
            return;
        }
        try {
            DISK_EXECUTOR.execute(() -> PERSISTENT_COLUMN_STORE.invalidate(server, dimension, cx, cz));
        } catch (RejectedExecutionException e) {
            VSSLogger.debug("Persistent LOD invalidation rejected: " + e.getMessage());
        }
    }

    private static void queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload) {
        queueColumn(player, state, payload, false);
    }

    private static void queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload, boolean priority) {
        payload.setAllowZstdEncoding(state.supportsZstdColumns());
        if (!state.enqueue(payload, priority)) {
            VSSNetworking.sendToPlayer(
                    player,
                    new BatchResponseS2CPayload(
                            new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                            new int[] {payload.requestId()},
                            1));
        }
    }

    public static void handleCancel(CancelRequestC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        ServerPlayer player = contextSupplier.get().getSender();
        if (player != null) {
            handleIntegratedCancel(player, payload);
        }
    }

    public static void handleIntegratedCancel(ServerPlayer player, CancelRequestC2SPayload payload) {
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state != null) {
            state.cancel(payload.requestId());
            GENERATION_SERVICE.cancelRequest(player.getUUID(), payload.requestId());
        }
    }

    public static void handleBandwidthUpdate(BandwidthUpdateC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        ServerPlayer player = contextSupplier.get().getSender();
        if (player != null) {
            handleIntegratedBandwidthUpdate(player, payload);
        }
    }

    public static void handleIntegratedBandwidthUpdate(ServerPlayer player, BandwidthUpdateC2SPayload payload) {
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state != null) {
            state.setDesiredBandwidth(payload.desiredRate());
        }
    }

    private static void flushQueuedColumns(MinecraftServer server) {
        long configuredLimit = VSSServerConfig.CONFIG.bytesPerSecondLimitPerPlayer;
        for (Map.Entry<UUID, PlayerRequestState> entry : PLAYER_STATES.entrySet()) {
            PlayerRequestState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }

            long effectiveLimit = Math.min(configuredLimit, state.desiredBandwidth());
            int playerCx = player.getBlockX() >> 4;
            int playerCz = player.getBlockZ() >> 4;
            int priorityColumnsSent = 0;
            long priorityBytesSent = 0L;
            while (priorityColumnsSent < PRIORITY_SEND_COLUMNS_PER_TICK) {
                PlayerRequestState.QueuedPayload queued = state.peekPriorityQueuedPayload(playerCx, playerCz);
                if (queued == null) {
                    break;
                }
                if (priorityColumnsSent > 0
                        && priorityBytesSent + queued.estimatedBytes() > PRIORITY_SEND_BYTES_PER_TICK) {
                    break;
                }
                state.pollPriorityQueuedPayload(playerCx, playerCz);
                if (state.consumeCancelled(queued.payload().requestId())) {
                    continue;
                }
                VSSNetworking.sendToPlayer(player, queued.payload());
                state.recordSend(queued.estimatedBytes());
                state.clearRequest(queued.payload().requestId());
                priorityColumnsSent++;
                priorityBytesSent += queued.estimatedBytes();
                logColumnSend(player, queued.payload(), state);
            }

            while (state.queuedPayloadCount() > 0) {
                PlayerRequestState.QueuedPayload queued = state.peekQueuedPayload(playerCx, playerCz);
                if (queued == null || !state.canSend(effectiveLimit)) {
                    break;
                }
                state.pollQueuedPayload(playerCx, playerCz);
                if (state.consumeCancelled(queued.payload().requestId())) {
                    continue;
                }
                VSSNetworking.sendToPlayer(player, queued.payload());
                state.recordSend(queued.estimatedBytes());
                state.clearRequest(queued.payload().requestId());
                logColumnSend(player, queued.payload(), state);
            }
        }
    }

    private static void logRequestBatch(ServerPlayer player, int count) {
        long now = System.nanoTime();
        if (now - lastRequestDiagnosticNanos < DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastRequestDiagnosticNanos = now;
        VSSLogger.debug("LOD requests received from " + player.getGameProfile().getName()
                + ": batch=" + count
                + ", total=" + totalColumnRequests.get()
                + ", diskPending=" + pendingDiskReads.get()
                + ", cacheHits=" + totalCacheHits.get()
                + ", diskHits=" + totalDiskReadHits.get()
                + ", diskMisses=" + totalDiskReadMisses.get()
                + ", distanceRejected=" + totalDistanceRejectedRequests.get());
    }

    private static void logColumnSend(ServerPlayer player, VoxelColumnS2CPayload payload, PlayerRequestState state) {
        long now = System.nanoTime();
        if (now - lastSendDiagnosticNanos < DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastSendDiagnosticNanos = now;
        VSSLogger.debug("LOD column sent to " + player.getGameProfile().getName()
                + ": chunk=" + payload.chunkX() + "," + payload.chunkZ()
                + ", bytes=" + payload.estimatedBytes()
                + ", queued=" + state.queuedPayloadCount()
                + ", queuedBytes=" + state.queuedBytes()
                + ", totalSentBytes=" + state.totalBytesSent());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerRequestState state = PLAYER_STATES.remove(player.getUUID());
            if (state != null) {
                state.clearAll();
            }
            GENERATION_SERVICE.removePlayer(player.getUUID());
            if (PLAYER_STATES.isEmpty()) {
                releaseIdleMemory();
            }
        }
    }

    private static void releaseIdleMemory() {
        if (idleMemoryReleased) {
            return;
        }
        GENERATION_SERVICE.releaseIdleMemory();
        DirtyColumnBroadcaster.clear();
        COLUMN_CACHE.clear();
        idleMemoryReleased = true;
        VSSLogger.info("Released idle VSS memory after the last VSS player disconnected");
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (PLAYER_STATES.isEmpty()) {
                releaseIdleMemory();
                return;
            }
            MinecraftServer server = event.getServer();
            flushGeneratedColumns(server);
            flushQueuedColumns(server);
            DirtyColumnBroadcaster.tick(server);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        idleMemoryReleased = false;
        releaseIdleMemory();
        PLAYER_STATES.clear();
    }
}
