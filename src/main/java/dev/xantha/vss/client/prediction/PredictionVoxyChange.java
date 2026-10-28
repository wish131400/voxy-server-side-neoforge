package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Tile;

/** Local peer indexes are prepared outside the source lock; Y sections and faces share evidence. */
final class PredictionVoxyChange implements LongPredicate {
    private static final ThreadLocal<long[]> ROWS=ThreadLocal.withInitial(()->new long[34]);
    private record Group(int span,Map<Long,List<Tile>> cells,List<Tile> tiles) { }
    private record Owner(Tile tile,List<Group> peers) { }
    private final List<Owner> owners;
    private final Map<Long,Boolean> horizontal=new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder peerVisits=new java.util.concurrent.atomic.LongAdder();
    PredictionVoxyChange(Tile before,Tile after,List<Tile> peers,boolean terrain) {
        var owners=new ArrayList<Owner>();
        for(Tile tile:new Tile[]{before,after})if(tile!=null)for(boolean plants:new boolean[]{false,true}) {
            if(plants ? !tile.surfaceComplete() && !tile.hasVegetation() : !terrain && tile.cityFeatures()==0)continue;
            var bySpan=new TreeMap<Integer,Map<Long,List<Tile>>>();
            var all=new HashMap<Integer,List<Tile>>();
            for(Tile peer:peers)if(!peer.key().equals(tile.key()) && precedes(peer,tile,plants)) {
                bySpan.computeIfAbsent(peer.span(),ignored->new HashMap<>())
                        .computeIfAbsent(pack(Math.floorDiv(peer.baseX(),peer.span()),Math.floorDiv(peer.baseZ(),peer.span())),
                                ignored->new ArrayList<>()).add(peer);
                all.computeIfAbsent(peer.span(),ignored->new ArrayList<>()).add(peer);
            }
            var groups=new ArrayList<Group>();
            bySpan.forEach((span,cells)->groups.add(new Group(span,cells,all.get(span))));
            owners.add(new Owner(tile,List.copyOf(groups)));
        }
        this.owners=List.copyOf(owners);
    }
    @Override public boolean test(long pos) {
        long key=VoxyPredictionBridge.position(VoxyPredictionBridge.level(pos),VoxyPredictionBridge.x(pos),0,VoxyPredictionBridge.z(pos));
        return horizontal.computeIfAbsent(key,ignored->{
            for(Owner owner:owners)if(contributes(owner,pos))return true;
            return false;
        });
    }
    private boolean contributes(Owner owner,long pos) {
        Tile tile=owner.tile();
        int step=1<<VoxyPredictionBridge.level(pos),span=32*step;
        // A neighboring face can change this section's mesh too. Include one
        // native voxel on every side, just as the neighbor source does.
        long bx=(long)VoxyPredictionBridge.x(pos)*span-step,bz=(long)VoxyPredictionBridge.z(pos)*span-step;
        int x0=cell(tile.baseX(),bx,step),x1=cell((long)tile.baseX()+tile.span(),bx,step);
        int z0=cell(tile.baseZ(),bz,step),z1=cell((long)tile.baseZ()+tile.span(),bz,step);
        if(x0>=x1 || z0>=z1)return false;
        var rows=ROWS.get();Arrays.fill(rows,0);
        long mask=((1L<<x1)-1)^((1L<<x0)-1);
        Arrays.fill(rows,z0,z1,mask);
        int remaining=(x1-x0)*(z1-z0);
        // Peers are aligned tiles. Only buckets containing these sample centers
        // can hide this owner; coarse tiles never expand into fine buckets.
        long minX=bx+x0*step+step/2,minZ=bz+z0*step+step/2;
        long maxX=bx+(x1-1)*step+step/2,maxZ=bz+(z1-1)*step+step/2;
        for(Group group:owner.peers()) {
            int firstX=(int)Math.floorDiv(minX,group.span()),lastX=(int)Math.floorDiv(maxX,group.span());
            int firstZ=(int)Math.floorDiv(minZ,group.span()),lastZ=(int)Math.floorDiv(maxZ,group.span());
            if(((long)lastX-firstX+1)*((long)lastZ-firstZ+1)>group.cells().size()) {
                for(Tile peer:group.tiles()) {
                    remaining=hide(peer,bx,bz,step,z0,z1,rows,remaining);
                    if(remaining==0)return false;
                }
            } else for(int z=firstZ;z<=lastZ;z++)for(int x=firstX;x<=lastX;x++) {
                var bucket=group.cells().get(pack(x,z));if(bucket==null)continue;
                for(Tile peer:bucket) {
                    remaining=hide(peer,bx,bz,step,z0,z1,rows,remaining);
                    if(remaining==0)return false;
                }
            }
        }
        return true;
    }
    private int hide(Tile peer,long bx,long bz,int step,int z0,int z1,long[] rows,int remaining) {
        peerVisits.increment();
        int minX=cell(peer.baseX(),bx,step),maxX=cell((long)peer.baseX()+peer.span(),bx,step);
        int minZ=Math.max(z0,cell(peer.baseZ(),bz,step)),maxZ=Math.min(z1,cell((long)peer.baseZ()+peer.span(),bz,step));
        long keep=~(((1L<<maxX)-1)^((1L<<minX)-1));
        for(int z=minZ;z<maxZ;z++) {remaining-=Long.bitCount(rows[z]&~keep);rows[z]&=keep;}
        return remaining;
    }
    long peerVisits() {return peerVisits.sum();}
    int horizontalChecks() {return horizontal.size();}
    private static long pack(int x,int z) {return (long)x<<32 | z&0xffffffffL;}
    private static int cell(long boundary,long origin,int step) {
        return (int)Math.max(0,Math.min(34,-Math.floorDiv(origin+step/2-boundary,step)));
    }
    private static boolean precedes(Tile peer,Tile tile,boolean plants) {
        if(plants) {
            // Only complete placement proves that lower-priority plants cannot
            // contribute. A partial child must not hide its parent's invalidation.
            if(!peer.surfaceComplete())return false;
            if(peer.surfaceComplete()!=tile.surfaceComplete())return peer.surfaceComplete();
        }
        return peer.step()<tile.step() || peer.step()==tile.step() && peer.key().lod()<tile.key().lod();
    }
}
