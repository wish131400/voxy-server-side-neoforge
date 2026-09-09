package dev.xantha.vss.client.prediction;

/** Deterministic lighting approximation for predicted columns. */
final class PredictionLighting {
    /** the packs four 4-bit corner light values into one vertex integer. */
    static final int LIGHT_MASK = 0xF;
    static final int CORNER_BITS = 4;

    private PredictionLighting() {
    }

    static int shade(int argb, int y, int seaLevel, boolean cave, boolean fluid) {
        float sky = y >= seaLevel ? 1.0F : Math.max(0.45F, 0.72F + (y - seaLevel) / 256.0F);
        float ao = cave ? 0.78F : 1.0F;
        float water = fluid ? 0.92F : 1.0F;
        float factor = Math.max(0.35F, Math.min(1.15F, sky * ao * water));
        int r = Math.min(255, Math.max(0, Math.round(((argb >> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, Math.round(((argb >> 8) & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, Math.round((argb & 0xFF) * factor)));
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /**
     * the corner ambient-occlusion count: how many of the two edge
     * neighbours and the diagonal neighbour rise above the surface this
     * corner belongs to.
     */
    static int occluders(boolean sideA, boolean sideB, boolean corner) {
        if (sideA && sideB) {
            return 3;
        }
        return (sideA ? 1 : 0) + (sideB ? 1 : 0) + (corner ? 1 : 0);
    }

    /**
     * the vertex light combination: ((15 - 3 * occluders) * ground + 7) / 15.
     * Reproduced exactly so shaded corners match the reference renderer.
     */
    static int combine(int occluders, int ground) {
        return ((15 - 3 * occluders) * ground + 7) / 15;
    }

    /** Packs four corner lights into one integer, the LodQuadWriter style. */
    static int packLight(int c0, int c1, int c2, int c3) {
        return (c0 & LIGHT_MASK)
                | (c1 & LIGHT_MASK) << CORNER_BITS
                | (c2 & LIGHT_MASK) << CORNER_BITS * 2
                | (c3 & LIGHT_MASK) << CORNER_BITS * 3;
    }
}
