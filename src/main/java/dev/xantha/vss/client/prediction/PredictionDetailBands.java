package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Radial terrain quality; vegetation eligibility is managed independently. */
final class PredictionDetailBands {
    private PredictionDetailBands() { }

    static int cellAxis(double distance, int horizon) {
        return distance < fineRadius(horizon) ? 64 : 32;
    }

    static int fineRadius(int horizon) {
        return Math.min(horizon, Math.max(256, Math.min(4096,
                dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionFineDistanceBlocks)));
    }

    static int fineRadius(int horizon, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        int normal = fineRadius(horizon);
        // End islands begin beyond a wide void. Expand detail, not the horizon or worker pool.
        return dimension.equals(net.minecraft.world.level.Level.END)
                ? Math.min(horizon, Math.min(4096, normal + normal / 2)) : normal;
    }

    static int cellAxis(PredictionTileKey key, VssLodLayout layout, double x, double y, double z,
                        VssLodFocus focus, double pixelsPerBlock, double minY, double maxY) {
        if (PredictionWorkOrder.scoped(key, layout, focus)) return 64;
        double vertical = Math.max(0, Math.max(minY - y, y - maxY));
        int span = layout.tileBlocks(key.lod());
        double horizontal = Math.hypot((key.tileX() + .5) * span - x, (key.tileZ() + .5) * span - z);
        if (Math.hypot(horizontal, vertical) < fineRadius(layout.maxDistanceBlocks(), key.dimension())) return 64;
        double distance = Math.sqrt(PredictionWorkOrder.distanceSquared(key, layout, x, z) + vertical * vertical);
        double projected = VssLodProjection.projectedSize(span, Math.max(1, distance), pixelsPerBlock);
        return projectedCellAxis(projected, layout.pixelThreshold() / VssLodLayout.TILE_QUADS);
    }

    static int projectedCellAxis(double projected, double pixelsPerCell) {
        // Choose the smallest grid that meets the actual cell error. A 32-cell
        // grid cannot borrow the screen budget of a 64-cell grid.
        if (projected <= 16 * pixelsPerCell) return 16;
        if (projected <= 32 * pixelsPerCell) return 32;
        return 64;
    }

    static int cellAxis(PredictionTileKey key, VssLodLayout layout, double x, double z, VssLodFocus focus) {
        int span = layout.tileBlocks(key.lod());
        VssLodFocus patch = PredictionWorkOrder.surfaceFocus(focus);
        if (patch != null && patch.intersects(key.tileX() * (double) span, key.tileZ() * (double) span,
                (key.tileX() + 1D) * span, (key.tileZ() + 1D) * span)) return 64;
        // Assign boundary tiles by their centres. Using the closest corner
        // would let a large middle-band tile swallow the entire outer ring.
        double distance = Math.hypot((key.tileX() + .5) * span - x, (key.tileZ() + .5) * span - z);
        return distance < fineRadius(layout.maxDistanceBlocks(), key.dimension()) ? 64 : 32;
    }

    static boolean needsBandSplit(double minDistance, double maxDistance, int span, int horizon) {
        if (span <= VssLodLayout.BASE_TILE_BLOCKS) return false;
        // Match the quadtree's power-of-two scale: a distance just below a
        // power of two must not accidentally quadruple the skeleton budget.
        int horizonSpan = Integer.highestOneBit(horizon - 1) << 1;
        if (span > Math.max(VssLodLayout.BASE_TILE_BLOCKS, horizonSpan / 8)) return true;
        return span > Math.max(VssLodLayout.BASE_TILE_BLOCKS, horizonSpan / 16)
                && crosses(minDistance, maxDistance, fineRadius(horizon));
    }

    private static boolean crosses(double min, double max, double boundary) {
        return min < boundary && max > boundary;
    }
}
