package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PreloadColumnFrontierTest {

    @Test
    void pollsNearestColumnFirstWithStableTieBreakers() {
        PreloadColumnFrontier frontier = new PreloadColumnFrontier();
        frontier.addColumn(new PlayerRequestState.PreloadColumn(4, 0, 10L));
        frontier.addColumn(new PlayerRequestState.PreloadColumn(2, 2, 20L));
        frontier.addColumn(new PlayerRequestState.PreloadColumn(-2, 2, 30L));
        frontier.addColumn(new PlayerRequestState.PreloadColumn(2, -2, 40L));

        assertColumn(frontier.pollFrontierColumn(0, 0, 0), -2, 2);
        assertColumn(frontier.pollFrontierColumn(0, 0, 0), 2, -2);
        assertColumn(frontier.pollFrontierColumn(0, 0, 0), 2, 2);
        assertColumn(frontier.pollFrontierColumn(0, 0, 0), 4, 0);
    }

    @Test
    void duplicateColumnsKeepNewestTimestamp() {
        PreloadColumnFrontier frontier = new PreloadColumnFrontier();
        frontier.addColumn(new PlayerRequestState.PreloadColumn(1, 1, 10L));
        frontier.addColumn(new PlayerRequestState.PreloadColumn(1, 1, 20L));

        assertEquals(1, frontier.count());
        PlayerRequestState.PreloadColumn column = frontier.pollFrontierColumn(0, 0, 0);
        assertColumn(column, 1, 1);
        assertEquals(20L, column.timestamp());
        assertNull(frontier.pollFrontierColumn(0, 0, 0));
    }

    @Test
    void reordersBucketsWhenPlayerCenterMoves() {
        PreloadColumnFrontier frontier = new PreloadColumnFrontier();
        frontier.addColumn(new PlayerRequestState.PreloadColumn(1, 0, 10L));
        frontier.addColumn(new PlayerRequestState.PreloadColumn(20, 0, 20L));

        assertColumn(frontier.pollFrontierColumn(19, 0, 0), 20, 0);
        assertColumn(frontier.pollFrontierColumn(0, 0, 0), 1, 0);
    }

    @Test
    void closerInFlightRegionScanGatesFarColumn() {
        PreloadColumnFrontier frontier = new PreloadColumnFrontier();
        frontier.addColumn(new PlayerRequestState.PreloadColumn(12, 0, 10L));
        frontier.beginRegionScan(4);

        assertNull(frontier.pollFrontierColumn(0, 0, 1));

        frontier.finishRegionScan(4);
        assertColumn(frontier.pollFrontierColumn(0, 0, 1), 12, 0);
    }

    private static void assertColumn(PlayerRequestState.PreloadColumn column, int expectedX, int expectedZ) {
        assertEquals(expectedX, column.chunkX());
        assertEquals(expectedZ, column.chunkZ());
    }
}
