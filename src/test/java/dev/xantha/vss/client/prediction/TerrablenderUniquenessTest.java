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

    static TerrablenderUniqueness.Tree uncached(long seed, int size,
                                               List<TerrablenderUniqueness.Region> regions) throws Exception {
        var initial = Class.forName(TerrablenderUniqueness.class.getName() + "$Initial")
                .getDeclaredConstructor(long.class, long.class, List.class);
        var zoom = Class.forName(TerrablenderUniqueness.class.getName() + "$Zoom")
                .getDeclaredConstructor(TerrablenderUniqueness.Tree.class, long.class, long.class, boolean.class);
        initial.setAccessible(true);
        zoom.setAccessible(true);
        var tree = (TerrablenderUniqueness.Tree)initial.newInstance(seed, 1L, regions);
        tree = (TerrablenderUniqueness.Tree)zoom.newInstance(tree, seed, 2000L, true);
        for (int i = 0; i < 3; i++) tree = (TerrablenderUniqueness.Tree)zoom.newInstance(tree, seed, 2001L + i, false);
        for (int i = 0; i < size; i++) tree = (TerrablenderUniqueness.Tree)zoom.newInstance(tree, seed, 1001L + i, false);
        return tree;
    }

    @Test void cacheCollisionsAndNegativeCoordinatesPreserveTheUncachedZoomAlgorithm() throws Exception {
        for (long seed : new long[]{0, 42, -9325173, Long.MAX_VALUE}) {
            var reference = uncached(seed, 3, BOP_LIKE);
            var cached = TerrablenderUniqueness.build(seed, 3, BOP_LIKE);
            for (int i = 0; i < 16384; i++) {
                int x = i * 1877 % 8192 - 4096, z = i * 547 % 16384 - 8192;
                assertEquals(reference.get(x, z), cached.get(x, z));
                assertEquals(reference.get(x, z), cached.get(x, z));
            }
            for (int x : new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE})
                for (int z : new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE})
                    assertEquals(reference.get(x, z), cached.get(x, z));
        }
    }

    @Test void workerCachesDoNotShareMutableCoordinatesOrLeakBetweenSeeds() throws Exception {
        var first = TerrablenderUniqueness.build(735L, 3, BOP_LIKE);
        var second = TerrablenderUniqueness.build(937L, 3, BOP_LIKE);
        var firstReference = uncached(735L, 3, BOP_LIKE);
        var secondReference = uncached(937L, 3, BOP_LIKE);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                int offset = worker * 65537;
                tasks.add(() -> {
                    for (int i = 0; i < 4096; i++) {
                        int x = offset + i % 64, z = -offset + i / 64;
                        assertEquals(firstReference.get(x, z), first.get(x, z));
                        assertEquals(secondReference.get(x, z), second.get(x, z));
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(tasks)) future.get();
        } finally { executor.shutdownNow(); }
    }

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
