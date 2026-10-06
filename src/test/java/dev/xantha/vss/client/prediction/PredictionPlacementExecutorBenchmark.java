package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Complete Java modifier/filter/configured-feature transactions; no isolated Stream microbenchmark. */
@EnabledIfEnvironmentVariable(named = "VSS_PLACEMENT_BENCH_OUTPUT", matches = ".+")
class PredictionPlacementExecutorBenchmark {
    private static final int WARMUP = 16, ROUNDS = 10, OPERATIONS = 2048;
    private static volatile long consumed;
    private static Map<PlacedFeature, PredictionPlacementExecutor.Plan> savedPlans;
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    record Result(long wall, long cpu, long bytes, long writes, long checksum) { }

    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        PredictionDecorationQueryTest.bootstrap();
        synchronized (planCache()) { savedPlans = new java.util.HashMap<>(planCache()); }
    }

    @AfterAll static void restorePlans() throws ReflectiveOperationException {
        synchronized (planCache()) { planCache().clear(); planCache().putAll(savedPlans); }
    }

    @Test void compareWholeFeaturesWithIdenticalWritesAndRandomContinuation() throws Exception {
        threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
        var rows = new ArrayList<String>(List.of(
                "scenario,round,operations,oldWallNs,newWallNs,oldCpuNs,newCpuNs,oldBytes,newBytes,writes,checksum"));
        for (String scenario : List.of("surface-patch", "biome-filter-rejection-count4",
                "environment-scan-success-count4", "environment-scan-rejection-count4", "environment-scan-rejection")) {
            List<PlacementModifier> modifiers = modifiers(scenario);
            var feature = PredictionPlacementExecutorTest.feature(modifiers);
            var plan = PredictionPlacementExecutor.compile(modifiers, ignored -> true);
            synchronized (planCache()) { planCache().put(feature, plan); }
            var fixture = new PredictionPlacementExecutorTest.Fixture();
            // Paired actual map and subsequent RNG equality are outside the timed workload.
            for (int seed = 0; seed < 32; seed++) {
                var oldLevel = fixture.level(); var newLevel = fixture.level();
                var oldRandom = RandomSource.create(seed); var newRandom = RandomSource.create(seed);
                PredictionPlacementExecutorTest.replay(feature, oldLevel, fixture.generator, oldRandom, null);
                PredictionPlacementExecutorTest.replay(feature, newLevel, fixture.generator, newRandom, plan);
                assertEquals(oldLevel.placed(), newLevel.placed());
                for (int i = 0; i < 8; i++) assertEquals(oldRandom.nextLong(), newRandom.nextLong());
            }
            long[] oldWall = new long[ROUNDS], newWall = new long[ROUNDS], oldBytes = new long[ROUNDS], newBytes = new long[ROUNDS];
            long[] oldCpu = new long[ROUNDS], newCpu = new long[ROUNDS];
            for (int round = -WARMUP; round < ROUNDS; round++) {
                Result oldResult, newResult;
                if ((round & 1) == 0) {
                    oldResult = run(fixture, feature, null); newResult = run(fixture, feature, plan);
                } else {
                    newResult = run(fixture, feature, plan); oldResult = run(fixture, feature, null);
                }
                assertEquals(oldResult.writes, newResult.writes);
                assertEquals(oldResult.checksum, newResult.checksum);
                if (round >= 0) {
                    oldWall[round] = oldResult.wall; newWall[round] = newResult.wall;
                    oldBytes[round] = oldResult.bytes; newBytes[round] = newResult.bytes;
                    oldCpu[round] = oldResult.cpu; newCpu[round] = newResult.cpu;
                    rows.add(scenario + "," + round + "," + OPERATIONS + "," + oldResult.wall + "," + newResult.wall
                            + "," + oldResult.cpu + "," + newResult.cpu + "," + oldResult.bytes + "," + newResult.bytes
                            + "," + oldResult.writes + "," + oldResult.checksum);
                }
            }
            System.out.printf(Locale.ROOT,
                    "PLACEMENT scenario=%s operations=%d oldMs=%.3f newMs=%.3f oldCpuMs=%.3f newCpuMs=%.3f oldBytes=%.0f newBytes=%.0f fastModifiers=%d/%d productionPlanLookup=true exact=true mixedFallback=true%n",
                    scenario, OPERATIONS, median(oldWall) / 1e6, median(newWall) / 1e6,
                    median(oldCpu) / 1e6, median(newCpu) / 1e6,
                    median(oldBytes), median(newBytes), plan.fastOperations(), modifiers.size());
        }
        var directory = Path.of(System.getenv("VSS_PLACEMENT_BENCH_OUTPUT"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("placement-executor-rounds.csv"), rows);
    }

    @SuppressWarnings("unchecked")
    private static Map<PlacedFeature, PredictionPlacementExecutor.Plan> planCache() throws ReflectiveOperationException {
        var field = PredictionPlacementExecutor.class.getDeclaredField("PLANS");
        field.setAccessible(true);
        return (Map<PlacedFeature, PredictionPlacementExecutor.Plan>) field.get(null);
    }

    private static List<PlacementModifier> modifiers(String scenario) {
        if (scenario.equals("surface-patch")) return PredictionPlacementExecutorTest.surfaceModifiers();
        if (scenario.equals("biome-filter-rejection-count4"))
            return List.of(CountPlacement.of(4), InSquarePlacement.spread(),
                    HeightmapPlacement.onHeightmap(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG),
                    BiomeFilter.biome());
        var modifiers = new ArrayList<PlacementModifier>(List.of(
                CountPlacement.of(scenario.endsWith("count4") ? 4 : 32), InSquarePlacement.spread(),
                HeightRangePlacement.uniform(VerticalAnchor.absolute(100), VerticalAnchor.absolute(120)),
                EnvironmentScanPlacement.scanningFor(Direction.DOWN,
                        BlockPredicate.matchesBlocks(scenario.contains("success") ? Blocks.GRASS_BLOCK : Blocks.DIAMOND_BLOCK), 12)));
        if (scenario.contains("success")) modifiers.add(RandomOffsetPlacement.vertical(ConstantInt.of(1)));
        modifiers.add(BlockPredicateFilter.forPredicate(BlockPredicate.ONLY_IN_AIR_PREDICATE));
        return List.copyOf(modifiers);
    }

    private Result run(PredictionPlacementExecutorTest.Fixture fixture, PlacedFeature feature,
                       PredictionPlacementExecutor.Plan plan) {
        var level = fixture.level();
        var random = RandomSource.create(0);
        long id = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(id), cpu = threads.getCurrentThreadCpuTime();
        long started = System.nanoTime();
        for (int i = 0; i < OPERATIONS; i++) {
            random.setSeed(i);
            if (plan == null) PredictionPlacementExecutorTest.replay(feature, level, fixture.generator, random, null);
            else {
                level.beginFeature();
                boolean success = false;
                try {
                    PredictionPlacementExecutor.visit(feature,
                            new PlacementContext(level, fixture.generator, Optional.of(feature)), random,
                            PredictionPlacementExecutorTest.ORIGIN,
                            pos -> feature.feature().value().place(level, fixture.generator, random, pos));
                    success = true;
                } finally { level.endFeature(success); }
            }
        }
        long wall = System.nanoTime() - started;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        bytes = threads.getThreadAllocatedBytes(id) - bytes;
        long checksum = level.placed().hashCode() * 31L + random.nextLong();
        consumed = checksum;
        return new Result(wall, cpu, bytes, level.placed().size(), checksum);
    }

    private static double median(long[] values) {
        var sorted = values.clone(); Arrays.sort(sorted);
        return (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2D;
    }
}
