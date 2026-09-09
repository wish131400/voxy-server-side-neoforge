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
        var plan = PredictionTileManager.withCoarseCoverage(List.of(leaf), layout);
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
                > PredictionWorkOrder.priority(near, layout, 0, 16, false),
                "continuous local preview precedes decoration");
        assertTrue(PredictionWorkOrder.priority(near, layout, 0, 64, true)
                > PredictionWorkOrder.priority(far, layout, 4000D * 4000, 0, false),
                "ordinary plants follow distant medium terrain");
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

    @Test void slabAndFenceSurviveTheActualPackedGpuFormatWithoutUndersides() {
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        var blocks = Map.of(new BlockPos(0, 64, 0), slab,
                new BlockPos(1, 64, 0), Blocks.OAK_FENCE.defaultBlockState());
        var surface = PredictionVegetation.Tile.of(blocks, 0, 0, 2, 1, 1);
        var sample = new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(),
                0, 0, 0, 0, 0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        var samples = new ClientColumnSample[9]; java.util.Arrays.fill(samples, sample);
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, 1, 3,
                true, new PredictionFeatureStampCache(), null, null, 0, 0, surface);
        var tile = new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(
                Level.OVERWORLD, 0, 0, 0), new int[9], new int[9], samples, mesh,
                new PredictionDepthBound(64, 66), 0, 1, 2, 1);
        var packed = PredictionPackedMesh.pack(tile);
        boolean fence = false, halfSlab = false;
        for (int offset = 0; offset < packed.quads().length; offset += PredictionPackedMesh.STRIDE_INTS) {
            var data = packed.quads();
            boolean fine = (data[offset + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
            float x = (data[offset] & 0xffff) / (fine ? 16F : 1F);
            float y = ((data[offset + 4] & 0xffff) - 32768) / (fine ? 16F : 4F);
            fence |= fine && x == 1.375F;
            halfSlab |= y == 64.5F;
        }
        assertTrue(fence, "fence post must keep its 6/16-width coordinates on the GPU");
        assertTrue(halfSlab, "top slabs must begin at half-block height");
        assertFalse(packed.downFaces(), "surface prediction must not introduce underground ceilings");
    }
}
