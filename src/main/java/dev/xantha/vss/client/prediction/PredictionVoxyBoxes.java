package dev.xantha.vss.client.prediction;

import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.world.level.block.state.BlockState;

/** Lossless immutable feature pages. Lists are compatibility views; mesh workers read primitives. */
final class PredictionVoxyBoxes extends AbstractList<PredictionVoxySource.Box> implements RandomAccess,PredictionVoxyFeatures.Coordinates {
    private static final int PAGE_SHIFT=8, PAGE_SIZE=1<<PAGE_SHIFT;
    private static final PredictionVoxyBoxes EMPTY=new PredictionVoxyBoxes(new Page[0],0);
    // Weak values share repeated publications/LOD pages without pinning retired terrain.
    private static final Map<Integer,WeakReference<Data>> SHARED=new LinkedHashMap<>(256,.75f,true);
    private static final int MAX_SHARED=8192;
    private static final Map<Integer,WeakReference<PredictionVoxyBoxes>> CONTENTS=new LinkedHashMap<>(128,.75f,true);
    private static final int MAX_CONTENTS=1024;
    private record Page(int x,int y,int z,Data data) { }
    private static final class Data implements PredictionVoxyFeatures.Coordinates {
        final char[] small;
        final int[] wide;
        final BlockState[] palette;
        final byte[] states;
        final int hash;
        final int[] limits;
        volatile PredictionVoxyFeatures index;
        Data(char[] small,int[] wide,BlockState[] palette,byte[] states) {
            this.small=small;this.wide=wide;this.palette=palette;this.states=states;
            hash=Objects.hash(Arrays.hashCode(small),Arrays.hashCode(wide),Arrays.hashCode(palette),
                    Arrays.hashCode(states));
            limits=small==null?null:new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,
                    Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
            if(limits!=null)for(int i=0;i<states.length;i++)for(int axis=0;axis<3;axis++) {
                limits[axis]=Math.min(limits[axis],coordinate(i,axis));
                limits[axis+3]=Math.max(limits[axis+3],coordinate(i,axis+3));
            }
        }
        int coordinate(int i) {return small==null?wide[i]:small[i];}
        @Override public int coordinate(int i,int axis) {return coordinate(i*6+axis);}
        PredictionVoxyFeatures index() {
            var ready=index;if(ready!=null)return ready;
            synchronized(this) {if(index==null)index=new PredictionVoxyFeatures(this,states.length);return index;}
        }
        BlockState state(int i) {return palette[Byte.toUnsignedInt(states[i])];}
        long bytes() {return 192L+(small==null?wide.length*4L:small.length*2L)+palette.length*4L
                +states.length;}
        @Override public boolean equals(Object other) {return other instanceof Data d && hash==d.hash
                && Arrays.equals(small,d.small) && Arrays.equals(wide,d.wide) && Arrays.equals(palette,d.palette)
                && Arrays.equals(states,d.states);}
        @Override public int hashCode() {return hash;}
    }
    private final Page[] pages;
    private final int size;
    private final long heapBytes;
    private final long reservedIndexBytes;
    private final int contentHash;
    private volatile PredictionVoxyFeatures index;
    private PredictionVoxyBoxes(Page[] pages,int size) {
        this.pages=pages;this.size=size;
        long bytes=64L+pages.length*4L,indexBytes=size==0?0:80;int hash=size;
        for(var page:pages) {
            bytes+=32L+page.data.bytes();hash=31*hash+page.hashCode();
            if(page.data.small!=null)indexBytes+=PredictionVoxyFeatures.treeBytes(page.data.states.length);
        }
        heapBytes=bytes;contentHash=hash;reservedIndexBytes=indexBytes;
    }
    static PredictionVoxyBoxes of(List<PredictionVoxySource.Box> boxes) {
        if(boxes instanceof PredictionVoxyBoxes packed)return packed;
        var builder=new Builder();builder.addAll(boxes);return builder.build();
    }
    @Override public int size() {return size;}
    @Override public int coordinate(int i,int axis) {
        Objects.checkIndex(i,size);var p=pages[i>>>PAGE_SHIFT];
        int origin=switch(axis%3) {case 0->p.x;case 1->p.y;default->p.z;};
        return origin+p.data.coordinate((i&(PAGE_SIZE-1))*6+axis);
    }
    BlockState state(int i) {Objects.checkIndex(i,size);return pages[i>>>PAGE_SHIFT].data.state(i&(PAGE_SIZE-1));}
    @Override public PredictionVoxySource.Box get(int i) {return new PredictionVoxySource.Box(coordinate(i,0),coordinate(i,1),
            coordinate(i,2),coordinate(i,3),coordinate(i,4),coordinate(i,5),state(i));}
    @Override public boolean equals(Object other) {
        if(other==this)return true;
        if(!(other instanceof PredictionVoxyBoxes b))return super.equals(other);
        if(size!=b.size)return false;
        for(int i=0;i<pages.length;i++) {
            Page a=pages[i],c=b.pages[i];
            if(a.equals(c))continue;
            int end=Math.min(size,(i+1)*PAGE_SIZE);
            for(int j=i*PAGE_SIZE;j<end;j++) {
                if(state(j)!=b.state(j))return false;
                for(int axis=0;axis<6;axis++)if(coordinate(j,axis)!=b.coordinate(j,axis))return false;
            }
        }
        return true;
    }
    long bytes() {return heapBytes;}
    /** Exact changed-box bounds; unchanged shared pages require no per-box objects or scans. */
    int[] changedBounds(PredictionVoxyBoxes previous) {
        int[] bounds={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        int length=Math.max(size,previous.size);
        var sources=new PredictionVoxyBoxes[]{this,previous};
        for(int start=0;start<length;start+=PAGE_SIZE) {
            int page=start/PAGE_SIZE;
            if(page<pages.length && page<previous.pages.length && pages[page].equals(previous.pages[page]))continue;
            for(int i=start;i<Math.min(length,start+PAGE_SIZE);i++) {
                if(i<size && i<previous.size && state(i)==previous.state(i) && sameGeometry(i,previous,i))continue;
                for(var boxes:sources)if(i<boxes.size)for(int a=0;a<3;a++) {
                    bounds[a]=Math.min(bounds[a],boxes.coordinate(i,a));
                    bounds[a+3]=Math.max(bounds[a+3],boxes.coordinate(i,a+3));
                }
            }
        }
        return bounds;
    }
    /** Missing source chunks in a placement LRU are unknown. Keep their published boxes.
     * Preserve old order to avoid remeshing unchanged content; fresh states win at equal bounds.
     * Geometry hashes are verified against all six coordinates, including hash collisions. */
    PredictionVoxyBoxes retainVegetation(int cities,PredictionVoxyBoxes previous,int previousCities) {
        if(cities==previousCities && equals(previous))return this;
        int count=size-cities;
        var heads=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(count);
        heads.defaultReturnValue(-1);
        int[] next=new int[count];
        boolean[] used=new boolean[count];
        for(int i=0;i<count;i++)next[i]=heads.put(geometryHash(cities+i),i);
        var builder=new Builder();builder.addRange(this,0,cities);
        for(int old=previousCities;old<previous.size;old++) {
            int match=heads.get(previous.geometryHash(old));
            while(match>=0 && !sameGeometry(cities+match,previous,old))match=next[match];
            if(match>=0) {used[match]=true;builder.addRange(this,cities+match,cities+match+1);}
            else builder.addRange(previous,old,old+1);
        }
        for(int i=0;i<count;i++)if(!used[i])builder.addRange(this,cities+i,cities+i+1);
        var result=builder.build();
        return equals(result)?this:result;
    }
    private long geometryHash(int i) {
        long hash=0xcbf29ce484222325L;
        for(int axis=0;axis<6;axis++)hash=(hash^coordinate(i,axis))*0x100000001b3L;
        return hash;
    }
    private boolean sameGeometry(int i,PredictionVoxyBoxes other,int j) {
        for(int axis=0;axis<6;axis++)if(coordinate(i,axis)!=other.coordinate(j,axis))return false;
        return true;
    }
    int pageCount() {return pages.length;}
    int pageCoordinate(int p,int axis) {
        var page=pages[p];var data=page.data;
        if(data.limits!=null) {
            int origin=switch(axis%3) {case 0->page.x;case 1->page.y;default->page.z;};
            return origin+data.limits[axis];
        }
        int limit=axis<3?Integer.MAX_VALUE:Integer.MIN_VALUE;
        for(int i=0;i<data.states.length;i++) {
            int value=coordinate(p*PAGE_SIZE+i,axis);
            limit=axis<3?Math.min(limit,value):Math.max(limit,value);
        }
        return limit;
    }
    long indexBytes() {return reservedIndexBytes;}
    /** Page order is publication order. Only touched pages build a relative tree;
     * shared page data reuses that tree across tiles, LODs and translated origins. */
    void intersect(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,
                   it.unimi.dsi.fastutil.ints.IntArrayList result,it.unimi.dsi.fastutil.ints.IntArrayList selectedPages) {
        result.clear();
        int count=selectedPages==null?pages.length:selectedPages.size();
        for(int n=0;n<count;n++) {
            int p=selectedPages==null?n:selectedPages.getInt(n);
            var page=pages[p];var data=page.data;int base=p*PAGE_SIZE;
            if(data.small==null) { // Wide deltas may wrap signed int; reconstruct absolute coordinates first.
                for(int i=0;i<data.states.length;i++) {int id=base+i;
                    if(coordinate(id,0)<maxX && coordinate(id,3)>minX && coordinate(id,1)<maxY
                            && coordinate(id,4)>minY && coordinate(id,2)<maxZ && coordinate(id,5)>minZ)result.add(id);
                }
                continue;
            }
            long lx=(long)minX-page.x,ly=(long)minY-page.y,lz=(long)minZ-page.z;
            long hx=(long)maxX-page.x,hy=(long)maxY-page.y,hz=(long)maxZ-page.z;
            var b=data.limits;
            if(lx>=b[3] || hx<=b[0] || ly>=b[4] || hy<=b[1] || lz>=b[5] || hz<=b[2])continue;
            if(lx<=b[0] && ly<=b[1] && lz<=b[2] && hx>=b[3] && hy>=b[4] && hz>=b[5]) {
                for(int i=0;i<data.states.length;i++)result.add(base+i);
                continue;
            }
            data.index().append(clamp(lx),clamp(ly),clamp(lz),clamp(hx),clamp(hy),clamp(hz),result,base);
        }
    }
    private static int clamp(long value) {return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,value));}
    PredictionVoxyFeatures index() {
        var ready=index;if(ready!=null)return ready;
        synchronized(this) {
            if(index==null)index=new PredictionVoxyFeatures(this);
            return index;
        }
    }
    private static synchronized PredictionVoxyBoxes shareContents(PredictionVoxyBoxes boxes) {
        var ref=CONTENTS.get(boxes.contentHash);var previous=ref==null?null:ref.get();
        if(boxes.equals(previous))return previous;
        CONTENTS.put(boxes.contentHash,new WeakReference<>(boxes));
        if(CONTENTS.size()>MAX_CONTENTS)CONTENTS.remove(CONTENTS.keySet().iterator().next());
        return boxes;
    }
    Object pageStorage(int i) {return pages[i>>>PAGE_SHIFT].data;}
    private static synchronized Data share(Data data) {
        var ref=SHARED.get(data.hash);var previous=ref==null?null:ref.get();
        if(data.equals(previous))return previous;
        SHARED.put(data.hash,new WeakReference<>(data));
        if(SHARED.size()>MAX_SHARED)SHARED.remove(SHARED.keySet().iterator().next());
        return data;
    }
    static final class Builder {
        private final List<Page> pages=new ArrayList<>();
        private final int[] coordinates=new int[PAGE_SIZE*6];
        private final BlockState[] states=new BlockState[PAGE_SIZE];
        private int count,size;
        void add(int x,int y,int z,int ex,int ey,int ez,BlockState state) {
            int i=count*6;coordinates[i]=x;coordinates[i+1]=y;coordinates[i+2]=z;
            coordinates[i+3]=ex;coordinates[i+4]=ey;coordinates[i+5]=ez;states[count++]=Objects.requireNonNull(state);size++;
            if(count==PAGE_SIZE)flush();
        }
        void addAll(List<PredictionVoxySource.Box> boxes) {addRange(boxes,0,boxes.size());}
        void addRange(List<PredictionVoxySource.Box> boxes,int from,int to) {
            if(boxes instanceof PredictionVoxyBoxes b) for(int i=from;i<to;i++)
                add(b.coordinate(i,0),b.coordinate(i,1),b.coordinate(i,2),b.coordinate(i,3),b.coordinate(i,4),b.coordinate(i,5),b.state(i));
            else for(int i=from;i<to;i++) {var b=boxes.get(i);add(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),b.state());}
        }
        int size() {return size;}
        private void flush() {
            if(count==0)return;
            int[] origins={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE};
            for(int i=0;i<count*6;i++)origins[i%3]=Math.min(origins[i%3],coordinates[i]);
            boolean narrow=true;
            for(int i=0;i<count*6;i++)if((long)coordinates[i]-origins[i%3]>65535)narrow=false;
            char[] small=narrow?new char[count*6]:null;int[] wide=narrow?null:new int[count*6];
            for(int i=0;i<count*6;i++) {int delta=coordinates[i]-origins[i%3];if(narrow)small[i]=(char)delta;else wide[i]=delta;}
            var ids=new IdentityHashMap<BlockState,Integer>();
            int[] indices=new int[count];
            for(int i=0;i<count;i++) {var id=ids.get(states[i]);if(id==null){id=ids.size();ids.put(states[i],id);}indices[i]=id;}
            BlockState[] palette=new BlockState[ids.size()];ids.forEach((state,id)->palette[id]=state);
            byte[] compact=new byte[count];
            for(int i=0;i<count;i++)compact[i]=(byte)indices[i];
            pages.add(new Page(origins[0],origins[1],origins[2],share(new Data(small,wide,palette,compact))));
            Arrays.fill(states,0,count,null);count=0;
        }
        PredictionVoxyBoxes build() {flush();return size==0?EMPTY:shareContents(new PredictionVoxyBoxes(pages.toArray(Page[]::new),size));}
    }
}
