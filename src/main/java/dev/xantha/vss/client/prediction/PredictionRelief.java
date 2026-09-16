package dev.xantha.vss.client.prediction;

/** Immutable worker-computed error summary; planning never scans column grids. */
record PredictionRelief(int maxStep, boolean structure, int spacing) {
    static PredictionRelief of(PredictionTileManager.PredictionTile tile) {
        int axis = tile.cellAxis() + 1;
        var samples = tile.samples();
        int jump = 0;
        boolean structure = false;
        if (samples.length != axis * axis) return new PredictionRelief(0, false, tile.spacingBlocks());
        for (int z = 0; z < axis; z++) for (int x = 0; x < axis; x++) {
            int i = z * axis + x;
            var sample = samples[i];
            if (sample == null || !sample.hasSurface()) continue;
            structure |= sample.structureIndex() != 0;
            if (x > 0) jump = Math.max(jump, difference(sample, samples[i - 1]));
            if (z > 0) jump = Math.max(jump, difference(sample, samples[i - axis]));
        }
        return new PredictionRelief(jump, structure, tile.spacingBlocks());
    }

    private static int difference(ClientColumnSample a, ClientColumnSample b) {
        if (b == null || !b.hasSurface()) return 0;
        return (int)Math.min(Integer.MAX_VALUE, Math.abs((long)a.surfaceY() - b.surfaceY()));
    }

    boolean needsRefinement() { return structure || maxStep > Math.max(8, spacing); }
}
