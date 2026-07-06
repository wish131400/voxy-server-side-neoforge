package dev.xantha.vss.networking.server.sending;


import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class QueuedColumnSender {
    private final PlayerRequestRegistry playerRegistry;
    private final int priorityColumnsPerTick;
    private final long diagnosticIntervalNanos;
    private volatile long lastSendDiagnosticNanos;

    public QueuedColumnSender(
            PlayerRequestRegistry playerRegistry,
            int priorityColumnsPerTick,
            long diagnosticIntervalNanos) {
        this.playerRegistry = playerRegistry;
        this.priorityColumnsPerTick = priorityColumnsPerTick;
        this.diagnosticIntervalNanos = diagnosticIntervalNanos;
    }

    public void flush(MinecraftServer server) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        long configuredLimit = VSSServerConfig.CONFIG.bandwidthBytesPerSecond();
        for (Map.Entry<UUID, PlayerRequestState> entry : playerRegistry.entries()) {
            PlayerRequestState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }

            long effectiveLimit = Math.min(configuredLimit, state.desiredBandwidth());
            int playerCx = player.getBlockX() >> 4;
            int playerCz = player.getBlockZ() >> 4;
            state.prepareSendOrder(playerCx, playerCz);
            flushPriorityPayloads(player, state, playerCx, playerCz, effectiveLimit);
            flushNormalPayloads(player, state, playerCx, playerCz, effectiveLimit);
        }
    }

    private void flushPriorityPayloads(
            ServerPlayer player,
            PlayerRequestState state,
            int playerCx,
            int playerCz,
            long effectiveLimit) {
        int sent = 0;
        while (sent < priorityColumnsPerTick) {
            PlayerRequestState.QueuedPayload queued = state.peekPriorityQueuedPayload(playerCx, playerCz);
            if (queued == null || !state.canSend(effectiveLimit)) {
                break;
            }
            if (state.pollPriorityQueuedPayload(queued) == null) {
                continue;
            }
            if (sendQueuedPayload(player, state, queued)) {
                sent++;
            }
        }
    }

    private void flushNormalPayloads(
            ServerPlayer player,
            PlayerRequestState state,
            int playerCx,
            int playerCz,
            long effectiveLimit) {
        while (state.queuedPayloadCount() > 0) {
            PlayerRequestState.QueuedPayload queued = state.peekNormalQueuedPayload(playerCx, playerCz);
            if (queued == null || !state.canSend(effectiveLimit)) {
                break;
            }
            if (state.pollNormalQueuedPayload(queued) == null) {
                continue;
            }
            sendQueuedPayload(player, state, queued);
        }
    }

    private boolean sendQueuedPayload(ServerPlayer player, PlayerRequestState state, PlayerRequestState.QueuedPayload queued) {
        VoxelColumnS2CPayload payload = queued.payload();
        if (state.consumeCancelled(payload.requestId())) {
            return false;
        }
        if (!isPayloadStillRelevant(player, payload)) {
            state.clearRequest(payload.requestId());
            return false;
        }
        VSSNetworking.sendToPlayer(player, payload);
        markClientKnownAfterSend(state, payload);
        state.recordSend(queued.estimatedBytes());
        state.clearRequest(payload.requestId());
        logColumnSend(player, payload, state);
        return true;
    }

    private static boolean isPayloadStillRelevant(ServerPlayer player, VoxelColumnS2CPayload payload) {
        return VSSServerNetworking.isColumnStillRelevant(player, payload.dimension(), payload.chunkX(), payload.chunkZ());
    }

    private static void markClientKnownAfterSend(PlayerRequestState state, VoxelColumnS2CPayload payload) {
        if (payload.completeColumn() && payload.columnTimestamp() > 0L) {
            state.markClientKnownColumn(
                    payload.dimension(),
                    PositionUtil.packPosition(payload.chunkX(), payload.chunkZ()),
                    payload.columnTimestamp());
        }
    }

    private void logColumnSend(ServerPlayer player, VoxelColumnS2CPayload payload, PlayerRequestState state) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSendDiagnosticNanos < diagnosticIntervalNanos) {
            return;
        }
        lastSendDiagnosticNanos = now;
        VSSLogger.debug("LOD column sent to " + player.getGameProfile().getName()
                + ": chunk=" + payload.chunkX() + "," + payload.chunkZ()
                + ", bytes=" + payload.estimatedBytes()
                + ", queued=" + state.queuedPayloadCount()
                + ", queuedBytes=" + state.queuedBytes()
                + ", totalSentBytes=" + state.totalBytesSent());
    }
}
