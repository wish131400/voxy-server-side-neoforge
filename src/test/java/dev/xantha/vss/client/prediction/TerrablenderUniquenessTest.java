package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Structural invariants of the TerraBlender uniqueness zoom-stack replay. */
class TerrablenderUniquenessTest {
    private static final List<TerrablenderUniqueness.Region> BOP_LIKE = List.of(
            new TerrablenderUniqueness.Region(0, 10),
            new TerrablenderUniqueness.Region(1, 6),
            new TerrablenderUniqueness.Region(2, 3),
            new TerrablenderUniqueness.Region(3, 1));

    @Test
    void regionIndicesStayWithinTheRegisteredRange() {
        TerrablenderUniqueness.Tree tree = TerrablenderUniqueness.build(1234L, 3, BOP_LIKE);
        for (int x = -512; x < 512; x += 7) {
            for (int z = -512; z < 512; z += 7) {
                int index = tree.get(x, z);
                assertTrue(index >= 0 && index < 4, "index " + index + " at " + x + "," + z);
            }
        }
    }

    @Test
    void replayIsDeterministicForTheSameSeed() {
        TerrablenderUniqueness.Tree first = TerrablenderUniqueness.build(42L, 3, BOP_LIKE);
        TerrablenderUniqueness.Tree second = TerrablenderUniqueness.build(42L, 3, BOP_LIKE);
        for (int x = -256; x < 256; x += 13) {
            for (int z = -256; z < 256; z += 13) {
                assertEquals(first.get(x, z), second.get(x, z), "x=" + x + " z=" + z);
            }
        }
    }

    @Test
    void seedsProduceDifferentRegionGrids() {
        TerrablenderUniqueness.Tree first = TerrablenderUniqueness.build(1L, 3, BOP_LIKE);
        TerrablenderUniqueness.Tree second = TerrablenderUniqueness.build(2L, 3, BOP_LIKE);
        boolean differs = false;
        for (int x = -512; x < 512 && !differs; x += 5) {
            for (int z = -512; z < 512; z += 5) {
                if (first.get(x, z) != second.get(x, z)) {
                    differs = true;
                    break;
                }
            }
        }
        assertTrue(differs, "distinct world seeds must shuffle the region grid");
    }

    @Test
    void aSingleWeightedRegionFillsTheWholeGrid() {
        TerrablenderUniqueness.Tree tree = TerrablenderUniqueness.build(99L, 3,
                List.of(new TerrablenderUniqueness.Region(3, 7)));
        for (int x = -256; x < 256; x += 11) {
            for (int z = -256; z < 256; z += 11) {
                assertEquals(3, tree.get(x, z));
            }
        }
    }

    @Test
    void zeroWeightFallsBackToTheBaseRegionIndex() {
        TerrablenderUniqueness.Tree tree = TerrablenderUniqueness.build(7L, 2,
                List.of(new TerrablenderUniqueness.Region(0, 0),
                        new TerrablenderUniqueness.Region(1, 0)));
        assertEquals(0, tree.get(123, -456));
    }
}
