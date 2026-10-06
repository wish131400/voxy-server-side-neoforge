package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StrictVoxyCoverageCacheTest {
    @Test void overlappingTilesReuseCompleteRangesWhileMissingSectionsStayMissing() {
        var cache = new StrictVoxyCoverageCache();
        AtomicInteger calls = new AtomicInteger();
        StrictVoxyCoverageCache.Query query = (x, minY, maxY, z) -> { calls.incrementAndGet(); return minY >= -4 && maxY <= 20; };
        assertTrue(cache.covers(-1, -4, 20, 2, 1, query));
        for (int tile = 0; tile < 1286; tile++) assertTrue(cache.covers(-1, 0, 10, 2, 1, query));
        assertEquals(1, calls.get());
        assertFalse(cache.covers(-1, -4, 21, 2, 1, query));
        assertFalse(cache.covers(-1, 21, -4, 2, 1, query));
        assertTrue(cache.covers(-1, -3, 19, 2, 1, query));
        assertEquals(2, calls.get(), "a failed broad range must not discard an already proved subrange");
        assertFalse(cache.covers(-1, -5, -4, 2, 1, query));
        assertEquals(3, calls.get());
    }

    @Test void unrelatedColumnWorkDoesNotFlushWarmAnswersAndLocalChangesRecheckImmediately() {
        var cache = new StrictVoxyCoverageCache();
        var changes = new StrictVoxyCoverageChanges();
        AtomicInteger calls = new AtomicInteger();
        boolean[] ready = {true};
        StrictVoxyCoverageCache.Query query = (x, minY, maxY, z) -> { calls.incrementAndGet(); return ready[0]; };
        assertTrue(cache.covers(0, -4, 20, 0, changes.columnRevision(0, 0), query));
        for (int edit = 0; edit < 1000; edit++) {
            changes.columnChanged(400, 400);
            assertTrue(cache.covers(0, -4, 20, 0, changes.columnRevision(0, 0), query));
        }
        assertEquals(1, calls.get());
        ready[0] = false;
        changes.nodeChanged(StrictVoxyNodeIndex.key(4, 0, 0, 0));
        assertFalse(cache.covers(0, -4, 20, 0, changes.columnRevision(0, 0), query));
        assertEquals(2, calls.get());
        ready[0] = true;
        changes.invalidateAll();
        assertTrue(cache.covers(0, -4, 20, 0, changes.columnRevision(0, 0), query));
        assertEquals(3, calls.get());
    }

    @Test void noncontiguousTrueRangesCannotHideAGapAndStorageIsBounded() {
        var cache = new StrictVoxyCoverageCache(2);
        AtomicInteger calls = new AtomicInteger();
        StrictVoxyCoverageCache.Query query = (x, first, last, z) -> { calls.incrementAndGet(); return last <= -2 || first >= 2; };
        assertTrue(cache.covers(0, -4, -2, 0, 1, query));
        assertTrue(cache.covers(0, 2, 4, 0, 1, query));
        assertFalse(cache.covers(0, -4, 4, 0, 1, query));
        assertEquals(3, calls.get());
        cache.covers(1, 2, 4, 0, 1, query);
        cache.covers(2, 2, 4, 0, 1, query);
        assertEquals(2, cache.size());
        cache.covers(0, -4, 4, 0, 1, query);
        assertEquals(6, calls.get(), "a replaced column must be recomputed after eviction");
    }
    @Test void repeatedBoundaryCrossingsAndStreamingReplacementStayBoundedAndMatchFreshQueries() {
        var cache = new StrictVoxyCoverageCache(17);
        var random = new java.util.Random(981);
        StrictVoxyCoverageCache.Query query = (x, first, last, z) -> first >= -4 && last <= 20 && ((x ^ z) & 1) == 0;
        for (int sample = 0; sample < 50_000; sample++) {
            int x = random.nextInt(100) - 50, z = random.nextInt(100) - 50;
            int first = random.nextInt(40) - 10, last = random.nextInt(40) - 10;
            long version = sample / 50;
            assertEquals(query.covers(x, Math.min(first, last), Math.max(first, last), z),
                    cache.covers(x, first, last, z, version, query));
            assertTrue(cache.size() <= 17);
        }
        assertTrue(cache.diagnostics().contains("replacement=clock4"));
    }

    @Test void versionResetDiscardsOldSuccessfulRangesEvenWhenTheColumnSlotIsReused() {
        var cache = new StrictVoxyCoverageCache(1);
        assertTrue(cache.covers(0, -4, 20, 0, 1, (x, a, b, z) -> true));
        assertFalse(cache.covers(0, 0, 10, 0, 2, (x, a, b, z) -> false));
        assertFalse(cache.covers(0, 0, 10, 0, 2, (x, a, b, z) -> { fail("exact failure should be cached"); return true; }));
        assertTrue(cache.covers(1, 0, 10, 0, 2, (x, a, b, z) -> true));
        assertFalse(cache.covers(0, 0, 10, 0, 2, (x, a, b, z) -> false));
        assertEquals(1, cache.size());
    }

    @Test void streamingPastCapacityDoesNotAllocateAColumnOnEachMiss() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        var allocation = (com.sun.management.ThreadMXBean) bean;
        org.junit.jupiter.api.Assumptions.assumeTrue(allocation.isThreadAllocatedMemorySupported());
        allocation.setThreadAllocatedMemoryEnabled(true);
        var cache = new StrictVoxyCoverageCache(1024);
        StrictVoxyCoverageCache.Query query = (x, first, last, z) -> true;
        for (int x = 0; x < 200_000; x++) cache.covers(x, -4, 20, -x, 1, query);
        long thread = Thread.currentThread().getId();
        long before = allocation.getThreadAllocatedBytes(thread);
        for (int x = 200_000; x < 400_000; x++) cache.covers(x, -4, 20, -x, 1, query);
        long bytes = allocation.getThreadAllocatedBytes(thread) - before;
        assertTrue(bytes < 512_000, "streaming replacement allocated " + bytes + " bytes for 200,000 queries");
        assertTrue(cache.size() <= 1024);
        System.out.println("STREAMING_COLUMN_ALLOCATION queries=200000 bytes=" + bytes);
    }
}
