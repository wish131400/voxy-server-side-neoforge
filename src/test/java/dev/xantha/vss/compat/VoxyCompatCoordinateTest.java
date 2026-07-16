package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VoxyCompatCoordinateTest {
    @Test
    void worldSectionCoversTwoChunksOnEachAxis() {
        assertEquals(0, VoxyCompat.chunkCoordinateForWorldSection(0, 0));
        assertEquals(1, VoxyCompat.chunkCoordinateForWorldSection(0, 1));
        assertEquals(2, VoxyCompat.chunkCoordinateForWorldSection(1, 0));
        assertEquals(3, VoxyCompat.chunkCoordinateForWorldSection(1, 1));
    }

    @Test
    void negativeWorldSectionsMapWithoutCrossingZero() {
        assertEquals(-2, VoxyCompat.chunkCoordinateForWorldSection(-1, 0));
        assertEquals(-1, VoxyCompat.chunkCoordinateForWorldSection(-1, 1));
        assertEquals(-4, VoxyCompat.chunkCoordinateForWorldSection(-2, 0));
        assertEquals(-3, VoxyCompat.chunkCoordinateForWorldSection(-2, 1));
    }

    @Test
    void rejectsInvalidLocalChunkCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> VoxyCompat.chunkCoordinateForWorldSection(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> VoxyCompat.chunkCoordinateForWorldSection(0, 2));
    }

    @Test
    void lightweightIndexPrefersConfirmedColumnsAndDefersUntilReady() {
        assertEquals(ModCompat.LocalColumnState.PRESENT, VoxyCompat.resolveLocalIndexState(true, false, false));
        assertEquals(ModCompat.LocalColumnState.UNKNOWN, VoxyCompat.resolveLocalIndexState(false, false, true));
        assertEquals(ModCompat.LocalColumnState.PRESENT, VoxyCompat.resolveLocalIndexState(false, true, true));
        assertEquals(ModCompat.LocalColumnState.MISSING, VoxyCompat.resolveLocalIndexState(false, true, false));
    }
}
