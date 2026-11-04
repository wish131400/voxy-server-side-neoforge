package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictionSharedCacheTest {
    @Test void unrelatedStartsBuildConcurrentlyAndSameStartIsShared() throws Exception {
        var cache = new PredictionSharedCache<String, Object>(8);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(3);
        Object value = new Object();
        try {
            var first = workers.submit(() -> cache.get("village", () -> {
                calls.incrementAndGet(); entered.countDown(); await(release); return value;
            }, () -> {}));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var same = workers.submit(() -> cache.get("village", () -> { calls.incrementAndGet(); return new Object(); }, () -> {}));
            var other = workers.submit(() -> cache.get("temple", Object::new, () -> {}));
            assertNotNull(other.get(5, TimeUnit.SECONDS), "A slow village must not lock another structure start");
            assertFalse(first.isDone());
            release.countDown();
            assertSame(value, first.get(5, TimeUnit.SECONDS));
            assertSame(value, same.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void cancelledProducerDoesNotCancelAnotherTilesDemand() throws Exception {
        var cache = new PredictionSharedCache<String, Integer>(4);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> assertThrows(CancellationException.class, () -> cache.get("page", () -> {
                entered.countDown(); await(release); throw new CancellationException();
            }, () -> {})));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = workers.submit(() -> cache.get("page", () -> 42, waiting::countDown));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertEquals(42, second.get(5, TimeUnit.SECONDS));
            assertEquals(42, cache.get("page", () -> fail("successful page must be reused"), () -> {}));
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void cancellationAndFailuresNeverPoisonTheCache() {
        var cache = new PredictionSharedCache<Integer, Integer>(2);
        assertThrows(PredictionWorkDeferred.class, () -> cache.get(0, () -> { throw new PredictionWorkDeferred(); }, () -> {}));
        assertThrows(IllegalStateException.class, () -> cache.get(0, () -> { throw new IllegalStateException(); }, () -> {}));
        assertThrows(CancellationException.class, () -> cache.get(0, () -> 1, () -> { throw new CancellationException(); }));
        assertEquals(0, cache.size());
        assertEquals(2, cache.get(0, () -> 2, () -> {}));
    }

    @Test void successfulEntriesUseBoundedLeastRecentlyUsedEviction() {
        var cache = new PredictionSharedCache<Integer, Integer>(2);
        for (int i = 0; i < 2; i++) { final int n = i; cache.get(i, () -> n, () -> {}); }
        assertEquals(0, cache.get(0, () -> fail("recent result"), () -> {}));
        cache.get(2, () -> 2, () -> {});
        assertEquals(2, cache.size());
        assertEquals(3, cache.get(1, () -> 3, () -> {}));
        assertEquals(2, cache.size());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
}
