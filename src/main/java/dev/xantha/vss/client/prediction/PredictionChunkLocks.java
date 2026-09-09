package dev.xantha.vss.client.prediction;

/** Mix both coordinates before selecting a power-of-two generation lock. */
final class PredictionChunkLocks {
    static int stripe(long key, int count) {
        // Long.hashCode(x << 32 | z) is x ^ z: every x == z chunk
        // otherwise queues behind one lock, even on unrelated terrain jobs.
        key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
        key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
        return (int)(key ^ (key >>> 31)) & (count - 1);
    }

    private PredictionChunkLocks() { }
}
