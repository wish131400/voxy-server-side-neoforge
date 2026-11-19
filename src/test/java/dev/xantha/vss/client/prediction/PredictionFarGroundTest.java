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
        assertEquals(65, calls.get(), "a new Java point must not construct its 63 unused neighbours");
        org.mockito.Mockito.verify(sampler).sampleForLod(ground.x(-1, ground.root(-1, -1)), ground.z(-1, ground.root(-1, -1)), 1);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> ground.at(0, 0, () -> { throw new java.util.concurrent.CancellationException(); }));
    }

    @Test void displayGroundUsesPreviewWithoutCallingExactTerrain() {
        var sampler=org.mockito.Mockito.mock(FreeTerraForgedTerrainSampler.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.tryParse("minecraft:overworld"),42L,-64,384,"noise","minecraft:overworld",123L);
        org.mockito.Mockito.doReturn(profile).when(sampler).profile();
        org.mockito.Mockito.doReturn(PredictionVoxelFixtures.sample(72)).when(sampler).samplePreview(
                org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt());
        var ground=new PredictionFarGround(sampler);
        var first=ground.at(-1,2,()->{});
        assertSame(first,ground.at(-1,2,()->{}));
        long root=ground.root(-1,2);
        org.mockito.Mockito.verify(sampler).samplePreview(ground.x(-1,root),ground.z(2,root));
        org.mockito.Mockito.verify(sampler,org.mockito.Mockito.never()).sampleSurface(
                org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt());
    }
}
