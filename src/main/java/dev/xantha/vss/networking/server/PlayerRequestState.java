package dev.xantha.vss.networking.server;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

final class PlayerRequestState {
    private final Set<Integer> cancelled = new HashSet<>();
    private final Map<Integer, Long> requestPositions = new HashMap<>();
    private final Set<Long> activePositions = new HashSet<>();
    private final Queue<QueuedPayload> prioritySendQueue = new ArrayDeque<>();
    private final Queue<QueuedPayload> sendQueue = new ArrayDeque<>();
    private int priorityQueuedPayloads;
    private long queuedBytes;
    private long desiredBandwidth = Long.MAX_VALUE;
    private long availableBytes;
    private long lastRefillNanos = System.nanoTime();
    private long totalBytesSent;
    private int clientCapabilities;

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

    boolean enqueue(VoxelColumnS2CPayload payload, boolean priority) {
        int estimatedBytes = payload.estimatedBytes();
        VSSServerConfig config = VSSServerConfig.CONFIG;
        int currentQueueSize = queuedPayloadCount();
        if (currentQueueSize >= config.sendQueueLimitPerPlayer
                || queuedBytes + estimatedBytes > config.sendQueueBytesLimitPerPlayer) {
            clearRequest(payload.requestId());
            if (currentQueueSize > config.sendQueueLimitPerPlayer / 2) {
                trimQueue();
            }
            return false;
        }

        if (priority) {
            prioritySendQueue.add(new QueuedPayload(payload, estimatedBytes, true));
            priorityQueuedPayloads++;
        } else {
            sendQueue.add(new QueuedPayload(payload, estimatedBytes, false));
        }
        queuedBytes += estimatedBytes;
        return true;
    }

    private void trimQueue() {
        int toRemove = Math.max(1, sendQueue.size() / 10);
        while (toRemove-- > 0 && !sendQueue.isEmpty()) {
            QueuedPayload removed = sendQueue.poll();
            if (removed != null) {
                queuedBytes = Math.max(0L, queuedBytes - removed.estimatedBytes());
                clearRequest(removed.payload().requestId());
            }
        }
    }

    QueuedPayload peekPriorityQueuedPayload(int playerCx, int playerCz) {
        return nearestQueuedPayload(prioritySendQueue, playerCx, playerCz, false);
    }

    QueuedPayload peekQueuedPayload(int playerCx, int playerCz) {
        QueuedPayload priorityPayload = nearestQueuedPayload(prioritySendQueue, playerCx, playerCz, false);
        return priorityPayload != null ? priorityPayload : nearestQueuedPayload(sendQueue, playerCx, playerCz, false);
    }

    QueuedPayload pollPriorityQueuedPayload(int playerCx, int playerCz) {
        QueuedPayload payload = nearestQueuedPayload(prioritySendQueue, playerCx, playerCz, true);
        if (payload != null) {
            priorityQueuedPayloads = Math.max(0, priorityQueuedPayloads - 1);
            queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        }
        return payload;
    }

    QueuedPayload pollQueuedPayload(int playerCx, int playerCz) {
        QueuedPayload payload = nearestQueuedPayload(prioritySendQueue, playerCx, playerCz, true);
        if (payload != null) {
            priorityQueuedPayloads = Math.max(0, priorityQueuedPayloads - 1);
        } else {
            payload = nearestQueuedPayload(sendQueue, playerCx, playerCz, true);
        }
        if (payload != null) {
            queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        }
        return payload;
    }

    private QueuedPayload nearestQueuedPayload(
            Collection<QueuedPayload> queue,
            int playerCx,
            int playerCz,
            boolean remove) {
        QueuedPayload best = null;
        int bestChebyshev = Integer.MAX_VALUE;
        long bestDistanceSquared = Long.MAX_VALUE;
        for (QueuedPayload candidate : queue) {
            VoxelColumnS2CPayload payload = candidate.payload();
            int dx = payload.chunkX() - playerCx;
            int dz = payload.chunkZ() - playerCz;
            int chebyshev = Math.max(Math.abs(dx), Math.abs(dz));
            long distanceSquared = (long) dx * dx + (long) dz * dz;
            if (best == null
                    || chebyshev < bestChebyshev
                    || (chebyshev == bestChebyshev && distanceSquared < bestDistanceSquared)) {
                best = candidate;
                bestChebyshev = chebyshev;
                bestDistanceSquared = distanceSquared;
            }
        }
        if (remove && best != null) {
            queue.remove(best);
        }
        return best;
    }

    int queuedPayloadCount() {
        return prioritySendQueue.size() + sendQueue.size();
    }

    int priorityQueuedPayloadCount() {
        return priorityQueuedPayloads;
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

    void setClientCapabilities(int clientCapabilities) {
        this.clientCapabilities = clientCapabilities;
    }

    boolean supportsZstdColumns() {
        return (clientCapabilities & dev.xantha.vss.common.VSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0;
    }

    void clearAll() {
        cancelled.clear();
        requestPositions.clear();
        activePositions.clear();
        prioritySendQueue.clear();
        sendQueue.clear();
        priorityQueuedPayloads = 0;
        queuedBytes = 0L;
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

    record QueuedPayload(VoxelColumnS2CPayload payload, int estimatedBytes, boolean priority) {
    }
}
