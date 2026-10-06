package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/** Counts distinct pending columns in aligned ancestors so an idle region needs one lookup. */
final class StrictVoxyPendingColumns {
    private final Long2IntOpenHashMap[] levels = new Long2IntOpenHashMap[6];
    StrictVoxyPendingColumns() { for (int lod = 0; lod < levels.length; lod++) levels[lod] = new Long2IntOpenHashMap(); }
    synchronized void changed(int x, int z, int delta) {
        for (int lod = 0; lod < levels.length; lod++) {
            long key = pack(x >> lod, z >> lod);
            int count = levels[lod].addTo(key, delta) + delta;
            if (count == 0) levels[lod].remove(key);
        }
    }
    synchronized boolean idleBox(int minX, int minZ, int maxX, int maxZ) {
        if (levels[5].isEmpty()) return true;
        for (int z = minZ >> 5; z <= maxZ >> 5; z++)
            for (int x = minX >> 5; x <= maxX >> 5; x++)
                if (!idleNode(5, x, z, minX, minZ, maxX, maxZ)) return false;
        return true;
    }
    private boolean idleNode(int lod, int x, int z, int minX, int minZ, int maxX, int maxZ) {
        if (!levels[lod].containsKey(pack(x, z))) return true;
        if (lod == 0) return false;
        int shift = lod - 1;
        int firstX = Math.max(x * 2, minX >> shift), lastX = Math.min(x * 2 + 1, maxX >> shift);
        int firstZ = Math.max(z * 2, minZ >> shift), lastZ = Math.min(z * 2 + 1, maxZ >> shift);
        for (long cz = firstZ; cz <= lastZ; cz++) for (long cx = firstX; cx <= lastX; cx++)
            if (!idleNode(lod - 1, (int) cx, (int) cz, minX, minZ, maxX, maxZ)) return false;
        return true;
    }
    synchronized void clear() { for (var level : levels) level.clear(); }
    private static long pack(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
}
