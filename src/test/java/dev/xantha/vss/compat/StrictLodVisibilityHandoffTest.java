package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import java.lang.reflect.Field;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class StrictLodVisibilityHandoffTest {
    private boolean oldPrediction, oldServer, oldVoxy, oldRenderHook;
    private int oldCapabilities;
    private StrictVoxyNodeIndex nodes;
    private StrictVoxyPipeline pipeline;

    @BeforeAll static void bootstrap() throws Exception {
        var method = Class.forName("dev.xantha.vss.client.prediction.ClientTerrainSamplerTest")
                .getDeclaredMethod("bootstrapMinecraft");
        method.setAccessible(true);
        method.invoke(null);
    }

    @BeforeEach void setup() throws Exception {
        oldPrediction = VSSClientConfig.CONFIG.enablePrediction;
        oldServer = field(VSSClientNetworking.class, "serverEnabled").getBoolean(null);
        oldCapabilities = field(VSSClientNetworking.class, "serverCapabilities").getInt(null);
        oldVoxy = field(ModCompat.class, "voxyLoaded").getBoolean(null);
        oldRenderHook = field(StrictLodVisibility.class, "renderHookSeen").getBoolean(null);
        StrictLodVisibility.reset();
        VSSClientConfig.CONFIG.enablePrediction = true;
        field(VSSClientNetworking.class, "serverEnabled").setBoolean(null, true);
        field(VSSClientNetworking.class, "serverCapabilities").setInt(null,
                dev.xantha.vss.common.VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN);
        field(ModCompat.class, "voxyLoaded").setBoolean(null, true);
        field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, true);
        field(StrictLodVisibility.class, "dimension").set(null, Level.OVERWORLD);
        field(StrictLodVisibility.class, "uploadedNodes").setBoolean(null, true);
        nodes = (StrictVoxyNodeIndex) field(StrictLodVisibility.class, "NODES").get(null);
        pipeline = new StrictVoxyPipeline();
        field(StrictLodVisibility.class, "meshPipeline").set(null, pipeline);
        nodes.update(1, StrictVoxyNodeIndex.key(0, 10, 2, 0), 123);
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 64);
    }

    @AfterEach void cleanup() throws Exception {
        field(VSSClientNetworking.class, "serverEnabled").setBoolean(null, oldServer);
        field(VSSClientNetworking.class, "serverCapabilities").setInt(null, oldCapabilities);
        field(ModCompat.class, "voxyLoaded").setBoolean(null, oldVoxy);
        field(StrictLodVisibility.class, "renderHookSeen").setBoolean(null, oldRenderHook);
        VSSClientConfig.CONFIG.enablePrediction = oldPrediction;
        StrictLodVisibility.reset();
    }

    @Test void residentCoverageCanHandoffWithoutACompletedNearRing() {
        assertEquals(-1, StrictLodVisibility.snapshot().visibleRing());
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        assertFalse(StrictLodVisibility.predictionCoverage(Level.NETHER, 20, 64, 95, 0));
    }

    @Test void residentNodesOutsideCurrentVoxyViewKeepPrediction() {
        nodes.update(2, StrictVoxyNodeIndex.key(0, 64, 2, 0), 456);
        assertTrue(nodes.coversRange(128, 4, 5, 0));
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 128, 64, 95, 0));
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 128 * 16, 0, 64);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 128, 64, 95, 0));
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
    }

    @Test void distanceBoundaryKeepsPredictionForPixelHandoff() {
        nodes.update(2, StrictVoxyNodeIndex.key(0, 30, 2, 0), 456);
        assertTrue(nodes.coversRange(61, 4, 5, 0));
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 61, 64, 95, 0));
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 128);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 61, 64, 95, 0));
    }

    @Test void missingVerticalSectionKeepsPredictionWhilePendingWorkRetainsUploadedCoverage() {
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 96, 0));
        Object section = new Object();
        StrictLodVisibility.beginIngest(section, 20, 0);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        StrictLodVisibility.cancelIngest(section);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        long key = StrictVoxyNodeIndex.key(0, 10, 2, 0);
        pipeline.queued(key);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        pipeline.started(key).uploaded();
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
    }

    @Test void disabledOrUnknownVoxyDistanceCannotRetractPrediction() {
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 0);
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        StrictLodVisibility.updateRenderWindow(null, 0, 0, 64);
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        StrictLodVisibility.updateRenderWindow(Level.NETHER, 0, 0, 64);
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
    }

    @Test void disabledVoxyMovementDoesNotInvalidatePredictionOwnership() {
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 0);
        long revision = StrictLodVisibility.coverageRevision();
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 128, -256, 0);
        assertEquals(revision, StrictLodVisibility.coverageRevision());
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
    }

    @Test void auxiliaryViewportsKeepMainCoverageAndItsCachedAnswers() throws Exception {
        var cache = (StrictVoxyCoverageCache) field(StrictLodVisibility.class, "COLUMN_COVERAGE").get(null);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        long revision = StrictLodVisibility.coverageRevision();
        long reset = StrictLodVisibility.coverageResetRevision();
        long misses = cache.misses();
        Object[] auxiliaryViews = {null, new Viewport(0, 1080), new Viewport(1920, 0),
                new Viewport(-1, 1080), new Viewport(1920, -1)};
        for (int frame = 0; frame < 64; frame++) {
            for (Object viewport : auxiliaryViews) StrictLodVisibility.beginFrame(null, viewport);
            StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 64);
            assertEquals(revision, StrictLodVisibility.coverageRevision());
            assertEquals(reset, StrictLodVisibility.coverageResetRevision());
            assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        }
        assertEquals(misses, cache.misses(), "shadow calls must retain the main view's warm coverage");
        nodes.update(1, -1, -1);
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0),
                "a real node removal still retracts coverage immediately");
    }

    @Test void auxiliaryViewportsCannotConsumePendingOrderingRestart() throws Exception {
        StrictLodVisibility.restartOrdering(Level.OVERWORLD, 0, 0);
        var pending = (java.util.concurrent.atomic.AtomicBoolean)
                field(StrictLodVisibility.class, "invalidated").get(null);
        assertTrue(pending.get());
        StrictLodVisibility.beginFrame(null, new Viewport(0, 0));
        assertTrue(pending.get(), "the next main view must process the requested restart");
    }

    @Test void worldResetRetractsCachedCoverageAndFreshUploadsCanRestoreIt() throws Exception {
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        long reset = StrictLodVisibility.coverageResetRevision();
        StrictLodVisibility.reset();
        assertTrue(StrictLodVisibility.coverageResetRevision() > reset);
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        field(StrictLodVisibility.class, "dimension").set(null, Level.OVERWORLD);
        field(StrictLodVisibility.class, "uploadedNodes").setBoolean(null, true);
        field(StrictLodVisibility.class, "meshPipeline").set(null, pipeline);
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 0, 0, 64);
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0),
                "old positive cache entries cannot survive a new world");
        nodes.update(1, StrictVoxyNodeIndex.key(0, 10, 2, 0), 123);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
    }

    private static final class Viewport {
        final int width, height;
        Viewport(int width, int height) { this.width = width; this.height = height; }
    }

    @Test void predictionToggleRetainsResidentVoxyNodes() {
        VSSClientConfig.CONFIG.enablePrediction = false;
        assertFalse(StrictLodVisibility.active());
        long revision = StrictLodVisibility.revision();
        StrictLodVisibility.restartOrdering(Level.OVERWORLD, 20, 0);
        assertTrue(StrictLodVisibility.revision() > revision);
        assertTrue(nodes.covers(20, 4, 0));
        VSSClientConfig.CONFIG.enablePrediction = true;
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
    }

    @Test void requestAndMeshBookkeepingDoNotInvalidateResidentOwnership() {
        long revision = StrictLodVisibility.coverageRevision();
        for (int change = 0; change < 10_000; change++) StrictLodVisibility.workChanged();
        assertEquals(revision, StrictLodVisibility.coverageRevision());
        new StrictVoxyPipeline().queued(StrictVoxyNodeIndex.key(0, 10, 2, 0));
        assertEquals(revision, StrictLodVisibility.coverageRevision());
        pipeline.queued(StrictVoxyNodeIndex.key(0, 10, 2, 0));
        assertEquals(revision, StrictLodVisibility.coverageRevision());
    }

    @Test void cachedCoverageFollowsGpuRetractionWhileRetainingIngestAndMeshReplacements() {
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        long before = StrictLodVisibility.coverageRevision();
        nodes.update(1, -1, -1);
        assertTrue(StrictLodVisibility.coverageRevision() > before);
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        long key = StrictVoxyNodeIndex.key(0, 10, 2, 0);
        nodes.update(1, key, 123);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        pipeline.queued(key);
        var old = pipeline.started(key);
        pipeline.queued(key);
        old.uploaded();
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        pipeline.started(key).uploaded();
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        Object section = new Object();
        StrictLodVisibility.beginIngest(section, 20, 0);
        StrictLodVisibility.beginIngest(section, 20, 0);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        StrictLodVisibility.cancelIngest(section);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        StrictLodVisibility.cancelIngest(section);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
    }

    @Test void wholeCellCoverageMatchesColumnsAndRetainsPendingDescendantsUnderAReadyAncestor() {
        nodes.update(2, StrictVoxyNodeIndex.key(4, 0, 0, 0), 123);
        for (int z = -2; z < 6; z++) for (int x = 16; x < 28; x++) {
            boolean expected = true;
            for (int cz = z; cz <= z + 3; cz++) for (int cx = x; cx <= x + 3; cx++)
                expected &= StrictLodVisibility.predictionCoverage(Level.OVERWORLD, cx, 0, 95, cz);
            assertEquals(expected, StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, x, 0, z, x + 3, 95, z + 3));
        }
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        long key = StrictVoxyNodeIndex.key(0, 9, 1, 1);
        pipeline.queued(key); var old = pipeline.started(key); pipeline.queued(key); old.uploaded();
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        pipeline.started(key).uploaded();
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        var section = new Object(); StrictLodVisibility.beginIngest(section, 17, 3); StrictLodVisibility.beginIngest(section, 17, 3);
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        StrictLodVisibility.cancelIngest(section);
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        StrictLodVisibility.cancelIngest(section);
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        nodes.update(2, -1, -1);
        assertFalse(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
    }

    @Test void boxWindowUsesItsFarthestCornerAndKeepsInteriorProofsWarmAfterMovement() throws Exception {
        nodes.update(2, StrictVoxyNodeIndex.key(4, 0, 0, 0), 123);
        nodes.update(3, StrictVoxyNodeIndex.key(4, 1, 0, 0), 123);
        var cache = (StrictVoxyCoverageRegions) field(StrictLodVisibility.class, "REGION_COVERAGE").get(null);
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        long misses = cache.misses();
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 16, 0, 64);
        assertTrue(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 16, 0, 0, 23, 95, 7));
        assertEquals(misses, cache.misses());
        assertFalse(StrictLodVisibility.predictionCoverageBox(Level.OVERWORLD, 56, 0, 0, 63, 95, 7), "one corner outside the circle retains the whole cell");
        assertFalse(StrictLodVisibility.predictionCoverageBox(Level.NETHER, 16, 0, 0, 23, 95, 7));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    @Test void crossingAChunkPreservesInteriorAnswersAndUpdatesEnteringAndLeavingCoverage() throws Exception {
        var cache = (StrictVoxyCoverageCache) field(StrictLodVisibility.class, "COLUMN_COVERAGE").get(null);
        nodes.update(2, StrictVoxyNodeIndex.key(0, 30, 2, 0), 456);
        nodes.update(3, StrictVoxyNodeIndex.key(0, -30, 2, 0), 789);
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 61, 64, 95, 0));
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, -60, 64, 95, 0));
        long revision = StrictLodVisibility.coverageRevision(), reset = StrictLodVisibility.coverageResetRevision();
        long misses = cache.misses();
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 16, 0, 64);
        assertEquals(reset, StrictLodVisibility.coverageResetRevision());
        assertFalse(StrictLodVisibility.coverageChangesSince(revision).reset());
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 20, 64, 95, 0));
        assertEquals(misses, cache.misses(), "interior uploaded answers survive a camera chunk crossing");
        assertTrue(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, 61, 64, 95, 0));
        assertFalse(StrictLodVisibility.predictionCoverage(Level.OVERWORLD, -60, 64, 95, 0));
        StrictLodVisibility.updateRenderWindow(Level.OVERWORLD, 16, 0, 128);
        assertTrue(StrictLodVisibility.coverageResetRevision() > reset, "distance changes remain a full reset");
    }
}
