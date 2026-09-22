package dev.xantha.vss.client.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionPauseLimitTest {
    @Test void capPreservesStricterLimitsAndImmediatelyReleasesWhenResumed() {
        assertEquals(30, PredictionPauseLimit.apply(260, 30, true, true, true));
        assertEquals(15, PredictionPauseLimit.apply(15, 30, true, true, true));
        assertEquals(30, PredictionPauseLimit.apply(0, 30, true, true, true));
        assertEquals(260, PredictionPauseLimit.apply(260, 30, true, true, false));
    }
    @Test void disabledPredictionNoWorldAndUnpausedMultiplayerKeepTheirLimit() {
        assertEquals(144, PredictionPauseLimit.apply(144, 30, false, true, true));
        assertEquals(144, PredictionPauseLimit.apply(144, 30, true, false, true));
        assertEquals(144, PredictionPauseLimit.apply(144, 0, true, true, true));
        assertEquals(144, PredictionPauseLimit.apply(144, 30, true, true, false));
    }
}
