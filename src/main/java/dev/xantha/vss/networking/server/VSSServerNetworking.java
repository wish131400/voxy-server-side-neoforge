package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
    private static final AtomicLong serverLifecycleEpoch = new AtomicLong();
    private static final AtomicLong configRevision = new AtomicLong(1L);
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final int PRIORITY_SEND_COLUMNS_PER_TICK = 4;
    private static final long PRIORITY_SEND_BYTES_PER_TICK = 256L * 1024L;
    private static volatile long lastRequestDiagnosticNanos;
    private static volatile long lastSendDiagnosticNanos;
    private static volatile boolean serverStopping;
    private static boolean idleMemoryReleased = true;
    private static final ThreadPoolExecutor DISK_READ_EXECUTOR = createDiskExecutor(
            "VSS-DiskReader",
            VSSServerConfig.CONFIG.diskReaderThreads);
    private static final ThreadPoolExecutor DISK_WRITE_EXECUTOR = createDiskExecutor("VSS-DiskWriter", 1);

    private VSSServerNetworking() {
    }

    private static ThreadPoolExecutor createDiskExecutor(String threadName, int threads) {
        int clampedThreads = Math.max(
                VSSServerConfig.MIN_DISK_READER_THREADS,
                Math.min(VSSServerConfig.MAX_DISK_READER_THREADS, threads));
        AtomicInteger threadId = new AtomicInteger();
        return new ThreadPoolExecutor(
                clampedThreads,
                clampedThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, threadName + "-" + threadId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static void resizeDiskReadExecutor() {
        int desiredThreads = Math.max(
                VSSServerConfig.MIN_DISK_READER_THREADS,
                Math.min(VSSServerConfig.MAX_DISK_READER_THREADS, VSSServerConfig.CONFIG.diskReaderThreads));
        int currentThreads = DISK_READ_EXECUTOR.getCorePoolSize();
        if (currentThreads == desiredThreads) {
            return;
        }
        if (desiredThreads > currentThreads) {
            DISK_READ_EXECUTOR.setMaximumPoolSize(desiredThreads);
            DISK_READ_EXECUTOR.setCorePoolSize(desiredThreads);
        } else {
            DISK_READ_EXECUTOR.setCorePoolSize(desiredThreads);
            DISK_READ_EXECUTOR.setMaximumPoolSize(desiredThreads);
        }
        VSSLogger.info("VSS disk reader threads resized to " + desiredThreads);
    }

    private static void decrementPendingDiskReads() {
        pendingDiskReads.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static void decrementPendingPersistentWrites() {
        pendingPersistentWrites.updateAndGet(value -> Math.max(0, value - 1));
    }

    public static boolean isRegistered(ServerPlayer player) {
        return !serverStopping && PLAYER_STATES.containsKey(player.getUUID());
    }

    static boolean hasRegisteredPlayers() {
        return !serverStopping && !PLAYER_STATES.isEmpty();
    }

    static boolean hasRegisteredPlayers(ServerLevel level) {
        if (serverStopping) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (isRegistered(player)) {
                return true;
            }
        }
        return false;
    }

    public static void refreshSessionConfigs(MinecraftServer server) {
        if (serverStopping) {
            return;
        }
        for (UUID uuid : PLAYER_STATES.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                sendSessionConfig(player);
            }
        }
    }

    public static void bumpAndRefreshSessionConfigs(MinecraftServer server) {
        applyRuntimeConfig();
        configRevision.incrementAndGet();
        refreshSessionConfigs(server);
    }

    public static void applyRuntimeConfig() {
        resizeDiskReadExecutor();
    }

    public static SessionConfigS2CPayload refreshIntegratedHostSession(ServerPlayer player, int clientProtocolVersion, int clientCapabilities) {
        return registerIntegratedHost(player, clientProtocolVersion, clientCapabilities);
    }

    public static SessionConfigS2CPayload registerIntegratedHost(ServerPlayer player, int clientProtocolVersion, int clientCapabilities) {
        if (serverStopping) {
            return createSessionConfig(false);
        }
        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean compatible = isCompatibleClient(clientProtocolVersion, clientCapabilities);
        boolean enabled = config.enabled && compatible;
        if (compatible && enabled) {
            PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
            if (state == null) {
                state = new PlayerRequestState();
                PLAYER_STATES.put(player.getUUID(), state);
                VSSLogger.info("Integrated host " + player.getGameProfile().getName() + " registered for VSS LOD sync");
            }
            state.setClientCapabilities(clientCapabilities);
            idleMemoryReleased = false;
        } else if (!compatible) {
            logIncompatibleClient("Integrated host " + player.getGameProfile().getName(), clientProtocolVersion, clientCapabilities);
        }
        return createSessionConfig(enabled);
    }

    static String generationDiagnostics() {
        return GENERATION_SERVICE.diagnostics();
    }

    static String storageDiagnostics() {
        return "diskReaders=" + DISK_READ_EXECUTOR.getCorePoolSize()
                + ", diskReadQueue=" + DISK_READ_EXECUTOR.getQueue().size()
                + ", diskWriteQueue=" + DISK_WRITE_EXECUTOR.getQueue().size()
                + ", diskPending=" + pendingDiskReads.get()
                + ", persistentWritePending=" + pendingPersistentWrites.get();
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

    public static void handleHandshake(HandshakeC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || serverStopping) {
            return;
        }

        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean compatible = isCompatibleClient(payload.protocolVersion(), payload.capabilities());
        boolean enabled = config.enabled && compatible;
        sendSessionConfig(player, enabled);

        if (!compatible) {
            logIncompatibleClient("Player " + player.getGameProfile().getName(), payload.protocolVersion(), payload.capabilities());
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
                enabled && !serverStopping,
                config.lodDistanceChunks,
                serverCapabilities(),
                config.syncOnLoadRateLimitPerPlayer,
                config.syncOnLoadConcurrencyLimitPerPlayer,
                config.generationRateLimitPerPlayer,
                config.generationConcurrencyLimitPerPlayer,
                config.enableChunkGeneration,
                config.bandwidthBytesPerSecond(),
                configRevision.get());
    }

    public static void handleBatchRequest(BatchChunkRequestC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || serverStopping || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        handleBatchRequest(player, payload);
    }

    public static void handleIntegratedBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        if (player == null || serverStopping || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        if (!PLAYER_STATES.containsKey(player.getUUID())) {
            registerIntegratedHost(
                    player,
                    VSSConstants.PROTOCOL_VERSION,
                    VSSConstants.CAPABILITY_VOXEL_COLUMNS | VSSConstants.CAPABILITY_ZSTD_COLUMNS);
        }
        handleBatchRequest(player, payload);
    }

    private static void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        if (serverStopping) {
            return;
        }
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
                            level.dimension(),
                            cachedColumn.columnData().withColumnStamp(requiredTimestamp)), priorityRefresh);
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
        if (serverStopping) {
            state.clearRequest(requestId);
            return;
        }
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        long lifecycleEpoch = serverLifecycleEpoch.get();
        try {
            totalDiskReadsSubmitted.incrementAndGet();
            pendingDiskReads.incrementAndGet();
            DISK_READ_EXECUTOR.execute(() -> {
                if (serverStopping || lifecycleEpoch != serverLifecycleEpoch.get()) {
                    decrementPendingDiskReads();
                    return;
                }

                PersistentColumnLodStore.Entry storedData = PERSISTENT_COLUMN_STORE.read(
                        server,
                        level.dimension(),
                        cx,
                        cz,
                        dirtyTimestamp > 0L ? dirtyTimestamp : 0L);
                if (serverStopping || lifecycleEpoch != serverLifecycleEpoch.get()) {
                    decrementPendingDiskReads();
                    return;
                }

                EncodedColumnData diskData = null;
                boolean failed = false;
                if (storedData == null && !preferLoadedColumn && shouldReadExistingChunkNbt(allowGeneration)) {
                    try {
                        LoadedColumnData rawDiskData = NbtSectionSerializer.readAndSerializeSections(
                                level,
                                level.getChunkSource().chunkMap,
                                cx,
                                cz,
                                VSSServerConfig.CONFIG.diskReadTimeoutMillis);
                        if (rawDiskData != null && rawDiskData.sectionBytes() != null && rawDiskData.sizeBytes() > 0) {
                            diskData = EncodedColumnData.encodeZstd(rawDiskData, columnTimestamp);
                        }
                    } catch (Exception e) {
                        failed = true;
                        VSSLogger.warn("Failed to read chunk NBT from disk at " + cx + ", " + cz + ": " + e.getMessage());
                    }
                }

                EncodedColumnData result = diskData;
                PersistentColumnLodStore.Entry storedResult = storedData;
                boolean readFailed = failed;
                if (serverStopping || lifecycleEpoch != serverLifecycleEpoch.get()) {
                    decrementPendingDiskReads();
                    return;
                }
                server.execute(() -> finishDiskRead(
                        lifecycleEpoch,
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
            decrementPendingDiskReads();
            state.clearRequest(requestId);
            VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(new byte[] {VSSConstants.RESPONSE_RATE_LIMITED}, new int[] {requestId}, 1));
        }
    }

    private static boolean shouldReadExistingChunkNbt(boolean allowGeneration) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return config.enableChunkNbtColumnSync || !allowGeneration || !config.enableChunkGeneration;
    }

    private static void finishDiskRead(
            long lifecycleEpoch,
            UUID playerId,
            ServerLevel level,
            PlayerRequestState requestState,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            PersistentColumnLodStore.Entry storedData,
            EncodedColumnData diskData,
            boolean readFailed,
            boolean preferLoadedColumn,
            boolean allowGeneration,
            boolean priority) {
        decrementPendingDiskReads();
        if (serverStopping || lifecycleEpoch != serverLifecycleEpoch.get()) {
            return;
        }
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
            EncodedColumnData columnData = storedData.columnData().withColumnStamp(Math.max(storedData.timestamp(), columnTimestamp));
            queueColumn(player, requestState, new VoxelColumnS2CPayload(
                    requestId,
                    level.dimension(),
                    columnData), priority);
            COLUMN_CACHE.put(level.dimension(), columnData);
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
        if (diskData == null || !diskData.hasBody()) {
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
        EncodedColumnData encodedDiskData = diskData.withColumnStamp(columnTimestamp);
        queueColumn(player, requestState, new VoxelColumnS2CPayload(
                requestId,
                level.dimension(),
                encodedDiskData), priority);
        COLUMN_CACHE.put(level.dimension(), encodedDiskData);
        writePersistentColumn(level.getServer(), level.dimension(), encodedDiskData);
    }

    private static void submitGeneration(ServerPlayer player, PlayerRequestState state, ServerLevel level, int requestId, int cx, int cz) {
        if (serverStopping) {
            state.clearRequest(requestId);
            return;
        }
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
        if (serverStopping) {
            return;
        }
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

            EncodedColumnData columnData = result.columnData();
            if (result.notGenerated() || columnData == null) {
                state.clearRequest(result.requestId());
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {result.requestId()},
                        1));
                continue;
            }

            if (!columnData.hasBody() || !columnData.completeColumn()) {
                state.clearRequest(result.requestId());
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {result.requestId()},
                        1));
                continue;
            }

            queueColumn(player, state, new VoxelColumnS2CPayload(
                    result.requestId(),
                    result.dimension(),
                    columnData), result.priority());
            COLUMN_CACHE.put(result.dimension(), columnData);
            writePersistentColumn(server, result.dimension(), columnData);
        }
    }

    static void invalidateCachedColumn(ServerLevel level, int cx, int cz) {
        if (serverStopping) {
            return;
        }
        COLUMN_CACHE.invalidate(level.dimension(), cx, cz);
        invalidatePersistentColumn(level.getServer(), level.dimension(), cx, cz);
    }

    private static void writePersistentColumn(MinecraftServer server, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, EncodedColumnData columnData) {
        if (serverStopping || !PERSISTENT_COLUMN_STORE.enabled()) {
            return;
        }
        if (pendingPersistentWrites.get() >= VSSServerConfig.CONFIG.persistentColumnCacheWriteQueueLimit) {
            return;
        }
        long lifecycleEpoch = serverLifecycleEpoch.get();
        pendingPersistentWrites.incrementAndGet();
        try {
            DISK_WRITE_EXECUTOR.execute(() -> {
                try {
                    if (!serverStopping && lifecycleEpoch == serverLifecycleEpoch.get()) {
                        PERSISTENT_COLUMN_STORE.write(server, dimension, columnData);
                    }
                } finally {
                    decrementPendingPersistentWrites();
                }
            });
        } catch (RejectedExecutionException e) {
            decrementPendingPersistentWrites();
            VSSLogger.debug("Persistent LOD write rejected: " + e.getMessage());
        }
    }

    private static void invalidatePersistentColumn(MinecraftServer server, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int cx, int cz) {
        if (serverStopping || !PERSISTENT_COLUMN_STORE.enabled()) {
            return;
        }
        long lifecycleEpoch = serverLifecycleEpoch.get();
        try {
            DISK_WRITE_EXECUTOR.execute(() -> {
                if (!serverStopping && lifecycleEpoch == serverLifecycleEpoch.get()) {
                    PERSISTENT_COLUMN_STORE.invalidate(server, dimension, cx, cz);
                }
            });
        } catch (RejectedExecutionException e) {
            VSSLogger.debug("Persistent LOD invalidation rejected: " + e.getMessage());
        }
    }

    private static void queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload, boolean priority) {
        if (serverStopping) {
            state.clearRequest(payload.requestId());
            return;
        }
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

    private static int serverCapabilities() {
        int capabilities = VSSConstants.CAPABILITY_VOXEL_COLUMNS;
        if (LodByteCompression.isZstdAvailable()) {
            capabilities |= VSSConstants.CAPABILITY_ZSTD_COLUMNS;
        }
        return capabilities;
    }

    private static boolean isCompatibleClient(int clientProtocolVersion, int clientCapabilities) {
        return clientProtocolVersion == VSSConstants.PROTOCOL_VERSION
                && LodByteCompression.isZstdAvailable()
                && (clientCapabilities & VSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0;
    }

    private static void logIncompatibleClient(String name, int clientProtocolVersion, int clientCapabilities) {
        if (clientProtocolVersion != VSSConstants.PROTOCOL_VERSION) {
            VSSLogger.warn(name + " has incompatible VSS protocol " + clientProtocolVersion
                    + " (server requires " + VSSConstants.PROTOCOL_VERSION + ")");
            return;
        }
        if (!LodByteCompression.isZstdAvailable()) {
            VSSLogger.warn("VSS LOD sync disabled for " + name + ": server Zstd support is unavailable");
            return;
        }
        if ((clientCapabilities & VSSConstants.CAPABILITY_ZSTD_COLUMNS) == 0) {
            VSSLogger.warn("VSS LOD sync disabled for " + name + ": client does not advertise Zstd column support");
        }
    }

    public static void handleCancel(CancelRequestC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player != null && !serverStopping) {
            handleIntegratedCancel(player, payload);
        }
    }

    public static void handleIntegratedCancel(ServerPlayer player, CancelRequestC2SPayload payload) {
        if (serverStopping) {
            return;
        }
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state != null) {
            state.cancel(payload.requestId());
            GENERATION_SERVICE.cancelRequest(player.getUUID(), payload.requestId());
        }
    }

    public static void handleBandwidthUpdate(BandwidthUpdateC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player != null && !serverStopping) {
            handleIntegratedBandwidthUpdate(player, payload);
        }
    }

    public static void handleIntegratedBandwidthUpdate(ServerPlayer player, BandwidthUpdateC2SPayload payload) {
        if (serverStopping) {
            return;
        }
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state != null) {
            state.setDesiredBandwidth(payload.desiredRate());
        }
    }

    private static void flushQueuedColumns(MinecraftServer server) {
        if (serverStopping) {
            return;
        }
        long configuredLimit = VSSServerConfig.CONFIG.bandwidthBytesPerSecond();
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
        VSSLogger.info("LOD column sent to " + player.getGameProfile().getName()
                + ": chunk=" + payload.chunkX() + "," + payload.chunkZ()
                + ", complete=" + payload.completeColumn()
                + ", rawBytes=" + payload.rawSectionBytesLength()
                + ", encodedBytes=" + payload.encodedSectionBytesLength()
                + ", compression=" + payload.encodedCompression()
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
    public static void onServerTick(ServerTickEvent.Post event) {
        if (serverStopping) {
            return;
        }
        if (PLAYER_STATES.isEmpty()) {
            releaseIdleMemory();
            return;
        }
        MinecraftServer server = event.getServer();
        flushGeneratedColumns(server);
        flushQueuedColumns(server);
        DirtyColumnBroadcaster.tick(server);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        serverLifecycleEpoch.incrementAndGet();
        serverStopping = false;
        applyRuntimeConfig();
        idleMemoryReleased = true;
        PLAYER_STATES.clear();
        DirtyColumnBroadcaster.clear();
        COLUMN_CACHE.clear();
        DISK_READ_EXECUTOR.getQueue().clear();
        DISK_WRITE_EXECUTOR.getQueue().clear();
        pendingDiskReads.set(0);
        pendingPersistentWrites.set(0);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        serverStopping = true;
        serverLifecycleEpoch.incrementAndGet();
        DISK_READ_EXECUTOR.getQueue().clear();
        DISK_WRITE_EXECUTOR.getQueue().clear();
        pendingDiskReads.set(0);
        pendingPersistentWrites.set(0);
        for (PlayerRequestState state : PLAYER_STATES.values()) {
            state.clearAll();
        }
        idleMemoryReleased = false;
        GENERATION_SERVICE.shutdown();
        DirtyColumnBroadcaster.clear();
        COLUMN_CACHE.clear();
        idleMemoryReleased = true;
        PLAYER_STATES.clear();
        VSSLogger.info("Stopped VSS LOD sync and cleared generation tickets during server shutdown");
    }
}
