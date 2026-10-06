package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.StrictLodVisibility;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionVoxyCoverageInvalidationTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void requestRingAndVoxyResetRetainWarmPredictionOwnership() {
        StrictLodVisibility.reset();
        try {
            var view = new PredictionRenderer.CoverageView(0, 0, 1400, null).ownership(false);
            var cached = new PredictionRenderer.CachedCoverage(1, 2, 0, view, null);
            for (int edit = 0; edit < 10_000; edit++) StrictLodVisibility.workChanged();
            assertTrue(cached.matchesOwnership(1, 2, view));
            StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 64);
            assertTrue(cached.matchesOwnership(1, 2, view));
        } finally { StrictLodVisibility.reset(); }
    }

    @Test @SuppressWarnings("unchecked")
    void hiddenParentSkipsVoxyQueriesButMissingChildrenStillDrawFallbackAndHandoff() throws Exception {
        boolean oldVoxy = field(dev.xantha.vss.compat.ModCompat.class, "voxyLoaded").getBoolean(null);
        boolean oldRenderHook = field(StrictLodVisibility.class, "renderHookSeen").getBoolean(null);
        StrictLodVisibility.reset();
        try {
            field(dev.xantha.vss.compat.ModCompat.class, "voxyLoaded").setBoolean(null, true);
            field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, true);
            field(StrictLodVisibility.class, "dimension").set(null, Level.OVERWORLD);
            field(StrictLodVisibility.class, "uploadedNodes").setBoolean(null, true);
            field(StrictLodVisibility.class, "meshPipeline").set(null, new dev.xantha.vss.compat.StrictVoxyPipeline());
            StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 512);
            var queryCache = (dev.xantha.vss.compat.StrictVoxyCoverageCache) field(StrictLodVisibility.class, "COLUMN_COVERAGE").get(null);
            var parent = PredictionLodSeamsTest.tile(0, 0, 2, 64);
            var layout = VssLodLayout.of(4096, 6, false, false);
            var tiles = new HashMap<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>();
            tiles.put(parent.key(), parent);
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
                var child = PredictionLodSeamsTest.tile(x, z, 1, 64);
                tiles.put(child.key(), child);
            }
            var resolve = PredictionRenderer.class.getDeclaredMethod("resolveCoverage", PredictionTileManager.PredictionTile.class,
                    int.class, int.class, double.class, PredictionTileManager.RenderSnapshot.class, VssLodFocus.class);
            resolve.setAccessible(true);
            long queries = queryCache.hits() + queryCache.misses();
            var covered = (PredictionRenderer.TileCoverage) resolve.invoke(null, parent, 0, 0, 1400,
                    new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, tiles, Map.of()), null);
            assertEquals(0, covered.rendered());
            assertEquals(queries, queryCache.hits() + queryCache.misses(), "a fully hidden parent must not walk Voxy columns");
            tiles.remove(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0));
            var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, tiles, Map.of());
            var fallback = (PredictionRenderer.TileCoverage) resolve.invoke(null, parent, 0, 0, 1400, snapshot, null);
            assertEquals(1024, fallback.rendered(), "a missing child retains one quadrant of parent geometry");
            var nodes = (dev.xantha.vss.compat.StrictVoxyNodeIndex) field(StrictLodVisibility.class, "NODES").get(null);
            nodes.update(1, dev.xantha.vss.compat.StrictVoxyNodeIndex.key(4, 0, 0, 0), 123);
            var handoff = (PredictionRenderer.TileCoverage) resolve.invoke(null, parent, 0, 0, 1400, snapshot, null);
            assertEquals(1024, handoff.rendered(), "uploaded Voxy must leave fallback available to the pixel-depth test");
            nodes.update(1, -1, -1);
            var retracted = (PredictionRenderer.TileCoverage) resolve.invoke(null, parent, 0, 0, 1400, snapshot, null);
            assertArrayEquals(fallback.allowed(), retracted.allowed(), "a Voxy retraction must immediately restore parent fallback");
        } finally {
            StrictLodVisibility.reset();
            field(dev.xantha.vss.compat.ModCompat.class, "voxyLoaded").setBoolean(null, oldVoxy);
            field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, oldRenderHook);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
