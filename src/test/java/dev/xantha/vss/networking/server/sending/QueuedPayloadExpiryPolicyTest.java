package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueuedPayloadExpiryPolicyTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void smallHighBandwidthQueueStillUsesTwentySecondBaseAge() {
        assertTrue(QueuedPayloadExpiryPolicy.isExpired(
                0L,
                21L * SECOND,
                16 * 1024,
                0L,
                4L * 1024L * 1024L));
    }

    @Test
    void lowBandwidthDeepQueueDoesNotExpireAtFixedTwentySecondCliff() {
        assertFalse(QueuedPayloadExpiryPolicy.isExpired(
                0L,
                21L * SECOND,
                16 * 1024,
                4L * 1024L * 1024L,
                64L * 1024L));
    }

    @Test
    void lowBandwidthPayloadEventuallyExpiresAfterDynamicQueueBudget() {
        long allowedAge = QueuedPayloadExpiryPolicy.allowedAgeNanos(
                16 * 1024,
                4L * 1024L * 1024L,
                64L * 1024L);

        assertTrue(QueuedPayloadExpiryPolicy.isExpired(
                0L,
                allowedAge + 1L,
                16 * 1024,
                4L * 1024L * 1024L,
                64L * 1024L));
    }
}
