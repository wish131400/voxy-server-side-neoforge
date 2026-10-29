package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionSurfaceDemandTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void visiblePlantsUseTheSameDemandBeyondStartupRadiusOnCoarseTerrain() {
        var layout = VssLodLayout.of(8192, 2, true, false);
        var front = new PredictionTileKey(Level.OVERWORLD, 4, 0, 2);
        var rear = new PredictionTileKey(Level.OVERWORLD, -5, 0, 2);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 16D / 9);
        assertFalse(PredictionWorkOrder.surfaceEligible(front, layout, 0, 0, 128, null));
        assertTrue(PredictionWorkOrder.surfaceEligible(front, layout, view, null,
                0, 80, 0, 128, 1000, -64, 384));
        assertFalse(PredictionWorkOrder.surfaceEligible(rear, layout, view, null,
                0, 80, 0, 128, 1000, -64, 384));
        var tiny = new PredictionTileKey(Level.OVERWORLD, 55, 0, 2);
        assertFalse(PredictionWorkOrder.surfaceEligible(tiny, layout, view, null,
                0, 80, 0, 128, 1000, -64, 384), "Subpixel distant trees keep their preview");
        assertFalse(PredictionWorkOrder.surfaceEligible(front, VssLodLayout.of(8192, 2, false, false), view, null,
                0, 80, 0, 128, 1000, -64, 384));
    }

    @Test void surfaceReplayHasABoundedFootprintIndependentOfTerrainSpacing() {
        var layout = VssLodLayout.of(8192, 2, true, false);
        assertTrue(PredictionWorkOrder.surfaceEligible(new PredictionTileKey(Level.OVERWORLD, -1, -1, 2),
                layout, 0, 0, 512, null), "Four-block nominal terrain can carry one-block plants");
        var large = new PredictionTileKey(Level.OVERWORLD, 0, 0, 3);
        assertFalse(PredictionWorkOrder.surfaceEligible(large, layout, 0, 0, 8192, null),
                "A 512-block terrain tile must subdivide before replaying all its plants");
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 16D / 9);
        assertFalse(PredictionWorkOrder.surfaceEligible(large, layout, view,
                new VssLodFocus(256, 256, 1024), 0, 80, 0, 8192, 1000, -64, 384));
    }

    @Test void requestingPlantsDoesNotRaiseTheTerrainSamplingTarget() {
        var layout = VssLodLayout.of(4096, 6, true, false);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 16D / 9);
        var input = new PredictionPlanPreparation.Input(Level.OVERWORLD, layout, view, null,
                0, 80, 0, 80, 0, 512, -64, 320, true,
                Collections.nCopies(layout.levelCount(), 16), 0, 0, ClientPredictionState.exactCoverageRemovalRevision(),
                new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(), Map.of()),
                Map.of(), Set.of(), false, Set.of(), Set.of(), Set.of(), 0, 0,
                PredictionVisibilityFeedback.Snapshot.EMPTY, Set.of(), Map.of(), Set.of(), Map.of(), Map.of(),
                new PredictionIdleRefinement(), true, true, false, System.nanoTime(), false);
        var result = PredictionPlanningTestSupport.buildPlan(input);
        assertFalse(result.surfaces().isEmpty());
        int partial = 0;
        for (var key : result.surfaces()) {
            int ordinary = PredictionPlanPreparation.ordinaryAxis(layout, view, null, key,
                    0, 80, 0, 80, -64, 320);
            assertEquals(ordinary, result.ordinary().get(key));
            assertEquals(ordinary, result.terrain().get(key));
            if (ordinary < 64) partial++;
            assertTrue(layout.tileBlocks(key.lod()) <= 256);
        }
        assertTrue(partial > 0, "Distinct trees can need completion while terrain is already screen-satisfied");
    }
}
