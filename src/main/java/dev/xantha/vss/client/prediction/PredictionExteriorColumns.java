package dev.xantha.vss.client.prediction;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Full floating columns and local exterior overhang occupancy. */
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

    /** Sample one complete column per existing grid anchor at every LOD, including flat island tops.
     * Unlike local cliff evidence, this preserves all air below and between islands. It does not
     * scan the step*step footprint of distant cells or change the selected Rust/Java backend. */
    private static int enrichFloating(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ,
                                      ClientTerrainSampler sampler, BooleanSupplier valid) {
        int changed = 0;
        for (int i = 0; i < samples.length; i++) {
            var s = samples[i];
            if (s == null || s.captured() || s.floating() || interiorVolume(s)) continue;
            if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            var full = sampler.sampleInterior(baseX + (i % grid - 1) * step,
                    baseZ + (i / grid - 1) * step);
            // Old partial cliff volumes are not full-height evidence. Preserve preview provenance
            // while replacing geometry/materials with the complete column from the same backend.
            int flags = (s.flags() & ~(ClientColumnSample.FLAG_SURFACE_ONLY
                    | ClientColumnSample.FLAG_NO_SURFACE | CHECKED | SPACING_MASK)) | full.flags();
            samples[i] = new ClientColumnSample(full.surfaceY(), full.fluidY(),
                    s.surfaceY() == full.surfaceY() ? s.biomeIndex() : full.biomeIndex(),
                    full.topBlockIndex(), s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(),
                    full.fluid(), flags, s.groundFeatureKind(), full.underBlockIndex(), full.deepBlockIndex(),
                    full.surfaceBottom(), full.lowerTop(), full.lowerBottom(), full.spanFloor(), full.volume());
            changed++;
        }
        return changed;
    }

    /** Worker-only, cached with the terrain. Flood confirmed roofs so broad overhangs get undersides. */
    static int enrich(ClientColumnSample[] samples, int grid, int step, int baseX, int baseZ,
                      ClientTerrainSampler sampler, BooleanSupplier valid) {
        if (sampler.floatingTerrain()) return enrichFloating(samples, grid, step, baseX, baseZ, sampler, valid);
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
        int[] pending = new int[8], xs = new int[8], zs = new int[8], bottoms = new int[8], tops = new int[8];
        int[] requestIndex = new int[8];
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            int count = 0, requests = 0;
            while (count < pending.length && !queue.isEmpty()) {
                int index = queue.removeFirst();
                pending[count] = index;
                var sample = samples[index];
                if (((sample.flags() & CHECKED) == 0 || spacing(sample) != step) && !sample.captured()) {
                    int x = index % grid, z = index / grid;
                    xs[requests] = baseX + (x - 1) * step;
                    zs[requests] = baseZ + (z - 1) * step;
                    requestIndex[requests] = count;
                    tops[requests] = sample.surfaceY();
                    int min = sampler.profile().minY();
                    bottoms[requests] = Math.max(min, Math.min(sampler.seaLevel() - 16, tops[requests] - 1));
                    requests++;
                }
                count++;
            }
            boolean[][] footprints = requests == 0 ? null
                    : sampler.exteriorFootprints(xs, zs, step, bottoms, tops, requests, valid);
            for (int request = 0; request < requests; request++) {
                boolean[] occupied = footprints[request];
                if (occupied == null) continue;
                int index = pending[requestIndex[request]];
                var sample = samples[index];
                int min = sampler.profile().minY();
                int floor = bottoms[request];
                sample = capture(sample, min, floor, y -> occupied[y - floor]);
                sample = withProfile(sample, sample.volume(), floor, step);
                samples[index] = sample; changed++;
            }
            for (int pendingIndex = 0; pendingIndex < count; pendingIndex++) {
                int i = pending[pendingIndex], x = i % grid, z = i / grid;
                var sample = samples[i];
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
        }
        return changed;
    }
}
