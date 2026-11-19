package dev.xantha.vss.client.prediction;

import dev.xantha.vss.config.VSSClientConfig;
import java.util.List;

/** One horizontal placement window; terrain and persisted world identities are independent of it. */
record PredictionDecorationRange(boolean enabled, int radius, int centerX, int centerZ) {
    private static final ThreadLocal<PredictionDecorationRange> ACTIVE = new ThreadLocal<>();
    static PredictionDecorationRange current() { return ACTIVE.get(); }

    static PredictionDecorationRange at(double x, double z) {
        var config = VSSClientConfig.CONFIG;
        int distance = config.predictionDecorationDistanceChunks == 0 ? config.predictionDistanceBlocks
                : config.predictionDecorationDistanceChunks * 16;
        return new PredictionDecorationRange(config.predictionDecorations, Math.min(distance, config.predictionDistanceBlocks),
                config.predictionDecorations ? Math.floorDiv((int)Math.floor(x), 16) * 16 + 8 : 0,
                config.predictionDecorations ? Math.floorDiv((int)Math.floor(z), 16) * 16 + 8 : 0);
    }

    boolean allows(int x, int z, int span) { return intersects(x, z, span, 0); }
    boolean covers(PredictionDecorationRange other) {
        return enabled && (!other.enabled || Math.hypot((double)centerX - other.centerX, (double)centerZ - other.centerZ)
                + other.radius <= radius);
    }
    boolean contains(int x, int z, int span) {
        double dx = Math.max(Math.abs((double)x - centerX), Math.abs((double)x + span - centerX));
        double dz = Math.max(Math.abs((double)z - centerZ), Math.abs((double)z + span - centerZ));
        return enabled && dx * dx + dz * dz <= (double)radius * radius;
    }
    private boolean intersects(int x, int z, int span, int margin) {
        double dx = Math.max(0, Math.max((double)x - centerX, (double)centerX - x - span));
        double dz = Math.max(0, Math.max((double)z - centerZ, (double)centerZ - z - span));
        double reach = (double)radius + margin;
        return enabled && dx * dx + dz * dz <= reach * reach;
    }

    /** A one-chunk halo retains features rooted just beyond the boundary, such as tree canopies. */
    static boolean placementChunk(int chunkX, int chunkZ) {
        var range = ACTIVE.get();
        return range == null || range.intersects(chunkX * 16, chunkZ * 16, 16, 16);
    }
    static boolean placementPoint(int x, int z) {
        var range = ACTIVE.get();
        return range == null || range.intersects(x, z, 1, 16);
    }
    static boolean partial(PredictionVoxySource.Tile tile) {
        var range = ACTIVE.get();
        return range != null && !range.contains(tile.baseX(), tile.baseZ(), tile.span());
    }

    Scope enter() {
        var previous = ACTIVE.get();
        ACTIVE.set(this);
        return new Scope(previous);
    }
    static final class Scope implements AutoCloseable {
        private final PredictionDecorationRange previous;
        Scope(PredictionDecorationRange previous) { this.previous = previous; }
        @Override public void close() { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }

    /** Project only the display layer. Keep original snapshots intact for warm reactivation. */
    PredictionVoxySource.Tile display(PredictionVoxySource.Tile tile) {
        if (contains(tile.baseX(), tile.baseZ(), tile.span())) return tile;
        var boxes = new PredictionVoxyBoxes.Builder();
        int cities = 0, templates = 0;
        if (enabled && allows(tile.baseX(), tile.baseZ(), tile.span())) {
            for (int i = 0; i < tile.features().size(); i++) {
                var box = tile.features().get(i);
                double dx = Math.max(0, Math.max((double)tile.baseX() + box.minX() - centerX,
                        (double)centerX - tile.baseX() - box.maxX()));
                double dz = Math.max(0, Math.max((double)tile.baseZ() + box.minZ() - centerZ,
                        (double)centerZ - tile.baseZ() - box.maxZ()));
                if (dx * dx + dz * dz > (double)radius * radius) continue;
                boxes.add(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), box.state());
                if (i < tile.cityFeatures()) cities++;
                else if (tile.template(i)) templates++;
            }
        }
        var features = boxes.size() == 0 ? List.<PredictionVoxySource.Box>of() : boxes.build();
        boolean outside = !allows(tile.baseX(), tile.baseZ(), tile.span());
        return new PredictionVoxySource.Tile(tile.key(), tile.baseX(), tile.baseZ(), tile.span(), tile.axis(), tile.step(),
                tile.columns(), features, tile.terrainBytes(), outside || tile.surfaceComplete(), cities,
                null, !outside && tile.approximateDecorations(), tile.decorationStep(), templates);
    }
}
