package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class PredictionSurfaceRetryTest {
    @BeforeAll static void bootstrap() { PredictionDecorationQueryTest.bootstrap(); }

    static ClientTerrainSampler terrain(AtomicInteger calls) {
        var feature = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) { return true; }
        };
        var placed = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE)), List.of());
        var generation = new BiomeGenerationSettings.PlainBuilder()
                .addFeature(GenerationStep.Decoration.RAW_GENERATION, Holder.direct(placed)).build();
        var biome = new Biome.BiomeBuilder().hasPrecipitation(true).temperature(.7F).downfall(.5F)
                .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(generation).build();
        var biomes = new FixedBiomeSource(Holder.direct(biome));
        var lookup = VanillaRegistries.createLookup();
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator = new NoiseBasedChunkGenerator(biomes, settings);
        var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), 42);
        return new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) { calls.incrementAndGet(); return PredictionSimpleVegetationTest.sample(100); }
            @Override long colorCacheFingerprint() { return 99; }
            @Override public int surfaceColor(int x, int y, int z) { return 0xff779933; }
            @Override public int foliageColor(int x, int y, int z) { return 0xff449922; }
            @Override Holder<Biome> noiseBiome(int x, int y, int z) { return Holder.direct(biome); }
            @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
            @Override RandomState randomStateContext() { return random; }
            @Override RegistryAccess decorationAccess() { return RegistryAccess.EMPTY; }
        };
    }

    @Test void preflightMatchesFullNegativeCoordinateFootprintAndPrefersCachedSources() throws Exception {
        var calls = new AtomicInteger();
        var terrain = terrain(calls);
        var vegetation = new PredictionVegetation(terrain);
        @SuppressWarnings("unchecked") var generating = (Map<Long, Object>) field(vegetation, "generationJobs");
        var reservation = generationJob();
        @SuppressWarnings("unchecked") var chunks = (Map<Long, Map<BlockPos, net.minecraft.world.level.block.state.BlockState>>) field(vegetation, "chunks");
        int x = -64, z = -128, span = 64;
        for (int cz = -9; cz <= -4; cz++) for (int cx = -5; cx <= 0; cx++) {
            long key = PredictionVegetationDisplayCache.chunkKey(cx, cz);
            generating.put(key, reservation);
            assertThrows(PredictionWorkDeferred.class, () -> vegetation.deferIfRenderingBusy(x, z, span, 1, () -> true));
            chunks.put(key, Map.of());
            assertDoesNotThrow(() -> vegetation.deferIfRenderingBusy(x, z, span, 1, () -> true));
            chunks.remove(key);
            generating.remove(key);
        }
        generating.put(PredictionVegetationDisplayCache.chunkKey(1, -9), reservation);
        generating.put(PredictionVegetationDisplayCache.chunkKey(-5, -3), reservation);
        assertDoesNotThrow(() -> vegetation.deferIfRenderingBusy(x, z, span, 1, () -> true));
        generating.clear();
        generating.put(PredictionVegetationDisplayCache.chunkKey(-5, -9), reservation);
        assertDoesNotThrow(() -> vegetation.deferIfRenderingBusy(x, z, span, 16, () -> true));
        assertThrows(PredictionWorkDeferred.class,
                () -> vegetation.tileForRendering(x, z, span, 1, false, ignored -> true, () -> true),
                "captured sources are still gathered before display filtering");
        assertEquals(0, calls.get(), "read-only preflight must never sample terrain or reserve generation");
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> vegetation.deferIfRenderingBusy(x, z, span, 1, () -> false));
    }

    @Test void busySurfaceRetriesSkipTerrainAndProduceIdenticalFinalMeshAfterOwnerCompletes() throws Exception {
        var original = exercise(true, 5);
        var candidate = exercise(false, 5);
        assertTrue(original.calls > 0, "the previous late deferral must prepare terrain");
        assertEquals(0, candidate.calls, "known shared ownership must defer before terrain");
        assertArrayEquals(original.words, candidate.words);
        assertArrayEquals(original.samples, candidate.samples);
    }

    @Test @EnabledIfEnvironmentVariable(named = "VSS_SURFACE_RETRY_BENCH_OUTPUT", matches = ".+")
    void pairedRetryBenchmarkIncludesAllProductionPreparation() throws Exception {
        List<String> rows = new ArrayList<>(List.of("round,retries,oldNs,newNs,oldBytes,newBytes,oldSampleCalls,newSampleCalls,oldCpuNs,newCpuNs"));
        long[] oldNs = new long[8], newNs = new long[8], oldBytes = new long[8], newBytes = new long[8];
        long[] oldCpu = new long[8], newCpu = new long[8];
        for (int round = -3; round < 8; round++) {
            Result old, next;
            if ((round & 1) == 0) { old = exercise(true, 20); next = exercise(false, 20); }
            else { next = exercise(false, 20); old = exercise(true, 20); }
            assertArrayEquals(old.words, next.words);
            assertArrayEquals(old.samples, next.samples);
            assertEquals(0, next.calls);
            if (round >= 0) {
                oldNs[round] = old.nanos; newNs[round] = next.nanos;
                oldBytes[round] = old.bytes; newBytes[round] = next.bytes;
                oldCpu[round] = old.cpu; newCpu[round] = next.cpu;
                rows.add(round + ",20," + old.nanos + "," + next.nanos + "," + old.bytes + "," + next.bytes + "," + old.calls + "," + next.calls + "," + old.cpu + "," + next.cpu);
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "SURFACE_RETRY retries=20 oldMs=%.3f newMs=%.3f oldCpuMs=%.3f newCpuMs=%.3f oldBytes=%.0f newBytes=%.0f exact=true artificialBackoffRemoved=true wallIncludesQueueAndPolling=true%n",
                median(oldNs) / 1e6, median(newNs) / 1e6, median(oldCpu) / 1e6, median(newCpu) / 1e6, median(oldBytes), median(newBytes));
        var path = Path.of(System.getenv("VSS_SURFACE_RETRY_BENCH_OUTPUT"));
        Files.createDirectories(path);
        Files.write(path.resolve("surface-retry-rounds.csv"), rows);
    }

    @SuppressWarnings("unchecked")
    private static Result exercise(boolean legacy, int attempts) throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean trees = config.predictionTrees, structures = config.predictionStructures, supersample = config.predictionSupersample;
        config.predictionTrees = false; config.predictionStructures = false; config.predictionSupersample = false;
        var samples = new AtomicInteger();
        var terrain = terrain(samples);
        var budget = new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 1);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        bean.setThreadCpuTimeEnabled(true);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, terrain, budget, null)) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            for (String name : List.of("desiredKeys", "terrainLeaves", "surfaceDesired"))
                ((Set<PredictionTileManager.PredictionTileKey>) field(manager, name)).add(key);
            int axis = manager.layout().cellAxis(0), spacing = manager.layout().tileBlocks(0) / axis;
            var previous = new ClientColumnSample[(axis + 1) * (axis + 1)];
            Arrays.fill(previous, PredictionSimpleVegetationTest.sample(100));
            var heights = new int[previous.length]; Arrays.fill(heights, 100);
            ((Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field(manager, "ready"))
                    .put(key, new PredictionTileManager.PredictionTile(key, heights, heights, previous, PredictionMeshCodecTest.fixture(axis),
                            new PredictionDepthBound(100, 100), 0, 1, axis, spacing));
            var vegetation = (PredictionVegetation) field(manager, "vegetation");
            Map<Long, Object> reservations = new java.util.concurrent.ConcurrentHashMap<>();
            if (legacy) {
                var field = PredictionVegetation.class.getDeclaredField("generationJobs"); field.setAccessible(true);
                final Map<Long, Object> legacyReservations = reservations;
                field.set(vegetation, new AbstractMap<Long, Object>() {
                    @Override public boolean isEmpty() { return true; }
                    @Override public int size() { return legacyReservations.size(); }
                    @Override public Set<Map.Entry<Long, Object>> entrySet() { return legacyReservations.entrySet(); }
                    @Override public boolean containsKey(Object key) { return legacyReservations.containsKey(key); }
                    @Override public Object put(Long key, Object value) { return legacyReservations.put(key, value); }
                    @Override public Object remove(Object key) { return legacyReservations.remove(key); }
                });
            } else reservations = (Map<Long, Object>) field(vegetation, "generationJobs");
            final Map<Long, Object> ownership = reservations;
            final Object reservation = generationJob();
            long held = PredictionVegetationDisplayCache.chunkKey(-1, -1);
            var gate = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var owner = new Thread(() -> {
                ownership.put(held, reservation); entered.countDown();
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { ownership.remove(held); }
            }, "surface-retry-test-owner");
            owner.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue",
                    PredictionTileManager.PredictionTileKey.class, int.class, int.class, boolean.class, boolean.class);
            enqueue.setAccessible(true);
            var queue = (java.util.concurrent.ThreadPoolExecutor) field(manager, "executor");
            var worker = queue.submit(() -> Thread.currentThread().getId()).get(5, TimeUnit.SECONDS);
            long allocated = bean.getThreadAllocatedBytes(worker);
            long cpuStart = bean.getThreadCpuTime(worker);
            long start = System.nanoTime();
            try {
                for (int attempt = 0; attempt < attempts; attempt++) {
                    ((Map<?, ?>) field(manager, "deferredUntil")).clear();
                    enqueue.invoke(manager, key, 0, 0, true, false);
                    await(manager);
                    assertEquals(attempt + 1, ((AtomicLong) field(manager, "contentionDeferrals")).get());
                    assertEquals(0, manager.failedTileCount());
                }
            } finally { gate.countDown(); owner.join(5000); }
            long nanos = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(worker) - allocated;
            long cpu = bean.getThreadCpuTime(worker) - cpuStart;
            int calls = samples.get();
            ((Map<?, ?>) field(manager, "deferredUntil")).clear();
            enqueue.invoke(manager, key, 0, 0, true, false);
            await(manager);
            assertTrue(((Set<?>) field(manager, "surfaceReady")).contains(key), manager.surfaceDiagnostics());
            var tile = manager.readyTiles().iterator().next();
            return new Result(nanos, bytes, cpu, calls, tile.samples().clone(), tile.mesh().gpuPayload().restoreWords());
        } finally {
            config.predictionTrees = trees; config.predictionStructures = structures; config.predictionSupersample = supersample;
        }
    }

    private static void await(PredictionTileManager manager) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (manager.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(0, manager.pendingCount(), manager.surfaceDiagnostics());
    }
    static Object generationJob() throws ReflectiveOperationException {
        var type = Class.forName(PredictionVegetation.class.getName() + "$Generation");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); return constructor.newInstance();
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static double median(long[] values) {
        Arrays.sort(values); return (values[values.length / 2 - 1] + values[values.length / 2]) / 2.0;
    }
    record Result(long nanos, long bytes, long cpu, int calls, ClientColumnSample[] samples, int[] words) { }
}
