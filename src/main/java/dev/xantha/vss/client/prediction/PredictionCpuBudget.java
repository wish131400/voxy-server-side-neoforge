package dev.xantha.vss.client.prediction;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Shared admission for expensive prediction work and integrated-server generation requests. */
public final class PredictionCpuBudget {
    /* The Windows management getters can take tens of milliseconds. Keep them off the
       render and prediction monitors by sampling on one daemon thread. */
    private static final ScheduledExecutorService METRIC_SAMPLER =
            Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "vss-cpu-budget-sampler");
                thread.setDaemon(true);
                return thread;
            });
    public static final PredictionCpuBudget SHARED = runtimeBudget();
    private static final long SAMPLE_NANOS = 500_000_000L;
    private static final long RECOVERY_NANOS = 3_000_000_000L;
    private static final long GENERATION_STALE_NANOS = 2_000_000_000L;
    private static final long DETAIL_TRICKLE_NANOS = 2_000_000_000L;
    private final IntSupplier configuredLimit;
    private final DoubleSupplier systemLoad, processLoad, frameRate;
    private final LongSupplier clock;
    private final boolean adaptive;
    private final boolean backgroundSampling;
    private long sampledAt, recoverySince, lastDetailAt;
    private boolean sampled, recovering, detailDelayed;
    private double cpu = -1, fps = -1;
    private int pressure, active, activeDetail, generationInFlight;
    private int reserved, reservedDetail;
    /** Reservations admitted into a bounded executor queue beyond active CPU capacity. */
    private int queuedOverflow, queuedOverflowDetail;
    private long generationAt;
    private long deferrals;
    private volatile MetricSample latestSample;
    private final AtomicLong sampleSequence = new AtomicLong();
    private long appliedSampleSequence;

    PredictionCpuBudget(IntSupplier configuredLimit, DoubleSupplier systemLoad,
                        DoubleSupplier processLoad, DoubleSupplier frameRate, LongSupplier clock) {
        // Keep the package-private constructor deterministic for existing tests and
        // callers that provide a fake clock. The runtime singleton opts into the
        // daemon sampler explicitly below.
        this(configuredLimit, systemLoad, processLoad, frameRate, clock, true, false);
    }

    /** Deterministic/manual constructor used by fake-clock tests. */
    PredictionCpuBudget(IntSupplier configuredLimit, DoubleSupplier systemLoad,
                        DoubleSupplier processLoad, DoubleSupplier frameRate,
                        LongSupplier clock, boolean backgroundSampling) {
        this(configuredLimit, systemLoad, processLoad, frameRate, clock, true, backgroundSampling);
    }

    private PredictionCpuBudget(IntSupplier configuredLimit, DoubleSupplier systemLoad,
                                DoubleSupplier processLoad, DoubleSupplier frameRate,
                                LongSupplier clock, boolean adaptive, boolean backgroundSampling) {
        this.configuredLimit = configuredLimit;
        this.systemLoad = systemLoad;
        this.processLoad = processLoad;
        this.frameRate = frameRate;
        this.clock = clock;
        this.adaptive = adaptive;
        this.backgroundSampling = adaptive && backgroundSampling;
        if (this.backgroundSampling) {
            METRIC_SAMPLER.scheduleWithFixedDelay(this::sampleMetrics, 0, SAMPLE_NANOS,
                    TimeUnit.NANOSECONDS);
        }
    }

    static PredictionCpuBudget fixed(int workers) {
        return new PredictionCpuBudget(() -> workers, () -> -1, () -> -1, () -> -1,
                System::nanoTime, false, false);
    }

    private static PredictionCpuBudget runtimeBudget() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        var extended = os instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
        int processors = Runtime.getRuntime().availableProcessors();
        return new PredictionCpuBudget(() -> PredictionPerformanceProfile.current().poolSize(processors),
                () -> extended == null ? -1 : extended.getCpuLoad(),
                () -> extended == null ? -1 : extended.getProcessCpuLoad(),
                PredictionFramePace::averageFps, System::nanoTime, true);
    }

    private static double validLoad(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1 ? value : -1;
    }

    private void sampleMetrics() {
        // Called only by the daemon sampler. Do not synchronize here: a slow
        // management getter must never delay an admission query.
        long now;
        double sampledCpu = Math.max(readLoad(systemLoad), readLoad(processLoad));
        double sampledFps = readFrameRate(frameRate);
        try {
            now = clock.getAsLong();
        } catch (Throwable ignored) {
            return;
        }
        latestSample = new MetricSample(sampleSequence.incrementAndGet(), now, sampledCpu, sampledFps);
    }

    private static double readLoad(DoubleSupplier supplier) {
        try {
            return validLoad(supplier.getAsDouble());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static double readFrameRate(DoubleSupplier supplier) {
        try {
            double value = supplier.getAsDouble();
            return Double.isFinite(value) && value > 0 ? value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void refresh() {
        if (!adaptive) return;
        if (backgroundSampling) {
            MetricSample sample = latestSample;
            if (sample == null) return;
            synchronized (this) {
                if (sample.sequence <= appliedSampleSequence) return;
                appliedSampleSequence = sample.sequence;
                sampled = true;
                sampledAt = sample.sampledAt;
                applyMetrics(sample.sampledAt, sample.cpu, sample.fps);
            }
            return;
        }

        // Deterministic/manual budgets retain fake-clock sampling cadence. Reserve
        // the timestamp under the monitor, then invoke arbitrary suppliers outside it.
        long now = clock.getAsLong();
        long sequence;
        synchronized (this) {
            long elapsed = now - sampledAt;
            if (sampled && elapsed >= 0 && elapsed < SAMPLE_NANOS) return;
            sampledAt = now;
            sampled = true;
            sequence = sampleSequence.incrementAndGet();
        }
        double sampledCpu = Math.max(readLoad(systemLoad), readLoad(processLoad));
        double sampledFps = readFrameRate(frameRate);
        now = clock.getAsLong();
        synchronized (this) {
            if (sequence <= appliedSampleSequence) return;
            appliedSampleSequence = sequence;
            applyMetrics(now, sampledCpu, sampledFps);
        }
    }

    /** Applies one already-collected sample. Caller must hold this monitor. */
    private void applyMetrics(long now, double sampledCpu, double sampledFps) {
        cpu = sampledCpu;
        fps = sampledFps;
        int observed = cpu >= .95 || fps > 0 && fps < 30 ? 2
                : cpu >= .85 || fps > 0 && fps < 45 ? 1 : 0;
        if (observed > pressure) {
            pressure = observed;
            recovering = false;
            lastDetailAt = now;
            detailDelayed = true;
        } else if (observed < pressure && (cpu < 0 || cpu < .80) && (fps <= 0 || fps >= 50)) {
            if (!recovering) { recoverySince = now; recovering = true; }
            if (now - recoverySince >= RECOVERY_NANOS) {
                pressure--;
                recovering = false;
            }
        } else recovering = false;
    }

    private record MetricSample(long sequence, long sampledAt, double cpu, double fps) { }

    private boolean detailWaiting() {
        long elapsed = clock.getAsLong() - lastDetailAt;
        return detailDelayed && elapsed >= 0 && elapsed < DETAIL_TRICKLE_NANOS;
    }

    private int limit() {
        return Math.max(1, Math.max(1, configuredLimit.getAsInt()) >> pressure);
    }

    private int localGeneration() {
        long age = clock.getAsLong() - generationAt;
        return age >= 0 && age < GENERATION_STALE_NANOS ? generationInFlight : 0;
    }

    /** No waiting on a worker or render thread. Refused tiles retry on the next planning tick. */
    Lease tryAcquire(boolean urgent) {
        return tryAcquire(urgent, false);
    }

    /** A paused tier may complete current targets, with the same bounded trickle as saturation. */
    Lease tryAcquire(boolean urgent, boolean pausedDetail) {
        refresh();
        synchronized (this) {
            int cap = limit();
            boolean trickle = adaptive && (pressure >= 2 || pausedDetail);
            int detailCap = !adaptive ? cap : trickle ? 1 : cap > 1 ? cap - 1 : 1;
            // Existing generation is allowed to finish. Keep one coverage/dirty-update lane
            // alive even while requests admitted under an earlier budget are draining.
            boolean coverageRecovery = urgent && active == 0;
            if (active >= cap || active + localGeneration() >= cap && !coverageRecovery
                    || !urgent && (activeDetail + localGeneration() >= detailCap
                        || trickle && detailWaiting())) {
                deferrals++;
                return null;
            }
            active++;
            if (!urgent) {
                activeDetail++;
                if (trickle) { lastDetailAt = clock.getAsLong(); detailDelayed = true; }
            }
            return new Lease(!urgent);
        }
    }

    /**
     * Reserves one admission slot before a tile is submitted to the executor. This
     * prevents a saturated queue from repeatedly starting workers which immediately
     * fail their final lease check. The worker must call {@link Reservation#consume()}
     * immediately before {@link #tryAcquire(boolean, boolean)}; cancellation paths
     * call {@link Reservation#close()} instead.
     */
    Reservation tryReserve(boolean urgent, boolean pausedDetail) {
        refresh();
        synchronized (this) {
            int cap = limit();
            boolean trickle = adaptive && (pressure >= 2 || pausedDetail);
            int detailCap = !adaptive ? cap : trickle ? 1 : cap > 1 ? cap - 1 : 1;
            int queued = reserved;
            int queuedGeneration = localGeneration();
            // Keep the existing coverage-recovery exception to allow one urgent update
            // through an older integrated-server reservation, but never duplicate it.
            boolean coverageRecovery = urgent && active == 0 && queued == 0;
            // Keep one bounded handoff slot for urgent coverage work when a
            // worker is already occupying the shared budget. The task waits
            // in the executor queue and gets the normal final lease check;
            // this avoids dropping the next coverage target while preventing
            // an unbounded queue of workers that will immediately refuse.
            boolean handoff = urgent && active >= cap && queued == 0 && queuedGeneration == 0;
            // Reservations own total worker capacity. The detail lane is
            // intentionally checked again by tryAcquire after a worker starts:
            // queued managers must be allowed to wait for the same lease rather
            // than being discarded before they ever enter the executor queue.
            if (active + queued >= cap && !handoff
                    || active + queued + queuedGeneration >= cap && !coverageRecovery && !handoff) {
                deferrals++;
                return null;
            }
            reserved++;
            if (!urgent) reservedDetail++;
            return new Reservation(!urgent);
        }
    }

    /**
     * Admission for a bounded executor queue. Queue slots are separate from
     * active CPU capacity: the worker still performs the final non-blocking
     * lease check, while a small queue keeps a single worker fed without
     * repeatedly rebuilding the same planner frontier.
     */
    Reservation tryReserveQueued(boolean urgent, boolean pausedDetail, int queueLimit) {
        refresh();
        synchronized (this) {
            int limit = Math.max(1, queueLimit);
            if (queuedOverflow >= limit) {
                deferrals++;
                return null;
            }
            queuedOverflow++;
            if (!urgent) queuedOverflowDetail++;
            return new Reservation(!urgent, true);
        }
    }

    boolean allowsDetail() {
        refresh();
        synchronized (this) {
            // A persistent GPU bottleneck must not make detail completion impossible.
            return pressure < 2 || activeDetail == 0 && !detailWaiting();
        }
    }

    /** Planning hint for an important current target during a tier's PAUSE.
     *  The worker must still acquire its bounded detail lease before doing work. */
    boolean allowsDetailTrickle() {
        refresh();
        synchronized (this) {
            return pressure >= 1 && activeDetail == 0 && !detailWaiting();
        }
    }

    /** The request count is a conservative admission bound, not a count of server CPU threads. */
    public void observeLocalGeneration(boolean local, int inFlight) {
        long now = clock.getAsLong();
        synchronized (this) {
            generationInFlight = local ? Math.max(0, inFlight) : 0;
            generationAt = now;
        }
    }

    /** Returns a total request ceiling; callers subtract their existing in-flight requests. */
    public int localGenerationLimit(int configured, boolean predictionActive) {
        refresh();
        synchronized (this) {
            if (configured <= 0 || pressure >= 2) return 0;
            int spare = limit() - active - (predictionActive ? 1 : 0);
            return Math.max(0, Math.min(configured, spare));
        }
    }

    /** Reserve the scan's total ceiling atomically against concurrent prediction admission.
     *  The caller publishes the actual request count in a finally block after scanning. */
    public int reserveLocalGeneration(int ceiling, boolean predictionActive, int inFlight) {
        refresh();
        long now = clock.getAsLong();
        synchronized (this) {
            int allowed;
            if (ceiling <= 0 || pressure >= 2) allowed = 0;
            else {
                int spare = limit() - active - (predictionActive ? 1 : 0);
                allowed = Math.max(0, Math.min(ceiling, spare));
            }
            generationInFlight = Math.max(Math.max(0, inFlight), allowed);
            generationAt = now;
            return allowed;
        }
    }

    public String diagnostics() {
        refresh();
        synchronized (this) {
            return String.format(Locale.ROOT,
                    "cpu=%.3f,fps=%.1f,pressure=%d,limit=%d,active=%d,detail=%d,localGeneration=%d,deferrals=%d",
                    cpu, fps, pressure, limit(), active, activeDetail, localGeneration(), deferrals);
        }
    }

    final class Lease implements AutoCloseable {
        private final boolean detail;
        private boolean closed;
        private Lease(boolean detail) { this.detail = detail; }
        @Override public void close() {
            synchronized (PredictionCpuBudget.this) {
                if (closed) return;
                closed = true;
                active--;
                if (detail) activeDetail--;
            }
        }
    }

    /** A queue admission token, distinct from the lease held while work is executing. */
    final class Reservation implements AutoCloseable {
        private final boolean detail;
        private final boolean queuedOnly;
        private boolean consumed;
        private boolean closed;

        private Reservation(boolean detail) { this(detail, false); }
        private Reservation(boolean detail, boolean queuedOnly) {
            this.detail = detail;
            this.queuedOnly = queuedOnly;
        }

        /** Releases the queued reservation before the worker performs its final check. */
        void consume() {
            synchronized (PredictionCpuBudget.this) {
                if (consumed || closed) return;
                consumed = true;
                releaseReservation();
            }
        }

        @Override public void close() {
            synchronized (PredictionCpuBudget.this) {
                if (consumed || closed) return;
                closed = true;
                releaseReservation();
            }
        }

        private void releaseReservation() {
            if (queuedOnly) {
                queuedOverflow--;
                if (detail) queuedOverflowDetail--;
            } else {
                reserved--;
                if (detail) reservedDetail--;
            }
        }
    }
}
