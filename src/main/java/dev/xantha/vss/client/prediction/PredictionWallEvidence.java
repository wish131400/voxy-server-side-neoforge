package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.IntPredicate;

/** Only confirmed air cuts holes in the exterior heightfield's connecting walls. */
final class PredictionWallEvidence {
    static final int CHECKED = 1 << 25;
    // Versioned separately: older captures classified ores and fluids as air.
    static final int CAPTURED_OCCUPANCY = 1 << 24;
    static final int MAX_PROBES = 64;
    private record Probe(int index, int drop) { }
    private static final Comparator<Probe> PROBE_ORDER = Comparator.comparingInt(Probe::drop)
            .thenComparingInt(Probe::index);
    private PredictionWallEvidence() { }

    static boolean hasInterior(ClientColumnSample s) {
        if (PredictionExteriorColumns.profiled(s)) return true;
        return s != null && s.captured() && s.hasSurface()
                && (s.flags() & (CHECKED | CAPTURED_OCCUPANCY)) == (CHECKED | CAPTURED_OCCUPANCY)
                && s.floating() && s.hasLowerSpan()
                && s.lowerBottom() < s.lowerTop() && s.lowerTop() < s.surfaceBottom()
                && s.surfaceBottom() < s.surfaceY();
    }

    static boolean hasInterior(ClientColumnSample s, int spacing) {
        if (PredictionExteriorColumns.profiled(s)) return spacing <= PredictionExteriorColumns.spacing(s);
        return spacing == 1 && hasInterior(s);
    }

    static ClientColumnSample inspect(ClientColumnSample s, int minY, IntPredicate solid) {
        return inspect(s, minY, solid, s.flags() & ~CAPTURED_OCCUPANCY);
    }

    static ClientColumnSample inspectCaptured(ClientColumnSample s, int minY, IntPredicate occupied) {
        if (!s.captured()) throw new IllegalArgumentException("Occupancy requires a complete capture");
        return inspect(s, minY, occupied, s.flags() | CAPTURED_OCCUPANCY);
    }

    private static ClientColumnSample inspect(ClientColumnSample s, int minY, IntPredicate solid, int flags) {
        int y = s.surfaceY() - 1;
        int roofBottom = s.surfaceY();
        if (y >= minY && solid.test(y)) {
            while (y >= minY && solid.test(y)) y--;
            roofBottom = y + 1;
        }
        while (y >= minY && !solid.test(y)) y--;
        int lowerTop = y >= minY ? y + 1 : ClientColumnSample.NO_SPAN;
        while (y >= minY && solid.test(y)) y--;
        int lowerBottom = lowerTop == ClientColumnSample.NO_SPAN ? ClientColumnSample.NO_SPAN : y + 1;
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(), flags | CHECKED,
                s.groundFeatureKind(), s.underBlockIndex(), s.deepBlockIndex(), roofBottom,
                lowerTop, lowerBottom, minY);
    }

    static List<PredictionLodSeams.HeightSpan> intervals(ClientColumnSample s, int bottom, int top, int spacing) {
        var result = new ArrayList<PredictionLodSeams.HeightSpan>(2);
        if (PredictionExteriorColumns.profiled(s) && hasInterior(s, spacing)) {
            var v = s.volume();
            for (int i = 0; i < v.size(); i++) add(result, Math.max(bottom, v.bottom(i)), Math.min(top, v.top(i)));
            return result;
        }
        // One captured block column cannot describe an entire coarse cell.
        // Subtract only the bounded first air gap; strata below the second
        // solid run were not recorded and must retain the solid fallback.
        if (hasInterior(s, spacing)) {
            add(result, Math.max(bottom, s.surfaceBottom()), top);
            add(result, bottom, Math.min(top, s.lowerTop()));
        } else {
            // Coarse LODs have no occupancy probes. Keep their heightfield
            // closed until actual evidence can replace this approximation.
            add(result, bottom, top);
        }
        return result;
    }

    private static void add(List<PredictionLodSeams.HeightSpan> out, int bottom, int top) {
        if (top > bottom) out.add(new PredictionLodSeams.HeightSpan(bottom, top));
    }

    static List<PredictionLodSeams.HeightSpan> exposed(ClientColumnSample sample, ClientColumnSample neighbor,
                                                      int height, int neighborHeight, int spacing) {
        return exposed(sample, neighbor, height, neighborHeight, spacing, spacing);
    }

    static List<PredictionLodSeams.HeightSpan> exposed(ClientColumnSample sample, ClientColumnSample neighbor,
                                                      int height, int neighborHeight, int spacing, int neighborSpacing) {
        int bottom = hasInterior(neighbor, neighborSpacing)
                ? Math.min(neighborHeight, floor(neighbor)) : neighborHeight;
        if (height <= bottom) return List.of();
        var result = intervals(sample, bottom, height, spacing);
        if (!hasInterior(neighbor, neighborSpacing)) return result;
        // A cave exposes rock below its neighbor's top as well as above it.
        // Subtract the neighbor's solid spans, leaving shared air untouched.
        for (var solid : intervals(neighbor, bottom, Math.min(height, neighborHeight), neighborSpacing)) {
            var remaining = new ArrayList<PredictionLodSeams.HeightSpan>(3);
            for (var span : result) {
                if (solid.top() <= span.bottom() || solid.bottom() >= span.top()) remaining.add(span);
                else {
                    add(remaining, span.bottom(), Math.min(span.top(), solid.bottom()));
                    add(remaining, Math.max(span.bottom(), solid.top()), span.top());
                }
            }
            result = remaining;
        }
        return result;
    }

    static int floor(ClientColumnSample s) {
        return PredictionExteriorColumns.profiled(s) ? s.spanFloor() : s.lowerTop();
    }

    static int enrich(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ, ClientTerrainSampler sampler) {
        if (step > 8) return 0;
        var candidates = new PriorityQueue<Probe>(MAX_PROBES, PROBE_ORDER);
        for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) {
            int i = z * grid + x;
            var s = samples[i];
            if (!s.hasSurface() || s.captured() || (s.flags() & CHECKED) != 0) continue;
            int low = s.surfaceY();
            if (x > 0) low = Math.min(low, samples[i - 1].surfaceY());
            if (x + 1 < grid) low = Math.min(low, samples[i + 1].surfaceY());
            if (z > 0) low = Math.min(low, samples[i - grid].surfaceY());
            if (z + 1 < grid) low = Math.min(low, samples[i + grid].surfaceY());
            int drop = s.surfaceY() - low;
            if (drop <= 4 || candidates.size() == MAX_PROBES && drop <= candidates.peek().drop()) continue;
            if (candidates.size() == MAX_PROBES) candidates.poll();
            candidates.add(new Probe(i, drop));
        }
        // Spend the bounded column budget on overhangs before ordinary slopes.
        var ordered = new ArrayList<>(candidates);
        ordered.sort(PROBE_ORDER.reversed());
        for (var probe : ordered) {
            int i = probe.index(), x = i % grid, z = i / grid;
            samples[i] = sampler.wallEvidence(baseX + (x - 1) * step, baseZ + (z - 1) * step, samples[i]);
        }
        return ordered.size();
    }
}
