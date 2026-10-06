package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Isolates the duplicate identity calculation formerly performed by warm surface builds. */
class PredictionWarmSurfaceSignatureBenchmark {
    record Timing(long elapsed, long cpu, byte[] identity) { }

    @Test void compareDuplicateIdentityWithSingleIdentityAndVerifyCompleteMeshRecord() throws Exception {
        assumeTrue("true".equals(System.getenv("VSS_SURFACE_SIGNATURE_BENCH")));
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var mesh = PredictionMeshCodecTest.fixture(64);
        int grid = mesh.cellAxis() + 2;
        var samples = PredictionRefinementOptimizationsTest.samples(grid);
        int[] colors = new int[samples.length], foliage = new int[samples.length], water = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            colors[i] = 0xff000000 | i * 313 & 0xffffff;
            foliage[i] = 0xff000000 | i * 991 & 0xffffff;
            water[i] = 0xff000000 | i * 17 & 0xffffff;
        }
        byte[] resources = new byte[32];
        Arrays.fill(resources, (byte) 7);
        var buildings = cityTile();
        var oldElapsed = new ArrayList<Long>();
        var newElapsed = new ArrayList<Long>();
        var oldCpu = new ArrayList<Long>();
        var newCpu = new ArrayList<Long>();
        for (int round = 0; round < 13; round++) {
            Timing old, current;
            if ((round & 1) == 0) {
                old = measure(true, resources, samples, colors, foliage, water, buildings);
                current = measure(false, resources, samples, colors, foliage, water, buildings);
            } else {
                current = measure(false, resources, samples, colors, foliage, water, buildings);
                old = measure(true, resources, samples, colors, foliage, water, buildings);
            }
            assertArrayEquals(old.identity, current.identity);
            byte[] full = PredictionMeshCodec.signature(resources, samples, colors, foliage, water,
                    63, 0xff509050, 1, true, 3, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY);
            byte[] previous = PredictionMeshCodec.encode(mesh, full, old.identity);
            byte[] candidate = PredictionMeshCodec.encode(mesh, full, current.identity);
            assertArrayEquals(previous, candidate, "complete persisted geometry/material/seam record must match");
            var restored = PredictionMeshCodec.decode(candidate, full, mesh.cellAxis());
            assertNotNull(restored);
            assertArrayEquals(mesh.gpuPayload().restoreWords(), restored.gpuPayload().restoreWords());
            assertEquals(mesh.vertexCount(), restored.vertexCount());
            assertEquals(mesh.waterVertexCount(), restored.waterVertexCount());
            if (round >= 5) {
                oldElapsed.add(old.elapsed); newElapsed.add(current.elapsed);
                oldCpu.add(old.cpu); newCpu.add(current.cpu);
            }
        }
        System.out.printf(Locale.ROOT,
                "SURFACE_SIGNATURE tiles=64 columnsPerTile=%d baseHashes=128->64 medianMs=%.3f->%.3f threadCpuMs=%.3f->%.3f identicalCompleteMeshRecords=true%n",
                samples.length, median(oldElapsed) / 1e6, median(newElapsed) / 1e6,
                median(oldCpu) / 1e6, median(newCpu) / 1e6);
        System.out.println("This isolates identity hashing; it does not predict complete surface generation or game FPS speedup.");
    }

    private static LostCityPreview.Tile cityTile() {
        var model = new LostCityPreview.Model(new int[] {
                LostCityPreview.origin(0, 0, 0), LostCityPreview.extent(16, 24, 0, 1), 1
        });
        var chunk = new LostCityPreview.Chunk(LostCityPreview.BUILDING, 64, true,
                1, 1, 0, 0, List.of(new LostCityPreview.Placement(0, model)), model);
        return new LostCityPreview.Tile(0, 0, 1, List.of(chunk));
    }

    private static Timing measure(boolean duplicate, byte[] resources, ClientColumnSample[] samples,
            int[] colors, int[] foliage, int[] water, LostCityPreview.Tile buildings) {
        var threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isCurrentThreadCpuTimeSupported());
        if (!threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
        long cpu = threads.getCurrentThreadCpuTime(), started = System.nanoTime();
        byte[] result = null;
        for (int tile = 0; tile < 64; tile++) {
            if (duplicate) identity(resources, samples, colors, foliage, water, buildings);
            result = identity(resources, samples, colors, foliage, water, buildings);
        }
        return new Timing(System.nanoTime() - started, threads.getCurrentThreadCpuTime() - cpu, result);
    }

    private static byte[] identity(byte[] resources, ClientColumnSample[] samples, int[] colors,
            int[] foliage, int[] water, LostCityPreview.Tile buildings) {
        return PredictionMeshCodec.withCityBuildings(PredictionMeshCodec.baseSignature(resources, samples,
                colors, foliage, water, 63, 0xff509050, 1, true, 3), buildings);
    }

    private static double median(List<Long> values) {
        Collections.sort(values);
        return (values.get(3) + values.get(4)) / 2.0;
    }
}
