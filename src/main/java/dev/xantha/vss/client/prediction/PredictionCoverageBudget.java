package dev.xantha.vss.client.prediction;

import java.util.function.LongSupplier;

/** Shared across preparations in one render frame; optional Voxy suppression can wait. */
final class PredictionCoverageBudget {
    private final int maxGroups;
    private final long maxNanos;
    private final LongSupplier clock;
    private long frame = Long.MIN_VALUE, started;
    private int used;
    private boolean running, exhausted;

    PredictionCoverageBudget() { this(4096, 2_000_000L, System::nanoTime); }
    PredictionCoverageBudget(int maxGroups, long maxNanos, LongSupplier clock) {
        if (maxGroups < 1 || maxNanos < 1) throw new IllegalArgumentException("Coverage budget must be positive");
        this.maxGroups = maxGroups; this.maxNanos = maxNanos; this.clock = clock;
    }
    void beginFrame(long next) {
        if (frame == next) return;
        frame = next; used = 0; running = exhausted = false;
    }
    boolean takeGroup() {
        if (exhausted || used >= maxGroups) return false;
        if (!running) { started = clock.getAsLong(); running = true; }
        // Amortize the clock read, bounding an overrun to one batch of 32 groups.
        if ((used & 31) == 0 && clock.getAsLong() - started >= maxNanos) { exhausted = true; return false; }
        used++; return true;
    }
    int usedGroups() { return used; }
    void clear() { frame = Long.MIN_VALUE; used = 0; running = exhausted = false; }
}
