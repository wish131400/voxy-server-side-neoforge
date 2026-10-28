package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PredictionMeshBuilderTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }



























    @Test
    void depthBoundIncludesSurfaceAndWaterWithoutUndergroundSpans() {
        int count = PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[count];
        java.util.Arrays.fill(samples, new ClientColumnSample(
                64, 80, 0, ClientColumnSample.NO_BLOCK, 0, 1, 1, 12,
                1, ClientColumnSample.FLAG_TREE_HERE, 0, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_BLOCK, 50, 40, 20, 8));
        PredictionDepthBound bound = PredictionDepthBound.fromSamples(samples);
        assertEquals(64, bound.minY());
        assertEquals(80, bound.maxY());
    }


















}
