package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StrictVoxyPendingColumnsTest {
    @Test void indexedPendingRegionsMatchColumnReferenceIncludingDuplicateCounts() {
        var index = new StrictVoxyPendingColumns(); var random = new Random(24098);
        var columns = new HashMap<Long, Integer>();
        for (int change = 0; change < 3000; change++) {
            int x = random.nextInt(100) - 50, z = random.nextInt(100) - 50;
            long key = pack(x, z); int count = columns.getOrDefault(key, 0);
            int delta = count > 0 && random.nextBoolean() ? -1 : 1;
            index.changed(x, z, delta);
            if (count + delta == 0) columns.remove(key); else columns.put(key, count + delta);
            int minX = random.nextInt(120) - 60, minZ = random.nextInt(120) - 60;
            int maxX = minX + random.nextInt(40), maxZ = minZ + random.nextInt(40);
            boolean expected = true;
            for (long column : columns.keySet()) {
                int cx = (int) (column >> 32), cz = (int) column;
                if (cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ) { expected = false; break; }
            }
            assertEquals(expected, index.idleBox(minX, minZ, maxX, maxZ));
        }
        index.clear(); assertTrue(index.idleBox(-100, -100, 100, 100));
    }
    @Test void lastColumnAtIntegerBoundaryDoesNotWrapTraversal() {
        var index = new StrictVoxyPendingColumns(); index.changed(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1);
        assertTrue(index.idleBox(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(index.idleBox(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
    private static long pack(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
}
