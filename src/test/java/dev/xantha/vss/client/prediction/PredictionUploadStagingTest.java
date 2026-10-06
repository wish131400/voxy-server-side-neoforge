package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionUploadStagingTest {
    static class Allocator implements PredictionUploadStaging.Allocator {
        final Set<ByteBuffer> live = Collections.newSetFromMap(new IdentityHashMap<>());
        int allocations;
        public synchronized ByteBuffer allocate(int bytes) {
            ByteBuffer buffer = ByteBuffer.allocate(bytes);
            live.add(buffer); allocations++; return buffer;
        }
        public synchronized void free(ByteBuffer buffer) { assertTrue(live.remove(buffer), "double free / foreign buffer"); }
    }
    private static int[] words(ByteBuffer bytes) {
        int[] words = new int[bytes.remaining() / 4]; bytes.asIntBuffer().get(words); return words;
    }

    @Test void exactNativeOrderOpaqueMorphPaddingAndWaterSurviveSourceReuse() {
        var allocator = new Allocator(); var pool = new PredictionUploadStaging(8192, 2, allocator);
        Object key = new Object(); int[] opaque = {1, -1, Integer.MIN_VALUE, 0x12345678};
        int[] water = {7, 8}; float[] morph = {1.25F, -0.5F, 0};
        assertTrue(pool.stage(key, opaque, water, morph, pool.epoch()));
        opaque[0] = 99; water[0] = 99; morph[0] = 99;
        try (var upload = pool.take(key)) {
            assertEquals(ByteOrder.nativeOrder(), upload.opaque().order());
            assertArrayEquals(new int[]{1,-1,Integer.MIN_VALUE,0x12345678,320,-128,0,0}, words(upload.opaque()));
            assertArrayEquals(new int[]{7,8}, words(upload.water()));
            upload.opaque().position(4);
            assertEquals(0, upload.opaque().position(), "views have independent positions");
        }
        pool.clear(); assertEquals(0, pool.allocatedBytes()); assertTrue(allocator.live.isEmpty());
    }

    @Test void sequentialUploadsReuseOneAllocationAndOverwriteMorphPadding() {
        var allocator = new Allocator(); var pool = new PredictionUploadStaging(4096, 1, allocator);
        for (int i = 0; i < 1000; i++) {
            Object key = new Object();
            assertTrue(pool.stage(key, new int[]{i}, new int[0], new float[]{i}, pool.epoch()));
            try (var upload = pool.take(key)) {
                assertArrayEquals(new int[]{i, i * 256, 0, 0, 0}, words(upload.opaque()));
                assertNull(upload.water());
            }
        }
        assertEquals(1, allocator.allocations); assertEquals(4096, pool.allocatedBytes());
        pool.clear(); assertTrue(allocator.live.isEmpty());
    }

    @Test void fullPoolNeverWaitsForInFlightUploadsOrOverwritesThem() {
        var allocator = new Allocator(); var pool = new PredictionUploadStaging(8192, 2, allocator);
        assertTrue(pool.stage("a", new int[]{1}, new int[0], null, pool.epoch()));
        var a = pool.take("a");
        assertTrue(pool.stage("b", new int[0], new int[]{2}, null, pool.epoch()));
        var b = pool.take("b");
        try {
            assertFalse(pool.stage("c", new int[]{3}, new int[0], null, pool.epoch()));
            assertEquals(8192, pool.allocatedBytes());
            assertArrayEquals(new int[]{1}, words(a.opaque()));
            assertEquals(0, b.opaque().remaining()); assertArrayEquals(new int[]{2}, words(b.water()));
            a.close();
            assertTrue(pool.stage("c", new int[]{3}, new int[0], null, pool.epoch()));
            assertArrayEquals(new int[]{2}, words(b.water()));
        } finally { a.close(); b.close(); pool.clear(); }
        assertTrue(allocator.live.isEmpty());
    }

    @Test void unconsumedPredictionsAreEvictableAndOversizeDoesNotDisturbReadyData() {
        var allocator = new Allocator(); var pool = new PredictionUploadStaging(8192, 2, allocator);
        for (int i = 0; i < 100; i++) {
            assertTrue(pool.stage(i, new int[]{i}, new int[0], null, pool.epoch()));
            assertTrue(pool.allocatedBytes() <= 8192);
        }
        assertFalse(pool.contains(0)); assertTrue(pool.contains(99));
        assertFalse(pool.stage("large", new int[4096], new int[0], null, pool.epoch()));
        assertTrue(pool.contains(99)); assertEquals(2, allocator.allocations);
        pool.clear(); assertEquals(0, pool.allocatedBytes());
    }

    @Test void clearFreesQueuedMemoryButUploadLeaseSurvivesUntilReturn() {
        var allocator = new Allocator(); var pool = new PredictionUploadStaging(8192, 2, allocator);
        long oldEpoch = pool.epoch();
        assertTrue(pool.stage("active", new int[]{27}, new int[0], null, oldEpoch));
        var upload = pool.take("active");
        assertTrue(pool.stage("queued", new int[]{19}, new int[0], null, oldEpoch));
        pool.clear();
        assertEquals(4096, pool.allocatedBytes());
        assertArrayEquals(new int[]{27}, words(upload.opaque()));
        assertFalse(pool.stage("old worker", new int[]{1}, new int[0], null, oldEpoch));
        upload.close(); upload.close();
        assertEquals(0, pool.allocatedBytes()); assertTrue(allocator.live.isEmpty());
        assertThrows(IllegalStateException.class, upload::opaque);
    }

    @Test void resetWhileWorkerAllocatesDoesNotBlockRenderOrPublishOldWorld() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var allocator = new Allocator() {
            @Override public ByteBuffer allocate(int bytes) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return super.allocate(bytes);
            }
        };
        var pool = new PredictionUploadStaging(4096, 1, allocator);
        var executor = Executors.newSingleThreadExecutor();
        try {
            long epoch = pool.epoch();
            var job = executor.submit(() -> pool.stage("old", new int[]{1}, new int[0], null, epoch));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), pool::clear);
                assertNull(pool.take("old")); assertEquals(4096, pool.allocatedBytes());
                assertFalse(pool.stage("new", new int[]{2}, new int[0], null, pool.epoch()));
            } finally { release.countDown(); }
            assertFalse(job.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertEquals(0, pool.allocatedBytes()); assertTrue(allocator.live.isEmpty());
    }

    @Test void discardedWorkerCannotReplaceNewPreparationForSameKey() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        var allocator = new Allocator() {
            @Override public ByteBuffer allocate(int bytes) {
                if (first.getAndSet(false)) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
                return super.allocate(bytes);
            }
        };
        var pool = new PredictionUploadStaging(8192, 2, allocator);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> pool.stage("same", new int[]{1}, new int[0], null, pool.epoch()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); pool.discard("same");
                assertTrue(pool.stage("same", new int[]{2}, new int[0], null, pool.epoch()));
            } finally { release.countDown(); }
            assertFalse(old.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        try (var upload = pool.take("same")) { assertArrayEquals(new int[]{2}, words(upload.opaque())); }
        pool.clear(); assertTrue(allocator.live.isEmpty());
    }

    @Test void failedNativeAllocationReleasesReservation() {
        var allocator = new Allocator() {
            @Override public ByteBuffer allocate(int bytes) { throw new OutOfMemoryError("test allocation failure"); }
        };
        var pool = new PredictionUploadStaging(4096, 1, allocator);
        assertFalse(pool.stage("a", new int[]{1}, new int[0], null, pool.epoch()));
        assertEquals(0, pool.allocatedBytes()); assertFalse(pool.contains("a")); pool.clear();
    }
}
