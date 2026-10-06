package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionWarmSurfaceRestoreTest {
    @TempDir Path directory;
    private static final byte[] RESOURCES = new byte[32];
    private int oldDistance, oldRadius;
    private boolean oldTrees, oldStructures, oldSupersample;
    private Object previousResources;
    static final class Counts { final AtomicInteger samples = new AtomicInteger(), placements = new AtomicInteger(); }
    record Result(ClientColumnSample[] samples, int[] heights, int[] words, PredictionDepthBound bounds,
                  boolean surfaceReady, boolean scopeOnly, int sourceReads, int placements, int heightCalls,
                  long elapsedNanos, long stageNanos, long cpuNanos) { }

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void settings() throws Exception {
        var config = VSSClientConfig.CONFIG;
        oldDistance = config.predictionDistanceBlocks; oldRadius = config.predictionSurfaceDistanceBlocks;
        oldTrees = config.predictionTrees; oldStructures = config.predictionStructures; oldSupersample = config.predictionSupersample;
        config.predictionDistanceBlocks = 1024; config.predictionSurfaceDistanceBlocks = 128;
        config.predictionTrees = true; config.predictionStructures = false; config.predictionSupersample = false;
        var field = PredictionMeshResources.class.getDeclaredField("current"); field.setAccessible(true);
        previousResources = field.get(null);
        resources(RESOURCES);
    }
    @AfterEach void restore() throws Exception {
        var config = VSSClientConfig.CONFIG;
        config.predictionDistanceBlocks = oldDistance; config.predictionSurfaceDistanceBlocks = oldRadius;
        config.predictionTrees = oldTrees; config.predictionStructures = oldStructures; config.predictionSupersample = oldSupersample;
        var field = PredictionMeshResources.class.getDeclaredField("current"); field.setAccessible(true);
        field.set(null, previousResources);
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1})
    void warmCompleteSurfaceRestoresWithoutGatheringSources(int lod) throws Exception {
        var cold = run(directory, lod, false, false);
        int expectedSources = lod == 0 ? 36 : 100;
        assertTrue(cold.surfaceReady);
        assertEquals(expectedSources, cold.placements);
        var warm = run(directory, lod, false, false);
        assertSameOutput(cold, warm);
        assertTrue(warm.surfaceReady, "the complete mesh must restore the manager's completion state");
        assertEquals(0, warm.sourceReads, "warm complete mesh must not decode or gather 36-100 source maps");
        assertEquals(0, warm.placements);
        assertEquals(0, warm.heightCalls);
    }

    private static dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload cityRegion(int x, int z, boolean building) {
        var chunks = new ArrayList<>(Collections.nCopies(64, dev.xantha.vss.common.worldgen.LostCityPreview.EMPTY));
        if (building && x == 0 && z == 0) {
            int state = net.minecraft.world.level.block.Block.getId(Blocks.BRICKS.defaultBlockState());
            var model = new dev.xantha.vss.common.worldgen.LostCityPreview.Model(new int[]{
                    dev.xantha.vss.common.worldgen.LostCityPreview.origin(0, 12, 0),
                    dev.xantha.vss.common.worldgen.LostCityPreview.extent(16, 0, 16, 0), state});
            chunks.set(0, new dev.xantha.vss.common.worldgen.LostCityPreview.Chunk(2, 82, true,
                    state, state, 0, 0, List.of(new dev.xantha.vss.common.worldgen.LostCityPreview.Placement(0, model)), model));
        }
        return new dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload(Level.OVERWORLD.location(), x, z, 17, true, chunks);
    }

    private void installCities(PredictionTileManager manager, boolean complete) throws Exception {
        field(manager, "cityHints", new LostCityHints(Level.OVERWORLD, 17, true));
        if (complete) for (int z = -1; z <= 0; z++) for (int x = -1; x <= 0; x++)
            manager.acceptCityHints(cityRegion(x, z, true));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void cityMeshRestoresBeforeHintsWithoutReplayAndValidatesLaterRemoval(int mode) throws Exception {
        VSSClientConfig.CONFIG.predictionStructures = true;
        int[] words, heights; ClientColumnSample[] samples;
        var key = tileKey(0, false);
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(new Counts(), false), cache)) {
            installCities(manager, true); desire(manager, key, true); enqueue(manager, key); idle(manager);
            var tile = manager.readyTiles().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
            words = tile.mesh().gpuPayload().restoreWords(); heights = tile.heights().clone(); samples = tile.samples().clone();
            assertEquals(83, heights[0]); assertTrue(set(manager, "surfaceReady").contains(key));
            cache.flushMeshes(); cache.flush();
        }
        PredictionCacheTestFiles.awaitBackgroundClose();
        if (mode == 2) {
            // Convert the seed to the old city format, then verify a validated hit
            // upgrades it without replay before the subsequent hint-free entry.
            try (var cache = new PredictionDiskCache(directory, 1)) {
                var meshKey = PredictionDiskCache.Key.mesh(key(0, false));
                byte[] raw;
                try (var in = new InflaterInputStream(new ByteArrayInputStream(PredictionCacheTestFiles.read(cache, meshKey)))) {
                    raw = in.readAllBytes();
                }
                int footer = ByteBuffer.wrap(raw).getInt(raw.length - 4);
                int length = raw.length - footer - 4;
                ByteBuffer.wrap(raw).putInt(36, 9).putInt(32, length - 36);
                var bytes = new ByteArrayOutputStream();
                try (var out = new DeflaterOutputStream(bytes)) { out.write(raw, 0, length); }
                PredictionCacheTestFiles.write(cache, meshKey, bytes.toByteArray()); cache.flush();
            }
            PredictionCacheTestFiles.awaitBackgroundClose();
            var migrationCalls = new Counts();
            try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(migrationCalls, false), cache)) {
                installCities(manager, true); desire(manager, key, true); enqueue(manager, key); idle(manager);
                assertEquals(1, adder(manager, "finishedMeshRestores"));
                assertEquals(0, migrationCalls.samples.get()); assertEquals(0, migrationCalls.placements.get());
                cache.flushMeshes(); cache.flush();
            }
            PredictionCacheTestFiles.awaitBackgroundClose();
        }
        var calls = new Counts();
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(calls, false), cache)) {
            installCities(manager, false); desire(manager, key, true); probe(cache, key);
            enqueueCache(manager, key); idle(manager);
            assertEquals(1, adder(manager, "finishedMeshRestores"), manager.surfaceDiagnostics());
            var tile = manager.readyTiles().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
            assertArrayEquals(words, tile.mesh().gpuPayload().restoreWords());
            assertArrayEquals(heights, tile.heights()); assertArrayEquals(samples, tile.samples());
            assertEquals(0, calls.samples.get()); assertEquals(0, calls.placements.get());
            if (mode != 0)
                for (int z = -1; z <= 0; z++) for (int x = -1; x <= 0; x++) manager.acceptCityHints(cityRegion(x, z, true));
            assertFalse(set(manager, "dirtyTiles").contains(key), "matching hints must not undo the warm restore");
            assertTrue(set(manager, "surfaceReady").contains(key));
            manager.acceptCityHints(cityRegion(0, 0, false));
            assertTrue(set(manager, "dirtyTiles").contains(key), "confirmed building removal must invalidate disk geometry");
            assertFalse(set(manager, "surfaceReady").contains(key));
            enqueue(manager, key); idle(manager);
            var changed = manager.readyTiles().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
            assertEquals(64, changed.heights()[0]);
        }
    }

    @Test void unknownCityPreviewCannotOverwritePreviouslyCompleteMesh() throws Exception {
        VSSClientConfig.CONFIG.predictionStructures = true;
        var key = tileKey(0, false); var diskKey = PredictionDiskCache.Key.mesh(key(0, false));
        byte[] original;
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(new Counts(), false), cache)) {
            installCities(manager, true); desire(manager, key, true); enqueue(manager, key); idle(manager);
            cache.flushMeshes(); cache.flush(); original = PredictionCacheTestFiles.read(cache, diskKey);
            assertNotNull(original);
        }
        PredictionCacheTestFiles.awaitBackgroundClose();
        byte[] changed = RESOURCES.clone(); changed[0] = 7; resources(changed);
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(new Counts(), false), cache)) {
            installCities(manager, false); desire(manager, key, true); enqueue(manager, key); idle(manager);
            cache.flushMeshes(); cache.flush();
            assertEquals(0, adder(manager, "finishedMeshRestores"));
            assertArrayEquals(original, PredictionCacheTestFiles.read(cache, diskKey), "partial/unknown hints must never replace the saved city mesh");
        }
    }

    @Test void cacheRestoreBypassesBusyGeneratorAndItsBuildSlots() throws Exception {
        var cold = run(directory, 0, false, false);
        var calls = new Counts();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(calls, false), cache)) {
            var key = tileKey(0, false); desire(manager, key, true); probe(cache, key);
            var generation = (ThreadPoolExecutor) field(manager, "executor");
            generation.execute(new PredictionTileManager.PredictionTask(key, 0, 0, () -> {
                entered.countDown();
                try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var budget = (PredictionMemoryBudget) field(manager, "memoryBudget");
            var cpu = (PredictionCpuBudget) field(manager, "cpuBudget");
            try (var build = budget.tryReserveBuild(); var lease = cpu.tryAcquire(false)) {
                assertNotNull(build); assertNotNull(lease);
                assertNull(budget.tryReserveBuild(), "the generation build slot is occupied");
                enqueueCache(manager, key);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (adder(manager, "finishedMeshRestores") == 0 && System.nanoTime() < deadline) Thread.sleep(2);
                assertEquals(1, adder(manager, "finishedMeshRestores"), manager.surfaceDiagnostics());
                var restored = manager.readyTiles().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
                assertArrayEquals(cold.words, restored.mesh().gpuPayload().restoreWords());
                assertTrue(set(manager, "surfaceReady").contains(key));
                assertEquals(0, calls.samples.get()); assertEquals(0, calls.placements.get());
            } finally { release.countDown(); }
            idle(manager);
        } finally { release.countDown(); }
    }

    @Test void cacheMissCannotReplayFeaturesOnRestoreWorker() throws Exception {
        run(directory, 0, false, false);
        byte[] changed = RESOURCES.clone(); changed[0] = 7; resources(changed);
        var calls = new Counts();
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(calls, false), cache)) {
            var key = tileKey(0, false); desire(manager, key, true); probe(cache, key);
            enqueueCache(manager, key); idle(manager);
            assertEquals(1, adder(manager, "cacheFastMisses"));
            assertEquals(0, calls.samples.get()); assertEquals(0, calls.placements.get());
            assertTrue(manager.readyTiles().stream().noneMatch(t -> t.key().equals(key)));
            enqueueCache(manager, key); idle(manager);
            assertEquals(1, adder(manager, "cacheFastMisses"), "miss backoff prevents repeated disk reads");
            enqueue(manager, key); idle(manager);
            assertTrue(set(manager, "surfaceReady").contains(key), "normal generation still repairs incompatible cache");
            assertEquals(0, adder(manager, "finishedMeshRestores"));
            assertTrue(adder(manager, "meshingNanos") > 0);
        }
    }

    @Test void pausingCacheRestoreRejectsItsOldPublication() throws Exception {
        run(directory, 0, false, false);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delegate = sampler(new Counts(), false);
        var gated = new ClientTerrainSampler(42, delegate.profile()) {
            @Override int initialTerrainCellAxis(int lod) { return 64; }
            @Override long colorCacheFingerprint() {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 77;
            }
            @Override public ClientColumnSample sample(int x, int z) { return delegate.sample(x, z); }
        };
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(gated, cache)) {
            var key = tileKey(0, false); desire(manager, key, true); probe(cache, key);
            enqueueCache(manager, key);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            manager.setPaused(true); release.countDown();
            idle(manager);
            assertFalse(set(manager, "surfaceReady").contains(key));
            assertTrue(manager.readyTiles().stream().noneMatch(t -> t.key().equals(key)));
        } finally { release.countDown(); }
    }

    private static void probe(PredictionDiskCache cache, PredictionTileManager.PredictionTileKey key) throws Exception {
        var diskKey = PredictionDiskCache.Key.terrain(key.tileX(), key.tileZ(), key.lod());
        cache.probeTerrain(List.of(diskKey));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.cachedTerrainAxis(diskKey) == 0 && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(cache.cachedTerrainAxis(diskKey) > 0);
    }

    private static void enqueueCache(PredictionTileManager manager, PredictionTileManager.PredictionTileKey key) throws Exception {
        var method = manager.getClass().getDeclaredMethod("enqueue", PredictionTileManager.PredictionTileKey.class,
                int.class, int.class, boolean.class, boolean.class);
        method.setAccessible(true); method.invoke(manager, key, 0, 0, false, true);
    }

    @Test void stableWorldStorageRestoresCompleteDetailAcrossChangedWireFingerprint() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = true;
        try {
            var storage = PredictionCacheStorage.forWorld(directory, directory.resolve("saves/world"), null, null);
            var cold = run(directory, 0, false, false, false, storage, 1);
            var warm = run(directory, 0, false, false, false, storage, 999);
            assertSameOutput(cold, warm);
            assertTrue(warm.surfaceReady);
            assertEquals(0, warm.sourceReads); assertEquals(0, warm.placements); assertEquals(0, warm.heightCalls);
            System.out.println("STABLE_WORLD_REENTRY coldHeightCalls=" + cold.heightCalls + " warmHeightCalls=" + warm.heightCalls
                    + " coldPlacements=" + cold.placements + " warmPlacements=" + warm.placements + " warmSourceReads=" + warm.sourceReads);
        } finally { VSSClientConfig.CONFIG.rememberTerrain = remember; }
    }

    @Test void emptyCompletedSurfaceStillRestoresWithoutInferringFromPlants() throws Exception {
        var cold = run(directory, 0, true, false);
        assertEquals(36, cold.placements);
        var warm = run(directory, 0, true, false);
        assertSameOutput(cold, warm);
        assertTrue(warm.surfaceReady);
        assertEquals(0, warm.sourceReads);
        assertEquals(0, warm.placements);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void onlyNewSurfaceSourcesDirtyAnAlreadyReadyMediumAncestor(boolean warm) throws Exception {
        Result cold = warm ? run(directory, 0, false, false) : null;
        var calls = new Counts();
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(sampler(calls, false), cache)) {
            var key = tileKey(0, false); desire(manager, key, true);
            var parent = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
            int axis = 32, spacing = manager.layout().tileBlocks(parent.lod()) / axis;
            var samples = PredictionRefinementOptimizationsTest.samples(axis + 1);
            int[] heights = new int[samples.length]; Arrays.fill(heights, 64);
            var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, spacing, axis + 1, false).compactForRendering();
            var ancestor = new PredictionTileManager.PredictionTile(parent, heights, heights, samples, mesh,
                    new PredictionDepthBound(64, 64), 0, 1, axis, spacing);
            mesh.retainedSampleObjects = PredictionSampleCompaction.compact(samples); mesh.prepareGpuPayload(ancestor);
            assertTrue(spacing > 2 && spacing <= 8);
            set(manager, "desiredKeys").add(parent);
            ((Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field(manager, "ready")).put(parent, ancestor);
            enqueue(manager, key); idle(manager);
            assertTrue(set(manager, "surfaceReady").contains(key), manager.surfaceDiagnostics());
            assertEquals(!warm, set(manager, "dirtyTiles").contains(parent),
                    "cached complete geometry must not schedule ancestor reconstruction; new sources must refresh it");
            assertEquals(warm ? 0 : 36, calls.placements.get());
            if (warm) {
                var leaf = manager.readyTiles().stream().filter(tile -> tile.key().equals(key)).findFirst().orElseThrow();
                assertArrayEquals(cold.words, leaf.mesh().gpuPayload().restoreWords());
                assertEquals(0, calls.samples.get());
                var vegetation = (PredictionVegetation) field(manager, "vegetation");
                assertEquals(0, count(vegetation.diagnostics(), "surfaceDiskHits="));
                assertEquals(0, count(vegetation.diagnostics(), "surfaceMemoryHits="));
            }
            cache.flushMeshes(); cache.flush();
        }
    }

    @Test void oldMeshRebuildsSurfaceOnceThenUpgradesItsCompletionRecord() throws Exception {
        var cold = run(directory, 0, false, false);
        makeLegacy(directory, key(0, false));
        var legacy = run(directory, 0, false, false);
        assertSameOutput(cold, legacy);
        assertTrue(legacy.surfaceReady);
        assertEquals(36, legacy.sourceReads, "version 8 must still gather every surface source");
        assertEquals(0, legacy.placements, "source maps remain reusable even though completion was unknown");
        var upgraded = run(directory, 0, false, false);
        assertSameOutput(cold, upgraded);
        assertEquals(0, upgraded.sourceReads);
        assertTrue(upgraded.surfaceReady);
    }

    @Test void changedResourcesAndSettingsCannotReuseSurfaceCompletion() throws Exception {
        run(directory, 0, false, false);
        byte[] changed = RESOURCES.clone(); changed[0] = 1;
        resources(changed);
        var resourceReload = run(directory, 0, false, false);
        assertEquals(36, resourceReload.sourceReads);
        assertTrue(resourceReload.surfaceReady);
        VSSClientConfig.CONFIG.predictionStructures = true;
        var settingsReload = run(directory, 0, false, false);
        assertEquals(36, settingsReload.placements, "different surface settings require their own placement results");
        assertTrue(settingsReload.surfaceReady);
    }

    @Test void dirtyCaptureInvalidatesCompletedMeshAndRegeneratesSources() throws Exception {
        run(directory, 0, false, false);
        try (var cache = new PredictionDiskCache(directory, 1)) { cache.invalidateChunk(0, 0); cache.flush(); }
        PredictionCacheTestFiles.awaitBackgroundClose();
        var edited = run(directory, 0, false, false);
        assertTrue(edited.surfaceReady);
        assertTrue(edited.placements > 0);
        assertTrue(edited.heightCalls > 0);
    }

    @Test void scopeOnlySurfaceNeverClaimsGlobalCompletion() throws Exception {
        var scoped = run(directory, 0, false, true);
        assertTrue(scoped.scopeOnly);
        try (var cache = new PredictionDiskCache(directory, 1); var lease = cache.lease(key(0, true))) {
            var terrain = cache.readTerrainData(lease, 0);
            assertNotNull(terrain);
            var record = cache.readMeshBaseRecord(lease, base(terrain, 0), terrain.cellAxis());
            assertNotNull(record);
            assertFalse(record.surfaceCompleted(), "a telescope-only surface cannot become global completion after restart");
        }
    }

    @Test void captureDuringWarmRestoreCannotPublishOldSurfaceCompletion() throws Exception {
        run(directory, 0, false, false);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delegate = sampler(new Counts(), false);
        var gated = new ClientTerrainSampler(42, delegate.profile()) {
            @Override int initialTerrainCellAxis(int lod) { return 64; }
            @Override long colorCacheFingerprint() {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("restore gate timed out"); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return 77;
            }
            @Override public ClientColumnSample sample(int x, int z) { return delegate.sample(x, z); }
        };
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(gated, cache)) {
            var key = tileKey(0, false); desire(manager, key, true); enqueue(manager, key);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                manager.capturedTerrainChanged(0, 0);
            } finally { release.countDown(); }
            idle(manager);
            assertFalse(set(manager, "surfaceReady").contains(key));
            assertTrue(manager.readyTiles().stream().noneMatch(tile -> tile.key().equals(key)),
                    "a capture that invalidated the restoring lease/epoch must prevent publication");
        } finally { release.countDown(); }
    }

    @Test void partialAxisCompletionCannotSuppressFullPrecisionSurface() throws Exception {
        var source = sampler(new Counts(), false);
        int axis = 32, count = (axis + 2) * (axis + 2);
        var samples = new ClientColumnSample[count]; Arrays.fill(samples, source.sample(0, 0));
        int[] tints = new int[count]; Arrays.fill(tints, 0xff509050);
        var terrain = new PredictionDiskCache.TerrainData(samples, 77, tints, tints, tints);
        var mesh = PredictionMeshCodecTest.fixture(axis);
        try (var cache = new PredictionDiskCache(directory, 1); var lease = cache.lease(key(0, false))) {
            assertTrue(cache.writeTerrain(lease, terrain));
            byte[] base = base(terrain, 0);
            cache.writeMeshLater(lease, base, mesh, base, true, true); cache.flushMeshes(); cache.flush();
        }
        PredictionCacheTestFiles.awaitBackgroundClose();
        try (var cache = new PredictionDiskCache(directory, 1); var manager = manager(source, cache)) {
            var key = tileKey(0, false); desire(manager, key, false); enqueue(manager, key); idle(manager);
            assertEquals(axis, manager.readyTiles().stream().filter(tile -> tile.key().equals(key)).findFirst().orElseThrow().cellAxis());
            assertFalse(set(manager, "surfaceReady").contains(key), "completion requires the current layout's complete axis");
        }
    }

    static Result run(Path root, int lod, boolean empty, boolean scoped) throws Exception {
        return run(root, lod, empty, scoped, false);
    }

    static Result prepareCompleteSurfaceSeed(Path root, int lod) throws Exception {
        return run(root, lod, false, false, true);
    }

    private static Result run(Path root, int lod, boolean empty, boolean scoped, boolean prepareSeed) throws Exception {
        return run(root, lod, empty, scoped, prepareSeed, null, 1);
    }

    private static Result run(Path root, int lod, boolean empty, boolean scoped, boolean prepareSeed,
                              PredictionCacheStorage storage, long wireFingerprint) throws Exception {
        var calls = new Counts();
        var source = sampler(calls, empty, wireFingerprint);
        long started = System.nanoTime();
        var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuStarted = os.getProcessCpuTime();
        try (var cache = storage == null ? new PredictionDiskCache(root, 1) : storage.open(source); var manager = manager(source, cache)) {
            var key = tileKey(lod, scoped); desire(manager, key, true);
            if (scoped) field(manager, "buildFocus", new VssLodFocus(key.tileX() * 64 + 32, 32, 64, 2));
            enqueue(manager, key); idle(manager);
            assertTrue(set(manager, "surfaceReady").contains(key), manager.surfaceDiagnostics());
            var tile = manager.readyTiles().stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow();
            var plants = (PredictionVegetation) field(manager, "vegetation");
            String stats = plants.diagnostics();
            int reads = count(stats, "surfaceDiskHits=") + count(stats, "surfaceMemoryHits=");
            long stages = adder(manager, "decorationNanos") + adder(manager, "meshingNanos") + adder(manager, "packingNanos");
            var result = new Result(tile.samples().clone(), tile.heights().clone(), tile.mesh().gpuPayload().restoreWords(),
                    tile.depthBound(), true, tile.scopeOnly(), reads, calls.placements.get(), calls.samples.get(),
                    System.nanoTime() - started, stages, os.getProcessCpuTime() - cpuStarted);
            cache.flushMeshes(); cache.flush();
            if (prepareSeed) prepareSourceRecords(cache, plants);
            return result;
        } finally { PredictionCacheTestFiles.awaitBackgroundClose(); }
    }

    @SuppressWarnings("unchecked") private static void prepareSourceRecords(PredictionDiskCache cache, PredictionVegetation vegetation) throws Exception {
        var chunks = (Map<Long, Map<BlockPos, net.minecraft.world.level.block.state.BlockState>>) field(vegetation, "chunks");
        Map<Long, Map<BlockPos, net.minecraft.world.level.block.state.BlockState>> sources;
        synchronized (chunks) { sources = Map.copyOf(chunks); }
        int settings = (int) field(vegetation, "settings");
        // Async admission may drop source saves during a 100-source cold burst.
        // Complete only the benchmark seed, after its measured result was captured.
        for (var entry : sources.entrySet()) {
            int x = (int) (entry.getKey() >> 32), z = (int) (long) entry.getKey();
            try (var lease = cache.lease(PredictionDiskCache.Key.surface(x, z, settings))) {
                assertTrue(cache.writeSurface(lease, entry.getValue(), true));
            }
        }
        cache.flush();
        for (var entry : sources.entrySet()) {
            int x = (int) (entry.getKey() >> 32), z = (int) (long) entry.getKey();
            try (var lease = cache.lease(PredictionDiskCache.Key.surface(x, z, settings))) {
                var saved = cache.readSurfaceData(lease);
                assertNotNull(saved); assertTrue(saved.canonical()); assertTrue(saved.weatherChecked());
                assertEquals(entry.getValue(), saved.blocks());
            }
        }
        System.out.println("WARM_SURFACE_SEED sources=" + sources.size() + " synchronouslySavedAndVerified=true outsideMeasuredReentry=true");
    }

    static void assertSameOutput(Result expected, Result actual) {
        assertArrayEquals(expected.samples, actual.samples);
        assertArrayEquals(expected.heights, actual.heights);
        assertArrayEquals(expected.words, actual.words, "all terrain/water/plant/material packed words must match");
        assertEquals(expected.bounds, actual.bounds);
        assertEquals(expected.scopeOnly, actual.scopeOnly);
    }

    static void makeLegacy(Path root, PredictionDiskCache.Key key) throws Exception {
        try (var cache = new PredictionDiskCache(root, 1)) {
            byte[] raw;
            try (var in = new InflaterInputStream(new ByteArrayInputStream(PredictionCacheTestFiles.read(cache, PredictionDiskCache.Key.mesh(key))))) {
                raw = in.readAllBytes();
            }
            ByteBuffer.wrap(raw).putInt(36, 8).putInt(32, raw.length - 37);
            var encoded = new ByteArrayOutputStream();
            try (var out = new DeflaterOutputStream(encoded)) { out.write(raw, 0, raw.length - 1); }
            PredictionCacheTestFiles.write(cache, PredictionDiskCache.Key.mesh(key), encoded.toByteArray()); cache.flush();
        }
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    static PredictionDiskCache.Key key(int lod, boolean scoped) { return PredictionDiskCache.Key.terrain(scoped ? 80 : 0, 0, lod); }
    private static PredictionTileManager.PredictionTileKey tileKey(int lod, boolean scoped) {
        return new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, scoped ? 80 : 0, 0, lod);
    }
    private static byte[] base(PredictionDiskCache.TerrainData data, int lod) {
        return PredictionMeshCodec.baseSignature(PredictionMeshResources.ready(), data.samples(), data.surfaceTints(), data.foliageTints(),
                data.waterTints(), 63, 0xff3f76e4, (64 << lod) / data.cellAxis(), true,
                (VSSClientConfig.CONFIG.predictionTrees ? 1 : 0) | (VSSClientConfig.CONFIG.predictionStructures ? 2 : 0)
                        | PredictionVegetation.predicateSettings()
                        | dev.xantha.vss.config.PredictionVegetationDensity.current().settingsBits());
    }
    private static PredictionTileManager manager(ClientTerrainSampler source, PredictionDiskCache cache) {
        return new PredictionTileManager(Level.OVERWORLD, source,
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 1), cache);
    }
    @SuppressWarnings("unchecked") private static Set<PredictionTileManager.PredictionTileKey> set(Object target, String name) throws Exception {
        return (Set<PredictionTileManager.PredictionTileKey>) field(target, name);
    }
    @SuppressWarnings("unchecked") private static void desire(PredictionTileManager manager, PredictionTileManager.PredictionTileKey key, boolean surface) throws Exception {
        set(manager, "desiredKeys").add(key); set(manager, "terrainLeaves").add(key);
        if (surface) set(manager, "surfaceDesired").add(key);
        var parent = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, key.tileX() >> 1, key.tileZ() >> 1, key.lod() + 1);
        int span = manager.layout().tileBlocks(parent.lod());
        var samples = PredictionRefinementOptimizationsTest.samples(2);
        int[] heights = {64, 64, 64, 64};
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, span, 2, false).compactForRendering();
        var tile = new PredictionTileManager.PredictionTile(parent, heights, heights, samples, mesh,
                new PredictionDepthBound(64, 64), 0, 1, 1, span);
        mesh.retainedSampleObjects = PredictionSampleCompaction.compact(samples);
        mesh.prepareGpuPayload(tile);
        ((Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field(manager, "ready")).put(parent,
                tile);
    }
    private static void enqueue(PredictionTileManager manager, PredictionTileManager.PredictionTileKey key) throws Exception {
        var method = manager.getClass().getDeclaredMethod("enqueue", PredictionTileManager.PredictionTileKey.class, int.class, int.class, boolean.class);
        method.setAccessible(true); method.invoke(manager, key, 0, 0, false);
    }
    private static void idle(PredictionTileManager manager) throws Exception {
        var executor = (ThreadPoolExecutor) field(manager, "executor");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        var cacheExecutor = (ThreadPoolExecutor) field(manager, "cacheExecutor");
        while ((manager.pendingCount() > 0 || executor.getActiveCount() > 0 || cacheExecutor.getActiveCount() > 0) && System.nanoTime() < deadline) Thread.sleep(2);
        assertEquals(0, manager.pendingCount(), manager.surfaceDiagnostics());
        assertEquals(0, executor.getActiveCount());
        assertEquals(0, manager.failedTileCount(), manager.surfaceDiagnostics());
    }
    private static long adder(Object object, String name) throws Exception { return ((java.util.concurrent.atomic.LongAdder) field(object, name)).sum(); }
    private static int count(String stats, String label) {
        int start = stats.indexOf(label) + label.length(), end = stats.indexOf(',', start);
        return Integer.parseInt(stats.substring(start, end));
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void field(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void resources(byte[] value) throws Exception {
        var field = PredictionMeshResources.class.getDeclaredField("current"); field.setAccessible(true);
        field.set(null, CompletableFuture.completedFuture(value));
    }
    private static ClientTerrainSampler sampler(Counts counts, boolean empty) {
        return sampler(counts, empty, 1);
    }

    private static ClientTerrainSampler sampler(Counts counts, boolean empty, long wireFingerprint) {
        var marker = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                counts.placements.incrementAndGet();
                var pos = new BlockPos(context.origin().getX() + 3, 65, context.origin().getZ() + 3);
                if (!empty) for (int y = 0; y < 4; y++) context.level().setBlock(pos.above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
                return true;
            }
        };
        var placed = new net.minecraft.world.level.levelgen.placement.PlacedFeature(Holder.direct(new ConfiguredFeature<>(marker, NoneFeatureConfiguration.INSTANCE)), List.of());
        var generation = new BiomeGenerationSettings.PlainBuilder().addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, Holder.direct(placed)).build();
        var biome = new Biome.BiomeBuilder().hasPrecipitation(false).temperature(.7F).downfall(.5F)
                .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(generation).build();
        var biomes = new FixedBiomeSource(Holder.direct(biome));
        var lookup = VanillaRegistries.createLookup();
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator = new NoiseBasedChunkGenerator(biomes, settings);
        var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), 42);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", wireFingerprint);
        return new ClientTerrainSampler(42, profile) {
            @Override int initialTerrainCellAxis(int lod) { return 64; }
            @Override long colorCacheFingerprint() { return 77; }
            @Override public int seaLevel() { return 63; }
            @Override public int fluidColor() { return 0xff3f76e4; }
            @Override public ClientColumnSample sample(int x, int z) {
                counts.samples.incrementAndGet();
                return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(), 0, 0, 0, 0, 0, 0,
                        0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            @Override public int surfaceColor(int x, int y, int z) { return 0xff509050; }
            @Override public int foliageColor(int x, int y, int z) { return 0xff509050; }
            @Override int waterTintForLod(int x, int y, int z, boolean preview) { return 0xff509050; }
            @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
            @Override RandomState randomStateContext() { return random; }
            @Override BiomeSource biomeSourceContext() { return biomes; }
            @Override RegistryAccess decorationAccess() { return RegistryAccess.EMPTY; }
        };
    }
}
