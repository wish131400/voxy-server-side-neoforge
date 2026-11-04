package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

class PredictionFarGroundNativeTest {
    @BeforeAll static void bootstrap() throws Exception { PredictionFarDecorationNativeTest.bootstrap(); }
    @Test void batchedAnchorsMatchEveryOriginalSinglePointQuery() throws Exception {
        try (var sampler = PredictionFarDecorationNativeTest.sampler();
             var original = PredictionFarDecorationNativeTest.sampler()) {
            var ground = new PredictionFarGround(sampler);
            for (int[] page : new int[][]{{-8, -8}, {0, 0}, {1640, -1640}}) {
                for (int i = 0; i < 64; i++) {
                    int gx = page[0] + i % 8, gz = page[1] + i / 8;
                    long root = ground.root(gx, gz);
                    var single = new ClientColumnSample[1];
                    original.sampleDisplayGridInPlace(ground.x(gx, root), ground.z(gz, root), 1, 1, 1, single, 0, 1);
                    assertEquals(single[0], ground.at(gx, gz, () -> {}), "anchor " + gx + "," + gz);
                }
            }
        }
    }
}
