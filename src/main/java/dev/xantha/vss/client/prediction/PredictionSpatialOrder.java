package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Worker-only ordering. Face ranges, complete records and transparent order stay intact. */
final class PredictionSpatialOrder {
    private PredictionSpatialOrder() { }

    /**
     * Sorting a packed mesh is a worker-side operation. Keep the two large
     * primitive work arrays with that worker so a repeated repack only
     * allocates the published payload and its indexes. The arrays are never
     * shared between threads and grow only when a larger face group appears.
     */
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private static final class Workspace {
        long[] keys = new long[0];
        int[] records = new int[0];

        void ensure(int keyCapacity, int recordCapacity) {
            if (keys.length < keyCapacity) keys = new long[keyCapacity];
            if (records.length < recordCapacity) records = new int[recordCapacity];
        }
    }

    /** Every display level is a prefix within each opaque face group. */
    static int[][] arrange(int[] words, int[] first, int[] count, boolean[] aquatic) {
        int[][] retained = new int[4][count.length];
        int capacity = 0;
        for (int n : count) capacity = Math.max(capacity, n);
        if (capacity == 0) return retained;
        Workspace workspace = WORKSPACE.get();
        workspace.ensure(capacity, Math.multiplyExact(capacity, PredictionPackedMesh.STRIDE_INTS));
        long[] keys = workspace.keys;
        int[] scratch = workspace.records;
        boolean spatial = !Boolean.getBoolean("vss.disablePredictionSpatialOrder");
        boolean thin = !Boolean.getBoolean("vss.disablePredictionAquaticLod");
        // Normalize all three axes; X/Z-only ordering made tall clusters span entire buildings.
        double maxCoordinate = 1;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int g = 0; g < count.length; g++) for (int q = first[g], end = q + count[g]; q < end; q++) {
            int p = q * 12;
            maxCoordinate = Math.max(maxCoordinate, Math.max(center(words, p, 0), center(words, p, 2)) * 16);
            double y = center(words, p, 4); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        maxCoordinate = Math.max(maxCoordinate, (maxY - minY) * 16);
        int shift = Math.max(0, 32 - Integer.numberOfLeadingZeros((int) Math.ceil(maxCoordinate)) - 9);
        for (int g = 0; g < count.length; g++) {
            boolean ordered = true;
            for (int i = 0; i < count[g]; i++) {
                int q = first[g] + i, p = q * 12;
                double x = center(words, p, 0), z = center(words, p, 2);
                int tier = thin ? retainedThrough(words[p + 6], aquatic, x, z) : 3;
                for (int level = 0; level <= tier; level++) retained[level][g]++;
                int morton = spatial ? spread(((int) (x * 16)) >>> shift)
                        | (spread(((int) (z * 16)) >>> shift) << 1)
                        | (spread(((int) ((center(words, p, 4) - minY) * 16)) >>> shift) << 2) : 0;
                long category = (words[p + 6] & PredictionPackedMesh.FLAG_CUTOUT) != 0 ? 1L << 27 : 0;
                long order = ((long) (3 - tier) << 28) | category | morton;
                keys[i] = (order << 32) | (q & 0xffffffffL);
                ordered &= i == 0 || keys[i - 1] <= keys[i];
            }
            // Finished caches already carry canonical spatial order. Recompute
            // classifications after material remapping, but only sort/copy if it changed.
            if (ordered) continue;
            Arrays.sort(keys, 0, count[g]);
            for (int i = 0; i < count[g]; i++)
                System.arraycopy(words, ((int) keys[i]) * 12, scratch, i * 12, 12);
            System.arraycopy(scratch, 0, words, first[g] * 12, count[g] * 12);
        }
        return retained;
    }

    static int retainedThrough(int flags, boolean[] aquatic, double x, double z) {
        int sprite = flags & 65535;
        // Only known crossed aquatic plants. Never thin solids, fluids or custom baked models.
        if (sprite >= aquatic.length || !aquatic[sprite]
                || (flags & PredictionPackedMesh.FLAG_UNSHADED) == 0
                || (flags & PredictionPackedMesh.FLAG_MODEL_UV) != 0
                || (flags >>> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3) != 0) return 3;
        // Tile origins are multiples of 64, including at negative world coordinates.
        // Select whole X/Z stalks: Y, plant top sprite and face orientation never affect selection.
        int column = (int) Math.floor(x) | (int) Math.floor(z);
        if ((column & 1) != 0) return 0;
        if ((column & 3) != 0) return 1;
        return (column & 7) != 0 ? 2 : 3;
    }

    private static double center(int[] words, int p, int axis) {
        int a = words[p + axis], b = words[p + axis + 1];
        double mean = ((a & 65535) + (a >>> 16) + (b & 65535) + (b >>> 16)) * .25;
        int flags = words[p + 6];
        if (axis == 4) return (mean - 32768) * ((flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0
                ? 1.0 / 16 : 1.0 / 4);
        return mean * ((flags & PredictionPackedMesh.FLAG_FINE_COORDINATES) != 0
                ? 1.0 / 16 : 1 << (flags >>> PredictionPackedMesh.XZ_SHIFT_BITS & 15));
    }

    private static int spread(int value) {
        value &= 0x1ff;
        value = (value | value << 16) & 0x030000ff;
        value = (value | value << 8) & 0x0300f00f;
        value = (value | value << 4) & 0x030c30c3;
        return (value | value << 2) & 0x09249249;
    }
}
