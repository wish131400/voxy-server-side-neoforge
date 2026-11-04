package dev.xantha.vss.client.prediction;

import java.util.function.BooleanSupplier;

/** Visit complete 64-block filtered regions without allocating a map or boxed point lists. */
final class FreeTerraForgedGrid {
    @FunctionalInterface interface Query { ClientColumnSample sample(int x, int z); }

    static boolean sample(int x, int z, int step, int width, int height,
                          ClientColumnSample[] target, int offset, int stride,
                          BooleanSupplier valid, Query query) {
        if (step <= 0) throw new IllegalArgumentException("FTF grid step must be positive");
        for (int rz = 0; rz < height;) {
            int endZ = end(z, rz, step, height);
            for (int rx = 0; rx < width;) {
                int endX = end(x, rx, step, width);
                for (int dz = rz; dz < endZ; dz++) for (int dx = rx; dx < endX; dx++) {
                    if (!valid.getAsBoolean()) return false;
                    int i = offset + dz * stride + dx;
                    if (target[i] == null) target[i] = query.sample(x + dx * step, z + dz * step);
                }
                rx = endX;
            }
            rz = endZ;
        }
        return true;
    }

    private static int end(int origin, int start, int step, int size) {
        int toBoundary = 64 - Math.floorMod(origin + start * step, 64);
        return Math.min(size, start + (toBoundary - 1) / step + 1);
    }

    private FreeTerraForgedGrid() {}
}
