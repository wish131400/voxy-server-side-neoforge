package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.compat.ModCompat;
import org.junit.jupiter.api.Test;

class PredictionRendererTest {
    @Test
    void periodicExpiryRetainsOwnershipButChangedOwnersInvalidateTheMask() {
        var view = new PredictionRenderer.CoverageView(0, 0, 700.0, null);
        var coverage = new PredictionRenderer.TileCoverage(new boolean[]{true}, 1, 1, 0, 0);
        var cached = new PredictionRenderer.CachedCoverage(1L, 5L, 0L, view, coverage);
        // Already expired, yet safe to keep while the optional probe waits.
        assertTrue(cached.matchesOwnership(1L, 5L, view));
        assertFalse(cached.matchesOwnership(1L, 6L, view));
        assertFalse(cached.matchesOwnership(2L, 5L, view));
    }

    @Test
    void movementAndTelescopeChangesInvalidateTheWholeCoverageView() {
        var view = new PredictionRenderer.CoverageView(0, 0, 700.0, null);
        var cached = new PredictionRenderer.CachedCoverage(1L, 5L, Long.MAX_VALUE, view, null);
        assertFalse(cached.matchesOwnership(1L, 5L,
                new PredictionRenderer.CoverageView(1, 0, 700.0, null)));
        assertFalse(cached.matchesOwnership(1L, 5L,
                new PredictionRenderer.CoverageView(0, 0, 7000.0, null)));
        assertFalse(cached.matchesOwnership(1L, 5L,
                new PredictionRenderer.CoverageView(0, 0, 700.0, new VssLodFocus(2000, 2000, 128))));
    }

    @Test
    void geometricLodStepsOneLevelPerDistanceDoubling() {
        // Inverse of the planner's projected-size test: every doubling of
        // distance steps exactly one level, so the renderer requests the
        // tiles the planner builds without skipping detail levels.  The
        // level is the largest power-of-two spacing not exceeding
        // distance * pixelsPerQuad / pixelsPerBlock (floor), matching the
        // planner's "subdivide while projected size exceeds threshold".
        assertEquals(0, PredictionRenderer.lodForBlocks(64.0D, 700.0D, 6.0D));
        assertEquals(0, PredictionRenderer.lodForBlocks(128.0D, 700.0D, 6.0D));
        assertEquals(1, PredictionRenderer.lodForBlocks(256.0D, 700.0D, 6.0D));
        assertEquals(2, PredictionRenderer.lodForBlocks(512.0D, 700.0D, 6.0D));
        assertEquals(6, PredictionRenderer.lodForBlocks(8_192.0D, 700.0D, 6.0D));
        // spacingMax exactly on a power of two keeps that level, not the next.
        assertEquals(3, PredictionRenderer.lodForBlocks(1_066.67D, 700.0D, 6.0D));
    }

    @Test
    void geometricLodClampsToSaneBounds() {
        assertEquals(0, PredictionRenderer.lodForBlocks(1.0D, 700.0D, 6.0D));
        assertTrue(PredictionRenderer.lodForBlocks(1.0E7D, 700.0D, 6.0D)
                <= PredictionTileManager.MAX_LOD_LEVEL);
        assertEquals(0, PredictionRenderer.lodForBlocks(64.0D, 0.0D, 6.0D));
    }

    @Test
    void exactCoverageWaitsForVoxyRenderNodesToSettle() {
        long started = 10_000_000_000L;

        assertFalse(ExactCoverageGate.ownsPrediction(ModCompat.LocalColumnState.PRESENT,
                started, started + ExactCoverageGate.SETTLE_NANOS - 1L));
        assertTrue(ExactCoverageGate.ownsPrediction(ModCompat.LocalColumnState.PRESENT,
                started, started + ExactCoverageGate.SETTLE_NANOS));
        assertFalse(ExactCoverageGate.ownsPrediction(ModCompat.LocalColumnState.UNKNOWN,
                started, Long.MAX_VALUE));
        assertFalse(ExactCoverageGate.ownsPrediction(ModCompat.LocalColumnState.MISSING,
                started, Long.MAX_VALUE));
    }

}
