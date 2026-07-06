package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.ChebyshevRingOffsets;

final class RingScanGate {
    private final int ringLimit;

    private RingScanGate(int ringLimit) {
        this.ringLimit = ringLimit;
    }

    static RingScanGate fromCursor(long[] orderedOffsets, int cursor, int totalCandidates) {
        if (orderedOffsets.length == 0 || cursor >= totalCandidates) {
            return new RingScanGate(Integer.MAX_VALUE);
        }
        return new RingScanGate(ChebyshevRingOffsets.ring(orderedOffsets[cursor]));
    }

    boolean allows(int ring) {
        return ring <= ringLimit;
    }

    int ringLimit() {
        return ringLimit;
    }
}
