package dev.xantha.vss.client.prediction;

/** Avalanche both coordinates before folding: mix(long) followed by long2int cancels X's low bits. */
final class PredictionKeyHash {
    static int hash(int x, int z, int detail) {
        long coordinates = (long) x << 32 | z & 0xffffffffL;
        long mixed = it.unimi.dsi.fastutil.HashCommon.murmurHash3(coordinates);
        return (int) (mixed ^ mixed >>> 32) ^ it.unimi.dsi.fastutil.HashCommon.mix(detail);
    }
    private PredictionKeyHash() { }
}
