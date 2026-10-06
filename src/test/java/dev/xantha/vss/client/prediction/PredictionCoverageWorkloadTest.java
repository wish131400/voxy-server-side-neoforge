package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionCoverageWorkloadTest {
    private boolean oldVoxy, oldHook;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void setup() throws Exception {
        oldVoxy = field(ModCompat.class, "voxyLoaded").getBoolean(null);
        oldHook = field(StrictLodVisibility.class, "renderHookSeen").getBoolean(null);
        StrictLodVisibility.reset();
        field(ModCompat.class, "voxyLoaded").setBoolean(null, true);
        field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, true);
        field(StrictLodVisibility.class, "dimension").set(null, Level.OVERWORLD);
        field(StrictLodVisibility.class, "uploadedNodes").setBoolean(null, true);
        field(StrictLodVisibility.class, "meshPipeline").set(null, new StrictVoxyPipeline());
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 1024);
    }
    @AfterEach void cleanup() throws Exception {
        StrictLodVisibility.reset(); field(ModCompat.class, "voxyLoaded").setBoolean(null, oldVoxy);
        field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, oldHook);
    }

    @Test void lodSevenOwnershipNeverScansVoxyMetadata() throws Exception {
        var tile = PredictionLodSeamsTest.tile(0, 0, 128, 64);
        var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, VssLodLayout.of(8192, 6, false, false),
                Map.of(tile.key(), tile), Map.of());
        var nodes = (StrictVoxyNodeIndex) field(StrictLodVisibility.class, "NODES").get(null);
        int id = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            nodes.update(id++, StrictVoxyNodeIndex.key(4, x, 0, z), 123);
        var regions = (StrictVoxyCoverageRegions) field(StrictLodVisibility.class, "REGION_COVERAGE").get(null);
        var columns = (StrictVoxyCoverageCache) field(StrictLodVisibility.class, "COLUMN_COVERAGE").get(null);
        var resolve = PredictionRenderer.class.getDeclaredMethod("resolveCoverage", PredictionTileManager.PredictionTile.class,
                int.class, int.class, double.class, PredictionTileManager.RenderSnapshot.class, VssLodFocus.class);
        resolve.setAccessible(true);
        long columnBefore = columns.hits() + columns.misses(), regionBefore = regions.misses(), nodeBefore = nodes.boxLookups();
        var result = (PredictionRenderer.TileCoverage) resolve.invoke(null, tile, 0, 0, 1400D, snapshot, null);
        assertEquals(4096, result.rendered()); assertEquals(0, result.authoritativeSkipped());
        assertEquals(0, columns.hits() + columns.misses() - columnBefore);
        assertEquals(0, regions.misses() - regionBefore);
        assertEquals(0, nodes.boxLookups() - nodeBefore);
        nodeBefore = nodes.boxLookups(); long misses = regions.misses(), hits = regions.hits();
        var warm = (PredictionRenderer.TileCoverage) resolve.invoke(null, tile, 0, 0, 1400D, snapshot, null);
        assertArrayEquals(result.allowed(), warm.allowed());
        assertEquals(nodeBefore, nodes.boxLookups()); assertEquals(misses, regions.misses()); assertEquals(0, regions.hits() - hits);
        System.out.println("coverage-workload: original-column-queries=262144, cold-region-proofs=0, cold-node-lookups=0, warm-node-lookups=0");
    }

    @Test void aHundredUnrelatedPublicationsPreserveNearlyAllWarmOwnershipQueries() {
        var cache = new PredictionCoverageOwners(); var layout = VssLodLayout.of(8192, 6, false, false);
        var tile = PredictionLodSeamsTest.tile(0, 0, 128, 64);
        var table = new PredictionTileTable<PredictionTileManager.PredictionTile>(Level.OVERWORLD).changed(Map.of(tile.key(), tile), Set.of());
        var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
        for (int z = 4; z < 512; z += 8) for (int x = 4; x < 512; x += 8)
            assertSame(tile, cache.coveringTileAtDetail(snapshot, x, z, 7));
        long before = cache.misses(), hits = cache.hits();
        for (int publication = 0; publication < 100; publication++) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 1000 + publication, 1000, 0);
            var far = new PredictionTileManager.PredictionTile(key, tile.heights(), tile.groundHeights(), tile.samples(),
                    tile.mesh(), tile.depthBound(), 0, publication + 1, 64, 1);
            table = table.changed(Map.of(key, far), Set.of());
            snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, table, Map.of());
            for (int z = 4; z < 512; z += 8) for (int x = 4; x < 512; x += 8)
                assertSame(tile, cache.coveringTileAtDetail(snapshot, x, z, 7));
        }
        long rechecks = cache.misses() - before, reused = cache.hits() - hits;
        assertTrue(rechecks < 128, "only conservative stamp collisions may recheck unrelated owners: " + rechecks);
        assertEquals(409_600, rechecks + reused);
        System.out.println("ownership-workload: publications=100, queries=409600, local-rechecks=" + rechecks + ",reused=" + reused);
    }

    @Test void ordinaryFineTileResolvesOwnershipOnceForAllSixteenChunks() throws Exception {
        var tile = PredictionLodSeamsTest.tile(-1, -1, 1, 64);
        var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, VssLodLayout.of(8192, 6, false, false),
                Map.of(tile.key(), tile), Map.of());
        var cache = (PredictionCoverageOwners) field(PredictionRenderer.class, "coverageOwners").get(null);
        var resolve = PredictionRenderer.class.getDeclaredMethod("resolveCoverage", PredictionTileManager.PredictionTile.class,
                int.class, int.class, double.class, PredictionTileManager.RenderSnapshot.class, VssLodFocus.class);
        resolve.setAccessible(true);
        long before = cache.hits() + cache.misses();
        var result = (PredictionRenderer.TileCoverage) resolve.invoke(null, tile, 0, 0, 1400D, snapshot, null);
        assertEquals(4096, result.rendered());
        assertEquals(1, cache.hits() + cache.misses() - before,
                "one ordinary 64x64 tile has one owner, regardless of its 4096 surface cells");
        System.out.println("fine-tile-ownership: cells=4096, chunks=16, ownership-queries=1");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
