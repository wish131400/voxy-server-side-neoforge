package dev.xantha.vss.networking.client;

import dev.xantha.vss.client.prediction.PredictionLoadingProgress;

/** Per-player generation admission only. Existing transfers and dirty refreshes keep running. */
final class PredictionGenerationPriority {
    private static final long SECOND = 1_000_000_000L;
    private enum Phase { DISABLED, PREDICTION, RAMP, NORMAL, STALLED }
    private Phase phase = Phase.DISABLED;
    private long holdSince, progressSince, readySince = -1, regressionSince = -1, rampSince;
    private int bestScore;
    private boolean stalled;

    int limit(int configured, boolean enabled, PredictionLoadingProgress progress, long now) {
        if (!enabled || configured <= 0) {
            reset();
            return Math.max(0, configured);
        }
        if (phase == Phase.DISABLED) hold(progress, now);
        if (phase == Phase.PREDICTION) {
            int score = progress.score();
            if (score > bestScore) { bestScore = score; progressSince = now; }
            if (progress.ready(95)) {
                if (readySince < 0) readySince = now;
            } else readySince = -1;
            boolean settled = readySince >= 0 && now - readySince >= SECOND;
            boolean stuck = now - progressSince >= 45 * SECOND || now - holdSince >= 120 * SECOND;
            if (settled || stuck) {
                stalled = !settled;
                phase = Phase.RAMP;
                rampSince = now;
            } else return 0;
        }
        if (phase == Phase.RAMP || phase == Phase.NORMAL) {
            // A few late tiles or tiny camera movements must not repeatedly stop generation.
            if (!stalled && !progress.ready(85)) {
                if (regressionSince < 0) regressionSince = now;
                if (now - regressionSince >= 2 * SECOND) {
                    hold(progress, now);
                    return 0;
                }
            } else regressionSince = -1;
        }
        if (phase == Phase.RAMP) {
            int quarters = (int) Math.min(4, 1 + Math.max(0, now - rampSince) / SECOND);
            if (quarters < 4) return Math.max(1, (int) (((long) configured * quarters + 3) / 4));
            phase = stalled ? Phase.STALLED : Phase.NORMAL;
        }
        // A failed/memory-blocked prediction cannot repeatedly reacquire the same exclusive turn.
        if (phase == Phase.STALLED && progress.ready(95)) { phase = Phase.NORMAL; stalled = false; }
        return configured;
    }

    String diagnostics() { return phase.name().toLowerCase(java.util.Locale.ROOT); }

    void reset() {
        phase = Phase.DISABLED;
        readySince = regressionSince = -1;
        stalled = false;
    }

    private void hold(PredictionLoadingProgress progress, long now) {
        phase = Phase.PREDICTION;
        holdSince = progressSince = now;
        bestScore = progress.score();
        readySince = regressionSince = -1;
        stalled = false;
    }
}
