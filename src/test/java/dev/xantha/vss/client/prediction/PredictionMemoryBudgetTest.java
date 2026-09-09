package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PredictionMemoryBudgetTest {
    @Test
    void workersLeaveTwoLogicalProcessorsForTheGame() {
        assertEquals(1, PredictionMemoryBudget.workerCount(1));
        assertEquals(1, PredictionMemoryBudget.workerCount(2));
        assertEquals(5, PredictionMemoryBudget.workerCount(7));
        assertEquals(14, PredictionMemoryBudget.workerCount(16));
        assertEquals(30, PredictionMemoryBudget.workerCount(32));
    }

    @Test
    void spareJvmHeapAllowsCacheBeyondOldCapAndAllAvailableWorkers() {
        long heap = 10_144L * PredictionMemoryBudget.MIB;
        AtomicLong free = new AtomicLong(heap - 3072L * PredictionMemoryBudget.MIB);
        PredictionMemoryBudget budget = PredictionMemoryBudget.adaptive(heap, free::get, () -> 1,
                PredictionMemoryBudget.workerCount(16), () -> 0);
        var reservations = new ArrayList<PredictionMemoryBudget.Reservation>();
        try {
            long cacheBytes = 2560L * PredictionMemoryBudget.MIB;
            var resident = budget.tryReserve(cacheBytes);
            assertNotNull(resident);
            assertTrue(resident.retain(cacheBytes));
            free.addAndGet(-cacheBytes);
            reservations.add(resident);
            for (int worker = 0; worker < budget.buildLimit(); worker++) {
                var task = budget.tryReserveBuild();
                assertNotNull(task, "working allowance must accommodate worker " + worker);
                reservations.add(task);
            }
            assertEquals(14, budget.activeBuildCount());
            assertNull(budget.tryReserveBuild());
            assertEquals(0, budget.availableBuildSlots());
            assertTrue(budget.usedBytes() > 1792L * PredictionMemoryBudget.MIB,
                    "the old 1792 MiB quota must not constrain a JVM with spare heap");
        } finally {
            reservations.forEach(PredictionMemoryBudget.Reservation::close);
        }
        assertEquals(0, budget.usedBytes());
        assertEquals(0, budget.activeBuildCount());
    }

    @Test
    void sharedBuildSlotsAreReleasedOnCompletionAndCancellation() {
        PredictionMemoryBudget budget = new PredictionMemoryBudget(4096L * PredictionMemoryBudget.MIB,
                0, () -> Long.MAX_VALUE, () -> 1, 2);
        try (var first = budget.tryReserveBuild(); var second = budget.tryReserveBuild()) {
            assertNotNull(first); assertNotNull(second);
            assertNull(budget.tryReserveBuild(), "spare bytes must not bypass the CPU limit");
            assertTrue(first.retain(16L * PredictionMemoryBudget.MIB));
            assertEquals(1, budget.activeBuildCount());
            try (var next = budget.tryReserveBuild()) {
                assertNotNull(next);
                assertEquals(2, budget.activeBuildCount());
            }
            assertEquals(1, budget.activeBuildCount());
        }
        assertEquals(0, budget.activeBuildCount());
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void lowHeapLowersConcurrencyAndGcHeadroomRestoresItAutomatically() {
        long heap = 4096L * PredictionMemoryBudget.MIB;
        long safety = heap / 10;
        AtomicLong free = new AtomicLong(safety + PredictionMemoryBudget.BUILD_BYTES);
        var budget = PredictionMemoryBudget.adaptive(heap, free::get, () -> 1, 8, () -> 0);
        try (var first = budget.tryReserveBuild()) {
            assertNotNull(first);
            assertNull(budget.tryReserveBuild(), "the second worker must not reuse the first worker's promised bytes");
            assertEquals(1, budget.activeBuildCount());
            free.set(safety + 4 * PredictionMemoryBudget.BUILD_BYTES);
            assertEquals(3, budget.availableBuildSlots());
            try (var second = budget.tryReserveBuild(); var third = budget.tryReserveBuild();
                 var fourth = budget.tryReserveBuild()) {
                assertNotNull(second); assertNotNull(third); assertNotNull(fourth);
                assertNull(budget.tryReserveBuild());
                free.set(safety);
                assertEquals(0, budget.availableBuildSlots());
                assertEquals(4, budget.activeBuildCount(), "pressure must not cancel already admitted workers");
                assertEquals(0, budget.reclaimTargetBytes(), "let active workers release their workspaces before evicting caches");
            }
        }
        free.set(safety + 8 * PredictionMemoryBudget.BUILD_BYTES);
        assertEquals(8, budget.availableBuildSlots());
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void releasedCachesWaitForGcWithoutRepeatedlyEvictingOrSpendingUncollectedBytes() {
        long heap = 4096L * PredictionMemoryBudget.MIB, safety = heap / 10;
        AtomicLong free = new AtomicLong(2048L * PredictionMemoryBudget.MIB);
        AtomicLong collections = new AtomicLong();
        var budget = PredictionMemoryBudget.adaptive(heap, free::get, () -> 1, 8, collections::get);
        var resident = budget.tryReserveBuild();
        assertNotNull(resident);
        assertTrue(resident.retain(32L * PredictionMemoryBudget.MIB));
        free.set(safety + PredictionMemoryBudget.BUILD_BYTES - 20L * PredictionMemoryBudget.MIB);
        assertEquals(20L * PredictionMemoryBudget.MIB, budget.reclaimTargetBytes());
        resident.close();
        assertEquals(0, budget.reclaimTargetBytes(), "dropped references are awaiting collection; do not evict every tile");
        assertNull(budget.tryReserveBuild(), "a dropped reference is not yet free JVM heap");
        collections.incrementAndGet();
        assertEquals(20L * PredictionMemoryBudget.MIB, budget.reclaimTargetBytes(),
                "if GC did not restore headroom, reassess pressure instead of keeping stale reclaim credit");
        free.addAndGet(32L * PredictionMemoryBudget.MIB);
        try (var next = budget.tryReserveBuild()) { assertNotNull(next); }
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void concurrentAdaptiveReservationsCannotOversubscribeUnallocatedHeap() throws Exception {
        var budget = PredictionMemoryBudget.adaptive(1024L * PredictionMemoryBudget.MIB,
                () -> 512L * PredictionMemoryBudget.MIB, () -> 1, 8, () -> 0);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<java.util.concurrent.Future<PredictionMemoryBudget.Reservation>>();
            for (int i = 0; i < 8; i++) attempts.add(workers.submit(() -> {
                start.await();
                return budget.tryReserveBuild();
            }));
            start.countDown();
            var reservations = new ArrayList<PredictionMemoryBudget.Reservation>();
            try {
                for (var attempt : attempts) {
                    var reservation = attempt.get(3, TimeUnit.SECONDS);
                    if (reservation != null) reservations.add(reservation);
                }
                assertEquals(3, reservations.size(), "512 MiB free minus 128 MiB safety fits three 128 MiB workspaces");
            } finally {
                reservations.forEach(PredictionMemoryBudget.Reservation::close);
            }
        }
        assertEquals(0, budget.usedBytes());
        assertEquals(3, budget.availableBuildSlots());
    }

    @Test
    void simultaneousWorkersCannotReserveBeyondSharedLimit() throws Exception {
        PredictionMemoryBudget budget = new PredictionMemoryBudget(256, 0, () -> 1024, () -> 1);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(16)) {
            var attempts = new ArrayList<java.util.concurrent.Future<PredictionMemoryBudget.Reservation>>();
            for (int i = 0; i < 16; i++) {
                attempts.add(workers.submit(() -> { start.await(); return budget.tryReserve(64); }));
            }
            start.countDown();
            int admitted = 0;
            var reservations = new ArrayList<PredictionMemoryBudget.Reservation>();
            for (var attempt : attempts) {
                var reservation = attempt.get(3, TimeUnit.SECONDS);
                if (reservation != null) { admitted++; reservations.add(reservation); }
            }
            assertEquals(4, admitted);
            assertEquals(256, budget.usedBytes());
            reservations.forEach(PredictionMemoryBudget.Reservation::close);
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test
    void completedMeshesConsumeBytesAndEvictionReleasesThemOnce() {
        PredictionMemoryBudget budget = new PredictionMemoryBudget(256, 0, () -> 1024, () -> 1);
        var first = budget.tryReserve(128);
        var second = budget.tryReserve(128);
        assertNotNull(first); assertNotNull(second);
        assertTrue(first.retain(96));
        assertFalse(second.retain(192));
        assertEquals(224, budget.usedBytes());
        second.close();
        first.close();
        first.close();
        assertEquals(0, budget.usedBytes());
        assertFalse(first.retain(12));
    }

    @Test
    void otherModsHeapUseStopsNewBuildsBeforeOutOfMemory() {
        AtomicLong free = new AtomicLong(1024);
        PredictionMemoryBudget budget = new PredictionMemoryBudget(1024, 128, free::get, () -> 1);
        free.set(255);
        assertNull(budget.tryReserve(128));
        assertEquals(0, budget.usedBytes());
        free.set(512);
        try (var reservation = budget.tryReserve(128)) { assertNotNull(reservation); }
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void outOfMemoryPausesAllWorkersAndAllowsRecoveryAfterBackoff() {
        // nanoTime may have a negative origin; zero is not a valid initial deadline.
        AtomicLong clock = new AtomicLong(-20_000_000_000L);
        PredictionMemoryBudget budget = new PredictionMemoryBudget(1024, 0, () -> 2048, clock::get);
        try (var reservation = budget.tryReserve(128)) { assertNotNull(reservation); }
        assertTrue(budget.pauseAfterOutOfMemory());
        assertFalse(budget.pauseAfterOutOfMemory());
        assertNull(budget.tryReserve(1));
        clock.addAndGet(31_000_000_000L);
        try (var reservation = budget.tryReserve(128)) { assertNotNull(reservation); }
        assertEquals(0, budget.usedBytes());
    }
}
