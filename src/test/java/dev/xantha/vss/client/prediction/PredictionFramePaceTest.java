package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PredictionFramePaceTest {
    @AfterEach void reset() { PredictionFramePace.resetForTesting(); }

    @Test void extraWorkersRequireFreshStableFramesAndStopAfterAHitchOrResume() {
        PredictionFramePace.resetForTesting();
        long now=1_000_000_000L;
        assertFalse(PredictionFramePace.allowsExtraWork(now));
        for(int i=0;i<=40;i++) PredictionFramePace.recordFrame(now+i*10_000_000L);
        now+=400_000_000L;
        assertEquals(100.0,PredictionFramePace.averageFps(),0.01);
        assertTrue(PredictionFramePace.allowsExtraWork(now));
        assertFalse(PredictionFramePace.allowsExtraWork(now+300_000_000L));
        now+=100_000_000L; PredictionFramePace.recordFrame(now);
        assertFalse(PredictionFramePace.allowsExtraWork(now),"recent hitch must veto an otherwise fast mean");
        now+=3_000_000_000L; PredictionFramePace.recordFrame(now);
        assertEquals(-1.0,PredictionFramePace.averageFps());
        assertFalse(PredictionFramePace.allowsExtraWork(now),"long loading pause invalidates old fast frames");
        for(int i=1;i<=100;i++) PredictionFramePace.recordFrame(now+i*25_000_000L);
        now+=2_500_000_000L;
        assertEquals(40.0,PredictionFramePace.averageFps(),0.01);
        assertFalse(PredictionFramePace.allowsExtraWork(now));
    }
}
