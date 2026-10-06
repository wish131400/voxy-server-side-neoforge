package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static dev.xantha.vss.client.prediction.RustVegetationDeltaUploadTest.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Measures the complete mixed Java/native feature round trip, including JNI and native height queries. */
class RustVegetationDeltaUploadBenchmark {
    @BeforeAll static void bootstrap() { RustVegetationDeltaUploadTest.bootstrap(); }

    @Test @EnabledIfEnvironmentVariable(named = "VSS_VEGETATION_DELTA_BENCH_OUTPUT", matches = ".+")
    void compareMixedFeatureRoundTripsWithDenseFinalOracles() throws Exception {
        int cycles = 24;
        var rows = new ArrayList<String>(List.of("round,cycles,oldNs,newNs,oldCpuNs,newCpuNs,oldBytes,newBytes,oldEditsUp,newEditsUp,finalBlocks"));
        long[] oldNs = new long[10], newNs = new long[10], oldCpu = new long[10], newCpu = new long[10];
        long[] oldBytes = new long[10], newBytes = new long[10], oldEdits = new long[10], newEdits = new long[10];
        try (var fixture = new Fixture()) {
            for (int round = -4; round < oldNs.length; round++) {
                Result old, candidate;
                if ((round & 1) == 0) { old = exercise(fixture, true, cycles); candidate = exercise(fixture, false, cycles); }
                else { candidate = exercise(fixture, false, cycles); old = exercise(fixture, true, cycles); }
                assertEquals(old.placed, candidate.placed, "all Java/native committed overlays must match");
                assertArrayEquals(old.nativeBlocks, candidate.nativeBlocks, "all native cells must match the former full upload");
                assertArrayEquals(old.heights, candidate.heights, "four complete final heightmap grids must match");
                assertTrue(candidate.edits <= cycles * 4L);
                assertTrue(old.edits >= 8192L * cycles);
                if (round >= 0) {
                    oldNs[round] = old.nanos; newNs[round] = candidate.nanos;
                    oldCpu[round] = old.cpu; newCpu[round] = candidate.cpu;
                    oldBytes[round] = old.bytes; newBytes[round] = candidate.bytes;
                    oldEdits[round] = old.edits; newEdits[round] = candidate.edits;
                    rows.add(round + "," + cycles + "," + old.nanos + "," + candidate.nanos + ","
                            + old.cpu + "," + candidate.cpu + "," + old.bytes + "," + candidate.bytes
                            + "," + old.edits + "," + candidate.edits + "," + candidate.placed.size());
                }
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "VEGETATION_DELTA initialPlaced=8192 pendingPerCycle=4 cycles=%d oldMs=%.3f newMs=%.3f oldCpuMs=%.3f newCpuMs=%.3f oldBytes=%.0f newBytes=%.0f oldEditsUp=%.0f newEditsUp=%.0f nativeCellsExact=true javaMapExact=true heightmapsExact=true sharedSampler=true syntheticTerrain=true wholeRoundTrip=true%n",
                cycles, median(oldNs) / 1e6, median(newNs) / 1e6, median(oldCpu) / 1e6, median(newCpu) / 1e6,
                median(oldBytes), median(newBytes), median(oldEdits), median(newEdits));
        var output = Path.of(System.getenv("VSS_VEGETATION_DELTA_BENCH_OUTPUT"));
        Files.createDirectories(output); Files.write(output.resolve("vegetation-delta-rounds.csv"), rows);
    }

    private static Result exercise(Fixture fixture, boolean legacy, int cycles) throws Exception {
        try (var run = fixture.run(false, 32)) {
            // Both variants create the same proxy, transfer the same full first snapshot,
            // and warm all four native height predicates before the measured interval.
            for (int feature = 0; feature < 4; feature++) assertNativePlace(fixture, run, feature);
            var probes = run.level().placed().entrySet().stream()
                    .filter(entry -> entry.getValue().is(Blocks.DANDELION))
                    .map(Map.Entry::getKey).sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY)).toList();
            assertEquals(4, probes.size());
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true); bean.setThreadCpuTimeEnabled(true);
            long id = Thread.currentThread().getId(), edits = editsUp(), allocated = bean.getThreadAllocatedBytes(id), cpu = bean.getThreadCpuTime(id);
            long start = System.nanoTime();
            for (int cycle = 0; cycle < cycles; cycle++) {
                if (cycle % 3 == 0) run.level().beginStructure(); else run.level().beginFeature();
                for (var probe : probes) {
                    int y = run.level().getHeight(HEIGHTS[0], probe.getX(), probe.getZ()) - 1;
                    var state = switch (cycle % 3) {
                        case 0 -> Blocks.AIR.defaultBlockState();
                        case 1 -> Blocks.WATER.defaultBlockState();
                        default -> Blocks.DIRT.defaultBlockState();
                    };
                    run.level().setBlock(new BlockPos(probe.getX(), y, probe.getZ()), state, 19, 0);
                }
                run.level().endFeature(true);
                if (legacy) legacyFullUploadOnNextPendingChange(run.stage());
                for (int feature = 0; feature < 4; feature++) assertNativePlace(fixture, run, feature);
                assertTrue(run.level().pendingUploads().isEmpty());
            }
            long nanos = System.nanoTime() - start, cpuNanos = bean.getThreadCpuTime(id) - cpu;
            long bytes = bean.getThreadAllocatedBytes(id) - allocated, uploaded = editsUp() - edits;
            var snapshot = snapshot(run.stage());
            var heights = assertHeightOracle(fixture, run.level(), snapshot);
            return new Result(nanos, cpuNanos, bytes, uploaded, Map.copyOf(run.level().placed()), snapshot.blocks(), heights);
        }
    }
    private static double median(long[] values) {
        Arrays.sort(values); return (values[values.length / 2 - 1] + values[values.length / 2]) / 2.0;
    }
    record Result(long nanos, long cpu, long bytes, long edits, Map<BlockPos, BlockState> placed, int[] nativeBlocks, int[] heights) { }
}
