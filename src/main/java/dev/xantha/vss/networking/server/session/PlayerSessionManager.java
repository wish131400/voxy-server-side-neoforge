package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard;

import dev.xantha.vss.networking.server.preload.ExistingColumnPreloader;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
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

/**
 * 服务端线程负责握手、协商配置、重握手与维度切换时的会话刷新。
 * <p>玩家状态唯一保存在注入的 registry；配置刷新更新 revision 并沿用原清理路径。
 * 会话激活通过回调通知装配入口，不反向访问网络门面取得服务。
 */
public final class PlayerSessionManager {
    private final ServerLifecycleGuard lifecycle;
    private final PlayerRequestRegistry playerRegistry;
    private final ExistingColumnPreloader existingColumnPreloader;
    private final Runnable onSessionActive;
    private final AtomicLong configRevision = new AtomicLong(1L);

    public PlayerSessionManager(
            PlayerRequestRegistry playerRegistry,
            ExistingColumnPreloader existingColumnPreloader,
            Runnable onSessionActive,
            ServerLifecycleGuard lifecycle) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
        this.playerRegistry = playerRegistry;
        this.existingColumnPreloader = existingColumnPreloader;
        this.onSessionActive = onSessionActive;
    }

    public void handleHandshake(ServerPlayer player, HandshakeC2SPayload payload) {
        if (lifecycle.isStopping()) {
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
        if (lifecycle.isStopping()) {
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
        if (lifecycle.isStopping()) {
            return createSessionConfig(player.level().getServer(), false);
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
        return createSessionConfig(player.level().getServer(), enabled);
    }

    private void sendSessionConfig(ServerPlayer player) {
        sendSessionConfig(player, VSSServerConfig.CONFIG.enabled);
    }

    private void sendSessionConfig(ServerPlayer player, boolean enabled) {
        VSSNetworking.sendToPlayer(player, createSessionConfig(player.level().getServer(), enabled));
    }

    private SessionConfigS2CPayload createSessionConfig(MinecraftServer server, boolean enabled) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return new SessionConfigS2CPayload(
                VSSConstants.PROTOCOL_VERSION,
                enabled && !lifecycle.isStopping(),
                config.effectiveColumnSyncDistanceChunks(),
                serverCapabilities(),
                config.nearSyncRateLimitPerTick,
                config.midSyncRateLimitPerTick,
                config.farSyncRateLimitPerTick,
                config.distantSyncRateLimitPerTick,
                config.effectiveGenerationConcurrencyLimitPerPlayer(!server.isDedicatedServer()),
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

    public void dimensionChanged(ServerPlayer player) {
        PlayerRequestState state = playerRegistry.get(player.getUUID());
        if (!lifecycle.isStopping() && state != null && state.supportsPredictiveWorldgen()
                && VSSServerConfig.CONFIG.enabled && VSSServerConfig.CONFIG.enablePredictionSync)
            sendWorldgenProfile(player.server, player);
    }

    private void sendWorldgenProfile(MinecraftServer server, ServerPlayer player) {
        sendWorldgenProfile(player.getGameProfile().getName(),
                () -> WorldgenProfileHolder.payloadFor(server, configRevision.get(), player.level().dimension()),
                payload -> VSSNetworking.sendToPlayer(player, payload));
    }

    static void sendWorldgenProfile(String playerName,
            java.util.function.Supplier<WorldgenProfileS2CPayload> profile,
            java.util.function.Consumer<WorldgenProfileS2CPayload> sender) {
        try {
            sender.accept(profile.get());
        } catch (RuntimeException | LinkageError failure) {
            // This boundary is shared by login, dimension changes and refresh
            // broadcasts. Mod linkage failures must not escape into server tick.
            VSSLogger.warn("VSS could not prepare or send the worldgen profile for "
                    + playerName + "; authoritative LOD sync remains active", failure);
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
