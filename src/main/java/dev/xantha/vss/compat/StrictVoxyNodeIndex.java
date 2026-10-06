package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/** Render-thread mirror of uploaded Voxy node metadata, including explicit empty geometry. */
public final class StrictVoxyNodeIndex {
    private final Int2LongOpenHashMap nodes = new Int2LongOpenHashMap();
    private final Long2IntOpenHashMap ready = new Long2IntOpenHashMap();
    private final java.util.function.LongConsumer changed;
    private final Runnable reset;
    private long boxLookups;

    public StrictVoxyNodeIndex() { this(position -> { }, () -> { }); }
    public StrictVoxyNodeIndex(java.util.function.LongConsumer changed, Runnable reset) {
        this.changed = changed;
        this.reset = reset;
    }
    public long update(int id, long position, int geometry) {
        long removed = Long.MIN_VALUE;
        boolean newReady = position != -1L && (geometry & 0xFFFFFF) != 0xFFFFFF;
        // Explicit empty and nonempty geometry both complete a generation ring.
        // Neither establishes visibility for prediction; only frame depth can.
        if (newReady && nodes.containsKey(id) && nodes.get(id) == position) return Long.MIN_VALUE;
        if (nodes.containsKey(id)) {
            long old = nodes.remove(id);
            int count = ready.get(old) - 1;
            if (count <= 0) { ready.remove(old); removed = old; changed.accept(old); }
            else ready.put(old, count);
        }
        if (newReady) {
            nodes.put(id, position);
            if (ready.addTo(position, 1) == 0) changed.accept(position);
            if (position == removed) removed = Long.MIN_VALUE;
        }
        return removed;
    }

    public boolean covers(int cx, int sectionY, int cz) {
        for (int lod = 0; lod <= 4; lod++) {
            int shift = lod + 1; // Voxy sections contain 32 voxels, Minecraft sections 16.
            if (ready.containsKey(key(lod, cx >> shift, sectionY >> shift, cz >> shift))) return true;
        }
        return false;
    }

    /**
     * Returns true only when every Minecraft section in the requested vertical
     * span has an uploaded Voxy node or an uploaded ancestor node. A single
     * missing section keeps prediction active for the whole cell, avoiding a
     * hole at a node boundary.
     */
    public boolean coversRange(int cx, int minSectionY, int maxSectionY, int cz) {
        int first = Math.min(minSectionY, maxSectionY);
        int last = Math.max(minSectionY, maxSectionY);
        int sectionY = first;
        while (true) {
            int coveredThrough = sectionY;
            boolean covered = false;
            // A ready ancestor proves its complete vertical span at once.
            // Walking every section repeated the same five parent lookups.
            for (int lod = 4; lod >= 0; lod--) {
                int shift = lod + 1;
                if (!ready.containsKey(key(lod, cx >> shift, sectionY >> shift, cz >> shift))) continue;
                coveredThrough = sectionY | ((1 << shift) - 1);
                covered = true;
                break;
            }
            if (!covered) return false;
            if (coveredThrough >= last) return true;
            sectionY = coveredThrough + 1;
        }
    }

    /** Proves a whole box by descending only through ancestors that are not uploaded. */
    public boolean coversBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minZ > maxZ) return false;
        int firstY = Math.min(minY, maxY), lastY = Math.max(minY, maxY);
        if ((long) lastY - firstY + 1 >= 8192) {
            firstY &= ~31;
            lastY = firstY + 8191;
        }
        int firstNodeY = firstY >> 5;
        // Packed Y repeats after 256 coarsest nodes; checking another period adds no information.
        int yCount = (int) Math.min(256L, (long) (lastY >> 5) - firstNodeY + 1);
        for (int z = minZ >> 5; z <= maxZ >> 5; z++)
            for (int x = minX >> 5; x <= maxX >> 5; x++)
                for (int offset = 0; offset < yCount; offset++)
                    if (!coversNodeBox(4, x, firstNodeY + offset, z,
                            minX, firstY, minZ, maxX, lastY, maxZ)) return false;
        return true;
    }

    private boolean coversNodeBox(int lod, int x, int y, int z,
                                  int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boxLookups++;
        if (ready.containsKey(key(lod, x, y, z))) return true;
        if (lod == 0) return false;
        int shift = lod; // Child nodes span 1 << lod Minecraft chunks/sections.
        int childMinX = Math.max(x * 2, minX >> shift), childMaxX = Math.min(x * 2 + 1, maxX >> shift);
        int childMinY = Math.max(y * 2, minY >> shift), childMaxY = Math.min(y * 2 + 1, maxY >> shift);
        int childMinZ = Math.max(z * 2, minZ >> shift), childMaxZ = Math.min(z * 2 + 1, maxZ >> shift);
        for (int cz = childMinZ; cz <= childMaxZ; cz++)
            for (int cx = childMinX; cx <= childMaxX; cx++)
                for (int cy = childMinY; cy <= childMaxY; cy++)
                    if (!coversNodeBox(lod - 1, cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ)) return false;
        return true;
    }

    public long boxLookups() { return boxLookups; }

    /**
     * Checks only Voxy's finest node for this Minecraft section. Coarser ancestor
     * nodes remain valid fallback geometry, but cannot unlock a near-first ring.
     */
    public boolean coversFinest(int cx, int sectionY, int cz) {
        return ready.containsKey(key(0, cx >> 1, sectionY >> 1, cz >> 1));
    }

    public static long key(int lod, int x, int y, int z) {
        return (long) lod << 60 | (long) (y & 255) << 52
                | (long) (z & 0xFFFFFF) << 28 | (long) (x & 0xFFFFFF) << 4;
    }
    public static int level(long key) { return (int) (key >>> 60); }
    public static int x(long key) { return (int) (key << 36 >> 40); }
    public static int z(long key) { return (int) (key << 12 >> 40); }
    public void retractUncovered(dev.xantha.vss.networking.client.StrictLodFrontier frontier,
                                 it.unimi.dsi.fastutil.longs.LongSet removed,
                                 java.util.function.BiPredicate<Integer, Integer> columnReady) {
        var checked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long position : removed) {
            if (hasReplacement(position)) continue;
            int span = 2 << level(position);
            int minX = x(position) * span, minZ = z(position) * span;
            int radius = frontier.visibleRing();
            for (int cx = Math.max(minX, frontier.centerX() - radius); cx <= Math.min(minX + span - 1, frontier.centerX() + radius); cx++) {
                for (int cz = Math.max(minZ, frontier.centerZ() - radius); cz <= Math.min(minZ + span - 1, frontier.centerZ() + radius); cz++) {
                    if (frontier.visible(cx, cz) && checked.add(dev.xantha.vss.common.PositionUtil.packPosition(cx, cz))
                            && !columnReady.test(cx, cz)) frontier.missing(cx, cz);
                }
            }
        }
    }
    private boolean hasReplacement(long position) {
        int level = level(position);
        for (int lod = level; lod <= 4; lod++) {
            int shift = lod - level;
            if (ready.containsKey(key(lod, x(position) >> shift, ((byte)(position >>> 52)) >> shift, z(position) >> shift))) return true;
        }
        return false;
    }
    public void clear() { nodes.clear(); ready.clear(); reset.run(); }
}
