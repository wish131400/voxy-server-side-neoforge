package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/** Render-thread mirror of uploaded Voxy node metadata, including explicit empty geometry. */
public final class StrictVoxyNodeIndex {
    private final Int2LongOpenHashMap nodes = new Int2LongOpenHashMap();
    private final Long2IntOpenHashMap ready = new Long2IntOpenHashMap();

    public long update(int id, long position, int geometry) {
        long removed = Long.MIN_VALUE;
        if (nodes.containsKey(id)) {
            long old = nodes.remove(id);
            int count = ready.get(old) - 1;
            if (count <= 0) { ready.remove(old); removed = old; }
            else ready.put(old, count);
        }
        if (position != -1L && (geometry & 0xFFFFFF) != 0xFFFFFF) {
            nodes.put(id, position);
            ready.addTo(position, 1);
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
    public void clear() { nodes.clear(); ready.clear(); }
}
