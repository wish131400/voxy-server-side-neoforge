package dev.xantha.vss.client.prediction;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Covers generation, captures, and cache tasks without modifying their queue identity or order. */
final class PredictionWorkerPool extends ThreadPoolExecutor {
    PredictionWorkerPool(int core, int maximum, long keepAlive, TimeUnit unit,
                         BlockingQueue<Runnable> queue, ThreadFactory factory) {
        super(core, maximum, keepAlive, unit, queue, factory);
    }

    @Override protected void beforeExecute(Thread thread, Runnable task) {
        super.beforeExecute(thread, task);
        PredictionCoreAffinity.applyCurrentWorker();
    }
}
