package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Camera-independent surface eligibility and bounded, near-first work bands. */
final class PredictionWorkOrder {
    // At most 18 x 18 placement chunks including the halo: below the shared
    // 512-chunk cache. Larger terrain tiles subdivide before replaying plants.
    static final int MAX_SURFACE_TILE_BLOCKS = 256;
    static final int SCOPED_RADIUS_BLOCKS = 64 * 16;
    static final int SCOPED_SURFACE_RADIUS_BLOCKS = 16 * 16;
    static final int INITIAL_CELL_AXIS = 16;
    static final int PREVIEW_CELL_AXIS = 32;
    private static final int WORK_BAND_BLOCKS = 64;
    static final int BACKGROUND_PRIORITY = 1_000_000;
    private PredictionWorkOrder() { }

    static int refinementWorkers(int processors, int configured) {
        int half = Math.max(1, processors / 2);
        if (configured > 0) {
            return Math.min(half, Math.max(1, Math.min(32, configured)));
        }
        return PredictionPerformanceProfile.current().refinementWorkers(processors);
    }

    static int localRefinementPriority(double distanceSquared, int residentAxis, boolean surface) {
        return nearPriority(distanceSquared, residentAxis, surface, 0);
    }

    /** Distance bands dominate stage and screen error; LOD is only a final tie breaker. */
    static int nearPriority(double distanceSquared, int residentAxis, boolean surface, int screenPriority) {
        int band = Math.min(16383, (int) (Math.sqrt(distanceSquared) / WORK_BAND_BLOCKS));
        int stage = residentAxis == 0 ? 0 : surface ? 1 : residentAxis < PREVIEW_CELL_AXIS ? 2 : 3;
        return 10_000 + band * 16 + stage * 4 + Math.min(3, Math.max(0, screenPriority / 1500));
    }

    /** Fill the useful local region before spending its turns on detail or trees.
     * Distant horizon work keeps distance ordering instead of imposing a global
     * coverage barrier. View bands, focus and fair turns remain caller-owned. */
    static int coveragePriority(double distanceSquared, int residentAxis, boolean surface, int screenPriority) {
        int priority = nearPriority(distanceSquared, residentAxis, surface, screenPriority);
        return residentAxis == 0 && !surface && distanceSquared <= 2048.0 * 2048
                ? priority - 5_000 : priority;
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
        return focus == null ? null : new VssLodFocus(focus.x(), focus.z(),
                Math.min(focus.radius(), SCOPED_SURFACE_RADIUS_BLOCKS), focus.pixelsPerBlock());
    }

    static VssLodFocus terrainFocus(VssLodFocus focus) {
        return focus == null || focus.radius() <= SCOPED_RADIUS_BLOCKS ? focus
                : new VssLodFocus(focus.x(), focus.z(), SCOPED_RADIUS_BLOCKS, focus.pixelsPerBlock());
    }

    static int detailBuildLimit(int workers, boolean previews, boolean scoping) {
        int limit = previews ? Math.max(1, workers - 1) : workers;
        return scoping ? Math.min(limit, Math.max(1, Math.min(3, workers / 2))) : limit;
    }

    static int surfaceBuildLimit(int workers, int refinementWorkers) {
        return Math.max(1, Math.min(workers, refinementWorkers));
    }

    static boolean scoped(PredictionTileKey key, VssLodLayout layout, VssLodFocus focus) {
        VssLodFocus patch = terrainFocus(focus);
        if (patch == null) return false;
        int span = layout.tileBlocks(key.lod());
        return patch.intersects(key.tileX() * (double) span, key.tileZ() * (double) span,
                (key.tileX() + 1D) * span, (key.tileZ() + 1D) * span);
    }

    static boolean surfaceEligible(PredictionTileKey key, VssLodLayout layout,
                                   double x, double z, int radius, VssLodFocus focus) {
        // Plant voxels stay at spacing 1 independently of the resident terrain grid.
        if (!surfaceTile(key, layout)) return false;
        if (distanceSquared(key, layout, x, z) <= (double) radius * radius) return true;
        int span = layout.tileBlocks(key.lod());
        focus = surfaceFocus(focus);
        return focus != null && focus.intersects(key.tileX() * (double) span, key.tileZ() * (double) span,
                (key.tileX() + 1D) * span, (key.tileZ() + 1D) * span);
    }

    static boolean surfaceTile(PredictionTileKey key, VssLodLayout layout) {
        return layout.tileBlocks(key.lod()) <= MAX_SURFACE_TILE_BLOCKS;
    }

    /** Ordinary planning and native Voxy requests share the same plant demand.
     * A rendered view replaces the startup radius with visible feature size. */
    static boolean surfaceEligible(PredictionTileKey key, VssLodLayout layout, PredictionWorkView view,
                                   VssLodFocus focus, double x, double y, double z, int radius,
                                   double pixels, double minY, double maxY) {
        if (!layout.trees() || !surfaceTile(key, layout)) return false;
        if (view == null || scoped(key, layout, focus))
            return surfaceEligible(key, layout, x, z, radius, focus);
        return PredictionScreenDetail.surfaceVisible(view, key, layout, x, y, z, pixels, minY, maxY);
    }

    static int priority(PredictionTileKey key, VssLodLayout layout, double distanceSquared,
                        int residentAxis, boolean surface) {
        return priority(key, layout, distanceSquared, residentAxis, surface, null);
    }

    static int priority(PredictionTileKey key, VssLodLayout layout, double distanceSquared,
                        int residentAxis, boolean surface, VssLodFocus focus) {
        VssLodFocus patch = terrainFocus(focus);
        int span = layout.tileBlocks(key.lod());
        if (patch != null && patch.intersects(key.tileX() * (double) span, key.tileZ() * (double) span,
                (key.tileX() + 1D) * span, (key.tileZ() + 1D) * span)) {
            int focusBand = (int) (Math.sqrt(distanceSquared(key, layout, patch.x(), patch.z())) / WORK_BAND_BLOCKS);
            return 1000 + focusBand * 3 + (surface ? 2 : residentAxis < 32 ? 0 : 1);
        }
        return localRefinementPriority(distanceSquared, residentAxis, surface);
    }

    static double orderingDistance(PredictionTileKey key, VssLodLayout layout,
                                   double x, double z, VssLodFocus focus) {
        double nearby = distanceSquared(key, layout, x, z);
        VssLodFocus patch = terrainFocus(focus);
        if (nearby >= 256D * 256 && patch != null
                && distanceSquared(key, layout, patch.x(), patch.z()) <= patch.radius() * patch.radius()) {
            return distanceSquared(key, layout, patch.x(), patch.z());
        }
        return nearby;
    }
}
