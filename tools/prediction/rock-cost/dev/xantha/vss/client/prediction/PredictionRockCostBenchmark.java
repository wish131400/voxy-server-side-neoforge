package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.PositionUtil;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic CPU benchmark; does not measure live game FPS or GPU duration. */
class PredictionRockCostBenchmark {
    private static volatile int consumed;

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void meshBeforeAfter() {
        for (String scene : new String[]{"flat", "slopes", "scattered64", "scattered256", "coherent256", "allCave"}) {
            var samples = new ClientColumnSample[66 * 66];
            Arrays.fill(samples, PredictionSimpleVegetationTest.sample(120));
            var cave = PredictionCaveInteriorTest.cave();
            if (scene.equals("slopes")) {
                for (int z = 0; z < 66; z++) for (int x = 0; x < 66; x++)
                    samples[z * 66 + x] = PredictionSimpleVegetationTest.sample(120 + (x * 7 + z * 3) % 30);
            } else if (scene.equals("allCave")) Arrays.fill(samples, cave);
            else if (scene.equals("coherent256")) {
                for (int z = 20; z < 36; z++) for (int x = 20; x < 36; x++) samples[z * 66 + x] = cave;
            } else if (scene.startsWith("scattered")) {
                int count = Integer.parseInt(scene.substring(9));
                for (int k = 0; k < count; k++) {
                    int cell = k * 67 % 4096;
                    samples[(cell / 64 + 1) * 66 + cell % 64 + 1] = cave;
                }
            }
            long[][] times = new long[2][80];
            int[] quads = new int[2];
            for (int iteration = 0; iteration < 120; iteration++) for (int order = 0; order < 2; order++) {
                int mode = (iteration + order) % 2;
                long start = System.nanoTime();
                var mesh = (mode == 0
                        ? PredictionMeshBuilderBeforeRock.build(samples, null, 63, 0, 1, 66, false)
                        : PredictionMeshBuilder.build(samples, null, 63, 0, 1, 66, false)).compactForRendering();
                quads[mode] = mesh.packed().quadCount();
                consumed = quads[mode];
                if (iteration >= 40) times[mode][iteration - 40] = System.nanoTime() - start;
            }
            for (long[] values : times) Arrays.sort(values);
            if (scene.equals("flat") || scene.equals("slopes") || scene.equals("allCave")) assertEquals(quads[0], quads[1]);
            else assertTrue(quads[1] > quads[0]);
            System.out.printf(Locale.ROOT,
                    "ROCK_MESH scene=%s oldP50Ms=%.4f newP50Ms=%.4f oldP95Ms=%.4f newP95Ms=%.4f oldQuads=%d newQuads=%d extraPackedKiB=%.3f%n",
                    scene, median(times[0]), median(times[1]), times[0][75] / 1e6, times[1][75] / 1e6,
                    quads[0], quads[1], (quads[1] - quads[0]) * 48 / 1024.0);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void actualAsyncCoverageSnapshot() throws Exception {
        var field = ClientPredictionState.class.getDeclaredField("exactCoverage");
        field.setAccessible(true);
        var cache = (PredictionExactCoverageIndex) field.get(null);
        assertEquals(0, cache.pageCount(), "isolated benchmark JVM must start with empty coverage cache");
        try {
            for (int entries : new int[]{10000, 66049, 262144, 1048576}) {
                cache.clear();
                int axis = (int) Math.ceil(Math.sqrt(entries));
                for (int i = 0; i < entries; i++) {
                    int x = i % axis - axis / 2, z = i / axis - axis / 2;
                    cache.confirm(Level.OVERWORLD, x, z, System.nanoTime() - 10_000_000_000L);
                }
                for (int radius : new int[]{128, 512}) {
                    long[] times = new long[80];
                    int bytes = 0;
                    for (int i = 0; i < 110; i++) {
                        long start = System.nanoTime();
                        var mask = ClientPredictionState.exactCoverageSnapshot(Level.OVERWORLD, 0, 0, radius).join();
                        long elapsed = System.nanoTime() - start;
                        assertEquals((byte) 255, mask.columns()[(0 - mask.originZ()) * mask.size() - mask.originX()]);
                        bytes = mask.columns().length;
                        consumed = bytes;
                        if (i >= 30) times[i - 30] = elapsed;
                    }
                    Arrays.sort(times);
                    System.out.printf(Locale.ROOT,
                            "ROCK_MASK entries=%d radius=%d bytes=%d asyncP50Ms=%.4f asyncP95Ms=%.4f fourUpdatesMsPerSecond=%.4f%n",
                            entries, radius, bytes, median(times), times[75] / 1e6, median(times) * 4);
                }
            }
        } finally { cache.clear(); }
    }

    private static double median(long[] sorted) { return (sorted[39] + sorted[40]) / 2e6; }
}
