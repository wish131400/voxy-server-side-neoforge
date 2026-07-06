package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class VSSClientPacketHandlers {
    private static final long INTEGRATED_HOST_DELIVERY_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static volatile long lastIntegratedHostDeliveryDiagnosticNanos;

    private VSSClientPacketHandlers() {
    }

    public static boolean tryHandleIntegratedHostPayload(ServerPlayer player, CustomPacketPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || server != player.server) {
            return false;
        }

        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null) {
            return false;
        }

        ServerPlayer integratedPlayer = server.getPlayerList().getPlayer(localPlayer.getUUID());
        if (integratedPlayer == null || integratedPlayer != player) {
            return false;
        }

        logIntegratedHostDelivery(payload);
        runOnClientThread(() -> handleDirectPayload(payload));
        return true;
    }

    public static void handleSessionConfig(SessionConfigS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleSessionConfig(payload));
    }

    public static void handleBatchResponse(BatchResponseS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleBatchResponse(payload));
    }

    public static void handleDirtyColumns(DirtyColumnsS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleDirtyColumns(payload));
    }

    public static void handleVoxelColumn(VoxelColumnS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleVoxelColumn(payload));
    }

    public static void handleFarPlayers(FarPlayersS2CPayload payload) {
        runOnClientThread(() -> FarPlayerClientRenderer.handleFarPlayers(payload));
    }

    private static void handleDirectPayload(CustomPacketPayload payload) {
        if (payload instanceof SessionConfigS2CPayload sessionConfig) {
            VSSClientNetworking.handleSessionConfig(sessionConfig);
        } else if (payload instanceof BatchResponseS2CPayload batchResponse) {
            VSSClientNetworking.handleBatchResponse(batchResponse);
        } else if (payload instanceof DirtyColumnsS2CPayload dirtyColumns) {
            VSSClientNetworking.handleDirtyColumns(dirtyColumns);
        } else if (payload instanceof VoxelColumnS2CPayload voxelColumn) {
            VSSClientNetworking.handleVoxelColumn(voxelColumn);
        } else if (payload instanceof FarPlayersS2CPayload farPlayers) {
            FarPlayerClientRenderer.handleFarPlayers(farPlayers);
        }
    }

    private static void runOnClientThread(Runnable task) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            task.run();
        } else {
            minecraft.execute(task);
        }
    }

    private static void logIntegratedHostDelivery(CustomPacketPayload payload) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastIntegratedHostDeliveryDiagnosticNanos < INTEGRATED_HOST_DELIVERY_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastIntegratedHostDeliveryDiagnosticNanos = now;
        VSSLogger.debug("Integrated host direct S2C delivered: " + payload.getClass().getSimpleName());
    }
}
