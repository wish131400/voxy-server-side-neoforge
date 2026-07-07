package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodRequestManagerTimeoutTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void lowBandwidthTimeoutScalesWithPendingRequestDepth() {
        long fiveHundredKbps = 500_000L / 8L;
        long timeout = LodRequestManager.queueTimeoutNanos(96, fiveHundredKbps);

        assertTrue(timeout > 200L * SECOND);
    }
}
