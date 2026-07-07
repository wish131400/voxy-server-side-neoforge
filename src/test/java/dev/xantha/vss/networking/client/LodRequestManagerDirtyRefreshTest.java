package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.xantha.vss.common.PositionUtil;
import org.junit.jupiter.api.Test;

class LodRequestManagerDirtyRefreshTest {

    @Test
    void dirtyRefreshWithoutLocalTimestampUsesSyntheticStaleTimestamp() {
        LodRequestManager manager = new LodRequestManager("test");
        long packed = PositionUtil.packPosition(12, -4);
        long dirtyTimestamp = 10_000L;

        manager.onDirtyColumns(new long[] {packed}, new long[] {dirtyTimestamp});

        assertEquals(dirtyTimestamp - 1L, manager.requestTimestampFor(packed));
    }

    @Test
    void dirtyRefreshCancelsNormalInFlightRequest() {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {
        });
        LodRequestManager manager = new LodRequestManager("test", tracker);
        long packed = PositionUtil.packPosition(2, 3);

        tracker.track(packed, false, false, 1_000_000_000L, 0L);

        manager.onDirtyColumns(new long[] {packed}, new long[] {10_000L});

        assertFalse(tracker.contains(packed));
        assertEquals(9_999L, manager.requestTimestampFor(packed));
    }
}
