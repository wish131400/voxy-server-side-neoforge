package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
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

class PredictionVegetationLocalInvalidationTest {
    @BeforeAll static void bootstrap() { PredictionDecorationQueryTest.bootstrap(); }

    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        void block() {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test gate timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
        }
        void await() throws InterruptedException { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
    }

    /** Real feature replay over a deterministic terrain fixture, with explicit publication gates. */
    static final class Fixture {
        final AtomicInteger samples = new AtomicInteger(), features = new AtomicInteger();
        final ClientTerrainSampler terrain;
        volatile Gate featureGate, sampleGate;
        volatile Runnable beforeFinish;
        volatile java.util.function.Consumer<PredictionDecorationLevel> beforeWrite;
        volatile boolean failSamples;
        volatile int blockedX, blockedZ;

        Fixture() {
            var feature = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
                @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                    var origin = context.origin();
                    int height = 0;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                        height = context.level().getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                                origin.getX() + x, origin.getZ() + z);
                    features.incrementAndGet();
                    var edit = beforeFinish;
                    if (edit != null) edit.run();
                    var gate = featureGate;
                    if (gate != null) gate.block();
                    var write = beforeWrite;
                    if (write != null) write.accept((PredictionDecorationLevel) context.level());
                    return context.level().setBlock(new BlockPos(origin.getX(), height, origin.getZ()),
                            Blocks.OAK_LOG.defaultBlockState(), 2);
                }
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
            terrain = new ClientTerrainSampler(42, PredictionDecorationQueryTest.PROFILE) {
                @Override public ClientColumnSample sampleSurface(int x, int z) {
                    samples.incrementAndGet();
                    var gate = sampleGate;
                    if (gate != null && x == blockedX && z == blockedZ) gate.block();
                    if (failSamples) throw new IllegalStateException("fixture sample failure");
                    return PredictionSimpleVegetationTest.sample(100);
                }
                @Override public ClientColumnSample sample(int x, int z) { return sampleSurface(x, z); }
                @Override Holder<Biome> noiseBiome(int x, int y, int z) { return Holder.direct(biome); }
                @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
                @Override RandomState randomStateContext() { return random; }
                @Override RegistryAccess decorationAccess() { return RegistryAccess.EMPTY; }
            };
        }
        PredictionVegetation vegetation() { return new PredictionVegetation(terrain, null, false); }
    }

    @Test void independentEditRetainsFarInFlightGenerationAndSharedTerrain() throws Exception {
        var fixture = new Fixture();
        var vegetation = fixture.vegetation();
        var gate = fixture.featureGate = new Gate();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> vegetation.chunk(-20, 30));
            gate.await();
            int samples = fixture.samples.get();
            vegetation.invalidate(0, 0);
            assertTrue(((VssLodSampleCache) field(vegetation, "terrainColumns")).size() >= 256);
            gate.release.countDown();
            var result = future.get(10, TimeUnit.SECONDS);
            assertFalse(result.isEmpty(), vegetation.diagnostics());
            assertSame(result, vegetation.chunk(-20, 30));
            assertEquals(1, fixture.features.get());
            assertEquals(samples, fixture.samples.get());
            assertReleased(vegetation);
        } finally { gate.release.countDown(); executor.shutdownNow(); }
    }

    @Test void allTwentyFiveDependentSourcesCancelButOuterSourcesSurviveAtNegativeCoordinates() throws Exception {
        var fixture = new Fixture();
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                var vegetation = fixture.vegetation();
                int x = -7 + dx, z = -11 + dz;
                var gate = fixture.featureGate = new Gate();
                var future = executor.submit(() -> vegetation.chunk(x, z));
                gate.await();
                for (int repeat = 0; repeat < 10; repeat++) vegetation.invalidate(-7, -11);
                var jobs = (Map<?, ?>) field(vegetation, "generationJobs");
                assertEquals(1, jobs.size());
                assertEquals(1, ((Set<?>) field(jobs.values().iterator().next(), "dirtyChunks")).size());
                gate.release.countDown();
                var failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertFalse(((Map<?, ?>) field(vegetation, "chunks")).containsKey(key(x, z)));
                assertReleased(vegetation);
                fixture.featureGate = null;
                assertFalse(vegetation.chunk(x, z).isEmpty(), vegetation.diagnostics());
                assertReleased(vegetation);
            }
            for (int[] outside : new int[][]{{-10, -11}, {-4, -11}, {-7, -14}, {-7, -8}}) {
                var vegetation = fixture.vegetation();
                var gate = fixture.featureGate = new Gate();
                var future = executor.submit(() -> vegetation.chunk(outside[0], outside[1]));
                gate.await();
                vegetation.invalidate(-7, -11);
                gate.release.countDown();
                var result = future.get(10, TimeUnit.SECONDS);
                assertSame(result, vegetation.chunk(outside[0], outside[1]));
                assertReleased(vegetation);
            }
        } finally {
            if (fixture.featureGate != null) fixture.featureGate.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void lateSampleFactoryCannotRepublishAnInvalidatedGroundColumn() throws Exception {
        var fixture = new Fixture();
        var vegetation = fixture.vegetation();
        fixture.blockedX = -112; fixture.blockedZ = -176;
        var gate = fixture.sampleGate = new Gate();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> vegetation.chunk(-7, -11));
            gate.await();
            vegetation.invalidate(-7, -11);
            gate.release.countDown();
            var failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            var terrain = (VssLodSampleCache) field(vegetation, "terrainColumns");
            assertNull(terrain.get(key(-112, -176)));
            assertReleased(vegetation);
            fixture.sampleGate = null;
            assertFalse(vegetation.chunk(-7, -11).isEmpty());
        } finally { gate.release.countDown(); executor.shutdownNow(); }
    }

    @Test void cancellationStillReleasesSameKeyForTheNextGeneration() throws Exception {
        var fixture = new Fixture();
        var vegetation = fixture.vegetation();
        var current = new AtomicBoolean(true);
        var gate = fixture.featureGate = new Gate();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> generate(vegetation, 5, 6, current::get));
            gate.await();
            assertThrows(PredictionWorkDeferred.class, () -> vegetation.chunk(5, 6));
            current.set(false);
            gate.release.countDown();
            var failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertReleased(vegetation);
            fixture.featureGate = null;
            assertFalse(vegetation.chunk(5, 6).isEmpty());
            Thread.currentThread().interrupt();
            try { assertThrows(CancellationException.class, () -> vegetation.chunk(5, 6)); }
            finally { Thread.interrupted(); }
        } finally { gate.release.countDown(); executor.shutdownNow(); }
    }

    @Test void localEvictionRetainsNegativeChunkBordersAndDistantColumns() {
        var cache = new VssLodSampleCache(1024);
        var sample = PredictionSimpleVegetationTest.sample(100);
        for (int z = -48; z < -32; z++) for (int x = -32; x < -16; x++) cache.put(key(x, z), sample);
        long[] neighbors = {key(-33, -48), key(-16, -48), key(-32, -49), key(-32, -32), key(900, -1000)};
        for (long neighbor : neighbors) cache.put(neighbor, sample);
        cache.removeChunk(-2, -3);
        assertEquals(neighbors.length, cache.size());
        for (long neighbor : neighbors) assertSame(sample, cache.get(neighbor));
    }

    @Test void interruptedJavaWriteRollsBackWithoutPermanentlyDisablingTheFeature() throws Exception {
        var fixture = new Fixture();
        var vegetation = fixture.vegetation();
        var activeLevel = new AtomicReference<PredictionDecorationLevel>();
        var partial = new BlockPos(81, 100, 96);
        fixture.beforeWrite = level -> {
            activeLevel.set(level);
            assertTrue(level.setBlock(partial, Blocks.OAK_LOG.defaultBlockState(), 2, 0));
            assertTrue(level.placed().containsKey(partial));
            Thread.currentThread().interrupt();
        };
        try {
            assertThrows(CancellationException.class, () -> vegetation.chunk(5, 6));
            assertTrue(Thread.currentThread().isInterrupted(), "cancellation must preserve the interrupt flag");
            assertNotNull(activeLevel.get());
            assertTrue(activeLevel.get().placed().isEmpty(), "cancelled feature must roll back its first write");
            assertTrue(((Map<?, ?>) field(vegetation, "chunks")).isEmpty(), "partial source must not be published");
            assertTrue(((Set<?>) field(vegetation, "unsupportedFeatures")).isEmpty(),
                    "thread cancellation must not classify a supported feature as permanently unsupported");
            assertReleased(vegetation);
        } finally {
            fixture.beforeWrite = null;
            Thread.interrupted();
        }
        var result = vegetation.chunk(5, 6);
        assertEquals(Map.of(new BlockPos(80, 100, 96), Blocks.OAK_LOG.defaultBlockState()), result);
        assertSame(result, vegetation.chunk(5, 6));
        assertEquals(2, fixture.features.get(), "the same feature must execute again after cancellation");
        assertReleased(vegetation);
    }

    @Test void actualWriteBoundsAndBudgetStillRejectAndRollBack() {
        var fixture = new Fixture();
        var level = new PredictionDecorationLevel(fixture.terrain, fixture.terrain, RegistryAccess.EMPTY, 5, 6);
        var position = new BlockPos(80, 100, 96);
        assertThrows(UnsupportedOperationException.class,
                () -> level.setBlock(position.offset(-17, 0, 0), Blocks.OAK_LOG.defaultBlockState(), 2, 0));
        level.beginFeature();
        try {
            for (int i = 0; i < 65_536; i++)
                assertTrue(level.setBlock(position, Blocks.OAK_LOG.defaultBlockState(), 2, 0));
            assertThrows(UnsupportedOperationException.class,
                    () -> level.setBlock(position, Blocks.OAK_LOG.defaultBlockState(), 2, 0));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally { level.endFeature(false); }
        assertTrue(level.placed().isEmpty());
    }

    @Test void failedTerrainLookupReleasesOwnershipAndAllowsRetry() throws Exception {
        var fixture = new Fixture();
        var vegetation = fixture.vegetation();
        fixture.failSamples = true;
        assertThrows(IllegalStateException.class, () -> vegetation.chunk(-7, -11));
        assertReleased(vegetation);
        fixture.failSamples = false;
        assertFalse(vegetation.chunk(-7, -11).isEmpty());
        assertReleased(vegetation);
    }

    @Test void renderingRegistersInvalidationBeforeGatheringItsFirstSource() throws Exception {
        var fixture = new Fixture();
        var vegetation = fixture.vegetation();
        var gate = fixture.featureGate = new Gate();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> vegetation.tileForRendering(-112, -176, 16, 1,
                    true, ignored -> false, () -> true));
            gate.await();
            vegetation.invalidate(-10, -11);
            gate.release.countDown();
            var failure = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertReleased(vegetation);
            var cache = (PredictionVegetationDisplayCache) field(vegetation, "displayCache");
            assertEquals(0, ((Set<?>) field(cache, "active")).size());
            fixture.featureGate = null;
            assertFalse(vegetation.tileForRendering(-112, -176, 16, 1,
                    true, ignored -> false, () -> true).blocks().isEmpty());
        } finally { gate.release.countDown(); executor.shutdownNow(); }
    }

    @Test void displayBuildInvalidationIsLocalAndCoversGatheredSourceBorders() throws Exception {
        var cache = new PredictionVegetationDisplayCache();
        var blocks = Map.of(new BlockPos(-160, 100, 480), Blocks.OAK_LOG.defaultBlockState());
        var sources = List.of(new PredictionVegetationDisplayCache.Source(key(-10, 30), blocks));
        var gate = new Gate();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> cache.tile(-160, 480, 16, 1, true, sources, chunk -> {
                gate.block(); return false;
            }));
            gate.await();
            cache.invalidate(0, 0);
            gate.release.countDown();
            var result = future.get(10, TimeUnit.SECONDS);
            assertSame(result, cache.tile(-160, 480, 16, 1, true, sources, ignored -> false));
            assertEquals(0, ((Set<?>) field(cache, "active")).size());
            // Display [-112,-96) gathers source -8. A dirty chunk -10 changes that source.
            try (var build = cache.begin(-112, -176, 16, 1, true)) {
                cache.invalidate(-10, -11);
                assertThrows(CancellationException.class, () -> cache.tile(build, List.of(), ignored -> false));
            }
            assertEquals(0, ((Set<?>) field(cache, "active")).size());
            for (int repeat = 0; repeat < 300; repeat++) {
                try (var build = cache.begin(-112, -176, 16, 1, true)) {
                    cache.invalidate(-7, -11);
                    assertThrows(CancellationException.class, () -> cache.tile(build, List.of(), ignored -> false));
                }
            }
            assertEquals(0, ((Set<?>) field(cache, "active")).size());
        } finally { gate.release.countDown(); executor.shutdownNow(); }
    }

    @Test @SuppressWarnings("unchecked") void sharedMergePreservesOrderingAndPartialColumnCaptures() throws Exception {
        var fixture = new Fixture();
        var vegetation = fixture.vegetation();
        var chunks = (Map<Long, Map<BlockPos, BlockState>>) field(vegetation, "chunks");
        var overlap = new BlockPos(-3, 100, -3);
        // Deliberately insert in the reverse of source order; both paths must use z then x.
        chunks.put(key(-1, -1), Map.of(overlap, Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(-8, 100, -8), Blocks.OAK_LEAVES.defaultBlockState()));
        chunks.put(key(-2, -2), Map.of(overlap, Blocks.BIRCH_LOG.defaultBlockState(),
                new BlockPos(-17, 100, -17), Blocks.BIRCH_LEAVES.defaultBlockState()));
        for (int spacing : new int[]{1, 2, 4, 8}) {
            var legacy = vegetation.cachedDisplay(-32, -32, 32, spacing,
                    (x, z) -> Math.floorDiv(x, 16) == -2 && Math.floorDiv(z, 16) == -2);
            var rendering = vegetation.cachedDisplayForRendering(-32, -32, 32, spacing,
                    chunk -> chunk == key(-2, -2));
            assertEquals(legacy, rendering);
        }
        var partial = vegetation.cachedDisplay(-32, -32, 32, 1, (x, z) -> x == -8);
        assertFalse(partial.blocks().containsKey(new BlockPos(-8, 100, -8)));
        assertEquals(Blocks.OAK_LOG.defaultBlockState(), partial.blocks().get(overlap));
        assertTrue(partial.blocks().containsKey(new BlockPos(-17, 100, -17)));
    }

    static void assertReleased(PredictionVegetation vegetation) throws Exception {
        assertTrue(((Map<?, ?>) field(vegetation, "generationJobs")).isEmpty());
    }
    static Map<BlockPos, BlockState> generate(PredictionVegetation vegetation, int x, int z, BooleanSupplier current) throws Exception {
        var method = PredictionVegetation.class.getDeclaredMethod("chunk", int.class, int.class, BooleanSupplier.class);
        method.setAccessible(true);
        try {
            @SuppressWarnings("unchecked") var result = (Map<BlockPos, BlockState>) method.invoke(vegetation, x, z, current);
            return result;
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }
    static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    static long key(int x, int z) { return PredictionVegetationDisplayCache.chunkKey(x, z); }
}
