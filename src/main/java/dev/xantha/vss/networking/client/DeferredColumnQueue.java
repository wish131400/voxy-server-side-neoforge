package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Iterator;

final class DeferredColumnQueue implements Iterable<Long> {
    private final LongOpenHashSet columns = new LongOpenHashSet();
    private final LongOpenHashSet urgentColumns = new LongOpenHashSet();
    private final Int2ObjectAVLTreeMap<LongArrayFIFOQueue> urgentColumnsByRing = new Int2ObjectAVLTreeMap<>();
    private final Int2ObjectAVLTreeMap<LongArrayFIFOQueue> normalColumnsByRing = new Int2ObjectAVLTreeMap<>();
    private final int maxColumns;
    private int queuedEntries;
    private int centerCx = Integer.MIN_VALUE;
    private int centerCz = Integer.MIN_VALUE;

    DeferredColumnQueue(int maxColumns) {
        this.maxColumns = Math.max(1, maxColumns);
    }

    boolean contains(long packed) {
        return columns.contains(packed);
    }

    boolean remove(long packed) {
        urgentColumns.remove(packed);
        return columns.remove(packed);
    }

    int size() {
        return columns.size();
    }

    int queuedEntries() {
        return queuedEntries;
    }

    void clear() {
        columns.clear();
        urgentColumns.clear();
        urgentColumnsByRing.clear();
        normalColumnsByRing.clear();
        queuedEntries = 0;
        centerCx = Integer.MIN_VALUE;
        centerCz = Integer.MIN_VALUE;
    }

    void recenter(int playerCx, int playerCz) {
        if (playerCx == centerCx && playerCz == centerCz) {
            return;
        }
        centerCx = playerCx;
        centerCz = playerCz;
        rebuildBuckets();
    }

    void defer(long packed) {
        defer(packed, false);
    }

    void defer(long packed, boolean urgent) {
        boolean alreadyDeferred = columns.contains(packed);
        if (!alreadyDeferred && !ensureCapacityFor(urgent)) {
            return;
        }
        if (!alreadyDeferred) {
            columns.add(packed);
            if (urgent) {
                urgentColumns.add(packed);
            }
            enqueue(packed, urgent);
            return;
        }
        if (urgent) {
            urgentColumns.add(packed);
            enqueue(packed, true);
        }
    }

    void requeue(long packed, boolean urgent) {
        boolean alreadyDeferred = columns.contains(packed);
        if (!alreadyDeferred && !ensureCapacityFor(urgent)) {
            return;
        }
        columns.add(packed);
        if (urgent) {
            urgentColumns.add(packed);
        }
        enqueue(packed, urgent || urgentColumns.contains(packed));
    }

    LongList pollClosestCandidates(int maxAttempts, boolean urgentOnly) {
        int attempts = Math.min(queuedEntries, Math.max(0, maxAttempts));
        LongArrayList candidates = new LongArrayList(attempts);
        LongOpenHashSet seen = new LongOpenHashSet();
        while (attempts > 0 && candidates.size() < maxAttempts) {
            long queued = pollClosest(urgentColumnsByRing);
            if (queued == Long.MIN_VALUE && !urgentOnly) {
                queued = pollClosest(normalColumnsByRing);
            }
            if (queued == Long.MIN_VALUE) {
                break;
            }
            attempts--;
            long packed = queued;
            if (!columns.contains(packed)
                    || (urgentOnly && !urgentColumns.contains(packed))
                    || !seen.add(packed)) {
                continue;
            }
            candidates.add(packed);
        }
        return candidates;
    }

    void compact() {
        rebuildBuckets();
    }

    @Override
    public Iterator<Long> iterator() {
        return columns.iterator();
    }

    private boolean ensureCapacityFor(boolean incomingUrgent) {
        while (columns.size() >= maxColumns) {
            long evicted = evictFurthest(normalColumnsByRing, false);
            if (evicted == Long.MIN_VALUE && incomingUrgent) {
                evicted = evictFurthest(urgentColumnsByRing, true);
            }
            if (evicted == Long.MIN_VALUE) {
                return false;
            }
        }
        return true;
    }

    private long evictFurthest(Int2ObjectAVLTreeMap<LongArrayFIFOQueue> buckets, boolean urgentBucket) {
        while (!buckets.isEmpty()) {
            int ring = buckets.lastIntKey();
            LongArrayFIFOQueue bucket = buckets.get(ring);
            long queued = bucket.dequeueLastLong();
            queuedEntries = Math.max(0, queuedEntries - 1);
            if (bucket.isEmpty()) {
                buckets.remove(ring);
            }
            long packed = queued;
            if (!columns.contains(packed)) {
                continue;
            }
            boolean isUrgent = urgentColumns.contains(packed);
            if (isUrgent != urgentBucket) {
                continue;
            }
            columns.remove(packed);
            urgentColumns.remove(packed);
            return packed;
        }
        return Long.MIN_VALUE;
    }

    private void enqueue(long packed, boolean urgent) {
        Int2ObjectAVLTreeMap<LongArrayFIFOQueue> buckets = bucketsFor(urgent);
        int ring = ringFor(packed);
        LongArrayFIFOQueue bucket = buckets.get(ring);
        if (bucket == null) {
            bucket = new LongArrayFIFOQueue();
            buckets.put(ring, bucket);
        }
        bucket.enqueue(packed);
        queuedEntries++;
    }

    private long pollClosest(Int2ObjectAVLTreeMap<LongArrayFIFOQueue> buckets) {
        while (!buckets.isEmpty()) {
            int ring = buckets.firstIntKey();
            LongArrayFIFOQueue bucket = buckets.get(ring);
            long queued = bucket.dequeueLong();
            queuedEntries = Math.max(0, queuedEntries - 1);
            if (bucket.isEmpty()) {
                buckets.remove(ring);
            }
            return queued;
        }
        return Long.MIN_VALUE;
    }

    private Int2ObjectAVLTreeMap<LongArrayFIFOQueue> bucketsFor(boolean urgent) {
        return urgent ? urgentColumnsByRing : normalColumnsByRing;
    }

    private int ringFor(long packed) {
        if (centerCx == Integer.MIN_VALUE || centerCz == Integer.MIN_VALUE) {
            return 0;
        }
        return PositionUtil.chebyshevDistance(
                PositionUtil.unpackX(packed),
                PositionUtil.unpackZ(packed),
                centerCx,
                centerCz);
    }

    private void rebuildBuckets() {
        urgentColumnsByRing.clear();
        normalColumnsByRing.clear();
        queuedEntries = 0;

        LongOpenHashSet validUrgentColumns = new LongOpenHashSet();
        for (long packed : urgentColumns) {
            if (columns.contains(packed)) {
                validUrgentColumns.add(packed);
            }
        }
        urgentColumns.clear();
        urgentColumns.addAll(validUrgentColumns);

        for (long packed : columns) {
            enqueue(packed, urgentColumns.contains(packed));
        }
    }
}
