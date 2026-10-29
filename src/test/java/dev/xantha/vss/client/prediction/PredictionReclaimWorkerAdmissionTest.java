package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionReclaimWorkerAdmissionTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
            "noise", "minecraft:overworld", 1L);
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void alreadyQueuedWorkerCannotSpendAnotherReclaimTargetsWorkspace() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        var sampling = new AtomicInteger();
        var blockerEntered = new CountDownLatch(1);
        var releaseQueue = new CountDownLatch(1);
        var budget = new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var manager = new PredictionTileManager(PROFILE.levelKey(), sampler(sampling, null, null), budget)) {
            var executor = (ThreadPoolExecutor) field("executor").get(manager);
            executor.execute(() -> { blockerEntered.countDown(); waitFor(releaseQueue); });
            assertTrue(blockerEntered.await(3, TimeUnit.SECONDS));
            var queued = key(manager, 0);
            var replacement = key(manager, 1);
            desire(manager, queued, replacement);
            enqueue(manager, queued);
            assertEquals(1, manager.pendingCount(), "the old task must already be queued before reclaim starts");
            field("reclaimAdmissionTarget").set(manager, replacement);
            releaseQueue.countDown();
            await(() -> manager.pendingCount() == 0);
            assertEquals(0, sampling.get(), "an older queued task cannot consume the replacement's workspace");
            assertEquals(0, budget.usedBytes(), "rejected queued work must release every lease");
            assertEquals(0, budget.activeBuildCount());
            assertEquals(replacement, field("reclaimAdmissionTarget").get(manager));
        } finally { releaseQueue.countDown(); VSSClientConfig.CONFIG.rememberTerrain = remember; }
        await(() -> budget.usedBytes() == 0);
    }

    @Test
    void reservedReplacementImmediatelyAllowsOtherWorkersBeforePublication() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        var sampling = new AtomicInteger();
        var bothSampling = new CountDownLatch(2);
        var releaseSampling = new CountDownLatch(1);
        var budget = new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, System::nanoTime, 2);
        try (var manager = new PredictionTileManager(PROFILE.levelKey(), sampler(sampling, bothSampling, releaseSampling), budget)) {
            var replacement = key(manager, 0);
            var other = key(manager, 1);
            desire(manager, replacement, other);
            field("reclaimAdmissionTarget").set(manager, replacement);
            enqueue(manager, replacement);
            await(() -> sampling.get() > 0);
            assertNull(field("reclaimAdmissionTarget").get(manager), "workspace reservation ends the handoff");
            assertEquals(0, manager.readyCount(), "replacement is still sampling, not published");
            assertEquals(1, budget.activeBuildCount());
            assertTrue(budget.usedBytes() >= PredictionMemoryBudget.BUILD_BYTES, "workspace remains genuinely charged");
            enqueue(manager, other);
            assertTrue(bothSampling.await(3, TimeUnit.SECONDS), "another worker can sample before replacement publication");
            assertEquals(2, budget.activeBuildCount());
            assertEquals(0, manager.readyCount());
            releaseSampling.countDown();
            await(() -> manager.pendingCount() == 0 && budget.activeBuildCount() == 0);
        } finally { releaseSampling.countDown(); VSSClientConfig.CONFIG.rememberTerrain = remember; }
        await(() -> budget.usedBytes() == 0);
    }

    private static ClientTerrainSampler sampler(AtomicInteger sampling, CountDownLatch entered, CountDownLatch release) {
        return new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                sampling.incrementAndGet();
                if (entered != null) entered.countDown();
                if (release != null) waitFor(release);
                return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(),
                        0, 0, 0, 0, 0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
        };
    }
    private static PredictionTileManager.PredictionTileKey key(PredictionTileManager manager, int x) {
        return new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), x, 0, manager.layout().levelCount() - 1);
    }
    @SuppressWarnings("unchecked")
    private static void desire(PredictionTileManager manager, PredictionTileManager.PredictionTileKey... keys) throws Exception {
        var desired = (Set<PredictionTileManager.PredictionTileKey>) field("desiredKeys").get(manager);
        desired.addAll(java.util.List.of(keys));
    }
    private static void enqueue(PredictionTileManager manager, PredictionTileManager.PredictionTileKey key) throws Exception {
        var method = PredictionTileManager.class.getDeclaredMethod("enqueue", PredictionTileManager.PredictionTileKey.class,
                int.class, int.class, boolean.class); method.setAccessible(true); method.invoke(manager, key, 0, 0, false);
    }
    private static java.lang.reflect.Field field(String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void waitFor(CountDownLatch release) {
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("controlled worker timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static void await(BooleanSupplier done) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(done.getAsBoolean(), "worker condition timed out");
    }
}
