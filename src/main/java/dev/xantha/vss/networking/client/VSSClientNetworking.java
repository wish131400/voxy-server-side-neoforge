package dev.xantha.vss.networking.client;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;

public final class VSSClientNetworking {
    private static volatile boolean serverEnabled;
    private static volatile int serverLodDistance;
    private static volatile boolean waitingForLanPublish;
    private static volatile boolean lanHostHandshakeSent;
    private static int lanHostHandshakeRetryTicks;
    private static volatile LodRequestManager requestManager;
    private static final ClientColumnProcessor COLUMN_PROCESSOR = new ClientColumnProcessor();
    private static final AtomicLong columnsReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static final int LAN_HANDSHAKE_RETRY_INTERVAL_TICKS = 40;
    private static final int LAN_HANDSHAKE_FAILED_RETRY_INTERVAL_TICKS = 20;

    private VSSClientNetworking() {
    }

    public static boolean isServerEnabled() {
        return serverEnabled;
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

    public static void handleSessionConfig(SessionConfigS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        if (payload.protocolVersion() != VSSConstants.PROTOCOL_VERSION) {
            VSSLogger.warn("Server has incompatible VSS protocol " + payload.protocolVersion());
            serverEnabled = false;
            waitingForLanPublish = false;
            lanHostHandshakeRetryTicks = 0;
            return;
        }

        waitingForLanPublish = false;
        lanHostHandshakeSent = true;
        lanHostHandshakeRetryTicks = 0;
        serverEnabled = payload.enabled();
        serverLodDistance = payload.lodDistanceChunks();
        if (payload.enabled()) {
            LodRequestManager manager = new LodRequestManager();
            manager.onSessionConfig(payload);
            requestManager = manager;
            sendBandwidthPreference();
            VSSLogger.info("Server session config received (LOD distance: " + payload.lodDistanceChunks()
                    + " chunks, generation: " + (payload.generationEnabled() ? "enabled" : "disabled") + ")");
        }
    }

    public static void handleBatchResponse(BatchResponseS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
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

    public static void handleDirtyColumns(DirtyColumnsS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.onDirtyColumns(payload.dirtyPositions());
        }
    }

    public static void handleVoxelColumn(VoxelColumnS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        columnsReceived.incrementAndGet();
        bytesReceived.addAndGet(payload.estimatedBytes());
        LodRequestManager manager = requestManager;
        LodRequestManager.ColumnReceiveResult receiveResult = new LodRequestManager.ColumnReceiveResult(false, false);
        if (manager != null) {
            receiveResult = manager.onColumnReceived(payload.requestId(), payload.columnTimestamp());
        }
        COLUMN_PROCESSOR.offer(payload, receiveResult.replaceMissingSections(), receiveResult.knownRequest());
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        serverEnabled = false;
        serverLodDistance = 0;
        waitingForLanPublish = false;
        lanHostHandshakeSent = false;
        lanHostHandshakeRetryTicks = 0;
        requestManager = null;
        if (!VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer() && !Boolean.getBoolean("vss.test.integratedServer")) {
            waitingForLanPublish = true;
            return;
        }

        sendHandshake("Handshake send failed: ");
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.disconnect();
        }
        FarPlayerClientRenderer.clear();
        COLUMN_PROCESSOR.shutdown();
        COLUMN_PROCESSOR.resetStats();
        serverEnabled = false;
        serverLodDistance = 0;
        waitingForLanPublish = false;
        lanHostHandshakeSent = false;
        lanHostHandshakeRetryTicks = 0;
        columnsReceived.set(0);
        bytesReceived.set(0);
        requestManager = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tryLanHostHandshake();
        LodRequestManager manager = requestManager;
        if (manager != null && serverEnabled) {
            manager.tick();
        }
        COLUMN_PROCESSOR.scheduleProcessing(serverEnabled);
    }

    public static void sendBandwidthPreference() {
        if (!serverEnabled) {
            return;
        }
        long desiredRate = VSSClientConfig.CONFIG.desiredBandwidthMiB > 0
                ? (long) VSSClientConfig.CONFIG.desiredBandwidthMiB * 1024L * 1024L
                : 0L;
        try {
            VSSNetworking.sendToServer(new BandwidthUpdateC2SPayload(desiredRate));
        } catch (Exception e) {
            VSSLogger.debug("Bandwidth preference send failed: " + e.getMessage());
        }
    }

    private static void tryLanHostHandshake() {
        if (!waitingForLanPublish || requestManager != null || !VSSClientConfig.CONFIG.receiveServerLods) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || !server.isPublished()) {
            return;
        }

        if (lanHostHandshakeRetryTicks > 0) {
            lanHostHandshakeRetryTicks--;
            return;
        }

        boolean sent = sendHandshake("LAN host handshake send failed: ");
        if (sent) {
            if (!lanHostHandshakeSent) {
                VSSLogger.info("LAN host VSS handshake sent; waiting for session config");
            }
            lanHostHandshakeSent = true;
            lanHostHandshakeRetryTicks = LAN_HANDSHAKE_RETRY_INTERVAL_TICKS;
        } else {
            lanHostHandshakeRetryTicks = LAN_HANDSHAKE_FAILED_RETRY_INTERVAL_TICKS;
        }
    }

    private static boolean sendHandshake(String failurePrefix) {
        int clientCaps = VSSApi.hasVoxelConsumers() ? VSSConstants.CAPABILITY_VOXEL_COLUMNS : 0;
        try {
            VSSNetworking.sendToServer(new HandshakeC2SPayload(VSSConstants.PROTOCOL_VERSION, clientCaps));
            return true;
        } catch (Exception e) {
            VSSLogger.debug(failurePrefix + e.getMessage());
            return false;
        }
    }
}
