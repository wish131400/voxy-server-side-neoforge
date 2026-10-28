package dev.xantha.vss.client.prediction;

import java.util.Map;

/** Interval-only bounded histogram, independent of menu frames and existing frame-pace consumers. */
final class PredictionPerformanceFrames {
    private final long[] millis = new long[2002];
    private long count, total, max, over33, over50;
    void record(long nanos) {
        if (nanos <= 0) return;
        count++; total += nanos; max = Math.max(max, nanos);
        if (nanos > 33_333_333L) over33++;
        if (nanos > 50_000_000L) over50++;
        millis[(int) Math.min(2001, (nanos + 999_999) / 1_000_000)]++;
    }
    private long percentile(double fraction) {
        long wanted = (long) Math.ceil(count * fraction), seen = 0;
        if (count == 0) return 0;
        for (int i = 0; i < millis.length; i++) if ((seen += millis[i]) >= wanted) return i;
        return 2001;
    }
    Map<String, Object> snapshot() {
        return Map.of("frameCount", count, "frameDurationNanos", total, "fps", total > 0 ? count * 1e9 / total : 0,
                "frameMeanMs", count > 0 ? total / (count * 1e6) : 0, "frameP50Ms", percentile(.50),
                "frameP95Ms", percentile(.95), "frameP99Ms", percentile(.99), "frameMaxMs", max / 1e6,
                "framesOver33ms", over33, "framesOver50ms", over50);
    }
    void clear() { java.util.Arrays.fill(millis, 0); count = total = max = over33 = over50 = 0; }
}
