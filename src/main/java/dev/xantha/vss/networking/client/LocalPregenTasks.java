package dev.xantha.vss.networking.client;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded local import work, including the bytes held by the running task. */
final class LocalPregenTasks {
    private final long maxBytes;
    private long retainedBytes;
    private final ThreadPoolExecutor executor;

    LocalPregenTasks(int queuedTasks, long maxBytes) {
        this.maxBytes = maxBytes;
        executor = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queuedTasks), task -> {
                    Thread thread = new Thread(task, "VSS local pregen import");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    synchronized boolean offer(long bytes, BooleanSupplier work, Consumer<Boolean> completed) {
        if (bytes <= 0 || bytes > maxBytes - retainedBytes) return false;
        retainedBytes += bytes;
        try {
            executor.execute(() -> {
                boolean accepted = false;
                try { accepted = work.getAsBoolean(); }
                catch (Exception e) { dev.xantha.vss.common.VSSLogger.warn("Local pregen import failed: " + e.getMessage()); }
                finally {
                    synchronized (this) { retainedBytes -= bytes; }
                    completed.accept(accepted);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            retainedBytes -= bytes;
            return false;
        }
    }

    synchronized long retainedBytes() { return retainedBytes; }
}
