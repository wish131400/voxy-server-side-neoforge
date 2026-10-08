package dev.xantha.vss.networking.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalPregenTasksTest {
    @Test void countsRunningAndQueuedBytesAndAcknowledgesOnlyAfterWorkCompletes() throws Exception {
        var tasks = new LocalPregenTasks(4, 100);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(2);
        var acknowledged = new AtomicBoolean();
        assertTrue(tasks.offer(60, () -> {
            started.countDown(); try { return release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { return false; }
        }, success -> { acknowledged.set(success); done.countDown(); }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            assertTrue(tasks.offer(40, () -> true, result -> done.countDown()));
            assertFalse(tasks.offer(1, () -> true, ignored -> fail("rejected work has no completion")));
            assertEquals(100, tasks.retainedBytes()); assertFalse(acknowledged.get());
        } finally { release.countDown(); }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(acknowledged.get()); assertEquals(0, tasks.retainedBytes());
    }
    @Test void cancelledSessionWorkReportsFailureAndReleasesItsReservation() throws Exception {
        var tasks = new LocalPregenTasks(1, 100); var done = new CountDownLatch(1);
        var accepted = new AtomicBoolean(true);
        assertTrue(tasks.offer(100, () -> false, result -> { accepted.set(result); done.countDown(); }));
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertFalse(accepted.get()); assertEquals(0, tasks.retainedBytes());
    }
}
