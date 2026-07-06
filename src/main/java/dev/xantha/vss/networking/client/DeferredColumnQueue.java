package dev.xantha.vss.networking.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class DeferredColumnQueue implements Iterable<Long> {
    private final LongOpenHashSet columns = new LongOpenHashSet();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final int maxColumns;

    DeferredColumnQueue(int maxColumns) {
        this.maxColumns = Math.max(1, maxColumns);
    }

    boolean contains(long packed) {
        return columns.contains(packed);
    }

    boolean remove(long packed) {
        return columns.remove(packed);
    }

    int size() {
        return columns.size();
    }

    int queuedEntries() {
        return queue.size();
    }

    void clear() {
        columns.clear();
        queue.clear();
    }

    void defer(long packed) {
        defer(packed, false);
    }

    void defer(long packed, boolean urgent) {
        boolean alreadyDeferred = columns.contains(packed);
        if (!alreadyDeferred && columns.size() >= maxColumns) {
            evictOldestQueuedColumn();
        }
        if (columns.add(packed) || urgent) {
            if (urgent) {
                queue.addFirst(packed);
                return;
            }
            queue.addLast(packed);
        }
    }

    void requeue(long packed, boolean urgent) {
        if (columns.size() >= maxColumns && !columns.contains(packed)) {
            evictOldestQueuedColumn();
        }
        columns.add(packed);
        if (urgent) {
            queue.addFirst(packed);
        } else {
            queue.addLast(packed);
        }
    }

    List<Long> pollUniqueCandidates(int maxAttempts) {
        int attempts = Math.min(queue.size(), Math.max(0, maxAttempts));
        ArrayList<Long> candidates = new ArrayList<>(attempts);
        LongOpenHashSet seen = new LongOpenHashSet();
        while (attempts-- > 0) {
            Long queued = queue.pollFirst();
            if (queued == null) {
                break;
            }
            long packed = queued.longValue();
            if (!columns.contains(packed) || !seen.add(packed)) {
                continue;
            }
            candidates.add(packed);
        }
        return candidates;
    }

    void compact() {
        queue.removeIf(queued -> !columns.contains(queued.longValue()));
    }

    @Override
    public Iterator<Long> iterator() {
        return columns.iterator();
    }

    private void evictOldestQueuedColumn() {
        Long oldest = queue.pollFirst();
        if (oldest != null) {
            columns.remove(oldest.longValue());
        }
    }
}
