package dev.xantha.vss.client.prediction;

/** Soft per-frame upload bounds; an oversized first tile must still make progress. */
final class PredictionUploadBudget {
    static final int MAX_TILES = 2;
    static final long MAX_BYTES = 4L * 1024 * 1024;
    static final long MAX_NANOS = 2_000_000L;
    private int tiles;
    private long bytes;
    private long nanos;
    private long requiredBytes, requiredNanos, reservedBytes, reservedNanos;

    // Masks and seams publish atomically with ownership. Reserve their measured
    // cost against the NEXT frame's optional mesh upgrades instead of tearing a handoff.
    void recordRequired(long bytes, long nanos) {
        requiredBytes += bytes; requiredNanos += nanos;
    }

    void reset() {
        reservedBytes = requiredBytes; reservedNanos = requiredNanos;
        requiredBytes = 0; requiredNanos = 0;
        tiles = 0; bytes = 0; nanos = 0;
    }

    void clear() { requiredBytes = 0; requiredNanos = 0; reset(); }

    boolean allows(long nextBytes) {
        return tiles == 0 || tiles < MAX_TILES && bytes + nextBytes + reservedBytes <= MAX_BYTES && nanos + reservedNanos < MAX_NANOS;
    }

    void record(long uploadedBytes, long elapsedNanos) {
        tiles++;
        bytes += uploadedBytes;
        nanos += elapsedNanos;
    }

    String diagnostics() { return "uploads=" + tiles + ",uploadBytes=" + bytes + ",uploadUs=" + nanos / 1000
            + ",handoffBytes=" + requiredBytes + ",handoffUs=" + requiredNanos / 1000
            + ",reservedHandoffUs=" + reservedNanos / 1000; }
}
