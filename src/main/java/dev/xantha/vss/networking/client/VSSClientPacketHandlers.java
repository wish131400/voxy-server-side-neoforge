package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;

public final class VSSClientPacketHandlers {
    private VSSClientPacketHandlers() {
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
}
