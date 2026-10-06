package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionAsyncTerrainCacheTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final Future<?> task;
        Gate() throws Exception {
            var field = PredictionDiskCache.class.getDeclaredField("COMMITS");
            field.setAccessible(true);
            task = ((ExecutorService) field.get(null)).submit(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }
        @Override public void close() throws Exception { release.countDown(); task.get(5, TimeUnit.SECONDS); }
    }

    static PredictionDiskCache.TerrainData data() {
        var samples = new ClientColumnSample[66 * 66];
        Arrays.fill(samples, PredictionSimpleVegetationTest.sample(120));
        return new PredictionDiskCache.TerrainData(samples, Long.MIN_VALUE, null, null, null);
    }

    @AfterEach void drain() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void builderLeaseCanCloseBeforeQueuedTerrainCommits() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var cache = new PredictionDiskCache(directory, 77)) {
            CompletableFuture<Boolean> pending;
            try (var gate = new Gate(); var lease = cache.lease(key)) {
                pending = cache.writeTerrainLater(lease, data());
                assertFalse(pending.isDone(), "builder must return while the commit thread is busy");
            }
            assertTrue(pending.get(5, TimeUnit.SECONDS));
            try (var lease = cache.lease(key)) { assertArrayEquals(data().samples(), cache.readTerrain(lease, 0)); }
        }
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            assertArrayEquals(data().samples(), cache.readTerrain(lease, 0));
        }
    }

    @Test void dirtyAfterEnqueueCannotResurrectTerrain() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var cache = new PredictionDiskCache(directory, 77)) {
            CompletableFuture<Boolean> pending;
            try (var gate = new Gate(); var lease = cache.lease(key)) {
                pending = cache.writeTerrainLater(lease, data());
                cache.invalidate(List.of(key));
            }
            assertFalse(pending.get(5, TimeUnit.SECONDS));
            cache.flush();
            try (var lease = cache.lease(key)) { assertNull(cache.readTerrain(lease, 0)); }
        }
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            assertNull(cache.readTerrain(lease, 0));
        }
    }

    @Test void reconnectRejectsOldQueuedWriterAndKeepsNewSession() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var older = new PredictionDiskCache(directory, 77)) {
            CompletableFuture<Boolean> pending;
            PredictionDiskCache newer;
            try (var gate = new Gate(); var lease = older.lease(key)) {
                pending = older.writeTerrainLater(lease, data());
                newer = new PredictionDiskCache(directory, 77);
                older.close();
            }
            try (newer; var lease = newer.lease(key)) {
                assertFalse(pending.get(5, TimeUnit.SECONDS));
                assertTrue(newer.writeTerrainLater(lease, data()).get(5, TimeUnit.SECONDS));
                assertArrayEquals(data().samples(), newer.readTerrain(lease, 0));
            }
        }
    }

    @Test void closingCacheCancelsPendingWritesAndDeletesTemporaryFiles() throws Exception {
        var cache = new PredictionDiskCache(directory, 77);
        CompletableFuture<Boolean> pending;
        try (var gate = new Gate(); var lease = cache.lease(PredictionDiskCache.Key.terrain(0, 0, 0))) {
            pending = cache.writeTerrainLater(lease, data());
            cache.close();
        }
        assertFalse(pending.get(5, TimeUnit.SECONDS));
        cache.flush();
        try (var paths = Files.walk(directory)) { assertFalse(paths.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }

    @Test void fullQueueDeclinesWithoutBlockingOrRetainingSamples() throws Exception {
        try (var cache = new PredictionDiskCache(directory, 77)) {
            var pending = new ArrayList<CompletableFuture<Boolean>>();
            try (var gate = new Gate()) {
                for (int x = 0; x < PredictionDiskCache.MAX_PENDING_TERRAIN_WRITES; x++) {
                    try (var lease = cache.lease(PredictionDiskCache.Key.terrain(x, 0, 0))) {
                        pending.add(cache.writeTerrainLater(lease, data()));
                        assertFalse(pending.get(x).isDone());
                    }
                }
                try (var lease = cache.lease(PredictionDiskCache.Key.terrain(999, 0, 0))) {
                    assertFalse(cache.writeTerrainLater(lease, data()).getNow(true));
                }
            }
            for (var future : pending) assertTrue(future.get(5, TimeUnit.SECONDS));
            cache.flush();
            assertTrue(cache.diagnostics().contains("terrainPending=0"));
            assertTrue(cache.diagnostics().contains("terrainPendingBytes=0"));
        }
    }

    @Test void surfaceCommitDoesNotBlockBuilderAndDirtyDataCannotReturnAfterReconnect() throws Exception {
        var key = PredictionDiskCache.Key.surface(-1, -2, 17);
        var blocks = Map.of(new net.minecraft.core.BlockPos(-16, 120, -32),
                net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
        try (var cache = new PredictionDiskCache(directory, 77)) {
            CompletableFuture<Boolean> pending;
            try (var gate = new Gate(); var lease = cache.lease(key)) {
                pending = cache.writeSurfaceLater(lease, blocks, true);
                assertFalse(pending.isDone(), "surface replay must not wait for disk force");
            }
            assertTrue(pending.get(5, TimeUnit.SECONDS));
            try (var lease = cache.lease(key)) { assertEquals(blocks, cache.readSurface(lease)); }
            try (var gate = new Gate(); var lease = cache.lease(key)) {
                pending = cache.writeSurfaceLater(lease, blocks, true);
                cache.invalidate(List.of(key));
            }
            assertFalse(pending.get(5, TimeUnit.SECONDS));
            cache.flush();
        }
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            assertNull(cache.readSurface(lease));
            assertTrue(cache.diagnostics().contains("errors=0"));
        }
    }

    @Test void worldEditsInvalidateOldAndCorrectedPredicateSurfacesIncludingInteriorNeighbors() throws Exception {
        var blocks = Map.of(new net.minecraft.core.BlockPos(-16, 120, -32),
                net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
        var keys = new ArrayList<PredictionDiskCache.Key>();
        for (int predicate : new int[]{0, PredictionVegetation.PREDICATE_SETTINGS_VERSION}) {
            for (int interior : new int[]{0, 96}) keys.add(PredictionDiskCache.Key.surface(-3, 0, 17 | predicate | interior));
        }
        try (var cache = new PredictionDiskCache(directory, 77)) {
            for (var key : keys) try (var lease = cache.lease(key)) {
                assertTrue(cache.writeSurfaceLater(lease, blocks, true).get(5, TimeUnit.SECONDS));
            }
            cache.invalidateCapture(-1, -2);
            cache.flush();
            for (var key : keys) try (var lease = cache.lease(key)) { assertEquals(blocks, cache.readSurface(lease)); }
            cache.invalidateChunk(-1, -2);
            cache.flush();
        }
        try (var cache = new PredictionDiskCache(directory, 77)) {
            for (var key : keys) try (var lease = cache.lease(key)) { assertNull(cache.readSurface(lease)); }
        }
    }
}
