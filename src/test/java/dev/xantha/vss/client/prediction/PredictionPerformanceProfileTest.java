package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PredictionPerformanceProfileTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 24, 64})
    void presetsLeaveGameHeadroomAndKeepRefinementInsideThePool(int processors) {
        assertEquals(Math.max(1, Math.min(8, processors / 2)),
                PredictionPerformanceProfile.MEDIUM.poolSize(processors));
        assertEquals(Math.max(1, processors - 2),
                PredictionPerformanceProfile.HIGH.poolSize(processors));
        for (var profile : PredictionPerformanceProfile.values()) {
            int pool = profile.poolSize(processors);
            assertTrue(pool >= 1);
            if (processors > 1) assertTrue(pool < processors, "leave logical capacity for the game");
            assertTrue(profile.refinementWorkers(processors) >= 1);
            assertTrue(profile.refinementWorkers(processors) <= pool);
        }
    }
}
