package dev.xantha.vss.networking.server;


import dev.xantha.vss.networking.server.diagnostics.ServerNetworkingDiagnostics;
import dev.xantha.vss.networking.server.diagnostics.ServerRequestStats;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.preload.ExistingColumnPreloader;
import dev.xantha.vss.networking.server.request.ClientControlMessageHandler;
import dev.xantha.vss.networking.server.request.ColumnRequestBatchHandler;
import dev.xantha.vss.networking.server.request.ColumnStorageReadPipeline;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.runtime.ServerNetworkingLifecycle;
import dev.xantha.vss.networking.server.sending.GeneratedColumnFlusher;
import dev.xantha.vss.networking.server.sending.ColumnPayloadSplitter;
import dev.xantha.vss.networking.server.sending.QueuedColumnSender;
import dev.xantha.vss.networking.server.session.PlayerSessionManager;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.state.PlayerSendQueue;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class VSSServerNetworking {
    private static final AtomicLong NEXT_TRANSFER_ID = new AtomicLong(1L);
    private static final PlayerRequestRegistry PLAYER_REGISTRY = new PlayerRequestRegistry();
    private static final ChunkGenerationService GENERATION_SERVICE = new ChunkGenerationService(VSSServerConfig.CONFIG);
    private static final ColumnLodCache COLUMN_CACHE = new ColumnLodCache(VSSServerConfig.CONFIG);
    private static final PersistentColumnLodStore PERSISTENT_COLUMN_STORE = new PersistentColumnLodStore(VSSServerConfig.CONFIG);
    private static final ServerRequestStats REQUEST_STATS = new ServerRequestStats();
    private static final ServerLifecycleGuard SERVER_LIFECYCLE = new ServerLifecycleGuard();
    private static final DiskTaskRuntime DISK_RUNTIME = new DiskTaskRuntime(
            VSSServerConfig.MIN_DISK_READER_THREADS,
            VSSServerConfig.MAX_DISK_READER_THREADS,
            () -> VSSServerConfig.CONFIG.diskReaderThreads,
            () -> !isServerStopping());
    private static final ExistingColumnPreloader EXISTING_COLUMN_PRELOADER = new ExistingColumnPreloader(
            PLAYER_REGISTRY,
            PERSISTENT_COLUMN_STORE,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            DISK_RUNTIME);
    private static final PersistentColumnWriter PERSISTENT_COLUMN_WRITER = new PersistentColumnWriter(
            PERSISTENT_COLUMN_STORE,
            DISK_RUNTIME);
    private static final ColumnStorageReadPipeline STORAGE_READ_PIPELINE = new ColumnStorageReadPipeline(
            PLAYER_REGISTRY,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            PERSISTENT_COLUMN_STORE,
            PERSISTENT_COLUMN_WRITER,
            REQUEST_STATS,
            DISK_RUNTIME);
    private static final GeneratedColumnFlusher GENERATED_COLUMN_FLUSHER = new GeneratedColumnFlusher(
            PLAYER_REGISTRY,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            PERSISTENT_COLUMN_WRITER);
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final ColumnRequestBatchHandler BATCH_REQUEST_HANDLER = new ColumnRequestBatchHandler(
            PLAYER_REGISTRY,
            COLUMN_CACHE,
            STORAGE_READ_PIPELINE,
            REQUEST_STATS,
            DISK_RUNTIME,
            DIAGNOSTIC_INTERVAL_NANOS);
    private static final ClientControlMessageHandler CONTROL_MESSAGE_HANDLER = new ClientControlMessageHandler(
            PLAYER_REGISTRY,
            GENERATION_SERVICE);
    private static final ServerNetworkingDiagnostics NETWORKING_DIAGNOSTICS = new ServerNetworkingDiagnostics(
            PLAYER_REGISTRY,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            PERSISTENT_COLUMN_STORE,
            REQUEST_STATS,
            DISK_RUNTIME);
    private static final int PRIORITY_SEND_COLUMNS_PER_TICK = 8;
    private static final QueuedColumnSender QUEUED_COLUMN_SENDER = new QueuedColumnSender(
            PLAYER_REGISTRY,
            PRIORITY_SEND_COLUMNS_PER_TICK,
            DIAGNOSTIC_INTERVAL_NANOS);
    private static final ServerNetworkingLifecycle SERVER_RUNTIME = new ServerNetworkingLifecycle(
            PLAYER_REGISTRY,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            PERSISTENT_COLUMN_STORE,
            PERSISTENT_COLUMN_WRITER,
            SERVER_LIFECYCLE,
            DISK_RUNTIME,
            GENERATED_COLUMN_FLUSHER,
            EXISTING_COLUMN_PRELOADER,
            QUEUED_COLUMN_SENDER);
    private static final PlayerSessionManager SESSION_MANAGER = new PlayerSessionManager(
            PLAYER_REGISTRY,
            EXISTING_COLUMN_PRELOADER,
            SERVER_RUNTIME::markSessionActive);

    private VSSServerNetworking() {
    }

    public static boolean isLifecycleStale(long lifecycleEpoch) {
        return SERVER_LIFECYCLE.isStale(lifecycleEpoch);
    }

    public static long lifecycleEpoch() {
        return SERVER_LIFECYCLE.currentEpoch();
    }

    public static boolean isServerStopping() {
        return SERVER_LIFECYCLE.isStopping();
    }

    public static boolean isRegistered(ServerPlayer player) {
        return !isServerStopping() && PLAYER_REGISTRY.contains(player.getUUID());
    }

    public static boolean hasRegisteredPlayers() {
        return !isServerStopping() && !PLAYER_REGISTRY.isEmpty();
    }

    public static boolean hasRegisteredPlayers(ServerLevel level) {
        if (isServerStopping()) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (isRegistered(player)) {
                return true;
            }
        }
        return false;
    }

    public static long clientKnownColumnTimestamp(
            ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            int cx,
            int cz) {
        if (isServerStopping() || player == null) {
            return 0L;
        }
        PlayerRequestState state = PLAYER_REGISTRY.get(player.getUUID());
        return state != null ? state.clientKnownTimestamp(dimension, cx, cz) : 0L;
    }

    public static void refreshSessionConfigs(MinecraftServer server) {
        SESSION_MANAGER.refresh(server);
    }

    public static void bumpAndRefreshSessionConfigs(MinecraftServer server) {
        applyRuntimeConfig();
        SESSION_MANAGER.bumpAndRefresh(server);
    }

    public static void applyRuntimeConfig() {
        SERVER_RUNTIME.applyRuntimeConfig();
    }

    public static String generationDiagnostics() {
        return NETWORKING_DIAGNOSTICS.generationDiagnostics();
    }

    public static Component storageDiagnostics() {
        return NETWORKING_DIAGNOSTICS.storageDiagnostics();
    }

    public static Component generationDiagnosticsComponent() {
        return NETWORKING_DIAGNOSTICS.generationDiagnosticsComponent();
    }

    public static String diagnostics() {
        return NETWORKING_DIAGNOSTICS.diagnostics();
    }

    public static Component diagnosticsComponent() {
        return NETWORKING_DIAGNOSTICS.diagnosticsComponent();
    }

    public static void handleHandshake(HandshakeC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || isServerStopping()) {
            return;
        }

        SESSION_MANAGER.handleHandshake(player, payload);
    }

    public static void handleBatchRequest(BatchChunkRequestC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || isServerStopping() || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        BATCH_REQUEST_HANDLER.handle(player, payload);
    }

    public static void invalidateCachedColumn(ServerLevel level, int cx, int cz, long dirtyTimestamp) {
        if (isServerStopping()) {
            return;
        }
        COLUMN_CACHE.invalidateOlderThan(level.dimension(), cx, cz, dirtyTimestamp);
        PERSISTENT_COLUMN_WRITER.invalidate(level.dimension(), cx, cz, dirtyTimestamp);
    }

    public static boolean queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload) {
        return queueColumn(player, state, payload, false);
    }

    public static boolean queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload, boolean priority) {
        if (isServerStopping()) {
            state.clearRequest(payload.requestId());
            return false;
        }
        if (!payload.completeColumn()) {
            state.clearRequest(payload.requestId());
            sendNotGenerated(player, payload.requestId());
            return false;
        }
        if (!isPayloadStillRelevant(player, payload)) {
            state.clearRequest(payload.requestId());
            sendBackpressured(player, payload.requestId());
            return false;
        }
        boolean allowZstdColumns = state.supportsZstdColumns();
        VoxelColumnS2CPayload transferPayload = payload.withTransferMetadata(
                nextTransferId(),
                0,
                1,
                payload.replacementSectionYs());
        transferPayload.setAllowZstdEncoding(allowZstdColumns);
        VSSServerConfig config = VSSServerConfig.CONFIG;
        long effectiveBandwidth = Math.min(config.bandwidthBytesPerSecond(), state.desiredBandwidth());
        List<VoxelColumnS2CPayload> payloads = ColumnPayloadSplitter.splitForBandwidth(
                player.serverLevel(),
                transferPayload,
                effectiveBandwidth,
                config.enableNetworkColumnCompression);
        for (VoxelColumnS2CPayload queuedPayload : payloads) {
            queuedPayload.setAllowZstdEncoding(allowZstdColumns);
        }
        state.prepareSendOrder(player.getBlockX() >> 4, player.getBlockZ() >> 4);
        PlayerSendQueue.EnqueueResult admission = state.enqueue(payloads, priority);
        for (int rejectedRequestId : admission.rejectedRequestIds()) {
            sendBackpressured(player, rejectedRequestId);
        }
        return admission.accepted();
    }

    private static boolean isPayloadStillRelevant(ServerPlayer player, VoxelColumnS2CPayload payload) {
        return isColumnStillRelevant(player, payload.dimension(), payload.chunkX(), payload.chunkZ());
    }

    public static boolean isColumnStillRelevant(
            ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            int cx,
            int cz) {
        if (isServerStopping() || player == null || !VSSServerConfig.CONFIG.enabled) {
            return false;
        }
        if (!player.serverLevel().dimension().equals(dimension)) {
            return false;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER;
        return PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= maxDistance;
    }

    private static void sendBackpressured(ServerPlayer player, int requestId) {
        sendResponse(player, requestId, VSSConstants.RESPONSE_BACKPRESSURE);
    }

    private static void sendNotGenerated(ServerPlayer player, int requestId) {
        sendResponse(player, requestId, VSSConstants.RESPONSE_NOT_GENERATED);
    }

    private static void sendResponse(ServerPlayer player, int requestId, byte responseType) {
        if (requestId < 0) {
            return;
        }
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {responseType},
                        new int[] {requestId},
                        1));
    }

    private static long nextTransferId() {
        return NEXT_TRANSFER_ID.getAndUpdate(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
    }

    public static void handleCancel(CancelRequestC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || isServerStopping()) {
            return;
        }
        CONTROL_MESSAGE_HANDLER.handleCancel(player, payload);
    }

    public static void handleBandwidthUpdate(BandwidthUpdateC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || isServerStopping()) {
            return;
        }
        CONTROL_MESSAGE_HANDLER.handleBandwidthUpdate(player, payload);
    }

    public static void handleRegionPresence(RegionPresenceC2SPayload payload, IPayloadContext context) {
        ServerPlayer player = context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null || isServerStopping() || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        CONTROL_MESSAGE_HANDLER.handleRegionPresence(player, payload);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SERVER_RUNTIME.onPlayerLogout(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SERVER_RUNTIME.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        SERVER_RUNTIME.onServerStarting();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SERVER_RUNTIME.onServerStopping(event.getServer());
    }

}
