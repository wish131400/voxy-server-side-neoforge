package dev.xantha.vss.client.prediction;

/**
 * VSS-owned equivalent of the LodLayout. The values are expressed in
 * blocks so the planner, renderer and cache use one coordinate convention.
 */
public record VssLodLayout(int maxDistanceBlocks, int levelCount,
                                double pixelThreshold, boolean trees,
                                boolean storedDetail) {
    public static final int TILE_QUADS = 64;
    public static final int SAMPLE_MARGIN = 1;
    public static final int BASE_TILE_BLOCKS = 64;
    public static final int MIN_DISTANCE_BLOCKS = 1024;
    public static final int MAX_DISTANCE_BLOCKS = 65536;
    public static final int WORLD_DISTANCE_BLOCKS = 0x2000000;
    private static final int MAX_LEVELS = 20;

    public VssLodLayout {
        if (maxDistanceBlocks < MIN_DISTANCE_BLOCKS
                || maxDistanceBlocks > WORLD_DISTANCE_BLOCKS) {
            throw new IllegalArgumentException("maxDistanceBlocks outside the range");
        }
        if (levelCount < 1 || levelCount > MAX_LEVELS) {
            throw new IllegalArgumentException("levelCount outside the range");
        }
        if (!(pixelThreshold > 0.0D) || !Double.isFinite(pixelThreshold)) {
            throw new IllegalArgumentException("pixelThreshold must be finite and positive");
        }
    }

    public static VssLodLayout of(int maxDistanceBlocks, double pixelsPerQuad,
                                       boolean trees, boolean storedDetail) {
        int distance = Math.max(MIN_DISTANCE_BLOCKS,
                Math.min(WORLD_DISTANCE_BLOCKS, maxDistanceBlocks));
        int levels = 1;
        while (levels < MAX_LEVELS
                && (long) BASE_TILE_BLOCKS << levels - 1 < distance) {
            levels++;
        }
        return new VssLodLayout(distance, levels,
                Math.max(0.25D, pixelsPerQuad * TILE_QUADS), trees, storedDetail);
    }

    public int spacing(int level) {
        checkLevel(level);
        return 1 << level;
    }

    /**
     * Returns the number of terrain cells in one prediction tile at this
     * level.  the samples the full 64x64 grid on every level: the
     * spacing grows with distance but the sample count does not, which is
     * what keeps distant mountains blocked instead of melting into large
     * smooth triangles.  VSS keeps the same fixed budget.
     */
    public int cellAxis(int level) {
        checkLevel(level);
        return TILE_QUADS;
    }

    /** Returns the world-space distance between adjacent samples. */
    public int sampleSpacing(int level) {
        return tileBlocks(level) / cellAxis(level);
    }

    /** Returns the sample grid including the one-sample border. */
    public int sampleGridSize(int level) {
        return cellAxis(level) + SAMPLE_MARGIN * 2;
    }

    public int tileBlocks(int level) {
        checkLevel(level);
        return BASE_TILE_BLOCKS << level;
    }

    public int samplesPerAxis() {
        return TILE_QUADS + SAMPLE_MARGIN * 2;
    }

    public double storedPixelThreshold() {
        return Math.min(pixelThreshold, 64.0D);
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= levelCount) {
            throw new IllegalArgumentException("LOD level outside layout: " + level);
        }
    }
}
