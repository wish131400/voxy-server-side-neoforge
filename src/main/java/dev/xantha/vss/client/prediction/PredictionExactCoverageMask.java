package dev.xantha.vss.client.prediction;

/** CPU snapshot of authoritative coverage, shared with prediction scheduling. */
final class PredictionExactCoverageMask {
    record Snapshot(int originX, int originZ, int size, byte[] columns, int[] prefix) {
        Snapshot(int originX, int originZ, int size, byte[] columns) {
            this(originX, originZ, size, columns, new int[(size + 1) * (size + 1)]);
        }
        /** Constant-time proof that every column is settled interior. Inclusive chunk bounds. */
        boolean interior(int minX, int minZ, int maxX, int maxZ) {
            int x = minX - originX, z = minZ - originZ, xx = maxX - originX + 1, zz = maxZ - originZ + 1;
            if (x < 0 || z < 0 || xx > size || zz > size || xx <= x || zz <= z) return false;
            int stride = size + 1;
            return prefix[zz * stride + xx] - prefix[z * stride + xx]
                    - prefix[zz * stride + x] + prefix[z * stride + x] == (xx - x) * (zz - z);
        }
        static Snapshot around(int cx, int cz, int radius) {
            int padded = Math.max(1, Math.min(512, radius)) + 32;
            int size = padded * 2 + 1;
            return new Snapshot(Math.floorDiv(cx, 32) * 32 - padded,
                    Math.floorDiv(cz, 32) * 32 - padded, size, new byte[size * size]);
        }
        void mark(int cx, int cz) {
            int x = cx - originX, z = cz - originZ;
            if (x >= 0 && z >= 0 && x < size && z < size) columns[z * size + x] = (byte) 255;
        }
        /** 255 is settled interior; 128 is an indexed edge needing actual frame depth. */
        void preserveBoundaryFallback() {
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (columns[i] == 0) continue;
                boolean interior = x > 0 && z > 0 && x < size - 1 && z < size - 1;
                for (int dz = -1; interior && dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    // Both edge and interior remain positive: classification
                    // in place must not erode additional rows as we traverse.
                    if (columns[(z + dz) * size + x + dx] == 0) { interior = false; break; }
                }
                if (!interior) columns[i] = (byte) 128;
            }
            int stride = size + 1;
            for (int z = 0; z < size; z++) {
                int row = 0;
                for (int x = 0; x < size; x++) {
                    if (columns[z * size + x] == (byte) 255) row++;
                    prefix[(z + 1) * stride + x + 1] = prefix[z * stride + x + 1] + row;
                }
            }
        }
    }
}
