package dev.xantha.vss.client.prediction;

/** Soft per-frame upload bounds; an oversized first tile must still make progress. */
final class PredictionUploadBudget {
    static final int MAX_TILES = 2;
    static final long MAX_BYTES = 4L * 1024 * 1024;
    static final long MAX_NANOS = 2_000_000L;
    private int tiles;
    private long bytes;
    private long nanos;

    void reset() { tiles = 0; bytes = 0; nanos = 0; }

    boolean allows(long nextBytes) {
        return tiles == 0 || tiles < MAX_TILES && bytes + nextBytes <= MAX_BYTES && nanos < MAX_NANOS;
    }

    void record(long uploadedBytes, long elapsedNanos) {
        tiles++;
        bytes += uploadedBytes;
        nanos += elapsedNanos;
    }

    String diagnostics() { return "uploads=" + tiles + ",uploadBytes=" + bytes + ",uploadUs=" + nanos / 1000; }
}
