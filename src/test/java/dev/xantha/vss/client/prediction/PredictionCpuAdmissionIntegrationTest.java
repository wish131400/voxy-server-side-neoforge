package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production queue and its final CPU lease, including CPU saturation and capped frame rates. */
class PredictionCpuAdmissionIntegrationTest {
    private static final long SECOND = 1_000_000_000L;
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    private String previousTier;
    private int previousDistance;

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @BeforeEach void lowTierFixture() {
        var config = VSSClientConfig.CONFIG;
        previousTier = config.performanceTier;
        previousDistance = config.predictionDistanceBlocks;


        config.performanceTier = "low";
        config.predictionDistanceBlocks = 8192;
        PredictionFramePace.resetForTesting();
    }

    @AfterEach void restoreFixture() {
        var config = VSSClientConfig.CONFIG;
        config.performanceTier = previousTier;
        config.predictionDistanceBlocks = previousDistance;
        PredictionFramePace.resetForTesting();
    }

    @Test void cpuSaturationCompletesCurrentTerrainWithTwoSecondsBetweenStarts() throws Exception {
        framePace(40_000_000L);
        var clock = new AtomicLong();
        var budget = budget(clock);
        var sampler = new Sampler(null);
        try (var manager = manager(sampler, budget)) {
            var first = root(manager, 0);
            var second = root(manager, 1);
            plan(manager, first, 8, 16);
            plan(manager, second, 8, 16);
            assertTrue(budget.diagnostics().contains("pressure=2"));
            enqueue(manager, first, false);
            assertEquals(0, manager.pendingCount(), "entering saturation starts a two-second detail delay");
            assertEquals(0, sampler.work.get());

            clock.set(2 * SECOND);
            enqueue(manager, first, false);
            awaitIdle(manager);
            assertEquals(16, residents(manager).get(first).cellAxis());
            assertTrue(sampler.work.get() > 0, "the actual production build must run and publish");
            assertTrue(budget.diagnostics().contains("active=0,detail=0"));
            int work = sampler.work.get();

            enqueue(manager, second, false);
            clock.set(4 * SECOND - 1);
            enqueue(manager, second, false);
            assertEquals(0, manager.pendingCount());
            assertEquals(work, sampler.work.get(), "short builds cannot admit the next task immediately");
            assertEquals(8, residents(manager).get(second).cellAxis());

            clock.incrementAndGet();
            enqueue(manager, second, false);
            awaitIdle(manager);
            assertEquals(16, residents(manager).get(second).cellAxis());
            assertTrue(sampler.work.get() > work);
        }
    }

