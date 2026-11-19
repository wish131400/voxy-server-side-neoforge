package dev.xantha.vss.client.prediction;

/** Explicit read-only generator ownership for isolated feature and model worlds. */
public interface PredictionFeatureContext {
    default ClientTerrainSampler predictionContext() { return null; }
}
