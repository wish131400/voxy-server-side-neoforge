package dev.xantha.vss.client.prediction;

/**
 * Published once per prediction plan. Basic coverage, the near preview, actual
 * near terrain targets and requested important surfaces have separate counts.
 * Optional idle refinement is excluded. An empty completed plan is distinct
 * from a plan that has not yet been initialized.
 */
public record PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady,
                                        int targetTotal, int targetReady, int surfaceTotal, int surfaceReady,
                                        boolean planned) {
    public static final PredictionLoadingProgress INITIALIZING =
            new PredictionLoadingProgress(0, 0, 0, 0, 0, 0, 0, 0, false);
    public static final PredictionLoadingProgress EMPTY =
            new PredictionLoadingProgress(0, 0, 0, 0, 0, 0, 0, 0, true);

    public PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady,
                                     int targetTotal, int targetReady, int surfaceTotal, int surfaceReady) {
        this(coverageTotal, coverageReady, nearTotal, nearReady,
                targetTotal, targetReady, surfaceTotal, surfaceReady, true);
    }

    /** Legacy callers have no additional surface debt and use near readiness as their target. */
    public PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady) {
        this(coverageTotal, coverageReady, nearTotal, nearReady, nearTotal, nearReady, 0, 0, coverageTotal > 0);
    }

    public boolean ready(int percent) {
        return planned && enough(coverageTotal, coverageReady, percent)
                && enough(nearTotal, nearReady, percent) && enough(targetTotal, targetReady, percent)
                && enough(surfaceTotal, surfaceReady, percent);
    }

    public int score() {
        if (!planned) return 0;
        return ratio(coverageTotal, coverageReady) + ratio(nearTotal, nearReady)
                + ratio(targetTotal, targetReady) + ratio(surfaceTotal, surfaceReady);
    }

    public String waitingFor(int percent) {
        if (!planned) return "initializing";
        if (!enough(coverageTotal, coverageReady, percent)) return "coverage";
        if (!enough(nearTotal, nearReady, percent)) return "near-ground";
        if (!enough(targetTotal, targetReady, percent)) return "near-target";
        if (!enough(surfaceTotal, surfaceReady, percent)) return "surface";
        return "complete";
    }

    private static boolean enough(int total, int ready, int percent) {
        return total == 0 || total > 0 && (long) Math.max(0, ready) * 100 >= (long) total * percent;
    }

    private static int ratio(int total, int ready) {
        return total <= 0 ? 10000 : (int) ((long) Math.min(total, Math.max(0, ready)) * 10000 / total);
    }
}
