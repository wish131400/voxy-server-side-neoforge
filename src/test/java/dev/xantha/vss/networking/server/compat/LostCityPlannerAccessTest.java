package dev.xantha.vss.networking.server.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class LostCityPlannerAccessTest {
    static class Legacy {
        public static synchronized Legacy getBuildingInfo(Object position, Object provider) { return null; }
    }
    static class Modern {
        static final Object LOCK = new Object();
        private static Object getDimensionLock(ResourceKey<Level> dimension) { return LOCK; }
    }
    static class Unsafe {
        public static Unsafe getBuildingInfo(Object position, Object provider) { return null; }
    }
    static class Broken {
        private static Object getDimensionLock(ResourceKey<Level> dimension) { throw new IllegalStateException("broken"); }
    }
    @Test void legacyUsesTheSameMonitorAsGeneration() throws Exception {
        assertSame(Legacy.class, LostCityPlannerAccess.lock(Legacy.class, Level.OVERWORLD));
    }
    @Test void modernUsesPlannerOwnedDimensionMonitor() throws Exception {
        assertSame(Modern.LOCK, LostCityPlannerAccess.lock(Modern.class, Level.OVERWORLD));
    }
    @Test void missingOrBrokenSynchronizationIsNotSilentlyIgnored() {
        assertThrows(NoSuchMethodException.class, () -> LostCityPlannerAccess.lock(Unsafe.class, Level.OVERWORLD));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> LostCityPlannerAccess.lock(Broken.class, Level.OVERWORLD));
    }

    @Test void legacyReadsWaitForTheSameMonitorAndReleaseItAfterFailure() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        Future<Integer> result;
        try {
            synchronized (Legacy.class) {
                result = executor.submit(() -> {
                    worker.set(Thread.currentThread());
                    started.countDown();
                    return LostCityPlannerAccess.withLegacyMonitor(Legacy.class, () -> {
                        assertTrue(Thread.holdsLock(Legacy.class));
                        return 42;
                    });
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (worker.get().getState() != Thread.State.BLOCKED && !result.isDone()
                        && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, worker.get().getState());
                assertFalse(result.isDone());
            }
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertThrows(ReflectiveOperationException.class, () ->
                    LostCityPlannerAccess.withLegacyMonitor(Legacy.class, () -> {
                        throw new ReflectiveOperationException("failed asset read");
                    }));
            assertEquals(7, executor.submit(() -> LostCityPlannerAccess.withLegacyMonitor(Legacy.class,
                    () -> 7)).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void modernStagedReadsDoNotHoldTheirDimensionMonitor() throws Exception {
        Object lock = LostCityPlannerAccess.lock(Modern.class, Level.OVERWORLD);
        assertEquals(42, LostCityPlannerAccess.withLegacyMonitor(lock, () -> {
            assertFalse(Thread.holdsLock(lock));
            return 42;
        }));
        assertEquals(7, LostCityPlannerAccess.withLegacyMonitor(null, () -> 7));
    }
}
