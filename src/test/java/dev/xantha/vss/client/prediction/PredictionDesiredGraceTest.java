package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * Camera movement reshuffles leaf distances, so tiles at the plan's budget
 * cutoff and the focus edge flip in and out of the exact plan every cycle.
 * Retirement must honour a short grace window, otherwise moving destroys and
 * rebuilds the same tiles over and over.
 */
class PredictionDesiredGraceTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("overworld"));

    private static PredictionTileManager.PredictionTileKey key(int x, int z, int lod) {
        return new PredictionTileManager.PredictionTileKey(DIMENSION, x, z, lod);
    }

    @Test
    void desiredTilesAreAlwaysKept() {
        var desired = Set.of(key(1, 1, 0));
        assertTrue(PredictionTileManager.effectivelyDesired(
                desired, new HashMap<>(), key(1, 1, 0), 1_000L, 5_000L));
    }

    @Test
    void recentlyPlannedTilesStayWithinTheGraceWindow() {
        var desired = Set.<PredictionTileManager.PredictionTileKey>of();
        Map<PredictionTileManager.PredictionTileKey, Long> grace = new HashMap<>();
        grace.put(key(2, 2, 1), 1_000L);
        // 3s after leaving the plan, still inside a 5s grace.
        assertTrue(PredictionTileManager.effectivelyDesired(
                desired, grace, key(2, 2, 1), 4_000L, 5_000L));
        // 6s after, the grace has expired.
        assertFalse(PredictionTileManager.effectivelyDesired(
                desired, grace, key(2, 2, 1), 7_000L, 5_000L));
    }

    @Test
    void unknownTilesHaveNoGrace() {
        assertFalse(PredictionTileManager.effectivelyDesired(
                Set.of(), new HashMap<>(), key(3, 3, 2), 1_000L, 5_000L));
    }
}
