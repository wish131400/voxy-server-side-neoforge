package dev.xantha.vss.client.prediction;

/** Reduce paused-world redraws without hiding terrain or changing saved game options. */
public final class PredictionPauseLimit {
    private PredictionPauseLimit() { }

    public static int apply(int current, int configured, boolean prediction, boolean world, boolean paused) {
        if (!prediction || !world || !paused || configured <= 0) return current;
        return current > 0 ? Math.min(current, configured) : configured;
    }
}
