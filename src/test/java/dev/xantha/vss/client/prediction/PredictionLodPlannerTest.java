package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionLodPlannerTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("overworld"));

    @Test
    void plannerProducesFineTilesNearFocusAndCoarseTilesFarAway() {
        List<PredictionTileManager.PredictionTileKey> keys =
                PredictionLodPlanner.plan(DIMENSION, 0, 0);
        assertTrue(keys.size() <= 512);
        long fine = keys.stream().filter(key -> key.lod() == 0).count();
        long coarse = keys.stream().filter(key -> key.lod() >= 4).count();
        assertTrue(fine >= 64, "near focus must retain a useful fine LOD ring");
        assertTrue(coarse >= 16, "outer horizon must retain coarse LOD coverage");
    }

    @Test
    void plannerLeavesDoNotOverlapWhenTheFocusMoves() {
        List<PredictionTileManager.PredictionTileKey> keys =
                PredictionLodPlanner.plan(DIMENSION, 1_003, -997);
        for (int i = 0; i < keys.size(); i++) {
            PredictionTileManager.PredictionTileKey left = keys.get(i);
            int leftSpan = PredictionTileManager.TILE_CHUNKS << left.lod();
            int leftMinX = left.tileX() * leftSpan;
            int leftMinZ = left.tileZ() * leftSpan;
            for (int j = i + 1; j < keys.size(); j++) {
                PredictionTileManager.PredictionTileKey right = keys.get(j);
                int rightSpan = PredictionTileManager.TILE_CHUNKS << right.lod();
                int rightMinX = right.tileX() * rightSpan;
                int rightMinZ = right.tileZ() * rightSpan;
                boolean overlap = leftMinX < rightMinX + rightSpan
                        && rightMinX < leftMinX + leftSpan
                        && leftMinZ < rightMinZ + rightSpan
                        && rightMinZ < leftMinZ + leftSpan;
                assertFalse(overlap, () -> "overlapping planner leaves: " + left + " / " + right);
            }
        }
    }

    @Test
    void coveringSelectionKeepsFinerLoadedTileWhenDistanceRequestsCoarser() {
        VssLodLayout layout = VssLodLayout.of(8_192, 6.0D, true, false);
        PredictionTileManager.PredictionTileKey fine =
                new PredictionTileManager.PredictionTileKey(DIMENSION, 17, -2, 0);
        PredictionTileManager.PredictionTileKey exact =
                new PredictionTileManager.PredictionTileKey(DIMENSION, 8, -1, 1);
        PredictionTileManager.PredictionTileKey parent =
                new PredictionTileManager.PredictionTileKey(DIMENSION, 2, -1, 3);

        assertEquals(fine, PredictionTileManager.findCoveringKey(
                Set.of(fine, exact, parent), DIMENSION, layout, 68, -7, 1));
        assertEquals(fine, PredictionTileManager.findCoveringKey(
                Set.of(fine), DIMENSION, layout, 68, -7, 1));
        assertEquals(parent, PredictionTileManager.findCoveringKey(
                Set.of(parent), DIMENSION, layout, 68, -7, 1));
    }

    @Test
    void coveringSelectionDoesNotTreatAnotherTileAsCoverage() {
        VssLodLayout layout = VssLodLayout.of(8_192, 6.0D, true, false);
        PredictionTileManager.PredictionTileKey elsewhere =
                new PredictionTileManager.PredictionTileKey(DIMENSION, 99, 99, 0);

        assertEquals(null, PredictionTileManager.findCoveringKey(
                Set.of(elsewhere), DIMENSION, layout, 68, -7, 1));
    }

    @Test
    void runtimePlanLeadsWithNearFineTiles() {
        VssLodLayout layout = VssLodLayout.of(8_192, 6.0D, true, false);
        VssLodFocus focus = new VssLodFocus(0.0D, 0.0D, 256.0D);
        List<PredictionTileManager.PredictionTileKey> keys = PredictionLodPlanner.plan(
                DIMENSION, 0.0D, 64.0D, 0.0D, layout, focus, 32.0D);
        assertFalse(keys.isEmpty());
        // The leading nearby work must be dominated by fine tiles:
        // near-field-first means the ground under the camera builds before
        // any coarse horizon sweep.
        int lead = Math.min(64, keys.size());
        long fineLead = keys.subList(0, lead).stream()
                .filter(key -> key.lod() <= 1).count();
        assertTrue(fineLead >= lead * 0.75,
                "plan must lead with near fine tiles, got " + fineLead + "/" + lead);
        assertTrue(keys.stream().anyMatch(key -> PredictionDetailBands.cellAxis(key,layout,0,0,null) == 32),
                "the outer horizon must advance beyond the initial coarse coverage");
    }

    @Test
    void workerQueuePrefersFineNearTilesOverCoarseFarTiles() {
        // The worker executes the plan order, so the queue contract is the
        // plan order: near fine leaves first, coarse horizon behind them.
        VssLodLayout layout = VssLodLayout.of(8_192, 6.0D, true, false);
        VssLodFocus focus = new VssLodFocus(0.0D, 0.0D, 512.0D);
        List<PredictionTileManager.PredictionTileKey> keys = PredictionLodPlanner.plan(
                DIMENSION, 0.0D, 64.0D, 0.0D, layout, focus, 32.0D);
        int firstCoarseIndex = -1;
        int lastFineIndex = -1;
        for (int index = 0; index < keys.size(); index++) {
            PredictionTileManager.PredictionTileKey key = keys.get(index);
            if (key.lod() >= layout.levelCount() - 2) {
                if (firstCoarseIndex < 0) firstCoarseIndex = index;
            } else if (key.lod() <= 1) {
                lastFineIndex = index;
            }
        }
        // Skeleton budget caps the tail at 64 entries; every near fine tile
        // must come before that reserved far-field block.
        assertTrue(firstCoarseIndex < 0 || lastFineIndex < firstCoarseIndex,
                "near fine tiles (last at " + lastFineIndex + ") must build before the"
                        + " coarse horizon skeleton (starts at " + firstCoarseIndex + ")");
    }

    @Test
    void saturatedRuntimePlanRefinesAllDirectionsAndKeepsCompleteCoverage() {
        VssLodLayout layout = VssLodLayout.of(8192, 1.0D, true, false);
        for (VssLodFocus focus : new VssLodFocus[]{null, new VssLodFocus(0, 0, 1024, 6144)}) {
            var keys = PredictionLodPlanner.plan(DIMENSION, 0, 182, 0, layout, focus, 1536);
            assertTrue(keys.size() <= (focus == null ? 1024 : 2048) + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
            assertEquals(keys.size(), new java.util.HashSet<>(keys).size());
            for (int distance : new int[]{32, 128, 512, 1024, 2048, 4096}) {
                int minLod = Integer.MAX_VALUE;
                int maxLod = -1;
                for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
                    int x = sx * distance;
                    int z = sz * distance;
                    var owners = keys.stream().filter(key -> {
                        int span = layout.tileBlocks(key.lod());
                        return Math.floorDiv(x, span) == key.tileX()
                                && Math.floorDiv(z, span) == key.tileZ();
                    }).toList();
                    assertEquals(1, owners.size());
                    int lod = owners.getFirst().lod();
                    minLod = Math.min(minLod, lod);
                    maxLod = Math.max(maxLod, lod);
                    if (distance <= 128) assertTrue(lod <= 1,
                            "near ground was starved at " + x + "," + z + ": lod=" + lod);
                }
                assertTrue(maxLod - minLod <= 1,
                        "traversal direction must not decide detail: " + minLod + ".." + maxLod);
            }
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
