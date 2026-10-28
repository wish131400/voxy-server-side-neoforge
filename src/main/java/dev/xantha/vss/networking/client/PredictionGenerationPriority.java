package dev.xantha.vss.networking.client;

import dev.xantha.vss.client.prediction.PredictionLoadingProgress;

/** Prediction readiness adjusts concurrency; it never makes real terrain wait for a completed view. */
final class PredictionGenerationPriority {
    private int lastLimit, lastBudget;
    private String reason = "disabled";

    int limit(int configured, boolean enabled, PredictionLoadingProgress progress, long now) {
        return limit(configured, enabled, progress, configured, now);
    }

    int limit(int configured, boolean enabled, PredictionLoadingProgress progress, int availableBudget, long now) {
        int cap = Math.min(Math.max(0, configured), Math.max(0, availableBudget));
        lastBudget = cap;
        if (cap == 0) return publish(0, configured <= 0 ? "disabled" : "budget");
        if (!enabled) return publish(cap, "prediction-disabled");
        if (progress.ready(95)) return publish(cap, "detail-ready");
        // Retain a continuous real-generation lane during startup, movement and
        // surface refinement, with the other half left to prediction work.
        return publish(Math.max(1, cap / 2), "shared-" + progress.waitingFor(95));
    }

    String diagnostics() {
        return "shared(reason=" + reason + ",limit=" + lastLimit + ",budget=" + lastBudget + ")";
    }

    void reset() { lastLimit = lastBudget = 0; reason = "disabled"; }

    private int publish(int limit, String reason) {
        lastLimit = limit; this.reason = reason; return limit;
    }
}
