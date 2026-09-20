package dev.xantha.vss.client.prediction;

/** Fog parameters shared by the predicted terrain pass and its water pass. */
public record VssLodFog(boolean haze, float start, float end,
                             float aerialDensity, boolean overridesVanilla) {
    /** Display fog follows both renderers, never the padded metadata scan radius. */
    static VssLodFog shared(int predictionDistance, int realDistance, int vanillaDistance, boolean haze) {
        var fog = of(Math.max(predictionDistance, realDistance), haze, .55, .5, true);
        // Even an unusually large vanilla range must retain a gradual fade.
        float clearDistance = Math.min(Math.max(0, vanillaDistance), fog.end() * .9F);
        return new VssLodFog(haze, Math.max(fog.start(), clearDistance), fog.end(),
                fog.aerialDensity(), true);
    }

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
