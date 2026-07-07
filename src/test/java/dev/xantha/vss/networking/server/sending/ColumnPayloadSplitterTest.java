package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnPayloadSplitterTest {

    @Test
    void targetWireBytesShrinksBelowOldTwentyFourKilobyteFloorAtLowBandwidth() {
        int target = ColumnPayloadSplitter.targetWireBytes(64L * 1024L);

        assertTrue(target < 24 * 1024);
        assertEquals(16 * 1024, target);
    }

    @Test
    void targetWireBytesKeepsSmallFloorForVeryLowBandwidth() {
        assertEquals(8 * 1024, ColumnPayloadSplitter.targetWireBytes(4L * 1024L));
    }

    @Test
    void targetWireBytesStillCapsLargeBandwidthPayloads() {
        assertEquals(256 * 1024, ColumnPayloadSplitter.targetWireBytes(4L * 1024L * 1024L));
    }
}
