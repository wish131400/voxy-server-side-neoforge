package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/** Mixed coordinate hashing: Long.hashCode(pack(x,y,z)) collapses regular sampling grids.
 * Values are immutable RGB triples. Generation checks prevent old work repopulating a reload. */
final class PredictionTintCache {
    private final Long2ObjectLinkedOpenHashMap<int[]> entries = new Long2ObjectLinkedOpenHashMap<>();
    private final int capacity;
    private long generation, hits, misses;
    PredictionTintCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("tint cache capacity");
        this.capacity = capacity;
    }
    synchronized long generation() { return generation; }
    synchronized int[] get(long key) {
        int[] value = entries.getAndMoveToLast(key);
        if (value == null) misses++; else hits++;
        return value;
    }
    synchronized boolean put(long key, int[] colors, long expectedGeneration) {
        if (expectedGeneration != generation) return false;
        entries.putAndMoveToLast(key, colors);
        while (entries.size() > capacity) entries.removeFirst();
        return true;
    }
    synchronized void clear() { generation++; entries.clear(); }
    synchronized String diagnostics() { return "hits="+hits+",misses="+misses+",size="+entries.size(); }
}
