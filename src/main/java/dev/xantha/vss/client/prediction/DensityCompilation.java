package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Lazy background compilation owned by one decoded sampler, published for all roots atomically. */
final class DensityCompilation implements AutoCloseable {
    private static final ThreadPoolExecutor COMPILER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "vss-density-compiler");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    static { COMPILER.allowCoreThreadTimeOut(true); }

    private final DensityFunction[] source;
    private final DensityFunction[] roots;
    private final boolean enabled = !"off".equals(System.getProperty("vss.javaDensityCompiler"));
    private volatile DensityGraphCompiler.Compiled compiled;
    private volatile String state;
    private FutureTask<Void> task;
    private boolean retired;
    private volatile long retryAfter;

    DensityCompilation(DensityFunction... source) {
        this.source = source.clone();
        this.roots = new DensityFunction[source.length];
        for (int i = 0; i < source.length; i++) roots[i] = enabled ? new Root(this, i) : source[i];
        state = enabled ? "deferred" : "disabled";
    }

    DensityFunction[] roots() { return roots.clone(); }

    private synchronized void request() {
        if (!enabled || retired || task != null || !(state.equals("deferred") || state.equals("busy"))) return;
        if (state.equals("busy") && System.nanoTime() < retryAfter) return;
        WeakReference<DensityCompilation> reference = new WeakReference<>(this);
        task = new FutureTask<>(() -> {
            DensityCompilation target = reference.get();
            if (target != null) target.compile();
            return null;
        });
        state = "queued";
        try { COMPILER.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            task = null;
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            state = "busy";
        }
    }

    private void compile() {
        synchronized (this) {
            if (retired) return;
            state = "compiling";
        }
        try {
            DensityGraphCompiler.Compiled result = DensityGraphCompiler.compile(source);
            synchronized (this) {
                if (retired || Thread.currentThread().isInterrupted()) return;
                if (result.metrics().specialized() <= source.length) {
                    state = "opaque";
                    return;
                }
                compiled = result;
                state = "ready";
            }
            VSSLogger.debug("VSS Java density compiler ready: " + result.metrics());
        } catch (CancellationException cancelled) {
            synchronized (this) { if (!retired) state = "cancelled"; }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed(failure);
        }
    }

    private synchronized void failed(Throwable failure) {
        compiled = null;
        if (!retired) {
            state = "fallback";
            VSSLogger.debug("VSS Java density compiler skipped; retaining original graph: " + failure);
        }
    }

    String diagnostics() {
        var current = compiled;
        return "state=" + state + (current == null ? "" : "," + current.metrics());
    }

    DensityGraphCompiler.Metrics metrics() {
        var current = compiled;
        return current == null ? null : current.metrics();
    }

    @Override public synchronized void close() {
        retired = true;
        compiled = null;
        state = "retired";
        if (task != null) {
            task.cancel(true);
            COMPILER.remove(task);
            task = null;
        }
    }

    static final class Root implements DensityFunction.SimpleFunction {
        private final DensityCompilation owner;
        private final int root;

        private Root(DensityCompilation owner, int root) { this.owner = owner; this.root = root; }
        DensityFunction original() { return owner.source[root]; }

        @Override public double compute(DensityFunction.FunctionContext context) {
            var current = owner.compiled;
            if (current == null) {
                if (owner.state.equals("deferred") || owner.state.equals("busy") && System.nanoTime() >= owner.retryAfter)
                    owner.request();
                return original().compute(context);
            }
            try { return current.compute(root, context); }
            catch (LinkageError failure) {
                owner.failed(failure);
                return original().compute(context);
            }
        }

        @Override public double minValue() { return original().minValue(); }
        @Override public double maxValue() { return original().maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Runtime compiled density root");
        }
    }
}
