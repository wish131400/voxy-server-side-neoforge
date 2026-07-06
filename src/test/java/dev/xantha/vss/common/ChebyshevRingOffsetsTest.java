package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChebyshevRingOffsetsTest {

    @Test
    void distanceZeroContainsOnlyOrigin() {
        long[] offsets = ChebyshevRingOffsets.generate(0);
        assertEquals(1, offsets.length);
        assertOffset(offsets[0], 0, 0, 0);
    }

    @Test
    void distanceOneUsesStableRingOrder() {
        long[] offsets = ChebyshevRingOffsets.generate(1);
        int[][] decoded = decode(offsets);
        assertArrayEquals(new int[][] {
                {0, 0},
                {-1, -1},
                {0, -1},
                {1, -1},
                {1, 0},
                {1, 1},
                {0, 1},
                {-1, 1},
                {-1, 0}
        }, decoded);
    }

    @Test
    void generatedOffsetsAreUniqueAndComplete() {
        int distance = 8;
        long[] offsets = ChebyshevRingOffsets.generate(distance);
        assertEquals((distance * 2 + 1) * (distance * 2 + 1), offsets.length);

        Set<Long> seen = new HashSet<>();
        for (long offset : offsets) {
            assertTrue(seen.add(offset), "duplicate offset " + offset);
        }
    }

    @Test
    void ringsNeverMoveInward() {
        long[] offsets = ChebyshevRingOffsets.generate(6);
        int previousRing = -1;
        for (long offset : offsets) {
            int ring = ChebyshevRingOffsets.ring(offset);
            assertTrue(ring >= previousRing);
            previousRing = ring;
        }
    }

    @Test
    void packedOffsetsPreserveSignedCoordinates() {
        long offset = ChebyshevRingOffsets.encode(-128, 127);
        assertOffset(offset, -128, 127, 128);
    }

    @Test
    void negativeDistanceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChebyshevRingOffsets.generate(-1));
    }

    private static int[][] decode(long[] offsets) {
        int[][] decoded = new int[offsets.length][2];
        for (int i = 0; i < offsets.length; i++) {
            decoded[i][0] = ChebyshevRingOffsets.decodeX(offsets[i]);
            decoded[i][1] = ChebyshevRingOffsets.decodeZ(offsets[i]);
        }
        return decoded;
    }

    private static void assertOffset(long offset, int expectedX, int expectedZ, int expectedRing) {
        assertEquals(expectedX, ChebyshevRingOffsets.decodeX(offset));
        assertEquals(expectedZ, ChebyshevRingOffsets.decodeZ(offset));
        assertEquals(expectedRing, ChebyshevRingOffsets.ring(offset));
    }
}
