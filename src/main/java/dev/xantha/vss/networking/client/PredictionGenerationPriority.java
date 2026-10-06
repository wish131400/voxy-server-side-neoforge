package dev.xantha.vss.networking.client;

import dev.xantha.vss.client.prediction.PredictionLoadingProgress;

/** Per-player generation admission only. Existing transfers and dirty refreshes keep running. */
final class PredictionGenerationPriority {
    private static final long SECOND = 1_000_000_000L;
    private static final long STALL_DELAY = 45 * SECOND;
    private static final long MAX_EXCLUSIVE_DELAY = 120 * SECOND;
    private static final long KEEPALIVE_WINDOW = 5 * SECOND;
    private static final long KEEPALIVE_COOLDOWN = 15 * SECOND;
    private enum Phase { DISABLED, PREDICTION, RAMP, NORMAL, KEEPALIVE, COOLDOWN }
    private Phase phase = Phase.DISABLED;
    private long holdSince, progressSince, readySince, regressionSince, rampSince;
    private boolean settling, regressing;
    private long keepaliveUntil, nextKeepaliveAt;
    private int bestScore, lastLimit, lastBudget;
    private String reason = "disabled";
    private String keepaliveReason = "timeout-keepalive";

    int limit(int configured, boolean enabled, PredictionLoadingProgress progress, long now) {
        return limit(configured, enabled, progress, configured, now);
    }

    /** availableBudget includes local CPU/frame headroom; remote generation may pass configured. */
    int limit(int configured, boolean enabled, PredictionLoadingProgress progress, int availableBudget, long now) {
        int cap = Math.min(Math.max(0, configured), Math.max(0, availableBudget));
        lastBudget = cap;
        if (!enabled || configured <= 0) {
            reset();
            lastBudget = cap;
            return publish(cap, configured <= 0 ? "disabled" : "prediction-disabled");
        }
        if (phase == Phase.DISABLED) hold(progress, now);
        int score = progress.score();
        if (score > bestScore) {
            bestScore = score;
            progressSince = now;
        }
        // Pressure revokes new admission immediately, including a timeout pulse.
        // Running requests are accounted for and drained by the request window.
        if (cap == 0) {
            settling = false;
            if (phase == Phase.RAMP || phase == Phase.NORMAL) hold(progress, now);
            if (phase == Phase.KEEPALIVE) {
                phase = Phase.COOLDOWN;
                nextKeepaliveAt = now + KEEPALIVE_COOLDOWN;
            }
            return publish(0, "budget");
        }
        if (phase == Phase.RAMP || phase == Phase.NORMAL) {
            // Small or brief plan changes do not repeatedly reacquire an exclusive turn.
            if (!progress.ready(85)) {
                if (!regressing) { regressionSince = now; regressing = true; }
                if (now - regressionSince >= 2 * SECOND) {
                    hold(progress, now);
                    return publish(0, progress.waitingFor(95));
                }
            } else regressing = false;
        } else {
            if (progress.ready(95)) {
                if (!settling) { readySince = now; settling = true; }
            } else settling = false;
            if (settling && now - readySince >= SECOND) {
                phase = Phase.RAMP;
                rampSince = now;
                regressing = false;
            } else {
                if (phase == Phase.KEEPALIVE && now - keepaliveUntil >= 0) {
                    phase = Phase.COOLDOWN;
                    nextKeepaliveAt = now + KEEPALIVE_COOLDOWN;
                }
                if (phase == Phase.KEEPALIVE) return publish(1, keepaliveReason);
                boolean stalled = now - progressSince >= STALL_DELAY;
                boolean waited = now - holdSince >= MAX_EXCLUSIVE_DELAY;
                if ((stalled || waited) && now - nextKeepaliveAt >= 0) {
                    phase = Phase.KEEPALIVE;
                    keepaliveUntil = now + KEEPALIVE_WINDOW;
                    keepaliveReason = stalled ? "stall-keepalive" : "wait-keepalive";
                    return publish(1, keepaliveReason);
                }
                return publish(0, phase == Phase.COOLDOWN ? "keepalive-cooldown" : progress.waitingFor(95));
            }
        }
        if (phase == Phase.RAMP) {
            int quarters = (int) Math.min(4, 1 + Math.max(0, now - rampSince) / SECOND);
            if (quarters < 4) return publish(Math.min(cap,
                    Math.max(1, (int) (((long) configured * quarters + 3) / 4))), "detail-ready");
            phase = Phase.NORMAL;
        }
        return publish(cap, "detail-ready");
    }

    String diagnostics() {
        return phase.name().toLowerCase(java.util.Locale.ROOT)
                + "(reason=" + reason + ",limit=" + lastLimit + ",budget=" + lastBudget + ")";
    }

    void reset() {
        phase = Phase.DISABLED;
        settling = regressing = false;
        keepaliveUntil = nextKeepaliveAt = 0;
        lastLimit = lastBudget = 0;
        reason = "disabled";
        keepaliveReason = "timeout-keepalive";
    }

    private int publish(int limit, String reason) {
        lastLimit = limit;
        this.reason = reason;
        return limit;
    }

    private void hold(PredictionLoadingProgress progress, long now) {
        phase = Phase.PREDICTION;
        holdSince = progressSince = now;
        bestScore = progress.score();
        settling = regressing = false;
        keepaliveUntil = 0;
        nextKeepaliveAt = now;
        keepaliveReason = "timeout-keepalive";
    }
}
