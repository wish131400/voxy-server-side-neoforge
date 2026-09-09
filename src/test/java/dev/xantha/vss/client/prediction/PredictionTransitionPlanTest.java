package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionTransitionPlanTest {
    private static PredictionTileKey key(int x, int z, int lod) {
        return new PredictionTileKey(Level.OVERWORLD, x, z, lod);
    }

    @Test void largeNeighborIsSplitIntoIntermediateBandsWithoutChangingCoverage() {
        for (int sign : new int[]{1,-1}) {
            var fine = key(sign > 0 ? -1 : 0, 0, 0);
            var coarse = key(sign > 0 ? 0 : -1, 0, 6);
            var balanced = PredictionTransitionPlan.balance(List.of(fine, coarse), 11);
            assertTrue(balanced.contains(fine));
            assertFalse(balanced.contains(coarse));
            assertBalanced(balanced);
            long area = balanced.stream().mapToLong(k -> 1L << (k.lod()*2)).sum();
            assertEquals(1 + (1L << 12), area, "Splitting must preserve coverage area");
            assertTrue(balanced.size() <= 128);
        }
    }

    @Test void exposedParentTracksChildDetailButFullyCoveredParentsStayCheap() {
        var parent = key(-1,0,3);
        var children = PredictionTransitionPlan.children(parent);
        var child = children.getFirst();
        var planned = new HashSet<>(List.of(parent,child));
        var axes = new HashMap<>(Map.of(parent,8,child,16));
        assertEquals(32, PredictionTransitionPlan.exposedTargets(planned,Set.of(child),axes,11).get(parent));
        planned.addAll(children);
        assertEquals(32, PredictionTransitionPlan.exposedTargets(planned,Set.of(child),axes,11).get(parent));
        children.forEach(key -> axes.putIfAbsent(key,8));
        assertTrue(PredictionTransitionPlan.exposedTargets(planned,Set.of(child),axes,11).isEmpty());
        planned.remove(children.getLast());
        axes.remove(children.getLast());
        axes.put(parent,64);
        assertTrue(PredictionTransitionPlan.exposedTargets(planned,Set.of(child),axes,11).isEmpty());
    }

    @Test void actualNearbyAndTelescopePlansHaveBoundedNeighborLevelDifferences() {
        var layout = VssLodLayout.of(65536,2,true,true);
        for (var focus : new VssLodFocus[]{null,new VssLodFocus(8192,8192,1024,1000)}) {
            var plan = PredictionLodPlanner.plan(Level.OVERWORLD,282,151,-85,layout,focus,700);
            assertBalanced(plan);
            assertTrue(plan.size() <= (focus == null ? 1024 : 2048) + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
        }
    }

    private static void assertBalanced(List<PredictionTileKey> keys) {
        var set = new HashSet<>(keys);
        for (var key : keys) for (int[] direction : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
            for (int lod = key.lod()+2; lod < 11; lod++) {
                int shift = lod-key.lod();
                assertFalse(set.contains(key((key.tileX()+direction[0]) >> shift,
                        (key.tileZ()+direction[1]) >> shift,lod)), "Missing transition beside " + key);
            }
        }
    }
}
