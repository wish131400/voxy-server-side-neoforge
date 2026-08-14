package dev.xantha.vss.networking.server.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

public final class TrackedTaskExecutor {
    private final Supplier<? extends Executor> executorSupplier;
    private final AtomicInteger pendingTasks;
    private final AtomicLong sequence = new AtomicLong();

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
        return submit(limit, task, onRejected, 1);
    }

    boolean submit(int limit, Runnable task, Consumer<RejectedExecutionException> onRejected, int priority) {
        Objects.requireNonNull(task, "task");
        PendingTask pendingTask = tryBeginTask(limit);
        if (pendingTask == null) {
            reject(onRejected, "VSS disk task queue is full");
            return false;
        }
        try {
            executorSupplier.get().execute(new PrioritizedTask(() -> {
                try {
                    task.run();
                } finally {
                    pendingTask.complete();
                }
            }, priority, sequence.incrementAndGet()));
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
        return submitManual(limit, task, onRejected, 0);
    }

    boolean submitManual(
            int limit,
            Consumer<DiskTaskRuntime.PendingDiskTask> task,
            Consumer<RejectedExecutionException> onRejected,
            int priority) {
        Objects.requireNonNull(task, "task");
        PendingTask pendingTask = tryBeginTask(limit);
        if (pendingTask == null) {
            reject(onRejected, "VSS disk task queue is full");
            return false;
        }
        try {
            executorSupplier.get().execute(new PrioritizedTask(() -> {
                try {
                    task.accept(pendingTask);
                } catch (RuntimeException e) {
                    pendingTask.complete();
                    throw e;
                } catch (Error e) {
                    pendingTask.complete();
                    throw e;
                }
            }, priority, sequence.incrementAndGet()));
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

    private record PrioritizedTask(Runnable delegate, int priority, long sequence) implements Runnable, Comparable<PrioritizedTask> {
        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int priorityOrder = Integer.compare(priority, other.priority);
            return priorityOrder != 0 ? priorityOrder : Long.compare(sequence, other.sequence);
        }
    }
}
