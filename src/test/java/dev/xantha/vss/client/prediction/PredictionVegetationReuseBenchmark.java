package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/** Opt-in paired benchmark of resident decoration display and finished-mesh identity. */
class PredictionVegetationReuseBenchmark {
    record Result(double ms, long bytes, int checksum) { }

    @Test void compareBoundedMeshingAndDenseFailure() {
        assumeTrue("1".equals(System.getenv("VSS_VEGETATION_REUSE_BENCH")));
        PredictionVegetationTest.bootstrap();
        try {
            for (int span : new int[]{32, 64}) {
                int grid = span + 1;
                var blocks = new HashMap<BlockPos, BlockState>();
                for (int z = 0; z < span; z += 2) for (int x = 0; x < span; x += 2)
                    for (int y = 80; y < 112; y += 2) blocks.put(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState());
                var plants = PredictionVegetation.Tile.of(blocks, 0, 0, span, 1, 1);
                var samples = PredictionRefinementOptimizationsTest.samples(grid);
                var old = new ArrayList<Result>(); var current = new ArrayList<Result>();
                int oldFailures = 0;
                for (int round = 0; round < 22; round++) {
                    for (boolean candidate : round % 2 == 0 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                        long id = Thread.currentThread().getId(), bytes = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
                        PredictionMesh mesh = null;
                        try {
                            mesh = candidate ? PredictionRefinementOptimizationsTest.build(samples, grid, plants)
                                    : PredictionMeshBuilder.build(samples, null, 63, 0, 1, grid,
                                            null, null, 0, 0, plants).compactForRendering();
                        } catch (PredictionMemoryBudget.MeshLimitException failure) {
                            assertFalse(candidate); oldFailures++;
                        }
                        var result = new Result((System.nanoTime() - start) / 1e6,
                                bean.getThreadAllocatedBytes(id) - bytes, mesh == null ? -1 : mesh.vertexCount());
                        if (round >= 12) (candidate ? current : old).add(result);
                    }
                }
                if (span == 32) assertEquals(old.get(0).checksum, current.get(0).checksum);
                System.out.printf(Locale.ROOT,
                        "VEGETATION_MESH span=%d blocks=%d oldMedianMs=%.3f newMedianMs=%.3f oldAllocatedBytes=%d newAllocatedBytes=%d oldFailures=%d newVertices=%d%n",
                        span, blocks.size(), median(old), median(current),
                        old.stream().mapToLong(Result::bytes).sorted().toArray()[old.size() / 2],
                        current.stream().mapToLong(Result::bytes).sorted().toArray()[current.size() / 2], oldFailures, current.get(0).checksum);
            }
        } finally { PredictionVegetationTest.restoreTags(); }
    }

    @Test @SuppressWarnings("unchecked") void compareRepeatedDisplayAndSignature() throws Exception {
        assumeTrue("1".equals(System.getenv("VSS_VEGETATION_REUSE_BENCH")));
        PredictionVegetationTest.bootstrap();
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean trees = config.predictionTrees;
        config.predictionTrees = true;
        try {
            var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                    ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384, "noise", "minecraft:overworld", 123L);
            var vegetation = new PredictionVegetation(new ClientTerrainSampler(42, profile));
            var field = PredictionVegetation.class.getDeclaredField("chunks"); field.setAccessible(true);
            var chunks = (Map<Long, Map<BlockPos, BlockState>>) field.get(vegetation);
            for (int cz = -1; cz <= 4; cz++) for (int cx = -1; cx <= 4; cx++) {
                var blocks = new HashMap<BlockPos, BlockState>();
                for (int z = 0; z < 16; z += 2) for (int x = 0; x < 16; x += 2)
                    for (int y = 80; y < 96; y += 2) blocks.put(new BlockPos(cx * 16 + x, y, cz * 16 + z),
                            y % 4 == 0 ? Blocks.OAK_LEAVES.defaultBlockState() : Blocks.BIRCH_LEAVES.defaultBlockState());
                chunks.put(PredictionVegetationDisplayCache.chunkKey(cx, cz), Map.copyOf(blocks));
            }
            Supplier<PredictionVegetation.Tile> previous = () -> vegetation.cachedDisplay(0, 0, 64, 1, (x, z) -> false);
            Supplier<PredictionVegetation.Tile> reused = () -> vegetation.cachedDisplayForRendering(0, 0, 64, 1, key -> false);
            var original = previous.get();
            var result = reused.get();
            assertEquals(original, result);
            assertArrayEquals(PredictionMeshCodec.decorationBytes(original), result.signatureCache().data(result));
            long start = System.nanoTime();
            var displayCache = new PredictionVegetationDisplayCache();
            var sources = new ArrayList<PredictionVegetationDisplayCache.Source>();
            chunks.forEach((key, value) -> sources.add(new PredictionVegetationDisplayCache.Source(key, value)));
            var cold = displayCache.tile(0, 0, 64, 1, true, sources, key -> false);
            assertEquals(original, cold);
            double coldMs = (System.nanoTime() - start) / 1e6;
            var oldResults = new ArrayList<Result>(); var newResults = new ArrayList<Result>();
            for (int round = 0; round < 24; round++) {
                for (boolean candidate : round % 2 == 0 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    Result measured = run(candidate ? reused : previous, 8);
                    if (round >= 12) (candidate ? newResults : oldResults).add(measured);
                }
            }
            assertEquals(oldResults.get(0).checksum, newResults.get(0).checksum);
            double oldMs = median(oldResults), newMs = median(newResults);
            long oldBytes = oldResults.stream().mapToLong(Result::bytes).sorted().toArray()[oldResults.size() / 2];
            long newBytes = newResults.stream().mapToLong(Result::bytes).sorted().toArray()[newResults.size() / 2];
            System.out.printf(Locale.ROOT,
                    "VEGETATION_REUSE blocks=%d sources=%d iterations=8 oldMedianMs=%.3f newMedianMs=%.3f speedup=%.2f oldAllocatedBytes=%d newAllocatedBytes=%d coldDisplayMs=%.3f %s%n",
                    original.blocks().size(), chunks.size(), oldMs, newMs, oldMs / newMs, oldBytes, newBytes, coldMs, vegetation.diagnostics());
        } finally { config.predictionTrees = trees; PredictionVegetationTest.restoreTags(); }
    }

    private static Result run(Supplier<PredictionVegetation.Tile> display, int iterations) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId(), bytes = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
        var samples = PredictionRefinementOptimizationsTest.samples(66);
        int[] colors = new int[samples.length];
        int checksum = 0;
        for (int i = 0; i < iterations; i++) {
            var tile = display.get();
            byte[] signature = PredictionMeshCodec.signature(new byte[32], samples, colors, colors, colors,
                    63, 0, 1, true, tile, PredictionSimpleVegetation.Result.EMPTY);
            checksum = 31 * checksum + Arrays.hashCode(signature);
        }
        return new Result((System.nanoTime() - start) / 1e6, bean.getThreadAllocatedBytes(id) - bytes, checksum);
    }

    private static double median(List<Result> results) {
        return results.stream().mapToDouble(Result::ms).sorted().toArray()[results.size() / 2];
    }
}
