package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Warm query costs only; no claim about full decoration, GPU or live game speed. */
@EnabledIfEnvironmentVariable(named = "VSS_DECORATION_BENCH_OUTPUT", matches = ".+")
class PredictionDecorationQueryBenchmark {
    private static final int QUERIES = 1_000_000, ROUNDS = 20, WARMUP = 10;
    private static volatile long consumed;
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final List<String> rows = new ArrayList<>(List.of(
            "stage,round,queries,oldWallNs,newWallNs,oldCpuNs,newCpuNs,oldAllocatedBytes,newAllocatedBytes"));

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        PredictionDecorationQueryTest.bootstrap();
    }

    @Test void compareRepeatedQueriesWithIdenticalAnswers() throws Exception {
        threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
        var fixture = new PredictionDecorationQueryTest();
        var terrain = fixture.terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, -4, -7);
        var chunks = new HashMap<Long, ChunkAccess>();
        measure("virtual-chunk-lookup", () -> chunks(level, chunks, true), () -> chunks(level, chunks, false));
        var quartBiomes = new HashMap<Quart, Holder<Biome>>();
        measure("quart-biome-lookup", () -> quartBiomes(level, terrain, quartBiomes, true),
                () -> quartBiomes(level, terrain, quartBiomes, false));
        var positions = new BlockPos[64];
        for (int i = 0; i < positions.length; i++) positions[i] = new BlockPos(-64 + i % 8, 101, -112 + i / 8);
        measure("block-biome-lookup", () -> blockBiomes(level, positions, true), () -> blockBiomes(level, positions, false));
        measure("height-lookup", () -> heights(level, positions, true), () -> heights(level, positions, false));
        Path directory = Path.of(System.getenv("VSS_DECORATION_BENCH_OUTPUT"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("query-rounds.csv"), rows);
    }

    private void measure(String stage, LongSupplier previous, LongSupplier optimized) {
        for (int i = 0; i < WARMUP; i++) { assertEquals(previous.getAsLong(), optimized.getAsLong()); }
        long[] oldCpu = new long[ROUNDS], newCpu = new long[ROUNDS], oldBytes = new long[ROUNDS], newBytes = new long[ROUNDS];
        long[] oldWall = new long[ROUNDS], newWall = new long[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            Result oldResult, newResult;
            if ((round & 1) == 0) { oldResult = run(previous); newResult = run(optimized); }
            else { newResult = run(optimized); oldResult = run(previous); }
            assertEquals(oldResult.checksum, newResult.checksum, stage);
            oldCpu[round] = oldResult.cpu; newCpu[round] = newResult.cpu;
            oldWall[round] = oldResult.wall; newWall[round] = newResult.wall;
            oldBytes[round] = oldResult.allocated; newBytes[round] = newResult.allocated;
            rows.add(stage + "," + round + "," + QUERIES + "," + oldResult.wall + "," + newResult.wall
                    + "," + oldResult.cpu + "," + newResult.cpu + "," + oldResult.allocated + "," + newResult.allocated);
        }
        System.out.printf(Locale.ROOT, "DECORATION_QUERY %s queries=%d oldMs=%.3f newMs=%.3f oldCpuMs=%.3f newCpuMs=%.3f oldBytesPerQuery=%.3f newBytesPerQuery=%.3f%n",
                stage, QUERIES, median(oldWall) / 1e6, median(newWall) / 1e6, median(oldCpu) / 1e6, median(newCpu) / 1e6,
                median(oldBytes) / (double) QUERIES, median(newBytes) / (double) QUERIES);
    }

    private Result run(LongSupplier work) {
        long thread = Thread.currentThread().threadId();
        long allocated = threads.getThreadAllocatedBytes(thread), cpu = threads.getCurrentThreadCpuTime(), start = System.nanoTime();
        long checksum = work.getAsLong();
        long wall = System.nanoTime() - start;
        cpu = threads.getCurrentThreadCpuTime() - cpu;
        allocated = threads.getThreadAllocatedBytes(thread) - allocated;
        consumed = checksum;
        return new Result(wall, cpu, allocated, checksum);
    }

    private static long chunks(PredictionDecorationLevel level, Map<Long, ChunkAccess> cache, boolean previous) {
        long result = 0;
        for (int i = 0; i < QUERIES; i++) {
            int x = -6 + i % 5, z = -9 + i / 5 % 5;
            long packed = (long) x << 32 | z & 0xffffffffL;
            ChunkAccess chunk = previous ? cache.computeIfAbsent(packed, ignored -> level.getChunk(x, z, ChunkStatus.EMPTY, true))
                    : level.getChunk(x, z, ChunkStatus.EMPTY, true);
            result += chunk.getPos().toLong();
        }
        return result;
    }

    private static long quartBiomes(PredictionDecorationLevel level, ClientTerrainSampler terrain,
                                   Map<Quart, Holder<Biome>> cache, boolean previous) {
        long result = 0;
        for (int i = 0; i < QUERIES; i++) {
            int x = i % 32 - 16, y = i / 1024 % 4, z = i / 32 % 32 - 16;
            Holder<Biome> biome;
            if (previous) {
                var key = new Quart(x, y, z);
                biome = cache.get(key);
                if (biome == null) { biome = terrain.noiseBiome(x, y, z); cache.put(key, biome); }
            } else biome = level.getNoiseBiome(x, y, z);
            result += biome == PredictionDecorationQueryTest.warm ? 1 : 2;
        }
        return result;
    }

    private static long blockBiomes(PredictionDecorationLevel level, BlockPos[] positions, boolean previous) {
        long result = 0;
        for (int i = 0; i < QUERIES; i++) {
            var pos = positions[i & 63];
            var biome = previous ? level.getBiomeManager().getBiome(pos) : level.getBiome(pos);
            result += biome == PredictionDecorationQueryTest.warm ? 1 : 2;
        }
        return result;
    }

    private static long heights(PredictionDecorationLevel level, BlockPos[] positions, boolean previous) {
        long result = 0;
        for (int i = 0; i < QUERIES; i++) {
            var pos = positions[i & 63];
            result += previous ? uncachedHeight(level, pos) : level.getHeight(Heightmap.Types.OCEAN_FLOOR, pos.getX(), pos.getZ());
        }
        return result;
    }

    private static int uncachedHeight(PredictionDecorationLevel level, BlockPos pos) {
        var sample = level.column(pos.getX(), pos.getZ());
        int top = Math.max(sample.surfaceY(), sample.fluidY());
        var cursor = new BlockPos.MutableBlockPos(pos.getX(), top, pos.getZ());
        for (int y = Math.min(top - 1, level.getMaxBuildHeight() - 1); y >= level.getMinBuildHeight(); y--) {
            if (Heightmap.Types.OCEAN_FLOOR.isOpaque().test(level.getBlockState(cursor.setY(y)))) return y + 1;
        }
        return level.getMinBuildHeight();
    }

    private static long median(long[] values) { Arrays.sort(values); return (values[values.length / 2 - 1] + values[values.length / 2]) / 2; }
    private record Quart(int x, int y, int z) { }
    private record Result(long wall, long cpu, long allocated, long checksum) { }
}
