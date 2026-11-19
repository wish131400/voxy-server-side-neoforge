package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class FreeTerraForgedPredictionTasksTest {
    @Test void batchesAndFilterContinuationStartAfterCacheRegistration() {
        Object generator = new Object();
        FreeTerraForgedPredictionTasks.ownGenerator(generator);
        var locked = new AtomicBoolean();
        var events = new java.util.ArrayList<String>();
        var future = FreeTerraForgedPredictionTasks.register(() -> {
            locked.set(true);
            try {
                var batch = FreeTerraForgedPredictionTasks.run(generator, () -> {
                    assertFalse(locked.get()); events.add("batch");
                    // Nested requests must not be stranded in the completed registration scope.
                    FreeTerraForgedPredictionTasks.register(() -> FreeTerraForgedPredictionTasks.run(
                            generator, () -> events.add("nested"), action -> fail("upstream"))).join();
                }, action -> fail("upstream"));
                assertFalse(batch.isDone());
                return batch.thenRun(() -> { assertFalse(locked.get()); events.add("filters"); });
            } finally { locked.set(false); events.add("published"); }
        });
        assertTrue(future.isDone()); future.join();
        assertEquals(java.util.List.of("published", "batch", "nested", "filters"), events);
    }

    @Test void registrationAndBatchFailuresDoNotStrandFuturesOrTheNextRequest() {
        Object generator = new Object();
        FreeTerraForgedPredictionTasks.ownGenerator(generator);
        var pending = new AtomicReference<CompletableFuture<Void>>();
        var failure = new IllegalStateException("registration failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> FreeTerraForgedPredictionTasks.register(() -> {
            pending.set(FreeTerraForgedPredictionTasks.run(generator, () -> fail("aborted batch ran"), Runnable::run));
            throw failure;
        })));
        assertSame(failure, assertThrows(CompletionException.class, () -> pending.get().join()).getCause());
        var next = FreeTerraForgedPredictionTasks.register(() -> FreeTerraForgedPredictionTasks.run(
                generator, () -> { throw new IllegalArgumentException("batch failed"); }, Runnable::run));
        assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, next::join).getCause());
        assertTrue(FreeTerraForgedPredictionTasks.run(generator, () -> {}, Runnable::run).isDone());
    }

    @Test void onlyOwnedPredictionBatchesUseTheirAdmittedCallerAndKeepFutureFailures() {
        Object prediction = new Object(), server = new Object();
        FreeTerraForgedPredictionTasks.ownGenerator(prediction);
        AtomicInteger submissions = new AtomicInteger();
        Executor upstream = action -> { submissions.incrementAndGet(); action.run(); };
        AtomicReference<Thread> worker = new AtomicReference<>();
        FreeTerraForgedPredictionTasks.run(prediction, () -> worker.set(Thread.currentThread()), upstream).join();
        assertSame(Thread.currentThread(), worker.get()); assertEquals(0, submissions.get());
        FreeTerraForgedPredictionTasks.run(server, () -> {}, upstream).join();
        assertEquals(1, submissions.get(), "server-owned generation must keep the upstream executor");
        var failure = FreeTerraForgedPredictionTasks.run(prediction, () -> { throw new IllegalStateException("failure"); }, upstream);
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, failure::join).getCause());
    }
}
