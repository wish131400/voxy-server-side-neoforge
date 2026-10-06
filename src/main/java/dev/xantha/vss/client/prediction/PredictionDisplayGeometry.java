package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Worker-built coplanar leaf/facade clusters. Original near geometry and disk records stay exact. */
final class PredictionDisplayGeometry {
    record Result(int[] words, PredictionDrawRanges[][] ranges) { }
    private static final int STRIDE = PredictionPackedMesh.STRIDE_INTS;
    private static final int[] COLOR_WORDS = {7, 9, 10, 11};
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);
    private static final class Workspace {
        final int[][] words = new int[4][], roots = new int[4][], members = new int[4][];
        final int[] recent = new int[64];
        void ensure(int size) {
            for (int tier = 1; tier <= 3; tier++) if (roots[tier] == null || roots[tier].length < size) {
                words[tier] = new int[size * STRIDE]; roots[tier] = new int[size]; members[tier] = new int[size];
            }
        }
    }
    private PredictionDisplayGeometry() { }

    static Result build(int[] source, int[] first, int[] count, int[][] retained, int axis) {
        if (Boolean.getBoolean("vss.disablePredictionDisplayLod")) return new Result(source, null);
        int total = Arrays.stream(count).sum();
        if (total < 128 || total > 131072) return new Result(source, null);
        Workspace workspace = WORKSPACE.get(); workspace.ensure(total);
        int[][] variants = new int[4][];
        int[][] parent = new int[4][], sizes = new int[4][];
        int extras = 0;
        for (int tier = 1; tier <= 3; tier++) {
            int[] words = workspace.words[tier], roots = workspace.roots[tier], members = workspace.members[tier];
            System.arraycopy(source, 0, words, 0, total * STRIDE);
            for (int q = 0; q < total; q++) { roots[q] = q; members[q] = 1; }
            int[] recent = workspace.recent;
            for (int group = 0; group < count.length; group++) {
                int seen = 0;
                for (int q = first[group], end = q + retained[tier][group]; q < end; q++) {
                    if (!eligible(words, q)) continue;
                    flattenColor(words, q);
                    boolean merged = false;
                    for (int i = 0; i < Math.min(seen, recent.length); i++) {
                        int previous = recent[(seen - 1 - i) & 63];
                        if (roots[previous] != previous) continue;
                        if (join(words, previous, q, axis, tier)) {
                            roots[q] = previous; members[previous]++; members[q] = 0;
                            int current = previous;
                            boolean again;
                            do {
                                again = false;
                                for (int j = 0; j < Math.min(seen, recent.length); j++) {
                                    int other = recent[(seen - 1 - j) & 63];
                                    if (other == current || roots[other] != other) continue;
                                    if (join(words, other, current, axis, tier)) {
                                        roots[current] = other; members[other] += members[current]; members[current] = 0;
                                        current = other; again = true; break;
                                    }
                                }
                            } while (again);
                            merged = true; break;
                        }
                    }
                    if (!merged) recent[seen++ & 63] = q;
                }
            }
            int added = 0, removed = 0;
            for (int q = 0; q < total; q++) {
                int root = roots[q]; while (root != roots[root]) root = roots[root]; roots[q] = root;
                if (members[q] >= 4) { added++; removed += members[q]; }
            }
            boolean fragmented = false;
            for (int group = 0; group < count.length; group++) {
                int runs = 0; boolean keeping = false;
                for (int q = first[group], end = q + retained[tier][group]; q < end; q++) {
                    boolean keep = members[roots[q]] < 4;
                    if (keep && !keeping) runs++;
                    keeping = keep;
                }
                fragmented |= runs > Math.max(8, retained[tier][group] / PredictionMeshletBounds.QUADS + 2);
            }
            // Avoid replacing pairs with another upload and scattering tiny command ranges.
            if (fragmented || removed - added < 64 || extras + added > total / 2) continue;
            variants[tier] = words; parent[tier] = roots; sizes[tier] = members; extras += added;
        }
        if (extras == 0) return new Result(source, null);
        int[] combined = Arrays.copyOf(source, source.length + extras * STRIDE);
        PredictionDrawRanges[][] ranges = new PredictionDrawRanges[4][32];
        int cursor = source.length / STRIDE;
        for (int tier = 1; tier <= 3; tier++) {
            int[][] starts = new int[count.length][], lengths = new int[count.length][];
            for (int group = 0; group < count.length; group++) {
                int capacity = retained[tier][group] + 1, n = 0;
                int[] f = new int[capacity], c = new int[capacity];
                int appended = cursor;
                for (int q = first[group], end = q + retained[tier][group]; q < end; q++) {
                    boolean replace = variants[tier] != null && sizes[tier][parent[tier][q]] >= 4;
                    if (!replace) n = append(f, c, n, q, 1);
                    else if (parent[tier][q] == q) {
                        System.arraycopy(variants[tier], q * STRIDE, combined, cursor++ * STRIDE, STRIDE);
                    }
                }
                if (cursor > appended) n = append(f, c, n, appended, cursor - appended);
                starts[group] = Arrays.copyOf(f, n); lengths[group] = Arrays.copyOf(c, n);
            }
            for (int visible = 0; visible < 32; visible++) {
                int size = 0;
                for (int g = 0; g < count.length; g++) if ((visible & 1 << g) != 0) size += starts[g].length;
                int[] f = new int[size], c = new int[size]; int n = 0;
                for (int g = 0; g < count.length; g++) if ((visible & 1 << g) != 0) {
                    for (int i = 0; i < starts[g].length; i++) n = append(f, c, n, starts[g][i], lengths[g][i]);
                }
                ranges[tier][visible] = new PredictionDrawRanges(Arrays.copyOf(f, n), Arrays.copyOf(c, n));
            }
        }
        return new Result(combined, ranges);
    }

    private static int append(int[] f, int[] c, int n, int first, int count) {
        if (n > 0 && f[n - 1] + c[n - 1] == first) c[n - 1] += count;
        else { f[n] = first; c[n++] = count; }
        return n;
    }

    private static boolean eligible(int[] w, int q) {
        int p = q * STRIDE, flags = w[p + 6];
        int excluded = PredictionPackedMesh.FLAG_UNSHADED | PredictionPackedMesh.FLAG_MODEL_UV
                | PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE | (3 << PredictionPackedMesh.FLAGS_FLUID_SHIFT);
        if ((flags & excluded) != 0 || (w[p + 9] & 0x0e000000) != 0) return false;
        int normal = normal(flags), u = (normal + 1) % 3, v = (normal + 2) % 3;
        int plane = coordinate(w, p, 0, normal);
        int u0 = coordinate(w, p, 0, u), u1 = coordinate(w, p, 2, u);
        int v0 = coordinate(w, p, 0, v), v1 = coordinate(w, p, 2, v);
        if (u0 == u1 || v0 == v1) return false;
        for (int corner = 0; corner < 4; corner++) {
            if (coordinate(w, p, corner, normal) != plane) return false;
            int a = coordinate(w, p, corner, u), b = coordinate(w, p, corner, v);
            if (a != u0 && a != u1 || b != v0 && b != v1) return false;
        }
        return coordinate(w, p, 1, u) != coordinate(w, p, 3, u)
                && coordinate(w, p, 1, v) != coordinate(w, p, 3, v);
    }

    private static void flattenColor(int[] w, int q) {
        int p = q * STRIDE, first = w[p + 7], flags = w[p + 9] & 0x0f000000;
        int red = 0, green = 0, blue = 0;
        for (int offset : COLOR_WORDS) {
            int color = w[p + offset];
            if ((color & 0xf0000000) != (first & 0xf0000000)) return;
            red += color >>> 16 & 255; green += color >>> 8 & 255; blue += color & 255;
        }
        int color = (first & 0xf0000000) | (red / 4 << 16) | (green / 4 << 8) | blue / 4;
        w[p + 7] = w[p + 10] = w[p + 11] = color; w[p + 9] = color | flags;
    }

    private static boolean join(int[] w, int a, int b, int cellAxis, int tier) {
        int p = a * STRIDE, q = b * STRIDE;
        if (w[p + 6] != w[q + 6] || w[p + 7] != w[q + 7]
                || (w[p + 9] & ~0x01000000) != (w[q + 9] & ~0x01000000)
                || w[p + 10] != w[q + 10] || w[p + 11] != w[q + 11]
                || w[p + 7] != w[p + 10] || w[q + 7] != w[q + 10]
                || w[p + 7] != w[p + 11] || w[q + 7] != w[q + 11]) return false;
        int normal = normal(w[p + 6]), u = (normal + 1) % 3, v = (normal + 2) % 3;
        if (coordinate(w, p, 0, normal) != coordinate(w, q, 0, normal)) return false;
        // A side's perpendicular ownership coordinate is fixed by the existing shader.
        if (normal == 0 && w[p + 8] % cellAxis != w[q + 8] % cellAxis
                || normal == 2 && w[p + 8] / cellAxis != w[q + 8] / cellAxis) return false;
        int a0 = Math.min(coordinate(w, p, 0, u), coordinate(w, p, 2, u));
        int a1 = Math.max(coordinate(w, p, 0, u), coordinate(w, p, 2, u));
        int b0 = Math.min(coordinate(w, p, 0, v), coordinate(w, p, 2, v));
        int b1 = Math.max(coordinate(w, p, 0, v), coordinate(w, p, 2, v));
        int c0 = Math.min(coordinate(w, q, 0, u), coordinate(w, q, 2, u));
        int c1 = Math.max(coordinate(w, q, 0, u), coordinate(w, q, 2, u));
        int d0 = Math.min(coordinate(w, q, 0, v), coordinate(w, q, 2, v));
        int d1 = Math.max(coordinate(w, q, 0, v), coordinate(w, q, 2, v));
        boolean alongU = b0 == d0 && b1 == d1 && (a0 == c1 || a1 == c0);
        boolean alongV = a0 == c0 && a1 == c1 && (b0 == d1 || b1 == d0);
        if (!alongU && !alongV) return false;
        int dimension = alongU ? u : v, low = alongU ? a0 : b0, high = alongU ? a1 : b1;
        int minimum = Math.min(low, alongU ? c0 : d0), maximum = Math.max(high, alongU ? c1 : d1);
        int limit = ((w[p + 6] & PredictionPackedMesh.FLAG_CUTOUT) != 0 ? 1 << tier : 4 << tier) * 16;
        if (maximum - minimum > limit) return false;
        for (int corner = 0; corner < 4; corner++)
            setCoordinate(w, p, corner, dimension, coordinate(w, p, corner, dimension) == low ? minimum : maximum);
        w[p + 9] &= ~0x01000000; // Tangential ownership remains per fragment for the merged surface.
        return true;
    }

    private static int normal(int flags) { return switch (flags >>> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3) { case 1 -> 0; case 2 -> 2; default -> 1; }; }

    private static int coordinate(int[] w, int p, int corner, int axis) {
        int word = w[p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + corner / 2] >>> ((corner & 1) * 16) & 65535;
        boolean fine = (w[p + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
        return axis == 1 ? (word - 32768) * (fine ? 1 : 4)
                : word * (fine ? 1 : 16 << (w[p + 6] >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
    }

    private static void setCoordinate(int[] w, int p, int corner, int axis, int value) {
        boolean fine = (w[p + 6] & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
        int encoded = axis == 1 ? value / (fine ? 1 : 4) + 32768
                : value / (fine ? 1 : 16 << (w[p + 6] >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
        int at = p + (axis == 0 ? 0 : axis == 2 ? 2 : 4) + corner / 2, shift = (corner & 1) * 16;
        w[at] = w[at] & ~(65535 << shift) | (encoded & 65535) << shift;
    }
}
