package dev.xantha.vss.client.prediction;

/** Fog parameters shared by the predicted terrain pass and its water pass. */
public record VssLodFog(boolean haze, float start, float end,
                             float aerialDensity, boolean overridesVanilla) {
    public static VssLodFog of(int maxDistanceBlocks, boolean haze,
                                    double fogStart, double aerialPerspective,
                                    boolean overridesVanilla) {
        float end = Math.max(1.0F, maxDistanceBlocks);
        float start = (float) (end * Math.max(0.0D, Math.min(0.99D, fogStart)));
        double aerial = Math.max(0.0D, Math.min(0.95D, aerialPerspective));
        float density = aerial == 0.0D
                ? 0.0F
                : (float) (-Math.log(1.0D - aerial) / end);
        return new VssLodFog(haze, start, end, density, overridesVanilla);
    }

    public float shaderStart() {
        return haze ? start : 10_000_000.0F;
    }

    public float shaderEnd() {
        return haze ? end : 10_000_001.0F;
    }
}
