package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Iterator;

final class DeferredColumnQueue implements Iterable<Long> {
    private final LongOpenHashSet columns = new LongOpenHashSet();
    private final LongOpenHashSet queuedColumns = new LongOpenHashSet();
    private final LongOpenHashSet urgentColumns = new LongOpenHashSet();
    private final Int2ObjectAVLTreeMap<LongLinkedOpenHashSet> urgentColumnsByRing = new Int2ObjectAVLTreeMap<>();
    private final Int2ObjectAVLTreeMap<LongLinkedOpenHashSet> normalColumnsByRing = new Int2ObjectAVLTreeMap<>();
    private final int maxColumns;
    private int centerCx = Integer.MIN_VALUE;
    private int centerCz = Integer.MIN_VALUE;

    DeferredColumnQueue(int maxColumns) {
        this.maxColumns = Math.max(1, maxColumns);
    }

    boolean contains(long packed) {
        return columns.contains(packed);
    }

    boolean remove(long packed) {
        if (!columns.remove(packed)) {
            return false;
        }
        boolean urgent = urgentColumns.remove(packed);
        if (queuedColumns.remove(packed)) {
            removeFromBucket(packed, urgent);
        }
        return true;
    }

    int size() {
        return columns.size();
    }

    int queuedEntries() {
        return queuedColumns.size();
    }

    void clear() {
        columns.clear();
        queuedColumns.clear();
        urgentColumns.clear();
        urgentColumnsByRing.clear();
        normalColumnsByRing.clear();
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
        boolean existing = columns.contains(packed);
        if (!existing && !ensureCapacityFor(packed, urgent)) {
            return;
        }
        if (!existing) {
            columns.add(packed);
        }

        boolean wasUrgent = urgentColumns.contains(packed);
        boolean targetUrgent = urgent || wasUrgent;
        if (urgent && !wasUrgent) {
            urgentColumns.add(packed);
            if (queuedColumns.remove(packed)) {
                removeFromBucket(packed, false);
            }
        }
        if (!queuedColumns.contains(packed)) {
            enqueue(packed, targetUrgent);
        }
    }

    void requeue(long packed, boolean urgent) {
        boolean existing = columns.contains(packed);
        if (!existing && !ensureCapacityFor(packed, urgent)) {
            return;
        }
        columns.add(packed);

        boolean wasUrgent = urgentColumns.contains(packed);
        boolean targetUrgent = urgent || wasUrgent;
        if (targetUrgent) {
            urgentColumns.add(packed);
        }
        if (queuedColumns.remove(packed)) {
            removeFromBucket(packed, wasUrgent);
        }
        enqueue(packed, targetUrgent);
    }

    LongList pollClosestCandidates(int maxAttempts, boolean urgentOnly) {
        int attempts = Math.min(queuedColumns.size(), Math.max(0, maxAttempts));
        LongArrayList candidates = new LongArrayList(attempts);
        while (attempts-- > 0 && candidates.size() < maxAttempts) {
            long packed = pollClosest(urgentColumnsByRing);
            if (packed == Long.MIN_VALUE && !urgentOnly) {
                packed = pollClosest(normalColumnsByRing);
            }
            if (packed == Long.MIN_VALUE) {
                break;
            }
            queuedColumns.remove(packed);
            if (!columns.contains(packed)
                    || (urgentOnly && !urgentColumns.contains(packed))) {
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

    private boolean ensureCapacityFor(long incoming, boolean incomingUrgent) {
        while (columns.size() >= maxColumns) {
            long evicted;
            if (!normalColumnsByRing.isEmpty() && incomingUrgent) {
                evicted = evictFurthest(normalColumnsByRing, false);
            } else {
                Int2ObjectAVLTreeMap<LongLinkedOpenHashSet> candidates = incomingUrgent
                        ? urgentColumnsByRing
                        : normalColumnsByRing;
                if (candidates.isEmpty() || ringFor(incoming) >= candidates.lastIntKey()) {
                    return false;
                }
                evicted = evictFurthest(candidates, incomingUrgent);
            }
            if (evicted == Long.MIN_VALUE) {
                return false;
            }
        }
        return true;
    }

    private long evictFurthest(
            Int2ObjectAVLTreeMap<LongLinkedOpenHashSet> buckets,
            boolean urgentBucket) {
        while (!buckets.isEmpty()) {
            int ring = buckets.lastIntKey();
            LongLinkedOpenHashSet bucket = buckets.get(ring);
            long packed = bucket.removeLastLong();
            if (bucket.isEmpty()) {
                buckets.remove(ring);
            }
            queuedColumns.remove(packed);
            if (!columns.contains(packed)
                    || urgentColumns.contains(packed) != urgentBucket) {
                continue;
            }
            columns.remove(packed);
            urgentColumns.remove(packed);
            return packed;
        }
        return Long.MIN_VALUE;
    }

    private void enqueue(long packed, boolean urgent) {
        Int2ObjectAVLTreeMap<LongLinkedOpenHashSet> buckets =
                urgent ? urgentColumnsByRing : normalColumnsByRing;
        int ring = ringFor(packed);
        buckets.computeIfAbsent(ring, ignored -> new LongLinkedOpenHashSet())
                .add(packed);
        queuedColumns.add(packed);
    }

    private long pollClosest(Int2ObjectAVLTreeMap<LongLinkedOpenHashSet> buckets) {
        while (!buckets.isEmpty()) {
            int ring = buckets.firstIntKey();
            LongLinkedOpenHashSet bucket = buckets.get(ring);
            long packed = bucket.removeFirstLong();
            if (bucket.isEmpty()) {
                buckets.remove(ring);
            }
            return packed;
        }
        return Long.MIN_VALUE;
    }

    private void removeFromBucket(long packed, boolean urgent) {
        Int2ObjectAVLTreeMap<LongLinkedOpenHashSet> buckets =
                urgent ? urgentColumnsByRing : normalColumnsByRing;
        int ring = ringFor(packed);
        LongLinkedOpenHashSet bucket = buckets.get(ring);
        if (bucket == null) {
            return;
        }
        bucket.remove(packed);
        if (bucket.isEmpty()) {
            buckets.remove(ring);
        }
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
        queuedColumns.clear();

        urgentColumns.removeIf(packed -> !columns.contains(packed));
        for (long packed : columns) {
            enqueue(packed, urgentColumns.contains(packed));
        }
    }
}
