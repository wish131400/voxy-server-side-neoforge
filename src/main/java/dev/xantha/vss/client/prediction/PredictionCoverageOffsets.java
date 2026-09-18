package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Four bytes per offset and no object allocation while traversing the scan. */
final class PredictionCoverageOffsets {
    private final int[] values;
    PredictionCoverageOffsets(int[] values) { this.values = values; }
    static int pack(int x, int z) { return (x << 16) | (z & 0xffff); }
    int size() { return values.length; }
    int x(int i) { return values[i] >> 16; }
    int z(int i) { return (short) values[i]; }
    static PredictionCoverageOffsets around(int radius) {
        radius = Math.max(1, Math.min(512, radius));
        long[] sorted = new long[(radius * 2 + 1) * (radius * 2 + 1)];
        int count = 0;
        for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
            int distance = x * x + z * z;
            if (distance <= radius * radius) sorted[count++] = ((long) distance << 32) | (pack(x, z) & 0xffffffffL);
        }
        Arrays.sort(sorted, 0, count);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = (int) sorted[i];
        return new PredictionCoverageOffsets(values);
    }
}
