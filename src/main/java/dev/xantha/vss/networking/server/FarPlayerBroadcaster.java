package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class FarPlayerBroadcaster {
    private static int tickCounter;

    private FarPlayerBroadcaster() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        VSSServerConfig config = VSSServerConfig.CONFIG;
        if (!config.enabled || !config.farPlayerSyncEnabled || !VSSServerNetworking.hasRegisteredPlayers()) {
            return;
        }
        if (++tickCounter < config.farPlayerSyncIntervalTicks) {
            return;
        }
        tickCounter = 0;
        broadcast(event.getServer(), config);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        tickCounter = 0;
    }

    private static void broadcast(MinecraftServer server, VSSServerConfig config) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.size() < 2) {
            return;
        }

        double maxDistanceSqr = square(config.lodDistanceChunks * 16.0D);
        double minDistanceSqr = square(Math.max(2, server.getPlayerList().getViewDistance()) * 16.0D);
        for (ServerPlayer viewer : players) {
            if (!VSSServerNetworking.isRegistered(viewer)) {
                continue;
            }

            List<FarPlayersS2CPayload.Entry> entries = new ArrayList<>();
            for (ServerPlayer target : players) {
                if (target == viewer || target.isSpectator() || !target.serverLevel().dimension().equals(viewer.serverLevel().dimension())) {
                    continue;
                }

                double distanceSqr = target.distanceToSqr(viewer);
                if (distanceSqr <= minDistanceSqr || distanceSqr > maxDistanceSqr) {
                    continue;
                }

                entries.add(new FarPlayersS2CPayload.Entry(
                        target.getUUID(),
                        target.getGameProfile().getName(),
                        target.getX(),
                        target.getY(),
                        target.getZ(),
                        target.getYRot(),
                        target.getXRot(),
                        target.getYHeadRot(),
                        target.isCrouching(),
                        target.isSprinting()));
                if (entries.size() >= VSSConstants.MAX_FAR_PLAYER_ENTRIES) {
                    break;
                }
            }

            VSSNetworking.sendToPlayer(viewer, new FarPlayersS2CPayload(entries.toArray(FarPlayersS2CPayload.Entry[]::new)));
        }
    }

    private static double square(double value) {
        return value * value;
    }
}
