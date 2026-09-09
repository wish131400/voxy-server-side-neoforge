package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.ModCompat;

/** Settling window for delivery-acked columns: the ingest ack precedes
 *  Voxy's render-node build, so prediction stays visible briefly after
 *  handover.  Probe positives (Voxy's own storage index) skip this window
 *  entirely — that data is already renderable on Voxy's side. */
final class ExactCoverageGate {
    static final long SETTLE_NANOS = 2_000_000_000L;

    private ExactCoverageGate() {
    }

    static boolean ownsPrediction(ModCompat.LocalColumnState state,
                                  long transitionStartedNanos, long nowNanos) {
        return state == ModCompat.LocalColumnState.PRESENT
                && nowNanos - transitionStartedNanos >= SETTLE_NANOS;
    }
}
