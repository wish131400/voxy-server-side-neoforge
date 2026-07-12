package dev.xantha.vss.networking.server.state;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

public final class PlayerSendQueue {
    private final BucketedPayloadQueue priorityQueue = new BucketedPayloadQueue();
    private final BucketedPayloadQueue normalQueue = new BucketedPayloadQueue();
    private final IdentityHashMap<PlayerRequestState.QueuedPayload, PlayerRequestState.QueuedPayloadBatch> payloadBatches = new IdentityHashMap<>();
    private int priorityPayloads;
    private long queuedBytes;
    private int orderedForPlayerCx = Integer.MIN_VALUE;
    private int orderedForPlayerCz = Integer.MIN_VALUE;
    private long nextSequence;

    public synchronized EnqueueResult enqueue(
            VoxelColumnS2CPayload payload,
            boolean priority,
            int queueLimit,
            int queueBytesLimit,
            boolean networkCompressionEnabled) {
        return enqueue(List.of(payload), priority, queueLimit, queueBytesLimit, networkCompressionEnabled);
    }

    public synchronized EnqueueResult enqueue(
            List<VoxelColumnS2CPayload> payloads,
            boolean priority,
            int queueLimit,
            int queueBytesLimit,
            boolean networkCompressionEnabled) {
        if (payloads == null || payloads.isEmpty()) {
            return EnqueueResult.accepted(List.of());
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

        ArrayList<Integer> rejectedRequestIds = new ArrayList<>();
        int incomingRing = hasOrderingCenter()
                ? ringForPayload(payloads.get(0), orderedForPlayerCx, orderedForPlayerCz)
                : Integer.MAX_VALUE;
        while (currentQueueSize + queuedPayloads.size() > queueLimit
                || queuedBytes + batchWireBytes > queueBytesLimit) {
            PlayerRequestState.QueuedPayloadBatch candidate = normalQueue.peekFarthestUnstarted();
            if (candidate == null
                    || (!priority && (incomingRing == Integer.MAX_VALUE
                    || ring(candidate, orderedForPlayerCx, orderedForPlayerCz) <= incomingRing))) {
                addRequestIds(payloads, rejectedRequestIds);
                return EnqueueResult.rejected(rejectedRequestIds);
            }
            PlayerRequestState.QueuedPayloadBatch removed = normalQueue.poll(candidate);
            if (removed == null) {
                addRequestIds(payloads, rejectedRequestIds);
                return EnqueueResult.rejected(rejectedRequestIds);
            }
            removeBatchAccounting(removed);
            addRequestId(removed.requestId(), rejectedRequestIds);
            currentQueueSize = queuedPayloadCount();
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
        return EnqueueResult.accepted(rejectedRequestIds);
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

    private static void addRequestIds(List<VoxelColumnS2CPayload> payloads, List<Integer> requestIds) {
        for (VoxelColumnS2CPayload payload : payloads) {
            addRequestId(payload.requestId(), requestIds);
        }
    }

    private static void addRequestId(int requestId, List<Integer> requestIds) {
        if (requestId >= 0 && !requestIds.contains(requestId)) {
            requestIds.add(requestId);
        }
    }

    private boolean hasOrderingCenter() {
        return orderedForPlayerCx != Integer.MIN_VALUE && orderedForPlayerCz != Integer.MIN_VALUE;
    }

    private static int ring(PlayerRequestState.QueuedPayloadBatch batch, int playerCx, int playerCz) {
        VoxelColumnS2CPayload payload = batch.firstPayload().payload();
        return ringForPayload(payload, playerCx, playerCz);
    }

    private static int ringForPayload(VoxelColumnS2CPayload payload, int playerCx, int playerCz) {
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

        private PlayerRequestState.QueuedPayloadBatch peekFarthestUnstarted() {
            if (!unordered.isEmpty()) {
                for (PlayerRequestState.QueuedPayloadBatch batch : unordered) {
                    if (!batch.hasSentPayloads()) {
                        return batch;
                    }
                }
                return null;
            }
            for (Map.Entry<Integer, TreeSet<PlayerRequestState.QueuedPayloadBatch>> entry
                    : buckets.descendingMap().entrySet()) {
                for (PlayerRequestState.QueuedPayloadBatch batch : entry.getValue()) {
                    if (!batch.hasSentPayloads()) {
                        return batch;
                    }
                }
            }
            return null;
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

    public record EnqueueResult(boolean accepted, List<Integer> rejectedRequestIds) {
        public EnqueueResult {
            rejectedRequestIds = List.copyOf(rejectedRequestIds);
        }

        private static EnqueueResult accepted(List<Integer> rejectedRequestIds) {
            return new EnqueueResult(true, rejectedRequestIds);
        }

        private static EnqueueResult rejected(List<Integer> rejectedRequestIds) {
            return new EnqueueResult(false, rejectedRequestIds);
        }
    }
}
