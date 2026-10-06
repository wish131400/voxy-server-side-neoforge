package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;

/** Short-lived raw tints for aligned refinement grids, without retaining terrain volumes. */
final class PredictionTerrainColors implements AutoCloseable {
    static final int MAX_ENTRIES = 64;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    private final LinkedHashMap<PredictionTileManager.PredictionTileKey, Snapshot> entries =
            new LinkedHashMap<>(16, .75f, true);
    private long bytes;
    private boolean closed;

    synchronized Snapshot get(PredictionTileManager.PredictionTileKey key, long revision,
                              long captureEpoch, long fingerprint) {
        if (fingerprint == Long.MIN_VALUE) return null;
        Snapshot snapshot = entries.get(key);
        return snapshot != null && snapshot.revision == revision && snapshot.captureEpoch == captureEpoch
                && snapshot.fingerprint == fingerprint ? snapshot : null;
    }

    synchronized void put(PredictionTileManager.PredictionTileKey key, long revision, long captureEpoch,
                          long fingerprint, int grid, int step, ClientColumnSample[] samples,
                          int[] surface, int[] foliage, int[] water) {
        if (closed || fingerprint == Long.MIN_VALUE) return;
        long cost = 192L + 21L * samples.length;
        if (cost > MAX_BYTES) return;
        Snapshot snapshot = new Snapshot(revision, captureEpoch, fingerprint, grid, step, samples,
                surface, foliage, water, cost);
        Snapshot old = entries.put(key, snapshot);
        bytes += cost - (old == null ? 0 : old.bytes);
        while (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
            var iterator = entries.entrySet().iterator();
            bytes -= iterator.next().getValue().bytes;
            iterator.remove();
        }
    }

    synchronized void remove(PredictionTileManager.PredictionTileKey key) {
        Snapshot old = entries.remove(key);
        if (old != null) bytes -= old.bytes;
    }

    synchronized String diagnostics() { return "entries=" + entries.size() + ",bytes=" + bytes; }
    @Override public synchronized void close() { closed = true; entries.clear(); bytes = 0; }

    static final class Snapshot {
        final long revision, captureEpoch, fingerprint, bytes;
        final int grid, step;
        final int[] surfaceY, fluidY, surface, foliage, water;
        final byte[] flags;

        Snapshot(long revision, long captureEpoch, long fingerprint, int grid, int step,
                 ClientColumnSample[] samples, int[] surface, int[] foliage, int[] water, long bytes) {
            this.revision = revision; this.captureEpoch = captureEpoch; this.fingerprint = fingerprint;
            this.grid = grid; this.step = step; this.bytes = bytes;
            this.surface = surface.clone(); this.foliage = foliage.clone(); this.water = water.clone();
            surfaceY = new int[samples.length]; fluidY = new int[samples.length]; flags = new byte[samples.length];
            for (int i = 0; i < samples.length; i++) {
                ClientColumnSample sample = samples[i];
                surfaceY[i] = sample.surfaceY(); fluidY[i] = sample.fluidY();
                flags[i] = (byte) ((PredictionExteriorColumns.interiorVolume(sample) ? 0 : 1)
                        | (sample.approximate() ? 2 : 0) | (sample.fluid() == 1 && !sample.ice() ? 4 : 0));
            }
        }

        int match(int x, int z, int newStep, ClientColumnSample sample) {
            int offsetX = (x - VssLodLayout.SAMPLE_MARGIN) * newStep;
            int offsetZ = (z - VssLodLayout.SAMPLE_MARGIN) * newStep;
            if (offsetX % step != 0 || offsetZ % step != 0) return -1;
            int oldX = offsetX / step + VssLodLayout.SAMPLE_MARGIN;
            int oldZ = offsetZ / step + VssLodLayout.SAMPLE_MARGIN;
            if (oldX < 0 || oldZ < 0 || oldX >= grid || oldZ >= grid) return -1;
            int index = oldZ * grid + oldX;
            return (flags[index] & 3) == (sample.approximate() ? 3 : 1)
                    && surfaceY[index] == sample.surfaceY() ? index : -1;
        }

        boolean waterMatches(int index, ClientColumnSample sample) {
            return index >= 0 && sample.fluid() == 1 && !sample.ice()
                    && (flags[index] & 4) != 0 && fluidY[index] == sample.fluidY();
        }
    }
}
