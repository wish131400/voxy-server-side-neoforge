package dev.xantha.vss.compat;

import java.util.LinkedHashMap;

/** Separate from native in-flight child requests. No lock is held while calling Voxy or the source. */
final class VoxySubdivisionWaiters {
    private final LinkedHashMap<Long, Boolean> positions = new LinkedHashMap<>();

    synchronized boolean add(long position) { return add(position, true); }
    synchronized boolean add(long position, boolean needsSamples) { return positions.put(position, needsSamples) == null; }
    synchronized boolean needsSamples(long position) { return Boolean.TRUE.equals(positions.get(position)); }
    synchronized boolean contains(long position) { return positions.containsKey(position); }
    synchronized boolean remove(long position) { return positions.remove(position) != null; }
    synchronized int size() { return positions.size(); }
    synchronized void clear() { positions.clear(); }

    /** Rotate a bounded set of live entries; completed/removed entries leave no stale queue records. */
    synchronized long[] nextBatch(int limit) {
        long[] batch = new long[Math.min(limit, positions.size())];
        boolean[] samples = new boolean[batch.length];
        var iterator = positions.entrySet().iterator();
        for (int i = 0; i < batch.length; i++) {
            var entry = iterator.next(); batch[i] = entry.getKey(); samples[i] = entry.getValue(); iterator.remove();
        }
        for (int i = 0; i < batch.length; i++) positions.put(batch[i], samples[i]);
        return batch;
    }
}
