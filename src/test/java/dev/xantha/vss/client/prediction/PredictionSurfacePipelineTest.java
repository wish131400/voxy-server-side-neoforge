package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.SlabBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionSurfacePipelineTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void horizonAndIntermediateAncestorsPrecedeTheirChildren() {
        var layout = VssLodLayout.of(8192, 2, true, false);
        var leaf = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, -51, 72, 0);
        var plan = PredictionLodPlanner.withCoarseCoverage(List.of(leaf), layout);
        assertEquals(layout.levelCount(), plan.size());
        assertEquals(layout.levelCount() - 1, plan.getFirst().lod());
        assertEquals(leaf, plan.getLast());
        for (int i = 1; i < plan.size(); i++) {
            assertEquals(plan.get(i).tileX() >> 1, plan.get(i - 1).tileX());
            assertEquals(plan.get(i).tileZ() >> 1, plan.get(i - 1).tileZ());
        }
    }

    @Test void nearSurfaceDoesNotDependOnAimAndDistantSurfaceRequiresSpyglass() {
        var layout = VssLodLayout.of(8192, 2, true, false);
        var near = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, -2, 1, 1);
        var far = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 30, 15, 1);
        assertTrue(PredictionWorkOrder.surfaceEligible(near, layout, 0, 0, 768, null));
        assertFalse(PredictionWorkOrder.surfaceEligible(far, layout, 0, 0, 768, null));
        var focus = new VssLodFocus(3904, 1984, 32, 7000);
        assertTrue(PredictionWorkOrder.surfaceEligible(far, layout, 0, 0, 768, focus));
        assertFalse(PredictionWorkOrder.surfaceEligible(far, layout, 0, 0, 768, new VssLodFocus(-3904, 1984, 32)));
        assertTrue(PredictionWorkOrder.priority(near, layout, 0, 64, true)
                < PredictionWorkOrder.priority(near, layout, 0, 16, false),
                "eligible local plants can finish before another preview upgrade");
        assertTrue(PredictionWorkOrder.priority(near, layout, 0, 64, true)
                < PredictionWorkOrder.priority(far, layout, 4000D * 4000, 0, false),
                "nearby plants precede distant medium terrain");
    }

    @Test void surfaceBandExtendsBeyondObservedRealLodInsteadOfHidingInsideIt() {
        int empty = PredictionWorkOrder.surfaceRadius(0, 0, 256, 768, 8192, (x, z) -> false);
        assertEquals(1024, empty);
        int filled = PredictionWorkOrder.surfaceRadius(0, 0, 256, 768, 8192,
                (x, z) -> Math.hypot(x * 16D, z * 16D) < 2048);
        assertTrue(filled >= 2816 && filled <= 2944, "real 2 km coverage must move surface detail beyond its boundary");
        int capped = PredictionWorkOrder.surfaceRadius(0, 0, 256, 768, 1024, (x, z) -> true);
        assertEquals(1024, capped, "the band cannot extend outside the prediction horizon");
    }


}
