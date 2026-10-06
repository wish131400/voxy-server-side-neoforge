package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PredictionCpuBudgetTest {
    private static final long SECOND = 1_000_000_000L;
    private static final class Environment {
        long now;
        double system = .5, process = .4, fps = 90;
        final PredictionCpuBudget budget = new PredictionCpuBudget(() -> 8,
                () -> system, () -> process, () -> fps, () -> now, false);
    }

    @Test void cpuPressureLimitsTheCombinedLanesAndReservesCoverage() {
        var e = new Environment();
        e.system = .90;
        var leases = new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for (int i = 0; i < 3; i++) leases.add(assertLease(e.budget.tryAcquire(false)));
            assertNull(e.budget.tryAcquire(false));
            leases.add(assertLease(e.budget.tryAcquire(true)));
            assertNull(e.budget.tryAcquire(true), "terrain and surface share the same total cap");
            assertEquals(0, e.budget.localGenerationLimit(16, true));
        } finally { leases.forEach(PredictionCpuBudget.Lease::close); }
    }

    @Test void saturationRetainsCoverageAndBoundedDetailProgress() {
        var e = new Environment();
        e.system = .98;
        assertNull(e.budget.tryAcquire(false));
        try (var first = assertLease(e.budget.tryAcquire(true));
             var second = assertLease(e.budget.tryAcquire(true))) {
            assertNull(e.budget.tryAcquire(true));
            assertEquals(0, e.budget.localGenerationLimit(16, true));
        }
        e.now = 2 * SECOND;
        var detail = assertLease(e.budget.tryAcquire(false));
        e.now = 4 * SECOND;
        assertNull(e.budget.tryAcquire(false), "only one expensive trickle job can run");
        detail.close();
        assertLease(e.budget.tryAcquire(false)).close();
        assertNull(e.budget.tryAcquire(false), "finishing a short job cannot flood the next frame");
    }

    @Test void processCpuAndSlowFramesAlsoReduceWorkWhenSystemLoadIsUnavailable() {
        var e = new Environment();
        e.system = Double.NaN; e.process = .90;
        assertTrue(e.budget.diagnostics().contains("limit=4"));
        e.now = SECOND; e.process = -1; e.fps = 25;
        assertTrue(e.budget.diagnostics().contains("limit=2"));
        assertNull(e.budget.tryAcquire(false));
    }

    @Test void briefRecoveryCannotReopenAllWorkersAndRunningLeasesAreNotCancelled() {
        var e = new Environment();
        var active = new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for (int i = 0; i < 7; i++) active.add(assertLease(e.budget.tryAcquire(false)));
            e.now = SECOND; e.system = .98;
            assertNull(e.budget.tryAcquire(true), "new cap waits for old work to drain");
            assertTrue(e.budget.diagnostics().contains("active=7"));
            active.forEach(PredictionCpuBudget.Lease::close);
            e.now = 2 * SECOND; e.system = .5;
            assertTrue(e.budget.diagnostics().contains("limit=2"));
            e.now = 5 * SECOND;
            assertTrue(e.budget.diagnostics().contains("limit=4"));
            e.now = 6 * SECOND;
            e.budget.diagnostics();
            e.now = 9 * SECOND;
            assertTrue(e.budget.diagnostics().contains("limit=8"));
        } finally { active.forEach(PredictionCpuBudget.Lease::close); }
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

    @Test void pressureOneOnlyTricklesPausedDetailWhileRegularDetailRetainsItsReducedCap() {
        var e = new Environment();
        e.fps = 40;
        assertNull(e.budget.tryAcquire(false, true));
        e.now = 2 * SECOND;
        var trickle = assertLease(e.budget.tryAcquire(false, true));
        e.now = 4 * SECOND;
        assertNull(e.budget.tryAcquire(false, true), "a running trickle still occupies the only detail slot");
        trickle.close();
        assertLease(e.budget.tryAcquire(false, true)).close();
        assertNull(e.budget.tryAcquire(false, true), "a short build cannot start another trickle immediately");

        var regular = new Environment();
        regular.system = .90;
        var leases = new ArrayList<PredictionCpuBudget.Lease>();
        try {
            for (int i = 0; i < 3; i++) leases.add(assertLease(regular.budget.tryAcquire(false)));
            assertNull(regular.budget.tryAcquire(false));
            leases.add(assertLease(regular.budget.tryAcquire(true)));
        } finally { leases.forEach(PredictionCpuBudget.Lease::close); }
    }

    @Test void negativeNanoTimeAndSignedWrapKeepRecoveryAndDetailDelayBounded() {
        for (long start : new long[] {-20 * SECOND, Long.MAX_VALUE - SECOND}) {
            var e = new Environment();
            e.now = start; e.system = .98;
            assertNull(e.budget.tryAcquire(false));
            e.now = start + 2 * SECOND;
            assertLease(e.budget.tryAcquire(false)).close();
            assertNull(e.budget.tryAcquire(false));
            e.system = .5; e.fps = Double.NaN;
            e.now = start + 3 * SECOND;
            assertTrue(e.budget.diagnostics().contains("limit=2"));
            e.now = start + 6 * SECOND;
            assertTrue(e.budget.diagnostics().contains("limit=4"));
            e.now = start + 7 * SECOND;
            e.budget.diagnostics();
            e.now = start + 10 * SECOND;
            assertTrue(e.budget.diagnostics().contains("limit=8"));
        }
    }

    @Test void unavailableMetricsPreserveBoundedAdmissionAndSamplingIsNotPerTile() {
        long[] now = {0}; int[] queries = {0};
        var budget = new PredictionCpuBudget(() -> 4,
                () -> { queries[0]++; return -1; }, () -> Double.NaN, () -> -1, () -> now[0], false);
        for (int i = 0; i < 100; i++) assertLease(budget.tryAcquire(false)).close();
        assertEquals(1, queries[0]);
        now[0] = SECOND;
        assertLease(budget.tryAcquire(false)).close();
        assertEquals(2, queries[0]);
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

    @Test void slowMetricSamplerCannotBlockAdmissionMonitor() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var budget = new PredictionCpuBudget(() -> 2,
                () -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return .1;
                }, () -> .1, () -> 90, System::nanoTime, true);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "daemon sampler did not start");
            var admission = Executors.newSingleThreadExecutor();
            try {
                var future = admission.submit(() -> budget.tryAcquire(true));
                var lease = future.get(500, TimeUnit.MILLISECONDS);
                assertNotNull(lease);
                lease.close();
            } finally {
                admission.shutdownNow();
            }
        } finally {
            release.countDown();
        }
    }

    @Test void queuedReservationsCountAgainstTheSharedCapAndReleaseIdempotently() {
        var budget = PredictionCpuBudget.fixed(2);
        var first = budget.tryReserve(true, false);
        var second = budget.tryReserve(true, false);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryReserve(true, false));
        first.consume();
        first.consume();
        try (var lease = assertLease(budget.tryAcquire(true))) {
            assertNull(budget.tryReserve(true, false), "one active lease plus one queued reservation fills the cap");
        }
        second.close();
        second.close();
        assertLease(budget.tryAcquire(true)).close();
    }

    @Test void boundedQueueReservationsReleaseWithoutChangingActiveCapacity() {
        var budget = PredictionCpuBudget.fixed(1);
        var active = assertLease(budget.tryAcquire(true));
        var first = budget.tryReserveQueued(true, false, 2);
        var second = budget.tryReserveQueued(false, false, 2);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryReserveQueued(true, false, 2), "queue limit must be enforced");
        assertNull(budget.tryAcquire(true), "queued work must not bypass the active CPU cap");

        first.consume();
        first.consume();
        var third = budget.tryReserveQueued(true, false, 2);
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

    private static PredictionCpuBudget.Lease assertLease(PredictionCpuBudget.Lease lease) {
        assertNotNull(lease); return lease;
    }
}
