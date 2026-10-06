package dev.xantha.vss.client.prediction;

/** Worker-built census of rectangles that can retain all four corners exactly.
 * The 12-byte coordinate estimate excludes mixed-format addressing and alignment;
 * it is a potential saving, not an implemented GPU format. */
record PredictionGeometryStats(long quads, long rectangles, long waterRectangles) {
    static PredictionGeometryStats measure(int[] words) {
        long rectangles = 0, water = 0;
        for (int p = 0; p < words.length; p += PredictionPackedMesh.STRIDE_INTS) {
            if (!rectangle(words, p)) continue;
            rectangles++;
            if ((words[p + 6] >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) != 0) water++;
        }
        return new PredictionGeometryStats(words.length / PredictionPackedMesh.STRIDE_INTS, rectangles, water);
    }

    private static boolean rectangle(int[] words, int p) {
        int flags = words[p + 6];
        if ((flags & PredictionPackedMesh.FLAG_MODEL_UV) != 0
                && (flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) == 0) return false;
        int normal = switch (flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3) {
            case 1 -> 0; case 2 -> 2; default -> 1;
        };
        int edge1 = -1, edge3 = -1;
        for (int axis = 0; axis < 3; axis++) {
            int a = coordinate(words, p, axis, 0), b = coordinate(words, p, axis, 1);
            int c = coordinate(words, p, axis, 2), d = coordinate(words, p, axis, 3);
            if (axis == normal) { if (a != b || a != c || a != d) return false; continue; }
            if (b != a) { if (edge1 >= 0) return false; edge1 = axis; }
            if (d != a) { if (edge3 >= 0) return false; edge3 = axis; }
            if (c != b + d - a) return false;
        }
        return edge1 >= 0 && edge3 >= 0 && edge1 != edge3;
    }

    private static int coordinate(int[] words, int p, int axis, int corner) {
        int start = axis == 0 ? 0 : axis == 2 ? 2 : 4;
        return words[p + start + corner / 2] >>> (corner % 2 * 16) & 65535;
    }
    long coordinateSavingUpperBound() { return rectangles * 12; }
}
