package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Isolates invalidation cost: legacy runs also pay the new local bookkeeping. */
@EnabledIfEnvironmentVariable(named = "VSS_QUERY_LOCALITY_BENCH_OUTPUT", matches = ".+")
class PredictionDecorationLocalityBenchmark {
    private static final int WARMUP = 6, ROUNDS = 12, OPERATIONS = 16_384;
    private static volatile long consumed;
    private static volatile ClientColumnSample consumedSample;
    private static MethodHandle legacyInvalidation;
    private static MethodHandle nativeColumn;
    private static MethodHandle legacyRebuild;
    private static MethodHandle changedTops;
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final List<String> rows = new ArrayList<>(List.of(
            "stage,round,operations,legacyWallNs,localWallNs,legacyCpuNs,localCpuNs,legacyBytes,localBytes,legacyBaseReads,localBaseReads"));

    @BeforeAll static void bootstrap() throws Exception {
        PredictionDecorationQueryTest.bootstrap();
        legacyInvalidation = MethodHandles.privateLookupIn(PredictionDecorationLevel.class, MethodHandles.lookup())
                .findVirtual(PredictionDecorationLevel.class, "invalidateQueryEpoch", MethodType.methodType(void.class));
        nativeColumn = MethodHandles.privateLookupIn(PredictionDecorationLevel.class, MethodHandles.lookup())
                .findVirtual(PredictionDecorationLevel.class, "nativeColumn",
                        MethodType.methodType(int[].class, RustTerrainSampler.class, int.class, int.class));
        var lookup = MethodHandles.privateLookupIn(PredictionDecorationLevel.class, MethodHandles.lookup());
        legacyRebuild = lookup.findVirtual(PredictionDecorationLevel.class, "rebuildChangedTops", MethodType.methodType(void.class));
        changedTops = lookup.findGetter(PredictionDecorationLevel.class, "changedTops", int[].class);
    }

    @Test void compareEmptyFailedTransactionsAgainstThePreviousProductionRebuild() throws Throwable {
        threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
        int operations = 128;
        var output = new ArrayList<String>(List.of("blocks,round,operations,legacyWallNs,localWallNs,legacyCpuNs,localCpuNs,legacyBytes,localBytes,legacyScannedEntries,localScannedEntries"));
        for (int blocks : new int[]{1024, 16384}) {
            var original = failureFixture(blocks);
            var candidate = failureFixture(blocks);
            long[] oldWall = new long[ROUNDS], newWall = new long[ROUNDS], oldBytes = new long[ROUNDS], newBytes = new long[ROUNDS];
            long[] oldCpu = new long[ROUNDS], newCpu = new long[ROUNDS];
            for (int round = -WARMUP; round < ROUNDS; round++) {
                Result oldResult, newResult;
                if ((round & 1) == 0) {
                    oldResult = failedTransactions(original, true, operations);
                    newResult = failedTransactions(candidate, false, operations);
                } else {
                    newResult = failedTransactions(candidate, false, operations);
                    oldResult = failedTransactions(original, true, operations);
                }
                assertEquals(original.placed(), candidate.placed());
                assertArrayEquals((int[]) changedTops.invokeExact(original), (int[]) changedTops.invokeExact(candidate));
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (var type : Heightmap.Types.values())
                    assertEquals(original.getHeight(type, x, z), candidate.getHeight(type, x, z));
                if (round >= 0) {
                    oldWall[round] = oldResult.wall; newWall[round] = newResult.wall;
                    oldCpu[round] = oldResult.cpu; newCpu[round] = newResult.cpu;
                    oldBytes[round] = oldResult.bytes; newBytes[round] = newResult.bytes;
                    output.add(blocks + "," + round + "," + operations + "," + oldResult.wall + "," + newResult.wall
                            + "," + oldResult.cpu + "," + newResult.cpu + "," + oldResult.bytes + "," + newResult.bytes
                            + "," + (long) blocks * operations + ",0");
                }
            }
            System.out.printf(Locale.ROOT,
                    "EMPTY_ROLLBACK blocks=%d operations=%d oldMs=%.3f newMs=%.3f oldCpuMs=%.3f newCpuMs=%.3f oldBytes=%.0f newBytes=%.0f oldScannedEntries=%d newScannedEntries=0 exact=true baselineReplaysProductionRebuild=true%n",
                    blocks, operations, median(oldWall) / 1e6, median(newWall) / 1e6,
                    median(oldCpu) / 1e6, median(newCpu) / 1e6, median(oldBytes), median(newBytes), (long) blocks * operations);
        }
        var directory = Path.of(System.getenv("VSS_QUERY_LOCALITY_BENCH_OUTPUT"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("decoration-empty-rollback-rounds.csv"), output);
    }

    private PredictionDecorationLevel failureFixture(int blocks) {
        var terrain = new Terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        var existing = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int i = 0; i < blocks; i++)
            existing.put(new BlockPos(i & 15, 104 + (i >>> 8), (i >>> 4) & 15),
                    (i & 1) == 0 ? Blocks.OAK_LOG.defaultBlockState() : Blocks.OAK_LEAVES.defaultBlockState());
        level.restoreSurface(existing);
        assertEquals(blocks, level.placed().size());
        return level;
    }

