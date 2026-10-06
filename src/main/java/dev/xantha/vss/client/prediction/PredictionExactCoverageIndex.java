package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.PositionUtil;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Sparse positive-only pages. Unknown/missing columns allocate no permanent objects. */
final class PredictionExactCoverageIndex {
    private static final int SHIFT = 4, AXIS = 1 << SHIFT;
    private final ConcurrentHashMap<ClientPredictionState.CellKey, Page> pages = new ConcurrentHashMap<>();
    private static final class Page {
        final long[] starts = new long[AXIS * AXIS];
        final long[] present = new long[AXIS * AXIS / 64];
        long latestStart;
        boolean has(int i) { return (present[i >>> 6] & (1L << i)) != 0; }
        boolean empty() { for (long bits : present) if (bits != 0) return false; return true; }
    }
    private static ClientPredictionState.CellKey key(ResourceKey<Level> dimension, int x, int z) {
        return new ClientPredictionState.CellKey(dimension, PositionUtil.packPosition(x >> SHIFT, z >> SHIFT));
    }
    private static int cell(int x, int z) { return (z & (AXIS - 1)) * AXIS + (x & (AXIS - 1)); }

    /** Preserve the first positive transition, including a settled storage discovery. */
    boolean confirm(ResourceKey<Level> dimension, int x, int z, long started) {
        boolean[] changed = {false};
        pages.compute(key(dimension, x, z), (ignored, page) -> {
            if (page == null) page = new Page();
            synchronized (page) {
                int i = cell(x, z);
                if (!page.has(i)) {
                    page.latestStart = page.empty() ? started : Math.max(page.latestStart, started);
                    page.starts[i] = started;
                    page.present[i >>> 6] |= 1L << i;
                    changed[0] = true;
                }
            }
            return page;
        });
        return changed[0];
    }
    boolean remove(ResourceKey<Level> dimension, int x, int z) {
        if (pages.isEmpty()) return false;
        var pageKey = key(dimension, x, z);
        Page observed = pages.get(pageKey);
        if (observed == null) return false;
        synchronized (observed) {
            // Linearize a miss at this read; a later confirmation belongs to
            // the next sweep. Keep positive removals in computeIfPresent so
            // deleting the last bit cannot race another cell's insertion.
            if (!observed.has(cell(x, z))) return false;
        }
        boolean[] changed = {false};
        pages.computeIfPresent(pageKey, (ignored, page) -> {
            synchronized (page) {
                int i = cell(x, z); changed[0] = page.has(i);
                page.present[i >>> 6] &= ~(1L << i);
                return page.empty() ? null : page;
            }
        });
        return changed[0];
    }
    boolean owns(ResourceKey<Level> dimension, int x, int z, long now) {
        Page page = pages.get(key(dimension, x, z));
        if (page == null) return false;
        synchronized (page) { int i = cell(x, z); return page.has(i) && now - page.starts[i] >= ExactCoverageGate.SETTLE_NANOS; }
    }
    boolean contains(ResourceKey<Level> dimension, int x, int z) {
        Page page = pages.get(key(dimension, x, z));
        if (page == null) return false;
        synchronized (page) { return page.has(cell(x, z)); }
    }
    /** Complete rectangles only: a missing or unsettled chunk preserves the tile. */
    boolean ownsBox(ResourceKey<Level> dimension, int minX, int minZ, int maxX, int maxZ, long now) {
        if (minX > maxX || minZ > maxZ) return false;
        for (int pz = minZ >> SHIFT; pz <= maxZ >> SHIFT; pz++) {
            for (int px = minX >> SHIFT; px <= maxX >> SHIFT; px++) {
                Page page = pages.get(key(dimension, px << SHIFT, pz << SHIFT));
                if (page == null) return false;
                synchronized (page) {
                    int left = Math.max(minX, px << SHIFT), right = Math.min(maxX, (px << SHIFT) + AXIS - 1);
                    boolean settledPage = now - page.latestStart >= ExactCoverageGate.SETTLE_NANOS;
                    for (int z = Math.max(minZ, pz << SHIFT); z <= Math.min(maxZ, (pz << SHIFT) + AXIS - 1); z++) {
                        int first = cell(left, z);
                        long row = ((1L << (right - left + 1)) - 1) << (first & 63);
                        if ((page.present[first >>> 6] & row) != row) return false;
                        if (!settledPage) for (int x = left; x <= right; x++)
                            if (now - page.starts[cell(x, z)] < ExactCoverageGate.SETTLE_NANOS) return false;
                    }
                }
            }
        }
        return true;
    }
    PredictionExactCoverageMask.Snapshot snapshot(ResourceKey<Level> dimension, int x, int z, int radius, long now) {
        var mask = PredictionExactCoverageMask.Snapshot.around(x, z, radius);
        int lastX = mask.originX() + mask.size() - 1, lastZ = mask.originZ() + mask.size() - 1;
        // Lookup only overlapping pages; cost is independent of explored world size.
        for (int pz = mask.originZ() >> SHIFT; pz <= lastZ >> SHIFT; pz++) {
            for (int px = mask.originX() >> SHIFT; px <= lastX >> SHIFT; px++) {
                Page page = pages.get(key(dimension, px << SHIFT, pz << SHIFT));
                if (page == null) continue;
                synchronized (page) {
                    for (int word = 0; word < page.present.length; word++) {
                        long bits = page.present[word];
                        while (bits != 0) {
                            int i = word * 64 + Long.numberOfTrailingZeros(bits);
                            if (now - page.starts[i] >= ExactCoverageGate.SETTLE_NANOS)
                                mask.mark((px << SHIFT) + i % AXIS, (pz << SHIFT) + i / AXIS);
                            bits &= bits - 1;
                        }
                    }
                }
            }
        }
        mask.preserveBoundaryFallback();
        return mask;
    }
    boolean retain(ResourceKey<Level> dimension, int x, int z, int radius) {
        int minX = (x - radius) >> SHIFT, maxX = (x + radius) >> SHIFT;
        int minZ = (z - radius) >> SHIFT, maxZ = (z + radius) >> SHIFT;
        return pages.keySet().removeIf(k -> !dimension.equals(k.dimension())
                || PositionUtil.unpackX(k.packed()) < minX || PositionUtil.unpackX(k.packed()) > maxX
                || PositionUtil.unpackZ(k.packed()) < minZ || PositionUtil.unpackZ(k.packed()) > maxZ);
    }
    int pageCount() { return pages.size(); }
    void clear() { pages.clear(); }
}
