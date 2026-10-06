package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class PredictionCacheWorkerLifecycleTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void nativeOwnerOutlivesBothGenerationAndCacheWorkers() throws Exception {
        var closed = new AtomicBoolean();
        var sampler = new ClientTerrainSampler(42,
                new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),
                        42L, -64, 384, "noise", "minecraft:overworld", 1L)) {
            @Override public void close() { closed.set(true); super.close(); }
        };
        var generator = Executors.newSingleThreadExecutor();
        var restore = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            restore.execute(() -> {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            generator.shutdownNow();
            assertTrue(generator.awaitTermination(5, TimeUnit.SECONDS));
            restore.shutdownNow();
            PredictionResources.retire(generator, null, sampler, restore);
            var field = PredictionResources.class.getDeclaredField("DISPOSER"); field.setAccessible(true);
            var fence = ((ExecutorService) field.get(null)).submit(() -> { });
            assertThrows(TimeoutException.class, () -> fence.get(100, TimeUnit.MILLISECONDS));
            assertFalse(closed.get(), "a cache decoder may still reference the native owner");
            release.countDown();
            fence.get(5, TimeUnit.SECONDS);
            assertTrue(closed.get());
        } finally {
            release.countDown(); generator.shutdownNow(); restore.shutdownNow();
            PredictionResources.awaitRetired();
        }
    }
}
