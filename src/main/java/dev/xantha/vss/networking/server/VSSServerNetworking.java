package dev.xantha.vss.networking.server;

import dev.xantha.vss.networking.server.sending.ColumnDeliveryService;
import dev.xantha.vss.networking.server.session.PlayerSessionLookup;


import dev.xantha.vss.networking.server.diagnostics.ServerNetworkingDiagnostics;
import dev.xantha.vss.networking.server.diagnostics.ServerRequestStats;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.generation.ChunkyGenerationService;
import dev.xantha.vss.networking.server.preload.ExistingColumnPreloader;
import dev.xantha.vss.networking.server.request.ClientControlMessageHandler;
import dev.xantha.vss.networking.server.request.ColumnRequestBatchHandler;
import dev.xantha.vss.networking.server.request.ColumnStorageReadPipeline;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;
import dev.xantha.vss.networking.server.runtime.ServerNetworkingLifecycle;
import dev.xantha.vss.networking.server.sending.GeneratedColumnFlusher;
import dev.xantha.vss.networking.server.sending.QueuedColumnSender;
import dev.xantha.vss.networking.server.session.PlayerSessionManager;
import dev.xantha.vss.networking.server.session.WorldgenProfileHolder;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.HandshakeRequestS2CPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload;
import dev.xantha.vss.networking.server.compat.LostCityHintService;
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

/**
 * 服务端组装与平台事件入口。只在此创建共享组件，再以构造参数交给业务模块。
 * <p>保持静态门面供命令和平台适配器使用；存储、调度、会话实现不得反向调用本类。
 */
