package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PredictionCoverageSweepTest {
    @Test void slowProbesResumeBothBandsAndResetOnMovementAndGeneration() {
        var clock = new AtomicLong();
        var sweep = new PredictionCoverageSweep(clock::get);
        int[] packed = new int[12];
        for (int x = 0; x < 12; x++) packed[x] = PredictionCoverageOffsets.pack(x, 0);
        var positions = new PredictionCoverageOffsets(packed);
        var seen = new ArrayList<Integer>();
        java.util.function.IntPredicate probe = i -> { seen.add(i); clock.addAndGet(1_000_000); return (i & 1) == 0; };
        var first = sweep.run(positions, 5, 0, 0, 1, () -> true, probe);
        assertEquals(List.of(0, 1, 6, 7), seen);
        assertEquals(4, first.checked()); assertEquals(2, first.present());
        for (int i = 0; i < 2; i++) sweep.run(positions, 5, 0, 0, 1, () -> true, probe);
        assertEquals(12, new java.util.HashSet<>(seen).size(), "budget exhaustion must not restart at the first column");
        seen.clear();
        sweep.run(positions, 5, -20, 30, 1, () -> true, probe);
        assertEquals(List.of(0, 1, 6, 7), seen);
        seen.clear();
        sweep.run(positions, 5, -20, 30, 2, () -> true, probe);
        assertEquals(List.of(0, 1, 6, 7), seen);
        seen.clear();
        sweep.run(positions, 5, -20, 30, 2, () -> false, probe);
        assertTrue(seen.isEmpty(), "old profile must stop without probing");
    }

    @Test void emptyAndSingleBandScansNeverRepeatAColumnWithinOnePass() {
        var sweep = new PredictionCoverageSweep(() -> 0);
        assertEquals(0, sweep.run(new PredictionCoverageOffsets(new int[0]), 16, 0, 0, 1, () -> true, i -> true).checked());
        var positions = new PredictionCoverageOffsets(new int[]{0, PredictionCoverageOffsets.pack(1, 0)});
        assertEquals(2, sweep.run(positions, 16, 0, 0, 1, () -> true, i -> true).checked());
        assertEquals(2, sweep.run(positions, 0, 0, 0, 1, () -> true, i -> true).checked());
    }
}
