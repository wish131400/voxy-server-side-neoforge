package dev.xantha.vss.client.prediction;

/** Bounded, allocation-free merging before triangle workspace admission. */
final class PredictionQuadMerger {
    private PredictionQuadMerger() { }

    // Only complete, uniformly coloured axis-aligned rectangles. Cutouts,
    // model normals, AO gradients and different material IDs keep their faces.
    static int mergeLast(float[] p, float[] n, int[] colors, int count, int cellStart, float[] bounds) {
        int last = count - 6;
        int axis = rectangle(p, n, colors, last, bounds, 0);
        if (axis < 0) return count;
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        for (int prior = last - 6, end = Math.max(cellStart, last - 64 * 6); prior >= end; prior -= 6) {
            if (colors[prior] != colors[last] || n[prior * 3 + axis] != n[last * 3 + axis]
                    || rectangle(p, n, colors, prior, bounds, 5) != axis || bounds[0] != bounds[5]) continue;
            boolean alongU = bounds[3] == bounds[8] && bounds[4] == bounds[9]
                    && (bounds[1] == bounds[7] || bounds[2] == bounds[6]);
            boolean alongV = bounds[1] == bounds[6] && bounds[2] == bounds[7]
                    && (bounds[3] == bounds[9] || bounds[4] == bounds[8]);
            if (!alongU && !alongV) continue;
            int coordinate = alongU ? u : v;
            int low = alongU ? 1 : 3;
            float oldMin = bounds[low + 5], oldMax = bounds[low + 6];
            float min = Math.min(bounds[low], oldMin), max = Math.max(bounds[low + 1], oldMax);
            for (int i = prior; i < prior + 6; i++) {
                int at = i * 3 + coordinate;
                p[at] = p[at] == oldMin ? min : max;
            }
            return last;
        }
        return count;
    }

    private static int rectangle(float[] p, float[] n, int[] colors, int first, float[] b, int out) {
        int start = first * 3, axis = -1;
        for (int d = 0; d < 3; d++) {
            if (n[start + d] == 0) continue;
            if (axis != -1 || Math.abs(n[start + d]) != 1) return -1;
            axis = d;
        }
        if (axis == -1) return -1;
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        for (int i = 0; i < 6; i++) {
            if (colors[first + i] != colors[first] || p[start + i * 3 + axis] != p[start + axis]) return -1;
            for (int d = 0; d < 3; d++) if (n[start + i * 3 + d] != n[start + d]) return -1;
        }
        for (int d = 0; d < 3; d++)
            if (p[start + d] != p[start + 9 + d] || p[start + 6 + d] != p[start + 12 + d]) return -1;
        float u0 = Math.min(p[start + u], p[start + 6 + u]);
        float u1 = Math.max(p[start + u], p[start + 6 + u]);
        float v0 = Math.min(p[start + v], p[start + 6 + v]);
        float v1 = Math.max(p[start + v], p[start + 6 + v]);
        if (u0 == u1 || v0 == v1) return -1;
        // The remaining corners must be the two other rectangle corners.
        if (!((p[start + 3 + u] == p[start + u] && p[start + 3 + v] == p[start + 6 + v]
                && p[start + 15 + u] == p[start + 6 + u] && p[start + 15 + v] == p[start + v])
                || (p[start + 3 + u] == p[start + 6 + u] && p[start + 3 + v] == p[start + v]
                && p[start + 15 + u] == p[start + u] && p[start + 15 + v] == p[start + 6 + v]))) return -1;
        b[out] = p[start + axis]; b[out + 1] = u0; b[out + 2] = u1; b[out + 3] = v0; b[out + 4] = v1;
        return axis;
    }
}
