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
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
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
    private static final int PRIORITY_SEND_COLUMNS_PER_TICK = 8;
    private static final int PRELOAD_COLUMNS_PER_REGION = 1024;
    private static final int PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD = 2048;
    private static final int PRELOAD_NEAR_DISTANCE = 32;
    private static final int PRELOAD_MID_DISTANCE = 64;
    private static final int PRELOAD_FAR_DISTANCE = 128;
    private static final int PRELOAD_PENDING_DISK_LIMIT = 256;
    private static volatile long lastRequestDiagnosticNanos;
    private static volatile long lastSendDiagnosticNanos;
    private static volatile boolean serverStopping;
    private static boolean idleMemoryReleased = true;
    private static final Object DISK_EXECUTOR_LOCK = new Object();
    private static volatile ThreadPoolExecutor diskReadExecutor;
    private static volatile ThreadPoolExecutor diskWriteExecutor;

    private VSSServerNetworking() {
    }

    private static ThreadPoolExecutor createDiskExecutor(String threadName, int threads) {
        int clampedThreads = Math.max(
                VSSServerConfig.MIN_DISK_READER_THREADS,
                Math.min(VSSServerConfig.MAX_DISK_READER_THREADS, threads));
        AtomicInteger threadId = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
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
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static ThreadPoolExecutor diskReadExecutor() {
        return diskExecutor(true);
    }

    private static ThreadPoolExecutor diskWriteExecutor() {
        return diskExecutor(false);
    }

    private static ThreadPoolExecutor diskExecutor(boolean readExecutor) {
        ThreadPoolExecutor executor = readExecutor ? diskReadExecutor : diskWriteExecutor;
        if (isExecutorRunning(executor)) {
            return executor;
        }
        if (serverStopping) {
            throw new RejectedExecutionException("VSS server is stopping");
        }
        synchronized (DISK_EXECUTOR_LOCK) {
            executor = readExecutor ? diskReadExecutor : diskWriteExecutor;
            if (isExecutorRunning(executor)) {
                return executor;
            }
            ThreadPoolExecutor created = readExecutor
                    ? createDiskExecutor("VSS-DiskReader", VSSServerConfig.CONFIG.diskReaderThreads)
                    : createDiskExecutor("VSS-DiskWriter", 1);
            if (readExecutor) {
                diskReadExecutor = created;
            } else {
                diskWriteExecutor = created;
            }
            return created;
        }
    }

    private static boolean isExecutorRunning(ThreadPoolExecutor executor) {
        return executor != null && !executor.isShutdown() && !executor.isTerminated();
    }

    private static void restartDiskExecutors() {
        ThreadPoolExecutor oldRead;
        ThreadPoolExecutor oldWrite;
        synchronized (DISK_EXECUTOR_LOCK) {
            oldRead = diskReadExecutor;
            oldWrite = diskWriteExecutor;
            diskReadExecutor = createDiskExecutor("VSS-DiskReader", VSSServerConfig.CONFIG.diskReaderThreads);
            diskWriteExecutor = createDiskExecutor("VSS-DiskWriter", 1);
        }
        shutdownDiskExecutor(oldRead);
        shutdownDiskExecutor(oldWrite);
    }

    private static void shutdownDiskExecutors() {
        ThreadPoolExecutor oldRead;
        ThreadPoolExecutor oldWrite;
        synchronized (DISK_EXECUTOR_LOCK) {
            oldRead = diskReadExecutor;
            oldWrite = diskWriteExecutor;
            diskReadExecutor = null;
            diskWriteExecutor = null;
        }
        shutdownDiskExecutor(oldRead);
        shutdownDiskExecutor(oldWrite);
    }

    private static void shutdownDiskExecutor(ThreadPoolExecutor executor) {
        if (executor == null) {
            return;
        }
        executor.getQueue().clear();
        executor.shutdownNow();
    }

    private static int diskExecutorThreads(ThreadPoolExecutor executor) {
        return isExecutorRunning(executor) ? executor.getCorePoolSize() : 0;
    }

    private static int diskExecutorQueueSize(ThreadPoolExecutor executor) {
        return executor != null ? executor.getQueue().size() : 0;
    }

    private static void resizeDiskReadExecutor() {
        if (serverStopping) {
            return;
        }
        int desiredThreads = Math.max(
                VSSServerConfig.MIN_DISK_READER_THREADS,
                Math.min(VSSServerConfig.MAX_DISK_READER_THREADS, VSSServerConfig.CONFIG.diskReaderThreads));
        ThreadPoolExecutor executor = diskReadExecutor();
        int currentThreads = executor.getCorePoolSize();
        if (currentThreads == desiredThreads) {
            return;
        }
        if (desiredThreads > currentThreads) {
            executor.setMaximumPoolSize(desiredThreads);
            executor.setCorePoolSize(desiredThreads);
        } else {
            executor.setCorePoolSize(desiredThreads);
            executor.setMaximumPoolSize(desiredThreads);
        }
        executor.prestartAllCoreThreads();
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
        primeAllSendCredits();
        refreshSessionConfigs(server);
    }

    public static void applyRuntimeConfig() {
        resizeDiskReadExecutor();
    }

    private static void primeAllSendCredits() {
        long configuredLimit = VSSServerConfig.CONFIG.bandwidthBytesPerSecond();
        for (PlayerRequestState state : PLAYER_STATES.values()) {
            state.primeSendCredit(configuredLimit);
        }
    }

    private static SessionConfigS2CPayload configurePlayerSession(
            ServerPlayer player,
            int clientProtocolVersion,
            int clientCapabilities,
            String logPrefix,
            boolean resetState) {
        if (serverStopping) {
            return createSessionConfig(false);
        }
        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean compatible = isCompatibleClient(clientProtocolVersion, clientCapabilities);
        boolean enabled = config.enabled && compatible;
        if (compatible && enabled) {
            PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
            boolean created = resetState || state == null;
            if (created) {
                if (state != null) {
                    state.clearAll();
                }
                state = new PlayerRequestState();
                PLAYER_STATES.put(player.getUUID(), state);
            }
            state.setClientCapabilities(clientCapabilities);
            state.primeSendCredit(config.bandwidthBytesPerSecond());
            if (created) {
                scheduleExistingColumnPreload(player, state);
                VSSLogger.info(logPrefix + " " + player.getGameProfile().getName() + " registered for VSS LOD sync");
            }
            idleMemoryReleased = false;
        } else if (!compatible) {
            logIncompatibleClient(logPrefix + " " + player.getGameProfile().getName(), clientProtocolVersion, clientCapabilities);
        }
        return createSessionConfig(enabled);
    }

    static String generationDiagnostics() {
        return GENERATION_SERVICE.diagnostics();
    }

    static Component storageDiagnostics() {
        ThreadPoolExecutor readExecutor = diskReadExecutor;
        ThreadPoolExecutor writeExecutor = diskWriteExecutor;
        return Component.translatable(
                "vss.command.storage.runtime",
                diskExecutorThreads(readExecutor),
                diskExecutorQueueSize(readExecutor),
                diskExecutorQueueSize(writeExecutor),
                pendingDiskReads.get(),
                pendingPersistentWrites.get());
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

        SessionConfigS2CPayload config = configurePlayerSession(
                player,
                payload.protocolVersion(),
                payload.capabilities(),
                "Player",
                true);
        VSSNetworking.sendToPlayer(player, config);
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
                config.nearSyncRateLimitPerTick,
                config.midSyncRateLimitPerTick,
                config.farSyncRateLimitPerTick,
                config.distantSyncRateLimitPerTick,
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

    private static void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        if (serverStopping) {
            return;
        }
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state == null) {
            return;
        }

        VSSServerConfig config = VSSServerConfig.CONFIG;
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

            if (!state.beginRequest(requestId, level.dimension(), packed)) {
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
            if (clientTimestamp > 0L) {
                state.markClientKnownColumn(level.dimension(), packed, clientTimestamp);
            }
            boolean allowGeneration = i < payload.allowGeneration().length && payload.allowGeneration()[i];
            long dirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), cx, cz);
            boolean priorityRefresh = clientTimestamp > 0L;
            if (!priorityRefresh && state.shouldBackpressureNormalRequests(config.bandwidthBytesPerSecond())) {
                responseTypes[responseCount] = VSSConstants.RESPONSE_BACKPRESSURE;
                responseIds[responseCount++] = requestId;
                state.clearRequest(requestId);
                continue;
            }
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
                    continue;
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
            diskReadExecutor().execute(() -> {
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
                            diskData = EncodedColumnData.encode(rawDiskData, columnTimestamp);
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
        if (!isColumnStillRelevant(player, level.dimension(), cx, cz)) {
            requestState.clearRequest(requestId);
            return;
        }
        if (storedData != null && storedData.columnData() != null) {
            EncodedColumnData columnData = storedData.columnData().withColumnStamp(Math.max(storedData.timestamp(), columnTimestamp));
            totalDiskReadHits.incrementAndGet();
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

            if (!isColumnStillRelevant(player, result.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                state.clearRequest(result.requestId());
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

            boolean queued = queueColumn(player, state, new VoxelColumnS2CPayload(
                    result.requestId(),
                    result.dimension(),
                    columnData), result.priority());
            COLUMN_CACHE.put(result.dimension(), columnData);
            if (!queued && isColumnStillRelevant(player, result.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                state.addPreloadColumn(new PlayerRequestState.PreloadColumn(
                        columnData.chunkX(),
                        columnData.chunkZ(),
                        columnData.columnStamp()));
            }
            writePersistentColumn(server, result.dimension(), columnData);
        }
    }

    private static void scheduleExistingColumnPreload(ServerPlayer player, PlayerRequestState state) {
        if (serverStopping || !PERSISTENT_COLUMN_STORE.enabled()) {
            return;
        }
        int regionSize = PersistentColumnLodStore.regionSize();
        int centerRegionX = Math.floorDiv(player.getBlockX() >> 4, regionSize);
        int centerRegionZ = Math.floorDiv(player.getBlockZ() >> 4, regionSize);
        int maxDistance = VSSServerConfig.CONFIG.lodDistanceChunks;
        int maxRegionRing = Math.max(0, (maxDistance + regionSize - 1) / regionSize);
        state.resetPreloadRegions(player.serverLevel().dimension(), centerRegionX, centerRegionZ, maxRegionRing);
        VSSLogger.debug("Queued " + state.preloadRegionCount() + " LOD cache regions for cold-start indexed preload around region "
                + centerRegionX + "," + centerRegionZ);
    }

    private static void updateExistingColumnPreloadWindow(ServerPlayer player, PlayerRequestState state) {
        int regionSize = PersistentColumnLodStore.regionSize();
        int centerRegionX = Math.floorDiv(player.getBlockX() >> 4, regionSize);
        int centerRegionZ = Math.floorDiv(player.getBlockZ() >> 4, regionSize);
        int maxDistance = VSSServerConfig.CONFIG.lodDistanceChunks;
        int maxRegionRing = Math.max(0, (maxDistance + regionSize - 1) / regionSize);
        state.updatePreloadRegions(player.serverLevel().dimension(), centerRegionX, centerRegionZ, maxRegionRing);
    }

    private static void scanPreloadRegions(MinecraftServer server) {
        if (serverStopping || !PERSISTENT_COLUMN_STORE.enabled()) {
            return;
        }
        for (Map.Entry<UUID, PlayerRequestState> entry : PLAYER_STATES.entrySet()) {
            if (pendingDiskReads.get() >= PRELOAD_PENDING_DISK_LIMIT) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            PlayerRequestState state = entry.getValue();
            if (player != null) {
                updateExistingColumnPreloadWindow(player, state);
            }
            if (player == null
                    || state.preloadRegionCount() <= 0
                    || state.preloadColumnCount() >= PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD) {
                continue;
            }
            while (pendingDiskReads.get() < PRELOAD_PENDING_DISK_LIMIT
                    && state.preloadColumnCount() < PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD) {
                PlayerRequestState.PreloadRegion region = state.pollPreloadRegion();
                if (region == null) {
                    break;
                }
                submitPreloadRegionScan(player, state, region);
            }
        }
    }

    private static void submitPreloadRegionScan(ServerPlayer player, PlayerRequestState state, PlayerRequestState.PreloadRegion region) {
        UUID playerId = player.getUUID();
        MinecraftServer server = player.server;
        ServerLevel level = player.serverLevel();
        long lifecycleEpoch = serverLifecycleEpoch.get();
        int centerCx = player.getBlockX() >> 4;
        int centerCz = player.getBlockZ() >> 4;
        int minDistance = 0;
        int maxDistance = VSSServerConfig.CONFIG.lodDistanceChunks;
        pendingDiskReads.incrementAndGet();
        try {
            diskReadExecutor().execute(() -> {
                try {
                    var columns = PERSISTENT_COLUMN_STORE.findExistingColumnsInRegion(
                            server,
                            level.dimension(),
                            region.regionX(),
                            region.regionZ(),
                            centerCx,
                            centerCz,
                            minDistance,
                            maxDistance,
                            PRELOAD_COLUMNS_PER_REGION);
                    if (columns.isEmpty() || serverStopping || lifecycleEpoch != serverLifecycleEpoch.get()) {
                        return;
                    }
                    server.execute(() -> {
                        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
                        if (onlinePlayer == null || PLAYER_STATES.get(playerId) != state) {
                            return;
                        }
                        columns.removeIf(column -> GENERATION_SERVICE.isGenerating(level.dimension(), column.chunkX(), column.chunkZ()));
                        columns.removeIf(column -> state.isClientKnownCurrent(
                                level.dimension(),
                                column.chunkX(),
                                column.chunkZ(),
                                Math.max(
                                        column.timestamp(),
                                        DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), column.chunkX(), column.chunkZ()))));
                        state.addPreloadColumns(columns);
                    });
                } finally {
                    decrementPendingDiskReads();
                }
            });
        } catch (RejectedExecutionException e) {
            decrementPendingDiskReads();
            VSSLogger.debug("Existing LOD preload region scan rejected: " + e.getMessage());
        }
    }

    private static void flushPreloadColumns(MinecraftServer server) {
        if (serverStopping || !PERSISTENT_COLUMN_STORE.enabled()) {
            return;
        }
        boolean submittedAny;
        do {
            submittedAny = false;
            for (Map.Entry<UUID, PlayerRequestState> entry : PLAYER_STATES.entrySet()) {
                if (pendingDiskReads.get() >= PRELOAD_PENDING_DISK_LIMIT) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                PlayerRequestState state = entry.getValue();
                if (player == null || state.preloadColumnCount() <= 0) {
                    continue;
                }
                int playerCx = player.getBlockX() >> 4;
                int playerCz = player.getBlockZ() >> 4;
                submittedAny |= submitNextPreloadReadInDistanceRange(
                        player, state, playerCx, playerCz, 0, PRELOAD_NEAR_DISTANCE);
                submittedAny |= submitNextPreloadReadInDistanceRange(
                        player, state, playerCx, playerCz, PRELOAD_NEAR_DISTANCE + 1, PRELOAD_MID_DISTANCE);
                submittedAny |= submitNextPreloadReadInDistanceRange(
                        player, state, playerCx, playerCz, PRELOAD_MID_DISTANCE + 1, PRELOAD_FAR_DISTANCE);
                submittedAny |= submitNextPreloadReadInDistanceRange(
                        player, state, playerCx, playerCz, PRELOAD_FAR_DISTANCE + 1, Integer.MAX_VALUE);
            }
        } while (submittedAny && pendingDiskReads.get() < PRELOAD_PENDING_DISK_LIMIT);
    }

    private static boolean submitNextPreloadReadInDistanceRange(
            ServerPlayer player,
            PlayerRequestState state,
            int playerCx,
            int playerCz,
            int minDistance,
            int maxDistance) {
        while (pendingDiskReads.get() < PRELOAD_PENDING_DISK_LIMIT) {
            PlayerRequestState.PreloadColumn preload = state.pollPreloadColumnInDistanceRange(
                    playerCx,
                    playerCz,
                    minDistance,
                    maxDistance);
            if (preload == null) {
                return false;
            }
            if (!isColumnStillRelevant(player, player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ())) {
                continue;
            }
            long requiredTimestamp = Math.max(
                    preload.timestamp(),
                    DirtyColumnBroadcaster.latestDirtyTimestamp(player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ()));
            if (state.isClientKnownCurrent(player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ(), requiredTimestamp)) {
                continue;
            }
            submitPreloadRead(player, state, preload);
            return true;
        }
        return false;
    }

    private static void submitPreloadRead(ServerPlayer player, PlayerRequestState state, PlayerRequestState.PreloadColumn preload) {
        if (serverStopping) {
            return;
        }
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        long lifecycleEpoch = serverLifecycleEpoch.get();
        pendingDiskReads.incrementAndGet();
        try {
            diskReadExecutor().execute(() -> {
                PersistentColumnLodStore.Entry storedData = null;
                try {
                    if (!serverStopping && lifecycleEpoch == serverLifecycleEpoch.get()) {
                        storedData = PERSISTENT_COLUMN_STORE.read(
                                server,
                                level.dimension(),
                                preload.chunkX(),
                                preload.chunkZ(),
                                DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), preload.chunkX(), preload.chunkZ()));
                    }
                } finally {
                    decrementPendingDiskReads();
                }
                if (storedData == null || storedData.columnData() == null
                        || serverStopping || lifecycleEpoch != serverLifecycleEpoch.get()) {
                    return;
                }
                EncodedColumnData columnData = storedData.columnData();
                server.execute(() -> {
                    ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
                    if (onlinePlayer == null || PLAYER_STATES.get(playerId) != state) {
                        return;
                    }
                    if (GENERATION_SERVICE.isGenerating(level.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                        return;
                    }
                    if (!isColumnStillRelevant(onlinePlayer, level.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                        return;
                    }
                    long requiredTimestamp = Math.max(
                            columnData.columnStamp(),
                            DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), columnData.chunkX(), columnData.chunkZ()));
                    if (state.isClientKnownCurrent(level.dimension(), columnData.chunkX(), columnData.chunkZ(), requiredTimestamp)) {
                        return;
                    }
                    boolean queued = queueColumn(onlinePlayer, state, new VoxelColumnS2CPayload(-1, level.dimension(), columnData), false);
                    if (queued) {
                        COLUMN_CACHE.put(level.dimension(), columnData);
                    } else if (isColumnStillRelevant(onlinePlayer, level.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                        state.addPreloadColumn(new PlayerRequestState.PreloadColumn(
                                columnData.chunkX(),
                                columnData.chunkZ(),
                                columnData.columnStamp()));
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            decrementPendingDiskReads();
            VSSLogger.debug("Existing LOD preload read rejected: " + e.getMessage());
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
            diskWriteExecutor().execute(() -> {
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
            diskWriteExecutor().execute(() -> {
                if (!serverStopping && lifecycleEpoch == serverLifecycleEpoch.get()) {
                    PERSISTENT_COLUMN_STORE.invalidate(server, dimension, cx, cz);
                }
            });
        } catch (RejectedExecutionException e) {
            VSSLogger.debug("Persistent LOD invalidation rejected: " + e.getMessage());
        }
    }

    private static boolean queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload, boolean priority) {
        if (serverStopping) {
            state.clearRequest(payload.requestId());
            return false;
        }
        if (!isPayloadStillRelevant(player, payload)) {
            state.clearRequest(payload.requestId());
            return false;
        }
        payload.setAllowZstdEncoding(state.supportsZstdColumns());
        if (!state.enqueue(payload, priority)) {
            if (payload.requestId() >= 0) {
                sendRateLimited(player, payload.requestId());
            }
            return false;
        }
        return true;
    }

    private static boolean isPayloadStillRelevant(ServerPlayer player, VoxelColumnS2CPayload payload) {
        return isColumnStillRelevant(player, payload.dimension(), payload.chunkX(), payload.chunkZ());
    }

    private static boolean isColumnStillRelevant(
            ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            int cx,
            int cz) {
        if (serverStopping || player == null || !VSSServerConfig.CONFIG.enabled) {
            return false;
        }
        if (!player.serverLevel().dimension().equals(dimension)) {
            return false;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = VSSServerConfig.CONFIG.lodDistanceChunks + VSSConstants.LOD_DISTANCE_BUFFER;
        return PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= maxDistance;
    }

    private static void sendRateLimited(ServerPlayer player, int requestId) {
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                        new int[] {requestId},
                        1));
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
                && (clientCapabilities & VSSConstants.CAPABILITY_VOXEL_COLUMNS) != 0;
    }

    private static void logIncompatibleClient(String name, int clientProtocolVersion, int clientCapabilities) {
        if (clientProtocolVersion != VSSConstants.PROTOCOL_VERSION) {
            VSSLogger.warn(name + " has incompatible VSS protocol " + clientProtocolVersion
                    + " (server requires " + VSSConstants.PROTOCOL_VERSION + ")");
            return;
        }
        if ((clientCapabilities & VSSConstants.CAPABILITY_VOXEL_COLUMNS) == 0) {
            VSSLogger.warn("VSS LOD sync disabled for " + name + ": client does not advertise voxel column support");
        }
    }

    public static void handleCancel(CancelRequestC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || serverStopping) {
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
        if (player == null || serverStopping) {
            return;
        }
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state != null) {
            state.setDesiredBandwidth(payload.desiredRate());
            state.primeSendCredit(VSSServerConfig.CONFIG.bandwidthBytesPerSecond());
        }
    }

    public static void handleRegionPresence(RegionPresenceC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || serverStopping || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state != null && player.serverLevel().dimension().equals(payload.dimension())) {
            try {
                state.updateClientKnownColumns(payload.dimension(), payload);
            } catch (RuntimeException e) {
                VSSLogger.debug("Ignored invalid LOD presence summary from "
                        + player.getGameProfile().getName() + ": " + e.getMessage());
            }
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
            state.prepareSendOrder(playerCx, playerCz);
            int priorityColumnsSent = 0;
            while (priorityColumnsSent < PRIORITY_SEND_COLUMNS_PER_TICK) {
                PlayerRequestState.QueuedPayload queued = state.peekPriorityQueuedPayload(playerCx, playerCz);
                if (queued == null || !state.canSend(effectiveLimit)) {
                    break;
                }
                if (state.pollPriorityQueuedPayload(queued) == null) {
                    continue;
                }
                if (state.consumeCancelled(queued.payload().requestId())) {
                    continue;
                }
                if (!isPayloadStillRelevant(player, queued.payload())) {
                    state.clearRequest(queued.payload().requestId());
                    continue;
                }
                VSSNetworking.sendToPlayer(player, queued.payload());
                markClientKnownAfterSend(state, queued.payload());
                state.recordSend(queued.estimatedBytes());
                state.clearRequest(queued.payload().requestId());
                priorityColumnsSent++;
                logColumnSend(player, queued.payload(), state);
            }

            while (state.queuedPayloadCount() > 0) {
                PlayerRequestState.QueuedPayload queued = state.peekQueuedPayload(playerCx, playerCz);
                if (queued == null || !state.canSend(effectiveLimit)) {
                    break;
                }
                if (state.pollQueuedPayload(queued) == null) {
                    continue;
                }
                if (state.consumeCancelled(queued.payload().requestId())) {
                    continue;
                }
                if (!isPayloadStillRelevant(player, queued.payload())) {
                    state.clearRequest(queued.payload().requestId());
                    continue;
                }
                VSSNetworking.sendToPlayer(player, queued.payload());
                markClientKnownAfterSend(state, queued.payload());
                state.recordSend(queued.estimatedBytes());
                state.clearRequest(queued.payload().requestId());
                logColumnSend(player, queued.payload(), state);
            }
        }
    }

    private static void markClientKnownAfterSend(PlayerRequestState state, VoxelColumnS2CPayload payload) {
        if (payload.completeColumn() && payload.columnTimestamp() > 0L) {
            state.markClientKnownColumn(
                    payload.dimension(),
                    PositionUtil.packPosition(payload.chunkX(), payload.chunkZ()),
                    payload.columnTimestamp());
        }
    }

    private static void logRequestBatch(ServerPlayer player, int count) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
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
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
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
        idleMemoryReleased = true;
        VSSLogger.info("Released idle VSS request state after the last VSS player disconnected; kept hot LOD caches");
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
        flushPreloadColumns(server);
        scanPreloadRegions(server);
        flushQueuedColumns(server);
        DirtyColumnBroadcaster.tick(server);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        serverLifecycleEpoch.incrementAndGet();
        serverStopping = false;
        restartDiskExecutors();
        applyRuntimeConfig();
        idleMemoryReleased = true;
        PLAYER_STATES.clear();
        DirtyColumnBroadcaster.clear();
        COLUMN_CACHE.clear();
        PERSISTENT_COLUMN_STORE.clearMemory();
        pendingDiskReads.set(0);
        pendingPersistentWrites.set(0);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        serverStopping = true;
        serverLifecycleEpoch.incrementAndGet();
        shutdownDiskExecutors();
        pendingDiskReads.set(0);
        pendingPersistentWrites.set(0);
        for (PlayerRequestState state : PLAYER_STATES.values()) {
            state.clearAll();
        }
        idleMemoryReleased = false;
        GENERATION_SERVICE.shutdown();
        DirtyColumnBroadcaster.clear();
        COLUMN_CACHE.clear();
        PERSISTENT_COLUMN_STORE.clearMemory();
        idleMemoryReleased = true;
        PLAYER_STATES.clear();
        VSSLogger.info("Stopped VSS LOD sync and cleared generation tickets during server shutdown");
    }
}
