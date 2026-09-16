package dev.xantha.vss.client.prediction;

import dev.xantha.vss.config.VSSClientConfig;

/**
 * Coarse resource presets for the prediction workers. The tiers only change
 * how many cores the prediction pool may occupy and when the frame-rate fuse
 * sheds background work; terrain distance, mesh detail and quality toggles
 * stay shared so a tier never produces a coarser world than a higher one.
 */
public enum PredictionPerformanceProfile {
    /** Quarter of the machine: prediction never competes with the render thread. */
    LOW {
        @Override
        public int poolSize(int logicalProcessors) {
            return Math.max(1, Math.min(4, logicalProcessors / 4));
        }

        @Override
        public int refinementWorkers(int logicalProcessors) {
            return Math.max(1, Math.min(3, poolSize(logicalProcessors)));
        }

        @Override
        public FrameFuse frameFuse() {
            return FrameFuse.PAUSE;
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

        @Override
        public FrameFuse frameFuse() {
            return FrameFuse.REDUCE;
        }
    },
    /** Historical 0.3 behavior: every logical core but two, no automatic fuse. */
    HIGH {
        @Override
        public int poolSize(int logicalProcessors) {
            return Math.max(1, logicalProcessors - 2);
        }

        @Override
        public int refinementWorkers(int logicalProcessors) {
            return Math.max(1, logicalProcessors / 2);
        }

        @Override
        public FrameFuse frameFuse() {
            return FrameFuse.NONE;
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

    /** Worker threads of the shared prediction executor. */
    public abstract int poolSize(int logicalProcessors);

    /** Concurrent ordinary-refinement slots when the config leaves them on auto. */
    public abstract int refinementWorkers(int logicalProcessors);

    public abstract FrameFuse frameFuse();

    /** What the frame-rate fuse does to ordinary/background work. */
    public enum FrameFuse {
        /** Never sheds work on slow frames. */
        NONE,
        /** Halves ordinary refinement and stops background work on slow frames. */
        REDUCE,
        /** Stops ordinary refinement and background work entirely on slow frames. */
        PAUSE
    }
}
