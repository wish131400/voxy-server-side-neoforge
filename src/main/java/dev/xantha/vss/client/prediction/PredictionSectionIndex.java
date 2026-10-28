package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.*;

/** Source-lock-owned cache membership. A section and its six faces share one indexed position. */
final class PredictionSectionIndex {
    private final Long2IntOpenHashMap references=new Long2IntOpenHashMap();
    private final Map<Integer,Map<Long,Set<Long>>> levels=new HashMap<>();
    private long candidates;
    private static long pack(int x,int z) {return (long)x<<32 | z&0xffffffffL;}
    void add(long pos) {
        if(references.addTo(pos,1)!=0)return;
        levels.computeIfAbsent(VoxyPredictionBridge.level(pos),ignored->new HashMap<>())
                .computeIfAbsent(pack(VoxyPredictionBridge.x(pos),VoxyPredictionBridge.z(pos)),ignored->new HashSet<>()).add(pos);
    }
    void remove(long pos) {
        int count=references.get(pos);if(count==0)throw new IllegalStateException("Unindexed cache entry");
        if(count>1) {references.put(pos,count-1);return;}
        references.remove(pos);int lod=VoxyPredictionBridge.level(pos);var cells=levels.get(lod);
        long key=pack(VoxyPredictionBridge.x(pos),VoxyPredictionBridge.z(pos));var positions=cells.get(key);
        positions.remove(pos);if(positions.isEmpty())cells.remove(key);if(cells.isEmpty())levels.remove(lod);
    }
    void clear() {references.clear();levels.clear();}
    int size() {return references.size();}
    long candidates() {return candidates;}
    LongArrayList intersect(long minX,long minY,long minZ,long maxX,long maxY,long maxZ) {
        var result=new LongArrayList();if(minX>=maxX || minY>=maxY || minZ>=maxZ)return result;
        for(var level:levels.entrySet()) {
            long span=32L<<level.getKey();var cells=level.getValue();
            int firstX=(int)Math.floorDiv(minX,span),lastX=(int)Math.floorDiv(maxX-1,span);
            int firstZ=(int)Math.floorDiv(minZ,span),lastZ=(int)Math.floorDiv(maxZ-1,span);
            if(((long)lastX-firstX+1)*((long)lastZ-firstZ+1)<cells.size()) {
                for(int z=firstZ;z<=lastZ;z++)for(int x=firstX;x<=lastX;x++)collect(cells.get(pack(x,z)),span,minY,maxY,result);
            } else for(var cell:cells.entrySet()) {
                int x=(int)(cell.getKey()>>32),z=(int)(long)cell.getKey();
                if(x>=firstX && x<=lastX && z>=firstZ && z<=lastZ)collect(cell.getValue(),span,minY,maxY,result);
            }
        }
        return result;
    }
    private void collect(Set<Long> positions,long span,long minY,long maxY,LongArrayList result) {
        if(positions==null)return;
        for(long pos:positions) {candidates++;long by=(long)VoxyPredictionBridge.y(pos)*span;
            if(by<maxY && by+span>minY)result.add(pos);}
    }
}
