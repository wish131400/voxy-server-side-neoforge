package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The detail-texture path maps a cell of the cellAxis grid onto the tile's
 * margin-cropped sample grid, which is one sample wider per axis.  Linear
 * indexing would read the wrong column for every row past the first and
 * smear textures diagonally across the tile.
 */
class PredictionSpriteMappingTest {
    @Test
    void mapsCellsOntoTheWiderSampleGrid() {
        // A 64-quad tile carries 65x65 margin-cropped samples: cell (x, z)
        // reads sample (x, z), not cell*1 in a flat array.
        int cellAxis = 64;
        assertEquals(0, PredictionGpuTile.sampleIndexForCell(0, cellAxis));
        assertEquals(1, PredictionGpuTile.sampleIndexForCell(1, cellAxis));
        // Second row: 65-wide stride, not 64.
        assertEquals(65, PredictionGpuTile.sampleIndexForCell(cellAxis, cellAxis));
        // Row 3, column 17: 3*65 + 17.
        assertEquals(3 * 65 + 17, PredictionGpuTile.sampleIndexForCell(
                17 + 3 * cellAxis, cellAxis));
        // Last cell of the last row: (63, 63) -> 63*65 + 63.
        assertEquals(63 * 65 + 63, PredictionGpuTile.sampleIndexForCell(
                cellAxis * cellAxis - 1, cellAxis));
    }

    @Test
    void legacySeventeenGridAlsoStridesCorrectly() {
        int cellAxis = 16;
        assertEquals(17, PredictionGpuTile.sampleIndexForCell(cellAxis, cellAxis));
        assertEquals(17 * 8 + 8, PredictionGpuTile.sampleIndexForCell(
                8 + 8 * cellAxis, cellAxis));
    }
}
