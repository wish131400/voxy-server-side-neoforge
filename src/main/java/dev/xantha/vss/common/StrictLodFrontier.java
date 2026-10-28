package dev.xantha.vss.common;

import java.util.function.BiPredicate;

/**
 * 完整方环的纯就绪前沿算法；数据和渲染证据由调用方以谓词提供。
 * <p>坐标和半径均以区块为单位，-1 表示中心尚未完成；调用方负责串行访问。
 * 同一 advance 必须使用同一帧的证据，缺列立即撤销外圈，reset 不继承旧世界完成状态。
 * 本类不查询网络、世界或 GPU，也不复制真实覆盖索引。
 */
public final class StrictLodFrontier {
    private int x, z, limit;
    private int visible = -1;
    private boolean initialized;
    private long revision;
    private int blockedX, blockedZ;
    private int blockedStage; // 0: none, 1: data, 2: render

    public void center(int x, int z, int limit) {
        int retained = initialized ? Math.max(-1, visible - Math.max(Math.abs(x - this.x), Math.abs(z - this.z))) : -1;
        if (!initialized || this.x != x || this.z != z || this.limit != Math.max(0, limit)) {
            revision++;
            blockedStage = 0;
        }
        this.x = x;
        this.z = z;
        this.limit = Math.max(0, limit);
        visible = Math.min(retained, this.limit);
        initialized = true;
    }

    public boolean advance(BiPredicate<Integer, Integer> dataReady, BiPredicate<Integer, Integer> renderReady) {
        if (!initialized || visible >= limit) return false;
        // A still-missing column is sufficient to reject this ring. Successful
        // evidence is never retained: once it recovers, recheck the entire ring.
        if (blockedStage != 0 && !(blockedStage == 1 ? dataReady : renderReady).test(blockedX, blockedZ)) return false;
        blockedStage = 0;
        int ring = visible + 1;
        int start = ChebyshevRingOffsets.firstIndexForRing(ring);
        int end = ChebyshevRingOffsets.firstIndexForRing(ring + 1);
        // Check the whole ring against one render-thread snapshot. Never carry
        // partial readiness over to a later frame where nodes might be evicted.
        for (int i = start; i < end; i++) {
            long offset = ChebyshevRingOffsets.offsetAt(i);
            int cx = x + ChebyshevRingOffsets.decodeX(offset), cz = z + ChebyshevRingOffsets.decodeZ(offset);
            if (!dataReady.test(cx, cz)) { blockedX = cx; blockedZ = cz; blockedStage = 1; return false; }
        }
        for (int i = start; i < end; i++) {
            long offset = ChebyshevRingOffsets.offsetAt(i);
            int cx = x + ChebyshevRingOffsets.decodeX(offset), cz = z + ChebyshevRingOffsets.decodeZ(offset);
            if (!renderReady.test(cx, cz)) { blockedX = cx; blockedZ = cz; blockedStage = 2; return false; }
        }
        visible = ring;
        revision++;
        return true;
    }

    public void missing(int cx, int cz) {
        int ring = Math.max(Math.abs(cx - x), Math.abs(cz - z));
        if (ring <= visible) { visible = ring - 1; blockedStage = 0; revision++; }
    }

    public void reset() { visible = -1; initialized = false; blockedStage = 0; revision++; }
    public int visibleRing() { return visible; }
    public int requestRing() { return Math.min(limit, visible + 1); }
    public int centerX() { return x; }
    public int centerZ() { return z; }
    public long revision() { return revision; }
    public boolean visible(int cx, int cz) {
        return initialized && Math.max(Math.abs(cx - x), Math.abs(cz - z)) <= visible;
    }
}
