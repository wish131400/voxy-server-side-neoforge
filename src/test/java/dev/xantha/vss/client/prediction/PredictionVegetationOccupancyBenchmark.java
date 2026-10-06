package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Old MapN is installed only in an opt-in benchmark fixture, never production or ordinary tests. */
@EnabledIfEnvironmentVariable(named = "VSS_VEGETATION_OCCUPANCY_BENCH", matches = "true")
class PredictionVegetationOccupancyBenchmark {
    private static volatile long consumed;
    private static final int WARMUP = 5, ROUNDS = 10;
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    record Result(long nanos, long cpu, long bytes, long checksum) { }
    record Fixture(int span, Map<BlockPos, BlockState> blocks, BlockPos[] positions,
                   PredictionVegetation.Tile baseline, PredictionVegetation.Tile candidate,
                   ClientColumnSample[] samples) { }

    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }

    @Test void compareRealDenseTreeNeighborQueriesAndCompleteMesh() throws Exception {
        threads.setThreadAllocatedMemoryEnabled(true);
        threads.setThreadCpuTimeEnabled(true);
        System.out.println("VEGETATION_OCCUPANCY environment=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.name") + " arch=" + System.getProperty("os.arch"));
        for (int span : new int[]{32, 64}) {
            var fixture = forest(span);
            assertEquals(fixture.baseline, fixture.candidate);
            assertArrayEquals(PredictionMeshCodec.decorationBytes(fixture.baseline),
                    PredictionMeshCodec.decorationBytes(fixture.candidate));
            assertEquals(queries(fixture, fixture.baseline), queries(fixture, fixture.candidate));
            for (int spacing : new int[]{1, 4}) {
                var samples = PredictionRefinementOptimizationsTest.samples(span / spacing + 1);
                var baseline = withOldMap(PredictionVegetation.Tile.of(fixture.blocks, -64, -64, span, spacing, 1));
                var candidate = PredictionVegetation.Tile.of(fixture.blocks, -64, -64, span, spacing, 1);
                PredictionVegetationOccupancyTest.assertMeshEqual(
                        PredictionVegetationOccupancyTest.build(samples, baseline, spacing, false),
                        PredictionVegetationOccupancyTest.build(samples, candidate, spacing, false), samples);
                PredictionVegetationOccupancyTest.assertMeshEqual(
                        PredictionVegetationOccupancyTest.build(samples, baseline, spacing, true),
                        PredictionVegetationOccupancyTest.build(samples, candidate, spacing, true), samples);
            }
            paired("neighbor-query", fixture, () -> queries(fixture, fixture.baseline), () -> queries(fixture, fixture.candidate), 2);
            paired("complete-mesh", fixture, () -> mesh(fixture, fixture.baseline), () -> mesh(fixture, fixture.candidate), 2);
        }
        System.out.println("Real vanilla Feature.TREE placement on a flat test surface; this isolates meshing/map cost, not modpack FPS or terrain generation.");
    }

    private void paired(String stage, Fixture fixture, LongSupplier baseline, LongSupplier candidate, int repeats) {
        var old = new ArrayList<Result>(); var current = new ArrayList<Result>();
        for (int round = -WARMUP; round < ROUNDS; round++) {
            Result a, b;
            if ((round & 1) == 0) { a = measure(baseline, repeats); b = measure(candidate, repeats); }
            else { b = measure(candidate, repeats); a = measure(baseline, repeats); }
            assertEquals(a.checksum, b.checksum);
            if (round >= 0) { old.add(a); current.add(b); }
        }
        System.out.printf(Locale.ROOT,
                "VEGETATION_OCCUPANCY stage=%s span=%d blocks=%d repeats=%d paired=%d baselineMedianMs=%.3f candidateMedianMs=%.3f baselineCpuMs=%.3f candidateCpuMs=%.3f baselineMedianBytes=%.0f candidateMedianBytes=%.0f outputEqual=true%n",
                stage, fixture.span, fixture.blocks.size(), repeats, ROUNDS,
                median(old, 0) / 1e6, median(current, 0) / 1e6,
                median(old, 1) / 1e6, median(current, 1) / 1e6, median(old, 2), median(current, 2));
    }

    private Result measure(LongSupplier work, int repeats) {
        long id = Thread.currentThread().getId(), bytes = threads.getThreadAllocatedBytes(id);
        long cpu = threads.getCurrentThreadCpuTime(), start = System.nanoTime(), checksum = 0;
        for (int i = 0; i < repeats; i++) checksum = 31 * checksum + work.getAsLong();
        long elapsed = System.nanoTime() - start;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        bytes = threads.getThreadAllocatedBytes(id) - bytes;
        consumed = checksum;
        return new Result(elapsed, cpu, bytes, checksum);
    }

    private static long queries(Fixture fixture, PredictionVegetation.Tile tile) {
        long count = 0;
        for (var p : fixture.positions) {
            int x = p.getX() - tile.baseX(), y = p.getY(), z = p.getZ() - tile.baseZ();
            if (tile.occupied(x - 1, y, z)) count++;
            if (tile.occupied(x + 1, y, z)) count++;
            if (tile.occupied(x, y - 1, z)) count++;
            if (tile.occupied(x, y + 1, z)) count++;
            if (tile.occupied(x, y, z - 1)) count++;
            if (tile.occupied(x, y, z + 1)) count++;
        }
        return count;
    }

    private static long mesh(Fixture fixture, PredictionVegetation.Tile tile) {
        var mesh = PredictionVegetationOccupancyTest.build(fixture.samples, tile, 1, true);
        var packed = PredictionVegetationOccupancyTest.pack(mesh, fixture.samples);
        return 31L * mesh.vertexCount() + Arrays.hashCode(packed.quads());
    }

    private static Fixture forest(int span) throws Exception {
        Method treeMethod = PredictionVegetationTest.class.getDeclaredMethod("tree"); treeMethod.setAccessible(true);
        var ordinary = (PlacedFeature) treeMethod.invoke(null);
        var modifiers = new ArrayList<>(ordinary.placement()); modifiers.set(0, CountPlacement.of(24));
        var trees = new PlacedFeature(ordinary.feature(), List.copyOf(modifiers));
        Method samplerMethod = PredictionVegetationTest.class.getDeclaredMethod("sampler", long.class, Block.class, List.class);
        samplerMethod.setAccessible(true);
        var sampler = (ClientTerrainSampler) samplerMethod.invoke(null, 48271L, Blocks.GRASS_BLOCK, List.of(trees));
        var vegetation = new PredictionVegetation(sampler, null, false);
        var blocks = new HashMap<BlockPos, BlockState>();
        for (int z = 0; z < span; z += 16) for (int x = 0; x < span; x += 16)
            blocks.putAll(vegetation.chunk((-64 + x) / 16, (-64 + z) / 16));
        assertTrue(blocks.values().stream().anyMatch(state -> state.is(Blocks.OAK_LEAVES)));
        assertTrue(blocks.values().stream().anyMatch(state -> state.is(Blocks.OAK_LOG)));
        var candidate = PredictionVegetation.Tile.of(blocks, -64, -64, span, 1, 1);
        var baseline = withOldMap(PredictionVegetation.Tile.of(blocks, -64, -64, span, 1, 1));
        return new Fixture(span, blocks, blocks.keySet().toArray(BlockPos[]::new), baseline, candidate,
                PredictionRefinementOptimizationsTest.samples(span + 1));
    }

    private static PredictionVegetation.Tile withOldMap(PredictionVegetation.Tile tile) throws Exception {
        // Records reject Field.set and sun.misc.Unsafe offsets. This opt-in
        // benchmark JVM alone opens the internal field access for its baseline.
        Class<?> type = Class.forName("jdk.internal.misc.Unsafe");
        var field = type.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object unsafe = field.get(null);
        long offset = (Long) type.getMethod("objectFieldOffset", Class.class, String.class)
                .invoke(unsafe, PredictionVegetation.Tile.class, "blocks");
        type.getMethod("putReference", Object.class, long.class, Object.class)
                .invoke(unsafe, tile, offset, Map.copyOf(tile.blocks()));
        assertTrue(tile.blocks().getClass().getName().contains("ImmutableCollections$MapN"));
        return tile;
    }

    private static double median(List<Result> values, int field) {
        long[] sorted = values.stream().mapToLong(result -> switch (field) {
            case 0 -> result.nanos; case 1 -> result.cpu; default -> result.bytes;
        }).sorted().toArray();
        return (sorted[4] + sorted[5]) / 2.0;
    }
}
