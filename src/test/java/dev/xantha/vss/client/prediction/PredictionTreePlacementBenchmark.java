package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.*;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.*;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The baseline source is saved under ignored build evidence, never retained as a production strategy. */
@EnabledIfEnvironmentVariable(named = "VSS_PLACEMENT_BENCH_OUTPUT", matches = ".+")
class PredictionTreePlacementBenchmark {
    private static final int WARMUP = 16, ROUNDS = 10, OPERATIONS = 512;
    private static volatile long consumed;
    private static Map<PlacedFeature, PredictionPlacementExecutor.Plan> savedPlans;
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    record Result(long wall, long cpu, long bytes, long writes, long checksum) { }

    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        PredictionDecorationQueryTest.bootstrap();
        PredictionVegetationTest.bootstrap();
        synchronized (planCache()) { savedPlans = new java.util.HashMap<>(planCache()); }
    }
    @AfterAll static void restoreTags() throws ReflectiveOperationException {
        synchronized (planCache()) { planCache().clear(); planCache().putAll(savedPlans); }
        PredictionVegetationTest.restoreTags();
    }

    @Test void pairedModelTreesSelectorsFallbacksAndContextualDecorators() throws Throwable {
        threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
        var fixture = new PredictionPlacementExecutorTest.Fixture();
        var rows = new ArrayList<String>(List.of(
                "scenario,round,operations,oldWallNs,newWallNs,oldCpuNs,newCpuNs,oldBytes,newBytes,writes,checksum,productionFastPath"));
        for (String scenario : List.of("supported-tree", "nested-selectors", "fallback-tree", "contextual-decorators")) {
            var feature = feature(scenario);
            primePlans(feature);
            var baseline = models("PredictionTreeModelsBaseline", fixture.generator);
            var production = models("PredictionTreeModels", fixture.generator);
            var candidate = models("PredictionTreeModelsFastBench", fixture.generator);
            long written = 0;
            boolean vines = false;
            for (int seed = 0; seed < 48; seed++) {
                var oldLevel = level(); var newLevel = level(); var productionLevel = level();
                var oldRandom = RandomSource.create(seed); var newRandom = RandomSource.create(seed);
                var productionRandom = RandomSource.create(seed);
                place(baseline, feature, oldLevel, oldRandom);
                place(candidate, feature, newLevel, newRandom);
                place(production, feature, productionLevel, productionRandom);
                assertEquals(oldLevel.placed(), newLevel.placed(), scenario + " seed=" + seed);
                assertEquals(oldLevel.placed(), productionLevel.placed(), scenario + " production seed=" + seed);
                for (int i = 0; i < 16; i++) {
                    long expected = oldRandom.nextLong();
                    assertEquals(expected, newRandom.nextLong());
                    assertEquals(expected, productionRandom.nextLong());
                }
                written += newLevel.placed().size();
                vines |= newLevel.placed().values().stream().anyMatch(state -> state.is(Blocks.VINE));
            }
            assertTrue(written > 0);
            if (scenario.equals("contextual-decorators")) assertTrue(vines);
            long[] oldWall = new long[ROUNDS], newWall = new long[ROUNDS], oldBytes = new long[ROUNDS], newBytes = new long[ROUNDS];
            long[] oldCpu = new long[ROUNDS], newCpu = new long[ROUNDS];
            for (int round = -WARMUP; round < ROUNDS; round++) {
                Result oldResult, newResult;
                if ((round & 1) == 0) {
                    oldResult = run(baseline, feature); newResult = run(candidate, feature);
                } else {
                    newResult = run(candidate, feature); oldResult = run(baseline, feature);
                }
                assertEquals(oldResult.writes, newResult.writes, scenario);
                assertEquals(oldResult.checksum, newResult.checksum, scenario);
                if (round >= 0) {
                    oldWall[round] = oldResult.wall; newWall[round] = newResult.wall;
                    oldBytes[round] = oldResult.bytes; newBytes[round] = newResult.bytes;
                    oldCpu[round] = oldResult.cpu; newCpu[round] = newResult.cpu;
                    rows.add(scenario + "," + round + "," + OPERATIONS + "," + oldResult.wall + "," + newResult.wall
                            + "," + oldResult.cpu + "," + newResult.cpu + "," + oldResult.bytes + "," + newResult.bytes
                            + "," + oldResult.writes + "," + oldResult.checksum + "," + PredictionPlacementExecutor.available());
                }
            }
            System.out.printf(Locale.ROOT,
                    "TREE_PLACEMENT scenario=%s operations=%d oldMs=%.3f newMs=%.3f oldCpuMs=%.3f newCpuMs=%.3f oldBytes=%.0f newBytes=%.0f productionFastPath=%s forcedVanillaBench=true productionPlanLookup=true exact=true%n",
                    scenario, OPERATIONS, median(oldWall) / 1e6, median(newWall) / 1e6,
                    median(oldCpu) / 1e6, median(newCpu) / 1e6,
                    median(oldBytes), median(newBytes), PredictionPlacementExecutor.available());
        }
        var directory = Path.of(System.getenv("VSS_PLACEMENT_BENCH_OUTPUT"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("tree-placement-rounds.csv"), rows);
    }

    @SuppressWarnings("unchecked")
    private static Map<PlacedFeature, PredictionPlacementExecutor.Plan> planCache() throws ReflectiveOperationException {
        var field = PredictionPlacementExecutor.class.getDeclaredField("PLANS");
        field.setAccessible(true);
        return (Map<PlacedFeature, PredictionPlacementExecutor.Plan>) field.get(null);
    }

    private static void primePlans(PlacedFeature feature) throws ReflectiveOperationException {
        synchronized (planCache()) {
            planCache().put(feature, PredictionPlacementExecutor.compile(feature.placement(), ignored -> true));
        }
        var configured = feature.feature().value();
        if (configured.config() instanceof RandomFeatureConfiguration random) {
            for (var choice : random.features) primePlans(choice.feature.value());
            primePlans(random.defaultFeature.value());
        } else if (configured.config() instanceof SimpleRandomFeatureConfiguration random) {
            for (var choice : random.features) primePlans(choice.value());
        } else if (configured.config() instanceof RandomBooleanFeatureConfiguration random) {
            primePlans(random.featureTrue.value());
            primePlans(random.featureFalse.value());
        }
    }

    private Result run(MethodHandle models, PlacedFeature feature) throws Throwable {
        long id = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(id), cpu = threads.getCurrentThreadCpuTime();
        long started = System.nanoTime(), writes = 0, checksum = 1;
        for (int seed = 0; seed < OPERATIONS; seed++) {
            var level = level();
            var random = RandomSource.create(seed);
            place(models, feature, level, random);
            writes += level.placed().size();
            checksum = checksum * 31 + level.placed().hashCode() + random.nextLong();
        }
        long wall = System.nanoTime() - started;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        bytes = threads.getThreadAllocatedBytes(id) - bytes;
        consumed = checksum;
        return new Result(wall, cpu, bytes, writes, checksum);
    }

    private static MethodHandle models(String name, ChunkGenerator generator) throws ReflectiveOperationException {
        Class<?> type = Class.forName("dev.xantha.vss.client.prediction." + name);
        var lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        var constructor = lookup.findConstructor(type, MethodType.methodType(void.class, RegistryAccess.class, ChunkGenerator.class));
        Object models;
        try { models = constructor.invoke(RegistryAccess.EMPTY, generator); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
        return lookup.findVirtual(type, "place", MethodType.methodType(void.class,
                PlacedFeature.class, WorldGenLevel.class, RandomSource.class, BlockPos.class)).bindTo(models);
    }

    private static void place(MethodHandle models, PlacedFeature feature, FeatureStampLevel level, RandomSource random) throws Throwable {
        models.invokeExact(feature, (WorldGenLevel) level, random, new BlockPos(-16, FeatureStampLevel.GROUND_Y, -16));
    }

    private static FeatureStampLevel level() {
        return new FeatureStampLevel(42, RegistryAccess.EMPTY, Blocks.GRASS_BLOCK.defaultBlockState());
    }

    private static PlacedFeature feature(String scenario) {
        var oak = tree(Blocks.OAK_LOG, Blocks.OAK_LEAVES, List.of());
        var decorated = tree(Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES,
                List.of(TrunkVineDecorator.INSTANCE, new LeaveVineDecorator(1), new CocoaDecorator(1)));
        var fallback = tree(Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, List.of(new BeehiveDecorator(0)));
        ConfiguredFeature<?, ?> configured;
        if (scenario.equals("nested-selectors")) {
            var nestedModifiers = List.<PlacementModifier>of(new PredictionPlacementExecutorTest.ContextModifier(false),
                    CountPlacement.of(2), RarityFilter.onAverageOnceEvery(2));
            var oakPlaced = new PlacedFeature(Holder.direct(oak), nestedModifiers);
            var fallbackPlaced = new PlacedFeature(Holder.direct(fallback), nestedModifiers);
            var bool = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.RANDOM_BOOLEAN_SELECTOR,
                    new RandomBooleanFeatureConfiguration(Holder.direct(oakPlaced), Holder.direct(fallbackPlaced)))), nestedModifiers);
            var simple = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.SIMPLE_RANDOM_SELECTOR,
                    new SimpleRandomFeatureConfiguration(net.minecraft.core.HolderSet.direct(Holder.direct(bool),
                            Holder.direct(oakPlaced))))), nestedModifiers);
            configured = new ConfiguredFeature<>(Feature.RANDOM_SELECTOR,
                    new RandomFeatureConfiguration(List.of(new net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature(
                            Holder.direct(simple), .5F)), Holder.direct(bool)));
        } else configured = scenario.equals("contextual-decorators") ? decorated
                : scenario.equals("fallback-tree") ? fallback : oak;
        return new PlacedFeature(Holder.direct(configured), List.of(CountPlacement.of(3),
                new PredictionPlacementExecutorTest.ContextModifier(true), InSquarePlacement.spread(),
                HeightmapPlacement.onHeightmap(Heightmap.Types.WORLD_SURFACE_WG)));
    }

    private static ConfiguredFeature<TreeConfiguration, ?> tree(net.minecraft.world.level.block.Block log,
                                                               net.minecraft.world.level.block.Block leaves,
                                                               List<TreeDecorator> decorators) {
        return new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(log), new StraightTrunkPlacer(4, 1, 0), BlockStateProvider.simple(leaves),
                new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).ignoreVines().decorators(decorators).build());
    }

    private static double median(long[] values) {
        var sorted = values.clone(); Arrays.sort(sorted);
        return (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2D;
    }
}
