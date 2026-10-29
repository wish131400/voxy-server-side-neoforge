package dev.xantha.vss.client.prediction;

/** Rolling frame-time diagnostics. Frame caps do not affect work admission. */
public final class PredictionFramePace {
    /** Average frame window; ~1.5 s at 60 fps before publishing a settled average. */
    private static final int SAMPLES = 90;
    /** Frame gaps above this are hitches/resumes, not a sustainable rate. */
    private static final long GAP_NANOS = 2_500_000_000L;
    /** Minimum samples before publishing the average. */
    private static final int MIN_SAMPLES = 40;

    private static final long[] gapsNanos = new long[SAMPLES];
    private static int cursor;
    private static int filled;
    private static long lastFrameNanos;
    private static long totalNanos;
    private static final long[] allGapsMillis = new long[502];
    private static long observedFrames, observedNanos, longFrames33, longFrames50;
    // Render thread owns the ring. Workers read only published scalar values.
    private static volatile double publishedFps = -1.0D;
    private static volatile long publishedGap;

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
        if (gap > 0) {
            observedFrames++; observedNanos += gap;
            if (gap > 33_333_333L) longFrames33++;
            if (gap > 50_000_000L) longFrames50++;
            allGapsMillis[(int)Math.min(501,(gap+999_999L)/1_000_000L)]++;
        }
        if (gap <= 0L || gap > GAP_NANOS) {
            cursor = 0; filled = 0; totalNanos = 0;
            publishedFps = -1.0D; publishedGap = gap;
            return;
        }
        if (filled == SAMPLES) totalNanos -= gapsNanos[cursor];
        gapsNanos[cursor] = gap;
        totalNanos += gap;
        cursor = (cursor + 1) % SAMPLES;
        if (filled < SAMPLES) filled++;
        publishedFps = filled < MIN_SAMPLES ? -1.0D : 1.0E9 * filled / totalNanos;
        publishedGap = gap;
    }

    /** Settled average frame rate, or a negative value while warming up. */
    public static double averageFps() {
        return publishedFps;
    }

    static String diagnostics() {
        return String.format(java.util.Locale.ROOT, "fps=%.1f,lastMs=%.1f,mode=diagnostic-only",
                publishedFps, publishedGap / 1_000_000.0D);
    }

    /** Render-thread only: last 90 gaps, not a session percentile. */
    record Window(int samples, long durationNanos, long p50Nanos, long p95Nanos,
                  long p99Nanos, long maxNanos, int over33ms, int over50ms) { }
    static Window snapshot() {
        int samples = filled;
        if (samples == 0) return new Window(0,0,0,0,0,0,0,0);
        long[] sorted = java.util.Arrays.copyOf(gapsNanos, samples);
        java.util.Arrays.sort(sorted);
        int over33 = 0, over50 = 0;
        for (long gap : sorted) { if (gap > 33_333_333L) over33++; if (gap > 50_000_000L) over50++; }
        return new Window(samples,totalNanos,percentile(sorted,.5),percentile(sorted,.95),
                percentile(sorted,.99),sorted[samples-1],over33,over50);
    }
    private static long percentile(long[] sorted, double fraction) {
        return sorted[Math.max(0,(int)Math.ceil(sorted.length*fraction)-1)];
    }
    /** Render-thread only; arrays are copied before background serialization. */
    static java.util.Map<String,Object> counters() {
        return java.util.Map.of("frames",observedFrames,"durationNanos",observedNanos,
                "over33ms",longFrames33,"over50ms",longFrames50,"histogramMillis",allGapsMillis.clone());
    }

    static void resetForTesting() {
        java.util.Arrays.fill(gapsNanos, 0L);
        cursor = 0;
        filled = 0;
        lastFrameNanos = 0L;
        totalNanos = 0L;
        publishedFps = -1.0D; publishedGap = 0;
        observedFrames = observedNanos = longFrames33 = longFrames50 = 0;
        java.util.Arrays.fill(allGapsMillis,0L);
    }
}
