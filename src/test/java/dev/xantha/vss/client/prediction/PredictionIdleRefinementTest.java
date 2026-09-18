package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionIdleRefinementTest {
    private final VssLodLayout layout = VssLodLayout.of(8192, 2, true, true);
    private final PredictionTileKey root = new PredictionTileKey(Level.OVERWORLD, -2, -3, 4);

    @Test void idleNeedsStableHeadroomAndSettlesAgainAfterMovementOrPressure() {
        var policy = new PredictionIdleRefinement();
        assertFalse(policy.settled(0, true, 0, 0));
        assertTrue(policy.settled(2_000_000_000L, true, 0, 0));
        assertFalse(policy.settled(3_000_000_000L, true, 17, 0));
        assertTrue(policy.settled(5_000_000_000L, true, 17, 0));
        assertFalse(policy.settled(6_000_000_000L, false, 17, 0));
        assertFalse(policy.settled(7_000_000_000L, true, 17, 0));
        assertTrue(policy.settled(9_000_000_000L, true, 17, 0));
    }

    @Test void doublesGridThenSubdividesWithCompleteCoverageAtNegativeCoordinates() {
        var policy = new PredictionIdleRefinement();
        Map<PredictionTileKey, Integer> resident = new HashMap<>(Map.of(root, 16));
        var base = Map.of(root, 16);
        var first = policy.update(Set.of(root), base, resident, layout, k -> true, k -> 4, true, 0);
        assertEquals(Map.of(root, 32), first);
        assertEquals(first, policy.update(Set.of(root), base, resident, layout, k -> true, k -> 4, true, 2_000_000_000L),
                "unfinished optional work must not expand the plan");
        resident.put(root, 32);
        var second = policy.update(Set.of(root), base, resident, layout, k -> true, k -> 4, true, 3_000_000_000L);
        assertEquals(Map.of(root, 64), second);
        resident.put(root, 64);
        var split = policy.update(Set.of(root), base, resident, layout, k -> true, k -> 4, true, 5_000_000_000L);
        assertEquals(5, split.size());
        assertEquals(64, split.get(root));
        for (var child : PredictionTransitionPlan.children(root)) {
            assertEquals(64, split.get(child));
            assertEquals(root.tileX(), Math.floorDiv(child.tileX(), 2));
            assertEquals(root.tileZ(), Math.floorDiv(child.tileZ(), 2));
        }
        assertEquals(split, policy.update(Set.of(root), base, resident, layout, k -> true, k -> 4, false, 7_000_000_000L),
                "pressure pauses new work without discarding completed targets");
    }

    @Test void ranksVisualBenefitAndPrunesTargetsWhenOrdinaryPlanTakesOwnership() {
        var policy = new PredictionIdleRefinement();
        var other = new PredictionTileKey(Level.OVERWORLD, 4, 3, 4);
        var base = Map.of(root, 32, other, 32);
        var targets = policy.update(base.keySet(), base, base, layout, k -> true,
                k -> k.equals(root) ? 8 : 2, true, 0);
        assertEquals(Map.of(root, 64), targets);
        var complete = Map.of(root, 64, other, 32);
        assertTrue(policy.update(complete.keySet(), complete, complete, layout, k -> true,
                k -> 0, false, 1_000_000_000L).isEmpty());
        assertTrue(policy.update(Set.of(other), Map.of(other, 32), base, layout, k -> false,
                k -> 8, true, 2_000_000_000L).isEmpty());
    }

    @Test void targetCountStaysBoundedUnderRepeatedSplits() {
        var policy = new PredictionIdleRefinement();
        var base = Map.of(root, 64);
        Map<PredictionTileKey, Integer> resident = new HashMap<>(base);
        for (int pass = 0; pass < 200; pass++) {
            var targets = policy.update(Set.of(root), base, resident, layout,
                    k -> true, k -> 16, true, pass * 2_000_000_000L);
            assertTrue(targets.size() <= PredictionIdleRefinement.MAX_TARGETS);
            resident.putAll(targets);
        }
        assertTrue(resident.size() > 64, "test must exercise the expansion budget");
    }

    @Test void upgradesCoarseNeighbourBeforeCreatingATwoLevelBoundary() {
        var policy = new PredictionIdleRefinement();
        var left = new PredictionTileKey(Level.OVERWORLD, 0, 0, 3);
        var right = new PredictionTileKey(Level.OVERWORLD, 1, 0, 3);
        var edge = new PredictionTileKey(Level.OVERWORLD, 1, 0, 2);
        var base = Map.of(left, 64, right, 64);
        Map<PredictionTileKey, Integer> resident = new HashMap<>(base);
        var first = policy.update(base.keySet(), base, resident, layout, k -> true,
                k -> k.equals(left) ? 10 : 1, true, 0);
        resident.putAll(first);
        var second = policy.update(base.keySet(), base, resident, layout, k -> true,
                k -> k.equals(edge) ? 100 : k.equals(right) ? 10 : 0, true, 2_000_000_000L);
        assertTrue(second.keySet().containsAll(PredictionTransitionPlan.children(right)));
        assertFalse(second.keySet().containsAll(PredictionTransitionPlan.children(edge)));
    }

    @Test void optionalMemoryRequiresWorkspaceAndNormalLoadingReserve() {
        long mib = PredictionMemoryBudget.MIB;
        var free = new java.util.concurrent.atomic.AtomicLong(1024 * mib);
        var budget = new PredictionMemoryBudget(1024 * mib, 64 * mib, free::get, () -> 0, 1);
        assertTrue(budget.allowsIdleRefinement());
        free.set(447 * mib);
        assertFalse(budget.allowsIdleRefinement());
        assertFalse(budget.exhausted(), "normal loading must remain admitted below the optional threshold");
        free.set(1024 * mib);
        budget.pauseAfterOutOfMemory();
        assertFalse(budget.allowsIdleRefinement());
    }
}
