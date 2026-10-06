package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Worker-built runs with one column footprint. Does not reorder transparent geometry. */
final class PredictionOwnershipRuns {
    final int[] ends;
    final float[] boxes;
    private final float minX, minY, minZ, maxX, maxY, maxZ;

    PredictionOwnershipRuns(int[] words) {
        int count = words.length / PredictionPackedMesh.STRIDE_INTS;
        int capacity = countRuns(words), size = 0;
        int[] ends = new int[capacity];
        float[] boxes = new float[capacity * 6];
        for (int q = 0; q < count; q++) {
            int p = q * PredictionPackedMesh.STRIDE_INTS, flags = words[p + 6];
            boolean fine = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0;
            int fluid = flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3;
            float xzScale = fine ? 1f / 16 : 1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15);
            float yScale = fine || fluid != 0 && (flags & PredictionPackedMesh.FLAG_FLUID_FINE_Y) != 0 ? 1f / 16 : 1f / 4;
            float x = Float.POSITIVE_INFINITY, y = x, z = x, xx = -x, yy = -x, zz = -x;
            for (int c = 0; c < 4; c++) {
                float a = (words[p + c / 2] >>> ((c & 1) * 16) & 65535) * xzScale;
                float b = ((words[p + 4 + c / 2] >>> ((c & 1) * 16) & 65535) - 32768) * yScale;
                float d = (words[p + 2 + c / 2] >>> ((c & 1) * 16) & 65535) * xzScale;
                x = Math.min(x, a); xx = Math.max(xx, a);
                y = Math.min(y, b); yy = Math.max(yy, b);
                z = Math.min(z, d); zz = Math.max(zz, d);
            }
            // Closed geometry at a chunk edge needs both neighbours; never clip a quad in half.
            x = (float) Math.floor((x - 0.0625) / 16) * 16;
            z = (float) Math.floor((z - 0.0625) / 16) * 16;
            xx = (float) (Math.floor((xx + 0.0625) / 16) + 1) * 16;
            zz = (float) (Math.floor((zz + 0.0625) / 16) + 1) * 16;
            int i = (size - 1) * 6;
            if (size > 0 && boxes[i] == x && boxes[i + 2] == z && boxes[i + 3] == xx && boxes[i + 5] == zz) {
                boxes[i + 1] = Math.min(boxes[i + 1], y); boxes[i + 4] = Math.max(boxes[i + 4], yy);
                ends[size - 1] = q + 1;
            } else {
                i = size * 6;
                boxes[i] = x; boxes[i + 1] = y; boxes[i + 2] = z;
                boxes[i + 3] = xx; boxes[i + 4] = yy; boxes[i + 5] = zz;
                ends[size++] = q + 1;
            }
        }
        this.ends = ends; this.boxes = boxes;
        float loX = Float.POSITIVE_INFINITY, loY = Float.POSITIVE_INFINITY, loZ = Float.POSITIVE_INFINITY;
        float hiX = Float.NEGATIVE_INFINITY, hiY = Float.NEGATIVE_INFINITY, hiZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            int b = i * 6;
            loX = Math.min(loX, boxes[b]); loY = Math.min(loY, boxes[b + 1]); loZ = Math.min(loZ, boxes[b + 2]);
            hiX = Math.max(hiX, boxes[b + 3]); hiY = Math.max(hiY, boxes[b + 4]); hiZ = Math.max(hiZ, boxes[b + 5]);
        }
        this.minX = size == 0 ? 0 : loX; this.minY = size == 0 ? 0 : loY; this.minZ = size == 0 ? 0 : loZ;
        this.maxX = size == 0 ? 0 : hiX; this.maxY = size == 0 ? 0 : hiY; this.maxZ = size == 0 ? 0 : hiZ;
    }

    // Count XZ footprints first so workers allocate only final arrays, without
    // growing/copying a potentially large index or allocating per-quad objects.
    private static int countRuns(int[] words) {
        int count = 0;
        float lastX = Float.NaN, lastZ = Float.NaN, lastXX = Float.NaN, lastZZ = Float.NaN;
        for (int p = 0; p < words.length; p += PredictionPackedMesh.STRIDE_INTS) {
            int flags = words[p + 6];
            float scale = (flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0
                    ? 1f / 16 : 1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15);
            float x = Float.POSITIVE_INFINITY, z = x, xx = -x, zz = -x;
            for (int c = 0; c < 4; c++) {
                float a = (words[p + c / 2] >>> ((c & 1) * 16) & 65535) * scale;
                float b = (words[p + 2 + c / 2] >>> ((c & 1) * 16) & 65535) * scale;
                x = Math.min(x, a); xx = Math.max(xx, a);
                z = Math.min(z, b); zz = Math.max(zz, b);
            }
            x = (float) Math.floor((x - 0.0625) / 16) * 16;
            z = (float) Math.floor((z - 0.0625) / 16) * 16;
            xx = (float) (Math.floor((xx + 0.0625) / 16) + 1) * 16;
            zz = (float) (Math.floor((zz + 0.0625) / 16) + 1) * 16;
            if (x != lastX || z != lastZ || xx != lastXX || zz != lastZZ) {
                count++; lastX = x; lastZ = z; lastXX = xx; lastZZ = zz;
            }
        }
        return count;
    }

    /** Scratch retained by a submission-cache entry so filtering never grows per frame. */
    static final class Scratch {
        private int[] first = new int[0];
        private int[] counts = new int[0];

        private void ensure(int capacity) {
            if (first.length >= capacity) return;
            int next = Math.max(capacity, Math.max(16, first.length * 2));
            first = Arrays.copyOf(first, next);
            counts = Arrays.copyOf(counts, next);
        }
    }

    /** Compute the camera-independent Voxy coverage proof for each run. */
    byte[] staticCoverage(PredictionExactCoverageMask.Snapshot index, double baseX, double baseZ) {
        byte[] covered = new byte[ends.length];
        if (index == null) return covered;
        for (int run = 0; run < ends.length; run++) {
            int b = run * 6;
            double x = baseX + boxes[b], z = baseZ + boxes[b + 2];
            double xx = baseX + boxes[b + 3], zz = baseZ + boxes[b + 5];
            covered[run] = (byte) (index.interior((int) Math.floor(x / 16), (int) Math.floor(z / 16),
                    (int) Math.ceil(xx / 16) - 1, (int) Math.ceil(zz / 16) - 1) ? 1 : 0);
        }
        return covered;
    }

    static boolean allStaticCoverage(byte[] covered) {
        for (byte value : covered) if (value == 0) return false;
        return true;
    }

    boolean fullyInside(double baseX, double baseZ, double x, double y, double z, double radius,
                        float morphMin, float morphMax) {
        return ends.length > 0 && radius > 0 && PredictionVoxyOwnership.containsBox(x, y, z, radius,
                baseX + minX - 4, Math.min(minY, morphMin) - 4, baseZ + minZ - 4,
                baseX + maxX + 4, Math.max(maxY, morphMax) + 4, baseZ + maxZ + 4);
    }
    boolean wholeStaticCoverage(PredictionExactCoverageMask.Snapshot index, double baseX, double baseZ) {
        return index.interior((int) Math.floor((baseX + minX) / 16), (int) Math.floor((baseZ + minZ) / 16),
                (int) Math.ceil((baseX + maxX) / 16) - 1, (int) Math.ceil((baseZ + maxZ) / 16) - 1);
    }

    PredictionDrawRanges filter(PredictionDrawRanges input, PredictionExactCoverageMask.Snapshot index,
                               double baseX, double baseZ, double cameraX, double cameraY, double cameraZ,
                               double radius, float morphMin, float morphMax) {
        return filter(input, index, baseX, baseZ, cameraX, cameraY, cameraZ, radius, morphMin, morphMax,
                null, false, new Scratch(), null);
    }

    /**
     * Filter a draw plan using a camera-independent coverage proof and reusable output scratch.
     * The previous result is returned when its exact ranges still match, avoiding a new plan on
     * every camera-cell crossing.
     */
    PredictionDrawRanges filter(PredictionDrawRanges input, PredictionExactCoverageMask.Snapshot index,
                               double baseX, double baseZ, double cameraX, double cameraY, double cameraZ,
                               double radius, float morphMin, float morphMax, byte[] staticCoverage,
                               boolean allStaticCoverage, Scratch scratch, PredictionDrawRanges previous) {
        if (index == null || radius <= 0 || input.quads == 0) return input;

        double lowY = Math.min(minY, morphMin), highY = Math.max(maxY, morphMax);
        double lowX = baseX + minX - 4, lowZ = baseZ + minZ - 4;
        double highX = baseX + maxX + 4, highZ = baseZ + maxZ + 4;
        // A whole-mesh outside test avoids touching every run when the camera leaves the tile.
        if (!intersectsSphere(cameraX, cameraY, cameraZ, radius, lowX, lowY - 4, lowZ,
                highX, highY + 4, highZ)) return input;
        boolean fullInside = PredictionVoxyOwnership.containsBox(cameraX, cameraY, cameraZ, radius,
                lowX, lowY - 4, lowZ, highX, highY + 4, highZ);

        if (fullInside && allStaticCoverage) {
            if (previous != null && previous.quads == 0) return previous;
            return new PredictionDrawRanges(new int[0], new int[0]);
        }

        int initial = Math.max(1, Math.min(32, ends.length + input.first.length));
        scratch.ensure(initial);
        int size = 0, total = 0;
        for (int r = 0; r < input.first.length; r++) {
            int start = input.first[r], end = start + input.count[r];
            int run = Arrays.binarySearch(ends, start + 1); if (run < 0) run = -run - 1;
            while (start < end) {
                int next = Math.min(end, ends[run]), b = run * 6;
                double x = baseX + boxes[b], z = baseZ + boxes[b + 2];
                double xx = baseX + boxes[b + 3], zz = baseZ + boxes[b + 5];
                double y = Math.min(boxes[b + 1], morphMin), yy = Math.max(boxes[b + 4], morphMax);
                boolean staticallyCovered = staticCoverage != null && staticCoverage[run] != 0;
                boolean owned = staticallyCovered && (fullInside || PredictionVoxyOwnership.containsBox(cameraX, cameraY, cameraZ,
                        radius, x - 4, y - 4, z - 4, xx + 4, yy + 4, zz + 4));
                if (staticCoverage == null) {
                    owned = fullInside || PredictionVoxyOwnership.containsBox(cameraX, cameraY, cameraZ, radius,
                            x - 4, y - 4, z - 4, xx + 4, yy + 4, zz + 4);
                    owned &= index.interior((int) Math.floor(x / 16), (int) Math.floor(z / 16),
                            (int) Math.ceil(xx / 16) - 1, (int) Math.ceil(zz / 16) - 1);
                }
                if (!owned) {
                    int n = next - start;
                    if (size > 0 && scratch.first[size - 1] + scratch.counts[size - 1] == start) {
                        scratch.counts[size - 1] += n;
                    } else {
                        if (size == scratch.first.length) scratch.ensure(size + 1);
                        scratch.first[size] = start; scratch.counts[size++] = n;
                    }
                    total += n;
                }
                start = next; run++;
            }
        }
        if (total == input.quads) return input;
        if (size == 0) {
            if (previous != null && previous.quads == 0) return previous;
            return new PredictionDrawRanges(new int[0], new int[0]);
        }
        if (previous != null && previous.quads == total && previous.first.length == size
                && sameRanges(previous, scratch.first, scratch.counts, size)) return previous;
        return new PredictionDrawRanges(Arrays.copyOf(scratch.first, size), Arrays.copyOf(scratch.counts, size));
    }

    private static boolean sameRanges(PredictionDrawRanges previous, int[] first, int[] counts, int size) {
        for (int i = 0; i < size; i++) {
            if (previous.first[i] != first[i] || previous.count[i] != counts[i]) return false;
        }
        return true;
    }

    private static boolean intersectsSphere(double cameraX, double cameraY, double cameraZ, double radius,
                                            double minX, double minY, double minZ,
                                            double maxX, double maxY, double maxZ) {
        double dx = cameraX < minX ? minX - cameraX : cameraX > maxX ? cameraX - maxX : 0;
        double dy = cameraY < minY ? minY - cameraY : cameraY > maxY ? cameraY - maxY : 0;
        double dz = cameraZ < minZ ? minZ - cameraZ : cameraZ > maxZ ? cameraZ - maxZ : 0;
        return dx * dx + dy * dy + dz * dz < radius * radius;
    }
}
