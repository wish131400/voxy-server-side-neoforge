package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Runs prediction-owned UTF batches on their admitted worker, outside the tile-cache lock. */
public final class FreeTerraForgedPredictionTasks {
    private static final Map<Object, Boolean> OWNED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Executor CALLER = Runnable::run;
    private static final ThreadLocal<List<Batch>> REGISTERING = new ThreadLocal<>();
    private static final ClassValue<MethodHandle> COMPUTE_ENTRY = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                var method = type.getDeclaredMethod("computeEntry", int.class, int.class);
                method.setAccessible(true);
                return MethodHandles.lookup().unreflect(method)
                        .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("UTF tile cache entry method unavailable", e);
            }
        }
    };

    private FreeTerraForgedPredictionTasks() { }

    static void own(Object context) throws ReflectiveOperationException {
        if (context != null) {
            ownGenerator(context.getClass().getField("generator").get(context));
            ownCache(context.getClass().getField("cache").get(context));
        }
    }

    static void ownGenerator(Object generator) { OWNED.put(generator, Boolean.TRUE); }
    static void ownCache(Object cache) { OWNED.put(cache, Boolean.TRUE); }

    public static Object computeEntry(Object cache, int x, int z) {
        MethodHandle method = COMPUTE_ENTRY.get(cache.getClass());
        Supplier<Object> registration = () -> {
            try {
                return (Object) method.invokeExact(cache, x, z);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException("UTF tile cache registration failed", e);
            }
        };
        return OWNED.containsKey(cache) ? register(registration) : registration.get();
    }

    static <T> T register(Supplier<T> registration) {
        List<Batch> batches = new ArrayList<>();
        List<Batch> previous = REGISTERING.get();
        REGISTERING.set(batches);
        T entry;
        try {
            // UTF calls generate() inside StampedBoundLongMap's write lock.
            // Publish its original pending CacheEntry before starting any batch.
            entry = registration.get();
        } catch (RuntimeException | Error e) {
            batches.forEach(batch -> batch.result.completeExceptionally(e));
            throw e;
        } finally {
            if (previous == null) REGISTERING.remove();
            else REGISTERING.set(previous);
        }
        // computeEntry has returned and released the map lock. Keep allOf and
        // its original filter continuation, on the same admitted VSS worker.
        for (Batch batch : batches) batch.execute();
        return entry;
    }

    public static CompletableFuture<Void> run(Object generator, Runnable task, Executor upstream) {
        if (!OWNED.containsKey(generator)) return CompletableFuture.runAsync(task, upstream);
        List<Batch> batches = REGISTERING.get();
        if (batches == null) return CompletableFuture.runAsync(task, CALLER);
        CompletableFuture<Void> result = new CompletableFuture<>();
        batches.add(new Batch(task, result));
        return result;
    }

    private record Batch(Runnable task, CompletableFuture<Void> result) {
        void execute() {
            try { task.run(); result.complete(null); }
            catch (Throwable e) { result.completeExceptionally(e); }
        }
    }
}
