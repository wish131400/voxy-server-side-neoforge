package dev.xantha.vss.client.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PredictionLoadingProgressTest {
    @Test void targetsAndSurfaceDebtRemainVisibleAfterBaseCoverageCompletes() {
        var targets = new PredictionLoadingProgress(234, 234, 192, 192, 192, 60, 100, 20);
        assertFalse(targets.ready(95));
        assertEquals("near-target", targets.waitingFor(95));
        var surfaces = new PredictionLoadingProgress(234, 234, 192, 192, 192, 192, 100, 94);
        assertFalse(surfaces.ready(95));
        assertEquals("surface", surfaces.waitingFor(95));
        var completed = new PredictionLoadingProgress(234, 234, 192, 192, 192, 192, 100, 95);
        assertTrue(completed.ready(95));
        assertEquals("complete", completed.waitingFor(95));
        assertTrue(targets.score() < surfaces.score());
        assertTrue(surfaces.score() < completed.score());
    }

    @Test void initializedEmptyRangeDoesNotBlockButUnavailablePlanDoes() {
        assertTrue(PredictionLoadingProgress.EMPTY.ready(95));
        assertEquals(40000, PredictionLoadingProgress.EMPTY.score());
        assertFalse(PredictionLoadingProgress.INITIALIZING.ready(95));
        assertEquals(0, PredictionLoadingProgress.INITIALIZING.score());
        assertEquals("initializing", PredictionLoadingProgress.INITIALIZING.waitingFor(95));
        assertTrue(new PredictionLoadingProgress(0, 0, 0, 0, 0, 0, 0, 0).ready(95));
        assertFalse(new PredictionLoadingProgress(0, 0, 0, 0).ready(95));
    }

    @Test void legacyCallersHaveNoExtraSurfaceRequirement() {
        var progress = new PredictionLoadingProgress(10, 10, 0, 0);
        assertTrue(progress.ready(95));
        assertEquals(0, progress.targetTotal());
        assertEquals(0, progress.surfaceTotal());
        var near = new PredictionLoadingProgress(100, 100, 100, 94);
        assertFalse(near.ready(95));
        assertEquals(94, near.targetReady());
    }

    @Test void largeCountsDoNotOverflowAndRatiosRemainBounded() {
        var all = new PredictionLoadingProgress(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(all.ready(100));
        assertEquals(40000, all.score());
        var insufficient = new PredictionLoadingProgress(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1_900_000_000, 0, 0);
        assertFalse(insufficient.ready(95));
        var inconsistent = new PredictionLoadingProgress(1, Integer.MAX_VALUE, 1, -1, 1, 1, 0, 0);
        assertEquals(30000, inconsistent.score());
        assertFalse(inconsistent.ready(95));
    }
}
