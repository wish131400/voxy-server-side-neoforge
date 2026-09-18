package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StrictLodFrontierTest {
    @Test void distantCacheCannotBypassMissingCenterEvenAfterRepeatedRetries() {
        StrictLodFrontier f = new StrictLodFrontier();
        f.center(0, 0, 128);
        for (int retry = 0; retry < 10000; retry++) {
            assertFalse(f.advance((x,z) -> x != 0 || z != 0, (x,z) -> true));
            assertEquals(-1, f.visibleRing());
            assertEquals(0, f.requestRing());
            assertFalse(f.visible(128, 0));
        }
    }
    @Test void wholeRingWaitsForBothDataAndGpuReadiness() {
        StrictLodFrontier f = new StrictLodFrontier();
        f.center(0, 0, 128);
        assertFalse(f.advance((x,z) -> true, (x,z) -> false));
        assertTrue(f.advance((x,z) -> true, (x,z) -> true));
        assertEquals(0, f.visibleRing());
        assertFalse(f.advance((x,z) -> x != -1 || z != -1, (x,z) -> true));
        assertFalse(f.visible(1, 0));
        assertFalse(f.advance((x,z) -> true, (x,z) -> x != -1 || z != -1));
        assertFalse(f.visible(1, 1));
        assertTrue(f.advance((x,z) -> true, (x,z) -> true));
        assertTrue(f.visible(-1, -1));
        assertTrue(f.requestable(2, 0));
        assertFalse(f.requestable(3, 0));
    }
    @Test void movementOnlyRetainsAnAlreadyCompleteInteriorAndTeleportClosesIt() {
        StrictLodFrontier f = new StrictLodFrontier();
        f.center(-4, -7, 128);
        for (int i = 0; i < 10; i++) assertTrue(f.advance((x,z) -> true, (x,z) -> true));
        f.center(-3, -7, 128);
        assertEquals(8, f.visibleRing());
        assertFalse(f.visible(6, -7));
        f.center(300, 300, 128);
        assertEquals(-1, f.visibleRing());
        assertFalse(f.visible(-4, -7));
    }
    @Test void lossOfAnInnerColumnImmediatelyRetractsEveryOuterRing() {
        StrictLodFrontier f = new StrictLodFrontier();
        f.center(0, 0, 128);
        for (int i = 0; i <= 128; i++) assertTrue(f.advance((x,z) -> true, (x,z) -> true));
        assertTrue(f.visible(128, 0));
        assertFalse(f.advance((x,z) -> true, (x,z) -> true));
        f.missing(2, -1);
        assertEquals(1, f.visibleRing());
        assertFalse(f.visible(128, 0));
        f.reset();
        f.center(0, 0, 128);
        assertEquals(-1, f.visibleRing(), "new world cannot inherit completion");
    }
}
