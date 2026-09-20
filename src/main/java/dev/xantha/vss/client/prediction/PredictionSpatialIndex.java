package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.function.Consumer;

/** Single-dimension quadtree. Empty subtrees are skipped, including beneath coarse changes. */
final class PredictionSpatialIndex<T> {
    private static final int TOP = PredictionTileManager.MAX_LOD_LEVEL;
    private final Long2ObjectOpenHashMap<T>[] values;
    private final Long2IntOpenHashMap[] counts = new Long2IntOpenHashMap[TOP + 1];
    private long visited;

    @SuppressWarnings("unchecked")
    PredictionSpatialIndex() {
        values = new Long2ObjectOpenHashMap[TOP + 1];
        for (int lod=0;lod<=TOP;lod++) {
            values[lod]=new Long2ObjectOpenHashMap<>();
            counts[lod]=new Long2IntOpenHashMap();
        }
    }

    void put(PredictionTileKey key,T value) {
        if (values[key.lod()].put(pack(key.tileX(),key.tileZ()),value)!=null) return;
        adjust(key,1);
    }

    void remove(PredictionTileKey key) {
        if (values[key.lod()].remove(pack(key.tileX(),key.tileZ()))!=null) adjust(key,-1);
    }

    private void adjust(PredictionTileKey key,int delta) {
        for (int lod=key.lod();lod<=TOP;lod++) {
            long at=pack(key.tileX()>>(lod-key.lod()),key.tileZ()>>(lod-key.lod()));
            int count=counts[lod].get(at)+delta;
            if (count==0) counts[lod].remove(at); else counts[lod].put(at,count);
        }
    }

    void intersect(long minX,long minZ,long maxX,long maxZ,Consumer<T> visit) {
        if (minX>=maxX || minZ>=maxZ) return;
        long span=64L<<TOP;
        for (long z=Math.floorDiv(minZ,span);z<=Math.floorDiv(maxZ-1,span);z++)
            for (long x=Math.floorDiv(minX,span);x<=Math.floorDiv(maxX-1,span);x++)
                intersect((int)x,(int)z,TOP,minX,minZ,maxX,maxZ,visit);
    }

    private void intersect(int x,int z,int lod,long minX,long minZ,long maxX,long maxZ,Consumer<T> visit) {
        visited++;
        long key=pack(x,z);
        if (!counts[lod].containsKey(key)) return;
        long span=64L<<lod, bx=x*span,bz=z*span;
        if (bx>=maxX || bz>=maxZ || bx+span<=minX || bz+span<=minZ) return;
        T value=values[lod].get(key);
        if (value!=null) visit.accept(value);
        if (lod==0) return;
        for (int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++)
            intersect(x*2+dx,z*2+dz,lod-1,minX,minZ,maxX,maxZ,visit);
    }

    long visited() { return visited; }
    boolean any(long minX,long minZ,long maxX,long maxZ) {
        if(counts[TOP].isEmpty() || minX>=maxX || minZ>=maxZ) return false;
        long span=64L<<TOP;
        for(long z=Math.floorDiv(minZ,span);z<=Math.floorDiv(maxZ-1,span);z++)
            for(long x=Math.floorDiv(minX,span);x<=Math.floorDiv(maxX-1,span);x++)
                if(any((int)x,(int)z,TOP,minX,minZ,maxX,maxZ)) return true;
        return false;
    }
    private boolean any(int x,int z,int lod,long minX,long minZ,long maxX,long maxZ) {
        long key=pack(x,z);if(!counts[lod].containsKey(key)) return false;
        long span=64L<<lod,bx=x*span,bz=z*span;
        if(bx>=maxX || bz>=maxZ || bx+span<=minX || bz+span<=minZ) return false;
        if(values[lod].containsKey(key)) return true;
        if(lod==0) return false;
        for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++)
            if(any(x*2+dx,z*2+dz,lod-1,minX,minZ,maxX,maxZ)) return true;
        return false;
    }
    void clear() { for(int lod=0;lod<=TOP;lod++) { values[lod].clear();counts[lod].clear(); } }
    private static long pack(int x,int z) { return (long)x<<32 | z&0xffffffffL; }
}
