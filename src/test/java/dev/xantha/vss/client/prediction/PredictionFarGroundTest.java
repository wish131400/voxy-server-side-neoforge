package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class PredictionFarGroundTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void alignedAnchorPagesRetainOriginalNegativeCoordinatesAndReuseAcrossLods() throws Exception {
        var calls = new AtomicInteger();
        var method = PredictionFirstCoverageTest.class.getDeclaredMethod("sampler", AtomicInteger.class,
                java.util.concurrent.CountDownLatch.class, java.util.concurrent.CountDownLatch.class);
        method.setAccessible(true);
        var sampler = org.mockito.Mockito.spy((ClientTerrainSampler) method.invoke(null, calls, null, null));
        var ground = new PredictionFarGround(sampler);
        for (int gz = -8; gz < 0; gz++) for (int gx = -8; gx < 0; gx++) {
            long root = PredictionFarDecoration.mix(sampler.profile().seed() ^ ((long) gx * 0x9e3779b97f4a7c15L)
                    ^ ((long) gz * 0xc2b2ae3d27d4eb4fL) ^ PredictionFarDecoration.stringHash(sampler.profile().dimension().toString()));
            assertEquals(root, ground.root(gx, gz));
            assertEquals(gx * 5 + (int) Long.remainderUnsigned(PredictionFarDecoration.mix(root ^ 11), 5) - 2, ground.x(gx, root));
            assertEquals(gz * 5 + (int) Long.remainderUnsigned(PredictionFarDecoration.mix(root ^ 17), 5) - 2, ground.z(gz, root));
            assertNotNull(ground.at(gx, gz, () -> {}));
        }
        assertEquals(64, calls.get(), "all 64 anchors share one page, without resampling across refinements");
        for (int i = 0; i < 5; i++) ground.at(-1, -1, () -> {});
        assertEquals(64, calls.get());
        ground.at(0, 0, () -> {});
        assertEquals(128, calls.get());
        org.mockito.Mockito.verify(sampler).sampleForLod(ground.x(-1, ground.root(-1, -1)), ground.z(-1, ground.root(-1, -1)), 1);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> ground.at(0, 0, () -> { throw new java.util.concurrent.CancellationException(); }));
    }
}
