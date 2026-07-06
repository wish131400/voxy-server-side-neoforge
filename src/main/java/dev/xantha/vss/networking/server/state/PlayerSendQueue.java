package dev.xantha.vss.networking.server.state;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.function.IntConsumer;

public final class PlayerSendQueue {
    private final BucketedPayloadQueue priorityQueue = new BucketedPayloadQueue();
    private final BucketedPayloadQueue normalQueue = new BucketedPayloadQueue();
    private int priorityPayloads;
    private long queuedBytes;
    private int orderedForPlayerCx = Integer.MIN_VALUE;
    private int orderedForPlayerCz = Integer.MIN_VALUE;
    private long nextSequence;

    public synchronized boolean enqueue(
            VoxelColumnS2CPayload payload,
            boolean priority,
            int queueLimit,
            int queueBytesLimit,
            boolean networkCompressionEnabled,
            IntConsumer clearRequest) {
        int estimatedBytes = payload.estimatedWireBytes(networkCompressionEnabled);
        int currentQueueSize = queuedPayloadCount();
        if (currentQueueSize >= queueLimit || queuedBytes + estimatedBytes > queueBytesLimit) {
            clearRequest.accept(payload.requestId());
            if (currentQueueSize > queueLimit / 2) {
                trimNormalQueue(clearRequest);
            }
            return false;
        }

        PlayerRequestState.QueuedPayload queuedPayload = new PlayerRequestState.QueuedPayload(
                payload,
                estimatedBytes,
                priority,
                nextSequence++);
        if (priority) {
            priorityQueue.add(queuedPayload, hasOrderingCenter(), orderedForPlayerCx, orderedForPlayerCz);
            priorityPayloads++;
        } else {
            normalQueue.add(queuedPayload, hasOrderingCenter(), orderedForPlayerCx, orderedForPlayerCz);
        }
        queuedBytes += estimatedBytes;
        return true;
    }

    public synchronized void prepareOrder(int playerCx, int playerCz) {
        if (playerCx == orderedForPlayerCx
                && playerCz == orderedForPlayerCz
                && priorityQueue.isOrderedFor(playerCx, playerCz)
                && normalQueue.isOrderedFor(playerCx, playerCz)) {
            return;
        }

        priorityQueue.prepare(playerCx, playerCz);
        normalQueue.prepare(playerCx, playerCz);
        orderedForPlayerCx = playerCx;
        orderedForPlayerCz = playerCz;
    }

    public synchronized PlayerRequestState.QueuedPayload peekPriority(int playerCx, int playerCz) {
        prepareOrder(playerCx, playerCz);
        return priorityQueue.peek();
    }

    public synchronized PlayerRequestState.QueuedPayload peek(int playerCx, int playerCz) {
        prepareOrder(playerCx, playerCz);
        PlayerRequestState.QueuedPayload priorityPayload = priorityQueue.peek();
        return priorityPayload != null ? priorityPayload : normalQueue.peek();
    }

    public synchronized PlayerRequestState.QueuedPayload peekNormal(int playerCx, int playerCz) {
        prepareOrder(playerCx, playerCz);
        return normalQueue.peek();
    }

    public synchronized PlayerRequestState.QueuedPayload pollPriority(PlayerRequestState.QueuedPayload payload) {
        if (payload == null) {
            return null;
        }
        PlayerRequestState.QueuedPayload removed = priorityQueue.poll(payload);
        if (removed == null) {
            return null;
        }
        priorityPayloads = Math.max(0, priorityPayloads - 1);
        queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        return payload;
    }

    public synchronized PlayerRequestState.QueuedPayload poll(PlayerRequestState.QueuedPayload payload) {
        if (payload == null) {
            return null;
        }
        if (payload.priority()) {
            PlayerRequestState.QueuedPayload removed = priorityQueue.poll(payload);
            if (removed == null) {
                return null;
            }
            priorityPayloads = Math.max(0, priorityPayloads - 1);
        } else {
            PlayerRequestState.QueuedPayload removed = normalQueue.poll(payload);
            if (removed == null) {
                return null;
            }
        }
        queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        return payload;
    }

