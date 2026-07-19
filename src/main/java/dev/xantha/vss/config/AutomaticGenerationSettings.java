package dev.xantha.vss.config;

final class AutomaticGenerationSettings {
    static final int TIMEOUT_SECONDS = 300;

    private AutomaticGenerationSettings() {
    }

    static int packingThreads(int availableProcessors, int globalConcurrency) {
        return Math.min(globalConcurrency, Math.max(1, availableProcessors / 2));
    }

    static int startsPerTick(int availableProcessors, int globalConcurrency) {
        return Math.min(globalConcurrency, Math.max(1, availableProcessors));
    }

    static int completionsPerTick(int availableProcessors, int globalConcurrency) {
        return Math.min(globalConcurrency, Math.max(2, packingThreads(availableProcessors, globalConcurrency) * 2));
    }

    static int packingQueueLimit(int availableProcessors, int globalConcurrency) {
        return Math.min(globalConcurrency, Math.max(32, packingThreads(availableProcessors, globalConcurrency) * 16));
    }
}
