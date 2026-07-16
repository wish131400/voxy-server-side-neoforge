package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.PositionUtil;
import org.junit.jupiter.api.Test;

class LodRequestManagerPresenceReconciliationTest {
    @Test
    void confirmedMissingColumnClearsRuntimeTimestampAndQueuesRetry() {
        LodRequestManager manager = new LodRequestManager("test");
        long packed = PositionUtil.packPosition(4, -7);
        manager.restoreKnownColumn(packed, 123L);

        assertTrue(manager.reconcileMissingColumn(packed));

        assertEquals(-1L, manager.requestTimestampFor(packed));
    }

    @Test
    void confirmedMissingColumnDoesNotReplaceMatchingInFlightRequest() {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {
        });
        LodRequestManager manager = new LodRequestManager("test", tracker);
        long packed = PositionUtil.packPosition(9, 3);
        manager.restoreKnownColumn(packed, 456L);
        tracker.track(packed, false, false, 1_000_000_000L, 0L);

        assertFalse(manager.reconcileMissingColumn(packed));

        assertTrue(tracker.contains(packed));
        assertEquals(-1L, manager.requestTimestampFor(packed));
    }
}
