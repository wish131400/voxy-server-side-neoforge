package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StrictLodFrontierTest {
    @Test void completedCoverageBoundsCannotIncludeAMissingCenter() {
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
        assertEquals(2, f.requestRing());
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
    @Test void anUnchangedBlockerCostsOneProbeButRecoveryRevalidatesEarlierSuccesses() {
        var f=new StrictLodFrontier();f.center(-13,-7,30);
        for(int n=0;n<20;n++)assertTrue(f.advance((x,z)->true,(x,z)->true));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        int end=ChebyshevRingOffsets.firstIndexForRing(21)-1;
        long offset=ChebyshevRingOffsets.offsetAt(end);
        int bx=-13+ChebyshevRingOffsets.decodeX(offset),bz=-7+ChebyshevRingOffsets.decodeZ(offset);
        java.util.function.BiPredicate<Integer,Integer> blocked=(x,z)->{count.incrementAndGet();return x!=bx||z!=bz;};
        assertFalse(f.advance(blocked,(x,z)->true));assertEquals(160,count.get());
        for(int retry=0;retry<100;retry++){count.set(0);assertFalse(f.advance(blocked,(x,z)->true));assertEquals(1,count.get());}
        long first=ChebyshevRingOffsets.offsetAt(ChebyshevRingOffsets.firstIndexForRing(20));
        int fx=-13+ChebyshevRingOffsets.decodeX(first),fz=-7+ChebyshevRingOffsets.decodeZ(first);
        assertFalse(f.advance((x,z)->x!=fx||z!=fz,(x,z)->true),"Recovery must not reuse a previously ready prefix");
        assertEquals(19,f.visibleRing());
        assertTrue(f.advance((x,z)->true,(x,z)->true));
    }
    @Test void renderBlockerRecoveryRechecksDataAndMovementAndResetDiscardTheOldBlocker() {
        var f=new StrictLodFrontier();f.center(0,0,5);assertTrue(f.advance((x,z)->true,(x,z)->true));
        assertFalse(f.advance((x,z)->true,(x,z)->x!=1||z!=1));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(f.advance((x,z)->{fail("Do not rescan data while the render blocker is still absent");return true;},(x,z)->{count.incrementAndGet();return false;}));
        assertEquals(1,count.get());
        assertFalse(f.advance((x,z)->false,(x,z)->true));
        f.center(-100,-200,5);assertTrue(f.advance((x,z)->x==-100&&z==-200,(x,z)->true));
        assertFalse(f.advance((x,z)->false,(x,z)->true));f.reset();f.center(1,2,5);
        assertTrue(f.advance((x,z)->x==1&&z==2,(x,z)->true));
        assertFalse(f.advance((x,z)->false,(x,z)->true));f.missing(1,2);
        assertTrue(f.advance((x,z)->x==1&&z==2,(x,z)->true));
    }
}
