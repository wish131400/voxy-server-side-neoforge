package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static dev.xantha.vss.client.prediction.PredictionVegetationLocalInvalidationTest.field;
import static dev.xantha.vss.client.prediction.PredictionVegetationLocalInvalidationTest.key;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Paired actual feature replay; the baseline recreates the former global revision rejection. */
class PredictionVegetationLocalInvalidationBenchmark {
    @BeforeAll static void bootstrap() { PredictionVegetationLocalInvalidationTest.bootstrap(); }

    @Test @EnabledIfEnvironmentVariable(named = "VSS_VEGETATION_DIRTY_BENCH_OUTPUT", matches = ".+")
    void independentDirtyEditsDoNotRepeatFarFeatureReplay() throws Exception {
        var rows = new ArrayList<String>(List.of(
                "round,edits,oldNs,newNs,oldCpuNs,newCpuNs,oldBytes,newBytes,oldSamples,newSamples,oldFeatures,newFeatures"));
        long[] oldNs = new long[10], newNs = new long[10], oldCpu = new long[10], newCpu = new long[10];
        long[] oldBytes = new long[10], newBytes = new long[10];
        int edits = 12;
        for (int round = -4; round < oldNs.length; round++) {
            Result old, candidate;
            if ((round & 1) == 0) { old = exercise(true, edits); candidate = exercise(false, edits); }
            else { candidate = exercise(false, edits); old = exercise(true, edits); }
            assertEquals(old.checksum, candidate.checksum);
            assertEquals(edits * 2, old.features);
            assertEquals(edits, candidate.features);
            assertEquals(old.samples, candidate.samples * 2);
            if (round >= 0) {
                oldNs[round] = old.nanos; newNs[round] = candidate.nanos;
                oldCpu[round] = old.cpu; newCpu[round] = candidate.cpu;
                oldBytes[round] = old.bytes; newBytes[round] = candidate.bytes;
                rows.add(round + "," + edits + "," + old.nanos + "," + candidate.nanos + ","
                        + old.cpu + "," + candidate.cpu + "," + old.bytes + "," + candidate.bytes
                        + "," + old.samples + "," + candidate.samples + "," + old.features + "," + candidate.features);
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "VEGETATION_DIRTY edits=%d oldMs=%.3f newMs=%.3f oldCpuMs=%.3f newCpuMs=%.3f oldBytes=%.0f newBytes=%.0f oldFeatures=%d newFeatures=%d oldSamples=%d newSamples=%d exact=true syntheticTerrain=true artificialWait=false%n",
                edits, median(oldNs) / 1e6, median(newNs) / 1e6, median(oldCpu) / 1e6, median(newCpu) / 1e6,
                median(oldBytes), median(newBytes), edits * 2, edits, edits * 512, edits * 256);
        var output = Path.of(System.getenv("VSS_VEGETATION_DIRTY_BENCH_OUTPUT"));
        Files.createDirectories(output);
        Files.write(output.resolve("vegetation-dirty-rounds.csv"), rows);
    }

    @SuppressWarnings("unchecked") private static Result exercise(boolean legacy, int edits) throws Exception {
        var fixture = new PredictionVegetationLocalInvalidationTest.Fixture();
        var vegetation = fixture.vegetation();
        var columns = (VssLodSampleCache) field(vegetation, "terrainColumns");
        var chunks = (Map<Long, Map<BlockPos, BlockState>>) field(vegetation, "chunks");
        var cover = (Map<Long, Float>) field(vegetation, "forestCoverage");
        var size = PredictionVegetation.class.getDeclaredField("cachedBlocks"); size.setAccessible(true);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true); bean.setThreadCpuTimeEnabled(true);
        long id = Thread.currentThread().getId(), allocated = bean.getThreadAllocatedBytes(id), cpu = bean.getThreadCpuTime(id);
        long start = System.nanoTime();
        int checksum = 0;
        for (int edit = 0; edit < edits; edit++) {
            int x = 20 + edit * 4, z = -30;
            fixture.beforeFinish = () -> {
                vegetation.invalidate(0, 0);
                if (legacy) columns.clear();
            };
            var first = vegetation.chunk(x, z);
            fixture.beforeFinish = null;
            if (legacy) {
                // Before this change an unrelated dirty edit incremented cacheRevision,
                // so the in-flight job returned this same map without inserting it.
                synchronized (chunks) {
                    var removed = chunks.remove(key(x, z));
                    assertSame(first, removed);
                    cover.remove(key(x, z));
                    size.setInt(vegetation, size.getInt(vegetation) - removed.size());
                }
            }
            var second = vegetation.chunk(x, z);
            assertEquals(first, second);
            if (!legacy) assertSame(first, second);
            checksum = 31 * checksum + second.hashCode();
        }
        return new Result(System.nanoTime() - start, bean.getThreadCpuTime(id) - cpu,
                bean.getThreadAllocatedBytes(id) - allocated, fixture.samples.get(), fixture.features.get(), checksum);
    }

    private static double median(long[] values) {
        Arrays.sort(values); return (values[values.length / 2 - 1] + values[values.length / 2]) / 2.0;
    }
    private record Result(long nanos, long cpu, long bytes, int samples, int features, int checksum) { }
}
