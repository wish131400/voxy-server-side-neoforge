package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionDeferredCacheWriteTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @org.junit.jupiter.api.AfterEach void awaitBackgroundClose() throws Exception {
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    @Test void supersededStagesAreNotEncodedAndLatestArraysAreDetached() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new DiskGate()) {
            var futures = new ArrayList<CompletableFuture<Boolean>>();
            for (int axis : new int[]{8, 16, 32}) try (var lease = cache.lease(key)) {
                futures.add(cache.writeTerrainLater(lease, terrain(axis, 64)));
            }
            var latest = terrain(64, 70);
            var expected = latest.samples().clone();
            CompletableFuture<Boolean> finalWrite;
            try (var lease = cache.lease(key)) { finalWrite = cache.writeTerrainLater(lease, latest); }
            latest.samples()[0] = column(200);
            latest.surfaceTints()[0] = 0;
            for (var future : futures) assertFalse(future.get(5, TimeUnit.SECONDS));
            try (var files = Files.walk(directory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("pending-")),
                        "the builder must not compress or create staging files");
            }
            assertTrue(cache.diagnostics().contains("deferredEncodes=0"));
            gate.release();
            assertTrue(finalWrite.get(5, TimeUnit.SECONDS));
            assertTrue(cache.diagnostics().contains("deferredEncodes=1"));
            assertTrue(cache.diagnostics().contains("coalescedWrites=3"));
            try (var lease = cache.lease(key)) {
                var restored = cache.readTerrainData(lease, expected.length);
                assertArrayEquals(expected, restored.samples());
                assertEquals(0x123456, restored.surfaceTints()[0]);
            }
        }
    }

    @Test void aLaterCoarseResultCannotOverwriteFineQueuedOrCommittedTerrain() throws Exception {
        var key = PredictionDiskCache.Key.terrain(1, 0, 0);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new DiskGate()) {
            CompletableFuture<Boolean> fine;
            try (var lease = cache.lease(key)) {
                fine = cache.writeTerrainLater(lease, terrain(32, 70));
                assertFalse(cache.writeTerrainLater(lease, terrain(8, 60)).join());
            }
            gate.release();
            assertTrue(fine.get(5, TimeUnit.SECONDS));
            try (var lease = cache.lease(key)) {
                assertFalse(cache.writeTerrainLater(lease, terrain(16, 60)).join());
                assertEquals(32, cache.readTerrainData(lease, 0).cellAxis());
            }
        }
    }

    @Test void invalidationBeforeEncodingKeepsTheNewCaptureAfterDeletion() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 1, 0);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new DiskGate()) {
            CompletableFuture<Boolean> old;
            try (var lease = cache.lease(key)) { old = cache.writeTerrainLater(lease, terrain(32, 70)); }
            cache.invalidate(List.of(key));
            CompletableFuture<Boolean> current;
            try (var lease = cache.lease(key)) { current = cache.writeTerrainLater(lease, terrain(8, 90)); }
            assertFalse(old.get(5, TimeUnit.SECONDS));
            gate.release();
            assertTrue(current.get(5, TimeUnit.SECONDS));
            try (var lease = cache.lease(key)) {
                var restored = cache.readTerrainData(lease, 0);
                assertEquals(8, restored.cellAxis());
                assertEquals(90, restored.samples()[0].surfaceY());
            }
        }
    }

    @Test void closeCancelsSnapshotsWithoutWaitingForBlockedDiskIo() throws Exception {
        var cache = new PredictionDiskCache(directory, 77);
        try (var gate = new DiskGate()) {
            CompletableFuture<Boolean> pending;
            try (var lease = cache.lease(PredictionDiskCache.Key.terrain(0, 0, 0))) {
                pending = cache.writeTerrainLater(lease, terrain(32, 70));
            }
            cache.close();
            assertFalse(pending.get(5, TimeUnit.SECONDS));
            assertTrue(cache.diagnostics().contains("terrainPending=0"));
            assertTrue(cache.diagnostics().contains("terrainPendingBytes=0"));
            gate.release();
            cache.flush();
        } finally { cache.close(); cache.flush(); }
        try (var cache2 = new PredictionDiskCache(directory, 77);
             var lease = cache2.lease(PredictionDiskCache.Key.terrain(0, 0, 0))) {
            assertNull(cache2.readTerrainData(lease, 0));
        }
    }

    @Test void aNewOwnerRejectsThePreviousSessionsQueuedSnapshot() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var old = new PredictionDiskCache(directory, 77); var gate = new DiskGate()) {
            CompletableFuture<Boolean> stale;
            try (var lease = old.lease(key)) { stale = old.writeTerrainLater(lease, terrain(32, 70)); }
            try (var current = new PredictionDiskCache(directory, 77)) {
                CompletableFuture<Boolean> fresh;
                try (var lease = current.lease(key)) { fresh = current.writeTerrainLater(lease, terrain(8, 90)); }
                gate.release();
                assertFalse(stale.get(5, TimeUnit.SECONDS));
                assertTrue(fresh.get(5, TimeUnit.SECONDS));
                try (var lease = current.lease(key)) {
                    assertEquals(90, current.readTerrainData(lease, 0).samples()[0].surfaceY());
                }
            }
        }
    }

    @Test void surfaceSnapshotCopiesMutablePositionsAndPreservesCanonicalMarker() throws Exception {
        var key = PredictionDiskCache.Key.surface(0, 0, 3);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new DiskGate()) {
            var position = new BlockPos.MutableBlockPos(1, 64, 2);
            var blocks = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
            blocks.put(position, Blocks.OAK_LEAVES.defaultBlockState());
            CompletableFuture<Boolean> write;
            try (var lease = cache.lease(key)) { write = cache.writeSurfaceLater(lease, blocks, true); }
            blocks.clear();
            position.set(99, 99, 99);
            gate.release();
            assertTrue(write.get(5, TimeUnit.SECONDS));
            try (var lease = cache.lease(key)) {
                var surface = cache.readSurfaceData(lease);
                assertEquals(Blocks.OAK_LEAVES.defaultBlockState(), surface.blocks().get(new BlockPos(1, 64, 2)));
                assertTrue(surface.canonical());
                assertTrue(surface.weatherChecked());
            }
        }
    }

    @Test void queueAdmissionIsBoundedAndCoalescingWorksAtTheCountLimit() throws Exception {
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new DiskGate()) {
            var writes = new ArrayList<CompletableFuture<Boolean>>();
            for (int x = 0; x < PredictionDiskCache.MAX_PENDING_TERRAIN_WRITES; x++)
                try (var lease = cache.lease(PredictionDiskCache.Key.terrain(x, 0, 0))) {
                    writes.add(cache.writeTerrainLater(lease, terrain(8, 70)));
                }
            try (var lease = cache.lease(PredictionDiskCache.Key.terrain(100, 0, 0))) {
                assertFalse(cache.writeTerrainLater(lease, terrain(8, 70)).join());
            }
            CompletableFuture<Boolean> newest;
            try (var lease = cache.lease(PredictionDiskCache.Key.terrain(0, 0, 0))) {
                newest = cache.writeTerrainLater(lease, terrain(16, 90));
            }
            assertFalse(writes.remove(0).get(5, TimeUnit.SECONDS));
            gate.release();
            assertTrue(newest.get(10, TimeUnit.SECONDS));
            for (var write : writes) assertTrue(write.get(10, TimeUnit.SECONDS));
            cache.flush();
            assertTrue(cache.diagnostics().contains("terrainPending=0"));
            assertTrue(cache.diagnostics().contains("terrainPendingBytes=0"));
        }
    }

    @Test void byteBudgetRejectsOversizedVolumeSnapshotsBeforeCopyingOrEncoding() throws Exception {
        int[] runs = new int[PredictionColumnVolume.MAX_RUNS * 4];
        int block = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        for (int index = 0; index < PredictionColumnVolume.MAX_RUNS; index++) {
            runs[index * 4] = index * 2;
            runs[index * 4 + 1] = index * 2 + 1;
            runs[index * 4 + 2] = block;
        }
        var dense = new PredictionColumnVolume(runs).asSample();
        var data = terrain(64, 70);
        Arrays.fill(data.samples(), dense);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new DiskGate();
             var lease = cache.lease(PredictionDiskCache.Key.terrain(0, 0, 0))) {
            assertFalse(cache.writeTerrainLater(lease, data).join());
            assertTrue(cache.diagnostics().contains("terrainWriteDeferrals=1"));
            assertTrue(cache.diagnostics().contains("terrainPending=0"));
            assertTrue(cache.diagnostics().contains("terrainPendingBytes=0"));
            assertTrue(cache.diagnostics().contains("deferredEncodes=0"));
        }
    }

    @Test void flushThenClosePreservesTheLatestAcceptedRecordForReentry() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        var cache = new PredictionDiskCache(directory, 77);
        try {
            CompletableFuture<Boolean> write;
            try (var lease = cache.lease(key)) { write = cache.writeTerrainLater(lease, terrain(32, 90)); }
            cache.flush();
            assertTrue(write.getNow(false));
        } finally { cache.close(); cache.flush(); }
        try (var reopened = new PredictionDiskCache(directory, 77); var lease = reopened.lease(key)) {
            var data = reopened.readTerrainData(lease, 0);
            assertNotNull(data);
            assertEquals(32, data.cellAxis());
            assertEquals(90, data.samples()[0].surfaceY());
        }
    }

    static PredictionDiskCache.TerrainData terrain(int axis, int height) {
        int count = (axis + 2) * (axis + 2);
        var samples = new ClientColumnSample[count];
        Arrays.fill(samples, column(height));
        int[] surface = new int[count], foliage = new int[count], water = new int[count];
        Arrays.fill(surface, 0x123456);
        Arrays.fill(foliage, 0x234567);
        Arrays.fill(water, 0x345678);
        return new PredictionDiskCache.TerrainData(samples, 1234L, surface, foliage, water);
    }

    static ClientColumnSample column(int height) {
        int block = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        return new ClientColumnSample(height, height, 0, block, 0, 0, 0, 0, 0, 0, 0, block, block,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN);
    }

    static final class DiskGate implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> task;
        DiskGate() throws Exception {
            var field = PredictionDiskCache.class.getDeclaredField("COMMITS");
            field.setAccessible(true);
            var entered = new CountDownLatch(1);
            task = ((ExecutorService) field.get(null)).submit(() -> {
                entered.countDown();
                release.await();
                return true;
            });
            if (!entered.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("disk gate did not start");
        }
        void release() { release.countDown(); }
        @Override public void close() throws Exception { release(); task.get(5, TimeUnit.SECONDS); }
    }
}
