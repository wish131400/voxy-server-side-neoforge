package dev.xantha.vss.client.prediction;

/** Tight bounds for consecutive packed quads; built with the mesh, never decoded in a frame loop. */
final class PredictionMeshletBounds {
    static final int QUADS = 256;
    final float[] values;

    PredictionMeshletBounds(int[] words) {
        int count = words.length / PredictionPackedMesh.STRIDE_INTS;
        values = new float[((count + QUADS - 1) / QUADS) * 6];
        for (int start = 0; start < count; start += QUADS) {
            int b = start / QUADS * 6;
            for (int a = 0; a < 3; a++) { values[b + a] = Float.POSITIVE_INFINITY; values[b + 3 + a] = Float.NEGATIVE_INFINITY; }
            for (int q = start; q < Math.min(count, start + QUADS); q++) {
                int p = q * PredictionPackedMesh.STRIDE_INTS, flags = words[p + 6];
                boolean fine = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
                int fluid = flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3;
                float xz = fine ? 1f / 16 : (1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
                float y = fine || fluid != 0 && (flags & PredictionPackedMesh.FLAG_FLUID_FINE_Y) != 0 ? 1f / 16 : 1f / 4;
                for (int c = 0; c < 4; c++) {
                    float xx = half(words[p + c / 2], c) * xz;
                    float zz = half(words[p + 2 + c / 2], c) * xz;
                    float yy = (half(words[p + 4 + c / 2], c) - 32768) * y;
                    values[b] = Math.min(values[b], xx); values[b + 3] = Math.max(values[b + 3], xx);
                    values[b + 1] = Math.min(values[b + 1], yy); values[b + 4] = Math.max(values[b + 4], yy);
                    values[b + 2] = Math.min(values[b + 2], zz); values[b + 5] = Math.max(values[b + 5], zz);
                }
            }
        }
    }

    private static int half(int word, int corner) { return word >>> ((corner & 1) * 16) & 65535; }
    static int end(int first, int end) { return Math.min(end, (first / QUADS + 1) * QUADS); }
    static int count(PredictionDrawRanges ranges) {
        int count = 0;
        for (int i = 0; i < ranges.first.length; i++)
            count += (ranges.first[i] + ranges.count[i] - 1) / QUADS - ranges.first[i] / QUADS + 1;
        return count;
    }
}
