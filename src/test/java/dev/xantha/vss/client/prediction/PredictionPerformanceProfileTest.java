package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PredictionPerformanceProfileTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 24, 32, 64, 128})
    void presetsLeaveGameHeadroomAndKeepRefinementInsideThePool(int processors) {
        assertEquals(Math.min(processors, Math.max(2, processors / 4)),
                PredictionPerformanceProfile.LOW.poolSize(processors));
        assertEquals(Math.max(1, processors / 2),
                PredictionPerformanceProfile.MEDIUM.poolSize(processors));
        assertEquals(Math.max(1, processors - 2),
                PredictionPerformanceProfile.HIGH.poolSize(processors));
        for (var profile : new PredictionPerformanceProfile[]{PredictionPerformanceProfile.LOW,
                PredictionPerformanceProfile.MEDIUM, PredictionPerformanceProfile.HIGH}) {
            int pool = profile.poolSize(processors);
            assertTrue(pool >= 1);
            if (processors > 2) assertTrue(pool < processors, "leave logical capacity for the game");
            assertTrue(profile.refinementWorkers(processors) >= 1);
            assertTrue(profile.refinementWorkers(processors) <= pool);
        }
    }

    @org.junit.jupiter.api.Test
    void presetsIgnoreCustomValueAndCustomClampsOnAnotherMachine() {
        var config = new dev.xantha.vss.config.VSSClientConfig();
        config.predictionWorkerThreads = 64;
        config.performanceTier = "medium";
        assertEquals(8, PredictionPerformanceProfile.workers(config, 16));
        config.performanceTier = "custom";
        assertEquals(16, PredictionPerformanceProfile.workers(config, 16));
        assertEquals(1, PredictionPerformanceProfile.workers(config, 1));
        config.predictionWorkerThreads = 3;
        assertEquals(3, PredictionPerformanceProfile.workers(config, 16));
        config.predictionWorkerThreads = 0;
        assertEquals(8, PredictionPerformanceProfile.workers(config, 16));
        config.predictionWorkerThreads = -5;
        assertEquals(1, PredictionPerformanceProfile.workers(config, 1));
        config.performanceTier = "high";
        assertEquals(14, PredictionPerformanceProfile.workers(config, 16));
        assertEquals(PredictionPerformanceProfile.CUSTOM, PredictionPerformanceProfile.fromName("CUSTOM"));
    }
}
