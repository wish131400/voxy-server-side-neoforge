package dev.xantha.vss.networking.server.runtime;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public final class DiskTaskRuntime {
    public interface PendingDiskTask {
        void complete();

        boolean isComplete();
    }

    private final int minThreads;
    private final int maxThreads;
    private final IntSupplier readThreadSupplier;
    private final BooleanSupplier acceptingTasks;
    private final AtomicInteger pendingReads = new AtomicInteger();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final TrackedTaskExecutor readTasks = new TrackedTaskExecutor(this::readExecutor, pendingReads);
    private final TrackedTaskExecutor writeTasks = new TrackedTaskExecutor(this::writeExecutor, pendingWrites);
    private final Object executorLock = new Object();
    private volatile ThreadPoolExecutor readExecutor;
    private volatile ThreadPoolExecutor writeExecutor;

    public DiskTaskRuntime(int minThreads, int maxThreads, IntSupplier readThreadSupplier, BooleanSupplier acceptingTasks) {
        this.minThreads = minThreads;
        this.maxThreads = maxThreads;
        this.readThreadSupplier = readThreadSupplier;
        this.acceptingTasks = acceptingTasks;
    }

    public boolean submitRead(int limit, Runnable task, Consumer<RejectedExecutionException> onRejected) {
        return readTasks.submit(limit, task, onRejected);
    }

    public boolean submitManualRead(
            int limit,
            Consumer<PendingDiskTask> task,
            Consumer<RejectedExecutionException> onRejected) {
        return readTasks.submitManual(limit, task, onRejected);
    }

    public boolean submitWrite(int limit, Runnable task, Consumer<RejectedExecutionException> onRejected) {
        return writeTasks.submit(limit, task, onRejected);
    }

    public int resizeReadExecutor() {
        if (!acceptingTasks.getAsBoolean()) {
            return 0;
        }
        int desiredThreads = desiredReadThreads();
        ThreadPoolExecutor executor = readExecutor();
        int currentThreads = executor.getCorePoolSize();
        if (currentThreads == desiredThreads) {
            return 0;
        }
        if (desiredThreads > currentThreads) {
            executor.setMaximumPoolSize(desiredThreads);
            executor.setCorePoolSize(desiredThreads);
        } else {
            executor.setCorePoolSize(desiredThreads);
            executor.setMaximumPoolSize(desiredThreads);
        }
        executor.prestartAllCoreThreads();
        return desiredThreads;
    }

    public void restart() {
        ThreadPoolExecutor oldRead;
        ThreadPoolExecutor oldWrite;
        synchronized (executorLock) {
            oldRead = readExecutor;
            oldWrite = writeExecutor;
            readExecutor = createDiskExecutor("VSS-DiskReader", readThreadSupplier.getAsInt());
            writeExecutor = createDiskExecutor("VSS-DiskWriter", 1);
        }
        shutdownExecutor(oldRead);
        shutdownExecutor(oldWrite);
    }

    public void shutdown() {
        ThreadPoolExecutor oldRead;
        ThreadPoolExecutor oldWrite;
        synchronized (executorLock) {
            oldRead = readExecutor;
            oldWrite = writeExecutor;
            readExecutor = null;
            writeExecutor = null;
        }
        shutdownExecutor(oldRead);
        shutdownExecutor(oldWrite);
    }

    public void resetPendingCounts() {
        pendingReads.set(0);
        pendingWrites.set(0);
    }

    public int pendingReads() {
        return pendingReads.get();
    }

    public int pendingWrites() {
        return pendingWrites.get();
    }

    public boolean hasReadCapacity(int limit) {
        return pendingReads() < limit;
    }

    public boolean hasWriteCapacity(int limit) {
        return pendingWrites() < limit;
    }

    public Snapshot snapshot() {
        ThreadPoolExecutor read = readExecutor;
        ThreadPoolExecutor write = writeExecutor;
        return new Snapshot(
                executorThreads(read),
                executorQueueSize(read),
                executorQueueSize(write),
                pendingReads(),
                pendingWrites());
    }

    private ThreadPoolExecutor readExecutor() {
        return diskExecutor(true);
    }

    private ThreadPoolExecutor writeExecutor() {
        return diskExecutor(false);
    }

    private ThreadPoolExecutor diskExecutor(boolean read) {
        ThreadPoolExecutor executor = read ? readExecutor : writeExecutor;
        if (isExecutorRunning(executor)) {
            return executor;
        }
        if (!acceptingTasks.getAsBoolean()) {
            throw new RejectedExecutionException("VSS server is stopping");
        }
        synchronized (executorLock) {
            executor = read ? readExecutor : writeExecutor;
            if (isExecutorRunning(executor)) {
                return executor;
            }
            ThreadPoolExecutor created = read
                    ? createDiskExecutor("VSS-DiskReader", readThreadSupplier.getAsInt())
                    : createDiskExecutor("VSS-DiskWriter", 1);
            if (read) {
                readExecutor = created;
            } else {
                writeExecutor = created;
            }
            return created;
        }
    }

    private ThreadPoolExecutor createDiskExecutor(String threadName, int threads) {
        int clampedThreads = Math.max(minThreads, Math.min(maxThreads, threads));
        AtomicInteger threadId = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                clampedThreads,
                clampedThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, threadName + "-" + threadId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }

    private int desiredReadThreads() {
        return Math.max(minThreads, Math.min(maxThreads, readThreadSupplier.getAsInt()));
    }

    private static boolean isExecutorRunning(ThreadPoolExecutor executor) {
        return executor != null && !executor.isShutdown() && !executor.isTerminated();
    }

    private static void shutdownExecutor(ThreadPoolExecutor executor) {
        if (executor == null) {
            return;
        }
        executor.getQueue().clear();
        executor.shutdownNow();
    }

    private static int executorThreads(ThreadPoolExecutor executor) {
        return isExecutorRunning(executor) ? executor.getCorePoolSize() : 0;
    }

    private static int executorQueueSize(ThreadPoolExecutor executor) {
        return executor != null ? executor.getQueue().size() : 0;
    }

    public record Snapshot(int readThreads, int readQueueSize, int writeQueueSize, int pendingReads, int pendingWrites) {
    }
}
