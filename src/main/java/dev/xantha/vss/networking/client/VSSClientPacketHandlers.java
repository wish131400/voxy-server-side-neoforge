package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class VSSClientPacketHandlers {
    private VSSClientPacketHandlers() {
    }

    public static boolean tryHandleIntegratedHostPayload(ServerPlayer player, CustomPacketPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null || minecraft.getSingleplayerServer() != player.server) {
            return false;
        }

        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null || !localPlayer.getUUID().equals(player.getUUID())) {
            return false;
        }

        minecraft.execute(() -> handleDirectPayload(payload));
        return true;
    }

    public static void handleSessionConfig(SessionConfigS2CPayload payload) {
        VSSClientNetworking.handleSessionConfig(payload);
    }

    public static void handleBatchResponse(BatchResponseS2CPayload payload) {
        VSSClientNetworking.handleBatchResponse(payload);
    }

    public static void handleDirtyColumns(DirtyColumnsS2CPayload payload) {
        VSSClientNetworking.handleDirtyColumns(payload);
    }

    public static void handleVoxelColumn(VoxelColumnS2CPayload payload) {
        VSSClientNetworking.handleVoxelColumn(payload);
    }

    public static void handleFarPlayers(FarPlayersS2CPayload payload) {
        FarPlayerClientRenderer.handleFarPlayers(payload);
    }

    private static void handleDirectPayload(CustomPacketPayload payload) {
        if (payload instanceof SessionConfigS2CPayload sessionConfig) {
            handleSessionConfig(sessionConfig);
        } else if (payload instanceof BatchResponseS2CPayload batchResponse) {
            handleBatchResponse(batchResponse);
        } else if (payload instanceof DirtyColumnsS2CPayload dirtyColumns) {
            handleDirtyColumns(dirtyColumns);
        } else if (payload instanceof VoxelColumnS2CPayload voxelColumn) {
            handleVoxelColumn(voxelColumn);
        } else if (payload instanceof FarPlayersS2CPayload farPlayers) {
            handleFarPlayers(farPlayers);
        }
    }
}
