package dev.xantha.vss.networking.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.function.LongSupplier;

final class RetryBackoff {
    private final Long2LongOpenHashMap retryAfterNanos = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap retryAttempts = new Long2IntOpenHashMap();
    private final LongSupplier nanoClock;
    private final RetryBackoffPolicy policy;

    RetryBackoff(
            LongSupplier nanoClock,
            RetryBackoffPolicy policy) {
        this.nanoClock = nanoClock;
        this.policy = policy;
        retryAfterNanos.defaultReturnValue(0L);
        retryAttempts.defaultReturnValue(0);
    }

    boolean isCoolingDown(long packed, long now) {
        long retryAfter = retryAfterNanos.get(packed);
        if (retryAfter <= 0L) {
            return false;
        }
        if (retryAfter > now) {
            return true;
        }
        retryAfterNanos.remove(packed);
        return false;
    }

    void markBackoff(long packed, boolean dirtyRefresh, boolean generationCandidate) {
        int previousAttempts = retryAttempts.get(packed);
        int attempts = previousAttempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : previousAttempts + 1;
        retryAttempts.put(packed, attempts);
        long delay = policy.retryDelay(dirtyRefresh, generationCandidate, attempts);
        retryAfterNanos.put(packed, nanoClock.getAsLong() + delay);
    }

    void markRateLimited(long packed) {
        markTransientDelay(packed, policy.retryDelay(false, false, 1));
    }

    void markBackpressure(long packed) {
        markTransientDelay(packed, policy.backpressureDelayNanos());
    }

    void markTimeout(long packed) {
        markTransientDelay(packed, policy.retryDelay(false, false, 1));
    }

    void clear(long packed) {
        retryAfterNanos.remove(packed);
        retryAttempts.remove(packed);
    }

    void clearAll() {
        retryAfterNanos.clear();
        retryAttempts.clear();
    }

    private void markTransientDelay(long packed, long delayNanos) {
        retryAttempts.remove(packed);
        retryAfterNanos.put(packed, nanoClock.getAsLong() + Math.max(0L, delayNanos));
    }
}
