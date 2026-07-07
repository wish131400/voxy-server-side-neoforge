package dev.xantha.vss.networking.server.state;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;

public final class PlayerSendQueue {
    private final BucketedPayloadQueue priorityQueue = new BucketedPayloadQueue();
    private final BucketedPayloadQueue normalQueue = new BucketedPayloadQueue();
    private final IdentityHashMap<PlayerRequestState.QueuedPayload, PlayerRequestState.QueuedPayloadBatch> payloadBatches = new IdentityHashMap<>();
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
        return enqueue(List.of(payload), priority, queueLimit, queueBytesLimit, networkCompressionEnabled, clearRequest);
    }

    public synchronized boolean enqueue(
            List<VoxelColumnS2CPayload> payloads,
            boolean priority,
            int queueLimit,
            int queueBytesLimit,
            boolean networkCompressionEnabled,
            IntConsumer clearRequest) {
        if (payloads == null || payloads.isEmpty()) {
            return true;
        }

        int currentQueueSize = queuedPayloadCount();
        ArrayList<PlayerRequestState.QueuedPayload> queuedPayloads = new ArrayList<>(payloads.size());
        long batchWireBytes = 0L;
        long batchRawBytes = 0L;
        long queuedNanos = System.nanoTime();
        long batchSequence = nextSequence;
        long batchQueuedBytesAhead = queuedBytes;
        for (VoxelColumnS2CPayload payload : payloads) {
            int wireBytes = payload.estimatedWireBytes(networkCompressionEnabled);
            int rawBytes = payload.rawEstimatedBytes();
            queuedPayloads.add(new PlayerRequestState.QueuedPayload(
                    payload,
                    wireBytes,
                    rawBytes,
                    priority,
                    nextSequence + queuedPayloads.size(),
                    queuedNanos,
                    queuedBytes + batchWireBytes));
            batchWireBytes += wireBytes;
            batchRawBytes += rawBytes;
        }

        if (currentQueueSize + queuedPayloads.size() > queueLimit || queuedBytes + batchWireBytes > queueBytesLimit) {
            clearRequests(payloads, clearRequest);
            if (currentQueueSize > queueLimit / 2) {
                trimNormalQueue(clearRequest);
            }
            return false;
        }

        PlayerRequestState.QueuedPayloadBatch batch = new PlayerRequestState.QueuedPayloadBatch(
                List.copyOf(queuedPayloads),
                priority,
                batchSequence,
                queuedNanos,
                saturatedInt(batchWireBytes),
                saturatedInt(batchRawBytes),
                batchQueuedBytesAhead);
        addBatch(batch);
        nextSequence += queuedPayloads.size();
        queuedBytes += batchWireBytes;
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

    public synchronized PlayerRequestState.QueuedPayloadBatch peekPriorityBatch(int playerCx, int playerCz) {
        prepareOrder(playerCx, playerCz);
        return priorityQueue.peek();
    }

    public synchronized PlayerRequestState.QueuedPayloadBatch peekBatch(int playerCx, int playerCz) {
        prepareOrder(playerCx, playerCz);
        PlayerRequestState.QueuedPayloadBatch priorityBatch = priorityQueue.peek();
        return priorityBatch != null ? priorityBatch : normalQueue.peek();
    }

    public synchronized PlayerRequestState.QueuedPayloadBatch peekNormalBatch(int playerCx, int playerCz) {
        prepareOrder(playerCx, playerCz);
        return normalQueue.peek();
    }

    public synchronized PlayerRequestState.QueuedPayload peekPriority(int playerCx, int playerCz) {
        PlayerRequestState.QueuedPayloadBatch batch = peekPriorityBatch(playerCx, playerCz);
        return batch == null ? null : batch.firstPayload();
    }

    public synchronized PlayerRequestState.QueuedPayload peek(int playerCx, int playerCz) {
        PlayerRequestState.QueuedPayloadBatch batch = peekBatch(playerCx, playerCz);
        return batch == null ? null : batch.firstPayload();
    }

    public synchronized PlayerRequestState.QueuedPayload peekNormal(int playerCx, int playerCz) {
        PlayerRequestState.QueuedPayloadBatch batch = peekNormalBatch(playerCx, playerCz);
        return batch == null ? null : batch.firstPayload();
    }

    public synchronized PlayerRequestState.QueuedPayloadBatch pollPriorityBatch(PlayerRequestState.QueuedPayloadBatch batch) {
        if (batch == null) {
            return null;
        }
        PlayerRequestState.QueuedPayloadBatch removed = priorityQueue.poll(batch);
        if (removed == null) {
            return null;
        }
        removeBatchAccounting(removed);
        return removed;
    }

    public synchronized PlayerRequestState.QueuedPayloadBatch pollBatch(PlayerRequestState.QueuedPayloadBatch batch) {
        if (batch == null) {
            return null;
        }
        PlayerRequestState.QueuedPayloadBatch removed = batch.priority()
                ? priorityQueue.poll(batch)
                : normalQueue.poll(batch);
        if (removed == null) {
            return null;
        }
        removeBatchAccounting(removed);
        return removed;
    }

    public synchronized PlayerRequestState.QueuedPayloadBatch pollNormalBatch(PlayerRequestState.QueuedPayloadBatch batch) {
        if (batch == null || batch.priority()) {
            return null;
        }
        PlayerRequestState.QueuedPayloadBatch removed = normalQueue.poll(batch);
        if (removed == null) {
            return null;
        }
        removeBatchAccounting(removed);
        return removed;
    }

    public synchronized PlayerRequestState.QueuedPayload pollPriority(PlayerRequestState.QueuedPayload payload) {
        PlayerRequestState.QueuedPayloadBatch batch = payloadBatches.get(payload);
        if (batch == null || !batch.priority()) {
            return null;
        }
        return pollPriorityBatch(batch) == null ? null : payload;
    }

    public synchronized PlayerRequestState.QueuedPayload poll(PlayerRequestState.QueuedPayload payload) {
        PlayerRequestState.QueuedPayloadBatch batch = payloadBatches.get(payload);
        if (batch == null) {
            return null;
        }
        return pollBatch(batch) == null ? null : payload;
    }

    public synchronized PlayerRequestState.QueuedPayload pollNormal(PlayerRequestState.QueuedPayload payload) {
        PlayerRequestState.QueuedPayloadBatch batch = payloadBatches.get(payload);
        if (batch == null || batch.priority()) {
            return null;
        }
        return pollNormalBatch(batch) == null ? null : payload;
    }

    public synchronized PlayerRequestState.QueuedPayload consumeBatchPayload(PlayerRequestState.QueuedPayloadBatch batch) {
        if (batch == null || !containsBatch(batch)) {
            return null;
        }
        PlayerRequestState.QueuedPayload payload = batch.consumeNextPayload();
        if (payload == null) {
            return null;
        }

        payloadBatches.remove(payload);
        queuedBytes = Math.max(0L, queuedBytes - payload.wireBytes());
        queueFor(batch).decrementPayloadCount(1);
        if (batch.priority()) {
            priorityPayloads = Math.max(0, priorityPayloads - 1);
        }
        if (batch.payloadCount() == 0) {
            queueFor(batch).removeBatch(batch);
        }
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
        payloadBatches.clear();
        priorityPayloads = 0;
        queuedBytes = 0L;
        orderedForPlayerCx = Integer.MIN_VALUE;
        orderedForPlayerCz = Integer.MIN_VALUE;
    }

    private void addBatch(PlayerRequestState.QueuedPayloadBatch batch) {
        for (PlayerRequestState.QueuedPayload payload : batch.payloads()) {
            payloadBatches.put(payload, batch);
        }
        if (batch.priority()) {
            priorityQueue.add(batch, hasOrderingCenter(), orderedForPlayerCx, orderedForPlayerCz);
            priorityPayloads += batch.payloadCount();
        } else {
            normalQueue.add(batch, hasOrderingCenter(), orderedForPlayerCx, orderedForPlayerCz);
        }
    }

    private void removeBatchAccounting(PlayerRequestState.QueuedPayloadBatch batch) {
        for (PlayerRequestState.QueuedPayload payload : batch.payloads()) {
            payloadBatches.remove(payload);
        }
        if (batch.priority()) {
            priorityPayloads = Math.max(0, priorityPayloads - batch.payloadCount());
        }
        queuedBytes = Math.max(0L, queuedBytes - batch.wireBytes());
    }

    private boolean containsBatch(PlayerRequestState.QueuedPayloadBatch batch) {
        return batch.priority() ? priorityQueue.contains(batch) : normalQueue.contains(batch);
    }

    private BucketedPayloadQueue queueFor(PlayerRequestState.QueuedPayloadBatch batch) {
        return batch.priority() ? priorityQueue : normalQueue;
    }

    private void trimNormalQueue(IntConsumer clearRequest) {
        int toRemove = Math.max(1, normalQueue.size() / 10);
        while (toRemove > 0 && !normalQueue.isEmpty()) {
            PlayerRequestState.QueuedPayloadBatch removed = normalQueue.pollFarthestOrOldest();
            if (removed != null) {
                removeBatchAccounting(removed);
                clearBatchRequest(removed, clearRequest);
                toRemove -= removed.payloadCount();
            }
        }
    }

    private static void clearRequests(List<VoxelColumnS2CPayload> payloads, IntConsumer clearRequest) {
        HashSet<Integer> cleared = new HashSet<>();
        for (VoxelColumnS2CPayload payload : payloads) {
            if (payload.requestId() >= 0 && cleared.add(payload.requestId())) {
                clearRequest.accept(payload.requestId());
            }
        }
    }

    private static void clearBatchRequest(PlayerRequestState.QueuedPayloadBatch batch, IntConsumer clearRequest) {
        int requestId = batch.requestId();
        if (requestId >= 0) {
            clearRequest.accept(requestId);
        }
    }

    private boolean hasOrderingCenter() {
        return orderedForPlayerCx != Integer.MIN_VALUE && orderedForPlayerCz != Integer.MIN_VALUE;
    }

    private static int ring(PlayerRequestState.QueuedPayloadBatch batch, int playerCx, int playerCz) {
        VoxelColumnS2CPayload payload = batch.firstPayload().payload();
        return Math.max(Math.abs(payload.chunkX() - playerCx), Math.abs(payload.chunkZ() - playerCz));
    }

    private static int saturatedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static final class BucketedPayloadQueue {
        private static final Comparator<PlayerRequestState.QueuedPayloadBatch> SEQUENCE_ORDER =
                Comparator.comparingLong(PlayerRequestState.QueuedPayloadBatch::sequence);

        private final TreeMap<Integer, TreeSet<PlayerRequestState.QueuedPayloadBatch>> buckets = new TreeMap<>();
        private final IdentityHashMap<PlayerRequestState.QueuedPayloadBatch, Integer> batchRings = new IdentityHashMap<>();
        private final ArrayDeque<PlayerRequestState.QueuedPayloadBatch> unordered = new ArrayDeque<>();
        private int orderedForPlayerCx = Integer.MIN_VALUE;
        private int orderedForPlayerCz = Integer.MIN_VALUE;
        private int payloadCount;

        private void add(PlayerRequestState.QueuedPayloadBatch batch, boolean hasOrder, int playerCx, int playerCz) {
            payloadCount += batch.payloadCount();
            if (!hasOrder) {
                unordered.addLast(batch);
                return;
            }
            if (!isOrderedFor(playerCx, playerCz)) {
                prepare(playerCx, playerCz);
            }
            addToBucket(batch, playerCx, playerCz);
        }

        private void prepare(int playerCx, int playerCz) {
            if (isOrderedFor(playerCx, playerCz)) {
                return;
            }

            ArrayDeque<PlayerRequestState.QueuedPayloadBatch> entries = new ArrayDeque<>();
            drainBuckets(entries);
            while (!unordered.isEmpty()) {
                entries.addLast(unordered.removeFirst());
            }

            buckets.clear();
            batchRings.clear();
            orderedForPlayerCx = playerCx;
            orderedForPlayerCz = playerCz;
            for (PlayerRequestState.QueuedPayloadBatch batch : entries) {
                addToBucket(batch, playerCx, playerCz);
            }
        }

        private boolean isOrderedFor(int playerCx, int playerCz) {
            return unordered.isEmpty()
                    && orderedForPlayerCx == playerCx
                    && orderedForPlayerCz == playerCz;
        }

        private PlayerRequestState.QueuedPayloadBatch peek() {
            if (!unordered.isEmpty()) {
                return unordered.peekFirst();
            }
            Map.Entry<Integer, TreeSet<PlayerRequestState.QueuedPayloadBatch>> entry = buckets.firstEntry();
            return entry == null ? null : entry.getValue().first();
        }

        private PlayerRequestState.QueuedPayloadBatch poll(PlayerRequestState.QueuedPayloadBatch batch) {
            if (batch == null) {
                return null;
            }
            if (!unordered.isEmpty()) {
                PlayerRequestState.QueuedPayloadBatch removed = unordered.peekFirst() == batch ? unordered.pollFirst() : null;
                if (removed == null && unordered.remove(batch)) {
                    removed = batch;
                }
                if (removed != null) {
                    payloadCount = Math.max(0, payloadCount - removed.payloadCount());
                }
                return removed;
            }

            Integer ring = batchRings.remove(batch);
            if (ring == null) {
                return null;
            }
            TreeSet<PlayerRequestState.QueuedPayloadBatch> bucket = buckets.get(ring);
            PlayerRequestState.QueuedPayloadBatch removed = removeFromBucket(bucket, batch);
            if (bucket != null && bucket.isEmpty()) {
                buckets.remove(ring);
            }
            if (removed != null) {
                payloadCount = Math.max(0, payloadCount - removed.payloadCount());
            }
            return removed;
        }

        private boolean contains(PlayerRequestState.QueuedPayloadBatch batch) {
            if (batch == null) {
                return false;
            }
            if (!unordered.isEmpty()) {
                return unordered.contains(batch);
            }
            Integer ring = batchRings.get(batch);
            if (ring == null) {
                return false;
            }
            TreeSet<PlayerRequestState.QueuedPayloadBatch> bucket = buckets.get(ring);
            return bucket != null && bucket.contains(batch);
        }

        private void removeBatch(PlayerRequestState.QueuedPayloadBatch batch) {
            if (batch == null) {
                return;
            }
            if (!unordered.isEmpty()) {
                unordered.remove(batch);
                return;
            }
            Integer ring = batchRings.remove(batch);
            if (ring == null) {
                return;
            }
            TreeSet<PlayerRequestState.QueuedPayloadBatch> bucket = buckets.get(ring);
            if (bucket != null) {
                bucket.remove(batch);
                if (bucket.isEmpty()) {
                    buckets.remove(ring);
                }
            }
        }

        private void decrementPayloadCount(int amount) {
            payloadCount = Math.max(0, payloadCount - Math.max(0, amount));
        }

        private PlayerRequestState.QueuedPayloadBatch pollFarthestOrOldest() {
            PlayerRequestState.QueuedPayloadBatch removed;
            if (!unordered.isEmpty()) {
                removed = unordered.pollFirst();
            } else {
                Map.Entry<Integer, TreeSet<PlayerRequestState.QueuedPayloadBatch>> entry = buckets.lastEntry();
                if (entry == null) {
                    return null;
                }
                removed = entry.getValue().pollFirst();
                if (entry.getValue().isEmpty()) {
                    buckets.pollLastEntry();
                }
            }
            if (removed != null) {
                batchRings.remove(removed);
                payloadCount = Math.max(0, payloadCount - removed.payloadCount());
            }
            return removed;
        }

        private int size() {
            return payloadCount;
        }

        private boolean isEmpty() {
            return payloadCount <= 0;
        }

        private void clear() {
            buckets.clear();
            batchRings.clear();
            unordered.clear();
            orderedForPlayerCx = Integer.MIN_VALUE;
            orderedForPlayerCz = Integer.MIN_VALUE;
            payloadCount = 0;
        }

        private void addToBucket(PlayerRequestState.QueuedPayloadBatch batch, int playerCx, int playerCz) {
            int ring = ring(batch, playerCx, playerCz);
            buckets.computeIfAbsent(ring, ignored -> new TreeSet<>(SEQUENCE_ORDER))
                    .add(batch);
            batchRings.put(batch, ring);
        }

        private void drainBuckets(ArrayDeque<PlayerRequestState.QueuedPayloadBatch> entries) {
            for (NavigableSet<PlayerRequestState.QueuedPayloadBatch> bucket : buckets.values()) {
                while (!bucket.isEmpty()) {
                    PlayerRequestState.QueuedPayloadBatch batch = bucket.pollFirst();
                    batchRings.remove(batch);
                    entries.addLast(batch);
                }
            }
        }

        private PlayerRequestState.QueuedPayloadBatch removeFromBucket(
                TreeSet<PlayerRequestState.QueuedPayloadBatch> bucket,
                PlayerRequestState.QueuedPayloadBatch batch) {
            if (bucket == null) {
                return null;
            }
            return bucket.remove(batch) ? batch : null;
        }
    }
}
