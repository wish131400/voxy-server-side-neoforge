package dev.xantha.vss.networking.server.sending;


import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class QueuedColumnSender {
    private final PlayerRequestRegistry playerRegistry;
    private final int priorityColumnsPerTick;
    private final long diagnosticIntervalNanos;
    private final Map<UUID, Boolean> priorityFirstByPlayer = new HashMap<>();
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
        priorityFirstByPlayer.keySet().retainAll(playerRegistry.playerIds());
        for (Map.Entry<UUID, PlayerRequestState> entry : playerRegistry.entries()) {
            PlayerRequestState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                priorityFirstByPlayer.remove(entry.getKey());
                continue;
            }

            long effectiveLimit = Math.min(configuredLimit, state.desiredBandwidth());
            int playerCx = player.getBlockX() >> 4;
            int playerCz = player.getBlockZ() >> 4;
            state.prepareSendOrder(playerCx, playerCz);
            boolean priorityFirst = priorityFirstByPlayer.getOrDefault(entry.getKey(), true);
            priorityFirst = flushQueuedPayloads(player, state, playerCx, playerCz, effectiveLimit, priorityFirst);
            priorityFirstByPlayer.put(entry.getKey(), priorityFirst);
        }
    }

    private boolean flushQueuedPayloads(
            ServerPlayer player,
            PlayerRequestState state,
            int playerCx,
            int playerCz,
            long effectiveLimit,
            boolean priorityFirst) {
        int prioritySent = 0;
        while (state.queuedPayloadCount() > 0) {
            boolean hasPriority = state.priorityQueuedPayloadCount() > 0;
            boolean hasNormal = state.normalQueuedPayloadCount() > 0;
            PlayerRequestState.QueuedPayloadBatch priorityBatch = hasPriority
                    ? state.peekPriorityQueuedBatch(playerCx, playerCz)
                    : null;
            PlayerRequestState.QueuedPayloadBatch normalBatch = hasNormal
                    ? state.peekNormalQueuedBatch(playerCx, playerCz)
                    : null;
            boolean canSendPriority = canSendPriority(state, priorityBatch, prioritySent, effectiveLimit);
            boolean canSendNormal = normalBatch != null && state.canSend(false, effectiveLimit);
            PlayerRequestState.QueuedPayloadBatch batch = nextBatch(
                    priorityBatch,
                    normalBatch,
                    playerCx,
                    playerCz,
                    priorityFirst,
                    prioritySent,
                    canSendPriority,
                    canSendNormal);
            if (batch == null) {
                break;
            }

            boolean sentPriority = batch.priority();
            int queuedBefore = state.queuedPayloadCount();
            boolean sent = sendQueuedPayloadBatch(player, state, batch, effectiveLimit);
            if (sent && sentPriority) {
                prioritySent++;
            }
            if (sent && hasPriority && hasNormal) {
                priorityFirst = !sentPriority;
            }
            if (!sent && state.queuedPayloadCount() == queuedBefore) {
                break;
            }
        }
        return priorityFirst;
    }

    private boolean canSendPriority(
            PlayerRequestState state,
            PlayerRequestState.QueuedPayloadBatch priorityBatch,
            int prioritySent,
            long effectiveLimit) {
        if (priorityBatch == null) {
            return false;
        }
        if (!priorityBatch.hasSentPayloads() && prioritySent >= priorityColumnsPerTick) {
            return false;
        }
        return state.canSend(true, effectiveLimit);
    }

    private PlayerRequestState.QueuedPayloadBatch nextBatch(
            PlayerRequestState.QueuedPayloadBatch priorityBatch,
            PlayerRequestState.QueuedPayloadBatch normalBatch,
            int playerCx,
            int playerCz,
            boolean priorityFirst,
            int prioritySent,
            boolean canSendPriority,
            boolean canSendNormal) {
        return chooseBatch(
                canSendPriority ? priorityBatch : null,
                canSendNormal ? normalBatch : null,
                playerCx,
                playerCz,
                priorityFirst,
                prioritySent,
                priorityColumnsPerTick);
    }

    static PlayerRequestState.QueuedPayloadBatch chooseBatch(
            PlayerRequestState.QueuedPayloadBatch priorityBatch,
            PlayerRequestState.QueuedPayloadBatch normalBatch,
            int playerCx,
            int playerCz,
            boolean priorityFirst,
            int prioritySent,
            int priorityColumnsPerTick) {
        if (priorityBatch == null) {
            return normalBatch;
        }
        if (normalBatch == null) {
            return priorityBatch;
        }

        boolean priorityInProgress = priorityBatch.hasSentPayloads();
        boolean normalInProgress = normalBatch.hasSentPayloads();
        if (priorityInProgress && normalInProgress) {
            return priorityBatch.sequence() <= normalBatch.sequence() ? priorityBatch : normalBatch;
        }
        if (priorityInProgress) {
            return priorityBatch;
        }
        if (normalInProgress) {
            return normalBatch;
        }

        int priorityRing = ring(priorityBatch, playerCx, playerCz);
        int normalRing = ring(normalBatch, playerCx, playerCz);
        if (priorityRing < normalRing) {
            return priorityBatch;
        }
        if (normalRing < priorityRing) {
            return normalBatch;
        }
        if (prioritySent >= priorityColumnsPerTick) {
            return normalBatch;
        }
        return priorityFirst ? priorityBatch : normalBatch;
    }

    private static int ring(PlayerRequestState.QueuedPayloadBatch batch, int playerCx, int playerCz) {
        VoxelColumnS2CPayload payload = batch.firstPayload().payload();
        return Math.max(Math.abs(payload.chunkX() - playerCx), Math.abs(payload.chunkZ() - playerCz));
    }

    private boolean sendQueuedPayloadBatch(
            ServerPlayer player,
            PlayerRequestState state,
            PlayerRequestState.QueuedPayloadBatch batch,
            long effectiveLimit) {
        VoxelColumnS2CPayload firstPayload = batch.firstPayload().payload();
        int requestId = batch.requestId();
        if (state.consumeCancelled(requestId)) {
            discardQueuedBatch(state, batch);
            return false;
        }
        if (!state.isActiveRequest(requestId)) {
            discardQueuedBatch(state, batch);
            return false;
        }
        if (!isPayloadStillRelevant(player, firstPayload)) {
            sendBackpressured(player, requestId);
            state.clearRequest(requestId);
            discardQueuedBatch(state, batch);
            return false;
        }
        if (QueuedPayloadExpiryPolicy.isExpired(
                batch.queuedNanos(),
                System.nanoTime(),
                batch.wireBytes(),
                batch.queuedBytesAheadAtEnqueue(),
                effectiveLimit)) {
            sendBackpressured(player, requestId);
            state.clearRequest(requestId);
            discardQueuedBatch(state, batch);
            return false;
        }
        PlayerRequestState.QueuedPayload queuedPayload = state.consumeQueuedPayload(batch);
        if (queuedPayload == null) {
            return false;
        }
        VoxelColumnS2CPayload payload = queuedPayload.payload();
        VSSNetworking.sendToPlayer(player, payload);
        if (payload.completesRequest()) {
            state.clearRequest(requestId);
        }
        state.recordSend(batch.priority(), queuedPayload.wireBytes());
        logColumnSend(player, batch, queuedPayload, state);
        return true;
    }

    private static void discardQueuedBatch(PlayerRequestState state, PlayerRequestState.QueuedPayloadBatch batch) {
        if (batch.priority()) {
            state.pollPriorityQueuedBatch(batch);
        } else {
            state.pollNormalQueuedBatch(batch);
        }
    }

    private static void sendBackpressured(ServerPlayer player, int requestId) {
        if (requestId < 0) {
            return;
        }
        VSSNetworking.sendToPlayer(
                player,
                new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_BACKPRESSURE},
                        new int[] {requestId},
                        1));
    }

    private static boolean isPayloadStillRelevant(ServerPlayer player, VoxelColumnS2CPayload payload) {
        return VSSServerNetworking.isColumnStillRelevant(player, payload.dimension(), payload.chunkX(), payload.chunkZ());
    }

    private void logColumnSend(
            ServerPlayer player,
            PlayerRequestState.QueuedPayloadBatch batch,
            PlayerRequestState.QueuedPayload queuedPayload,
            PlayerRequestState state) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSendDiagnosticNanos < diagnosticIntervalNanos) {
            return;
        }
        lastSendDiagnosticNanos = now;
        VoxelColumnS2CPayload payload = queuedPayload.payload();
        VSSLogger.debug("LOD column sent to " + player.getGameProfile().getName()
                + ": chunk=" + payload.chunkX() + "," + payload.chunkZ()
                + ", remainingParts=" + batch.payloadCount()
                + ", partWireBytes=" + queuedPayload.wireBytes()
                + ", remainingWireBytes=" + batch.wireBytes()
                + ", partRawBytes=" + queuedPayload.rawBytes()
                + ", queued=" + state.queuedPayloadCount()
                + ", queuedWireBytes=" + state.queuedBytes()
                + ", normalSendCreditBytes=" + state.normalSendCreditBytes()
                + ", priorityBypassWireBytes=" + state.priorityBytesSent()
                + ", totalSentWireBytes=" + state.totalBytesSent());
    }
}
