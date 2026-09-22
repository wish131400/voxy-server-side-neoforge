package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PredictionQuadBufferPoolTest {
    @Test void neverOverwritesInFlightAndRejectsOversizedCapacity() {
        var driver = new Fake(); var clock = new AtomicLong();
        try (var pool = new PredictionQuadBufferPool(driver, clock::get)) {
            var first = pool.upload(ByteBuffer.allocate(4096)); pool.retire(first);
            var next = pool.upload(ByteBuffer.allocate(4096));
            assertNotSame(first, next); assertEquals(0, driver.uploads);
            driver.ready.add(first.fence);
            var reused = pool.upload(ByteBuffer.allocate(4000));
            assertSame(first, reused); assertEquals(1, driver.uploads);
            assertEquals(0, reused.fence); assertEquals(0, pool.retainedBytes());
            pool.retire(reused); driver.ready.add(reused.fence);
            var smaller = pool.upload(ByteBuffer.allocate(1024));
            assertNotSame(reused, smaller, "shrinking must not retain a large live allocation");
            pool.discard(next); pool.discard(smaller);
            clock.set(PredictionQuadBufferPool.MAX_IDLE_NANOS); pool.trim();
            assertEquals(0, pool.retainedCount()); assertEquals(0, driver.alive.size());
        }
    }

    @Test void enforcesByteAndObjectLimitsAndReleasesEvenUnsignaledResources() {
        var driver = new Fake();
        try (var pool = new PredictionQuadBufferPool(driver, () -> 0L)) {
            for (int i = 0; i < 20; i++) pool.retire(driver.allocation(1024 * 1024));
            assertEquals(PredictionQuadBufferPool.MAX_BYTES, pool.retainedBytes());
            assertEquals(16, pool.retainedCount());
            pool.close(); assertTrue(driver.alive.isEmpty());
            for (int i = 0; i < 200; i++) pool.retire(driver.allocation(48));
            assertEquals(PredictionQuadBufferPool.MAX_ENTRIES, pool.retainedCount());
        }
        assertTrue(driver.alive.isEmpty()); assertTrue(driver.fences.isEmpty());
    }

    @Test void pollsAtMostEightFencesAndRemovesBrokenUploadFromPool() {
        var driver = new Fake();
        try (var pool = new PredictionQuadBufferPool(driver, () -> 0L)) {
            for (int i = 0; i < 20; i++) pool.retire(driver.allocation(48));
            var fresh = pool.upload(ByteBuffer.allocate(48));
            assertEquals(8, driver.polls); pool.discard(fresh);
            driver.ready.addAll(driver.fences); driver.failUpload = true;
            assertThrows(IllegalStateException.class, () -> pool.upload(ByteBuffer.allocate(48)));
            assertEquals(19, pool.retainedCount());
        }
        assertTrue(driver.alive.isEmpty()); assertTrue(driver.fences.isEmpty());
    }

    @Test void instanceSelectionMatchesOriginalQuadOrderForAllFaceMasks() {
        int[] first = {7, 9, 12, 16, 21}, count = {2, 3, 4, 5, 6};
        for (int mask = 0; mask < 32; mask++) {
            var ranges = new PredictionDrawRanges(first, count, mask);
            var expected = new java.util.ArrayList<Integer>();
            for (int g = 0; g < 5; g++) if ((mask & (1 << g)) != 0)
                for (int q = first[g]; q < first[g] + count[g]; q++) expected.add(q);
            int[] pairs = PredictionInstanceExperiment.pairs(ranges);
            var actual = new java.util.ArrayList<Integer>();
            for (int i = 0; i < ranges.quads; i++) for (int r = 0; r < ranges.first.length; r++)
                if (i < pairs[r * 2]) { actual.add(i + pairs[r * 2 + 1]); break; }
            assertEquals(expected, actual);
        }
    }

    private static final class Fake implements PredictionQuadBufferPool.Driver {
        int ids, uploads, polls;
        long sync;
        boolean failUpload;
        final HashSet<Integer> alive = new HashSet<>();
        final HashSet<Long> fences = new HashSet<>(), ready = new HashSet<>();
        PredictionQuadBufferPool.Allocation allocation(int capacity) {
            alive.add(++ids); return new PredictionQuadBufferPool.Allocation(ids, ids, capacity);
        }
        public PredictionQuadBufferPool.Allocation create(ByteBuffer data) { return allocation(data.remaining()); }
        public void upload(PredictionQuadBufferPool.Allocation allocation, ByteBuffer data) {
            assertEquals(0, allocation.fence); assertTrue(alive.contains(allocation.buffer));
            assertTrue(data.remaining() <= allocation.capacity); uploads++;
            if (failUpload) throw new IllegalStateException("injected upload failure");
        }
        public long fence() { fences.add(++sync); return sync; }
        public boolean ready(long fence) { polls++; return ready.contains(fence); }
        public void deleteFence(long fence) { assertTrue(fences.remove(fence)); }
        public void delete(PredictionQuadBufferPool.Allocation allocation) { assertTrue(alive.remove(allocation.buffer)); }
    }
}