    public synchronized PlayerRequestState.QueuedPayload pollNormal(PlayerRequestState.QueuedPayload payload) {
        if (payload == null || payload.priority()) {
            return null;
        }
        PlayerRequestState.QueuedPayload removed = normalQueue.poll(payload);
        if (removed == null) {
            return null;
        }
        queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        return payload;
    }

    public synchronized int queuedPayloadCount() {
        return priorityQueue.size() + normalQueue.size();
    }

    public synchronized int priorityQueuedPayloadCount() {
        return priorityPayloads;
    }

    public synchronized int normalQueuedPayloadCount() {
        return normalQueue.size();
    }

    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    public synchronized void clear() {
        priorityQueue.clear();
        normalQueue.clear();
        priorityPayloads = 0;
        queuedBytes = 0L;
        orderedForPlayerCx = Integer.MIN_VALUE;
        orderedForPlayerCz = Integer.MIN_VALUE;
    }

    private void trimNormalQueue(IntConsumer clearRequest) {
        int toRemove = Math.max(1, normalQueue.size() / 10);
        while (toRemove-- > 0 && !normalQueue.isEmpty()) {
            PlayerRequestState.QueuedPayload removed = normalQueue.pollFarthestOrOldest();
            if (removed != null) {
                queuedBytes = Math.max(0L, queuedBytes - removed.estimatedBytes());
                clearRequest.accept(removed.payload().requestId());
            }
        }
    }

    private boolean hasOrderingCenter() {
        return orderedForPlayerCx != Integer.MIN_VALUE && orderedForPlayerCz != Integer.MIN_VALUE;
    }

    private static int ring(VoxelColumnS2CPayload payload, int playerCx, int playerCz) {
        return Math.max(Math.abs(payload.chunkX() - playerCx), Math.abs(payload.chunkZ() - playerCz));
    }

    private static final class BucketedPayloadQueue {
        private static final Comparator<PlayerRequestState.QueuedPayload> SEQUENCE_ORDER =
                Comparator.comparingLong(PlayerRequestState.QueuedPayload::sequence);

        private final TreeMap<Integer, PriorityQueue<PlayerRequestState.QueuedPayload>> buckets = new TreeMap<>();
        private final ArrayDeque<PlayerRequestState.QueuedPayload> unordered = new ArrayDeque<>();
        private int orderedForPlayerCx = Integer.MIN_VALUE;
        private int orderedForPlayerCz = Integer.MIN_VALUE;
        private int size;

        private void add(PlayerRequestState.QueuedPayload payload, boolean hasOrder, int playerCx, int playerCz) {
            size++;
            if (!hasOrder) {
                unordered.addLast(payload);
                return;
            }
            if (!isOrderedFor(playerCx, playerCz)) {
                prepare(playerCx, playerCz);
            }
            addToBucket(payload, playerCx, playerCz);
        }

        private void prepare(int playerCx, int playerCz) {
            if (isOrderedFor(playerCx, playerCz)) {
                return;
            }

            ArrayDeque<PlayerRequestState.QueuedPayload> entries = new ArrayDeque<>(size);
            drainBuckets(entries);
            while (!unordered.isEmpty()) {
                entries.addLast(unordered.removeFirst());
            }

            buckets.clear();
            orderedForPlayerCx = playerCx;
            orderedForPlayerCz = playerCz;
            for (PlayerRequestState.QueuedPayload payload : entries) {
                addToBucket(payload, playerCx, playerCz);
            }
        }

        private boolean isOrderedFor(int playerCx, int playerCz) {
            return unordered.isEmpty()
                    && orderedForPlayerCx == playerCx
                    && orderedForPlayerCz == playerCz;
        }

