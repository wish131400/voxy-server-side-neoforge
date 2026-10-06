package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionTerrainColorsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void alignedCoordinatesReuseRawTintsButRejectDifferentHeightPrecisionAndIdentity() {
        var cache = new PredictionTerrainColors();
        var key = key(0);
        var samples = PredictionRefinementOptimizationsTest.samples(10);
        int[] colors = new int[100];
        for (int i = 0; i < colors.length; i++) colors[i] = i;
        cache.put(key, 2, 3, 4, 10, 8, samples, colors, colors, colors);
        var snapshot = cache.get(key, 2, 3, 4);
        assertNotNull(snapshot);
        assertEquals(11, snapshot.match(1, 1, 4, samples[0]));
        assertEquals(99, snapshot.match(17, 17, 4, samples[0]));
        assertEquals(-1, snapshot.match(0, 1, 4, samples[0]));
        assertEquals(-1, snapshot.match(2, 1, 4, samples[0]));
        assertEquals(-1, snapshot.match(19, 1, 4, samples[0]));
        assertEquals(-1, snapshot.match(1, 1, 4, PredictionSimpleVegetationTest.sample(100)));
        var original = samples[0];
        var approximate = new ClientColumnSample(original.surfaceY(), original.fluidY(), original.biomeIndex(),
                original.topBlockIndex(), 0, 0, 0, 0, original.fluid(), original.flags() | ClientColumnSample.FLAG_APPROXIMATE,
                0, original.underBlockIndex(), original.deepBlockIndex(), original.surfaceBottom(), original.lowerTop(),
                original.lowerBottom(), original.spanFloor());
        assertEquals(-1, snapshot.match(1, 1, 4, approximate));
        assertNull(cache.get(key, 3, 3, 4));
        assertNull(cache.get(key, 2, 4, 4));
        assertNull(cache.get(key, 2, 3, 5));
        assertNull(cache.get(key, 2, 3, Long.MIN_VALUE));
        Arrays.fill(colors, -1);
        assertEquals(11, snapshot.surface[11], "producer arrays cannot mutate cached values");
        assertFalse(snapshot.waterMatches(11, original));
        assertFalse(snapshot.waterMatches(-1, original));
    }

    @Test void boundedCacheEvictsAndDoesNotAcceptWorkAfterClose() {
        var cache = new PredictionTerrainColors();
        var samples = PredictionRefinementOptimizationsTest.samples(66);
        var colors = new int[samples.length];
        for (int i = 0; i < 100; i++) cache.put(key(i), 0, 0, 1, 66, 1, samples, colors, colors, colors);
        assertNull(cache.get(key(0), 0, 0, 1));
        assertNotNull(cache.get(key(99), 0, 0, 1));
        assertTrue(cache.diagnostics().startsWith("entries=64,"));
        assertTrue(Long.parseLong(cache.diagnostics().split("bytes=")[1]) <= PredictionTerrainColors.MAX_BYTES);
        cache.close();
        cache.put(key(100), 0, 0, 1, 66, 1, samples, colors, colors, colors);
        assertEquals("entries=0,bytes=0", cache.diagnostics());
    }

    @Test void waterReuseRequiresMatchingSurfacePrecisionFluidHeightAndNoIce() {
        var cache = new PredictionTerrainColors();
        var samples = new ClientColumnSample[100];
        Arrays.fill(samples, wetSample(false, 128));
        var colors = new int[100];
        Arrays.fill(colors, 0xff334455);
        cache.put(key(0), 0, 0, 77, 10, 8, samples, colors, colors, colors);
        var snapshot = cache.get(key(0), 0, 0, 77);
        int index = snapshot.match(1, 1, 4, samples[0]);
        assertTrue(snapshot.waterMatches(index, samples[0]));
        assertFalse(snapshot.waterMatches(index, wetSample(false, 129)));
        assertFalse(snapshot.waterMatches(index, wetSample(true, 128)));
        assertFalse(snapshot.waterMatches(index, PredictionSimpleVegetationTest.sample(120)));
        var approximate = withFlags(samples[0], samples[0].flags() | ClientColumnSample.FLAG_APPROXIMATE);
        assertEquals(-1, snapshot.match(1, 1, 4, approximate));
        Arrays.fill(samples, wetSample(true, 128));
        cache.put(key(0), 0, 0, 77, 10, 8, samples, colors, colors, colors);
        snapshot = cache.get(key(0), 0, 0, 77);
        assertFalse(snapshot.waterMatches(snapshot.match(1, 1, 4, wetSample(false, 128)), wetSample(false, 128)),
                "an ice snapshot never supplies a water tint");
    }

    @Test void productionRefinementQueriesOnlyMissingTintPointsAndPreservesSamplesAndMesh() throws Exception {
        Result baseline = refine(false), candidate = refine(true);
        assertArrayEquals(baseline.samples, candidate.samples);
        assertArrayEquals(baseline.heights, candidate.heights);
        assertEquals(baseline.vertices, candidate.vertices);
        assertEquals(baseline.waterVertices, candidate.waterVertices);
        assertArrayEquals(baseline.words, candidate.words, "complete rendered material/geometry records must match");
        assertTrue(candidate.calls < baseline.calls, baseline.calls + " -> " + candidate.calls);
        System.out.printf("TINT_GRID_CALLS baseline=%d candidate=%d reductionPct=%.2f%n", baseline.calls,
                candidate.calls, 100.0 * (baseline.calls - candidate.calls) / baseline.calls);
    }

    @Test void mixedWaterIceRefinementPreservesEveryStageAndQueriesFewerWaterTints() throws Exception {
        var baseline = refine(false, Scenario.WATER_ICE);
        var candidate = refine(true, Scenario.WATER_ICE);
        assertSameOutput(baseline, candidate);
        assertTrue(candidate.waterCalls > 0 && candidate.waterCalls < baseline.waterCalls);
        assertTrue(candidate.waterVertices > 0);
    }

    @Test void forestTintChangesAreRecomputedAndCapturedMaterialSurvivesRefinement() throws Exception {
        var baseline = refine(false, Scenario.FOREST_CAPTURE);
        var candidate = refine(true, Scenario.FOREST_CAPTURE);
        assertSameOutput(baseline, candidate);
        assertEquals(capturedSample(), candidate.samples[0]);
        assertEquals(137, candidate.heights[0]);
        assertTrue(candidate.calls < baseline.calls);
    }

    @Test void exactPredictedColumnArrivingDuringPreviewIsStillPreferred() throws Exception {
        var result = refine(true, Scenario.CONCURRENT_EXACT);
        assertEquals(withFlags(capturedSample(), ClientColumnSample.FLAG_SURFACE_ONLY), result.samples[0]);
        assertEquals(137, result.heights[0]);
    }

    private static void assertSameOutput(Result baseline, Result candidate) {
        assertArrayEquals(baseline.samples, candidate.samples);
        assertArrayEquals(baseline.heights, candidate.heights);
        assertEquals(baseline.vertices, candidate.vertices);
        assertEquals(baseline.waterVertices, candidate.waterVertices);
        assertArrayEquals(baseline.words, candidate.words);
        assertEquals(baseline.stages.size(), candidate.stages.size());
        for (int i = 0; i < baseline.stages.size(); i++)
            assertArrayEquals(baseline.stages.get(i), candidate.stages.get(i), "refinement stage " + i);
    }

    static Result refine(boolean reuse) throws Exception {
        return refine(reuse, Scenario.DRY);
    }

    private enum Scenario { DRY, WATER_ICE, FOREST_CAPTURE, CONCURRENT_EXACT }

    private static Result refine(boolean reuse, Scenario scenario) throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean oldTrees = config.predictionTrees, oldStructures = config.predictionStructures,
                oldSupersample = config.predictionSupersample;
        config.predictionTrees = scenario == Scenario.FOREST_CAPTURE;
        config.predictionStructures = false; config.predictionSupersample = false;
        var count = new AtomicInteger();
        var waterCount = new AtomicInteger();
        var sharedSamples = new java.util.concurrent.atomic.AtomicReference<VssLodSampleCache>();
        var source = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE) {
            @Override long colorCacheFingerprint() { return 77; }
            @Override int initialTerrainCellAxis(int lod) { return 8; }
            @Override public ClientColumnSample sample(int x, int z) {
                int kind = Math.floorMod(Math.floorDiv(x, 64) + Math.floorDiv(z, 64), 3);
                return scenario == Scenario.WATER_ICE && kind != 0 ? wetSample(kind == 2, 128)
                        : PredictionSimpleVegetationTest.sample(120);
            }
            @Override public ClientColumnSample samplePreview(int x, int z, int step) {
                if (scenario == Scenario.CONCURRENT_EXACT && x < 0 && z < 0)
                    sharedSamples.get().put(0L, withFlags(capturedSample(), ClientColumnSample.FLAG_SURFACE_ONLY));
                return sample(x, z);
            }
            @Override int surfaceColorForLod(int x, int y, int z, boolean preview) { count.incrementAndGet(); return color(x, z); }
            @Override int foliageColorForLod(int x, int y, int z, boolean preview) { count.incrementAndGet(); return color(z + 17, x); }
            @Override int waterTintForLod(int x, int y, int z, boolean preview) { waterCount.incrementAndGet(); return color(x, z); }
        };
        var budget = new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, source, budget, null)) {
            sharedSamples.set((VssLodSampleCache) field(manager, "sampleCache"));
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, manager.layout().levelCount() - 1);
            @SuppressWarnings("unchecked") var desired = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "desiredKeys");
            desired.add(key);
            @SuppressWarnings("unchecked") var leaves = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "terrainLeaves");
            leaves.add(key);
            if (scenario == Scenario.FOREST_CAPTURE) ((VssLodSampleCache) field(manager, "sampleCache")).put(0L, capturedSample());
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue",
                    PredictionTileManager.PredictionTileKey.class, int.class, int.class, boolean.class, boolean.class);
            enqueue.setAccessible(true);
            int finalAxis = manager.layout().cellAxis(key.lod()), actualAxis = 0;
            var stages = new ArrayList<int[]>();
            for (int attempt = 0; attempt < 8 && actualAxis < finalAxis; attempt++) {
                if (!reuse) ((PredictionTerrainColors) field(manager, "terrainColors")).remove(key);
                if (scenario == Scenario.FOREST_CAPTURE) {
                    @SuppressWarnings("unchecked") var coverage = (Map<Long, Float>) field(field(manager, "vegetation"), "forestCoverage");
                    coverage.clear();
                    int axis = actualAxis == 0 ? 8 : Math.min(finalAxis, actualAxis * 2);
                    int step = manager.layout().tileBlocks(key.lod()) / axis;
                    for (int z = -1; z <= axis; z++) for (int x = -1; x <= axis; x++) {
                        long chunk = (long) Math.floorDiv(x * step, 16) << 32 | Math.floorDiv(z * step, 16) & 0xffffffffL;
                        coverage.put(chunk, (attempt & 1) == 0 ? 1F : .25F);
                    }
                }
                enqueue.invoke(manager, key, 0, 0, false, false);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (manager.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(2);
                assertEquals(0, manager.pendingCount(), manager.surfaceDiagnostics());
                assertEquals(1, manager.readyCount(), manager.surfaceDiagnostics());
                var tile = manager.readyTiles().iterator().next();
                assertTrue(tile.cellAxis() > actualAxis, manager.surfaceDiagnostics());
                actualAxis = tile.cellAxis();
                stages.add(tile.mesh().gpuPayload().restoreWords());
            }
            assertEquals(finalAxis, actualAxis);
            var tile = manager.readyTiles().iterator().next();
            return new Result(count.get(), waterCount.get(), tile.samples().clone(), tile.heights().clone(), tile.mesh().vertexCount(),
                    tile.mesh().waterVertexCount(), tile.mesh().gpuPayload().restoreWords(), stages);
        } finally {
            config.predictionTrees = oldTrees; config.predictionStructures = oldStructures;
            config.predictionSupersample = oldSupersample;
        }
    }

    private static ClientColumnSample wetSample(boolean ice, int fluidY) {
        var s = PredictionSimpleVegetationTest.sample(120);
        return new ClientColumnSample(s.surfaceY(), fluidY, s.biomeIndex(), s.topBlockIndex(), 0, 0, 0, 0, 1,
                s.flags() | (ice ? ClientColumnSample.FLAG_ICE : 0), 0, s.underBlockIndex(), s.deepBlockIndex(),
                s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
    }

    private static ClientColumnSample withFlags(ClientColumnSample s, int flags) {
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(), 0, 0, 0, 0,
                s.fluid(), flags, 0, s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
    }

    private static ClientColumnSample capturedSample() {
        var s = PredictionSimpleVegetationTest.sample(137);
        return new ClientColumnSample(137, 137, 0, PredictionMaterialPalette.stoneIndex(), 0, 0, 0, 0, 0,
                s.flags() | ClientColumnSample.FLAG_CAPTURED, 0, s.underBlockIndex(), s.deepBlockIndex(),
                s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
    }

    private static int color(int x, int z) { return 0xff000000 | ((x * 991 + z * 313) & 0xffffff); }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static PredictionTileManager.PredictionTileKey key(int x) {
        return new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, x, 0, 0);
    }
    record Result(int calls, int waterCalls, ClientColumnSample[] samples, int[] heights, int vertices,
                  int waterVertices, int[] words, List<int[]> stages) { }
}