    private Result failedTransactions(PredictionDecorationLevel level, boolean legacy, int operations) throws Throwable {
        long thread = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(thread), cpu = threads.getCurrentThreadCpuTime();
        long start = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            level.beginFeature();
            level.endFeature(false);
            if (legacy) legacyRebuild.invokeExact(level);
        }
        long wall = System.nanoTime() - start;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        bytes = threads.getThreadAllocatedBytes(thread) - bytes;
        consumed = level.placed().size();
        return new Result(wall, cpu, bytes, 0, level.placed().size());
    }

    private static double median(long[] values) {
        var sorted = values.clone(); Arrays.sort(sorted);
        return (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;
    }

    private static final class Terrain extends ClientTerrainSampler {
        long baseReads;
        final ClientColumnSample sample = new PredictionDecorationQueryTest().terrain().sampleSurface(0, 0);
        Terrain() { super(42, PredictionDecorationQueryTest.PROFILE); }
        @Override public ClientColumnSample sampleSurface(int x, int z) { return sample; }
        @Override boolean interiorTerrain() { baseReads++; return false; }
    }

    @Test void compareQueryPatternsWithEqualAnswers() throws Throwable {
        threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
        for (String stage : List.of("read-only-warm", "read-only-cold", "write-then-neighbors", "write-then-own-block")) {
            var original = new Fixture();
            var candidate = new Fixture();
            for (int round = -WARMUP; round < ROUNDS; round++) {
                Result oldResult, newResult;
                if ((round & 1) == 0) {
                    oldResult = run(original, stage, true);
                    newResult = run(candidate, stage, false);
                } else {
                    newResult = run(candidate, stage, false);
                    oldResult = run(original, stage, true);
                }
                assertEquals(oldResult.checksum, newResult.checksum, stage + " round=" + round);
                if (stage.startsWith("read-only"))
                    assertEquals(oldResult.baseReads, newResult.baseReads, "no writes must produce no artificial cache gain");
                if (round >= 0) rows.add(stage + "," + round + "," + OPERATIONS + ","
                        + oldResult.wall + "," + newResult.wall + "," + oldResult.cpu + "," + newResult.cpu + ","
                        + oldResult.bytes + "," + newResult.bytes + "," + oldResult.baseReads + "," + newResult.baseReads);
            }
            summarize(stage);
        }
        try (var sampler = nativeSampler()) {
            var level = new PredictionDecorationLevel(sampler, sampler, PredictionDecorationQueryTest.access, 0, 0);
            level.useDisplayTerrain(true);
            ClientColumnSample[] expected = new ClientColumnSample[64];
            for (int i = 0; i < expected.length; i++) expected[i] = level.column(i % 8, i / 8);
            for (int i = 0; i < expected.length; i++)
                assertEquals(expected[i], sampler.surfaceSample((int[]) nativeColumn.invokeExact(level, sampler, i % 8, i / 8)));
            for (int round = -WARMUP; round < ROUNDS; round++) {
                Result oldResult, newResult;
                if ((round & 1) == 0) {
                    oldResult = display(sampler, level, expected, true);
                    newResult = display(sampler, level, expected, false);
                } else {
                    newResult = display(sampler, level, expected, false);
                    oldResult = display(sampler, level, expected, true);
                }
                assertEquals(oldResult.checksum, newResult.checksum);
                if (round >= 0) rows.add("rust-display-summary," + round + "," + OPERATIONS + ","
                        + oldResult.wall + "," + newResult.wall + "," + oldResult.cpu + "," + newResult.cpu + ","
                        + oldResult.bytes + "," + newResult.bytes + ",0,0");
            }
            summarize("rust-display-summary");
        }
        var directory = Path.of(System.getenv("VSS_QUERY_LOCALITY_BENCH_OUTPUT"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("decoration-locality-rounds.csv"), rows);
    }

    private static RustTerrainSampler nativeSampler() throws Exception {
        assertTrue(RustTerrainSampler.available(), "packaged native backend must load before creating a sampler");
        var document = LithostitchedNativeTest.document();
        document.add("possible_biomes", new com.google.gson.JsonArray());
        var profile = PredictionDecorationQueryTest.PROFILE;
        return new RustTerrainSampler(RustWorldgenBackend.create(42, 0, document.toString()), profile,
                new ClientTerrainSampler(42, profile));
    }

    private Result display(RustTerrainSampler sampler, PredictionDecorationLevel level,
                           ClientColumnSample[] expected, boolean legacy) throws Throwable {
        long thread = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(thread), cpu = threads.getCurrentThreadCpuTime();
        long start = System.nanoTime(), checksum = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            int index = i & 63, x = index % 8, z = index / 8;
            ClientColumnSample sample;
            if (legacy) {
                sampler.handle();
                sample = sampler.surfaceSample((int[]) nativeColumn.invokeExact(level, sampler, x, z));
            }
            else sample = level.column(x, z);
            consumedSample = sample;
            checksum += sample.surfaceY() + sample.topBlockIndex();
        }
        long wall = System.nanoTime() - start;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        bytes = threads.getThreadAllocatedBytes(thread) - bytes;
        consumed = checksum;
        return new Result(wall, cpu, bytes, 0, checksum);
    }

    private static final class Fixture {
        final Terrain terrain = new Terrain();
        final PredictionDecorationLevel level =
                new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        final BlockPos[] blocks = new BlockPos[64], writes = new BlockPos[64];
        Fixture() {
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = new BlockPos(i % 8, 101, i / 8);
                writes[i] = new BlockPos(i % 8, 120, i / 8);
            }
        }
    }

    private Result run(Fixture fixture, String stage, boolean legacy) throws Throwable {
        if (stage.equals("read-only-cold")) legacyInvalidation.invokeExact(fixture.level);
        fixture.level.beginFeature();
        long thread = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(thread), cpu = threads.getCurrentThreadCpuTime();
        long reads = fixture.terrain.baseReads, start = System.nanoTime(), checksum = 0;
        boolean writes = stage.startsWith("write-");
        for (int i = 0; i < OPERATIONS; i++) {
            if (writes) {
                fixture.level.setBlock(fixture.writes[i & 63],
                        (i & 1) == 0 ? Blocks.OAK_LOG.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 0, 0);
                if (legacy) legacyInvalidation.invokeExact(fixture.level);
            }
            if (stage.equals("write-then-own-block")) {
                checksum += Block.getId(fixture.level.getBlockState(fixture.writes[i & 63]));
            } else {
                for (int neighbor = 1; neighbor <= 16; neighbor++) {
                    var position = fixture.blocks[(i + neighbor) & 63];
                    checksum += fixture.level.getHeight(Heightmap.Types.OCEAN_FLOOR, position.getX(), position.getZ());
                    checksum += Block.getId(fixture.level.getBlockState(position));
                }
            }
        }
        long wall = System.nanoTime() - start;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        bytes = threads.getThreadAllocatedBytes(thread) - bytes;
        reads = fixture.terrain.baseReads - reads;
        fixture.level.endFeature(true);
        consumed = checksum;
        return new Result(wall, cpu, bytes, reads, checksum);
    }

    private void summarize(String stage) {
        List<String[]> matching = rows.stream().skip(1).map(row -> row.split(","))
                .filter(row -> row[0].equals(stage)).toList();
        double oldMs = median(matching, 3) / 1e6, newMs = median(matching, 4) / 1e6;
        System.out.printf(Locale.ROOT,
                "QUERY_LOCALITY %s operations=%d oldMs=%.3f newMs=%.3f speedup=%.3f oldBytes=%.0f newBytes=%.0f oldBaseReads=%.0f newBaseReads=%.0f exact=true baselinePaysLocalBookkeeping=true%n",
                stage, OPERATIONS, oldMs, newMs, oldMs / newMs,
                median(matching, 7), median(matching, 8), median(matching, 9), median(matching, 10));
    }

    private static double median(List<String[]> rows, int column) {
        double[] values = rows.stream().mapToDouble(row -> Double.parseDouble(row[column])).toArray();
        Arrays.sort(values);
        return (values[values.length / 2 - 1] + values[values.length / 2]) / 2;
    }
    private record Result(long wall, long cpu, long bytes, long baseReads, long checksum) { }
}
