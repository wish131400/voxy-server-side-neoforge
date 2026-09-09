package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * Voxy-style pinned residency: a built tile must stay resident anywhere
 * inside the prediction horizon, even after the focus or the plan moves
 * away, so turning the camera back re-renders it at full detail with zero
 * rebuild cost.  Retirement happens only beyond the horizon and only when
 * an ancestor keeps the region covered.
 */
class PredictionPinningTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation.withDefaultNamespace("overworld"));

    /** Horizon 2048 blocks: lod 0 tiles at tileX 50+ sit past the margin. */
    private static final VssLodLayout LAYOUT =
            VssLodLayout.of(2048, 6.0D, true, false);

    private static PredictionTileManager.PredictionTileKey key(int x, int z, int lod) {
        return new PredictionTileManager.PredictionTileKey(DIMENSION, x, z, lod);
    }

    @Test
    void focusBuiltTilesStayPinnedAfterTheFocusMovesAway() {
        // The camera sits on a lod 0 tile built by an earlier focus sweep;
        // the focus has since moved and the tile left the desired set, but a
        // coarser ancestor is resident.  Pinned residency keeps it.
        var camera = 0;
        var pinned = key(1, 1, 0);
        var ancestor = key(0, 0, 3);
        assertFalse(PredictionTileManager.shouldRetirePinned(pinned,
                Set.of(ancestor, pinned), Set.of(), DIMENSION, LAYOUT, camera, camera));
    }

    @Test
    void distantFocusTilesInsideTheHorizonStayPinned() {
        // A lod 2 tile refined by looking at a mountain ~2 km away: outside
        // the plan again, covered by its lod 3 parent, still inside the
        // horizon plus margin — nothing may retire it.
        var camera = 0;
        var distant = key(8, 8, 2);
        // Parent (4, 4, 3) spans blocks 2048..2560 and covers the tile.
        var parent = key(4, 4, 3);
        assertFalse(PredictionTileManager.shouldRetirePinned(distant,
                Set.of(parent, distant), Set.of(), DIMENSION, LAYOUT, camera, camera));
    }

    @Test
    void desiredTilesBeyondTheHorizonAreKept() {
        var far = key(50, 50, 0);
        assertFalse(PredictionTileManager.shouldRetirePinned(far,
                Set.of(far, key(6, 6, 3)), Set.of(far), DIMENSION, LAYOUT, 0, 0));
    }

    @Test
    void beyondHorizonTilesRetireOnlyWhenAnAncestorCoversThem() {
        var far = key(50, 50, 0);
        // Ancestor chain: lod 0 tileX 50 -> chunk 200; lod 3 spans 32 chunks
        // -> ancestor tile (6, 6, 3).  Resident ancestor retires the tile.
        assertTrue(PredictionTileManager.shouldRetirePinned(far,
                Set.of(far, key(6, 6, 3)), Set.of(), DIMENSION, LAYOUT, 0, 0));
        // Without any resident ancestor the tile is the only coverage left
        // and must stay so the far field never opens a hole.
        assertFalse(PredictionTileManager.shouldRetirePinned(far,
                Set.of(far), Set.of(), DIMENSION, LAYOUT, 0, 0));
    }

    @Test
    void staleLevelFromAShrunkenLayoutRetiresInsteadOfThrowing() {
        // A profile reinstall can shrink levelCount while lod-7 tiles from
        // the previous layout stay resident; the retirement walk must drop
        // them instead of reaching the layout's level validation.
        var stale = key(1, 1, 7);
        assertTrue(PredictionTileManager.shouldRetirePinned(stale,
                Set.of(stale), Set.of(), DIMENSION, LAYOUT, 0, 0));
        // And the fully-authoritative guard must treat it as unknown, not
        // throw (covered through shouldRetirePinned's early exit above).
    }

    @Test
    void horizonGeometryHonoursTileSpanAndMargin() {
        // Tile containing the camera.
        assertFalse(PredictionTileManager.beyondHorizon(0, 0, 64, 8, 8, 3072.0D));
        // Tile overlapping the margin ring (nearest point 3000 blocks).
        assertFalse(PredictionTileManager.beyondHorizon(3000, 0, 64, 0, 0, 3072.0D));
        // Tile fully past the horizon plus margin.
        assertTrue(PredictionTileManager.beyondHorizon(3200, 3200, 64, 0, 0, 3072.0D));
        // A big coarse tile counts as near while any part overlaps the ring.
        assertFalse(PredictionTileManager.beyondHorizon(2000, 2000, 2048, 0, 0, 3072.0D));
    }
}
