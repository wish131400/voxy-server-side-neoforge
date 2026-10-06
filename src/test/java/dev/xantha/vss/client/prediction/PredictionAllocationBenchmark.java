package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "VSS_GC_BENCH", matches = "1")
class PredictionAllocationBenchmark {
    private static final int WARMUP = 8, ROUNDS = 7, COLUMNS = 2048, PATH_CALLS = 65536;
    private static volatile Object consumed;
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private record Measurement(long nanos, long bytes) { }

    @Test void columnScratchComparedWithPreviousFullHeightAllocation() {
        threads.setThreadAllocatedMemoryEnabled(true);
        for (String scenario : new String[]{"empty", "surface", "cavern", "dense"}) {
            IntUnaryOperator blocks = switch (scenario) {
                case "empty" -> y -> -1;
                case "surface" -> y -> y < 72 ? 0 : -1;
                case "cavern" -> y -> y < 55 || y >= 120 && y < 128 || y >= 250 ? 0 : y < 63 ? 1 : -1;
                default -> y -> y & 1;
            };
            IntUnaryOperator fluid = id -> id;
            assertEquals(previousColumn(-64, 384, blocks, fluid),
                    PredictionColumnVolume.sample(-64, 384, blocks, fluid));
            long[] oldNanos = new long[ROUNDS], newNanos = new long[ROUNDS];
            long[] oldBytes = new long[ROUNDS], newBytes = new long[ROUNDS];
            for (int round = -WARMUP; round < ROUNDS; round++) {
                Measurement oldRun, newRun;
                if ((round & 1) == 0) {
                    oldRun = columns(true, blocks, fluid);
                    newRun = columns(false, blocks, fluid);
                } else {
                    newRun = columns(false, blocks, fluid);
                    oldRun = columns(true, blocks, fluid);
                }
                if (round >= 0) {
                    oldNanos[round] = oldRun.nanos; newNanos[round] = newRun.nanos;
                    oldBytes[round] = oldRun.bytes; newBytes[round] = newRun.bytes;
                }
            }
            assertTrue(median(newBytes) < median(oldBytes), scenario);
            System.out.printf(Locale.ROOT,
                    "GC_COLUMNS scenario=%s columns=%d oldBytesPerColumn=%.1f newBytesPerColumn=%.1f oldMs=%.3f newMs=%.3f exact=true%n",
                    scenario, COLUMNS, median(oldBytes) / (double) COLUMNS, median(newBytes) / (double) COLUMNS,
                    median(oldNanos) / 1e6, median(newNanos) / 1e6);
        }
    }

    @Test void regionPathsComparedWithPreviousRepeatedResolution() throws Exception {
        threads.setThreadAllocatedMemoryEnabled(true);
        var root = Path.of("build", "allocation-cache");
        for (int count : new int[]{1, 128}) {
            var keys = new PredictionDiskCache.Key[count];
            for (int i = 0; i < count; i++) keys[i] = PredictionDiskCache.Key.terrain((i - 64) * 32, -1, i & 3);
            try (var store = new PredictionRegionStorage(root)) {
                for (var key : keys) assertEquals(previousPath(root, key), store.path(key));
                long[] oldNanos = new long[ROUNDS], newNanos = new long[ROUNDS];
                long[] oldBytes = new long[ROUNDS], newBytes = new long[ROUNDS];
                for (int round = -WARMUP; round < ROUNDS; round++) {
                    Measurement oldRun, newRun;
                    if ((round & 1) == 0) {
                        oldRun = paths(root, store, keys, true); newRun = paths(root, store, keys, false);
                    } else {
                        newRun = paths(root, store, keys, false); oldRun = paths(root, store, keys, true);
                    }
                    if (round >= 0) {
                        oldNanos[round] = oldRun.nanos; newNanos[round] = newRun.nanos;
                        oldBytes[round] = oldRun.bytes; newBytes[round] = newRun.bytes;
                    }
                }
                assertTrue(median(newBytes) < median(oldBytes));
                System.out.printf(Locale.ROOT,
                        "GC_PATHS regions=%d calls=%d oldBytesPerCall=%.1f newBytesPerCall=%.1f oldMs=%.3f newMs=%.3f%n",
                        count, PATH_CALLS, median(oldBytes) / (double) PATH_CALLS, median(newBytes) / (double) PATH_CALLS,
                        median(oldNanos) / 1e6, median(newNanos) / 1e6);
            }
        }
    }

