package dev.xantha.vss.networking.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TrackedTaskExecutorTest {

    @Test
    void submitTracksTaskUntilItFinishes() {
        AtomicInteger pending = new AtomicInteger();
        TrackedTaskExecutor executor = new TrackedTaskExecutor(() -> Runnable::run, pending);
        AtomicInteger observedPending = new AtomicInteger();

        boolean accepted = executor.submit(10, () -> observedPending.set(pending.get()), e -> fail());

        assertTrue(accepted);
        assertEquals(1, observedPending.get());
        assertEquals(0, pending.get());
        assertEquals(0, executor.pendingTasks());
    }

    @Test
    void rejectedSubmissionRollsBackPendingAndNotifiesHandler() {
        AtomicInteger pending = new AtomicInteger();
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("full");
        };
        TrackedTaskExecutor executor = new TrackedTaskExecutor(() -> rejectingExecutor, pending);
        AtomicInteger rejections = new AtomicInteger();

        boolean accepted = executor.submit(10, () -> fail(), e -> rejections.incrementAndGet());

        assertFalse(accepted);
        assertEquals(1, rejections.get());
        assertEquals(0, pending.get());
    }

    @Test
    void manualPendingTaskCompletesOnlyOnce() {
        AtomicInteger pending = new AtomicInteger();
        TrackedTaskExecutor executor = new TrackedTaskExecutor(() -> Runnable::run, pending);

        TrackedTaskExecutor.PendingTask pendingTask = executor.beginTask();
        pendingTask.complete();
        pendingTask.complete();

        assertTrue(pendingTask.isComplete());
        assertEquals(0, pending.get());
    }

    @Test
    void taskFailureStillReleasesPending() {
        AtomicInteger pending = new AtomicInteger();
        TrackedTaskExecutor executor = new TrackedTaskExecutor(() -> Runnable::run, pending);

        assertThrows(IllegalStateException.class, () -> executor.submit(10, () -> {
            throw new IllegalStateException("boom");
        }, e -> fail()));

        assertEquals(0, pending.get());
    }
}
