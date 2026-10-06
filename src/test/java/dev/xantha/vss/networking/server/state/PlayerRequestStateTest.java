package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerRequestStateTest {
    @Test
    void oldClientsGetNoUnknownQueueResponsesAndNewClientsGetPacedMetadata() {
        PlayerRequestState state = new PlayerRequestState();
        state.enqueue(new dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload(
                7, 0, 0, null, 1L, new byte[] {0}), false);
        assertEquals(0, state.queuedRequestIdsForAck(10_000_000_000L).length);
        state.setClientCapabilities(dev.xantha.vss.common.VSSConstants.CAPABILITY_QUEUED_ACKNOWLEDGEMENTS);
        assertEquals(7, state.queuedRequestIdsForAck(10_000_000_000L)[0]);
        assertEquals(0, state.queuedRequestIdsForAck(11_000_000_000L).length);
        assertEquals(7, state.queuedRequestIdsForAck(15_000_000_000L)[0]);
        assertEquals(1, state.queuedPayloadCount(), "status acknowledgements never resend or consume data");
        state.cancel(7);
        assertEquals(0, state.queuedRequestIdsForAck(20_000_000_000L).length);
        assertEquals(0L, state.queuedBytes());
    }


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
    void priorityUsesTheSamePersonalSendCredit() {
        PlayerRequestState state = new PlayerRequestState();
        long limit = 64L * 1024L;

        state.primeSendCredit(limit);
        assertTrue(state.canSend(limit));

        state.recordSend(true, 256 * 1024);

        assertFalse(state.canSend(limit));
        assertEquals(256L * 1024L, state.totalBytesSent());
        assertEquals(256L * 1024L, state.priorityBytesSent());
    }

    @Test
    void unlimitedPersonalBandwidthUsesConfiguredQueueDepth() {
        assertFalse(PlayerRequestState.shouldBackpressureNormalRequests(
                1,
                8L * 1024L * 1024L,
                1000,
                32L * 1024L * 1024L,
                Long.MAX_VALUE,
                Long.MAX_VALUE));
    }
}
