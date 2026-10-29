package dev.xantha.vss.client.prediction;

import java.util.Locale;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Shared, configuration-bounded admission for prediction and local generation.
 * Worker presets and lower-priority threads leave game headroom. Machine-wide CPU
 * utilization and capped frame rates are observations, not admission signals. */
public final class PredictionCpuBudget {
    public static final PredictionCpuBudget SHARED = runtimeBudget();
    private static final long GENERATION_STALE_NANOS = 2_000_000_000L;
    private static final long ADMISSION_RETRY_NANOS = 100_000_000L;
    private final IntSupplier configuredLimit;
    private final LongSupplier clock;
    private final boolean keepCoverageLane;
    private int active, activeDetail, generationInFlight, reserved, queuedOverflow;
    private long generationAt, generationWaitAt, deferrals;
    private boolean generationWaiting;
    private final boolean[] admissionDelayed = new boolean[2];
    private final long[] admissionRejectedAt = new long[2];

    PredictionCpuBudget(IntSupplier configuredLimit, LongSupplier clock) {
        this(configuredLimit, clock, true);
    }

    private PredictionCpuBudget(IntSupplier configuredLimit, LongSupplier clock, boolean keepCoverageLane) {
        this.configuredLimit = configuredLimit;
        this.clock = clock;
        this.keepCoverageLane = keepCoverageLane;
    }

    static PredictionCpuBudget fixed(int workers) {
        return new PredictionCpuBudget(() -> workers, System::nanoTime, false);
    }

    private static PredictionCpuBudget runtimeBudget() {
        int processors = Runtime.getRuntime().availableProcessors();
        return new PredictionCpuBudget(() -> PredictionPerformanceProfile.current().poolSize(processors),
                System::nanoTime);
    }

    private int limit() { return Math.max(1, configuredLimit.getAsInt()); }

    private int detailLimit() {
        int cap = limit();
        return keepCoverageLane && cap > 1 ? cap - 1 : cap;
    }

    private int localGeneration() {
        long age = clock.getAsLong() - generationAt;
        int running = age >= 0 && age < GENERATION_STALE_NANOS ? generationInFlight : 0;
        return generationWaiting() ? Math.max(limit() > 1 ? 2 : 1, running) : running;
    }

    private boolean generationWaiting() {
        long age = clock.getAsLong() - generationWaitAt;
        return generationWaiting && age >= 0 && age < GENERATION_STALE_NANOS;
    }

    /** Never waits on a render or worker thread. A completed lease wakes admission. */
    synchronized Lease tryAcquire(boolean urgent) {
        int cap = limit();
        // Existing local generation may have been admitted under an earlier
        // setting. Allow one coverage update while that older debt drains.
        boolean coverageRecovery = urgent && active == 0 && !generationWaiting();
        if (active >= cap || active + localGeneration() >= cap && !coverageRecovery
                || !urgent && activeDetail + localGeneration() >= detailLimit()) {
            deferrals++;
            int lane = urgent ? 0 : 1;
            admissionDelayed[lane] = true;
            admissionRejectedAt[lane] = clock.getAsLong();
            return null;
        }
        active++;
        if (!urgent) activeDetail++;
        return new Lease(!urgent);
    }

    /** Strict pre-submission reservation, distinct from an executing lease. */
    synchronized Reservation tryReserve(boolean urgent) {
        if (admissionWaiting(urgent)) { deferrals++; return null; }
        int cap = limit();
        boolean coverageRecovery = urgent && active == 0 && reserved == 0 && !generationWaiting();
        if (active + reserved >= cap
                || active + reserved + localGeneration() >= cap && !coverageRecovery) {
            deferrals++;
            return null;
        }
        reserved++;
        return new Reservation(false);
    }

    /** A small shared queue keeps workers fed; executing work still needs a lease. */
    synchronized Reservation tryReserveQueued(boolean urgent, int queueLimit) {
        if (admissionWaiting(urgent) || queuedOverflow >= Math.max(1, queueLimit)) {
            deferrals++;
            return null;
        }
        queuedOverflow++;
        return new Reservation(true);
    }

    /** Back off only after an actual capacity refusal; release wakes immediately.
     * The short fallback also permits retries when external generation expires. */
    private boolean admissionWaiting(boolean urgent) {
        if (!urgent && activeDetail + localGeneration() >= detailLimit()) return true;
        int lane = urgent ? 0 : 1;
        long elapsed = clock.getAsLong() - admissionRejectedAt[lane];
        return admissionDelayed[lane] && elapsed >= 0 && elapsed < ADMISSION_RETRY_NANOS;
    }

    /** Request counts coordinate local terrain generation with prediction. */
    public synchronized void observeLocalGeneration(boolean local, int inFlight) {
        generationInFlight = local ? Math.max(0, inFlight) : 0;
        if (!local || inFlight > 0) generationWaiting = false;
        generationAt = clock.getAsLong();
    }

    /** Returns a total request ceiling; callers subtract requests already running. */
    public synchronized int localGenerationLimit(int configured, boolean predictionActive) {
        if (configured <= 0) return 0;
        int cap = limit();
        int spare = cap - active - (predictionActive && cap > 1 ? 1 : 0);
        int allowed = Math.max(0, Math.min(configured, spare));
        if (allowed == 0) { generationWaiting = true; generationWaitAt = clock.getAsLong(); }
        return allowed;
    }

    /** Atomically reserves the scan's total ceiling against prediction admission. */
    public synchronized int reserveLocalGeneration(int ceiling, boolean predictionActive, int inFlight) {
        int cap = limit();
        int spare = cap - active - (predictionActive && cap > 1 ? 1 : 0);
        int allowed = Math.max(0, Math.min(ceiling, spare));
        if (allowed > 0 || ceiling <= 0) generationWaiting = false;
        else { generationWaiting = true; generationWaitAt = clock.getAsLong(); }
        generationInFlight = Math.max(Math.max(0, inFlight), allowed);
        generationAt = clock.getAsLong();
        return allowed;
    }

    public synchronized String diagnostics() {
        return String.format(Locale.ROOT,
                "mode=configured,limit=%d,active=%d,detail=%d,localGeneration=%d,queued=%d,reserved=%d,deferrals=%d",
                limit(), active, activeDetail, localGeneration(), queuedOverflow, reserved, deferrals);
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
                admissionDelayed[0] = admissionDelayed[1] = false;
            }
        }
    }

    /** A bounded executor queue slot, released on start or cancellation. */
    final class Reservation implements AutoCloseable {
        private final boolean queuedOnly;
        private boolean released;
        private Reservation(boolean queuedOnly) { this.queuedOnly = queuedOnly; }
        void consume() { close(); }
        @Override public void close() {
            synchronized (PredictionCpuBudget.this) {
                if (released) return;
                released = true;
                if (queuedOnly) queuedOverflow--;
                else reserved--;
            }
        }
    }
}