    @Test void warmRustGridWindowsAvoidTemporaryBatchArrays() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        threads.setThreadAllocatedMemoryEnabled(true);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L);
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new com.google.gson.JsonArray());
        try (var sampler = new RustTerrainSampler(RustWorldgenBackend.create(1, 0, doc.toString()), profile,
                new ClientTerrainSampler(1, profile))) {
            int gridSize = 18;
            var grid = new ClientColumnSample[gridSize * gridSize];
            for (int z = 0; z < gridSize; z += 8) for (int x = 0; x < gridSize; x += 8)
                sampler.sampleGridInPlace(-32 + x * 4, -32 + z * 4, 4,
                        Math.min(8, gridSize - x), Math.min(8, gridSize - z), grid, z * gridSize + x, gridSize, false);
            var expected = grid.clone();
            long computed = sampler.gridComputedPoints.sum();
            long[] oldNanos = new long[ROUNDS], newNanos = new long[ROUNDS];
            long[] oldBytes = new long[ROUNDS], newBytes = new long[ROUNDS];
            for (int round = -WARMUP; round < ROUNDS; round++) {
                Measurement oldRun, newRun;
                if ((round & 1) == 0) {
                    oldRun = grids(sampler, grid, gridSize, true); newRun = grids(sampler, grid, gridSize, false);
                } else {
                    newRun = grids(sampler, grid, gridSize, false); oldRun = grids(sampler, grid, gridSize, true);
                }
                assertArrayEquals(expected, grid);
                if (round >= 0) {
                    oldNanos[round] = oldRun.nanos; newNanos[round] = newRun.nanos;
                    oldBytes[round] = oldRun.bytes; newBytes[round] = newRun.bytes;
                }
            }
            assertEquals(computed, sampler.gridComputedPoints.sum(), "no native misses in a warm-grid overhead comparison");
            assertTrue(median(newBytes) < median(oldBytes));
            System.out.printf(Locale.ROOT,
                    "GC_RUST_WARM_GRID axis=%d grids=%d oldBytesPerGrid=%.1f newBytesPerGrid=%.1f oldMs=%.3f newMs=%.3f exact=true%n",
                    gridSize, COLUMNS, median(oldBytes) / (double) COLUMNS, median(newBytes) / (double) COLUMNS,
                    median(oldNanos) / 1e6, median(newNanos) / 1e6);
        }
    }

    private Measurement columns(boolean old, IntUnaryOperator blocks, IntUnaryOperator fluid) {
        long thread = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(thread), started = System.nanoTime();
        for (int i = 0; i < COLUMNS; i++)
            consumed = old ? previousColumn(-64, 384, blocks, fluid) : PredictionColumnVolume.sample(-64, 384, blocks, fluid);
        return new Measurement(System.nanoTime() - started, threads.getThreadAllocatedBytes(thread) - bytes);
    }

    private Measurement paths(Path root, PredictionRegionStorage store, PredictionDiskCache.Key[] keys, boolean old) {
        long thread = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(thread), started = System.nanoTime();
        for (int i = 0; i < PATH_CALLS; i++) {
            var key = keys[i % keys.length];
            consumed = old ? previousPath(root, key) : store.path(key);
        }
        return new Measurement(System.nanoTime() - started, threads.getThreadAllocatedBytes(thread) - bytes);
    }

    private Measurement grids(RustTerrainSampler sampler, ClientColumnSample[] grid, int gridSize, boolean old) {
        long thread = Thread.currentThread().getId();
        long bytes = threads.getThreadAllocatedBytes(thread), started = System.nanoTime();
        for (int i = 0; i < COLUMNS; i++) {
            for (int z = 0; z < gridSize; z += 8) for (int x = 0; x < gridSize; x += 8) {
                int width = Math.min(8, gridSize - x), height = Math.min(8, gridSize - z);
                if (old) {
                    var retained = new ClientColumnSample[width * height];
                    for (int row = 0; row < height; row++)
                        System.arraycopy(grid, (z + row) * gridSize + x, retained, row * width, width);
                    var batch = sampler.sampleGrid(-32 + x * 4, -32 + z * 4, 4, width, height, retained, false);
                    for (int row = 0; row < height; row++)
                        System.arraycopy(batch, row * width, grid, (z + row) * gridSize + x, width);
                } else sampler.sampleGridInPlace(-32 + x * 4, -32 + z * 4, 4,
                        width, height, grid, z * gridSize + x, gridSize, false);
            }
        }
        return new Measurement(System.nanoTime() - started, threads.getThreadAllocatedBytes(thread) - bytes);
    }

    private static Path previousPath(Path root, PredictionDiskCache.Key key) {
        return root.resolve(key.kind() + "-" + key.detail())
                .resolve((key.x() >> 5) + "_" + (key.z() >> 5) + ".vpr");
    }

    private static PredictionColumnVolume previousColumn(int min, int height, IntUnaryOperator blocks, IntUnaryOperator fluid) {
        int[] runs = new int[height * 4];
        int size = 0, start = min, previous = -1;
        for (int y = min; y <= min + height; y++) {
            if ((y & 15) == 0 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int block = y == min + height ? -1 : blocks.applyAsInt(y);
            if (block == previous) continue;
            if (previous >= 0) {
                runs[size++] = start; runs[size++] = y;
                runs[size++] = previous; runs[size++] = fluid.applyAsInt(previous);
            }
            start = y; previous = block;
        }
        return new PredictionColumnVolume(Arrays.copyOf(runs, size));
    }

    private static long median(long[] values) { var sorted = values.clone(); Arrays.sort(sorted); return sorted[sorted.length / 2]; }
}