public final class VSSServerNetworking {
    private static final PlayerRequestRegistry PLAYER_REGISTRY = new PlayerRequestRegistry();
    private static final ChunkGenerationService GENERATION_SERVICE = new ChunkGenerationService(VSSServerConfig.CONFIG);
    private static final ColumnLodCache COLUMN_CACHE = new ColumnLodCache(VSSServerConfig.CONFIG);
    private static final PersistentColumnLodStore PERSISTENT_COLUMN_STORE = new PersistentColumnLodStore(VSSServerConfig.CONFIG);
    private static final ServerRequestStats REQUEST_STATS = new ServerRequestStats();
    private static final ServerLifecycleGuard SERVER_LIFECYCLE = new ServerLifecycleGuard();
    private static final PlayerSessionLookup SESSION_LOOKUP = new PlayerSessionLookup(PLAYER_REGISTRY, SERVER_LIFECYCLE);
    private static final ColumnDeliveryService COLUMN_DELIVERY = new ColumnDeliveryService(
            SERVER_LIFECYCLE, () -> VSSServerConfig.CONFIG, VSSNetworking::sendToPlayer);
    private static final DiskTaskRuntime DISK_RUNTIME = new DiskTaskRuntime(
            VSSServerConfig.MIN_DISK_READER_THREADS,
            VSSServerConfig.MAX_DISK_READER_THREADS,
            () -> VSSServerConfig.CONFIG.diskReaderThreads,
            () -> !SERVER_LIFECYCLE.isStopping());
    private static final ExistingColumnPreloader EXISTING_COLUMN_PRELOADER = new ExistingColumnPreloader(
            PLAYER_REGISTRY,
            PERSISTENT_COLUMN_STORE,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            DISK_RUNTIME,
            SERVER_LIFECYCLE,
            COLUMN_DELIVERY);
    private static final PersistentColumnWriter PERSISTENT_COLUMN_WRITER = new PersistentColumnWriter(
            PERSISTENT_COLUMN_STORE,
            DISK_RUNTIME,
            SERVER_LIFECYCLE);
    private static final ChunkyGenerationService CHUNKY_SERVICE = new ChunkyGenerationService(
            GENERATION_SERVICE, COLUMN_CACHE, PERSISTENT_COLUMN_STORE,
            PERSISTENT_COLUMN_WRITER, DISK_RUNTIME, PLAYER_REGISTRY,
            SERVER_LIFECYCLE,
            SESSION_LOOKUP);
    private static final ColumnStorageReadPipeline STORAGE_READ_PIPELINE = new ColumnStorageReadPipeline(
            PLAYER_REGISTRY,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            PERSISTENT_COLUMN_STORE,
            PERSISTENT_COLUMN_WRITER,
            REQUEST_STATS,
            DISK_RUNTIME,
            SERVER_LIFECYCLE,
            COLUMN_DELIVERY);
    private static final GeneratedColumnFlusher GENERATED_COLUMN_FLUSHER = new GeneratedColumnFlusher(
            PLAYER_REGISTRY,
            GENERATION_SERVICE,
            COLUMN_CACHE,
            PERSISTENT_COLUMN_WRITER,
            CHUNKY_SERVICE,
            SERVER_LIFECYCLE,
            COLUMN_DELIVERY);
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final ColumnRequestBatchHandler BATCH_REQUEST_HANDLER = new ColumnRequestBatchHandler(
            PLAYER_REGISTRY,
            COLUMN_CACHE,
            STORAGE_READ_PIPELINE,
            REQUEST_STATS,
            DISK_RUNTIME,
            DIAGNOSTIC_INTERVAL_NANOS,
            SERVER_LIFECYCLE,
            COLUMN_DELIVERY);
    private static final ClientControlMessageHandler CONTROL_MESSAGE_HANDLER = new ClientControlMessageHandler(
            PLAYER_REGISTRY,
            GENERATION_SERVICE,
            SERVER_LIFECYCLE);
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
            DIAGNOSTIC_INTERVAL_NANOS,
            SERVER_LIFECYCLE,
            COLUMN_DELIVERY);
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
            QUEUED_COLUMN_SENDER,
            CHUNKY_SERVICE);
    private static final PlayerSessionManager SESSION_MANAGER = new PlayerSessionManager(
            PLAYER_REGISTRY,
            EXISTING_COLUMN_PRELOADER,
            SERVER_RUNTIME::markSessionActive,
            SERVER_LIFECYCLE);

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
        return SESSION_LOOKUP.isRegistered(player);
    }

    public static boolean hasRegisteredPlayers() {
        return SESSION_LOOKUP.hasRegisteredPlayers();
    }

    public static boolean hasRegisteredPlayers(ServerLevel level) {
        return SESSION_LOOKUP.hasRegisteredPlayers(level);
    }

    public static long clientKnownColumnTimestamp(ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int cx, int cz) {
        return SESSION_LOOKUP.clientKnownColumnTimestamp(player, dimension, cx, cz);
    }

    public static void refreshSessionConfigs(MinecraftServer server) {
        SESSION_MANAGER.refresh(server);
    }

    public static void bumpAndRefreshSessionConfigs(MinecraftServer server) {
        if (!server.isSameThread()) {
            server.execute(() -> bumpAndRefreshSessionConfigs(server));
            return;
        }
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

    public static ChunkyGenerationService chunky() {
        return CHUNKY_SERVICE;
    }

    public static String diagnostics() {
        return NETWORKING_DIAGNOSTICS.diagnostics();
    }

    public static Component diagnosticsComponent(MinecraftServer server) {
        return NETWORKING_DIAGNOSTICS.diagnosticsComponent(server);
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

    public static void handleLostCityHints(LostCityHintsC2SPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && !isServerStopping())
            LostCityHintService.handle(player, payload);
    }

    public static void handleStructureTemplateRequest(
            dev.xantha.vss.networking.payloads.StructureTemplateRequestC2SPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && !isServerStopping() && VSSServerConfig.CONFIG.enabled)
            dev.xantha.vss.networking.server.session.StructureTemplateService.handle(player, payload);
    }

    public static void invalidateCachedColumn(ServerLevel level, int cx, int cz, long dirtyTimestamp) {
        if (isServerStopping()) {
            return;
        }
        COLUMN_CACHE.invalidateOlderThan(level.dimension(), cx, cz, dirtyTimestamp);
        PERSISTENT_COLUMN_WRITER.invalidate(level.dimension(), cx, cz, dirtyTimestamp);
    }

    /** 服务端事件与外部兼容入口；交付规则由注入的服务统一执行。 */
    public static boolean queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload) {
        return COLUMN_DELIVERY.queueColumn(player, state, payload);
    }

    public static boolean queueColumn(ServerPlayer player, PlayerRequestState state,
            VoxelColumnS2CPayload payload, boolean priority) {
        return COLUMN_DELIVERY.queueColumn(player, state, payload, priority);
    }

    public static boolean isColumnStillRelevant(ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, int cx, int cz) {
        return COLUMN_DELIVERY.isColumnStillRelevant(player, dimension, cx, cz);
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
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !isServerStopping()
                && player.connection.hasChannel(HandshakeRequestS2CPayload.TYPE)) {
            VSSNetworking.sendToPlayer(player, new HandshakeRequestS2CPayload());
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SESSION_MANAGER.dimensionChanged(player);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LostCityHintService.forgetPlayer(player.getUUID());
            dev.xantha.vss.networking.server.session.StructureTemplateService.forgetPlayer(player.getUUID());
            SERVER_RUNTIME.onPlayerLogout(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        dev.xantha.vss.networking.server.session.StructureTemplateService.tick(event.getServer());
        SERVER_RUNTIME.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        WorldgenProfileHolder.clear();
        SERVER_RUNTIME.onServerStarting();
    }

    @SubscribeEvent
    public static void onWorldgenDataReload(net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            WorldgenProfileHolder.clear();
            bumpAndRefreshSessionConfigs(event.getPlayerList().getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LostCityHintService.stop();
        SERVER_RUNTIME.onServerStopping(event.getServer());
        WorldgenProfileHolder.clear();
    }

}
