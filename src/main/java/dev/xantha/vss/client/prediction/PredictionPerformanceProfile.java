package dev.xantha.vss.client.prediction;

import dev.xantha.vss.config.VSSClientConfig;

/**
 * Coarse resource presets for the prediction workers. The tiers only change
 * how many worker threads the prediction pool may occupy.
 * All tiers share a bounded concurrency budget with local
 * generation; terrain distance, mesh detail and quality toggles
 * stay shared so a tier never produces a coarser world than a higher one.
 */
public enum PredictionPerformanceProfile {
    /** Quarter of the logical processors, capped at four workers. */
    LOW {
        @Override
        public int poolSize(int logicalProcessors) {
            return Math.max(1, Math.min(4, logicalProcessors / 4));
        }

        @Override
        public int refinementWorkers(int logicalProcessors) {
            return Math.max(1, Math.min(3, poolSize(logicalProcessors)));
        }
    },
    /** Half of the machine by default: fast coverage while the game stays playable. */
    MEDIUM {
        @Override
        public int poolSize(int logicalProcessors) {
            return Math.max(1, Math.min(8, logicalProcessors / 2));
        }

        @Override
        public int refinementWorkers(int logicalProcessors) {
            return Math.max(1, Math.min(6, poolSize(logicalProcessors)));
        }
    },
    /** Every logical core but two; shared concurrency admission still applies. */
    HIGH {
        @Override
        public int poolSize(int logicalProcessors) {
            return Math.max(1, logicalProcessors - 2);
        }

        @Override
        public int refinementWorkers(int logicalProcessors) {
            return Math.max(1, logicalProcessors / 2);
        }
    };

    /** Name persisted in vss-client-config.json. */
    public static final String DEFAULT_NAME = "medium";

    /** Lowercase JSON/GUI persistence name matching the enum constant. */
    public String configName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static PredictionPerformanceProfile current() {
        return fromName(VSSClientConfig.CONFIG.performanceTier);
    }

    public static PredictionPerformanceProfile fromName(String name) {
        if (name != null) {
            switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "low" -> { return LOW; }
                case "high" -> { return HIGH; }
                case "medium" -> { return MEDIUM; }
            }
        }
        return MEDIUM;
    }

    /** Maximum worker threads per dimension and base admission across dimensions. */
    public abstract int poolSize(int logicalProcessors);

    /** Concurrent ordinary-refinement slots when the config leaves them on auto. */
    public abstract int refinementWorkers(int logicalProcessors);
}
