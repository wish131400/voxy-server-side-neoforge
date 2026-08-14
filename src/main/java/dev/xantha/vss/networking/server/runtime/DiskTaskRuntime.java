package dev.xantha.vss.networking.server.runtime;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.ArrayDeque;
import java.util.Objects;

public final class DiskTaskRuntime {
    public interface PendingDiskTask {
        void complete();

        boolean isComplete();
    }

    public interface AsyncReadCompletion<T> {
        boolean execute(Runnable continuation);

        boolean complete(T value);

        void fail(Throwable error);

        boolean isComplete();

        boolean hasActiveListeners();
    }

    private final int minThreads;
    private final int maxThreads;
    private final IntSupplier readThreadSupplier;
    private final BooleanSupplier acceptingTasks;
    private final AtomicInteger pendingReads = new AtomicInteger();
    private final AtomicInteger pendingPreloadReads = new AtomicInteger();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicLong manualReadsSubmitted = new AtomicLong();
    private final AtomicLong manualReadsCompleted = new AtomicLong();
    private final AtomicLong manualReadsRejected = new AtomicLong();
    private final AtomicLong preloadReadsSubmitted = new AtomicLong();
    private final AtomicLong preloadReadsCompleted = new AtomicLong();
    private final AtomicLong preloadReadsRejected = new AtomicLong();
    private final AtomicLong readWaitSamples = new AtomicLong();
    private final AtomicLong readWaitNanos = new AtomicLong();
    private final AtomicLong maxReadWaitNanos = new AtomicLong();
    private final AtomicLong coalescedReads = new AtomicLong();
    private final AtomicLong preloadReadsReusedByLive = new AtomicLong();
    private final ConcurrentHashMap<ReadKey, SharedRead<?>> inFlightReads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReadKey, SharedRead<?>> inFlightNbtReads = new ConcurrentHashMap<>();
    private final AsyncReadGate nbtReadGate = new AsyncReadGate();
    private final AtomicLong nbtReadsCoalesced = new AtomicLong();
    private final AtomicLong nbtReadHits = new AtomicLong();
    private final AtomicLong nbtReadMisses = new AtomicLong();
    private final AtomicLong nbtReadFailures = new AtomicLong();
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
        return readTasks.submit(limit, task, onRejected, 0);
    }

    public boolean submitManualRead(
            int limit,
            Consumer<PendingDiskTask> task,
            Consumer<RejectedExecutionException> onRejected) {
        long queuedNanos = System.nanoTime();
        boolean submitted = readTasks.submitManual(
                limit,
                pendingTask -> {
                    recordReadWait(queuedNanos);
                    MeasuredPendingDiskTask measured =
                            new MeasuredPendingDiskTask(pendingTask, manualReadsCompleted);
                    try {
                        task.accept(measured);
                    } catch (RuntimeException | Error error) {
                        measured.complete();
                        throw error;
                    }
                },
                error -> {
                    manualReadsRejected.incrementAndGet();
                    if (onRejected != null) {
                        onRejected.accept(error);
                    }
                }, 0);
        if (submitted) {
            manualReadsSubmitted.incrementAndGet();
        }
        return submitted;
    }

    public boolean submitPreloadRead(
            int totalLimit,
            int reservedManualSlots,
            Runnable task,
            Consumer<RejectedExecutionException> onRejected) {
        int preloadLimit = preloadLimit(totalLimit, reservedManualSlots);
        long queuedNanos = System.nanoTime();
        pendingPreloadReads.incrementAndGet();
        boolean submitted = readTasks.submit(
                preloadLimit,
                () -> {
                    try {
                        recordReadWait(queuedNanos);
                        task.run();
                    } finally {
                        preloadReadsCompleted.incrementAndGet();
                        pendingPreloadReads.updateAndGet(value -> Math.max(0, value - 1));
                    }
                },
                error -> {
                    preloadReadsRejected.incrementAndGet();
                    pendingPreloadReads.updateAndGet(value -> Math.max(0, value - 1));
                    if (onRejected != null) {
                        onRejected.accept(error);
                    }
                }, 1);
        if (submitted) {
            preloadReadsSubmitted.incrementAndGet();
        }
        return submitted;
    }

    /** Shares one persistent-column read between live requests and preload work. */
    @SuppressWarnings("unchecked")
    public <T> boolean submitCoalescedRead(
            ReadKey key,
            boolean preload,
            int totalLimit,
            int reservedManualSlots,
            Supplier<T> task,
            Consumer<T> onComplete,
            Consumer<RejectedExecutionException> onRejected) {
        SharedRead<T> existing = (SharedRead<T>) inFlightReads.get(key);
        if (existing != null) {
            coalescedReads.incrementAndGet();
            if (!preload && existing.preload()) {
                preloadReadsReusedByLive.incrementAndGet();
            }
            existing.add(onComplete, onRejected);
            return true;
        }
        SharedRead<T> created = new SharedRead<>(preload);
        created.add(onComplete, onRejected);
        existing = (SharedRead<T>) inFlightReads.putIfAbsent(key, created);
        if (existing != null) {
            coalescedReads.incrementAndGet();
            if (!preload && existing.preload()) {
                preloadReadsReusedByLive.incrementAndGet();
            }
            existing.add(onComplete, onRejected);
            return true;
        }
        Runnable work = () -> {
            try {
                T value = task.get();
                created.complete(value);
                inFlightReads.remove(key, created);
            } catch (Throwable error) {
                created.fail(new RejectedExecutionException("VSS coalesced disk read failed", error));
                inFlightReads.remove(key, created);
            }
        };
        Consumer<RejectedExecutionException> reject = error -> {
            inFlightReads.remove(key, created);
            created.fail(error);
        };
        boolean submitted = preload
                ? submitPreloadRead(totalLimit, reservedManualSlots, work, reject)
                : submitManualRead(totalLimit, pending -> {
                    try {
                        work.run();
                    } finally {
                        pending.complete();
                    }
                }, reject);
        return submitted;
    }

    public long coalescedReads() {
        return coalescedReads.get();
    }

    public void recordNbtReadHit() {
        nbtReadHits.incrementAndGet();
    }

    public void recordNbtReadMiss() {
        nbtReadMisses.incrementAndGet();
    }

    public void recordNbtReadFailure() {
        nbtReadFailures.incrementAndGet();
    }

    @SuppressWarnings("unchecked")
    public <T> boolean submitCoalescedNbtRead(
            ReadKey key,
            int maxConcurrent,
            int queueLimit,
            int diskTaskLimit,
            BooleanSupplier listenerActive,
            Consumer<AsyncReadCompletion<T>> starter,
            Consumer<T> onComplete,
            Consumer<RejectedExecutionException> onRejected) {
        Objects.requireNonNull(starter, "starter");
        SharedRead<T> existing = (SharedRead<T>) inFlightNbtReads.get(key);
        if (existing != null) {
            coalescedReads.incrementAndGet();
            nbtReadsCoalesced.incrementAndGet();
            existing.add(onComplete, onRejected, listenerActive);
            return true;
        }
        SharedRead<T> created = new SharedRead<>(false);
        created.add(onComplete, onRejected, listenerActive);
        existing = (SharedRead<T>) inFlightNbtReads.putIfAbsent(key, created);
        if (existing != null) {
            coalescedReads.incrementAndGet();
            nbtReadsCoalesced.incrementAndGet();
            existing.add(onComplete, onRejected, listenerActive);
            return true;
        }

        Consumer<RejectedExecutionException> reject = error -> {
            inFlightNbtReads.remove(key, created);
            created.fail(error);
        };
        return nbtReadGate.submit(
                maxConcurrent,
                queueLimit,
                gateEpoch -> startNbtRead(key, created, diskTaskLimit, starter, reject, gateEpoch),
                reject);
    }

    private <T> void startNbtRead(
            ReadKey key,
            SharedRead<T> shared,
            int diskTaskLimit,
            Consumer<AsyncReadCompletion<T>> starter,
            Consumer<RejectedExecutionException> reject,
            long gateEpoch) {
        boolean submitted = submitManualRead(
                diskTaskLimit,
                pending -> {
                    AsyncReadCompletionImpl<T> completion =
                            new AsyncReadCompletionImpl<>(key, shared, pending, gateEpoch);
                    try {
                        if (completion.hasActiveListeners()) {
                            starter.accept(completion);
                        } else {
                            completion.complete(null);
                        }
                    } catch (Throwable error) {
                        completion.fail(error);
                    }
                },
                error -> {
                    nbtReadFailures.incrementAndGet();
                    nbtReadGate.complete(gateEpoch);
                    reject.accept(error);
                });
        if (!submitted) {
            return;
        }
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
        return desiredThreads;
    }

    public void restart() {
        ThreadPoolExecutor oldRead;
        ThreadPoolExecutor oldWrite;
        synchronized (executorLock) {
            oldRead = readExecutor;
            oldWrite = writeExecutor;
            readExecutor = createDiskExecutor("VSS-DiskReader", readThreadSupplier.getAsInt(), true);
            writeExecutor = createDiskExecutor("VSS-DiskWriter", 1, false);
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
        clearCoalescedReads();
    }

    public void clearCoalescedReads() {
        RejectedExecutionException stopped = new RejectedExecutionException("VSS disk read runtime stopped");
        for (var entry : inFlightReads.entrySet()) {
            if (inFlightReads.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().fail(stopped);
            }
        }
        nbtReadGate.clear(stopped);
        for (var entry : inFlightNbtReads.entrySet()) {
            if (inFlightNbtReads.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().fail(stopped);
            }
        }
    }

    public void resetPendingCounts() {
        pendingReads.set(0);
        pendingPreloadReads.set(0);
        pendingWrites.set(0);
    }

    public int pendingReads() {
        return pendingReads.get();
    }

    public int pendingWrites() {
        return pendingWrites.get();
    }

    public boolean awaitWrites(long timeoutMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        while (pendingWrites() > 0 && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pendingWrites() == 0;
    }

    public int pendingPreloadReads() {
        return pendingPreloadReads.get();
    }

    public boolean hasReadCapacity(int limit) {
        return pendingReads() < limit;
    }

    public boolean hasPreloadReadCapacity(int totalLimit, int reservedManualSlots) {
        return !hasLiveReadPressure()
                && pendingReads() < preloadLimit(totalLimit, reservedManualSlots);
    }

    public boolean hasLiveReadPressure() {
        return pendingReads() > pendingPreloadReads() || nbtReadGate.active() > 0 || nbtReadGate.queued() > 0;
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
                pendingWrites(),
                pendingPreloadReads(),
                manualReadsSubmitted.get(),
                manualReadsCompleted.get(),
                manualReadsRejected.get(),
                preloadReadsSubmitted.get(),
                preloadReadsCompleted.get(),
                preloadReadsRejected.get(),
                readWaitSamples.get(),
                readWaitNanos.get(),
                maxReadWaitNanos.get(),
                coalescedReads.get(),
                preloadReadsReusedByLive.get(),
                nbtReadGate.active(),
                nbtReadGate.queued(),
                nbtReadGate.submitted(),
                nbtReadGate.completed(),
                nbtReadHits.get(),
                nbtReadMisses.get(),
                nbtReadFailures.get(),
                nbtReadsCoalesced.get(),
                nbtReadGate.rejected());
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
                    ? createDiskExecutor("VSS-DiskReader", readThreadSupplier.getAsInt(), true)
                    : createDiskExecutor("VSS-DiskWriter", 1, false);
            if (read) {
                readExecutor = created;
            } else {
                writeExecutor = created;
            }
            return created;
        }
    }

    private ThreadPoolExecutor createDiskExecutor(String threadName, int threads, boolean read) {
        int clampedThreads = Math.max(minThreads, Math.min(maxThreads, threads));
        AtomicInteger threadId = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                clampedThreads,
                clampedThreads,
                0L,
                TimeUnit.MILLISECONDS,
                read ? new PriorityBlockingQueue<>() : new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, threadName + "-" + threadId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.setKeepAliveTime(45L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private int desiredReadThreads() {
        return Math.max(minThreads, Math.min(maxThreads, readThreadSupplier.getAsInt()));
    }

    private static int preloadLimit(int totalLimit, int reservedManualSlots) {
        return Math.max(0, Math.max(0, totalLimit) - Math.max(0, reservedManualSlots));
    }

    private void recordReadWait(long queuedNanos) {
        long waitNanos = Math.max(0L, System.nanoTime() - queuedNanos);
        readWaitSamples.incrementAndGet();
        readWaitNanos.addAndGet(waitNanos);
        maxReadWaitNanos.accumulateAndGet(waitNanos, Math::max);
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

    public record Snapshot(
            int readThreads,
            int readQueueSize,
            int writeQueueSize,
            int pendingReads,
            int pendingWrites,
            int pendingPreloadReads,
            long manualReadsSubmitted,
            long manualReadsCompleted,
            long manualReadsRejected,
            long preloadReadsSubmitted,
            long preloadReadsCompleted,
            long preloadReadsRejected,
            long readWaitSamples,
            long readWaitNanos,
            long maxReadWaitNanos,
            long coalescedReads,
            long preloadReadsReusedByLive,
            int nbtReadsActive,
            int nbtReadsQueued,
            long nbtReadsSubmitted,
            long nbtReadsCompleted,
            long nbtReadHits,
            long nbtReadMisses,
            long nbtReadFailures,
            long nbtReadsCoalesced,
            long nbtReadsRejected) {
    }

    public record ReadKey(ResourceLocation dimension, int chunkX, int chunkZ) {
    }

    private static final class SharedRead<T> {
        private final boolean preload;
        private final java.util.ArrayList<Listener<T>> listeners = new java.util.ArrayList<>();

        private SharedRead(boolean preload) {
            this.preload = preload;
        }

        boolean preload() {
            return preload;
        }

        void add(Consumer<T> onComplete, Consumer<RejectedExecutionException> onRejected) {
            add(onComplete, onRejected, () -> true);
        }

        void add(
                Consumer<T> onComplete,
                Consumer<RejectedExecutionException> onRejected,
                BooleanSupplier active) {
            T value;
            RejectedExecutionException failure;
            synchronized (this) {
                if (!completed) {
                    listeners.add(new Listener<>(onComplete, onRejected, active));
                    return;
                }
                value = result;
                failure = error;
            }
            if (!active.getAsBoolean()) {
                return;
            }
            if (failure == null) {
                onComplete.accept(value);
            } else if (onRejected != null) {
                onRejected.accept(failure);
            }
        }

        synchronized boolean hasActiveListeners() {
            for (Listener<T> listener : listeners) {
                if (listener.active().getAsBoolean()) {
                    return true;
                }
            }
            return false;
        }

        void complete(T value) {
            java.util.ArrayList<Listener<T>> pending;
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
                result = value;
                pending = drainLocked();
            }
            for (Listener<T> listener : pending) {
                if (listener.active().getAsBoolean()) {
                    listener.onComplete().accept(value);
                }
            }
        }

        void fail(RejectedExecutionException error) {
            java.util.ArrayList<Listener<T>> pending;
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
                this.error = error;
                pending = drainLocked();
            }
            for (Listener<T> listener : pending) {
                if (listener.active().getAsBoolean() && listener.onRejected() != null) {
                    listener.onRejected().accept(error);
                }
            }
        }

        private java.util.ArrayList<Listener<T>> drainLocked() {
            java.util.ArrayList<Listener<T>> result = new java.util.ArrayList<>(listeners);
            listeners.clear();
            return result;
        }

        private boolean completed;
        private T result;
        private RejectedExecutionException error;
    }

    private record Listener<T>(
            Consumer<T> onComplete,
            Consumer<RejectedExecutionException> onRejected,
            BooleanSupplier active) {
    }

    private static final class MeasuredPendingDiskTask implements PendingDiskTask {
        private final PendingDiskTask delegate;
        private final AtomicLong completedCounter;
        private final AtomicBoolean completed = new AtomicBoolean();

        private MeasuredPendingDiskTask(PendingDiskTask delegate, AtomicLong completedCounter) {
            this.delegate = delegate;
            this.completedCounter = completedCounter;
        }

        @Override
        public void complete() {
            if (completed.compareAndSet(false, true)) {
                completedCounter.incrementAndGet();
                delegate.complete();
            }
        }

        @Override
        public boolean isComplete() {
            return delegate.isComplete();
        }
    }

    private final class AsyncReadCompletionImpl<T> implements AsyncReadCompletion<T> {
        private final ReadKey key;
        private final SharedRead<T> shared;
        private final PendingDiskTask pending;
        private final long gateEpoch;
        private final AtomicBoolean completed = new AtomicBoolean();

        private AsyncReadCompletionImpl(
                ReadKey key,
                SharedRead<T> shared,
                PendingDiskTask pending,
                long gateEpoch) {
            this.key = key;
            this.shared = shared;
            this.pending = pending;
            this.gateEpoch = gateEpoch;
        }

        @Override
        public boolean execute(Runnable continuation) {
            if (completed.get()) {
                return false;
            }
            return readTasks.executeContinuation(
                    () -> {
                        if (!completed.get()) {
                            continuation.run();
                        }
                    },
                    this::fail,
                    0);
        }

        @Override
        public boolean complete(T value) {
            if (completed.compareAndSet(false, true)) {
                pending.complete();
                inFlightNbtReads.remove(key, shared);
                nbtReadGate.complete(gateEpoch);
                shared.complete(value);
                return true;
            }
            return false;
        }

        @Override
        public void fail(Throwable error) {
            if (completed.compareAndSet(false, true)) {
                nbtReadFailures.incrementAndGet();
                pending.complete();
                inFlightNbtReads.remove(key, shared);
                nbtReadGate.complete(gateEpoch);
                shared.fail(error instanceof RejectedExecutionException rejected
                        ? rejected
                        : new RejectedExecutionException("VSS asynchronous NBT read failed", error));
            }
        }

        @Override
        public boolean isComplete() {
            return completed.get();
        }

        @Override
        public boolean hasActiveListeners() {
            return shared.hasActiveListeners();
        }
    }

    private static final class AsyncReadGate {
        private final ArrayDeque<GateEntry> queue = new ArrayDeque<>();
        private long submitted;
        private long completed;
        private long rejected;
        private long epoch = 1L;
        private int active;
        private int maxConcurrent = 1;

        boolean submit(
                int maxConcurrent,
                int queueLimit,
                LongConsumer starter,
            Consumer<RejectedExecutionException> onRejected) {
            GateEntry entry;
            RejectedExecutionException rejection = null;
            synchronized (this) {
                this.maxConcurrent = Math.max(1, maxConcurrent);
                submitted++;
                entry = new GateEntry(starter, onRejected, epoch);
                if (active < this.maxConcurrent) {
                    active++;
                } else if (queue.size() < Math.max(0, queueLimit)) {
                    queue.addLast(entry);
                    entry = null;
                } else {
                    rejected++;
                    rejection = new RejectedExecutionException("VSS NBT read queue is full");
                    entry = null;
                }
            }
            if (rejection != null) {
                onRejected.accept(rejection);
                return false;
            }
            if (entry != null) {
                entry.starter().accept(entry.epoch());
            }
            return true;
        }

        void complete(long completedEpoch) {
            GateEntry next;
            synchronized (this) {
                if (completedEpoch != epoch) {
                    return;
                }
                completed++;
                active = Math.max(0, active - 1);
                next = active < maxConcurrent ? queue.pollFirst() : null;
                if (next != null) {
                    active++;
                }
            }
            if (next != null) {
                next.starter().accept(next.epoch());
            }
        }

        void clear(RejectedExecutionException stopped) {
            ArrayDeque<GateEntry> pending;
            synchronized (this) {
                pending = new ArrayDeque<>(queue);
                queue.clear();
                rejected += pending.size() + active;
                active = 0;
                epoch++;
            }
            for (GateEntry entry : pending) {
                entry.onRejected().accept(stopped);
            }
        }

        synchronized int active() {
            return active;
        }

        synchronized int queued() {
            return queue.size();
        }

        synchronized long submitted() {
            return submitted;
        }

        synchronized long completed() {
            return completed;
        }

        synchronized long rejected() {
            return rejected;
        }
    }

    private record GateEntry(
            LongConsumer starter,
            Consumer<RejectedExecutionException> onRejected,
            long epoch) {
    }
}
