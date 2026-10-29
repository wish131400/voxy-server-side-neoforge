package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PredictionCpuBudgetTest {
    @Test void waitingRealGenerationGetsHeadroomWhenContinuousPredictionFinishes() {
        var e=new Environment();var leases=new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for(int i=0;i<7;i++)leases.add(assertLease(e.budget.tryAcquire(false)));
            assertEquals(0,e.budget.reserveLocalGeneration(4,true,0));
            e.budget.observeLocalGeneration(true,0);
            leases.remove(0).close();
            assertNull(e.budget.tryAcquire(true),"finishing prediction must leave room for waiting real terrain");
            assertEquals(1,e.budget.localGenerationLimit(8,true));
            assertEquals(1,e.budget.reserveLocalGeneration(1,true,0));
            e.budget.observeLocalGeneration(true,1);
            assertNull(e.budget.tryAcquire(false));
        } finally {leases.forEach(PredictionCpuBudget.Lease::close);}
    }
    @Test void oneWorkerCanGenerateRealTerrainWithPredictionEnabled() {
        var budget=PredictionCpuBudget.fixed(1);
        assertEquals(1,budget.localGenerationLimit(8,true));
        assertEquals(1,budget.reserveLocalGeneration(1,true,0));
    }

    private static final long SECOND = 1_000_000_000L;
    private static final class Environment {
        long now;
        final PredictionCpuBudget budget = new PredictionCpuBudget(() -> 8, () -> now);
    }

    @Test void configuredCapReservesOneCoverageLaneWithoutHalvingWorkers() {
        var e = new Environment();
        var leases = new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for (int i = 0; i < 7; i++) leases.add(assertLease(e.budget.tryAcquire(false)));
            assertNull(e.budget.tryAcquire(false));
            leases.add(assertLease(e.budget.tryAcquire(true)));
            assertNull(e.budget.tryAcquire(true), "terrain and surface share the configured cap");
            assertTrue(e.budget.diagnostics().contains("limit=8,active=8"));
        } finally { leases.forEach(PredictionCpuBudget.Lease::close); }
    }

    @Test void completedDetailImmediatelyMakesRoomWithoutTrickleOrRecoveryDelay() {
        var e = new Environment();
        for (int i = 0; i < 100; i++) {
            var queue = e.budget.tryReserveQueued(false, 8);
            assertNotNull(queue);
            queue.consume();
            assertLease(e.budget.tryAcquire(false)).close();
        }
        assertEquals(0, e.now, "no two-second fake-clock advance should be needed");
        assertTrue(e.budget.diagnostics().contains("active=0,detail=0"));
    }

    @Test void admissionHasNoOperatingSystemLoadSamplerOrThrottleState() {
        for (var field : PredictionCpuBudget.class.getDeclaredFields()) {
            assertNotEquals(java.util.function.DoubleSupplier.class, field.getType());
            assertFalse(java.util.concurrent.ExecutorService.class.isAssignableFrom(field.getType()));
            assertNotEquals("pressure", field.getName());
        }
        assertTrue(new Environment().budget.diagnostics().startsWith("mode=configured,limit=8,"));
    }

    @Test void changingConfiguredLimitDoesNotCancelWorkAndIncreaseNeedsNoRecoveryWait() {
        int[] configured = {8};
        var budget = new PredictionCpuBudget(() -> configured[0], () -> 0);
        var leases = new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for (int i = 0; i < 8; i++) leases.add(assertLease(budget.tryAcquire(true)));
            configured[0] = 2;
            assertNull(budget.tryAcquire(true));
            assertTrue(budget.diagnostics().contains("limit=2,active=8"));
            leases.forEach(PredictionCpuBudget.Lease::close);
            leases.clear();
            configured[0] = 8;
            for (int i = 0; i < 8; i++) leases.add(assertLease(budget.tryAcquire(true)));
            assertNull(budget.tryAcquire(true));
        } finally { leases.forEach(PredictionCpuBudget.Lease::close); }
    }

    @Test void integratedGenerationDrainsWithoutStarvingCoverageAndDisconnectClearsItsDebt() {
        var e = new Environment();
        e.budget.observeLocalGeneration(true, 8);
        assertNull(e.budget.tryAcquire(false));
        var coverage = assertLease(e.budget.tryAcquire(true));
        assertNull(e.budget.tryAcquire(true));
        coverage.close();
        e.budget.observeLocalGeneration(false, 8);
        var detail = assertLease(e.budget.tryAcquire(false));
        assertEquals(6, e.budget.localGenerationLimit(16, true));
        detail.close();
        assertEquals(7, e.budget.localGenerationLimit(16, true));
    }

    @Test void staleGenerationCountersCannotBlockANewSession() {
        var e = new Environment();
        e.budget.observeLocalGeneration(true, 8);
        assertNull(e.budget.tryAcquire(false));
        e.now = 3 * SECOND;
        assertLease(e.budget.tryAcquire(false)).close();
    }

    @Test void requestScanReservesItsQuotaAgainstConcurrentPredictionAndReleasesUnusedSlots() {
        var e = new Environment();
        assertEquals(7, e.budget.reserveLocalGeneration(16, true, 0));
        assertNull(e.budget.tryAcquire(false));
        var coverage = assertLease(e.budget.tryAcquire(true));
        assertNull(e.budget.tryAcquire(true));
        e.budget.observeLocalGeneration(true, 2);
        var detail = assertLease(e.budget.tryAcquire(false));
        assertEquals(5, e.budget.reserveLocalGeneration(16, true, 2));
        var finalDetail = assertLease(e.budget.tryAcquire(false));
        assertNull(e.budget.tryAcquire(false));
        assertNull(e.budget.tryAcquire(true));
        coverage.close();
        assertNull(e.budget.tryAcquire(false), "after coverage finishes its slot remains reserved for coverage");
        assertLease(e.budget.tryAcquire(true)).close();
        finalDetail.close(); detail.close();
        e.budget.observeLocalGeneration(true, 0);
        assertEquals(7, e.budget.localGenerationLimit(16, true));
    }

    @Test void requestReservationRechecksHeadroomAndPreservesPreviouslyAdmittedDebt() {
        var e = new Environment();
        assertEquals(7, e.budget.localGenerationLimit(16, true));
        var active = new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for (int i = 0; i < 6; i++) active.add(assertLease(e.budget.tryAcquire(false)));
            assertEquals(1, e.budget.reserveLocalGeneration(7, true, 5));
            assertNull(e.budget.tryAcquire(true));
            assertTrue(e.budget.diagnostics().contains("localGeneration=5"));
        } finally { active.forEach(PredictionCpuBudget.Lease::close); }
    }



    @Test void signedClockWrapCannotKeepGenerationOrAdmissionBlocked() {
        for (long start : new long[] {-20 * SECOND, Long.MAX_VALUE - SECOND}) {
            var e = new Environment();
            e.now = start;
            e.budget.observeLocalGeneration(true, 8);
            assertNull(e.budget.tryAcquire(false));
            assertNull(e.budget.tryReserveQueued(false, 8));
            e.now = start + 3 * SECOND;
            var queue = e.budget.tryReserveQueued(false, 8);
            assertNotNull(queue);
            queue.consume();
            assertLease(e.budget.tryAcquire(false)).close();
        }
    }



    @Test void concurrentAdmissionsShareOneCapAndClosingTwiceDoesNotLeakSlots() throws Exception {
        var budget = PredictionCpuBudget.fixed(4);
        var executor = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        var leases = new ArrayList<java.util.concurrent.Future<PredictionCpuBudget.Lease>>();
        try {
            for (int i = 0; i < 12; i++) leases.add(executor.submit(() -> {
                start.await(); return budget.tryAcquire(false);
            }));
            start.countDown();
            int admitted = 0;
            var completed = new ArrayList<PredictionCpuBudget.Lease>();
            for (var future : leases) {
                var lease = future.get(5, TimeUnit.SECONDS);
                if (lease != null) { admitted++; completed.add(lease); }
            }
            assertEquals(4, admitted);
            completed.forEach(l -> { l.close(); l.close(); });
            assertTrue(budget.diagnostics().contains("active=0"));
            assertLease(budget.tryAcquire(true)).close();
        } finally { executor.shutdownNow(); }
    }



    @Test void queuedReservationsCountAgainstTheSharedCapAndReleaseIdempotently() {
        var budget = PredictionCpuBudget.fixed(2);
        var first = budget.tryReserve(true);
        var second = budget.tryReserve(true);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryReserve(true));
        first.consume();
        first.consume();
        try (var lease = assertLease(budget.tryAcquire(true))) {
            assertNull(budget.tryReserve(true), "one active lease plus one queued reservation fills the cap");
        }
        second.close();
        second.close();
        assertLease(budget.tryAcquire(true)).close();
    }

    @Test void boundedQueueReservationsReleaseWithoutChangingActiveCapacity() {
        var budget = PredictionCpuBudget.fixed(1);
        var active = assertLease(budget.tryAcquire(true));
        var first = budget.tryReserveQueued(true, 2);
        var second = budget.tryReserveQueued(false, 2);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryReserveQueued(true, 2), "queue limit must be enforced");

        first.consume();
        first.consume();
        var third = budget.tryReserveQueued(true, 2);
        assertNotNull(third,
                "consuming a queued task must release its queue slot");

        active.close();
        active.close();
        assertLease(budget.tryAcquire(true)).close();
        second.close();
        second.close();
        third.close();
        third.close();
    }

    @Test void rejectedWorkerClosesAdmissionUntilCapacityReleaseOrRetryDeadline() {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var budget = new PredictionCpuBudget(() -> 1, now::get);
        var active = assertLease(budget.tryAcquire(true));
        var queued = budget.tryReserveQueued(true, 8);
        assertNotNull(queued);
        queued.consume();
        assertNull(budget.tryAcquire(true));
        for (int i = 0; i < 10000; i++) {
            assertNull(budget.tryReserveQueued(true, 8));
            assertNull(budget.tryReserve(true));
        }
        now.set(100_000_000L);
        var probe = budget.tryReserveQueued(true, 8);
        assertNotNull(probe, "a capacity change outside prediction must get a bounded retry");
        probe.consume();
        assertNull(budget.tryAcquire(true));
        active.close();
        var resumed = budget.tryReserveQueued(true, 8);
        assertNotNull(resumed, "lease release wakes admission immediately, before retry timeout");
        resumed.consume();
        assertLease(budget.tryAcquire(true)).close();
    }

    @Test void queuedDetailRemainsBoundedAndReleaseWakesOtherManagersImmediately() {
        var e = new Environment();
        var leases = new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for (int i = 0; i < 7; i++) leases.add(assertLease(e.budget.tryAcquire(false)));
            for (int i = 0; i < 100; i++) assertNull(e.budget.tryReserveQueued(false, 8));
            leases.remove(0).close();
            var resumed = e.budget.tryReserveQueued(false, 8);
            assertNotNull(resumed);
            resumed.close(); resumed.close();
        } finally { leases.forEach(PredictionCpuBudget.Lease::close); }
    }

    private static PredictionCpuBudget.Lease assertLease(PredictionCpuBudget.Lease lease) {
        assertNotNull(lease); return lease;
    }
    @Test void cappedFrameRatesCannotBlockPredictionOrLocalGeneration() {
        try {
            for (int fps : new int[] {15, 30, 40}) {
                PredictionFramePace.resetForTesting();
                long gap = 1_000_000_000L / fps;
                for (int i = 0; i <= 90; i++) PredictionFramePace.recordFrame(SECOND + i * gap);
                assertEquals(fps, PredictionFramePace.averageFps(), .01);
                var e = new Environment();
                assertEquals(8, e.budget.localGenerationLimit(16, false));
                assertEquals(8, e.budget.reserveLocalGeneration(16, false, 0));
                e.budget.observeLocalGeneration(true, 0);
                assertLease(e.budget.tryAcquire(false)).close();
                assertFalse(e.budget.diagnostics().contains("fps="));
            }
        } finally { PredictionFramePace.resetForTesting(); }
    }





}
