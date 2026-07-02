package dev.xantha.vss.networking.server;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class PlayerRequestState {
    private final Set<Integer> cancelled = new HashSet<>();
    private final Map<Integer, RequestPosition> requestPositions = new HashMap<>();
    private final Set<RequestPosition> activePositions = new HashSet<>();
    private final Queue<QueuedPayload> prioritySendQueue = new ArrayDeque<>();
    private final Queue<QueuedPayload> sendQueue = new ArrayDeque<>();
    private int priorityQueuedPayloads;
    private long queuedBytes;
    private long desiredBandwidth = Long.MAX_VALUE;
    private long availableBytes;
    private long lastRefillNanos = System.nanoTime();
    private long totalBytesSent;
    private int clientCapabilities;
    private int orderedForPlayerCx = Integer.MIN_VALUE;
    private int orderedForPlayerCz = Integer.MIN_VALUE;
    private long nextQueueSequence;
    private boolean queueOrderDirty = true;

    void cancel(int requestId) {
        cancelled.add(requestId);
        RequestPosition position = requestPositions.remove(requestId);
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

    boolean beginRequest(int requestId, ResourceKey<Level> dimension, long position) {
        RequestPosition requestPosition = new RequestPosition(dimension, position);
        if (!activePositions.add(requestPosition)) {
            return false;
        }
        requestPositions.put(requestId, requestPosition);
        return true;
    }

    void clearRequest(int requestId) {
        RequestPosition position = requestPositions.remove(requestId);
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
            prioritySendQueue.add(new QueuedPayload(payload, estimatedBytes, true, nextQueueSequence++));
            priorityQueuedPayloads++;
        } else {
            sendQueue.add(new QueuedPayload(payload, estimatedBytes, false, nextQueueSequence++));
        }
        queuedBytes += estimatedBytes;
        queueOrderDirty = true;
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
        queueOrderDirty = true;
    }

    void prepareSendOrder(int playerCx, int playerCz) {
        if (!queueOrderDirty && playerCx == orderedForPlayerCx && playerCz == orderedForPlayerCz) {
            return;
        }

        reorderQueue(prioritySendQueue, playerCx, playerCz);
        reorderQueue(sendQueue, playerCx, playerCz);
        orderedForPlayerCx = playerCx;
        orderedForPlayerCz = playerCz;
        queueOrderDirty = false;
    }

    QueuedPayload peekPriorityQueuedPayload(int playerCx, int playerCz) {
        prepareSendOrder(playerCx, playerCz);
        return prioritySendQueue.peek();
    }

    QueuedPayload peekQueuedPayload(int playerCx, int playerCz) {
        prepareSendOrder(playerCx, playerCz);
        QueuedPayload priorityPayload = prioritySendQueue.peek();
        return priorityPayload != null ? priorityPayload : sendQueue.peek();
    }

    QueuedPayload pollPriorityQueuedPayload(QueuedPayload payload) {
        if (payload == null) {
            return null;
        }
        QueuedPayload removed = prioritySendQueue.peek() == payload ? prioritySendQueue.poll() : null;
        if (removed == null && !prioritySendQueue.remove(payload)) {
            return null;
        }
        priorityQueuedPayloads = Math.max(0, priorityQueuedPayloads - 1);
        queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        return payload;
    }

    QueuedPayload pollQueuedPayload(QueuedPayload payload) {
        if (payload == null) {
            return null;
        }
        if (payload.priority()) {
            QueuedPayload removed = prioritySendQueue.peek() == payload ? prioritySendQueue.poll() : null;
            if (removed == null && !prioritySendQueue.remove(payload)) {
                return null;
            }
            priorityQueuedPayloads = Math.max(0, priorityQueuedPayloads - 1);
        } else {
            QueuedPayload removed = sendQueue.peek() == payload ? sendQueue.poll() : null;
            if (removed == null && !sendQueue.remove(payload)) {
                return null;
            }
        }
        queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        return payload;
    }

    private static void reorderQueue(Queue<QueuedPayload> queue, int playerCx, int playerCz) {
        if (queue.size() <= 1) {
            return;
        }

        ArrayList<QueuedPayload> ordered = new ArrayList<>(queue);
        ordered.sort(sendOrderComparator(playerCx, playerCz));
        queue.clear();
        queue.addAll(ordered);
    }

    private static Comparator<QueuedPayload> sendOrderComparator(int playerCx, int playerCz) {
        return Comparator
                .comparingInt((QueuedPayload candidate) -> chebyshevDistance(candidate.payload(), playerCx, playerCz))
                .thenComparingLong(QueuedPayload::sequence);
    }

    private static int chebyshevDistance(VoxelColumnS2CPayload payload, int playerCx, int playerCz) {
        return Math.max(Math.abs(payload.chunkX() - playerCx), Math.abs(payload.chunkZ() - playerCz));
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
        queueOrderDirty = true;
        orderedForPlayerCx = Integer.MIN_VALUE;
        orderedForPlayerCz = Integer.MIN_VALUE;
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

    private record RequestPosition(ResourceKey<Level> dimension, long packedPosition) {
    }

    record QueuedPayload(VoxelColumnS2CPayload payload, int estimatedBytes, boolean priority, long sequence) {
    }
}
