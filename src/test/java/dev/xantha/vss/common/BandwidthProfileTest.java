package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BandwidthProfileTest {

    @Test
    void targetWireBytesNeverExceedsSendBurstCap() {
        long[] bandwidths = {
                1L,
                4L * 1024L,
                62_500L,
                64L * 1024L,
                96L * 1024L,
                125_000L,
                512L * 1024L,
                4L * 1024L * 1024L,
                128L * 1024L * 1024L
        };

        for (long bandwidth : bandwidths) {
            assertTrue(
                    BandwidthProfile.targetWireBytes(bandwidth)
                            <= BandwidthProfile.sendBurstCapBytes(bandwidth),
                    "split target must fit send burst cap at " + bandwidth + " B/s");
        }
    }

    @Test
    void fiveHundredKbpsAndOneMbpsDoNotCrossABurstTargetCliff() {
        long fiveHundredKbps = 62_500L;
        long oneMbps = 125_000L;

        assertEquals(15_625, BandwidthProfile.targetWireBytes(fiveHundredKbps));
        assertEquals(31_250, BandwidthProfile.targetWireBytes(oneMbps));
        assertEquals(64L * 1024L, BandwidthProfile.sendBurstCapBytes(fiveHundredKbps));
        assertEquals(64L * 1024L, BandwidthProfile.sendBurstCapBytes(oneMbps));
    }

    @Test
    void targetAndBurstShareQuarterBandwidthAboveLowBandwidthFloor() {
        long bandwidth = 512L * 1024L;

        assertEquals(128 * 1024, BandwidthProfile.targetWireBytes(bandwidth));
        assertEquals(128L * 1024L, BandwidthProfile.sendBurstCapBytes(bandwidth));
    }
}
