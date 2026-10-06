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
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final VssLodLayout LAYOUT = VssLodLayout.of(65536, 6, true, true);

    @Test void smallCachedTilesShareOneFrameWithoutExceedingTimeOrBytes() {
        var budget = new PredictionUploadBudget();
        int uploaded = 0;
        while (budget.allows(64 * 1024)) {
            budget.record(64 * 1024, 100_000);
            uploaded++;
        }
        assertEquals(16, uploaded, "small cache hits should not be limited to two per frame");
        budget.reset();
        budget.record(1, PredictionUploadBudget.MAX_NANOS);
        assertFalse(budget.allows(1), "measured upload time must still stop the burst");
        budget.reset();
        budget.record(PredictionUploadBudget.MAX_BYTES, 0);
        assertFalse(budget.allows(1), "byte budget must still stop the burst");
    }

    @Test void uploadCandidatesDrainAndRefreshForReplacementAndReset() {
        var state = new PredictionRenderResidency();
        var a = PredictionLodSeamsTest.tile(0, 0, 2, 64);
        var b = PredictionLodSeamsTest.tile(1, 0, 2, 64);
        var initial = source(Map.of(a.key(), a, b.key(), b));
        state.retain(initial);
        assertEquals(2, state.pendingUploads(initial).size());
        state.uploaded(a);
        assertEquals(java.util.List.of(b), state.pendingUploads(initial));
        state.uploaded(b);
        assertTrue(state.pendingUploads(initial).isEmpty());
        var replacement = PredictionLodSeamsTest.tile(0, 0, 2, 96);
        var next = source(Map.of(a.key(), replacement, b.key(), b));
        state.retain(next);
        assertEquals(java.util.List.of(replacement), state.pendingUploads(next));
        assertSame(a, state.snapshot(next).tiles().get(a.key()), "old mesh survives until its replacement uploads");
        state.clear();
        state.retain(next);
        assertEquals(2, state.pendingUploads(next).size());
    }

    @Test void unchangedPendingListsAvoidResidentScansButStillObserveChangingViewsAndUploads() throws Exception {
        var state = new PredictionRenderResidency();
        var counted = new HashMap<PredictionTileKey, PredictionTile>() {
            int reads;
            @Override public PredictionTile get(Object key) { reads++; return super.get(key); }
        };
        var tilesField = PredictionRenderResidency.class.getDeclaredField("tiles");
        tilesField.setAccessible(true);
        tilesField.set(state, counted);
        var tile = PredictionLodSeamsTest.tile(0, 0, 2, 64);
        var initial = source(Map.of(tile.key(), tile));
        state.retain(initial);
        assertFalse(state.hasPendingUploads(initial, pending -> false));
        int reads = counted.reads;
        for (int frame = 0; frame < 120; frame++) {
            assertEquals(java.util.List.of(tile), state.pendingUploads(initial));
            assertTrue(state.hasPendingUploads(initial, pending -> true), "entering the horizon must see retained pending work immediately");
        }
        assertEquals(reads, counted.reads, "unchanged pending work must not scan GPU residents each frame");
        state.uploaded(tile);
        assertFalse(state.hasPendingUploads(initial, pending -> true));
        var replacement = PredictionLodSeamsTest.tile(0, 0, 2, 96);
        var next = source(Map.of(replacement.key(), replacement));
        state.retain(next);
        assertEquals(java.util.List.of(replacement), state.pendingUploads(next));
        assertSame(tile, state.snapshot(next).tiles().get(tile.key()));
        state.clear();
        state.retain(next);
        assertTrue(state.hasPendingUploads(next, pending -> true));
    }

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
            budget.record(1024, 1_000_000);
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
            budget.record(1024, 1_000_000);
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

    @Test void shrinkingHorizonPreservesUploadedNearbyDetailAndInvalidatesOwnership() {
        var state = new PredictionRenderResidency();
        var near = tile(1, 0, 0, 1);
        var far = tile(200, 0, 0, 2);
        var root = tile(0, 0, 10, 3);
        var initial = source(Map.of(near.key(), near, far.key(), far, root.key(), root));
        state.retain(initial);
        state.uploaded(near);
        state.uploaded(far);
        state.uploaded(root);
        var before = state.snapshot(initial);
        var smaller = new RenderSnapshot(Level.OVERWORLD, VssLodLayout.of(4096, 6, true, true),
                Map.of(near.key(), near), Map.of());
        state.retain(smaller);
        assertTrue(state.contains(near), "no second upload or blank frame for unchanged nearby geometry");
        assertFalse(state.contains(far));
        assertFalse(state.contains(root));
        var after = state.snapshot(smaller);
        assertEquals(smaller.layout(), after.layout());
        assertTrue(after.epoch(near.key()) > before.epoch(near.key()), "layout-dependent masks must be recalculated");
        assertEquals(3, before.tiles().size(), "old frame remains immutable");

        state.retain(initial);
        assertTrue(state.contains(near), "expansion also reuses existing GPU payloads");
        assertFalse(state.contains(far), "retired tiles still need a real upload");
    }

    @Test void shrinkingHorizonDoesNotPinEvictedGpuDetailBehindAnUnuploadedParent() {
        var state = new PredictionRenderResidency();
        var child = tile(70, 0, 0, 1);
        var initial = source(Map.of(child.key(), child));
        state.retain(initial);
        state.uploaded(child);
        // This large parent intersects the new radius, but the old child does not.
        var parent = tile(1, 0, 6, 2);
        var smaller = new RenderSnapshot(Level.OVERWORLD, VssLodLayout.of(4096, 6, true, true),
                Map.of(parent.key(), parent), Map.of());
        state.retain(smaller);
        assertFalse(state.contains(child), "out-of-range geometry must release even before fallback uploads");
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

    @Test void seamAndMaskUploadsReduceOptionalUpgradesWithoutStarvingProgress() {
        var budget = new PredictionUploadBudget();
        budget.recordRequired(4096, PredictionUploadBudget.MAX_NANOS);
        budget.reset();
        assertTrue(budget.allows(1), "one upgrade still makes progress");
        budget.record(1, 1);
        assertFalse(budget.allows(1), "handoff costs must reserve the next frame's optional budget");
        budget.reset();
        budget.record(1, 1);
        assertTrue(budget.allows(1), "unchanged seams must not permanently consume a budget");
        budget.recordRequired(PredictionUploadBudget.MAX_BYTES, 0);
        budget.clear();
        budget.record(1, 1);
        assertTrue(budget.allows(1), "world reset removes the previous world's reservation");
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

    @Test void telescopeRefinementKeepsUploadedOrdinaryDetailThroughPreviewAndUpload() {
        var state=new PredictionRenderResidency();
        var parent=tile(8,-1,3,1);
        var initial=source(Map.of(parent.key(),parent));
        state.retain(initial); state.uploaded(parent);
        var before=state.snapshot(initial);
        var childKey=new PredictionTileKey(Level.OVERWORLD,16,-1,2);
        var preview=new PredictionTile(childKey,new int[0],new int[0],new ClientColumnSample[0],null,
                new PredictionDepthBound(64,64),0,2,8,32,true);
        var published=source(Map.of(parent.key(),parent,preview.key(),preview));
        state.retain(published);
        assertSame(parent,state.snapshot(published).coveringTileAtDetail(256,-1,0),"CPU publication cannot transfer ownership before upload");
        state.uploaded(preview);
        assertSame(parent,state.snapshot(published).coveringTileAtDetail(256,-1,0),"a smaller tile with coarser actual samples cannot replace detail");
        var child=new PredictionTile(childKey,new int[0],new int[0],new ClientColumnSample[0],null,
                new PredictionDepthBound(64,64),0,3,64,4,true);
        var completed=source(Map.of(parent.key(),parent,child.key(),child));
        state.retain(completed);
        assertSame(parent,state.snapshot(completed).coveringTileAtDetail(256,-1,0),"the finished child also waits for a GPU upload");
        state.uploaded(child);
        assertSame(child,state.snapshot(completed).coveringTileAtDetail(256,-1,0));
        assertSame(parent,state.snapshot(completed).coveringTileAtDetail(272,-1,0),"unreplaced neighboring chunks keep their parent");
        assertSame(parent,before.coveringTileAtDetail(256,-1,0),"the previously rendered frame stays immutable");
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
