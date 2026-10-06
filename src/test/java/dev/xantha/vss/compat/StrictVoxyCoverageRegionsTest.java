package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StrictVoxyCoverageRegionsTest {
    @Test void cachedProofsSeparateFootprintsHeightsAndLocalVersions() {
        var cache = new StrictVoxyCoverageRegions(13); var calls = new AtomicInteger();
        StrictVoxyCoverageRegions.Query query = (x, y, z, ex, ey, ez) -> { calls.incrementAndGet(); return y == 0 && ey == 10; };
        assertTrue(cache.covers(-8, 0, 0, -1, 10, 7, 1, query));
        assertTrue(cache.covers(-8, 10, 0, -1, 0, 7, 1, query)); assertEquals(1, calls.get());
        assertFalse(cache.covers(-8, 0, 0, -1, 11, 7, 1, query));
        assertFalse(cache.covers(-8, 11, 0, -1, 0, 7, 1, query)); assertEquals(2, calls.get());
        assertFalse(cache.covers(-8, 0, 0, -1, 11, 7, 2, query)); assertEquals(3, calls.get());
        assertTrue(cache.covers(-8, 0, 0, -2, 10, 7, 2, query)); assertEquals(4, calls.get());
    }
    @Test void repeatedCapacityOverflowRetainsHotAnswersWithReusableStorage() {
        var cache = new StrictVoxyCoverageRegions(4096);
        StrictVoxyCoverageRegions.Query query = (x, y, z, ex, ey, ez) -> x % 3 == 0;
        for (int i = 0; i < 30_000; i++) {
            assertEquals(i % 3 == 0, cache.covers(i * 8, 0, 0, i * 8 + 7, 31, 7, 1, query));
            assertEquals(i % 3 == 0, cache.covers(i * 8, 0, 0, i * 8 + 7, 31, 7, 1, query));
        }
        assertEquals(30_000, cache.hits());
    }
}
