package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionPerformanceProfile.FrameFuse;

/**
 * Rolling frame-time monitor backing the tier fuse. One render-frame record
 * per frame; the admission gate in {@link PredictionTileManager#enqueue}
 * consults {@link #currentThrottle()} so slow frames shed ordinary refinement
 * and background work while scoped, dirty and medium-coverage builds continue.
 */
public final class PredictionFramePace {
    /** Average frame window; ~1.5 s at 60 fps before a verdict is trusted. */
    private static final int SAMPLES = 90;
    /** Frame gaps above this are hitches/resumes, not a sustainable rate. */
    private static final long GAP_NANOS = 2_500_000_000L;
    /** Minimum settled samples before a verdict is trusted. */
    private static final int MIN_SAMPLES = 40;

    private static final long[] gapsNanos = new long[SAMPLES];
    private static int cursor;
    private static int filled;
    private static long lastFrameNanos;
    private static long totalNanos;
    // Render thread owns the ring. Workers read only published scalar values.
    private static volatile double publishedFps = -1.0D;
    private static volatile long publishedGap, publishedAt;

    public enum ThrottleLevel {
        NONE,
        /** Halve ordinary refinement and stop background work. */
        REDUCE,
        /** Stop ordinary refinement and background work entirely. */
        PAUSE
    }

    private PredictionFramePace() {
    }

    /** Called once per render frame; cheap and stateless across worlds. */
    public static void recordFrame() {
        recordFrame(System.nanoTime());
    }

    static void recordFrame(long now) {
        long last = lastFrameNanos;
        lastFrameNanos = now;
        if (last == 0L) {
            return;
        }
        long gap = now - last;
        if (gap <= 0L || gap > GAP_NANOS) {
            cursor = 0; filled = 0; totalNanos = 0;
            publishedFps = -1.0D; publishedGap = gap; publishedAt = now;
            return;
        }
        if (filled == SAMPLES) totalNanos -= gapsNanos[cursor];
        gapsNanos[cursor] = gap;
        totalNanos += gap;
        cursor = (cursor + 1) % SAMPLES;
        if (filled < SAMPLES) filled++;
        publishedFps = filled < MIN_SAMPLES ? -1.0D : 1.0E9 * filled / totalNanos;
        publishedGap = gap;
        publishedAt = now;
    }

    /** Settled average frame rate, or a negative value while warming up. */
    public static double averageFps() {
        return publishedFps;
    }

    static boolean allowsExtraWork() { return allowsExtraWork(System.nanoTime()); }

    static boolean allowsExtraWork(long now) {
        long age = now - publishedAt;
        return age >= 0 && age < 250_000_000L && publishedFps >= 60.0D
                && publishedGap > 0 && publishedGap < 33_333_334L;
    }

    static String diagnostics() {
        return String.format(java.util.Locale.ROOT, "fps=%.1f,lastMs=%.1f,extra=%s,throttle=%s",
                publishedFps, publishedGap / 1_000_000.0D, allowsExtraWork(), currentThrottle());
    }

    public static ThrottleLevel currentThrottle() {
        FrameFuse fuse = PredictionPerformanceProfile.current().frameFuse();
        if (fuse == FrameFuse.NONE) {
            return ThrottleLevel.NONE;
        }
        double fps = averageFps();
        if (fps <= 0.0D) {
            return ThrottleLevel.NONE;
        }
        return switch (fuse) {
            case PAUSE -> fps < 45.0D ? ThrottleLevel.PAUSE : ThrottleLevel.NONE;
            case REDUCE -> fps < 30.0D ? ThrottleLevel.REDUCE : ThrottleLevel.NONE;
            default -> ThrottleLevel.NONE;
        };
    }

    static void resetForTesting() {
        java.util.Arrays.fill(gapsNanos, 0L);
        cursor = 0;
        filled = 0;
        lastFrameNanos = 0L;
        totalNanos = 0L;
        publishedFps = -1.0D; publishedGap = 0; publishedAt = 0;
    }
}
