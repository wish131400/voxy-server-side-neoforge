package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Camera-independent surface eligibility and bounded, near-first work bands. */
final class PredictionWorkOrder {
    static final int SCOPED_RADIUS_BLOCKS = 64 * 16;
    static final int INITIAL_CELL_AXIS = 16;
    static final int PREVIEW_CELL_AXIS = 32;
    private static final int WORK_BAND_BLOCKS = 64;
    static final int BACKGROUND_PRIORITY = 1_000_000;
    private PredictionWorkOrder() { }

    static int refinementWorkers(int processors, int configured) {
        int half = Math.max(1, processors / 2);
        return configured <= 0 ? half : Math.min(half, Math.max(1, Math.min(32, configured)));
    }

    static int localRefinementPriority(double distanceSquared, int residentAxis, boolean surface) {
        int band = Math.min(65535, (int) (Math.sqrt(distanceSquared) / WORK_BAND_BLOCKS));
        return 100_000 + band * 3 + (surface ? 2 : residentAxis < PREVIEW_CELL_AXIS ? 0 : 1);
    }

    static int initialCellAxis(int lod) {
        // Large ancestors only establish coverage. Keep the denser first
        // preview for local tiles without charging it to the entire horizon.
        return lod < 2 ? INITIAL_CELL_AXIS : 8;
    }

    static double distanceSquared(PredictionTileKey key, VssLodLayout layout, double x, double z) {
        double span = layout.tileBlocks(key.lod());
        double dx = Math.max(0, Math.max(key.tileX() * span - x, x - (key.tileX() + 1) * span));
        double dz = Math.max(0, Math.max(key.tileZ() * span - z, z - (key.tileZ() + 1) * span));
        return dx * dx + dz * dz;
    }

    static int surfaceRadius(double x, double z, int vanillaRadius, int extension, int horizon,
                             java.util.function.BiPredicate<Integer, Integer> authoritative) {
        int frontier = Math.max(0, vanillaRadius);
        // Follow observed coverage, not Voxy's configured maximum: an empty
        // 8 km cache must not move the useful decoration band out to 8 km.
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dz == 0) continue;
            double length = Math.hypot(dx, dz);
            int distance = Math.max(0, vanillaRadius);
            for (; distance < horizon; distance += 128) {
                int cx = Math.floorDiv((int) Math.floor(x + dx / length * distance), 16);
                int cz = Math.floorDiv((int) Math.floor(z + dz / length * distance), 16);
                if (!authoritative.test(cx, cz)) break;
            }
            frontier = Math.max(frontier, distance);
        }
        return Math.min(horizon, frontier + extension);
    }

    static VssLodFocus surfaceFocus(VssLodFocus focus) {
        // Expensive one-block decoration is a bounded patch in the telescope.
        // Its terrain refinement may cover the wider projected field of view.
        return focus == null ? null : new VssLodFocus(focus.x(), focus.z(), SCOPED_RADIUS_BLOCKS, focus.pixelsPerBlock());
    }

    static boolean scoped(PredictionTileKey key, VssLodLayout layout, VssLodFocus focus) {
        VssLodFocus patch = surfaceFocus(focus);
        int span = layout.tileBlocks(key.lod());
        return patch != null && patch.intersects(key.tileX() * (double) span, key.tileZ() * (double) span,
                (key.tileX() + 1D) * span, (key.tileZ() + 1D) * span);
    }

    static boolean surfaceEligible(PredictionTileKey key, VssLodLayout layout,
                                   double x, double z, int radius, VssLodFocus focus) {
        // Keep actual one-block plants; never rescale a blade of grass into a tree-sized voxel.
        if (layout.sampleSpacing(key.lod()) > 2) return false;
        if (distanceSquared(key, layout, x, z) <= (double) radius * radius) return true;
        int span = layout.tileBlocks(key.lod());
        focus = surfaceFocus(focus);
        return focus != null && focus.intersects(key.tileX() * (double) span, key.tileZ() * (double) span,
                (key.tileX() + 1D) * span, (key.tileZ() + 1D) * span);
    }

    static int priority(PredictionTileKey key, VssLodLayout layout, double distanceSquared,
                        int residentAxis, boolean surface) {
        return priority(key, layout, distanceSquared, residentAxis, surface, null);
    }

    static int priority(PredictionTileKey key, VssLodLayout layout, double distanceSquared,
                        int residentAxis, boolean surface, VssLodFocus focus) {
        // Medium terrain precedes final terrain and plants at every distance.
        int band = Math.min(65535, (int) (Math.sqrt(distanceSquared) / WORK_BAND_BLOCKS));
        VssLodFocus patch = surfaceFocus(focus);
        int span = layout.tileBlocks(key.lod());
        if (patch != null && patch.intersects(key.tileX() * (double) span, key.tileZ() * (double) span,
                (key.tileX() + 1D) * span, (key.tileZ() + 1D) * span)) {
            int focusBand = (int) (Math.sqrt(distanceSquared(key, layout, patch.x(), patch.z())) / WORK_BAND_BLOCKS);
            return 1000 + focusBand * 3 + (surface ? 2 : residentAxis < 32 ? 0 : 1);
        }
        int stage = surface ? 2 : residentAxis < PREVIEW_CELL_AXIS ? 0 : 1;
        return 100_000 + stage * 100_000 + band;
    }

    static double orderingDistance(PredictionTileKey key, VssLodLayout layout,
                                   double x, double z, VssLodFocus focus) {
        double nearby = distanceSquared(key, layout, x, z);
        VssLodFocus patch = surfaceFocus(focus);
        if (nearby >= 256D * 256 && patch != null
                && distanceSquared(key, layout, patch.x(), patch.z()) <= patch.radius() * patch.radius()) {
            return distanceSquared(key, layout, patch.x(), patch.z());
        }
        return nearby;
    }
}
