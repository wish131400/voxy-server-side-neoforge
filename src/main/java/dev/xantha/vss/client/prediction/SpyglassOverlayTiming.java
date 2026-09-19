package dev.xantha.vss.client.prediction;

/** A slow frame must finish the scope animation, never extrapolate its black border across the view. */
public final class SpyglassOverlayTiming {
    public static float interpolation(float factor) {
        return Float.isFinite(factor) ? Math.max(0.0F, Math.min(1.0F, factor)) : 1.0F;
    }

    private SpyglassOverlayTiming() { }
}
