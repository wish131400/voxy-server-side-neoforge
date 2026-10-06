package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.StrictVoxyNodeIndex;
import dev.xantha.vss.compat.StrictVoxyPipeline;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

/** Voxy work and metadata never remove fallback before this frame's depth test. */
class PredictionVoxyStableOwnershipTest {
    private boolean oldVoxy, oldHook;
    private StrictVoxyNodeIndex nodes;
    private StrictVoxyPipeline pipeline;
    private PredictionTileManager.PredictionTile tile;
    private PredictionTileManager.RenderSnapshot snapshot;

    @BeforeAll static void bootstrap() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @BeforeEach void setup() throws Exception {
        oldVoxy = field(ModCompat.class, "voxyLoaded").getBoolean(null);
        oldHook = field(StrictLodVisibility.class, "renderHookSeen").getBoolean(null);
        StrictLodVisibility.reset();
        field(ModCompat.class, "voxyLoaded").setBoolean(null, true);
        field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, true);
        field(StrictLodVisibility.class, "dimension").set(null, Level.OVERWORLD);
        field(StrictLodVisibility.class, "uploadedNodes").setBoolean(null, true);
        pipeline = new StrictVoxyPipeline();
        field(StrictLodVisibility.class, "meshPipeline").set(null, pipeline);
        nodes = (StrictVoxyNodeIndex) field(StrictLodVisibility.class, "NODES").get(null);
        nodes.update(1, StrictVoxyNodeIndex.key(4, 0, 0, 0), 123);
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 512);
        tile = PredictionLodSeamsTest.tile(0, 0, 1, 64);
        snapshot = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,
                VssLodLayout.of(4096, 6, false, false), Map.of(tile.key(), tile), Map.of());
    }

    @AfterEach void cleanup() throws Exception {
        StrictLodVisibility.reset();
        field(ModCompat.class, "voxyLoaded").setBoolean(null, oldVoxy);
        field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, oldHook);
    }

    @Test void queuedDescendantNeverRemovesTheResidentPredictionFallback() throws Exception {
        assertEquals(4096, resolve().rendered());
        long position = StrictVoxyNodeIndex.key(0, 0, 2, 0);
        pipeline.queued(position);
        var first = pipeline.started(position);
        pipeline.queued(position);
        first.uploaded();
        assertFalse(pipeline.idle(0, 4, 0), "the request ordering still tracks the newer mesh edit");
        assertEquals(4096, resolve().rendered(), "both surfaces reach the pixel-depth test while replacement work is pending");
        nodes.update(1, -1, -1);
        assertTrue(resolve().rendered() > 0, "an actual GPU node retraction restores fallback immediately, even with pending work");
    }

    @Test void repeatedIngestAndMeshAttemptsDoNotInvalidateWarmResidentOwnership() throws Exception {
        assertEquals(4096, resolve().rendered());
        long revision = StrictLodVisibility.coverageRevision();
        long position = StrictVoxyNodeIndex.key(0, 0, 2, 0);
        for (int frame = 0; frame < 120; frame++) {
            Object section = new Object();
            StrictLodVisibility.beginIngest(section, 0, 0);
            pipeline.queued(position);
            var work = pipeline.started(position);
            assertEquals(4096, resolve().rendered(), "resident ownership must not oscillate while ingest or meshing is in flight");
            StrictLodVisibility.cancelIngest(section);
            work.uploaded();
            assertEquals(revision, StrictLodVisibility.coverageRevision(), "CPU work state does not change uploaded geometry");
        }
        nodes.update(1, -1, -1);
        assertTrue(StrictLodVisibility.coverageRevision() > revision);
        assertTrue(resolve().rendered() > 0);
    }

    private PredictionRenderer.TileCoverage resolve() throws Exception {
        var method = PredictionRenderer.class.getDeclaredMethod("resolveCoverage",
                PredictionTileManager.PredictionTile.class, int.class, int.class, double.class,
                PredictionTileManager.RenderSnapshot.class, VssLodFocus.class);
        method.setAccessible(true);
        return (PredictionRenderer.TileCoverage) method.invoke(null, tile, 0, 0, 1400, snapshot, null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
