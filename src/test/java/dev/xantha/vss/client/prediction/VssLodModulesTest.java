package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;

class VssLodModulesTest {
    @Test
    void layoutUsesPowerOfTwoTileSpacing() {
        VssLodLayout layout = VssLodLayout.of(65_536, 1.0D, true, false);
        assertEquals(64, layout.tileBlocks(0));
        assertEquals(128, layout.tileBlocks(1));
        assertEquals(2, layout.spacing(1));
        assertEquals(66, layout.samplesPerAxis());
    }

    @Test
    void configuredPredictionHorizonIsPreservedAtDefault() {
        VssLodLayout layout = VssLodLayout.of(8_192, 6.0D, true, false);

        assertEquals(8_192, layout.maxDistanceBlocks());
        assertEquals(8, layout.levelCount());
        assertEquals(8_192, layout.tileBlocks(layout.levelCount() - 1));
        assertEquals(66, layout.samplesPerAxis());
    }

    @Test
    void everyLevelKeepsTheFullSampleGridBudget() {
        VssLodLayout layout = VssLodLayout.of(8_192, 6.0D, true, false);

        // the samples the full 64x64 grid on every level; the spacing
        // grows with distance but the sample count does not. That is what
        // keeps distant mountains blocked instead of smoothing into large
        // triangles.
        assertEquals(64, layout.cellAxis(0));
        assertEquals(66, layout.sampleGridSize(0));
        assertEquals(1, layout.sampleSpacing(0));
        assertEquals(64, layout.cellAxis(1));
        assertEquals(66, layout.sampleGridSize(1));
        assertEquals(2, layout.sampleSpacing(1));
        assertEquals(64, layout.cellAxis(2));
        assertEquals(66, layout.sampleGridSize(2));
        assertEquals(4, layout.sampleSpacing(2));
        assertEquals(64, layout.cellAxis(7));
        assertEquals(8_192, layout.tileBlocks(7));
        assertEquals(128, layout.sampleSpacing(7));
    }

    @Test
    void projectionDecidesSubdivideByScreenFootprint() {
        assertTrue(VssLodProjection.shouldSubdivide(128.0D, 32.0D, 1.0D, 2.0D));
        assertFalse(VssLodProjection.shouldSubdivide(16.0D, 128.0D, 1.0D, 2.0D));
    }

    @Test
    void projectionUsesInfiniteReversedDepthWithoutMutatingVanilla() {
        Matrix4f vanilla = new Matrix4f().perspective((float) Math.toRadians(70.0D),
                16.0F / 9.0F, 0.05F, 1_024.0F);
        Matrix4f original = new Matrix4f(vanilla);

        VssLodProjection.MatrixData projection = VssLodProjection.of(vanilla);

        assertEquals(original, vanilla);
        assertEquals(0.0F, projection.matrix().m02(), 0.0F);
        assertEquals(0.0F, projection.matrix().m12(), 0.0F);
        assertEquals(1.0F, projection.matrix().m22());
        assertEquals(2.0F, projection.matrix().m32());
        assertEquals(0.0F, projection.culling().m22());
        assertEquals(0.0F, projection.culling().m32());
        assertTrue(Float.isFinite(projection.vanillaA()));
        assertTrue(Float.isFinite(projection.vanillaB()));
    }

    @Test
    void vanillaAndReversedDepthRoundTripAtTerrainDistances() {
        Matrix4f vanilla = new Matrix4f().perspective((float) Math.toRadians(70.0D),
                16.0F / 9.0F, 0.05F, 1_024.0F);
        VssLodProjection.MatrixData projection = VssLodProjection.of(vanilla);

        for (double distance : new double[] {1.0D, 64.0D, 512.0D}) {
            double vanillaDepth = VssLodProjection.distanceToVanillaDepth(distance, projection);
            assertEquals(distance,
                    VssLodProjection.vanillaDepthToDistance(vanillaDepth, projection),
                    distance * 1.0E-6D);

            double reversedDepth = VssLodProjection.distanceToReversedDepth(distance, 1.0D);
            assertEquals(distance,
                    VssLodProjection.reversedDepthToDistance(reversedDepth, 1.0D),
                    1.0E-9D);
        }
    }

    @Test
    void reversedDepthOrdersPredictionSurfacesFrontToBack() {
        double importedDepth = VssLodProjection.distanceToReversedDepth(128.0D, 1.0D);

        assertTrue(VssLodProjection.distanceToReversedDepth(64.0D, 1.0D)
                >= importedDepth);
        assertFalse(VssLodProjection.distanceToReversedDepth(256.0D, 1.0D)
                >= importedDepth);
    }

    @Test
    void voxyFinalBlitDepthWriteMixinIsRegistered() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/vss.compat.mixins.json")) {
            assertNotNull(stream);
            String config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("\"voxy.NormalRenderPipelineDepthMaskMixin\""));
            assertTrue(config.contains("\"voxy.IrisVoxyRenderPipelineDepthMaskMixin\""));
        }
    }

    @Test
    void greedyMesherMergesEqualMaterialRectangles() {
        List<VssLodGreedyMesher.Rectangle> rectangles =
                VssLodGreedyMesher.merge(new int[] {1, 1, 2, 1, 1, 2, 3, 3, 3}, 3);
        assertEquals(3, rectangles.size());
        assertEquals(2, rectangles.get(0).width());
        assertEquals(2, rectangles.get(0).height());
    }

    @Test
    void greedyMesherBoundsCoarseMaterialFootprints() {
        int[] cells = new int[64 * 64];
        java.util.Arrays.fill(cells, 7);
        List<VssLodGreedyMesher.Rectangle> rectangles =
                VssLodGreedyMesher.merge(cells, 64, 8, 4);
        assertTrue(rectangles.stream().allMatch(rect -> rect.width() <= 8 && rect.height() <= 4));
        assertEquals(64 * 64, rectangles.stream()
                .mapToInt(rect -> rect.width() * rect.height()).sum());
    }



    @Test
    void sampleCacheIsBoundedAndReusesValues() {
        VssLodSampleCache cache = new VssLodSampleCache(256);
        ClientColumnSample first = new ClientColumnSample(64, 64, -1,
                ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 0, 0, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        assertEquals(first, cache.getOrCompute(1L, ignored -> first));
        assertEquals(first, cache.getOrCompute(1L,
                ignored -> {
                    throw new AssertionError("cache did not reuse sample");
                }));
        for (long key = 2L; key < 400L; key++) {
            cache.getOrCompute(key, ignored -> first);
        }
        assertTrue(cache.size() <= 256);
    }
}
