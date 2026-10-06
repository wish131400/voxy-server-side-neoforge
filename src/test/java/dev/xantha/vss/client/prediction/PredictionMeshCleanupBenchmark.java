package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.*;

/** The former implementation is loaded only from an ignored one-run benchmark fixture. */
class PredictionMeshCleanupBenchmark {
    private static volatile long blackhole;
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }
    record Fixture(String name, ClientColumnSample[] samples, int[] colors, int grid, int spacing) { }
    record Result(double milliseconds, long bytes) { }

    @Test void compareCompleteMeshOutputAndConstructionCost() throws Throwable {
        assumeTrue("true".equals(System.getenv("VSS_MESH_CLEANUP_BENCH")));
        var type = MethodType.methodType(PredictionMesh.class, ClientColumnSample[].class, int[].class,
                int.class, int.class, int.class, int.class, boolean.class);
        var lookup = MethodHandles.lookup();
        var baseline = lookup.findStatic(Class.forName("dev.xantha.vss.client.prediction.PredictionMeshBuilderBaseline"), "build", type);
        var candidate = lookup.findStatic(PredictionMeshBuilder.class, "build", type);
        for (int grid : new int[]{2, 17, 34, 66}) for (int spacing : new int[]{1, 2, 4, 8, 64})
            equal(build(baseline, fixture("mixed", grid, spacing)), build(candidate, fixture("mixed", grid, spacing)));
        for (String name : List.of("dry", "ocean", "shoreline")) {
            var fixture = fixture(name, 66, 1);
            equal(build(baseline, fixture), build(candidate, fixture));
            var old = new ArrayList<Result>();
            var current = new ArrayList<Result>();
            for (int round = 0; round < 20; round++) {
                for (boolean changed : round % 2 == 0 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    var result = measure(changed ? candidate : baseline, fixture, 8);
                    if (round >= 8) (changed ? current : old).add(result);
                }
            }
            System.out.printf(Locale.ROOT,
                    "MESH_CLEANUP_AB fixture=%s grid=%d spacing=%d builds=8 baselineMedianMs=%.3f candidateMedianMs=%.3f baselineMedianBytes=%d candidateMedianBytes=%d outputEqual=true%n",
                    fixture.name(), fixture.grid(), fixture.spacing(), medianTime(old), medianTime(current), medianBytes(old), medianBytes(current));
        }
    }

    private static PredictionMesh build(MethodHandle method, Fixture input) throws Throwable {
        return (PredictionMesh) method.invokeExact(input.samples(), input.colors(), 63, 0xb22d78c5,
                input.spacing(), input.grid(), false);
    }

    private static Result measure(MethodHandle method, Fixture input, int repeats) throws Throwable {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().getId(), allocated = threads.getThreadAllocatedBytes(id), start = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            var mesh = build(method, input);
            blackhole += mesh.vertexCount() + mesh.waterVertexCount() + mesh.colors[0];
        }
        return new Result((System.nanoTime() - start) / 1e6, threads.getThreadAllocatedBytes(id) - allocated);
    }

    private static Fixture fixture(String name, int grid, int spacing) {
        var samples = new ClientColumnSample[grid * grid];
        int[] colors = new int[samples.length];
        for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) {
            int choice = Math.floorMod(x * 17 + z * 31, 5);
            int fluid = name.equals("dry") ? 0 : name.equals("ocean") ? 1 : choice == 0 ? 0 : choice == 1 ? 2 : 1;
            boolean ice = name.equals("mixed") && choice == 4;
            samples[z * grid + x] = PredictionMeshCleanupTest.column(fluid == 0 ? 65 : 58, fluid, ice);
            colors[z * grid + x] = 0xff63754d ^ ((x + z) & 3);
        }
        return new Fixture(name, samples, colors, grid, spacing);
    }

    private static void equal(PredictionMesh expected, PredictionMesh actual) {
        assertEquals(expected.vertexCount(), actual.vertexCount());
        assertEquals(expected.waterVertexCount(), actual.waterVertexCount());
        assertEquals(expected.cellAxis(), actual.cellAxis());
        assertArrayEquals(expected.positions, actual.positions);
        assertArrayEquals(expected.normals, actual.normals);
        assertArrayEquals(expected.colors, actual.colors);
        assertArrayEquals(expected.waterPositions, actual.waterPositions);
        assertArrayEquals(expected.waterNormals, actual.waterNormals);
        assertArrayEquals(expected.waterColors, actual.waterColors);
        assertArrayEquals(expected.waterCells, actual.waterCells);
        assertArrayEquals(expected.cellOffsets, actual.cellOffsets);
        assertArrayEquals(expected.cellCounts, actual.cellCounts);
        assertArrayEquals(expected.waterOffsets, actual.waterOffsets);
        assertArrayEquals(expected.waterCounts, actual.waterCounts);
        assertArrayEquals(expected.terrainEnds, actual.terrainEnds);
    }

    private static double medianTime(List<Result> results) {
        return results.stream().mapToDouble(Result::milliseconds).sorted().toArray()[results.size() / 2];
    }
    private static long medianBytes(List<Result> results) {
        return results.stream().mapToLong(Result::bytes).sorted().toArray()[results.size() / 2];
    }
}
