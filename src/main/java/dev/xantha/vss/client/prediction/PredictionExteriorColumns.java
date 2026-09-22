package dev.xantha.vss.client.prediction;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Local exterior occupancy. A coarse hole requires air across the entire cell footprint. */
final class PredictionExteriorColumns {
    static final int CHECKED = 1 << 23;
    static final int MAX_SPACING = 4;
    private static final int SPACING_SHIFT = 21, SPACING_MASK = 3 << SPACING_SHIFT;
    private PredictionExteriorColumns() { }

    static boolean profiled(ClientColumnSample s) {
        return s != null && (s.flags() & CHECKED) != 0 && s.volume() != null;
    }

    static boolean interiorVolume(ClientColumnSample s) {
        return s.volume() != null && !profiled(s);
    }

    static int spacing(ClientColumnSample s) { return 1 << ((s.flags() & SPACING_MASK) >>> SPACING_SHIFT); }

    static ClientColumnSample withProfile(ClientColumnSample s, PredictionColumnVolume volume, int floor) {
        return withProfile(s, volume, floor, 1);
    }

    static ClientColumnSample withProfile(ClientColumnSample s, PredictionColumnVolume volume, int floor, int step) {
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(),
                (s.flags() & ~SPACING_MASK) | CHECKED | Integer.numberOfTrailingZeros(step) << SPACING_SHIFT,
                s.groundFeatureKind(), s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(),
                s.lowerTop(), s.lowerBottom(), floor, volume);
    }

    /** Non-air occupancy includes ores and liquids; only proven air removes rock. */
    static ClientColumnSample capture(ClientColumnSample s, int minY, int floor, IntPredicate occupied) {
        if (!s.hasSurface() || s.surfaceY() <= minY || s.surfaceY() - (long) minY > PredictionColumnVolume.MAX_RUNS) return s;
        floor = Math.max(minY, Math.min(floor, s.surfaceY() - 1));
        final int bottom = floor;
        int block = PredictionMaterialPalette.wallDeepBlock(s);
        var volume = PredictionColumnVolume.sample(minY, s.surfaceY() - minY,
                y -> y < bottom || occupied.test(y) ? block : -1, ignored -> 0);
        // Preserve the sampled roof: an incompatible generator must not turn it into a floating plate.
        if (!volume.occupied(s.surfaceY() - 1, false)) return s;
        boolean gap = false;
        int cursor = floor;
        for (int i = 0; i < volume.size(); i++) {
            if (volume.bottom(i) > cursor) gap = true;
            cursor = Math.max(cursor, volume.top(i));
        }
        return withProfile(s, gap ? volume : null, floor);
    }

    /** Worker-only, cached with the terrain. Flood confirmed roofs so broad overhangs get undersides. */
    static int enrich(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ,
                      ClientTerrainSampler sampler, BooleanSupplier valid) {
        if (step > MAX_SPACING || sampler.interiorTerrain()) return 0;
        var queue = new ArrayDeque<Integer>();
        boolean[] queued = new boolean[samples.length];
        for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) {
            int i = z * grid + x;
            var s = samples[i];
            if (s == null || !s.hasSurface() || interiorVolume(s)) continue;
            boolean cliff = profiled(s);
            for (int dz = -1; dz <= 1 && !cliff; dz++) for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, zz = z + dz;
                if (xx < 0 || zz < 0 || xx >= grid || zz >= grid) continue;
                var other = samples[zz * grid + xx];
                if (other != null && s.surfaceY() - other.surfaceY() >= Math.max(8, step * 2)) cliff = true;
            }
            if (cliff) { queued[i] = true; queue.add(i); }
        }
        int changed = 0;
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            int i = queue.removeFirst(), x = i % grid, z = i / grid;
            var sample = samples[i];
            // Captured columns are authoritative. Their occupancy is attached by the extractor;
            // an older capture must never be replaced with generated holes.
            if (((sample.flags() & CHECKED) == 0 || spacing(sample) != step)
                    && !sample.captured()) {
                int wx = baseX + (x - 1) * step, wz = baseZ + (z - 1) * step;
                int min = sampler.profile().minY();
                int floor = Math.max(min, Math.min(sampler.seaLevel() - 16, sample.surfaceY() - 1));
                boolean[] occupied = sampler.exteriorFootprint(wx, wz, step, floor, sample.surfaceY(), valid);
                if (occupied != null) {
                    sample = capture(sample, min, floor, y -> occupied[y - floor]);
                    sample = withProfile(sample, sample.volume(), floor, step);
                    samples[i] = sample; changed++;
                }
            }
            if (!profiled(sample)) continue;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, zz = z + dz;
                if (xx < 0 || zz < 0 || xx >= grid || zz >= grid) continue;
                int next = zz * grid + xx;
                var s = samples[next];
                if (!queued[next] && s != null && s.hasSurface() && !interiorVolume(s)) {
                    queued[next] = true; queue.add(next);
                }
            }
        }
        return changed;
    }
}
