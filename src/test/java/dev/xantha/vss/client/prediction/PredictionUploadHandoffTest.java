package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionUploadHandoffTest {
    private static final VssLodLayout LAYOUT = VssLodLayout.of(65536, 6, true, true);

    @Test void frameBudgetKeepsCoarseCoverageUntilEachChildHasActuallyUploaded() {
        var state = new PredictionRenderResidency();
        var root = tile(0, 0, 1, 1);
        var sourceTiles = new HashMap<PredictionTileKey, PredictionTile>();
        sourceTiles.put(root.key(), root);
        var initial = source(sourceTiles);
        state.retain(initial);
        state.uploaded(root);
        var oldFrame = state.snapshot(initial);
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            var child = tile(x, z, 0, 2 + x + z * 2);
            sourceTiles.put(child.key(), child);
        }
        var completedOnWorkers = source(sourceTiles);
        state.retain(completedOnWorkers);
        assertSame(root, state.snapshot(completedOnWorkers).coveringTile(0, 0, 0));
        var budget = new PredictionUploadBudget();
        for (var tile : sourceTiles.values()) if (tile != root && budget.allows(1024)) {
            state.uploaded(tile);
            budget.record(1024, 100_000);
        }
        var halfway = state.snapshot(completedOnWorkers);
        assertEquals(3, halfway.tiles().size(), "one parent and two uploaded children");
        for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
            assertNotNull(halfway.coveringTile(x, z, 0), "deferred uploads cannot open holes");
            assertSame(root, oldFrame.coveringTile(x, z, 0), "a completed frame cannot change mid-draw");
        }
        budget.reset();
        for (var tile : sourceTiles.values()) if (!state.contains(tile) && budget.allows(1024)) {
            state.uploaded(tile);
            budget.record(1024, 100_000);
        }
        assertEquals(5, state.snapshot(completedOnWorkers).tiles().size());
        var child = sourceTiles.get(new PredictionTileKey(Level.OVERWORLD, 0, 0, 0));
        var decorated = tile(0, 0, 0, 100);
        sourceTiles.put(decorated.key(), decorated);
        var upgraded = source(sourceTiles);
        state.retain(upgraded);
        assertSame(child, state.snapshot(upgraded).coveringTile(0, 0, 0));
        state.uploaded(decorated);
        assertSame(decorated, state.snapshot(upgraded).coveringTile(0, 0, 0));
    }

    @Test void evictedDetailRemainsUntilItsFallbackParentUploads() {
        var state = new PredictionRenderResidency();
        var child = tile(0, 0, 0, 1);
        var initial = source(Map.of(child.key(), child));
        state.retain(initial);
        state.uploaded(child);
        var parent = tile(0, 0, 1, 2);
        var replacement = source(Map.of(parent.key(), parent));
        state.retain(replacement);
        assertSame(child, state.snapshot(replacement).coveringTile(0, 0, 0));
        state.uploaded(parent);
        state.retain(replacement);
        assertSame(parent, state.snapshot(replacement).coveringTile(0, 0, 0));
        assertFalse(state.snapshot(replacement).tiles().containsKey(child.key()));
    }

    @Test void uploadBudgetsLimitTimeAndBytesButAllowOneOversizedTileToProgress() {
        var budget = new PredictionUploadBudget();
        assertTrue(budget.allows(PredictionUploadBudget.MAX_BYTES * 2));
        budget.record(PredictionUploadBudget.MAX_BYTES * 2, 10);
        assertFalse(budget.allows(1));
        budget.reset();
        budget.record(1, PredictionUploadBudget.MAX_NANOS);
        assertFalse(budget.allows(1));
        budget.reset();
        assertTrue(budget.allows(1));
    }

    @Test void loadedDetailSurvivesDistanceSelectionAndCoarserChildPreviews() {
        var fullParent = tile(64,-65,1,1);
        var preview = new PredictionTile(new PredictionTileKey(Level.OVERWORLD,128,-130,0),
                new int[0],new int[0],new ClientColumnSample[0],null,
                new PredictionDepthBound(64,64),0,2,8,8);
        var initial = source(Map.of(fullParent.key(),fullParent,preview.key(),preview));
        for (int desired=0; desired<11; desired++) assertSame(fullParent,initial.coveringTile(512,-520,desired),
                "Nominal LOD 0 preview must not replace actual two-block detail");
        var fullChild = tile(128,-130,0,3);
        var refined = source(Map.of(fullParent.key(),fullParent,fullChild.key(),fullChild));
        for (int desired=0; desired<11; desired++) assertSame(fullChild,refined.coveringTile(512,-520,desired),
                "Already loaded distant detail must survive closing the telescope");
    }

    private static RenderSnapshot source(Map<PredictionTileKey, PredictionTile> tiles) {
        return new RenderSnapshot(Level.OVERWORLD, LAYOUT, Map.copyOf(tiles), Map.of());
    }
    private static PredictionTile tile(int x, int z, int lod, long revision) {
        return new PredictionTile(new PredictionTileKey(Level.OVERWORLD, x, z, lod),
                new int[0], new int[0], new ClientColumnSample[0], null,
                new PredictionDepthBound(64, 64), 0, revision, 64, 1 << lod);
    }
}
