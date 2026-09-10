package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;

/** One debug line per initialization stage group, never per terrain tile. */
final class PredictionInitializationTiming {
    private final boolean enabled = VSSClientConfig.CONFIG.debugLogging;
    private final String name;
    private final StringBuilder phases = new StringBuilder();
    private final long started = System.nanoTime();
    private long last = started;

    PredictionInitializationTiming(String name) { this.name = name; }

    void mark(String phase) {
        if (!enabled) return;
        long now = System.nanoTime();
        phases.append(", ").append(phase).append("Ms=").append((now - last) / 1_000_000L);
        last = now;
    }

    void finish() {
        if (enabled) VSSLogger.debug("VSS prediction init " + name + phases
                + ", totalMs=" + (System.nanoTime() - started) / 1_000_000L);
    }
}
