package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientKnownColumnIndexTest {
    private static final String TEST_DIMENSION = "vss_test:overworld";

    @Test
    void presenceEntriesMarkKnownColumns() {
        ClientKnownColumnIndex index = new ClientKnownColumnIndex();
        RegionPresenceC2SPayload.RegionEntry entry = entry(1, -2,
                new int[] {0, 33},
                new long[] {100L, 200L},
                2);

        index.updateEntries(TEST_DIMENSION, false, List.of(entry));

        assertTrue(index.isCurrent(TEST_DIMENSION, 32, -64, 100L));
        assertTrue(index.isCurrent(TEST_DIMENSION, 33, -63, 200L));
        assertFalse(index.isCurrent(TEST_DIMENSION, 33, -63, 201L));
        assertEquals(200L, index.knownTimestamp(TEST_DIMENSION, 33, -63));
        assertEquals(0L, index.knownTimestamp(TEST_DIMENSION, 40, -63));
    }

    @Test
    void resetClearsExistingDimensionState() {
        ClientKnownColumnIndex index = new ClientKnownColumnIndex();
        index.markKnown(TEST_DIMENSION, PositionUtil.packPosition(4, 5), 100L);

        index.updateEntries(TEST_DIMENSION, true, List.of());

        assertFalse(index.isCurrent(TEST_DIMENSION, 4, 5, 1L));
    }

    @Test
    void invalidPresenceSlotsAndTimestampsAreIgnored() {
        ClientKnownColumnIndex index = new ClientKnownColumnIndex();
        RegionPresenceC2SPayload.RegionEntry entry = entry(0, 0,
                new int[] {-1, RegionPresenceC2SPayload.REGION_SLOT_COUNT, 2},
                new long[] {100L, 200L, 0L},
                3);

        index.updateEntries(TEST_DIMENSION, false, List.of(entry));

        assertFalse(index.isCurrent(TEST_DIMENSION, 0, 0, 1L));
        assertFalse(index.isCurrent(TEST_DIMENSION, 2, 0, 1L));
    }

    @Test
    void explicitMarksKeepNewestTimestamp() {
        ClientKnownColumnIndex index = new ClientKnownColumnIndex();
        long packed = PositionUtil.packPosition(12, 14);

        index.markKnown(TEST_DIMENSION, packed, 200L);
        index.markKnown(TEST_DIMENSION, packed, 100L);

        assertTrue(index.isCurrent(TEST_DIMENSION, 12, 14, 200L));
        assertFalse(index.isCurrent(TEST_DIMENSION, 12, 14, 201L));
    }

    private static RegionPresenceC2SPayload.RegionEntry entry(
            int regionX,
            int regionZ,
            int[] slots,
            long[] timestamps,
            int count) {
        return new RegionPresenceC2SPayload.RegionEntry(
                regionX,
                regionZ,
                new long[RegionPresenceC2SPayload.REGION_BITMAP_LONGS],
                slots,
                timestamps,
                count);
    }
}
