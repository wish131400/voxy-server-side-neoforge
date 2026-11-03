package dev.xantha.vss.compat;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Ownership of actual 16-cube writes, independent of queue acceptance and rendering. */
final class VoxyRealCoverage implements VoxyPredictionBridge.RealColumn {
    private static final Map<Object, VoxyRealCoverage> WORLDS = Collections.synchronizedMap(new WeakHashMap<>());
    private final ConcurrentHashMap<Long, Integer> sections = new ConcurrentHashMap<>();
    private final java.util.Set<Long> columns = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> completeColumns = ConcurrentHashMap.newKeySet();
    private volatile int minY, sectionCount;
    private volatile boolean closed;

    static VoxyRealCoverage of(Object engine) {
        synchronized (WORLDS) { return WORLDS.computeIfAbsent(engine, ignored -> new VoxyRealCoverage()); }
    }

    static void reset() {
        synchronized (WORLDS) {
            WORLDS.values().forEach(coverage -> coverage.closed = true);
            WORLDS.clear();
        }
    }

    boolean mark(int x, int y, int z) {
        if (closed) return false;
        columns.add(dev.xantha.vss.common.PositionUtil.packPosition(x,z));
        long key = VoxySectionPosition.key(0, x >> 1, y >> 1, z >> 1);
        int bit = 1 << ((x & 1) | (z & 1) << 1 | (y & 1) << 2);
        int[] before = new int[1];
        sections.compute(key, (ignored, mask) -> {
            before[0] = mask == null ? 0 : mask;
            return before[0] | bit;
        });
        return (before[0] & bit) == 0;
    }

    void verticalRange(int minY,int count) { this.minY=minY;this.sectionCount=count; }

    @Override public boolean contains(int x, int z) {
        if(closed || sectionCount==0)return false;
        long column=dev.xantha.vss.common.PositionUtil.packPosition(x,z);
        if(!columns.contains(column))return false;
        if(completeColumns.contains(column))return true;
        for(int y=minY;y<minY+sectionCount;y++)if(!containsSection(x,y,z))return false;
        completeColumns.add(column);
        return true;
    }

    @Override public boolean containsSection(int x, int y, int z) {
        if (closed) return false;
        int mask = sections.getOrDefault(VoxySectionPosition.key(0, x >> 1, y >> 1, z >> 1), 0);
        return (mask & 1 << ((x & 1) | (z & 1) << 1 | (y & 1) << 2)) != 0;
    }

    @Override public int coverageMask(int x,int firstY,int z,int count,int lod) {
        if (closed || !columns.contains(dev.xantha.vss.common.PositionUtil.packPosition(x,z))) return 0;
        int result=0;
        for(int i=0;i<count;i++)if(containsSection(x,firstY+i,z))result|=1<<i;
        return result;
    }
}
