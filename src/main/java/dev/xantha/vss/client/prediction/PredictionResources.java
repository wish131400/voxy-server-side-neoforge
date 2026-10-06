package dev.xantha.vss.client.prediction;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Slow disposal is serialized off the client/render threads. */
final class PredictionResources {
    private static final ExecutorService DISPOSER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "vss-prediction-dispose");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    static void retire(ExecutorService workers, PredictionSampleStore store, ClientTerrainSampler sampler,
                       ExecutorService... additionalWorkers) {
        if (sampler instanceof RustTerrainSampler rust) rust.cancelWork();
        sampler.decorationContext().cancelDensityCompilation();
        DISPOSER.execute(() -> {
            try {
                // A JNI graph must outlive every sample still using it.
                while (!workers.awaitTermination(30, TimeUnit.SECONDS)) { }
                for (ExecutorService additional : additionalWorkers)
                    while (!additional.awaitTermination(30, TimeUnit.SECONDS)) { }
                if (store != null) store.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                if (workers.isTerminated() && java.util.Arrays.stream(additionalWorkers)
                        .allMatch(ExecutorService::isTerminated)) releaseSampler(sampler);
            }
        });
    }

    /** Profile worker only: retired worlds must release their native slots before another is created. */
    static void awaitRetired() throws InterruptedException, java.util.concurrent.ExecutionException {
        DISPOSER.submit(() -> { }).get();
    }

    static void releaseSampler(ClientTerrainSampler sampler) {
        sampler.close();
    }

    private PredictionResources() { }
}
