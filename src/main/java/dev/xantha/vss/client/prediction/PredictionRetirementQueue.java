package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keyed disposal stays off the client thread and cannot build an obsolete command backlog. */
final class PredictionRetirementQueue<K> implements AutoCloseable {
    private static final Executor WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "vss-voxy-retirement");
        thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
    });
    private record Command(Runnable task, java.util.List<Runnable> completions) {
        void release() { completions.forEach(Runnable::run); }
    }
    private final ConcurrentHashMap<K, Command> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Executor worker;
    private volatile boolean closed;
    PredictionRetirementQueue() { this(WORKER); }
    PredictionRetirementQueue(Executor worker) { this.worker = worker; }
    void submit(K key, Runnable task) {
        submit(key, task, () -> { });
    }
    void submit(K key, Runnable task, Runnable completion) {
        if (closed) { completion.run(); return; }
        Command command = pending.compute(key, (ignored, previous) -> {
            var releases = previous == null ? new java.util.ArrayList<Runnable>()
                    : new java.util.ArrayList<>(previous.completions());
            releases.add(completion); return new Command(task, releases);
        });
        if (closed && pending.remove(key, command)) command.release();
        schedule();
    }
    private void schedule() {
        if (!closed && scheduled.compareAndSet(false, true)) worker.execute(this::drain);
    }
    private void drain() {
        try {
            long until = System.nanoTime() + 4_000_000L;
            int count = 0;
            for (var entry : pending.entrySet()) {
                if (closed || count++ >= 32 || System.nanoTime() >= until) break;
                if (!pending.remove(entry.getKey(), entry.getValue())) continue;
                try { entry.getValue().task().run(); }
                catch (RuntimeException failure) { VSSLogger.error("VSS prediction retirement failed", failure); }
                finally { entry.getValue().release(); }
            }
        } finally {
            scheduled.set(false);
            if (!pending.isEmpty()) schedule();
        }
    }
    int size() { return pending.size(); }
    boolean active() { return scheduled.get() || !pending.isEmpty(); }
    @Override public void close() {
        closed = true;
        pending.forEach((key, command) -> { if (pending.remove(key, command)) command.release(); });
    }
}
