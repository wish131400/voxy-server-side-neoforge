package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void chunkAndSectionCoordinatesSelectTheCorrectSixteenCubedSubregion() {
        assertEquals(-1, VoxyCompat.worldSectionCoordinate(-1));
        assertEquals(-1, VoxyCompat.worldSectionCoordinate(-2));
        assertEquals(16, VoxyCompat.localSectionBase(-1));
        assertEquals(0, VoxyCompat.localSectionBase(-2));

        long[] data = new long[32 * 32 * 32];
        data[index(16, 16, 16)] = 1L << 27;

        assertTrue(VoxyCompat.hasExpectedChunkSectionData(data, -1, -1, -1));
        assertFalse(VoxyCompat.hasExpectedChunkSectionData(data, -2, -1, -1));
        assertFalse(VoxyCompat.hasExpectedChunkSectionData(data, -1, -2, -1));
        assertFalse(VoxyCompat.hasExpectedChunkSectionData(data, -1, -1, -2));
    }

    @Test
    void skyLightOnlyDoesNotMakeAnExpectedSectionPresent() {
        long[] data = new long[32 * 32 * 32];
        data[index(0, 0, 0)] = 0x0F00_0000_0000_0000L;
        assertFalse(VoxyCompat.hasExpectedChunkSectionData(data, 0, 0, 0));

        data[index(0, 0, 0)] = 0xF000_0000_0000_0000L;
        assertTrue(VoxyCompat.hasExpectedChunkSectionData(data, 0, 0, 0));
    }

    private static int index(int x, int y, int z) {
        return (y << 10) | (z << 5) | x;
    }
}
