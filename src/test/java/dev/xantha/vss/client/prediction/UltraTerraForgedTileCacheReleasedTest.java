package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises the two production Mixins on the released UTF cache and generator. */
final class UltraTerraForgedTileCacheReleasedTest {
    static void verify(Object context) throws Exception {
        Object cache = context.getClass().getField("cache").get(context);
        Object generator = context.getClass().getField("generator").get(context);
        var heightmap = generator.getClass().getDeclaredField("heightmap"); heightmap.setAccessible(true);
        var queue = cache.getClass().getDeclaredField("queue"); queue.setAccessible(true);
        var batchesField = generator.getClass().getDeclaredField("batchCount"); batchesField.setAccessible(true);
        int batchesPerTile = batchesField.getInt(generator) * batchesField.getInt(generator);
        Object original = heightmap.get(generator); boolean originalQueue = queue.getBoolean(cache);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true); var batches = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "vss-utf-cache-regression"); thread.setDaemon(true); return thread;
        });
        try {
            heightmap.set(generator, new ThreadLocal<>() {
                @Override public Object get() {
                    batches.incrementAndGet();
                    if (first.compareAndSet(true, false)) {
                        entered.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("batch gate timed out"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    }
                    // Fail fast after the gate: this test checks scheduling, not noise accuracy.
                    throw new IllegalStateException("controlled UTF batch failure");
                }
            });
            queue.setBoolean(cache, true);
            var provide = cache.getClass().getMethod("provide", int.class, int.class);
            var present = cache.getClass().getMethod("provideIfPresent", int.class, int.class);
            var enqueue = cache.getClass().getMethod("queue", int.class, int.class);
            var compute = cache.getClass().getDeclaredMethod("computeEntry", int.class, int.class); compute.setAccessible(true);
            var owner = pool.submit(() -> provide.invoke(cache, 37, -41));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "released generator must start on its caller");
            var probe = pool.submit(() -> {
                assertNull(present.invoke(cache, 38, -41), "unrelated cache reads must remain available");
                Object entry = compute.invoke(cache, 37, -41);
                assertSame(entry, compute.invoke(cache, 37, -41), "same tile shares its pending entry");
                assertEquals(false, entry.getClass().getMethod("isDone").invoke(entry));
                enqueue.invoke(cache, 38, -41);
                assertEquals(1 + batchesPerTile, batches.get(), "only the different tile starts another build");
                assertNull(present.invoke(cache, 38, -41), "failed queued entry can be removed");
                return null;
            });
            // This fails on the previous implementation, where owner holds the write lock.
            probe.get(5, TimeUnit.SECONDS);
            release.countDown();
            assertThrows(ExecutionException.class, () -> owner.get(5, TimeUnit.SECONDS));
            assertEquals(2 * batchesPerTile, batches.get());
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> provide.invoke(cache, 37, -41));
            assertEquals(3 * batchesPerTile, batches.get(), "failed tile is retried, never stranded pending");
        } finally {
            release.countDown(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            heightmap.set(generator, original); queue.setBoolean(cache, originalQueue);
        }
    }
}
