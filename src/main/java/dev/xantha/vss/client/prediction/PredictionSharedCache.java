package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Bounded world-owned results. Only callers for the same missing key wait together. */
final class PredictionSharedCache<K,V> {
    private final int capacity;
    private final LinkedHashMap<K,V> ready = new LinkedHashMap<>(64, .75f, true);
    private final HashMap<K,CompletableFuture<V>> pending = new HashMap<>();
    PredictionSharedCache(int capacity) { this.capacity = capacity; }

    V get(K key, Supplier<V> build, Runnable check) {
        retry: for (;;) {
            check.run();
            CompletableFuture<V> flight;
            boolean owner;
            synchronized (this) {
                V value = ready.get(key);
                if (value != null) return value;
                flight = pending.get(key);
                owner = flight == null;
                if (owner) { flight = new CompletableFuture<>(); pending.put(key, flight); }
            }
            if (owner) {
                try {
                    V value = Objects.requireNonNull(build.get());
                    check.run();
                    synchronized (this) {
                        ready.put(key, value);
                        if (ready.size() > capacity) ready.remove(ready.keySet().iterator().next());
                        pending.remove(key);
                    }
                    flight.complete(value);
                    return value;
                } catch (RuntimeException | Error failure) {
                    synchronized (this) { pending.remove(key); }
                    flight.completeExceptionally(failure);
                    throw failure;
                }
            }
            for (;;) {
                check.run();
                try { return flight.get(20, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) { /* Keep cancellation responsive. */ }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                } catch (ExecutionException failed) {
                    // A cancelled producer belongs to another tile/view. Retry this key
                    // under our own cancellation scope; never cache a failed result.
                    Throwable cause = failed.getCause();
                    if (cause instanceof java.util.concurrent.CancellationException || cause instanceof PredictionWorkDeferred)
                        continue retry;
                    if (cause instanceof RuntimeException failure) throw failure;
                    if (cause instanceof Error failure) throw failure;
                    throw new IllegalStateException(cause);
                } catch (java.util.concurrent.CancellationException cancelledProducer) {
                    check.run();
                    continue retry;
                }
            }
        }
    }
    synchronized int size() { return ready.size(); }
}
