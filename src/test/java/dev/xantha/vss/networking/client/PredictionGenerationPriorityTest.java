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
        assertTrue(gate.diagnostics().startsWith("prediction("));
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
        assertTrue(gate.diagnostics().startsWith("normal("));
    }

    @Test void disabledPredictionImmediatelyRestoresConfiguredLimitAndReenableStartsFresh() {
        var gate = new PredictionGenerationPriority();
        assertEquals(0, gate.limit(16, true, LOADING, 0));
        assertEquals(16, gate.limit(16, false, LOADING, S));
        assertTrue(gate.diagnostics().startsWith("disabled("));
        assertEquals(0, gate.limit(16, true, LOADING, 2*S));
        assertEquals(0, gate.limit(0, true, READY, 3*S));
        assertTrue(gate.diagnostics().startsWith("disabled("));
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

    @Test void stallOnlyAdmitsSingleKeepaliveAndRequiresCooldown() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, LOADING, 0);
        assertEquals(0, gate.limit(16, true, LOADING, 44*S));
        assertEquals(1, gate.limit(16, true, LOADING, 45*S));
        assertTrue(gate.diagnostics().contains("reason=stall-keepalive"));
        assertEquals(1, gate.limit(16, true, LOADING, 48*S));
        assertTrue(gate.diagnostics().contains("reason=stall-keepalive"));
        assertEquals(0, gate.limit(16, true, LOADING, 50*S));
        assertEquals(0, gate.limit(16, true, LOADING, 64*S));
        assertEquals(1, gate.limit(16, true, LOADING, 65*S));
        assertEquals(0, gate.limit(16, true, LOADING, 70*S));
        assertEquals(1, gate.limit(16, true, LOADING, 500*S));
        assertEquals(1, gate.limit(16, true, READY, 501*S));
        assertEquals(4, gate.limit(16, true, READY, 502*S));
        assertEquals(16, gate.limit(16, true, READY, 505*S));
        assertTrue(gate.diagnostics().startsWith("normal("));
    }

    @Test void progressExtendsStallWindowButCannotHoldGenerationForever() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, LOADING, 0);
        assertEquals(0, gate.limit(16, true, progress(30, 30), 40*S));
        assertEquals(0, gate.limit(16, true, progress(40, 40), 80*S));
        assertEquals(1, gate.limit(16, true, progress(50, 50), 120*S));
        assertTrue(gate.diagnostics().contains("reason=wait-keepalive"));
        assertEquals(0, gate.limit(16, true, progress(60, 60), 125*S));
        assertEquals(0, gate.limit(16, true, progress(70, 70), 139*S));
        assertEquals(1, gate.limit(16, true, progress(80, 80), 140*S));
    }

    @Test void missingProfileTimesOutAndResetStartsNewDimension() {
        var gate = new PredictionGenerationPriority();
        gate.limit(8, true, PredictionLoadingProgress.INITIALIZING, 0);
        assertEquals(1, gate.limit(8, true, PredictionLoadingProgress.INITIALIZING, 45*S));
        assertEquals(1, gate.limit(8, true, PredictionLoadingProgress.INITIALIZING, 48*S));
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

    @Test void completeBaseCoverageDoesNotAdmitGenerationBeforeActualDetailAndSurfaces() {
        var gate = new PredictionGenerationPriority();
        var report = new PredictionLoadingProgress(234, 234, 192, 192, 192, 60, 100, 20);
        assertEquals(0, gate.limit(16, true, report, 0));
        assertTrue(gate.diagnostics().contains("reason=near-target"));
        var groundReady = new PredictionLoadingProgress(234, 234, 192, 192, 192, 192, 100, 94);
        assertEquals(0, gate.limit(16, true, groundReady, 2*S));
        assertTrue(gate.diagnostics().contains("reason=surface"));
        var detailsReady = new PredictionLoadingProgress(234, 234, 192, 192, 192, 192, 100, 95);
        assertEquals(0, gate.limit(16, true, detailsReady, 3*S));
        assertEquals(4, gate.limit(16, true, detailsReady, 4*S));
        assertEquals(16, gate.limit(16, true, detailsReady, 7*S));
    }

    @Test void sustainedTargetOrSurfaceRegressionCanReacquirePredictionPriority() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, READY, 0);
        gate.limit(16, true, READY, S);
        assertEquals(16, gate.limit(16, true, READY, 4*S));
        var targetDebt = new PredictionLoadingProgress(100, 100, 100, 100, 100, 50, 100, 100);
        assertEquals(16, gate.limit(16, true, targetDebt, 5*S));
        assertEquals(0, gate.limit(16, true, targetDebt, 7*S));
        assertTrue(gate.diagnostics().contains("reason=near-target"));
        gate.limit(16, true, READY, 8*S);
        assertEquals(4, gate.limit(16, true, READY, 9*S));
        assertEquals(16, gate.limit(16, true, READY, 12*S));
        var surfaceDebt = new PredictionLoadingProgress(100, 100, 100, 100, 100, 100, 100, 20);
        assertEquals(16, gate.limit(16, true, surfaceDebt, 13*S));
        assertEquals(0, gate.limit(16, true, surfaceDebt, 15*S));
        assertTrue(gate.diagnostics().contains("reason=surface"));
    }

    @Test void budgetCapAppliesThroughoutRampAndImmediatelyRevokesNormalAdmission() {
        var gate = new PredictionGenerationPriority();
        assertEquals(0, gate.limit(16, true, READY, 3, 0));
        assertEquals(3, gate.limit(16, true, READY, 3, S));
        assertEquals(3, gate.limit(16, true, READY, 3, 4*S));
        assertEquals(0, gate.limit(16, true, READY, 0, 5*S));
        assertTrue(gate.diagnostics().contains("reason=budget"));
        assertEquals(0, gate.limit(16, true, READY, 2, 6*S));
        assertEquals(2, gate.limit(16, true, READY, 2, 7*S));
        assertEquals(2, gate.limit(16, true, READY, 2, 10*S));
        assertEquals(0, gate.limit(16, true, READY, -1, 11*S));
    }

    @Test void timeoutNeverBypassesPressureAndCancelledPulseMustCoolDown() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, LOADING, 0, 0);
        assertEquals(0, gate.limit(16, true, LOADING, 0, 120*S));
        assertEquals(1, gate.limit(16, true, LOADING, 4, 121*S));
        assertEquals(0, gate.limit(16, true, LOADING, 0, 122*S));
        assertEquals(0, gate.limit(16, true, LOADING, 4, 136*S));
        assertEquals(1, gate.limit(16, true, LOADING, 4, 137*S));
        assertEquals(0, gate.limit(16, true, LOADING, 4, 142*S));
    }

    @Test void completedDetailCanRecoverDuringKeepaliveCooldown() {
        var gate = new PredictionGenerationPriority();
        gate.limit(16, true, LOADING, 0);
        gate.limit(16, true, LOADING, 45*S);
        assertEquals(0, gate.limit(16, true, LOADING, 50*S));
        assertEquals(0, gate.limit(16, true, READY, 51*S));
        assertEquals(4, gate.limit(16, true, READY, 52*S));
    }

    @Test void emptyPlannedFrontierResumesAndLocalBudgetStillCapsDisabledPrediction() {
        var gate = new PredictionGenerationPriority();
        assertEquals(0, gate.limit(16, true, PredictionLoadingProgress.EMPTY, 0));
        assertEquals(4, gate.limit(16, true, PredictionLoadingProgress.EMPTY, S));
        assertEquals(16, gate.limit(16, true, PredictionLoadingProgress.EMPTY, 4*S));
        assertEquals(2, gate.limit(16, false, PredictionLoadingProgress.INITIALIZING, 2, 5*S));
        assertEquals(0, gate.limit(16, false, PredictionLoadingProgress.INITIALIZING, 0, 6*S));
        assertEquals(16, gate.limit(16, false, PredictionLoadingProgress.INITIALIZING, 100, 7*S));
        assertEquals(0, gate.limit(-1, false, PredictionLoadingProgress.INITIALIZING, 100, 8*S));
    }

    @Test void monotonicClockMayHaveNegativeOriginAndWrapWithoutSkippingDelays() {
        var gate = new PredictionGenerationPriority();
        long negative = -200*S;
        assertEquals(0, gate.limit(16, true, READY, negative));
        assertEquals(4, gate.limit(16, true, READY, negative + S));
        assertEquals(16, gate.limit(16, true, READY, negative + 4*S));
        gate.reset();
        long origin = Long.MAX_VALUE - 48*S;
        assertEquals(0, gate.limit(16, true, LOADING, origin));
        assertEquals(1, gate.limit(16, true, LOADING, origin + 45*S));
        assertEquals(1, gate.limit(16, true, LOADING, origin + 46*S));
        assertEquals(1, gate.limit(16, true, LOADING, origin + 49*S));
        assertEquals(0, gate.limit(16, true, LOADING, origin + 50*S));
        assertEquals(0, gate.limit(16, true, LOADING, origin + 64*S));
        assertEquals(1, gate.limit(16, true, LOADING, origin + 65*S));
    }
}
