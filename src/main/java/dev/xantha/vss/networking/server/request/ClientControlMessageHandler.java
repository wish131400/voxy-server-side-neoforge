package dev.xantha.vss.networking.server.request;


import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import net.minecraft.server.level.ServerPlayer;

public final class ClientControlMessageHandler {
    private final PlayerRequestRegistry playerRegistry;
    private final ChunkGenerationService generationService;

    public ClientControlMessageHandler(
            PlayerRequestRegistry playerRegistry,
            ChunkGenerationService generationService) {
        this.playerRegistry = playerRegistry;
        this.generationService = generationService;
    }

    public void handleCancel(ServerPlayer player, CancelRequestC2SPayload payload) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        PlayerRequestState state = playerRegistry.get(player.getUUID());
        if (state != null) {
            state.cancel(payload.requestId());
            generationService.cancelRequest(player.getUUID(), payload.requestId());
        }
    }

    public void handleBandwidthUpdate(ServerPlayer player, BandwidthUpdateC2SPayload payload) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        PlayerRequestState state = playerRegistry.get(player.getUUID());
        if (state != null) {
            state.setDesiredBandwidth(payload.desiredRate());
            state.primeSendCredit(Long.MAX_VALUE);
        }
    }

    public void handleRegionPresence(ServerPlayer player, RegionPresenceC2SPayload payload) {
        if (VSSServerNetworking.isServerStopping() || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        PlayerRequestState state = playerRegistry.get(player.getUUID());
        if (state != null && player.serverLevel().dimension().equals(payload.dimension())) {
            try {
                state.updateClientKnownColumns(payload.dimension(), payload);
                DirtyColumnBroadcaster.sendStaleColumnsForPresence(player, payload);
            } catch (RuntimeException e) {
                VSSLogger.debug("Ignored invalid LOD presence summary from "
                        + player.getGameProfile().getName() + ": " + e.getMessage());
            }
        }
    }
}
