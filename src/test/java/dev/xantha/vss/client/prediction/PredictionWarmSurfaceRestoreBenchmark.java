package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Paired full manager reentry: copied identical source/terrain/mesh records, only completion metadata differs. */
class PredictionWarmSurfaceRestoreBenchmark {
    @TempDir Path directory;

    @Test void compareLegacySurfaceReplayWithCompletedMeshRestore() throws Exception {
        assumeTrue("true".equals(System.getenv("VSS_SURFACE_RESTORE_BENCH")));
        PredictionWarmSurfaceRestoreTest.bootstrap();
        var fixture = new PredictionWarmSurfaceRestoreTest();
        fixture.settings();
        try {
            Path seed = directory.resolve("seed");
            var cold = PredictionWarmSurfaceRestoreTest.prepareCompleteSurfaceSeed(seed, 1);
            assertEquals(100, cold.placements());
            var legacyTimes = new ArrayList<PredictionWarmSurfaceRestoreTest.Result>();
            var completeTimes = new ArrayList<PredictionWarmSurfaceRestoreTest.Result>();
            for (int round = 0; round < 15; round++) {
                Path legacyRoot = directory.resolve("legacy-" + round), completeRoot = directory.resolve("complete-" + round);
                copy(seed, legacyRoot); copy(seed, completeRoot);
                PredictionWarmSurfaceRestoreTest.makeLegacy(legacyRoot, PredictionWarmSurfaceRestoreTest.key(1, false));
                PredictionWarmSurfaceRestoreTest.Result legacy, complete;
                if ((round & 1) == 0) {
                    legacy = PredictionWarmSurfaceRestoreTest.run(legacyRoot, 1, false, false);
                    complete = PredictionWarmSurfaceRestoreTest.run(completeRoot, 1, false, false);
                } else {
                    complete = PredictionWarmSurfaceRestoreTest.run(completeRoot, 1, false, false);
                    legacy = PredictionWarmSurfaceRestoreTest.run(legacyRoot, 1, false, false);
                }
                PredictionWarmSurfaceRestoreTest.assertSameOutput(cold, legacy);
                PredictionWarmSurfaceRestoreTest.assertSameOutput(cold, complete);
                assertEquals(100, legacy.sourceReads()); assertEquals(0, complete.sourceReads());
                assertEquals(0, legacy.placements()); assertEquals(0, complete.placements());
                assertEquals(0, legacy.heightCalls()); assertEquals(0, complete.heightCalls());
                if (round >= 5) { legacyTimes.add(legacy); completeTimes.add(complete); }
            }
            System.out.printf(Locale.ROOT,
                    "WARM_SURFACE_RESTORE paired=10 sources=100->0 placements=0->0 heightCalls=0->0 readyMedianMs=%.3f->%.3f surfaceReplayPackingMedianMs=%.3f->%.3f processCpuMedianMs=%.3f->%.3f completePackedOutputIdentical=true%n",
                    median(legacyTimes, 0), median(completeTimes, 0), median(legacyTimes, 1), median(completeTimes, 1),
                    median(legacyTimes, 2), median(completeTimes, 2));
            System.out.println("Synthetic deterministic marker decoration measures warm cache reentry; it is not a modpack FPS or cold terrain generation estimate.");
        } finally { fixture.restore(); }
    }

    private static double median(List<PredictionWarmSurfaceRestoreTest.Result> values, int field) {
        double[] sorted = values.stream().mapToDouble(value -> switch (field) {
            case 0 -> value.elapsedNanos(); case 1 -> value.stageNanos(); default -> value.cpuNanos();
        }).sorted().toArray();
        return (sorted[4] + sorted[5]) / 2e6;
    }

    private static void copy(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }
}
