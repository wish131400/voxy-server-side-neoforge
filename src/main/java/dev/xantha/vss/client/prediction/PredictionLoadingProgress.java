package dev.xantha.vss.client.prediction;

/** Published once per terrain plan; optional decoration and idle refinement are excluded. */
public record PredictionLoadingProgress(int coverageTotal, int coverageReady, int nearTotal, int nearReady) {
    public static final PredictionLoadingProgress INITIALIZING = new PredictionLoadingProgress(0, 0, 0, 0);

    public boolean ready(int percent) {
        return coverageTotal > 0 && (long) coverageReady * 100 >= (long) coverageTotal * percent
                && (nearTotal == 0 || (long) nearReady * 100 >= (long) nearTotal * percent);
    }

    public int score() {
        if (coverageTotal <= 0) return 0;
        return (int) ((long) coverageReady * 10000 / coverageTotal)
                + (nearTotal == 0 ? 10000 : (int) ((long) nearReady * 10000 / nearTotal));
    }
}
