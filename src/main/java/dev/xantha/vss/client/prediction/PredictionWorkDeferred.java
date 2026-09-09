package dev.xantha.vss.client.prediction;

/** Yield a worker while shared chunk data or a native workspace is being built. */
final class PredictionWorkDeferred extends RuntimeException {
    PredictionWorkDeferred() { super(null, null, false, false); }
}
