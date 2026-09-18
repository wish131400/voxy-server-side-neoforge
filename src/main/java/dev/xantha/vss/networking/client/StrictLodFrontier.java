package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.ChebyshevRingOffsets;
import java.util.function.BiPredicate;

/** Complete square rings only. A failure, timeout or missing cache never completes a cell. */
public final class StrictLodFrontier {
    private int x, z, limit;
    private int visible = -1;
    private boolean initialized;
    private long revision;

    public void center(int x, int z, int limit) {
        int retained = initialized ? Math.max(-1, visible - Math.max(Math.abs(x - this.x), Math.abs(z - this.z))) : -1;
        if (!initialized || this.x != x || this.z != z || this.limit != limit) revision++;
        this.x = x;
        this.z = z;
        this.limit = Math.max(0, limit);
        visible = Math.min(retained, this.limit);
        initialized = true;
    }

    public boolean advance(BiPredicate<Integer, Integer> dataReady, BiPredicate<Integer, Integer> renderReady) {
        if (!initialized || visible >= limit) return false;
        int ring = visible + 1;
        int start = ChebyshevRingOffsets.firstIndexForRing(ring);
        int end = ChebyshevRingOffsets.firstIndexForRing(ring + 1);
        // Check the whole ring against one render-thread snapshot. Never carry
        // partial readiness over to a later frame where nodes might be evicted.
        for (int i = start; i < end; i++) {
            long offset = ChebyshevRingOffsets.offsetAt(i);
            if (!dataReady.test(x + ChebyshevRingOffsets.decodeX(offset), z + ChebyshevRingOffsets.decodeZ(offset))) return false;
        }
        for (int i = start; i < end; i++) {
            long offset = ChebyshevRingOffsets.offsetAt(i);
            if (!renderReady.test(x + ChebyshevRingOffsets.decodeX(offset), z + ChebyshevRingOffsets.decodeZ(offset))) return false;
        }
        visible = ring;
        revision++;
        return true;
    }

    public void missing(int cx, int cz) {
        int ring = Math.max(Math.abs(cx - x), Math.abs(cz - z));
        if (ring <= visible) { visible = ring - 1; revision++; }
    }

    public void reset() { visible = -1; initialized = false; revision++; }
    public int visibleRing() { return visible; }
    public int requestRing() { return Math.min(limit, visible + 1); }
    public int centerX() { return x; }
    public int centerZ() { return z; }
    public long revision() { return revision; }
    public boolean visible(int cx, int cz) {
        return initialized && Math.max(Math.abs(cx - x), Math.abs(cz - z)) <= visible;
    }
    public boolean requestable(int cx, int cz) {
        return initialized && Math.max(Math.abs(cx - x), Math.abs(cz - z)) <= requestRing();
    }
}
