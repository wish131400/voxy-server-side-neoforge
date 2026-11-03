package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionCacheRecoveryConcurrencyTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void drain() throws Exception {
        PredictionResources.awaitRetired();
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    private static ClientColumnSample[] grid(int axis) {
        var samples = new ClientColumnSample[(axis + 2) * (axis + 2)];
        Arrays.fill(samples, PredictionVoxelFixtures.sample(64));
        return samples;
    }

    private void seed(PredictionDiskCache.Key key) throws Exception {
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease, grid(8)));
        }
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    private static void awaitProbes(PredictionDiskCache cache) throws Exception {
        var field = PredictionDiskCache.class.getDeclaredField("probesFinished");
        field.setAccessible(true);
        ((CompletableFuture<?>) field.get(cache)).get(5, TimeUnit.SECONDS);
    }

    @Test void discoveryContinuesWhileWritesAreBlocked() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        seed(key);
        try (var cache = new PredictionDiskCache(directory, 77);
             var gate = new PredictionAsyncTerrainCacheTest.Gate()) {
            cache.probeTerrain(List.of(key));
            awaitProbes(cache);
            assertEquals(8, cache.cachedTerrainAxis(key), "header discovery cannot wait for the writer lane");
            try (var lease = cache.lease(key)) {
                assertEquals(8, cache.readTerrainData(lease, 0).cellAxis());
            }
        }
    }

    @Test void anOldProbeCannotRepublishTheHeaderReplacedByACommit() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        seed(key);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new HeaderGate(cache, key)) {
            cache.probeTerrain(List.of(key));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            try (var lease = cache.lease(key)) { assertTrue(cache.writeTerrain(lease, grid(64))); }
            gate.release.countDown();
            awaitProbes(cache);
            assertEquals(0, cache.cachedTerrainAxis(key), "the previous header must be discarded after a concurrent commit");
            cache.probeTerrain(List.of(key));
            awaitProbes(cache);
            assertEquals(64, cache.cachedTerrainAxis(key));
        }
    }

    @Test void invalidationWhileReadingCannotRestoreTheOldHint() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        seed(key);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new HeaderGate(cache, key)) {
            cache.probeTerrain(List.of(key));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            cache.invalidate(List.of(key));
            gate.release.countDown();
            cache.flush();
            assertEquals(0, cache.cachedTerrainAxis(key));
            try (var lease = cache.lease(key)) { assertNull(cache.readTerrainData(lease, 0)); }
        }
    }

    @Test void closeDoesNotWaitForAProbeAndOldResultsCannotEnterTheNewSession() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        seed(key);
        try (var older = new PredictionDiskCache(directory, 77); var gate = new HeaderGate(older, key)) {
            older.probeTerrain(List.of(key));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), older::close);
            try (var newer = new PredictionDiskCache(directory, 77); var lease = newer.lease(key)) {
                assertTrue(newer.writeTerrain(lease, grid(64)));
                gate.release.countDown();
                awaitProbes(older);
                newer.probeTerrain(List.of(key));
                newer.flush();
                assertEquals(0, older.cachedTerrainAxis(key));
                assertEquals(64, newer.cachedTerrainAxis(key));
                assertEquals(64, newer.readTerrainData(lease, 0).cellAxis());
            }
        }
    }

    @Test void flushWaitsForEveryHeaderBatch() throws Exception {
        var keys = new ArrayList<PredictionDiskCache.Key>();
        for (int x = 0; x < 257; x++) keys.add(PredictionDiskCache.Key.terrain(x, 0, 0));
        var last = keys.get(keys.size() - 1);
        seed(last);
        try (var cache = new PredictionDiskCache(directory, 77)) {
            cache.probeTerrain(keys);
            cache.flush();
            assertEquals(8, cache.cachedTerrainAxis(last));
            assertTrue(keys.stream().noneMatch(cache::terrainProbePending));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void eightRestoresAreAdmittedAndDecodeOnTheConfiguredWorkers() throws Exception {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.tryParse("minecraft:overworld"),
                42, -64, 384, "noise", "minecraft:overworld", 123);
        var context = PredictionCacheStartupTest.cacheSampler(profile);
        int settings = 3 | PredictionVegetation.runtimeSettings(profile.dimension());
        var keys = new ArrayList<PredictionTileManager.PredictionTileKey>();
        try (var disk = new PredictionDiskCache(directory, 77)) {
            for (int x = 0; x < 9; x++) {
                var key = new PredictionTileManager.PredictionTileKey(profile.levelKey(), x, 0, 1);
                keys.add(key);
                try (var lease = disk.lease(PredictionDiskCache.Key.voxels(x, 0, 1))) {
                    assertTrue(disk.writeVoxelsLater(lease, PredictionVoxelCacheTest.tile(key, 64, true),
                            settings, PredictionVoxelCache.identity(context)).get(5, TimeUnit.SECONDS));
                }
            }
        }
        int workers = Runtime.getRuntime().availableProcessors() >= 8 ? 4 : 2;
        var entered = new CountDownLatch(workers);
        var release = new CountDownLatch(1);
        var decoderThreads = ConcurrentHashMap.<Thread>newKeySet();
        var budget = new PredictionMemoryBudget(1024L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var disk = new PredictionDiskCache(directory, 77);
             var manager = new PredictionTileManager(profile.levelKey(), context, budget, disk,
                     new PredictionCpuBudget(() -> 1, System::nanoTime))) {
            var actual = PredictionCacheTestFiles.storage(disk);
            var storage = spy(actual);
            var sharedField = PredictionDiskCache.class.getDeclaredField("shared"); sharedField.setAccessible(true);
            var shared = sharedField.get(disk);
            var regions = shared.getClass().getDeclaredField("regions"); regions.setAccessible(true); regions.set(shared, storage);
            doAnswer(call -> {
                var key = (PredictionDiskCache.Key) call.getArgument(0);
                if (key.kind() == 4) {
                    decoderThreads.add(Thread.currentThread()); entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return call.callRealMethod();
            }).when(storage).read(any());
            for (var key : keys) PredictionCacheStartupTest.setup(manager, key);
            disk.probeTerrain(keys.stream().map(k -> PredictionDiskCache.Key.terrain(k.tileX(), k.tileZ(), k.lod())).toList());
            disk.flush();
            try {
                for (var key : keys) PredictionCacheStartupTest.enqueue(manager, key, true);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(workers, decoderThreads.size(), "independent restores must overlap");
                assertEquals(8, manager.pendingCount(), "running and queued restores share the eight-task cap");
            } finally { release.countDown(); }
            PredictionCacheStartupTest.await(manager);
            assertEquals(8, manager.readyCount());
            assertFalse(manager.generationReady(), "finished-cache restoration does not need a generation worker");
        } finally { release.countDown(); }
        assertEquals(0, budget.usedBytes());
    }

    private static final class HeaderGate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HeaderGate(PredictionDiskCache cache, PredictionDiskCache.Key key) throws Exception {
            var storage = spy(PredictionCacheTestFiles.storage(cache));
            var sharedField = PredictionDiskCache.class.getDeclaredField("shared"); sharedField.setAccessible(true);
            var shared = sharedField.get(cache);
            var regions = shared.getClass().getDeclaredField("regions"); regions.setAccessible(true); regions.set(shared, storage);
            var first = new AtomicBoolean(true);
            doAnswer(call -> {
                byte[] result = (byte[]) call.callRealMethod();
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return result;
            }).when(storage).header(key);
        }
        @Override public void close() { release.countDown(); }
    }
}
