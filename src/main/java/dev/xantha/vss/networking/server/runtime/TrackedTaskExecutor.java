package dev.xantha.vss.networking.server.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TrackedTaskExecutor {
    private final Supplier<? extends Executor> executorSupplier;
    private final AtomicInteger pendingTasks;

    public TrackedTaskExecutor(Supplier<? extends Executor> executorSupplier, AtomicInteger pendingTasks) {
        this.executorSupplier = Objects.requireNonNull(executorSupplier, "executorSupplier");
        this.pendingTasks = Objects.requireNonNull(pendingTasks, "pendingTasks");
    }

    PendingTask beginTask() {
        pendingTasks.incrementAndGet();
        return new PendingTask(pendingTasks);
    }

    PendingTask tryBeginTask(int limit) {
        int safeLimit = Math.max(0, limit);
        while (true) {
            int current = pendingTasks.get();
            if (current >= safeLimit) {
                return null;
            }
            if (pendingTasks.compareAndSet(current, current + 1)) {
                return new PendingTask(pendingTasks);
            }
        }
    }

    boolean submit(int limit, Runnable task, Consumer<RejectedExecutionException> onRejected) {
        Objects.requireNonNull(task, "task");
        PendingTask pendingTask = tryBeginTask(limit);
        if (pendingTask == null) {
            reject(onRejected, "VSS disk task queue is full");
            return false;
        }
        try {
            executorSupplier.get().execute(() -> {
                try {
                    task.run();
                } finally {
                    pendingTask.complete();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pendingTask.complete();
            if (onRejected != null) {
                onRejected.accept(e);
            }
            return false;
        }
    }

    boolean submitManual(
            int limit,
            Consumer<DiskTaskRuntime.PendingDiskTask> task,
            Consumer<RejectedExecutionException> onRejected) {
        Objects.requireNonNull(task, "task");
        PendingTask pendingTask = tryBeginTask(limit);
        if (pendingTask == null) {
            reject(onRejected, "VSS disk task queue is full");
            return false;
        }
        try {
            executorSupplier.get().execute(() -> {
                try {
                    task.accept(pendingTask);
                } catch (RuntimeException e) {
                    pendingTask.complete();
                    throw e;
                } catch (Error e) {
                    pendingTask.complete();
                    throw e;
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pendingTask.complete();
            if (onRejected != null) {
                onRejected.accept(e);
            }
            return false;
        }
    }

    public int pendingTasks() {
        return pendingTasks.get();
    }

    private static void reject(Consumer<RejectedExecutionException> onRejected, String message) {
        if (onRejected != null) {
            onRejected.accept(new RejectedExecutionException(message));
        }
    }

    static final class PendingTask implements DiskTaskRuntime.PendingDiskTask {
        private final AtomicInteger pendingTasks;
        private final AtomicBoolean complete = new AtomicBoolean();

        private PendingTask(AtomicInteger pendingTasks) {
            this.pendingTasks = pendingTasks;
        }

        public void complete() {
            if (complete.compareAndSet(false, true)) {
                pendingTasks.updateAndGet(value -> Math.max(0, value - 1));
            }
        }

        public boolean isComplete() {
            return complete.get();
        }
    }
}
