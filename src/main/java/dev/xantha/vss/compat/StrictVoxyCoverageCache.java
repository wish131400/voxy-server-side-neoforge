package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.HashCommon;

/** Render-thread range cache. Four-way clock replacement never shifts a hash table or allocates columns. */
public final class StrictVoxyCoverageCache {
    private static final int MAX_COLUMNS = 262_144, MAX_WAYS = 4;
    private static final byte OCCUPIED = 1, RECENT = 2, RESULT = 4;
    private final int capacity, ways, buckets, bucketMask;
    private final long[] keys, versions;
    private final int[] firsts, lasts, coveredFirsts, coveredLasts;
    private final byte[] flags, clocks;
    private int size;
    private long queries, hits, misses, evictions;

    public StrictVoxyCoverageCache() { this(MAX_COLUMNS); }

    StrictVoxyCoverageCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Coverage cache must have a positive capacity");
        this.capacity = capacity;
        ways = Math.min(MAX_WAYS, capacity);
        buckets = (capacity + ways - 1) / ways;
        bucketMask = Integer.bitCount(buckets) == 1 ? buckets - 1 : -1;
        keys = new long[capacity]; versions = new long[capacity];
        firsts = new int[capacity]; lasts = new int[capacity];
        coveredFirsts = new int[capacity]; coveredLasts = new int[capacity];
        flags = new byte[capacity]; clocks = new byte[buckets];
    }

    public boolean covers(int chunkX, int minY, int maxY, int chunkZ, long version, Query query) {
        queries++;
        int first = Math.min(minY, maxY), last = Math.max(minY, maxY);
        long key = (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
        long hash = HashCommon.mix(key);
        int bucket = bucketMask >= 0 ? (int) hash & bucketMask : (int) Long.remainderUnsigned(hash, buckets);
        int start = bucket * ways, end = Math.min(start + ways, capacity);
        int slot = -1, free = -1;
        for (int i = start; i < end; i++) {
            if ((flags[i] & OCCUPIED) == 0) { if (free < 0) free = i; }
            else if (keys[i] == key) { slot = i; break; }
        }
        if (slot < 0) {
            slot = free >= 0 ? free : victim(bucket, start, end);
            if ((flags[slot] & OCCUPIED) == 0) size++; else evictions++;
            keys[slot] = key;
            reset(slot, version);
        } else if (versions[slot] != version) reset(slot, version);
        flags[slot] |= RECENT;
        if (coveredFirsts[slot] <= first && coveredLasts[slot] >= last) { hits++; return true; }
        if (firsts[slot] == first && lasts[slot] == last) { hits++; return (flags[slot] & RESULT) != 0; }
        misses++;
        boolean result = query.covers(chunkX, first, last, chunkZ);
        firsts[slot] = first; lasts[slot] = last;
        if (result) {
            flags[slot] |= RESULT;
            if ((long) first <= (long) coveredLasts[slot] + 1
                    && (long) last >= (long) coveredFirsts[slot] - 1) {
                coveredFirsts[slot] = Math.min(coveredFirsts[slot], first);
                coveredLasts[slot] = Math.max(coveredLasts[slot], last);
            } else { coveredFirsts[slot] = first; coveredLasts[slot] = last; }
        } else flags[slot] &= ~RESULT;
        return result;
    }

    private void reset(int slot, long version) {
        versions[slot] = version; flags[slot] = OCCUPIED;
        firsts[slot] = 1; lasts[slot] = 0;
        coveredFirsts[slot] = Integer.MAX_VALUE; coveredLasts[slot] = Integer.MIN_VALUE;
    }

    private int victim(int bucket, int start, int end) {
        int count = end - start, cursor = clocks[bucket];
        for (;;) {
            int slot = start + cursor;
            cursor = (cursor + 1) % count;
            if ((flags[slot] & RECENT) == 0) { clocks[bucket] = (byte) cursor; return slot; }
            flags[slot] &= ~RECENT;
        }
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public int size() { return size; }
    public String diagnostics() {
        return "columns=" + size + ",queries=" + queries + ",hits=" + hits + ",misses=" + misses
                + ",capacity=" + capacity + ",evictions=" + evictions + ",replacement=clock4";
    }

    @FunctionalInterface public interface Query {
        boolean covers(int chunkX, int minSectionY, int maxSectionY, int chunkZ);
    }
}
