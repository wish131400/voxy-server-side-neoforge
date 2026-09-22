package dev.xantha.vss.client.prediction;

/** Thread-confined, bounded exact heights. No boxed keys, locks or whole-map clears. */
final class PredictionHeightCache {
    static final int CAPACITY = 4096;
    private final long[] keys = new long[CAPACITY];
    private final int[] heights = new int[CAPACITY];
    private final boolean[] valid = new boolean[CAPACITY];

    int get(int x, int z) {
        long key = key(x, z);
        int slot = slot(key);
        return valid[slot] && keys[slot] == key ? heights[slot] : Integer.MIN_VALUE;
    }

    void put(int x, int z, int height) {
        long key = key(x, z);
        int slot = slot(key);
        keys[slot] = key;
        heights[slot] = height;
        valid[slot] = true;
    }

    private static long key(int x, int z) { return (long)x << 32 | z & 0xffffffffL; }
    private static int slot(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int)key & (CAPACITY - 1);
    }
}
