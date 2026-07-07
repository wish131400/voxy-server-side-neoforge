package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerRequestStateTest {

    @Test
    void lowEffectiveBandwidthBackpressuresBeforeByteQueueLimit() {
        int queueLimit = 1000;
        long queueBytesLimit = 32L * 1024L * 1024L;
        long bandwidth = 64L * 1024L;

        assertFalse(PlayerRequestState.shouldBackpressureNormalRequests(
                2,
                512L * 1024L,
                queueLimit,
                queueBytesLimit,
                bandwidth,
                Long.MAX_VALUE));
        assertTrue(PlayerRequestState.shouldBackpressureNormalRequests(
                2,
                700L * 1024L,
                queueLimit,
                queueBytesLimit,
                bandwidth,
                Long.MAX_VALUE));
    }

    @Test
    void clientDesiredBandwidthAlsoLimitsBackpressureDepth() {
        assertTrue(PlayerRequestState.shouldBackpressureNormalRequests(
                1,
                700L * 1024L,
                1000,
                32L * 1024L * 1024L,
                4L * 1024L * 1024L,
                64L * 1024L));
    }

    @Test
    void priorityAndNormalSendCreditsAreIndependent() {
        PlayerRequestState state = new PlayerRequestState();
        long limit = 64L * 1024L;

        state.primeSendCredit(limit);
        assertTrue(state.canSend(false, limit));
        assertTrue(state.canSend(true, limit));

        state.recordSend(false, 256 * 1024);

        assertFalse(state.canSend(false, limit));
        assertTrue(state.canSend(true, limit));

        state.recordSend(true, 256 * 1024);

        assertFalse(state.canSend(true, limit));
    }
}