    @Test void cpuSaturationDefersUnseenSurfaceThenCompletesItAfterTurning() throws Exception {
        framePace(40_000_000L);
        var clock = new AtomicLong();
        var budget = budget(clock);
        var sampler = new Sampler(null);
        try (var manager = manager(sampler, budget)) {
            var key = root(manager, 2);
            int axis = manager.layout().cellAxis(key.lod());
            plan(manager, key, axis, axis);
            keys(manager, "surfaceDesired").add(key);
            manager.setWorkView(PredictionWorkView.of(0, 80, 0, -1, 0, 0, 70, 1));
            var background = PredictionTileManager.class.getDeclaredMethod("backgroundWork", PredictionTileKey.class);
            background.setAccessible(true);
            assertEquals(true, background.invoke(manager, key), "this must exercise the paused background gate too");
            budget.diagnostics();
            clock.set(2 * SECOND);

            enqueue(manager, key, true);
            awaitIdle(manager);
            assertFalse(keys(manager, "surfaceReady").contains(key), "an invisible surface must wait even when the visible view has finished");
            assertEquals(0, sampler.work.get());
            manager.setWorkView(PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1));
            enqueue(manager, key, true);
            awaitIdle(manager);
            assertTrue(keys(manager, "surfaceReady").contains(key), "important surfaces must publish under CPU saturation");
            assertEquals(0, sampler.work.get(), "surface completion reuses resident terrain without density or color resampling");
            assertEquals(1, manager.builtTileCount());
            assertTrue(budget.diagnostics().contains("active=0,detail=0"));
        }
    }

    @Test void cpuPressureOneReducesConcurrencyWithoutTheSaturationDelay() throws Exception {
        framePace(33_333_333L);
        var clock = new AtomicLong();
        var budget = budget(clock, .90);
        var sampler = new Sampler(null);
        try (var manager = manager(sampler, budget)) {
            var key = root(manager, 0);
            plan(manager, key, 8, 16);
            assertEquals(PredictionCpuBudget.ThrottleLevel.REDUCE, budget.currentThrottle());
            enqueue(manager, key, false);
            awaitIdle(manager);
            assertEquals(16, residents(manager).get(key).cellAxis());
            assertTrue(sampler.work.get() > 0);
            assertTrue(budget.diagnostics().contains("active=0,detail=0"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "medium", "high"})
    void thirtyFpsAllowsActualTerrainAndIdleBuildsInEveryTier(String tier) throws Exception {
        VSSClientConfig.CONFIG.performanceTier = tier;
        framePace(33_333_333L);
        var clock = new AtomicLong();
        var budget = budget(clock, .5);
        var sampler = new Sampler(null);
        try (var manager = manager(sampler, budget)) {
            var key = root(manager, 0);
            plan(manager, key, 8, 16);
            assertEquals(PredictionCpuBudget.ThrottleLevel.NONE, budget.currentThrottle());
            assertTrue(budget.allowsIdleRefinement());
            enqueue(manager, key, false);
            awaitIdle(manager);
            assertEquals(16, residents(manager).get(key).cellAxis());
            assertEquals(1, manager.builtTileCount());

            var idleKey = root(manager, 1);
            plan(manager, idleKey, 8, 8);
            field("terrainTargets").set(manager, Map.of(key, 16, idleKey, 16));
            field("idleTargets").set(manager, Map.of(idleKey, 16));
            field("idleAllowed").setBoolean(manager, true);
            // Publication marks foreground planning dirty. This direct-enqueue
            // fixture must model the next completed plan before admitting idle work.
            field("foregroundWorkPending").setBoolean(manager, false);
            enqueue(manager, idleKey, false);
            awaitIdle(manager);
            assertEquals(16, residents(manager).get(idleKey).cellAxis(), "optional idle quality must progress at capped 30 FPS");
            assertEquals(2, manager.builtTileCount());
            assertEquals(8, budget.localGenerationLimit(16, false));
            assertTrue(budget.diagnostics().contains("active=0,detail=0"));
        }
    }

    @Test void idleUpgradeCannotUseTheCurrentTargetTrickleLane() throws Exception {
        framePace(40_000_000L);
        var clock = new AtomicLong();
        var budget = budget(clock);
        var sampler = new Sampler(null);
        try (var manager = manager(sampler, budget)) {
            var key = root(manager, 0);
            plan(manager, key, 8, 8);
            field("terrainTargets").set(manager, Map.of(key, 16));
            field("idleTargets").set(manager, Map.of(key, 16));
            field("idleAllowed").setBoolean(manager, true);
            budget.diagnostics();
            clock.set(2 * SECOND);
            enqueue(manager, key, false);
            assertEquals(0, manager.pendingCount());
            assertEquals(0, sampler.work.get());
            assertEquals(8, residents(manager).get(key).cellAxis());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {25_000_000L, 40_000_000L})
    void queuedManagersShareOneRealDetailLeaseAndDelayIsMeasuredFromStart(long frameGap) throws Exception {
        framePace(frameGap);
        var clock = new AtomicLong();
        var budget = budget(clock);
        var releaseBuild = new CountDownLatch(1);
        var firstSampler = new Sampler(releaseBuild);
        var secondSampler = new Sampler(null);
        try (var first = manager(firstSampler, budget); var second = manager(secondSampler, budget)) {
            CountDownLatch releaseFirstWorker = null, releaseSecondWorker = null;
            try {
                releaseFirstWorker = blockWorker(first);
                releaseSecondWorker = blockWorker(second);
                var firstKey = root(first, 0);
                var secondKey = root(second, 1);
                plan(first, firstKey, 8, 16);
                plan(second, secondKey, 8, 16);
                budget.diagnostics();
                clock.set(2 * SECOND);
                enqueue(first, firstKey, false);
                enqueue(second, secondKey, false);
                assertEquals(1, first.pendingCount());
                assertEquals(1, second.pendingCount(), "both planning hints must pass before either worker takes a lease");

                releaseFirstWorker.countDown();
                assertTrue(firstSampler.entered.await(10, TimeUnit.SECONDS));
                assertTrue(budget.diagnostics().contains("active=1,detail=1"));
                clock.set(4 * SECOND);
                releaseSecondWorker.countDown();
                awaitIdle(second);
                assertEquals(0, secondSampler.work.get(), "the worker must reject a second actual detail lease even after two seconds");
                assertEquals(8, residents(second).get(secondKey).cellAxis());

                releaseBuild.countDown();
                awaitIdle(first);
                assertEquals(16, residents(first).get(firstKey).cellAxis());
                enqueue(second, secondKey, false);
                awaitIdle(second);
                assertEquals(16, residents(second).get(secondKey).cellAxis(),
                        "a long build already waited two seconds since its start; finishing must not restart the delay");
                assertTrue(secondSampler.work.get() > 0);
                assertTrue(budget.diagnostics().contains("active=0,detail=0"));
            } finally {
                releaseBuild.countDown();
                if (releaseFirstWorker != null) releaseFirstWorker.countDown();
                if (releaseSecondWorker != null) releaseSecondWorker.countDown();
            }
        }
    }

    private static final class Sampler extends ClientTerrainSampler {
        final AtomicInteger work = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;
        Sampler(CountDownLatch release) { super(PROFILE.seed(), PROFILE); this.release = release; }
        private void observe() {
            work.incrementAndGet();
            entered.countDown();
            if (release != null) awaitLatch(release);
        }
        @Override int initialTerrainCellAxis(int lod) { return 8; }
        @Override public ClientColumnSample sample(int x, int z) { observe(); return PredictionVoxelFixtures.sample(64); }
        @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
        @Override public ClientColumnSample samplePreview(int x, int z, int step) { return sample(x, z); }
        @Override int surfaceColorForLod(int x, int y, int z, boolean preview) { observe(); return 0xff70aa30; }
        @Override int foliageColorForLod(int x, int y, int z, boolean preview) { return 0xff70aa30; }
    }

    private static void framePace(long gap) {
        long now = System.nanoTime();
        for (int i = 50; i >= 0; i--) PredictionFramePace.recordFrame(now - i * gap);
        assertEquals(1.0E9 / gap, PredictionFramePace.averageFps(), .01);
    }

    private static PredictionCpuBudget budget(AtomicLong clock) {
        return budget(clock, .98);
    }

    private static PredictionCpuBudget budget(AtomicLong clock, double cpuLoad) {
        return new PredictionCpuBudget(() -> 8, () -> cpuLoad, () -> .4, clock::get, false);
    }

    private static PredictionTileManager manager(ClientTerrainSampler sampler, PredictionCpuBudget budget) {
        return new PredictionTileManager(PROFILE.levelKey(), sampler,
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0,
                        () -> Long.MAX_VALUE, System::nanoTime, 1), null, budget);
    }

    private static PredictionTileKey root(PredictionTileManager manager, int x) {
        return new PredictionTileKey(PROFILE.levelKey(), x, 0, manager.layout().levelCount() - 1);
    }

    private static void plan(PredictionTileManager manager, PredictionTileKey key, int axis, int target) throws Exception {
        keys(manager, "desiredKeys").add(key);
        keys(manager, "terrainLeaves").add(key);
        for (String name : new String[] { "ordinaryTargets", "terrainTargets" }) {
            @SuppressWarnings("unchecked") var existing = (Map<PredictionTileKey, Integer>) field(name).get(manager);
            var targets = new HashMap<>(existing);
            targets.put(key, target);
            field(name).set(manager, Map.copyOf(targets));
        }
        int spacing = manager.layout().tileBlocks(key.lod()) / axis;
        int[] heights = new int[(axis + 1) * (axis + 1)];
        Arrays.fill(heights, 64);
        var samples = new ClientColumnSample[heights.length];
        Arrays.fill(samples, PredictionVoxelFixtures.sample(64));
        var mesh = PredictionVoxelFixtures.flat(key,axis,spacing,64);
        residents(manager).put(key, new PredictionTile(key, heights, heights, samples, mesh,
                new PredictionDepthBound(64, 64), 0, 0, axis, spacing, false));
    }

    private static CountDownLatch blockWorker(PredictionTileManager manager) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // The first core worker receives this directly; no non-comparable Runnable enters the priority queue.
        ((ThreadPoolExecutor) field("executor").get(manager)).execute(() -> {
            started.countDown();
            awaitLatch(release);
        });
        if (!started.await(10, TimeUnit.SECONDS)) {
            release.countDown();
            fail("worker did not start");
        }
        return release;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("test worker was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    private static void enqueue(PredictionTileManager manager, PredictionTileKey key, boolean surface) throws Exception {
        var method = PredictionTileManager.class.getDeclaredMethod("enqueue", PredictionTileKey.class,
                int.class, int.class, boolean.class);
        method.setAccessible(true);
        method.invoke(manager, key, 0, 0, surface);
    }

    private static void awaitIdle(PredictionTileManager manager) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (manager.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, manager.pendingCount());
        assertEquals(0, manager.failedTileCount());
    }

    private static Field field(String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Set<PredictionTileKey> keys(PredictionTileManager manager, String name) throws Exception {
        return (Set<PredictionTileKey>) field(name).get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<PredictionTileKey, PredictionTile> residents(PredictionTileManager manager) throws Exception {
        return (Map<PredictionTileKey, PredictionTile>) field("ready").get(manager);
    }
}
