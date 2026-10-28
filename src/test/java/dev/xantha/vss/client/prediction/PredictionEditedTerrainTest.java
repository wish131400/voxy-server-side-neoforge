package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionEditedTerrainTest {
    static { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @TempDir Path directory;
    static final DimensionProfile PROFILE = new DimensionProfile(Level.OVERWORLD.location(), 42L, -64, 384,
            "noise", "minecraft:overworld", 77L);
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void drain() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void coarseAreaIncludesACraterBetweenEveryOriginalGridPoint() {
        var store = new PredictionCapturedTerrain(null);
        assertTrue(store.put(1, 1, craterSamples()));
        assertEquals(56, store.exact(24, 24).surfaceY());
        assertEquals(64, store.exact(16, 16).surfaceY());
        var reduced = store.sample(0, 0, 32, sample(64));
        assertEquals(63, reduced.surfaceY(), "all 100 excavated columns contribute, although no grid point hit them");
        assertTrue(reduced.captured());
        assertNotEquals(0, reduced.flags() & ClientColumnSample.FLAG_CAPTURE_MIP);
        assertEquals(64, store.sample(32, 0, 32, sample(64)).surfaceY(), "unrelated cells retain their own source");
        assertEquals(64, store.sample(0, 32, 32, sample(64)).surfaceY());
    }

    @Test void areaReductionUsesNegativeCoordinatesAndReplacingACaptureReplacesItsParents() {
        var store = new PredictionCapturedTerrain(null);
        store.put(-1, -1, craterSamples());
        assertEquals(56, store.exact(-8, -8).surfaceY());
        assertEquals(63, store.sample(-32, -32, 32, sample(64)).surfaceY());
        var filled = new ClientColumnSample[256]; Arrays.fill(filled, sample(64));
        assertTrue(store.put(-1, -1, filled));
        assertEquals(64, store.sample(-32, -32, 32, sample(64)).surfaceY());
        assertFalse(store.put(-1, -1, filled), "identical resends must not rewrite all ancestors");
    }

    @Test void reusingAReducedSampleDoesNotDeepenTheCraterAgain() {
        var store = new PredictionCapturedTerrain(null);
        store.put(1, 1, craterSamples());
        var reduced = store.sample(0, 0, 32, sample(64));
        assertEquals(63, reduced.surfaceY());
        for (int i = 0; i < 20; i++) reduced = store.sample(0, 0, 32, reduced);
        assertEquals(63, reduced.surfaceY(), "disk/mesh reuse must not apply the same partial coverage repeatedly");
        assertFalse(reduced.reusableFor(false), "an area mean is not an exact column for a finer grid");
        assertFalse(reduced.reusableForDisplay());
        assertEquals(64, store.sample(0, 0, 256, sample(64)).surfaceY(),
                "a small crater must quantize away instead of lowering every distant ancestor by one block");
    }

    @Test void fineMipsUseTheWholeCellAndCompleteAirDoesNotTurnIntoSolidGround() {
        var store = new PredictionCapturedTerrain(null);
        store.put(0, 0, craterSamples());
        assertEquals(56, store.sample(4, 4, 4, sample(64)).surfaceY());
        assertEquals(61, store.sample(0, 0, 8, sample(64)).surfaceY());
        var empty = new ClientColumnSample[256];
        Arrays.fill(empty, new ClientColumnSample(-64, -64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_CAPTURED | ClientColumnSample.FLAG_NO_SURFACE, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        store.put(0, 0, empty);
        assertFalse(store.sample(0, 0, 16, sample(64)).hasSurface());
    }

    @Test void aLowerDryColumnCannotEraseTheFluidTypeFromFineOrPersistedAreaMips() {
        for (int kind : new int[]{1, 2}) {
            try (var disk = new PredictionDiskCache(directory.resolve("fluid-" + kind), 77)) {
                var store = new PredictionCapturedTerrain(disk);
                var ground = sample(60);
                var wet = new ClientColumnSample(60, 64, 0, ground.topBlockIndex(), 0, 0, 0, 0,
                        kind, ground.flags(), 0, ground.underBlockIndex(), ground.deepBlockIndex(),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
                var columns = new ClientColumnSample[256]; Arrays.fill(columns, wet);
                columns[0] = sample(32);
                store.put(0, 0, columns); store.put(1, 0, columns); store.put(0, 1, columns);
                Arrays.fill(columns, sample(32)); store.put(1, 1, columns);
                for (int step : new int[]{8, 32}) {
                    var reduced = store.sample(0, 0, step, sample(64));
                    assertTrue(reduced.hasFluid()); assertEquals(kind, reduced.fluid());
                }
                store.clear();
                var reloaded = store.sample(0, 0, 32, sample(64));
                assertTrue(reloaded.hasFluid()); assertEquals(kind, reloaded.fluid());
            }
        }
    }

    @Test void decodedEvictionAndRestartKeepBothTheRealColumnAndCoarseModification() {
        try (var disk = new PredictionDiskCache(directory, 77)) {
            var store = new PredictionCapturedTerrain(disk, 36_000);
            store.put(1, 1, craterSamples());
            for (int i = 8; i < 18; i++) store.put(i, -i, craterSamples());
            assertTrue(store.bytes() <= 36_000);
            assertEquals(56, store.exact(24, 24).surfaceY(), "eviction must reload real data, not regenerate seed terrain");
            store.clear();
            var reopened = new PredictionCapturedTerrain(disk, 36_000);
            assertEquals(56, reopened.exact(24, 24).surfaceY());
            assertEquals(63, reopened.sample(0, 0, 32, sample(64)).surfaceY());
        }
        try (var disk = new PredictionDiskCache(directory, 77)) {
            var store = new PredictionCapturedTerrain(disk);
            assertEquals(56, store.exact(24, 24).surfaceY());
            assertEquals(63, store.sample(0, 0, 32, sample(64)).surfaceY());
        }
    }

    @Test void dirtyNotificationKeepsLastValidCraterUntilNewVersionArrives() throws Exception {
        try (var manager = manager(sampler(new AtomicInteger()))) {
            assertTrue(manager.captureExactColumn(1, 1, craterData()));
            awaitCaptures(manager);
            var exact = PredictionTileManager.class.getDeclaredMethod("sampleAt", int.class, int.class, int.class, int.class);
            exact.setAccessible(true);
            assertEquals(56, ((ClientColumnSample) exact.invoke(manager, 24, 24, 0, 1)).surfaceY());
            manager.invalidate(1, 1);
            assertEquals(56, ((ClientColumnSample) exact.invoke(manager, 24, 24, 0, 1)).surfaceY());
            VssLodSampleCache cache = field(manager, "sampleCache"); cache.clear();
            assertEquals(56, ((ClientColumnSample) exact.invoke(manager, 24, 24, 0, 1)).surfaceY(),
                    "fallback must survive eviction of the shared prediction sample cache too");
        }
    }

    @Test void aCompleteRealChunkDoesNotResample256SeedColumns() {
        var reads = new AtomicInteger();
        var result = ClientCaptureExtractor.extractAll(1, 1, craterData(), sampler(reads));
        assertEquals(56, result[8 + 8 * 16].surfaceY());
        assertTrue(reads.get() <= 1, "real voxel content already supplies heights/materials");
    }

    @Test void newCaptureInvalidatesASparseActiveAncestor() throws Exception {
        int previous = dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks = 65536;
        try (var manager = manager(sampler(new AtomicInteger()))) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 6);
            Set<PredictionTileManager.PredictionTileKey> pending = field(manager, "pending"); pending.add(key);
            assertFalse(PredictionTileManager.captureIntersectsGrid(key, manager.layout(), 1, 1));
            assertTrue(manager.captureExactColumn(1, 1, craterData())); awaitCaptures(manager);
            Map<PredictionTileManager.PredictionTileKey, Long> epochs = field(manager, "captureEpochs");
            assertEquals(1L, epochs.get(key));
        } finally { dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks = previous; }
    }

    @Test void obsoleteInFlightExtractionCannotOverwriteTheLatestAcceptedSnapshot() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var source = new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample samplePreview(int x, int z, int step) {
                assertTrue(Thread.currentThread().getName().startsWith("vss-prediction-"));
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new java.util.concurrent.CancellationException(); }
                }
                return PredictionEditedTerrainTest.sample(64);
            }
        };
        try (var manager = manager(source)) {
            assertTrue(manager.captureExactColumn(1, 1, craterData()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            manager.invalidate(1, 1);
            assertTrue(manager.captureExactColumn(1, 1, new VoxelColumnData(new VoxelColumnData.SectionData[0], 2L, true)));
            release.countDown(); awaitCaptures(manager);
            PredictionCapturedTerrain real = field(manager, "capturedTerrain");
            assertFalse(real.exact(24, 24).hasSurface(), "the later all-air snapshot supersedes the older crater");
        } finally { release.countDown(); }
    }

    @Test void productionBuildKeepsTheCapturedDepressionWhenRebuildingACoarseTile() throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks;
        config.predictionDistanceBlocks = 4096;
        try (var manager = manager(sampler(new AtomicInteger()))) {
            assertTrue(manager.captureLocalColumn(1, 1, craterData(), true)); awaitCaptures(manager);
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 5);
            invokeFixture("desire", new Class<?>[]{PredictionTileManager.class, key.getClass(), boolean.class}, manager, key, true);
            var parent = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 6);
            Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile> ready = field(manager, "ready");
            ready.put(parent, PredictionParentLodTest.tile(parent, 10, 64));
            for (int stage = 0; stage < 4; stage++) {
                invokeFixture("enqueue", new Class<?>[]{PredictionTileManager.class, key.getClass()}, manager, key);
                invokeFixture("awaitIdle", new Class<?>[]{PredictionTileManager.class}, manager);
                var tile = manager.readyTiles().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
                if (tile.spacingBlocks() <= 32)
                    assertTrue(tile.samples()[0].surfaceY() < 64, "the actual mesher input must contain the coarse depression");
                if (stage == 3) assertEquals(32, tile.spacingBlocks());
                assertTrue(Arrays.stream(tile.data().voxyTile.columns()).anyMatch(c -> !c.runs().isEmpty()));
            }
        } finally {
            config.predictionDistanceBlocks = distance;
        }
    }

    @Test void aDirtyQueuedSnapshotReleasesItsCapacityAndCpuDenialRetainsAcceptedWork() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var manager = manager(sampler(new AtomicInteger()))) {
            java.util.concurrent.ThreadPoolExecutor workers = field(manager, "executor");
            var constructor = PredictionTileManager.PredictionTask.class.getDeclaredConstructor(int.class, long.class, Runnable.class);
            constructor.setAccessible(true);
            workers.execute(constructor.newInstance(Integer.MIN_VALUE, 0L, (Runnable) () -> {
                entered.countDown(); try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) assertTrue(manager.captureExactColumn(i, 0, craterData()));
            assertFalse(manager.captureExactColumn(40, 0, craterData()));
            for (int i = 0; i < 32; i++) manager.invalidate(i, 0);
            assertTrue(((Set<?>) field(manager, "pendingCaptures")).isEmpty());
            assertEquals(0L, (Long) field(manager, "pendingCaptureBytes"));
            PredictionCpuBudget cpu = field(manager, "cpuBudget");
            try (var lease = cpu.tryAcquire(true)) {
                assertNotNull(lease);
                assertTrue(manager.captureExactColumn(40, 0, craterData()));
                release.countDown();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < until && (Boolean) field(manager, "captureDrainQueued")) Thread.sleep(5);
                assertEquals(1, ((Set<?>) field(manager, "pendingCaptures")).size());
            }
            awaitCaptures(manager);
            PredictionCapturedTerrain real = field(manager, "capturedTerrain");
            assertEquals(56, real.exact(40 * 16 + 8, 8).surfaceY());
        } finally { release.countDown(); }
    }

    private static void invokeFixture(String name, Class<?>[] signature, Object... args) throws Exception {
        var method = PredictionFirstCoverageTest.class.getDeclaredMethod(name, signature);
        method.setAccessible(true); method.invoke(null, args);
    }

    static ClientColumnSample sample(int y) {
        int gravel = BuiltInRegistries.BLOCK.getId(Blocks.GRAVEL);
        return new ClientColumnSample(y, y, 0, gravel, 0, 0, 0, 0, 0, ClientColumnSample.FLAG_CAPTURED,
                0, gravel, gravel, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    static ClientColumnSample[] craterSamples() {
        var samples = new ClientColumnSample[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            samples[z * 16 + x] = sample(x >= 3 && x <= 12 && z >= 3 && z <= 12 ? 56 : 64);
        return samples;
    }
    static VoxelColumnData craterData() {
        var section = ClientCaptureExtractorTest.section();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            for (int y = 0; y < (x >= 3 && x <= 12 && z >= 3 && z <= 12 ? 8 : 16); y++)
                section.setBlockState(x, y, z, Blocks.GRAVEL.defaultBlockState());
        return new VoxelColumnData(new VoxelColumnData.SectionData[]{new VoxelColumnData.SectionData(3, section, null, null)}, 1L, true);
    }
    static ClientTerrainSampler sampler(AtomicInteger calls) {
        return new ClientTerrainSampler(42, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) { calls.incrementAndGet(); return PredictionEditedTerrainTest.sample(64); }
            @Override public ClientColumnSample sampleSurface(int x, int z) { calls.incrementAndGet(); return PredictionEditedTerrainTest.sample(64); }
            @Override public ClientColumnSample samplePreview(int x, int z, int step) { calls.incrementAndGet(); return PredictionEditedTerrainTest.sample(64); }
        };
    }
    static PredictionTileManager manager(ClientTerrainSampler sampler) {
        return new PredictionTileManager(Level.OVERWORLD, sampler,
                new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 1), null);
    }
    static void awaitCaptures(PredictionTileManager manager) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            manager.serviceCaptures();
            synchronized (manager) { if (((Set<?>) field(manager, "pendingCaptures")).isEmpty()) return; }
            Thread.sleep(5);
        }
        fail("capture queue did not drain: " + manager.surfaceDiagnostics());
    }
    @SuppressWarnings("unchecked") static <T> T field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(owner);
    }
}
