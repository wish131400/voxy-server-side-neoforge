package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.ModCompat;

/** Grace period for newly acknowledged or discovered columns before regional
 *  handoff. Storage presence and ingest acceptance can both precede GPU work.
 *  This is a scheduling grace period, not a GPU completion fence. */
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
