package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.Arrays;

/** Render-thread ownership cache with local snapshot stamps and fixed reusable storage. */
final class PredictionCoverageOwners {
    private static final int STAMP_BUCKETS = 16_384;
    private static final byte OCCUPIED = 1, RECENT = 2;
    private final int capacity, ways, buckets;
    private final long[] keys, versions, checkedAt;
    private final byte[] details, flags, clocks;
    private final PredictionTileManager.PredictionTile[] owners;
    private final long[][] regionVersions = new long[21][STAMP_BUCKETS];
    private PredictionTileManager.RenderSnapshot source;
    private int entries;
    private long serial, hits, misses, evictions, publications, invalidations;

    PredictionCoverageOwners() { this(262_144); }
    PredictionCoverageOwners(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Ownership cache must have a positive capacity");
        this.capacity = capacity; ways = Math.min(4, capacity); buckets = (capacity + ways - 1) / ways;
        keys = new long[capacity]; versions = new long[capacity]; checkedAt = new long[capacity];
        details = new byte[capacity]; flags = new byte[capacity]; clocks = new byte[buckets];
        owners = new PredictionTileManager.PredictionTile[capacity];
    }

    PredictionTileManager.PredictionTile coveringTileAtDetail(PredictionTileManager.RenderSnapshot snapshot,
                                                             int x, int z, int desiredLod) {
        if (desiredLod < 0) return null;
        if (source != snapshot) advance(snapshot);
        // Every level is aligned to this minimum footprint. The sixteen
        // Minecraft chunks in it have the same owner for a given detail.
        int span = snapshot.layout().tileBlocks(0) / 16;
        x = Math.floorDiv(x, span) * span;
        z = Math.floorDiv(z, span) * span;
        // Ordinary ownership does not depend on desired detail; share its answer across levels.
        int detail = snapshot.hasScopedTiles() ? Math.min(20, desiredLod) : 0;
        long key = pack(x, z), hash = HashCommon.mix(key ^ detail * 0x9E3779B97F4A7C15L);
        int bucket = (int) Long.remainderUnsigned(hash, buckets), start = bucket * ways, end = Math.min(start + ways, capacity);
        int slot = -1, free = -1;
        for (int i = start; i < end; i++) {
            if ((flags[i] & OCCUPIED) == 0) { if (free < 0) free = i; }
            else if (keys[i] == key && details[i] == detail) { slot = i; break; }
        }
        long version = slot >= 0 && checkedAt[slot] == serial ? versions[slot] : version(x, z);
        if (slot >= 0 && versions[slot] == version) {
            checkedAt[slot] = serial; flags[slot] |= RECENT; hits++; return owners[slot];
        }
        var owner = snapshot.coveringTileAtDetail(x, z, desiredLod);
        if (slot < 0) {
            slot = free >= 0 ? free : victim(bucket, start, end);
            if ((flags[slot] & OCCUPIED) == 0) entries++; else evictions++;
        }
        keys[slot] = key; details[slot] = (byte) detail; versions[slot] = version; checkedAt[slot] = serial;
        owners[slot] = owner; flags[slot] = OCCUPIED | RECENT; misses++;
        return owner;
    }

    private void advance(PredictionTileManager.RenderSnapshot next) {
        if (source == null || !source.dimension().equals(next.dimension()) || !source.layout().equals(next.layout())) clear();
        serial++; publications++;
        if (source != null) next.forEachChangedTile(source, key -> {
            int lod = key.lod();
            if (lod >= 0 && lod < regionVersions.length && key.dimension().equals(next.dimension())) {
                regionVersions[lod][stampBucket(key.tileX(), key.tileZ())] = serial;
                invalidations++;
            }
        });
        source = next;
    }
    private long version(int x, int z) {
        long version = 0;
        for (int lod = 0; lod < source.layout().levelCount(); lod++) {
            int span = source.layout().tileBlocks(lod) / 16;
            version = Math.max(version, regionVersions[lod][stampBucket(Math.floorDiv(x, span), Math.floorDiv(z, span))]);
        }
        return version;
    }
    private int victim(int bucket, int start, int end) {
        int count = end - start, cursor = clocks[bucket];
        for (;;) {
            int slot = start + cursor; cursor = (cursor + 1) % count;
            if ((flags[slot] & RECENT) == 0) { clocks[bucket] = (byte) cursor; return slot; }
            flags[slot] &= ~RECENT;
        }
    }
    void clear() {
        Arrays.fill(flags, (byte) 0); Arrays.fill(clocks, (byte) 0); Arrays.fill(owners, null);
        for (var level : regionVersions) Arrays.fill(level, 0);
        source = null; entries = 0;
    }
    long hits() { return hits; }
    long misses() { return misses; }
    long evictions() { return evictions; }
    private static long pack(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
    private static int stampBucket(int x, int z) { return (int) HashCommon.mix(pack(x, z)) & (STAMP_BUCKETS - 1); }
    String diagnostics() { return "entries=" + entries + ",hits=" + hits + ",misses=" + misses
            + ",evictions=" + evictions + ",publications=" + publications + ",localInvalidations=" + invalidations; }
}
