package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.ChebyshevRingOffsets;
import org.junit.jupiter.api.Test;

class RingScanGateTest {

    @Test
    void gateAllowsOnlyTheRingWhereTheScanPassStarted() {
        long[] offsets = ChebyshevRingOffsets.generate(3);
        int ringTwoCursor = firstIndexOfRing(offsets, 2);

        RingScanGate gate = RingScanGate.fromCursor(ringTwoCursor, offsets.length);

        assertTrue(gate.allows(2));
        assertFalse(gate.allows(3));
    }

    @Test
    void gateDoesNotDependOnWhetherTheRingProducedRequests() {
        long[] offsets = ChebyshevRingOffsets.generate(2);
        int ringOneCursor = firstIndexOfRing(offsets, 1);

        RingScanGate gate = RingScanGate.fromCursor(ringOneCursor, offsets.length);

        assertTrue(gate.allows(1));
        assertFalse(gate.allows(2));
    }

    private static int firstIndexOfRing(long[] offsets, int ring) {
        for (int i = 0; i < offsets.length; i++) {
            if (ChebyshevRingOffsets.ring(offsets[i]) == ring) {
                return i;
            }
        }
        throw new AssertionError("ring not found: " + ring);
    }
}
