package dev.xantha.vss.networking.server.session;


import dev.xantha.vss.networking.server.preload.ExistingColumnPreloader;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerSessionManager {
    private final PlayerRequestRegistry playerRegistry;
    private final ExistingColumnPreloader existingColumnPreloader;
    private final Runnable onSessionActive;
    private final AtomicLong configRevision = new AtomicLong(1L);

    public PlayerSessionManager(
            PlayerRequestRegistry playerRegistry,
            ExistingColumnPreloader existingColumnPreloader,
            Runnable onSessionActive) {
        this.playerRegistry = playerRegistry;
        this.existingColumnPreloader = existingColumnPreloader;
        this.onSessionActive = onSessionActive;
    }

    public void handleHandshake(ServerPlayer player, HandshakeC2SPayload payload) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        SessionConfigS2CPayload config = configurePlayerSession(
                player,
                payload.protocolVersion(),
                payload.capabilities(),
                "Player");
        VSSNetworking.sendToPlayer(player, config);
        if (config.enabled()
                && (payload.capabilities() & VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN) != 0
                && VSSServerConfig.CONFIG.enablePredictionSync) {
            sendWorldgenProfile(player.server, player);
        }
    }

    public void bumpAndRefresh(MinecraftServer server) {
        configRevision.incrementAndGet();
        primeAllSendCredits();
        refresh(server);
    }

    public void refresh(MinecraftServer server) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        for (UUID uuid : playerRegistry.playerIds()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                sendSessionConfig(player);
                PlayerRequestState state = playerRegistry.get(uuid);
                if (state != null
                        && state.supportsPredictiveWorldgen()
                        && VSSServerConfig.CONFIG.enabled
                        && VSSServerConfig.CONFIG.enablePredictionSync) {
                    sendWorldgenProfile(server, player);
                }
            }
        }
    }

    private void primeAllSendCredits() {
        for (PlayerRequestState state : playerRegistry.states()) {
            state.primeSendCredit(Long.MAX_VALUE);
        }
    }

    private SessionConfigS2CPayload configurePlayerSession(
            ServerPlayer player,
            int clientProtocolVersion,
            int clientCapabilities,
            String logPrefix) {
        if (VSSServerNetworking.isServerStopping()) {
            return createSessionConfig(false);
        }
        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean compatible = isCompatibleClient(clientProtocolVersion, clientCapabilities);
        boolean enabled = config.enabled && compatible;
        if (compatible && enabled) {
            PlayerRequestState state = playerRegistry.get(player.getUUID());
            boolean created = state == null;
            if (created) {
                state = new PlayerRequestState();
                playerRegistry.put(player.getUUID(), state);
            }
            state.setClientCapabilities(clientCapabilities);
            if (created) {
                state.primeSendCredit(Long.MAX_VALUE);
                existingColumnPreloader.schedule(player, state);
                VSSLogger.info(logPrefix + " " + player.getGameProfile().getName() + " registered for VSS LOD sync");
            }
            onSessionActive.run();
        } else if (!compatible) {
            logIncompatibleClient(logPrefix + " " + player.getGameProfile().getName(), clientProtocolVersion, clientCapabilities);
        }
        return createSessionConfig(enabled);
    }

    private void sendSessionConfig(ServerPlayer player) {
        sendSessionConfig(player, VSSServerConfig.CONFIG.enabled);
    }

    private void sendSessionConfig(ServerPlayer player, boolean enabled) {
        VSSNetworking.sendToPlayer(player, createSessionConfig(enabled));
    }

    private SessionConfigS2CPayload createSessionConfig(boolean enabled) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return new SessionConfigS2CPayload(
                VSSConstants.PROTOCOL_VERSION,
                enabled && !VSSServerNetworking.isServerStopping(),
                config.effectiveColumnSyncDistanceChunks(),
                serverCapabilities(),
                config.nearSyncRateLimitPerTick,
                config.midSyncRateLimitPerTick,
                config.farSyncRateLimitPerTick,
                config.distantSyncRateLimitPerTick,
                config.generationConcurrencyLimitPerPlayer,
                config.enableChunkGeneration,
                config.totalBandwidthBytesPerSecond(),
                configRevision.get());
    }

    private static int serverCapabilities() {
        int capabilities = VSSConstants.CAPABILITY_VOXEL_COLUMNS;
        if (LodByteCompression.isZstdAvailable()) {
            capabilities |= VSSConstants.CAPABILITY_ZSTD_COLUMNS;
        }
        if (VSSServerConfig.CONFIG.enablePredictionSync) {
            capabilities |= VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN;
        }
        return capabilities;
    }

    private void sendWorldgenProfile(MinecraftServer server, ServerPlayer player) {
        try {
            WorldgenProfileS2CPayload payload = WorldgenProfileHolder.payloadFor(server, configRevision.get());
            VSSNetworking.sendToPlayer(player, payload);
        } catch (RuntimeException exception) {
            // A third-party generator may expose a density codec unavailable
            // to this runtime. Keep the VSS session alive and fall back to
            // authoritative columns instead of failing the login.
            VSSLogger.warn("VSS could not encode the worldgen profile for "
                    + player.getGameProfile().getName() + "; predictive LOD disabled", exception);
        }
    }

    public static boolean isCompatibleClient(int clientProtocolVersion, int clientCapabilities) {
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
}
