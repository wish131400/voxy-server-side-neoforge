package dev.xantha.vss.networking.client;

import dev.xantha.vss.client.prediction.PredictionLoadingProgress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionGenerationPriorityTest {
    private static final long S = 1_000_000_000L;
    private static final PredictionLoadingProgress LOADING = progress(20, 20);
    private static final PredictionLoadingProgress READY = progress(95, 95);
    private static PredictionLoadingProgress progress(int coverage, int near) {
        return new PredictionLoadingProgress(100, coverage, 100, near);
    }

    @Test void startupAndMissingProfileReserveGenerationSlots() {
        var gate = new PredictionGenerationPriority();
        assertEquals(0, gate.limit(16, true, PredictionLoadingProgress.INITIALIZING, 0));
        assertEquals(0, gate.limit(16, true, LOADING, 10*S));
        assertEquals("prediction", gate.diagnostics());
    }

    @Test void bothCoverageAndNearGroundMustBeMostlyReady() {
        var gate = new PredictionGenerationPriority();
        assertEquals(0, gate.limit(16, true, progress(100, 94), 0));
        assertEquals(0, gate.limit(16, true, progress(94, 100), 2*S));
        assertEquals(0, gate.limit(16, true, READY, 3*S));
        assertEquals(4, gate.limit(16, true, READY, 4*S));
        assertEquals(8, gate.limit(16, true, READY, 5*S));
        assertEquals(12, gate.limit(16, true, READY, 6*S));
        assertEquals(16, gate.limit(16, true, READY, 7*S));
        assertEquals("normal", gate.diagnostics());
    }

    @Test void disabledPredictionImmediatelyRestoresConfiguredLimitAndReenableStartsFresh() {
        var gate = new PredictionGenerationPriority();
        assertEquals(0, gate.limit(16, true, LOADING, 0));
        assertEquals(16, gate.limit(16, false, LOADING, S));
        assertEquals("disabled", gate.diagnostics());
        assertEquals(0, gate.limit(16, true, LOADING, 2*S));
        assertEquals(0, gate.limit(0, true, READY, 3*S));
        assertEquals("disabled", gate.diagnostics());
    }

    @Test void smallOrBriefCoverageRegressionDoesNotStopGeneration() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, READY, 0);
        assertEquals(4, gate.limit(16, true, READY, S));
        assertEquals(16, gate.limit(16, true, READY, 4*S));
        assertEquals(16, gate.limit(16, true, progress(90, 90), 5*S));
        assertEquals(16, gate.limit(16, true, LOADING, 6*S));
        assertEquals(16, gate.limit(16, true, READY, 7*S));
        assertEquals(16, gate.limit(16, true, LOADING, 8*S));
        assertEquals(0, gate.limit(16, true, LOADING, 10*S));
    }

    @Test void stallFailsOpenAndDoesNotReacquireEveryScan() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, LOADING, 0);
        assertEquals(0, gate.limit(16, true, LOADING, 44*S));
        assertEquals(4, gate.limit(16, true, LOADING, 45*S));
        assertEquals(16, gate.limit(16, true, LOADING, 48*S));
        assertEquals("stalled", gate.diagnostics());
        assertEquals(16, gate.limit(16, true, LOADING, 500*S));
        assertEquals(16, gate.limit(16, true, READY, 501*S));
        assertEquals("normal", gate.diagnostics());
    }

    @Test void progressExtendsStallWindowButCannotHoldGenerationForever() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, LOADING, 0);
        assertEquals(0, gate.limit(16, true, progress(30, 30), 40*S));
        assertEquals(0, gate.limit(16, true, progress(40, 40), 80*S));
        assertEquals(4, gate.limit(16, true, progress(50, 50), 120*S));
    }

    @Test void missingProfileTimesOutAndResetStartsNewDimension() {
        var gate = new PredictionGenerationPriority();
        gate.limit(8, true, PredictionLoadingProgress.INITIALIZING, 0);
        assertEquals(2, gate.limit(8, true, PredictionLoadingProgress.INITIALIZING, 45*S));
        assertEquals(8, gate.limit(8, true, PredictionLoadingProgress.INITIALIZING, 48*S));
        gate.reset();
        assertEquals(0, gate.limit(8, true, LOADING, 49*S));
    }

    @Test void completedCacheAndSmallConfiguredLimitResumeWithoutOvershoot() {
        var gate = new PredictionGenerationPriority();
        assertEquals(0, gate.limit(1, true, READY, 0));
        assertEquals(1, gate.limit(1, true, READY, S));
        assertEquals(1, gate.limit(1, true, READY, 4*S));
        assertTrue(new PredictionLoadingProgress(10, 10, 0, 0).ready(95));
        assertFalse(PredictionLoadingProgress.INITIALIZING.ready(95));
    }

    @Test void generationHoldKeepsDirtyRefreshAndCachedColumnCapacity() {
        var gate = new PredictionGenerationPriority();
        int limit = gate.limit(16, true, LOADING, 0);
        var window = new RequestWindow(8, 4, 2, 1, Math.max(0, limit - 6), 3, 2);
        assertFalse(window.canSend(false, true, false, 1));
        assertTrue(window.canSend(true, false, false, 1));
        assertTrue(window.canSend(false, false, false, 1));
        assertTrue(window.canSend(false, false, true, 1));
        assertTrue(window.hasCapacity());
        // Already in-flight generation must be subtracted from the new cap as it drains.
        gate.limit(16, true, READY, S);
        limit = gate.limit(16, true, READY, 2*S);
        assertEquals(4, limit);
        assertEquals(0, Math.max(0, limit - 6));
        assertEquals(2, Math.max(0, limit - 2));
    }
}
