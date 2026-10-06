package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.HashCommon;

/** Fixed primitive storage for whole-cell coverage proofs; replacement never rebuilds a map. */
public final class StrictVoxyCoverageRegions {
    private static final byte OCCUPIED = 1, RECENT = 2, COVERED = 4;
    private final int capacity, ways, buckets;
    private final int[] minXs, minZs, maxXs, maxZs, firstYs, lastYs;
    private final long[] versions;
    private final byte[] flags, clocks;
    private int size;
    private long hits, misses, evictions;

    public StrictVoxyCoverageRegions() { this(131_072); }
    StrictVoxyCoverageRegions(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Coverage cache must have a positive capacity");
        this.capacity = capacity; ways = Math.min(4, capacity); buckets = (capacity + ways - 1) / ways;
        minXs = new int[capacity]; minZs = new int[capacity]; maxXs = new int[capacity]; maxZs = new int[capacity];
        firstYs = new int[capacity]; lastYs = new int[capacity]; versions = new long[capacity];
        flags = new byte[capacity]; clocks = new byte[buckets];
    }

    public boolean covers(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long version, Query query) {
        int firstY = Math.min(minY, maxY), lastY = Math.max(minY, maxY);
        long min = (long) minX << 32 | minZ & 0xFFFFFFFFL, max = (long) maxX << 32 | maxZ & 0xFFFFFFFFL;
        long hash = HashCommon.mix(min ^ Long.rotateLeft(HashCommon.mix(max), 23));
        int bucket = (int) Long.remainderUnsigned(hash, buckets), start = bucket * ways, end = Math.min(start + ways, capacity);
        int slot = -1, free = -1;
        for (int i = start; i < end; i++) {
            if ((flags[i] & OCCUPIED) == 0) { if (free < 0) free = i; }
            else if (minXs[i] == minX && minZs[i] == minZ && maxXs[i] == maxX && maxZs[i] == maxZ) { slot = i; break; }
        }
        if (slot >= 0 && versions[slot] == version && firstYs[slot] == firstY && lastYs[slot] == lastY) {
            hits++; flags[slot] |= RECENT; return (flags[slot] & COVERED) != 0;
        }
        misses++;
        boolean result = query.covers(minX, firstY, minZ, maxX, lastY, maxZ);
        if (slot < 0) {
            slot = free >= 0 ? free : victim(bucket, start, end);
            if ((flags[slot] & OCCUPIED) == 0) size++; else evictions++;
        }
        minXs[slot] = minX; minZs[slot] = minZ; maxXs[slot] = maxX; maxZs[slot] = maxZ;
        firstYs[slot] = firstY; lastYs[slot] = lastY; versions[slot] = version;
        flags[slot] = (byte) (OCCUPIED | RECENT | (result ? COVERED : 0));
        return result;
    }

    private int victim(int bucket, int start, int end) {
        int count = end - start, cursor = clocks[bucket];
        for (;;) {
            int slot = start + cursor; cursor = (cursor + 1) % count;
            if ((flags[slot] & RECENT) == 0) { clocks[bucket] = (byte) cursor; return slot; }
            flags[slot] &= ~RECENT;
        }
    }
    public long hits() { return hits; }
    public long misses() { return misses; }
    public String diagnostics() { return "regions=" + size + ",hits=" + hits + ",misses=" + misses + ",evictions=" + evictions; }

    @FunctionalInterface public interface Query {
        boolean covers(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    }
}
