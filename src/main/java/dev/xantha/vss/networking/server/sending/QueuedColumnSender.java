package dev.xantha.vss.networking.server.sending;


import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.BandwidthLimiter;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class QueuedColumnSender {
    private final PlayerRequestRegistry playerRegistry;
    private final int priorityColumnsPerTick;
    private final long diagnosticIntervalNanos;
    private final BandwidthLimiter totalBandwidthLimiter = new BandwidthLimiter(System::nanoTime);
    private final RoundRobinPlayerCursor roundRobinCursor = new RoundRobinPlayerCursor();
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

    public void applyRuntimeConfig() {
        totalBandwidthLimiter.reset();
        totalBandwidthLimiter.primeSendCredit(VSSServerConfig.CONFIG.totalBandwidthBytesPerSecond());
    }

    public void reset() {
        totalBandwidthLimiter.reset();
        roundRobinCursor.reset();
        priorityFirstByPlayer.clear();
    }

    public void flush(MinecraftServer server) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        long configuredLimit = VSSServerConfig.CONFIG.totalBandwidthBytesPerSecond();
        priorityFirstByPlayer.keySet().retainAll(playerRegistry.playerIds());
        List<PlayerTarget> targets = new ArrayList<>();
        List<Map.Entry<UUID, PlayerRequestState>> entries = new ArrayList<>(playerRegistry.entries());
        entries.sort((left, right) -> left.getKey().compareTo(right.getKey()));
        for (Map.Entry<UUID, PlayerRequestState> entry : entries) {
            PlayerRequestState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                priorityFirstByPlayer.remove(entry.getKey());
                continue;
            }

            int playerCx = player.getBlockX() >> 4;
            int playerCz = player.getBlockZ() >> 4;
            state.prepareSendOrder(playerCx, playerCz);
            if (state.queuedPayloadCount() == 0) {
                continue;
            }
            targets.add(new PlayerTarget(entry.getKey(), player, state, playerCx, playerCz));
        }
        if (targets.isEmpty()) {
            return;
        }

        List<UUID> playerOrder = new ArrayList<>(targets.size());
        for (PlayerTarget target : targets) {
            playerOrder.add(target.id);
        }
        int index = roundRobinCursor.startIndex(playerOrder);
        int noSendAttempts = 0;
        Map<UUID, Integer> prioritySentByPlayer = new HashMap<>();
        while (noSendAttempts < targets.size() && totalBandwidthLimiter.canSend(configuredLimit)) {
            PlayerTarget target = targets.get(index);
            int prioritySent = prioritySentByPlayer.getOrDefault(target.id, 0);
            long expiryLimit = effectiveExpiryBandwidth(configuredLimit, target.state, targets.size());
            SendResult result = sendOneQueuedPayload(
                    target.player,
                    target.state,
                    target.playerCx,
                    target.playerCz,
                    prioritySent,
                    priorityFirstByPlayer.getOrDefault(target.id, true),
                    expiryLimit);
            if (result.sent) {
                totalBandwidthLimiter.recordSend(result.wireBytes);
                if (result.priority) {
                    prioritySentByPlayer.put(target.id, prioritySent + 1);
                }
                if (result.hadBothQueues) {
                    priorityFirstByPlayer.put(target.id, !result.priority);
                }
                noSendAttempts = 0;
            } else {
                noSendAttempts++;
            }
            roundRobinCursor.advance(playerOrder, index);
            index = (index + 1) % targets.size();
        }
    }

    private long effectiveExpiryBandwidth(long totalBandwidth, PlayerRequestState state, int activePlayers) {
        long desired = state.desiredBandwidth();
        long fairShare = Math.max(1L, totalBandwidth / Math.max(1, activePlayers));
        return Math.min(desired, fairShare);
    }

    private SendResult sendOneQueuedPayload(
            ServerPlayer player,
            PlayerRequestState state,
            int playerCx,
            int playerCz,
            int prioritySent,
            boolean priorityFirst,
            long expiryLimit) {
        boolean hasPriority = state.priorityQueuedPayloadCount() > 0;
        boolean hasNormal = state.normalQueuedPayloadCount() > 0;
        PlayerRequestState.QueuedPayloadBatch priorityBatch = hasPriority
                    ? state.peekPriorityQueuedBatch(playerCx, playerCz)
                    : null;
        PlayerRequestState.QueuedPayloadBatch normalBatch = hasNormal
                    ? state.peekNormalQueuedBatch(playerCx, playerCz)
                    : null;
        boolean canSendPriority = canSendPriority(state, priorityBatch, prioritySent);
        boolean canSendNormal = normalBatch != null && state.canSend(Long.MAX_VALUE);
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
            return SendResult.NO_SEND;
        }
        int wireBytes = sendQueuedPayloadBatch(player, state, batch, expiryLimit);
        return wireBytes > 0 ? new SendResult(wireBytes, batch.priority(), hasPriority && hasNormal) : SendResult.NO_SEND;
    }

    private boolean canSendPriority(
            PlayerRequestState state,
            PlayerRequestState.QueuedPayloadBatch priorityBatch,
            int prioritySent) {
        if (priorityBatch == null) {
            return false;
        }
        if (!priorityBatch.hasSentPayloads() && prioritySent >= priorityColumnsPerTick) {
            return false;
        }
        return state.canSend(Long.MAX_VALUE);
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

    private int sendQueuedPayloadBatch(
            ServerPlayer player,
            PlayerRequestState state,
            PlayerRequestState.QueuedPayloadBatch batch,
            long effectiveLimit) {
        VoxelColumnS2CPayload firstPayload = batch.firstPayload().payload();
        int requestId = batch.requestId();
        if (state.consumeCancelled(requestId)) {
            discardQueuedBatch(state, batch);
            return 0;
        }
        if (!state.isActiveRequest(requestId)) {
            discardQueuedBatch(state, batch);
            return 0;
        }
        if (!isPayloadStillRelevant(player, firstPayload)) {
            sendBackpressured(player, requestId);
            state.clearRequest(requestId);
            discardQueuedBatch(state, batch);
            return 0;
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
            return 0;
        }
        PlayerRequestState.QueuedPayload queuedPayload = state.consumeQueuedPayload(batch);
        if (queuedPayload == null) {
            return 0;
        }
        VoxelColumnS2CPayload payload = queuedPayload.payload();
        VSSNetworking.sendToPlayer(player, payload);
        if (payload.completesRequest()) {
            state.clearRequest(requestId);
        }
        state.recordSend(batch.priority(), queuedPayload.wireBytes());
        logColumnSend(player, batch, queuedPayload, state);
        return queuedPayload.wireBytes();
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
                + ", personalSendCreditBytes=" + state.personalSendCreditBytes()
                + ", priorityWireBytes=" + state.priorityBytesSent()
                + ", totalSentWireBytes=" + state.totalBytesSent());
    }

    private static final class PlayerTarget {
        private final UUID id;
        private final ServerPlayer player;
        private final PlayerRequestState state;
        private final int playerCx;
        private final int playerCz;

        private PlayerTarget(
                UUID id,
                ServerPlayer player,
                PlayerRequestState state,
                int playerCx,
                int playerCz) {
            this.id = id;
            this.player = player;
            this.state = state;
            this.playerCx = playerCx;
            this.playerCz = playerCz;
        }
    }

    private static final class SendResult {
        private static final SendResult NO_SEND = new SendResult(0, false, false);

        private final int wireBytes;
        private final boolean priority;
        private final boolean hadBothQueues;
        private final boolean sent;

        private SendResult(int wireBytes, boolean priority, boolean hadBothQueues) {
            this.wireBytes = wireBytes;
            this.priority = priority;
            this.hadBothQueues = hadBothQueues;
            this.sent = wireBytes > 0;
        }
    }
}
