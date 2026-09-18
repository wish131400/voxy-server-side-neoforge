package dev.xantha.vss.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Bounded-by-call-site counters; keys must be constants, never positions or request IDs. */
public final class DiagnosticCounters {
    private final BooleanSupplier enabled;
    private final long intervalNanos;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private boolean started;
    private long lastReportNanos;

    public DiagnosticCounters(BooleanSupplier enabled, long intervalNanos) {
        this.enabled = enabled;
        this.intervalNanos = intervalNanos;
    }

    public void record(String reason) {
        add(reason, 1L);
    }

    public synchronized void add(String reason, long amount) {
        if (enabled.getAsBoolean() && amount > 0L) {
            counts.merge(reason, amount, Long::sum);
        }
    }

    /** Interval counts, not lifetime totals. Empty intervals still produce a heartbeat. */
    public synchronized String poll(long now) {
        if (!enabled.getAsBoolean()) {
            reset();
            return null;
        }
        if (!started) {
            started = true;
            lastReportNanos = now;
            return null;
        }
        if (now - lastReportNanos < intervalNanos) {
            return null;
        }
        String report = "windowMs=" + (now - lastReportNanos) / 1_000_000L + ", events=" + counts;
        counts.clear();
        lastReportNanos = now;
        return report;
    }

    public synchronized void reset() {
        counts.clear();
        started = false;
        lastReportNanos = 0L;
    }
}