        private PlayerRequestState.QueuedPayload peek() {
            if (!unordered.isEmpty()) {
                return unordered.peekFirst();
            }
            Map.Entry<Integer, PriorityQueue<PlayerRequestState.QueuedPayload>> entry = buckets.firstEntry();
            return entry == null ? null : entry.getValue().peek();
        }

        private PlayerRequestState.QueuedPayload poll(PlayerRequestState.QueuedPayload payload) {
            if (payload == null) {
                return null;
            }
            if (!unordered.isEmpty()) {
                PlayerRequestState.QueuedPayload removed = unordered.peekFirst() == payload ? unordered.pollFirst() : null;
                if (removed == null && unordered.remove(payload)) {
                    removed = payload;
                }
                if (removed != null) {
                    size = Math.max(0, size - 1);
                }
                return removed;
            }

            int ring = ring(payload.payload(), orderedForPlayerCx, orderedForPlayerCz);
            PriorityQueue<PlayerRequestState.QueuedPayload> bucket = buckets.get(ring);
            PlayerRequestState.QueuedPayload removed = removeFromBucket(bucket, payload);
            if (bucket != null && bucket.isEmpty()) {
                buckets.remove(ring);
            }
            if (removed == null) {
                removed = removeFromAnyBucket(payload);
            }
            if (removed != null) {
                size = Math.max(0, size - 1);
            }
            return removed;
        }

        private PlayerRequestState.QueuedPayload pollFarthestOrOldest() {
            PlayerRequestState.QueuedPayload removed;
            if (!unordered.isEmpty()) {
                removed = unordered.pollFirst();
            } else {
                Map.Entry<Integer, PriorityQueue<PlayerRequestState.QueuedPayload>> entry = buckets.lastEntry();
                if (entry == null) {
                    return null;
                }
                removed = entry.getValue().poll();
                if (entry.getValue().isEmpty()) {
                    buckets.pollLastEntry();
                }
            }
            if (removed != null) {
                size = Math.max(0, size - 1);
            }
            return removed;
        }

        private int size() {
            return size;
        }

        private boolean isEmpty() {
            return size <= 0;
        }

        private void clear() {
            buckets.clear();
            unordered.clear();
            orderedForPlayerCx = Integer.MIN_VALUE;
            orderedForPlayerCz = Integer.MIN_VALUE;
            size = 0;
        }

        private void addToBucket(PlayerRequestState.QueuedPayload payload, int playerCx, int playerCz) {
            buckets.computeIfAbsent(ring(payload.payload(), playerCx, playerCz), ignored -> new PriorityQueue<>(SEQUENCE_ORDER))
                    .add(payload);
        }

        private void drainBuckets(ArrayDeque<PlayerRequestState.QueuedPayload> entries) {
            for (PriorityQueue<PlayerRequestState.QueuedPayload> bucket : buckets.values()) {
                while (!bucket.isEmpty()) {
                    entries.addLast(bucket.poll());
                }
            }
        }

        private PlayerRequestState.QueuedPayload removeFromAnyBucket(PlayerRequestState.QueuedPayload payload) {
            var iterator = buckets.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, PriorityQueue<PlayerRequestState.QueuedPayload>> entry = iterator.next();
                PlayerRequestState.QueuedPayload removed = removeFromBucket(entry.getValue(), payload);
                if (entry.getValue().isEmpty()) {
                    iterator.remove();
                }
                if (removed != null) {
                    return removed;
                }
            }
            return null;
        }

        private PlayerRequestState.QueuedPayload removeFromBucket(
                PriorityQueue<PlayerRequestState.QueuedPayload> bucket,
                PlayerRequestState.QueuedPayload payload) {
            if (bucket == null) {
                return null;
            }
            PlayerRequestState.QueuedPayload removed = bucket.peek() == payload ? bucket.poll() : null;
            if (removed == null && bucket.remove(payload)) {
                removed = payload;
            }
            return removed;
        }
    }
}
