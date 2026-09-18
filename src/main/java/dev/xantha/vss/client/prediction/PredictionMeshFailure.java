package dev.xantha.vss.client.prediction;

/** A geometry limit is deterministic for the same inputs, unlike a transient heap shortage. */
record PredictionMeshFailure(long revision, long captureEpoch, int targetAxis, int surfaceSettings) {
    boolean matches(long nextRevision, long nextCapture, int nextAxis, int nextSettings) {
        return revision == nextRevision && captureEpoch == nextCapture
                && targetAxis == nextAxis && surfaceSettings == nextSettings;
    }
}
