package dev.xantha.vss.config;

/** Shared production defaults; config migration preserves later user choices. */
final class PredictionConfigDefaults {
    static final String VERSION = "v0.2.19";
    static final boolean FAR_DECORATIONS = true;
    static final int DISTANCE_BLOCKS = 8_192;
    static final int EXACT_DISTANCE_BLOCKS = 1_024;
    private PredictionConfigDefaults() { }
}
