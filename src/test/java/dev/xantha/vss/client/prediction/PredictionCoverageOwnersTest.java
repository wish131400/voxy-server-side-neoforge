package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionCoverageOwnersTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void pagedPublicationsInvalidateOnlyChangedFootprintsAndPreserveNegativeMissingAnswers() {
        var cache = new PredictionCoverageOwners(); var layout = VssLodLayout.of(8192, 6, false, false);
        var near = PredictionLodSeamsTest.tile(0, 0, 8, 64); var far = PredictionLodSeamsTest.tile(20, 20, 8, 64);
        var child = PredictionLodSeamsTest.tile(0, 0, 1, 64); var negative = PredictionLodSeamsTest.tile(-1, -1, 1, 64);
        var table = new PredictionTileTable<PredictionTileManager.PredictionTile>(Level.OVERWORLD)
                .changed(Map.of(near.key(), near, far.key(), far), Set.of());
        var first = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
        assertSame(far, cache.coveringTileAtDetail(first, 641, 641, 3));
        assertSame(near, cache.coveringTileAtDetail(first, 0, 0, 3));
        assertNull(cache.coveringTileAtDetail(first, -1, -1, 3));
        long misses = cache.misses();
        table = table.changed(Map.of(child.key(), child), Set.of());
        var second = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
        assertSame(far, cache.coveringTileAtDetail(second, 641, 641, 12));
        assertNull(cache.coveringTileAtDetail(second, -1, -1, 7));
        assertEquals(misses, cache.misses(), "unrelated publication and desired detail retain warm ordinary owners");
        assertSame(child, cache.coveringTileAtDetail(second, 0, 0, 3)); assertEquals(++misses, cache.misses());
        table = table.changed(Map.of(negative.key(), negative), Set.of(child.key()));
        var third = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
        assertSame(near, cache.coveringTileAtDetail(third, 0, 0, 3));
        assertSame(negative, cache.coveringTileAtDetail(third, -1, -1, 3));
        misses += 2; assertEquals(misses, cache.misses());
        assertSame(far, cache.coveringTileAtDetail(third, 641, 641, 3)); assertEquals(misses, cache.misses());
        var upgraded = new PredictionTileManager.PredictionTile(far.key(), far.heights(), far.groundHeights(), far.samples(),
                far.mesh(), far.depthBound(), 0, far.revision() + 1, far.cellAxis(), far.spacingBlocks());
        table = table.changed(Map.of(far.key(), upgraded), Set.of());
        var fourth = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
        assertSame(upgraded, cache.coveringTileAtDetail(fourth, 641, 641, 3)); assertEquals(++misses, cache.misses());
        assertSame(far, first.coveringTileAtDetail(641, 641, 3), "older immutable frames retain their original mesh");
    }

    @Test void telescopeOwnersRemainDetailDependentAcrossLocalPublication() {
        var cache = new PredictionCoverageOwners(); var layout = VssLodLayout.of(8192, 6, false, false);
        var ordinary = PredictionLodSeamsTest.tile(0, 0, 32, 64); var base = PredictionLodSeamsTest.tile(0, 0, 1, 64);
        var scoped = new PredictionTileManager.PredictionTile(base.key(), base.heights(), base.groundHeights(), base.samples(),
                base.mesh(), base.depthBound(), 0, 1, 64, 1, true);
        var table = new PredictionTileTable<PredictionTileManager.PredictionTile>(Level.OVERWORLD)
                .changed(Map.of(ordinary.key(), ordinary, scoped.key(), scoped), Set.of());
        var first = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
        assertSame(scoped, cache.coveringTileAtDetail(first, 0, 0, 0));
        assertSame(ordinary, cache.coveringTileAtDetail(first, 0, 0, 5));
        table = table.changed(Map.of(), Set.of(scoped.key()));
        var second = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
        assertSame(ordinary, cache.coveringTileAtDetail(second, 0, 0, 0));
        assertSame(ordinary, cache.coveringTileAtDetail(second, 0, 0, 5));
    }

    @Test void capacityChurnReusesArraysAndNeverDropsAllWarmQueries() throws Exception {
        var cache = new PredictionCoverageOwners(4096); var layout = VssLodLayout.of(8192, 6, false, false);
        var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(), Map.of());
        var storage = PredictionCoverageOwners.class.getDeclaredField("owners"); storage.setAccessible(true);
        var before = storage.get(cache);
        for (int x = 0; x < 30_000; x++) {
            assertNull(cache.coveringTileAtDetail(snapshot, x * 4, -x * 4, 7));
            assertNull(cache.coveringTileAtDetail(snapshot, x * 4, -x * 4, 17));
        }
        assertEquals(30_000, cache.misses()); assertEquals(30_000, cache.hits()); assertTrue(cache.evictions() > 0);
        cache.clear(); assertSame(before, storage.get(cache));
    }

    @Test void baseTileFootprintSharesOneQueryIncludingNegativeCoordinates() {
        var cache = new PredictionCoverageOwners();
        var layout = VssLodLayout.of(4096, 6, false, false);
        var tile = PredictionLodSeamsTest.tile(-1, -1, 1, 64);
        var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout,
                Map.of(tile.key(), tile), Map.of());
        for (int z = -4; z < 0; z++) for (int x = -4; x < 0; x++)
            assertSame(tile, cache.coveringTileAtDetail(snapshot, x, z, 5));
        assertEquals(1, cache.misses());
        assertEquals(15, cache.hits());
        assertNull(cache.coveringTileAtDetail(snapshot, 0, -1, 5));
        assertNull(cache.coveringTileAtDetail(snapshot, -5, -1, 5));
        assertEquals(3, cache.misses(), "adjacent footprints must stay independent");
    }

    @Test void sharedQueriesMatchTheSnapshotAcrossLodsAndSnapshotReplacement() {
        var cache = new PredictionCoverageOwners();
        var layout = VssLodLayout.of(4096, 6, false, false);
        var tiles = new HashMap<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>();
        for (int lod = 0; lod < 5; lod++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            var tile = PredictionLodSeamsTest.tile(x, z, 1 << lod, 64);
            tiles.put(tile.key(), tile);
        }
        var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.copyOf(tiles), Map.of());
        for (int repeat = 0; repeat < 2; repeat++) for (int lod = 0; lod < 6; lod++)
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++)
                assertSame(snapshot.coveringTileAtDetail(x, z, lod), cache.coveringTileAtDetail(snapshot, x, z, lod));
        assertTrue(cache.hits() > 2000);
        long misses = cache.misses();
        tiles.clear();
        snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.copyOf(tiles), Map.of());
        assertNull(cache.coveringTileAtDetail(snapshot, 0, 0, 0), "old owners cannot survive a changed resident snapshot");
        assertEquals(misses + 1, cache.misses());
        assertNull(cache.coveringTileAtDetail(snapshot, 0, 0, 0));
        assertEquals(misses + 1, cache.misses(), "missing owners must also be shared");
    }
}
