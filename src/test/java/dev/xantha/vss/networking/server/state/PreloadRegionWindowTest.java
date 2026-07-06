package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PreloadRegionWindowTest {

    @Test
    void resetQueuesRegionsInChebyshevRingOrder() {
        PreloadRegionWindow window = new PreloadRegionWindow(new AtomicLong()::get);
        window.reset("overworld", 0, 0, 1);

        assertRegion(window.poll(), 0, 0);
        assertRegion(window.poll(), -1, -1);
        assertRegion(window.poll(), 0, -1);
        assertRegion(window.poll(), 1, -1);
        assertRegion(window.poll(), 1, 0);
        assertRegion(window.poll(), 1, 1);
        assertRegion(window.poll(), 0, 1);
        assertRegion(window.poll(), -1, 1);
        assertRegion(window.poll(), -1, 0);
        assertNull(window.poll());
    }

    @Test
    void slidingWindowQueuesOnlyEnteredRegions() {
        PreloadRegionWindow window = new PreloadRegionWindow(new AtomicLong()::get);
        window.reset("overworld", 0, 0, 1);
        window.poll();

        window.update("overworld", 1, 1, 1);

        assertEquals(8, window.count());
    }

    @Test
    void dimensionChangeResetsWindow() {
        PreloadRegionWindow window = new PreloadRegionWindow(new AtomicLong()::get);
        window.reset("overworld", 0, 0, 1);
        window.poll();

        window.update("nether", 10, 10, 0);

        assertEquals(1, window.count());
        assertRegion(window.poll(), 10, 10);
        assertNull(window.poll());
    }

    private static void assertRegion(PlayerRequestState.PreloadRegion region, int expectedX, int expectedZ) {
        assertEquals(expectedX, region.regionX());
        assertEquals(expectedZ, region.regionZ());
    }
}
