package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/** Resumable near/far scans; used only on the single profile executor. */
final class PredictionCoverageSweep {
    private List<int[]> offsets;
    private int nearEnd, nearCursor, farCursor, centerX, centerZ, radius;
    private long generation = Long.MIN_VALUE;
    private final LongSupplier clock;
    PredictionCoverageSweep() { this(System::nanoTime); }
    PredictionCoverageSweep(LongSupplier clock) { this.clock = clock; }

    record Result(int checked, int present, long nanos) { }

    Result run(List<int[]> positions, int nearRadius, int x, int z, long epoch,
               BooleanSupplier current, IntPredicate probe) {
        if (offsets != positions || radius != nearRadius || generation != epoch || centerX != x || centerZ != z) {
            offsets = positions; radius = nearRadius; generation = epoch; centerX = x; centerZ = z;
            // The list is sorted by squared distance. New views start nearby.
            int low = 0, high = positions.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                int[] p = positions.get(mid);
                if ((long) p[0] * p[0] + (long) p[1] * p[1] <= (long) nearRadius * nearRadius) low = mid + 1;
                else high = mid;
            }
            nearEnd = low; nearCursor = 0; farCursor = nearEnd;
        }
        long start = clock.getAsLong();
        int checked = 0, present = 0;
        // Each band gets its own budget so an expensive near band cannot
        // permanently starve distant stored columns. A single probe may overrun.
        for (int band = 0; band < 2; band++) {
            long bandStart = clock.getAsLong();
            int count = Math.min(band == 0 ? nearEnd : positions.size() - nearEnd, band == 0 ? 2048 : 8192);
            for (int i = 0; i < count; i++) {
                if (!current.getAsBoolean()) return new Result(checked, present, clock.getAsLong() - start);
                if (i > 0 && clock.getAsLong() - bandStart >= 2_000_000L) break;
                int at = band == 0 ? nearCursor : farCursor;
                if (probe.test(at)) present++;
                checked++;
                if (band == 0) nearCursor = (at + 1) % nearEnd;
                else farCursor = at + 1 == positions.size() ? nearEnd : at + 1;
            }
        }
        return new Result(checked, present, clock.getAsLong() - start);
    }
}
