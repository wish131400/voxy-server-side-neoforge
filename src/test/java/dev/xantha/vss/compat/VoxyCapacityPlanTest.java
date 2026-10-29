package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VoxyCapacityPlanTest {
    @Test void ordinary8192HorizonKeepsOriginalAllocationEvenInTallWorld() {
        var plan = VoxyCapacityPlan.create(16, 128, 8192, -64, 2031);
        assertEquals(17, plan.radius()); assertEquals(5, plan.layers()); assertEquals(4505, plan.roots());
        assertEquals(200_000, plan.initialCapacity());
    }
    @Test void tall65536HorizonFitsFromItsFirstAllocation() {
        var plan = VoxyCapacityPlan.create(64, 128, 65536, -64, 2031);
        assertEquals(129, plan.radius()); assertEquals(261285, plan.roots());
        assertTrue(plan.initialCapacity() >= plan.roots());
        assertTrue(plan.initialCapacity() < 400_000);
    }
    @Test void sourceOfLargestDistanceAndVerticalAlignmentAreRetained() {
        assertEquals(128f, VoxyCapacityPlan.create(16, 4096, 8192, -64, 319).sections());
        assertEquals(160f, VoxyCapacityPlan.create(160, 128, 8192, -64, 319).sections());
        assertEquals(2, VoxyCapacityPlan.create(16, 0, 0, -64, 319).layers());
        assertEquals(1, VoxyCapacityPlan.create(16, 0, 0, 0, 511).layers());
        assertEquals(2, VoxyCapacityPlan.create(16, 0, 0, -512, 0).layers());
    }
    @Test void circlesMatchIndependentEnumerationAtAlignmentBoundaries() {
        for (int distance : new int[]{0,1,2,15,16,17,31,64,112,128}) {
            var plan=VoxyCapacityPlan.create(distance,0,0,0,511);
            int radius=distance+1; long expected=0;
            for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)
                if((long)x*x+(long)z*z<=(long)radius*radius)expected++;
            assertEquals(expected,plan.roots());
        }
    }
    @Test void capacityGrowsForTransientPeaksAndDoesNotShrink() {
        int capacity=VoxyCapacityPlan.capacity(200000,200001);
        assertTrue(capacity>=300000); assertEquals(capacity,VoxyCapacityPlan.capacity(capacity,4505));
        assertThrows(IllegalStateException.class,()->VoxyCapacityPlan.capacity(200000,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->VoxyCapacityPlan.create(Float.NaN,0,0,0,511));
    }
}
