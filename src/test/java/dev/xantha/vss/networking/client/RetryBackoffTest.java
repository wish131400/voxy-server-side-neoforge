package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    @Test
    void firstNormalBackoffUsesBaseDelay() {
        ManualClock clock = new ManualClock(1_000L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, false, false);

        assertTrue(backoff.isCoolingDown(42L, 1_999L));
        assertFalse(backoff.isCoolingDown(42L, 2_000L));
    }

    @Test
    void generationBackoffUsesGenerationCurve() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, false, true);
        backoff.markBackoff(42L, false, true);
        backoff.markBackoff(42L, false, true);
        backoff.markBackoff(42L, false, true);

        assertTrue(backoff.isCoolingDown(42L, 9_999L));
        assertFalse(backoff.isCoolingDown(42L, 10_000L));
    }

    @Test
    void normalBackoffUsesItsOwnCurveInsteadOfGenerationCap() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);

        assertTrue(backoff.isCoolingDown(42L, 15_999L));
        assertFalse(backoff.isCoolingDown(42L, 16_000L));
    }

    @Test
    void dirtyRefreshUsesDirtyDelay() {
        ManualClock clock = new ManualClock(100L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, true, true);

        assertTrue(backoff.isCoolingDown(42L, 599L));
        assertFalse(backoff.isCoolingDown(42L, 600L));
    }

    @Test
    void backpressureUsesDedicatedShortDelay() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackpressure(42L);

        assertTrue(backoff.isCoolingDown(42L, 249L));
        assertFalse(backoff.isCoolingDown(42L, 250L));
    }

    @Test
    void rateLimitUsesFixedDelayAndDoesNotAccumulateAttempts() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markRateLimited(42L);
        backoff.markRateLimited(42L);
        backoff.markRateLimited(42L);

        assertTrue(backoff.isCoolingDown(42L, 999L));
        assertFalse(backoff.isCoolingDown(42L, 1_000L));
    }

    @Test
    void rateLimitClearsPreviousExponentialAttemptState() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);
        backoff.markRateLimited(42L);
        backoff.markBackoff(42L, false, false);

        assertTrue(backoff.isCoolingDown(42L, 999L));
        assertFalse(backoff.isCoolingDown(42L, 1_000L));
    }

    @Test
    void backpressureClearsPreviousExponentialAttemptState() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);
        backoff.markBackpressure(42L);
        backoff.markBackoff(42L, false, false);

        assertTrue(backoff.isCoolingDown(42L, 999L));
        assertFalse(backoff.isCoolingDown(42L, 1_000L));
    }

    @Test
    void timeoutUsesFixedDelayAndClearsPreviousExponentialAttemptState() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);
        backoff.markTimeout(42L);
        backoff.markBackoff(42L, false, false);

        assertTrue(backoff.isCoolingDown(42L, 999L));
        assertFalse(backoff.isCoolingDown(42L, 1_000L));
    }

    @Test
    void clearingColumnResetsAttempts() {
        ManualClock clock = new ManualClock(0L);
        RetryBackoff backoff = backoff(clock);

        backoff.markBackoff(42L, false, false);
        backoff.markBackoff(42L, false, false);
        backoff.clear(42L);
        backoff.markBackoff(42L, false, false);

        assertTrue(backoff.isCoolingDown(42L, 999L));
        assertFalse(backoff.isCoolingDown(42L, 1_000L));
    }

    private static RetryBackoff backoff(ManualClock clock) {
        return new RetryBackoff(
                clock::now,
                new RetryBackoffPolicy(
                        500L,
                        1_000L,
                        5_000L,
                        250L,
                        3,
                        4,
                        1));
    }

    private static final class ManualClock {
        private final long now;

        private ManualClock(long now) {
            this.now = now;
        }

        private long now() {
            return now;
        }
    }
}
