package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionDecorationControlTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    private VSSClientConfig saved;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void setup() { saved = VSSClientConfig.CONFIG; VSSClientConfig.CONFIG = new VSSClientConfig(); }
    @AfterEach void cleanup() throws Exception { VSSClientConfig.CONFIG = saved; PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void distanceDefaultsFollowTerrainAndCustomRadiusCannotExceedIt() throws Exception {
        var config = VSSClientConfig.CONFIG;
        assertTrue(config.predictionDecorations);
        assertEquals(0, config.predictionDecorationDistanceChunks);
        config.predictionDistanceBlocks = 4096;
        assertEquals(4096, PredictionDecorationRange.at(0, 0).radius());
        config.predictionDecorationDistanceChunks = 16;
        assertEquals(256, PredictionDecorationRange.at(0, 0).radius());
        config.predictionDecorationDistanceChunks = 1024;
        assertEquals(4096, PredictionDecorationRange.at(0, 0).radius());
        config.predictionDecorationDistanceChunks = -4;
        invoke(config, "validate", new Class[0]);
        assertEquals(0, config.predictionDecorationDistanceChunks);
    }

    @Test void circleAndNegativeChunkCentersHaveStableBoundaries() {
        var range = new PredictionDecorationRange(true, 64, 0, 0);
        assertTrue(range.allows(-64, -16, 16));
        assertFalse(range.allows(64, 64, 16));
        assertTrue(range.contains(-16, -16, 32));
        assertFalse(range.contains(48, 48, 16));
        var negative = PredictionDecorationRange.at(-0.1, -16.1);
        assertEquals(-8, negative.centerX()); assertEquals(-24, negative.centerZ());
        assertEquals(negative, PredictionDecorationRange.at(-15.9, -31.9));
    }

    @Test void placementScopeSkipsOutsideChunksAndRestoresPreviousScope() {
        assertTrue(PredictionDecorationRange.placementChunk(100, 100));
        try (var local = new PredictionDecorationRange(true, 32, 0, 0).enter()) {
            assertTrue(PredictionDecorationRange.placementChunk(0, 0));
            assertFalse(PredictionDecorationRange.placementChunk(10, 0));
            assertFalse(PredictionDecorationRange.placementPoint(160, 0));
            try (var disabled = new PredictionDecorationRange(false, 32, 0, 0).enter()) {
                assertFalse(PredictionDecorationRange.placementChunk(0, 0));
                assertFalse(PredictionDecorationRange.placementPoint(0, 0));
            }
            assertTrue(PredictionDecorationRange.placementChunk(0, 0));
        }
        assertTrue(PredictionDecorationRange.placementChunk(100, 100));
    }

    @Test void displayFiltersCitiesTemplatesAndExactPlantsWithoutChangingRawTerrain() {
        var raw = decoratedTile(new PredictionTileKey(Level.OVERWORLD, 0, 0, 0)).data().voxyTile;
        var near = new PredictionDecorationRange(true, 20, 0, 0).display(raw);
        assertSame(raw.columns(), near.columns());
        assertEquals(2, near.features().size()); assertEquals(1, near.cityFeatures());
        assertEquals(1, near.templateFeatures());
        assertTrue(near.template(1));
        assertEquals(3, raw.features().size(), "display projection must retain the warm source");
        var off = new PredictionDecorationRange(false, 1000, 0, 0).display(raw);
        assertSame(raw.columns(), off.columns()); assertTrue(off.features().isEmpty());
        assertTrue(off.surfaceComplete(), "an empty outside tile must also mask coarser vegetation");
        assertEquals(0, off.cityFeatures()); assertEquals(0, off.templateFeatures());
        assertSame(raw, new PredictionDecorationRange(true, 1000, 0, 0).display(raw));
    }

    @Test @SuppressWarnings("unchecked")
    void disabledManagerDoesNotPrepareDecorationContextOrAdmitSurfaceCacheWork() throws Exception {
        VSSClientConfig.CONFIG.predictionDecorations = false;
        var decorationContexts = new AtomicInteger();
        var sampler = new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override ClientTerrainSampler decorationContext() { decorationContexts.incrementAndGet(); return this; }
        };
        try (var manager = manager(sampler)) {
            assertNull(get(manager, "vegetation"));
            var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            ((Map<PredictionTileKey, PredictionTile>)get(manager, "ready")).put(key, decoratedTile(key));
            ((Set<PredictionTileKey>)get(manager, "desiredKeys")).add(key);
            set(manager, "surfaceDesired", Set.of(key));
            assertFalse((boolean)invoke(manager, "surfaceBuildReady", new Class[]{PredictionTileKey.class}, key));
            assertEquals("DEFERRED", invoke(manager, "enqueue", new Class[]{PredictionTileKey.class, int.class, int.class,
                    boolean.class, boolean.class}, key, 0, 0, true, true).toString());
            assertEquals(0, manager.pendingCount());
            assertEquals(0, decorationContexts.get(), "disabling before world entry must not initialize placement");
        }
    }

    @Test @SuppressWarnings("unchecked")
    void rangeIsAnAdmissionAndRunningLimitEvenForCacheAndSpyglassRequests() throws Exception {
        VSSClientConfig.CONFIG.predictionDecorationDistanceChunks = 4;
        try (var manager = manager(new ClientTerrainSampler(PROFILE.seed(), PROFILE))) {
            var near = new PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            var far = new PredictionTileKey(Level.OVERWORLD, 10, 0, 0);
            var ready = (Map<PredictionTileKey, PredictionTile>)get(manager, "ready");
            ready.put(near, decoratedTile(near)); ready.put(far, decoratedTile(far));
            set(manager, "surfaceDesired", Set.of(near, far));
            set(manager, "buildFocus", new VssLodFocus(640, 0, 64, 1024));
            assertTrue((boolean)invoke(manager, "surfaceBuildReady", new Class[]{PredictionTileKey.class}, near));
            assertFalse((boolean)invoke(manager, "surfaceBuildReady", new Class[]{PredictionTileKey.class}, far));
            VSSClientConfig.CONFIG.predictionDecorations = false;
            for (boolean cache : List.of(false, true))
                assertFalse((boolean)invoke(manager, "buildDemandCurrent", new Class[]{PredictionTileKey.class,
                        int.class, boolean.class, boolean.class}, near, 64, true, cache));
            assertTrue((boolean)invoke(manager, "buildDemandCurrent", new Class[]{PredictionTileKey.class,
                    int.class, boolean.class, boolean.class}, near, 64, false, true), "terrain cache reads continue");
        }
    }

    @Test @SuppressWarnings("unchecked")
    void togglingWhileLoadingPausedHidesAndRestoresResidentFeaturesWithoutRebuildingTerrain() throws Exception {
        try (var manager = manager(new ClientTerrainSampler(PROFILE.seed(), PROFILE))) {
            var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            var raw = decoratedTile(key);
            ((Map<PredictionTileKey, PredictionTile>)get(manager, "ready")).put(key, raw);
            var source = (PredictionVoxySource)get(manager, "voxySource");
            source.put(raw.data().voxyTile);
            var columns = raw.data().voxyTile.columns();
            manager.setPaused(true);
            long revision = ((java.util.concurrent.atomic.AtomicLong)get(manager, "meshRevision")).get();
            for (boolean enabled : List.of(false, true, false, true)) {
                VSSClientConfig.CONFIG.predictionDecorations = enabled;
                manager.tick(0, 80, 0, 1000, null, 256);
                awaitFeatures(source, key, enabled ? 3 : 0);
                assertSame(raw, manager.renderSnapshot().tiles().get(key));
                assertSame(columns, raw.data().voxyTile.columns());
                assertEquals(3, raw.data().voxyTile.features().size());
                assertEquals(revision, ((java.util.concurrent.atomic.AtomicLong)get(manager, "meshRevision")).get());
                assertEquals(0, manager.pendingCount()); assertNull(get(manager, "vegetation"));
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    void shrinkingKeepsFullPlacementReadyAndExpandingReadmitsOnlyPartialCoverage() throws Exception {
        try (var manager = manager(new ClientTerrainSampler(PROFILE.seed(), PROFILE))) {
            var full = new PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            var partial = new PredictionTileKey(Level.OVERWORLD, 1, 0, 0);
            var ready = (Map<PredictionTileKey, PredictionTile>)get(manager, "ready");
            ready.put(full, decoratedTile(full)); ready.put(partial, decoratedTile(partial));
            var completed = (Set<PredictionTileKey>)get(manager, "surfaceReady");
            completed.addAll(Set.of(full, partial));
            VSSClientConfig.CONFIG.predictionDecorationDistanceChunks = 2;
            invoke(manager, "refreshDecorations", new Class[]{double.class, double.class}, 0D, 0D);
            assertTrue(completed.contains(full), "shrinking only hides complete warm placement");
            var ranges = (Map<PredictionTileKey, PredictionDecorationRange>)get(manager, "partialSurfaceRanges");
            ranges.put(partial, new PredictionDecorationRange(true, 80, 8, 8));
            VSSClientConfig.CONFIG.predictionDecorationDistanceChunks = 16;
            invoke(manager, "refreshDecorations", new Class[]{double.class, double.class}, 0D, 0D);
            assertTrue(completed.contains(full));
            assertFalse(completed.contains(partial), "newly uncovered boundary demand must be restored or generated");
        }
    }

    private static PredictionTile decoratedTile(PredictionTileKey key) {
        var column = new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64, 64,
                Blocks.STONE.defaultBlockState())), "minecraft:plains");
        var features = List.of(box(0, Blocks.STONE), box(8, Blocks.OAK_LEAVES), box(48, Blocks.OAK_LOG));
        var voxels = new PredictionVoxySource.Tile(key, key.tileX() * 64, key.tileZ() * 64, 64, 1, 64,
                new PredictionVoxySource.Column[]{column}, features, 512, true, 1, null, true, 64, 1);
        var data = PredictionVoxelData.forVoxy(voxels);
        return new PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0], data,
                new PredictionDepthBound(-64, 80), 0, 1, 1, 64);
    }
    private static PredictionVoxySource.Box box(int x, net.minecraft.world.level.block.Block block) {
        return new PredictionVoxySource.Box(x, 64, 0, x + 4, 80, 4, block.defaultBlockState());
    }
    private static PredictionTileManager manager(ClientTerrainSampler sampler) {
        return new PredictionTileManager(PROFILE.levelKey(), sampler,
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 1),
                null, PredictionCpuBudget.fixed(1));
    }
    private static Object get(Object owner, String field) throws Exception { return PredictionPlanningTestSupport.get(owner, field); }
    private static void set(Object owner, String field, Object value) throws Exception { PredictionPlanningTestSupport.set(owner, field, value); }
    private static Object invoke(Object owner, String name, Class<?>[] signature, Object... args) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, signature); method.setAccessible(true);
        synchronized (owner) { return method.invoke(owner, args); }
    }
    @SuppressWarnings("unchecked") private static void awaitFeatures(PredictionVoxySource source, PredictionTileKey key, int expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (;;) {
            synchronized (source) {
                var tiles = (Map<PredictionTileKey, PredictionVoxySource.Tile>)get(source, "tiles");
                if (tiles.get(key) != null && tiles.get(key).features().size() == expected) return;
            }
            if (System.nanoTime() >= until) fail("decoration display did not reach " + expected + " features");
            Thread.sleep(5);
        }
    }
}
