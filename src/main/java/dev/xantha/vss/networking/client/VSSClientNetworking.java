package dev.xantha.vss.networking.client;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class VSSClientNetworking {
    private static volatile boolean serverEnabled;
    private static volatile int serverLodDistance;
    private static volatile boolean waitingForHandshake;
    private static volatile boolean handshakeSent;
    private static int handshakeRetryTicks;
    private static volatile LodRequestManager requestManager;
    private static final ClientColumnProcessor COLUMN_PROCESSOR = new ClientColumnProcessor();
    private static final AtomicLong columnsReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static final int HANDSHAKE_RETRY_INTERVAL_TICKS = 40;
    private static final int HANDSHAKE_FAILED_RETRY_INTERVAL_TICKS = 20;
    private static final long COLUMN_RECEIVE_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final long INTEGRATED_HOST_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static volatile long lastColumnReceiveDiagnosticNanos;
    private static volatile long lastIntegratedHostDiagnosticNanos;

    private VSSClientNetworking() {
    }

    public static boolean isServerEnabled() {
        return serverEnabled;
    }

    public static boolean isClientLodSessionActive() {
        return serverEnabled && COLUMN_PROCESSOR.isActive() && isClientWorldReady();
    }

    public static int getServerLodDistance() {
        return serverLodDistance;
    }

    static int getQueuedColumnCount() {
        return COLUMN_PROCESSOR.getQueuedCount();
    }

    public static long getColumnsReceived() {
        return columnsReceived.get();
    }

    public static long getBytesReceived() {
        return bytesReceived.get();
    }

    public static long getColumnsDropped() {
        return COLUMN_PROCESSOR.getColumnsDropped();
    }

    public static void handleSessionConfig(SessionConfigS2CPayload payload) {
        if (!isClientWorldReady()) {
            discardSession();
            return;
        }
        if (payload.protocolVersion() != VSSConstants.PROTOCOL_VERSION) {
            VSSLogger.warn("Server has incompatible VSS protocol " + payload.protocolVersion());
            discardSession();
            return;
        }
        if (payload.enabled() && (payload.serverCapabilities() & VSSConstants.CAPABILITY_ZSTD_COLUMNS) == 0) {
            VSSLogger.warn("VSS LOD session rejected: server does not advertise Zstd column support");
            discardSession();
            return;
        }
        if (payload.enabled() && !LodByteCompression.isZstdAvailable()) {
            VSSLogger.warn("VSS LOD session rejected: client Zstd support is unavailable");
            discardSession();
            return;
        }

        waitingForHandshake = false;
        handshakeSent = true;
        handshakeRetryTicks = 0;
        serverEnabled = payload.enabled();
        serverLodDistance = payload.lodDistanceChunks();
        if (payload.enabled()) {
            COLUMN_PROCESSOR.beginSession();
            LodRequestManager manager = new LodRequestManager();
            manager.onSessionConfig(payload);
            requestManager = manager;
            sendBandwidthPreference();

            boolean hasConsumers = VSSApi.hasVoxelConsumers();
            if (!hasConsumers) {
                VSSLogger.warn("VSS LOD session started but no voxel consumers registered! LOD data will not be processed.");
                VSSLogger.warn("Make sure Voxy mod is loaded or register a custom consumer via VSSApi.registerColumnConsumer()");
            }

            VSSLogger.info("VSS LOD session ready: distance=" + payload.lodDistanceChunks()
                    + " chunks, generation=" + (payload.generationEnabled() ? "enabled" : "disabled")
                    + ", consumers=" + hasConsumers);
        } else {
            LodRequestManager manager = requestManager;
            requestManager = null;
            if (manager != null) {
                manager.disconnect();
            }
            FarPlayerClientRenderer.clear();
            COLUMN_PROCESSOR.shutdown();
        }
    }

    public static void handleBatchResponse(BatchResponseS2CPayload payload) {
        if (!serverEnabled || !isClientWorldReady()) {
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager == null) {
            return;
        }
        for (int i = 0; i < payload.count(); i++) {
            int requestId = payload.requestIds()[i];
            switch (payload.responseTypes()[i]) {
                case VSSConstants.RESPONSE_RATE_LIMITED -> manager.onRateLimited(requestId);
                case VSSConstants.RESPONSE_UP_TO_DATE -> manager.onColumnUpToDate(requestId);
                case VSSConstants.RESPONSE_NOT_GENERATED -> manager.onColumnNotGenerated(requestId);
                default -> VSSLogger.warn("Unknown batch response type: " + payload.responseTypes()[i]);
            }
        }
    }

    public static void handleDirtyColumns(DirtyColumnsS2CPayload payload) {
        if (!serverEnabled || !isClientWorldReady()) {
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.onDirtyColumns(payload.dirtyPositions(), payload.dirtyTimestamps());
        }
    }

    public static void handleVoxelColumn(VoxelColumnS2CPayload payload) {
        if (!isClientLodSessionActive()) {
            return;
        }
        columnsReceived.incrementAndGet();
        bytesReceived.addAndGet(payload.estimatedBytes());
        logColumnReceive(payload);
        LodRequestManager manager = requestManager;
        LodRequestManager.ColumnReceiveResult receiveResult;
        if (payload.requestId() < 0) {
            receiveResult = new LodRequestManager.ColumnReceiveResult(true, true, Long.MIN_VALUE);
        } else if (manager != null) {
            receiveResult = manager.onColumnReceived(payload.requestId(), payload.columnTimestamp());
        } else {
            receiveResult = new LodRequestManager.ColumnReceiveResult(false, false, Long.MIN_VALUE);
        }
        boolean replaceMissingSections = receiveResult.knownRequest() && payload.completeColumn();
        boolean queued = COLUMN_PROCESSOR.offer(
                payload,
                receiveResult.knownRequest(),
                receiveResult.priority(),
                replaceMissingSections);
        if (!queued && manager != null && receiveResult.packedPosition() != Long.MIN_VALUE) {
            manager.onColumnProcessingFailed(payload.dimension(), payload.chunkX(), payload.chunkZ());
        }
    }

    public static void onColumnProcessingFailed(ResourceKey<Level> dimension, int cx, int cz) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> onColumnProcessingFailed(dimension, cx, cz));
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.onColumnProcessingFailed(dimension, cx, cz);
        }
    }

    public static void onClientChunkDropped(ResourceKey<Level> dimension, int cx, int cz) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> onClientChunkDropped(dimension, cx, cz));
            return;
        }
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.onClientChunkDropped(dimension, cx, cz);
        }
    }

    public static void forceLodResync(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> forceLodResync(reason));
            return;
        }
        LodRequestManager manager = requestManager;
        if (!serverEnabled || manager == null || !isClientWorldReady()) {
            return;
        }
        COLUMN_PROCESSOR.beginSession();
        manager.forceResync();
        VSSLogger.info("VSS LOD resync requested: " + reason);
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        serverEnabled = false;
        serverLodDistance = 0;
        waitingForHandshake = false;
        handshakeSent = false;
        handshakeRetryTicks = 0;
        requestManager = null;
        if (!VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        waitingForHandshake = true;
        handshakeRetryTicks = 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopClientSessionForWorldShutdown();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ensureHandshakePending();
        tryPendingHandshake();
        LodRequestManager manager = requestManager;
        if (manager != null && serverEnabled) {
            manager.tick();
        }
        COLUMN_PROCESSOR.scheduleProcessing(serverEnabled);
        ModCompat.clientTick();
    }

    public static void sendBandwidthPreference() {
        if (!serverEnabled) {
            return;
        }
        long desiredRate = VSSClientConfig.CONFIG.desiredBandwidthKbps > 0
                ? (long) VSSClientConfig.CONFIG.desiredBandwidthKbps * 1000L / 8L
                : 0L;
        sendBandwidthUpdate(new BandwidthUpdateC2SPayload(desiredRate));
    }

    public static void stopClientSessionForWorldShutdown() {
        stopClientSession(true);
    }

    static void sendBatchRequest(BatchChunkRequestC2SPayload payload) {
        if (trySendToIntegratedServer(
                "LOD batch request count=" + payload.count(),
                serverPlayer -> VSSServerNetworking.handleIntegratedBatchRequest(serverPlayer, payload))) {
            return;
        }
        VSSNetworking.sendToServer(payload);
    }

    static void sendCancelRequest(CancelRequestC2SPayload payload) {
        if (trySendToIntegratedServer(
                "cancel request id=" + payload.requestId(),
                serverPlayer -> VSSServerNetworking.handleIntegratedCancel(serverPlayer, payload))) {
            return;
        }
        VSSNetworking.sendToServer(payload);
    }

    private static void sendBandwidthUpdate(BandwidthUpdateC2SPayload payload) {
        try {
            if (trySendToIntegratedServer(
                    "bandwidth update",
                    serverPlayer -> VSSServerNetworking.handleIntegratedBandwidthUpdate(serverPlayer, payload))) {
                return;
            }
            VSSNetworking.sendToServer(payload);
        } catch (Exception e) {
            VSSLogger.debug("Bandwidth preference send failed: " + e.getMessage());
        }
    }

    private static boolean trySendToIntegratedServer(String action, java.util.function.Consumer<ServerPlayer> consumer) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        LocalPlayer localPlayer = minecraft.player;
        if (integratedServerPlayer(server, localPlayer) == null) {
            return false;
        }

        logIntegratedHostDirect("Integrated host direct C2S: " + action);
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(localPlayer.getUUID());
            if (serverPlayer != null) {
                consumer.accept(serverPlayer);
            } else {
                VSSLogger.debug("Integrated host direct C2S skipped because the local server player is not ready");
            }
        });
        return true;
    }

    private static void tryPendingHandshake() {
        if (!waitingForHandshake || requestManager != null || !VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || !isClientWorldReady()) {
            return;
        }
        IntegratedServer integratedServer = mc.getSingleplayerServer();
        if (integratedServerPlayer(integratedServer, mc.player) != null) {
            tryIntegratedServerHandshake(mc, integratedServer);
            return;
        }

        if (handshakeRetryTicks > 0) {
            handshakeRetryTicks--;
            return;
        }

        boolean sent = sendHandshake("Handshake send failed: ");
        if (sent) {
            if (!handshakeSent) {
                VSSLogger.debug("VSS handshake sent; waiting for session config");
            }
            handshakeSent = true;
            handshakeRetryTicks = HANDSHAKE_RETRY_INTERVAL_TICKS;
        } else {
            handshakeRetryTicks = HANDSHAKE_FAILED_RETRY_INTERVAL_TICKS;
        }
    }

    private static void ensureHandshakePending() {
        if (waitingForHandshake || handshakeSent || requestManager != null || serverEnabled || !VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null && isClientWorldReady()) {
            waitingForHandshake = true;
            handshakeRetryTicks = 0;
        }
    }

    private static void tryIntegratedServerHandshake(Minecraft minecraft, IntegratedServer server) {
        if (handshakeRetryTicks > 0) {
            handshakeRetryTicks--;
            return;
        }

        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null) {
            return;
        }

        handshakeSent = true;
        handshakeRetryTicks = HANDSHAKE_RETRY_INTERVAL_TICKS;
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(localPlayer.getUUID());
            if (serverPlayer == null) {
                return;
            }
            SessionConfigS2CPayload config = VSSServerNetworking.registerIntegratedHost(
                    serverPlayer,
                    VSSConstants.PROTOCOL_VERSION,
                    clientCapabilities());
            minecraft.execute(() -> {
                VSSLogger.info("VSS integrated host session ready: distance=" + config.lodDistanceChunks()
                        + " chunks, generation=" + (config.generationEnabled() ? "enabled" : "disabled"));
                handleSessionConfig(config);
            });
        });
    }

    private static void logIntegratedHostDirect(String message) {
        long now = System.nanoTime();
        if (now - lastIntegratedHostDiagnosticNanos < INTEGRATED_HOST_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastIntegratedHostDiagnosticNanos = now;
        VSSLogger.debug(message);
    }

    private static boolean sendHandshake(String failurePrefix) {
        try {
            VSSNetworking.sendToServer(new HandshakeC2SPayload(VSSConstants.PROTOCOL_VERSION, clientCapabilities()));
            return true;
        } catch (Exception e) {
            VSSLogger.debug(failurePrefix + e.getMessage());
            return false;
        }
    }

    private static int clientCapabilities() {
        int clientCaps = VSSApi.hasVoxelConsumers() ? VSSConstants.CAPABILITY_VOXEL_COLUMNS : 0;
        if (LodByteCompression.isZstdAvailable()) {
            clientCaps |= VSSConstants.CAPABILITY_ZSTD_COLUMNS;
        }
        return clientCaps;
    }

    private static ServerPlayer integratedServerPlayer(IntegratedServer server, LocalPlayer localPlayer) {
        if (server == null || localPlayer == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(localPlayer.getUUID());
    }

    private static boolean isClientWorldReady() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        return level != null && player != null && !player.isRemoved();
    }

    private static void discardSession() {
        stopClientSession(false);
    }

    private static void stopClientSession(boolean resetStats) {
        LodRequestManager manager = requestManager;
        requestManager = null;
        serverEnabled = false;
        serverLodDistance = 0;
        waitingForHandshake = false;
        handshakeSent = false;
        handshakeRetryTicks = 0;
        if (manager != null) {
            manager.disconnect();
        }
        FarPlayerClientRenderer.clear();
        COLUMN_PROCESSOR.shutdown();
        if (resetStats) {
            COLUMN_PROCESSOR.resetStats();
            columnsReceived.set(0);
            bytesReceived.set(0);
            lastColumnReceiveDiagnosticNanos = 0L;
            lastIntegratedHostDiagnosticNanos = 0L;
        }
    }

    private static void logColumnReceive(VoxelColumnS2CPayload payload) {
        long now = System.nanoTime();
        if (now - lastColumnReceiveDiagnosticNanos < COLUMN_RECEIVE_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastColumnReceiveDiagnosticNanos = now;
        VSSLogger.debug("LOD columns received: total=" + columnsReceived.get()
                + ", bytes=" + bytesReceived.get()
                + ", queued=" + COLUMN_PROCESSOR.getQueuedCount()
                + ", last=" + payload.chunkX() + "," + payload.chunkZ()
                + ", sectionsBytes=" + payload.decompressedSections().length);
    }
}
