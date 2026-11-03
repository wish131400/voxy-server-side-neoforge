package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.function.BiConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Experiment only: exact coordinate Map contract backed by mutable vertical runs. */
public final class ColumnBlocks extends AbstractMap<BlockPos,BlockState> {
    public interface Runs { void accept(int x,int z,int bottom,int top,BlockState state); }
    private final Long2ObjectOpenHashMap<Column> columns=new Long2ObjectOpenHashMap<>();
    private int size;
    private boolean frozen;
    private static long key(int x,int z){return (long)x<<32 | z&0xffffffffL;}
    public static Map<BlockPos,BlockState> enabledMap(){return LodExperiment.spans?new ColumnBlocks():new HashMap<>();}
    private static final class Column {
        int count;
        int[] lo=new int[4],hi=new int[4];
        BlockState[] state=new BlockState[4];
        int find(int y){for(int i=0;i<count;i++){if(y<lo[i])break;if(y<hi[i])return i;}return -1;}
        void insert(int at,int bottom,int top,BlockState value){
            if(count==lo.length){lo=Arrays.copyOf(lo,count*2);hi=Arrays.copyOf(hi,count*2);state=Arrays.copyOf(state,count*2);}
            System.arraycopy(lo,at,lo,at+1,count-at);System.arraycopy(hi,at,hi,at+1,count-at);System.arraycopy(state,at,state,at+1,count-at);
            lo[at]=bottom;hi[at]=top;state[at]=value;count++;
        }
        void erase(int at){int n=count-at-1;System.arraycopy(lo,at+1,lo,at,n);System.arraycopy(hi,at+1,hi,at,n);System.arraycopy(state,at+1,state,at,n);state[--count]=null;}
        void merge(int at){
            if(at>0 && hi[at-1]==lo[at] && state[at-1]==state[at]){hi[at-1]=hi[at];erase(at);at--;}
            if(at+1<count && hi[at]==lo[at+1] && state[at]==state[at+1]){hi[at]=hi[at+1];erase(at+1);}
        }
    }
    public static Map<BlockPos,BlockState> mutableCopy(Map<BlockPos,BlockState> source){
        if(!LodExperiment.spans)return new HashMap<>(source);
        var result=new ColumnBlocks();result.add(source);return result;
    }
    public static Map<BlockPos,BlockState> snapshot(Map<BlockPos,BlockState> source){
        if(!LodExperiment.spans)return Map.copyOf(source);
        if(source instanceof ColumnBlocks c && c.frozen)return c;
        var result=new ColumnBlocks();result.add(source);result.frozen=true;return result;
    }
    public void add(Map<BlockPos,BlockState> source){
        if(source instanceof ColumnBlocks c)c.runs(this::putRun);
        else source.forEach(this::put);
    }
    public int size(){return size;}
    public boolean containsKey(Object key){if(!(key instanceof BlockPos p))return false;var c=columns.get(key(p.getX(),p.getZ()));return c!=null&&c.find(p.getY())>=0;}
    public BlockState get(Object key){if(!(key instanceof BlockPos p))return null;var c=columns.get(key(p.getX(),p.getZ()));int i=c==null?-1:c.find(p.getY());return i<0?null:c.state[i];}
    public BlockState put(BlockPos p,BlockState state){return put(p.getX(),p.getY(),p.getZ(),state);}
    public BlockState put(int x,int y,int z,BlockState state){
        if(frozen)throw new UnsupportedOperationException("frozen columns");
        var c=columns.computeIfAbsent(key(x,z),ignored->new Column());
        int at=0;while(at<c.count && c.hi[at]<=y)at++;
        BlockState previous=null;
        if(at<c.count && c.lo[at]<=y){
            previous=c.state[at];if(previous==state)return previous;
            int bottom=c.lo[at],top=c.hi[at];
            c.lo[at]=y;c.hi[at]=y+1;c.state[at]=state;
            if(bottom<y){c.insert(at,bottom,y,previous);at++;}
            if(y+1<top)c.insert(at+1,y+1,top,previous);
        }else{c.insert(at,y,y+1,state);size++;}
        c.merge(at);return previous;
    }
    public void putRun(int x,int z,int bottom,int top,BlockState state){
        // Preserve overwrite semantics; adjacent writes extend runs without allocating keys.
        for(int y=bottom;y<top;y++)put(x,y,z,state);
    }
    public BlockState remove(Object key){
        if(frozen)throw new UnsupportedOperationException("frozen columns");
        if(!(key instanceof BlockPos p))return null;long k=key(p.getX(),p.getZ());var c=columns.get(k);
        int at=c==null?-1:c.find(p.getY());if(at<0)return null;
        int bottom=c.lo[at],top=c.hi[at],y=p.getY();BlockState old=c.state[at];
        if(bottom==y && top==y+1)c.erase(at);
        else if(bottom==y)c.lo[at]++;
        else {c.hi[at]=y;if(y+1<top)c.insert(at+1,y+1,top,old);}
        size--;if(c.count==0)columns.remove(k);return old;
    }
    public void clear(){if(frozen)throw new UnsupportedOperationException();columns.clear();size=0;}
    public void runs(Runs visitor){
        var iterator=columns.long2ObjectEntrySet().fastIterator();
        while(iterator.hasNext()){var e=iterator.next();int x=(int)(e.getLongKey()>>32),z=(int)e.getLongKey();var c=e.getValue();
            for(int i=0;i<c.count;i++)visitor.accept(x,z,c.lo[i],c.hi[i],c.state[i]);}
    }
    public void orderedRuns(Runs visitor){
        long[] keys=columns.keySet().toLongArray();
        it.unimi.dsi.fastutil.longs.LongArrays.quickSort(keys,(a,b)->{int c=Integer.compare((int)a,(int)b);return c!=0?c:Integer.compare((int)(a>>32),(int)(b>>32));});
        for(long k:keys){var c=columns.get(k);for(int i=0;i<c.count;i++)visitor.accept((int)(k>>32),(int)k,c.lo[i],c.hi[i],c.state[i]);}
    }
    public long runCount(){long count=0;for(var c:columns.values())count+=c.count;return count;}
    public long bytes(){long bytes=96+columns.size()*48L;for(var c:columns.values())bytes+=48+c.lo.length*12L;return bytes;}
    public void forEach(BiConsumer<? super BlockPos,? super BlockState> visitor){runs((x,z,b,t,s)->{for(int y=b;y<t;y++)visitor.accept(new BlockPos(x,y,z),s);});}
    public Set<Entry<BlockPos,BlockState>> entrySet(){return new AbstractSet<>(){
        public int size(){return ColumnBlocks.this.size;}
        public Iterator<Entry<BlockPos,BlockState>> iterator(){
            var keys=columns.keySet().iterator();
            return new Iterator<>(){
                long key;Column column;int run,y;
                private boolean prepare(){while(column==null || run>=column.count){if(!keys.hasNext())return false;key=keys.nextLong();column=columns.get(key);run=0;y=column.lo[0];}return true;}
                public boolean hasNext(){return prepare();}
                public Entry<BlockPos,BlockState> next(){if(!prepare())throw new NoSuchElementException();var result=new SimpleImmutableEntry<>(new BlockPos((int)(key>>32),y,(int)key),column.state[run]);if(++y>=column.hi[run]){run++;if(run<column.count)y=column.lo[run];}return result;}
            };
        }
    };}
}
