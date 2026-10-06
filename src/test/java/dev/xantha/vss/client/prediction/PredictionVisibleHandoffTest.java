package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.StrictVoxyNodeIndex;
import dev.xantha.vss.compat.StrictVoxyPipeline;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

/** Uploaded/empty/culled Voxy nodes are not evidence of a drawn surface. */
class PredictionVisibleHandoffTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void uploadedAncestorCannotRemoveFallbackBeforeThePixelDepthTest() throws Exception {
        boolean hook = field(StrictLodVisibility.class, "renderHookSeen").getBoolean(null);
        StrictLodVisibility.reset();
        try {
            field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, true);
            field(StrictLodVisibility.class, "dimension").set(null, Level.OVERWORLD);
            field(StrictLodVisibility.class, "uploadedNodes").setBoolean(null, true);
            field(StrictLodVisibility.class, "meshPipeline").set(null, new StrictVoxyPipeline());
            StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 512);
            var nodes = (StrictVoxyNodeIndex) field(StrictLodVisibility.class, "NODES").get(null);
            var tile = PredictionLodSeamsTest.tile(0, 0, 1, 64);
            var snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,
                    VssLodLayout.of(4096, 6, false, false), Map.of(tile.key(), tile), Map.of());
            var resolve = PredictionRenderer.class.getDeclaredMethod("resolveCoverage",
                    PredictionTileManager.PredictionTile.class, int.class, int.class, double.class,
                    PredictionTileManager.RenderSnapshot.class, VssLodFocus.class);
            resolve.setAccessible(true);
            // Voxy retains ancestors while GPU traversal chooses children. An
            // explicitly empty node also counts as ready for request ordering.
            for (int geometry : new int[]{123, 0xFFFFFE, -1, 456}) {
                nodes.update(1, StrictVoxyNodeIndex.key(4, 0, 0, 0), geometry);
                var coverage = (PredictionRenderer.TileCoverage) resolve.invoke(null, tile, 0, 0, 1400, snapshot, null);
                assertEquals(4096, coverage.rendered(), "fallback must reach the frame-depth test; geometry=" + geometry);
                assertEquals(0, coverage.authoritativeSkipped());
            }
        } finally {
            StrictLodVisibility.reset();
            field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, hook);
        }
    }

    @Test void voxyResetDoesNotInvalidatePredictionMeshOwnership() {
        var view = new PredictionRenderer.CoverageView(0, 0, 1400, null).ownership(false);
        var cached = new PredictionRenderer.CachedCoverage(1, 2, 0, view, null);
        StrictLodVisibility.reset();
        assertTrue(cached.matchesOwnership(1, 2, view), "Voxy visibility belongs to this frame's depth, not the CPU tile mask");
        assertFalse(cached.matchesOwnership(2, 2, view), "a replaced prediction mesh still invalidates its own mask");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
