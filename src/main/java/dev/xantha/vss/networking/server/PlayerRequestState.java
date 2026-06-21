package dev.xantha.vss.networking.server;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

final class PlayerRequestState {
    private final Set<Integer> cancelled = new HashSet<>();
    private final Map<Integer, Long> requestPositions = new HashMap<>();
    private final Set<Long> activePositions = new HashSet<>();
    private final Queue<QueuedPayload> sendQueue = new ArrayDeque<>();
    private long queuedBytes;
    private long desiredBandwidth = Long.MAX_VALUE;
    private long availableBytes;
    private long lastRefillNanos = System.nanoTime();
    private long totalBytesSent;

    void cancel(int requestId) {
        cancelled.add(requestId);
        Long position = requestPositions.remove(requestId);
        if (position != null) {
            activePositions.remove(position);
        }
    }

    boolean consumeCancelled(int requestId) {
        boolean wasCancelled = cancelled.remove(requestId);
        if (wasCancelled) {
            clearRequest(requestId);
        }
        return wasCancelled;
    }

    boolean beginRequest(int requestId, long position) {
        if (!activePositions.add(position)) {
            return false;
        }
        requestPositions.put(requestId, position);
        return true;
    }

    void clearRequest(int requestId) {
        Long position = requestPositions.remove(requestId);
        if (position != null) {
            activePositions.remove(position);
        }
    }

    boolean enqueue(VoxelColumnS2CPayload payload) {
        int estimatedBytes = payload.estimatedBytes();
        VSSServerConfig config = VSSServerConfig.CONFIG;
        if (sendQueue.size() >= config.sendQueueLimitPerPlayer
                || queuedBytes + estimatedBytes > config.sendQueueBytesLimitPerPlayer) {
            clearRequest(payload.requestId());
            return false;
        }
        sendQueue.add(new QueuedPayload(payload, estimatedBytes));
        queuedBytes += estimatedBytes;
        return true;
    }

    QueuedPayload peekQueuedPayload() {
        return sendQueue.peek();
    }

    QueuedPayload pollQueuedPayload() {
        QueuedPayload payload = sendQueue.poll();
        if (payload != null) {
            queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        }
        return payload;
    }

    int queuedPayloadCount() {
        return sendQueue.size();
    }

    long queuedBytes() {
        return queuedBytes;
    }

    boolean canSend(long configuredLimit) {
        refill(configuredLimit);
        return availableBytes > 0L;
    }

    void recordSend(int bytes) {
        availableBytes = Math.max(0L, availableBytes - bytes);
        totalBytesSent += bytes;
    }

    long totalBytesSent() {
        return totalBytesSent;
    }

    void setDesiredBandwidth(long desiredBandwidth) {
        this.desiredBandwidth = desiredBandwidth > 0L ? desiredBandwidth : Long.MAX_VALUE;
    }

    long desiredBandwidth() {
        return desiredBandwidth;
    }

    private void refill(long configuredLimit) {
        long limit = effectiveLimit(configuredLimit);
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos < 1_000_000L) {
            return;
        }

        lastRefillNanos = now;
        elapsedNanos = Math.min(elapsedNanos, 1_000_000_000L);
        long refill = elapsedNanos * limit / 1_000_000_000L;
        long burstCap = Math.max(1L, limit / 4L);
        availableBytes = Math.min(availableBytes + refill, burstCap);
    }

    private long effectiveLimit(long configuredLimit) {
        long safeConfiguredLimit = Math.max(1L, configuredLimit);
        return Math.min(safeConfiguredLimit, desiredBandwidth);
    }

    record QueuedPayload(VoxelColumnS2CPayload payload, int estimatedBytes) {
    }
}
