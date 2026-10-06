package dev.xantha.vss.networking.server.generation;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkyAreaTest {
    @Test void negativeCoordinatesRoundDownToChunks() {
        ChunkyArea area = ChunkyArea.rectangle(-17, -1, -1, 15);
        assertEquals(new ChunkyArea(-2, -1, -1, 0), area);
        assertEquals(4L, area.columnCount());
        assertEquals(new ChunkyArea(-1, -1, -1, -1), ChunkyArea.square(-1, -1, 0));
    }

    @Test void squareIncludesBothEdgesAndUsesBlockRadius() {
        assertEquals(new ChunkyArea(-1, -1, 1, 1), ChunkyArea.square(0, 0, 16));
        assertEquals(4225L, ChunkyArea.square(0, 0, 512).columnCount());
        assertEquals(1024L, ChunkyArea.rectangle(-256, -256, 255, 255).columnCount());
    }

    @Test void cornersCanBeGivenInEitherOrder() {
        assertEquals(ChunkyArea.rectangle(-32, 48, 31, 63), ChunkyArea.rectangle(31, 63, -32, 48));
    }

    @Test void iteratorVisitsEveryColumnExactlyOnceWithoutBuildingAList() {
        ChunkyArea area = new ChunkyArea(-3, -2, 4, 5);
        HashSet<String> coordinates = new HashSet<>();
        for (long i = 0; i < area.columnCount(); i++)
            assertTrue(coordinates.add(area.chunkX(i) + "," + area.chunkZ(i)));
        assertEquals(area.columnCount(), coordinates.size());
        assertThrows(IndexOutOfBoundsException.class, () -> area.chunkX(area.columnCount()));
        assertThrows(IndexOutOfBoundsException.class, () -> area.chunkZ(-1));
    }

    @Test void overflowWorldEdgesAndOversizedAreasAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChunkyArea.square(Integer.MAX_VALUE, 0, 8192));
        assertThrows(IllegalArgumentException.class, () -> ChunkyArea.square(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ChunkyArea.square(0, 0, 8193));
        assertThrows(IllegalArgumentException.class, () -> ChunkyArea.square(ChunkyArea.MAX_BLOCK_COORDINATE, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> ChunkyArea.rectangle(-500000, -500000, 500000, 500000));
        assertEquals(ChunkyArea.MAX_COLUMNS, new ChunkyArea(0, 0, 2047, 2047).columnCount());
        assertThrows(IllegalArgumentException.class, () -> new ChunkyArea(0, 0, 2048, 2047));
    }
}
