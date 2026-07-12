package dev.xantha.vss.networking.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DiskTaskRuntimeTest {

    @Test
    void restartCreatesClampedExecutors() {
        DiskTaskRuntime runtime = runtime(() -> 8, () -> true);
        try {
            runtime.restart();

            DiskTaskRuntime.Snapshot snapshot = runtime.snapshot();
            assertEquals(2, snapshot.readThreads());
            assertEquals(0, snapshot.pendingReads());
            assertEquals(0, snapshot.pendingWrites());
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    void submitReadTracksPendingUntilTaskFinishes() throws Exception {
        DiskTaskRuntime runtime = runtime(() -> 1, () -> true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            assertTrue(runtime.submitRead(10, () -> {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }, e -> {
            }));

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(1, runtime.pendingReads());
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            waitForPendingReads(runtime, 0);
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    void rejectedReadRollsBackPendingAndCallsHandler() {
        AtomicBoolean accepting = new AtomicBoolean(false);
        DiskTaskRuntime runtime = runtime(() -> 1, accepting::get);
        AtomicBoolean rejected = new AtomicBoolean();

        boolean accepted = runtime.submitRead(10, () -> {
        }, e -> rejected.set(true));

        assertFalse(accepted);
        assertTrue(rejected.get());
        assertEquals(0, runtime.pendingReads());
    }

    @Test
    void manualReadPendingTaskIsIdempotent() throws Exception {
        DiskTaskRuntime runtime = runtime(() -> 1, () -> true);
        CountDownLatch finished = new CountDownLatch(1);

        assertTrue(runtime.submitManualRead(1, pendingTask -> {
            pendingTask.complete();
            pendingTask.complete();
            finished.countDown();
        }, e -> {
        }));

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(0, runtime.pendingReads());
    }

    @Test
    void boundedReadSubmitRejectsWhenLimitIsReached() throws Exception {
        DiskTaskRuntime runtime = runtime(() -> 1, () -> true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejected = new AtomicBoolean();
        try {
            assertTrue(runtime.submitRead(1, () -> {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, e -> {
            }));

            assertTrue(started.await(2, TimeUnit.SECONDS));

            boolean second = runtime.submitRead(1, () -> {
            }, e -> rejected.set(true));

            assertFalse(second);
            assertTrue(rejected.get());
            assertEquals(1, runtime.pendingReads());
        } finally {
            release.countDown();
            runtime.shutdown();
        }
        waitForPendingReads(runtime, 0);
    }

    @Test
    void resizeReadExecutorAppliesNewConfiguredThreadCount() {
        AtomicInteger configuredThreads = new AtomicInteger(1);
        DiskTaskRuntime runtime = runtime(configuredThreads::get, () -> true);
        try {
            runtime.restart();
            configuredThreads.set(2);

            assertEquals(2, runtime.resizeReadExecutor());
            assertEquals(2, runtime.snapshot().readThreads());
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    void preloadReadsLeaveReservedCapacityForLiveReads() throws Exception {
        DiskTaskRuntime runtime = runtime(() -> 1, () -> true);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch preloadFinished = new CountDownLatch(3);
        AtomicBoolean rejected = new AtomicBoolean();
        try {
            for (int i = 0; i < 3; i++) {
                int index = i;
                assertTrue(runtime.submitPreloadRead(4, 1, () -> {
                    if (index == 0) {
                        firstStarted.countDown();
                        try {
                            releaseFirst.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    preloadFinished.countDown();
                }, e -> rejected.set(true)));
            }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            assertFalse(runtime.submitPreloadRead(4, 1, () -> {
            }, e -> rejected.set(true)));
            assertTrue(runtime.submitManualRead(4, pending -> pending.complete(), e -> rejected.set(true)));
            assertEquals(3, runtime.pendingPreloadReads());
            assertTrue(rejected.get());

            releaseFirst.countDown();
            assertTrue(preloadFinished.await(2, TimeUnit.SECONDS));
            waitForPendingReads(runtime, 0);
            assertEquals(3, runtime.snapshot().preloadReadsSubmitted());
            assertEquals(3, runtime.snapshot().preloadReadsCompleted());
            assertEquals(1, runtime.snapshot().manualReadsSubmitted());
            assertEquals(1, runtime.snapshot().manualReadsCompleted());
            assertEquals(4, runtime.snapshot().readWaitSamples());
        } finally {
            releaseFirst.countDown();
            runtime.shutdown();
        }
    }

    @Test
    void zeroPreloadCapacityRejectsPreloadButAcceptsLiveRead() {
        DiskTaskRuntime runtime = runtime(() -> 1, () -> true);
        try {
            assertFalse(runtime.submitPreloadRead(1, 1, () -> {
            }, e -> {
            }));
            assertTrue(runtime.submitManualRead(1, pending -> pending.complete(), e -> {
            }));
        } finally {
            runtime.shutdown();
        }
    }

    private static DiskTaskRuntime runtime(java.util.function.IntSupplier readThreads, java.util.function.BooleanSupplier accepting) {
        return new DiskTaskRuntime(1, 2, readThreads, accepting);
    }

    private static void waitForPendingReads(DiskTaskRuntime runtime, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (runtime.pendingReads() == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, runtime.pendingReads());
    }
}
