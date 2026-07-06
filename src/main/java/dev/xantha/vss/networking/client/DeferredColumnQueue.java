package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class DeferredColumnQueue implements Iterable<Long> {
    private final LongOpenHashSet columns = new LongOpenHashSet();
    private final LongOpenHashSet urgentColumns = new LongOpenHashSet();
    private final TreeMap<Integer, ArrayDeque<Long>> urgentColumnsByRing = new TreeMap<>();
    private final TreeMap<Integer, ArrayDeque<Long>> normalColumnsByRing = new TreeMap<>();
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

    List<Long> pollClosestCandidates(int maxAttempts, boolean urgentOnly) {
        int attempts = Math.min(queuedEntries, Math.max(0, maxAttempts));
        ArrayList<Long> candidates = new ArrayList<>(attempts);
        LongOpenHashSet seen = new LongOpenHashSet();
        while (attempts > 0 && candidates.size() < maxAttempts) {
            Long queued = pollClosest(urgentColumnsByRing);
            if (queued == null && !urgentOnly) {
                queued = pollClosest(normalColumnsByRing);
            }
            if (queued == null) {
                break;
            }
            attempts--;
            long packed = queued.longValue();
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
            Long evicted = evictFurthest(normalColumnsByRing, false);
            if (evicted == null && incomingUrgent) {
                evicted = evictFurthest(urgentColumnsByRing, true);
            }
            if (evicted == null) {
                return false;
            }
        }
        return true;
    }

    private Long evictFurthest(TreeMap<Integer, ArrayDeque<Long>> buckets, boolean urgentBucket) {
        while (!buckets.isEmpty()) {
            Map.Entry<Integer, ArrayDeque<Long>> entry = buckets.lastEntry();
            ArrayDeque<Long> bucket = entry.getValue();
            Long queued = bucket.pollLast();
            queuedEntries = Math.max(0, queuedEntries - 1);
            if (bucket.isEmpty()) {
                buckets.pollLastEntry();
            }
            if (queued == null) {
                continue;
            }
            long packed = queued.longValue();
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
        return null;
    }

    private void enqueue(long packed, boolean urgent) {
        bucketsFor(urgent).computeIfAbsent(ringFor(packed), ignored -> new ArrayDeque<>()).addLast(packed);
        queuedEntries++;
    }

    private Long pollClosest(TreeMap<Integer, ArrayDeque<Long>> buckets) {
        while (!buckets.isEmpty()) {
            Map.Entry<Integer, ArrayDeque<Long>> entry = buckets.firstEntry();
            ArrayDeque<Long> bucket = entry.getValue();
            Long queued = bucket.pollFirst();
            queuedEntries = Math.max(0, queuedEntries - 1);
            if (bucket.isEmpty()) {
                buckets.pollFirstEntry();
            }
            if (queued != null) {
                return queued;
            }
        }
        return null;
    }

    private TreeMap<Integer, ArrayDeque<Long>> bucketsFor(boolean urgent) {
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
