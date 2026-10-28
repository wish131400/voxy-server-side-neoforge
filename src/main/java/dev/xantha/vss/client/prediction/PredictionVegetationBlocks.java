package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable full-coordinate blocks. Placement compatibility can use the Map
 * view; display workers read primitive positions and palette IDs directly. */
final class PredictionVegetationBlocks extends AbstractMap<BlockPos,BlockState> {
    private static final PredictionVegetationBlocks EMPTY=new Builder().build();
    private final int[] positions,table;
    private final BlockState[] palette;
    private final byte[] small;
    private final int[] wide;
    private final int size;
    private PredictionVegetationBlocks(Builder builder) {
        size=builder.size;positions=builder.positions.length==size*3?builder.positions:Arrays.copyOf(builder.positions,size*3);table=builder.table;
        palette=builder.palette.toArray(BlockState[]::new);
        if(palette.length<=256) {
            small=new byte[size];for(int i=0;i<size;i++)small[i]=(byte)builder.states[i];wide=null;
        } else {small=null;wide=Arrays.copyOf(builder.states,size);}
    }
    static PredictionVegetationBlocks copyOf(Map<BlockPos,BlockState> blocks) {
        if(blocks instanceof PredictionVegetationBlocks packed)return packed;
        if(blocks.isEmpty())return EMPTY;
        var builder=new Builder();blocks.forEach(builder::put);return builder.build();
    }
    int x(int i){return positions[i*3];}
    int y(int i){return positions[i*3+1];}
    int z(int i){return positions[i*3+2];}
    BlockState state(int i){return palette[small==null ? wide[i] : Byte.toUnsignedInt(small[i])];}
    long bytes(){return 128L+positions.length*4L+table.length*4L+palette.length*4L+(small==null?wide.length*4L:small.length);}
    public int size(){return size;}
    private static int hash(int x,int y,int z) {
        int hash=x*0x9e3779b9 ^ Integer.rotateLeft(y*0x85ebca6b,11) ^ Integer.rotateLeft(z*0xc2b2ae35,21);
        return it.unimi.dsi.fastutil.HashCommon.mix(hash);
    }
    private int find(int x,int y,int z) {
        int at=hash(x,y,z)&(table.length-1),entry;
        while((entry=table[at])!=0) {
            int i=entry-1;if(x(i)==x && y(i)==y && z(i)==z)return i;
            at=(at+1)&(table.length-1);
        }
        return -1;
    }
    public boolean containsKey(Object key){return key instanceof BlockPos p && find(p.getX(),p.getY(),p.getZ())>=0;}
    public BlockState get(Object key){if(!(key instanceof BlockPos p))return null;int i=find(p.getX(),p.getY(),p.getZ());return i<0?null:state(i);}
    public void forEach(BiConsumer<? super BlockPos,? super BlockState> consumer) {
        Objects.requireNonNull(consumer);for(int i=0;i<size;i++)consumer.accept(new BlockPos(x(i),y(i),z(i)),state(i));
    }
    public Set<Entry<BlockPos,BlockState>> entrySet(){return new AbstractSet<>() {
        public int size(){return size;}
        public Iterator<Entry<BlockPos,BlockState>> iterator(){return new Iterator<>() {
            int at;
            public boolean hasNext(){return at<size;}
            public Entry<BlockPos,BlockState> next(){if(!hasNext())throw new NoSuchElementException();int i=at++;return new SimpleImmutableEntry<>(new BlockPos(x(i),y(i),z(i)),state(i));}
        };}
    };}

    static final class Builder {
        private int[] positions,states,table;
        private final IdentityHashMap<BlockState,Integer> ids=new IdentityHashMap<>();
        private final ArrayList<BlockState> palette=new ArrayList<>();
        private int size;
        private boolean built;
        Builder(){this(16);}
        Builder(int expected) {
            int capacity=Math.max(16,Math.min(65536,expected));
            positions=new int[capacity*3];states=new int[capacity];
            int buckets=32;while(buckets*2L<capacity*3L)buckets*=2;table=new int[buckets];
        }
        void put(BlockPos pos,BlockState state){put(pos.getX(),pos.getY(),pos.getZ(),state);}
        void put(int x,int y,int z,BlockState state) {
            if(built)throw new IllegalStateException("Published block builder");
            Objects.requireNonNull(state);
            int at=hash(x,y,z)&(table.length-1),entry;
            while((entry=table[at])!=0) {
                int i=entry-1;
                if(positions[i*3]==x && positions[i*3+1]==y && positions[i*3+2]==z){states[i]=id(state);return;}
                at=(at+1)&(table.length-1);
            }
            if((size+1)*3>table.length*2){rehash();put(x,y,z,state);return;}
            if(size==states.length){states=Arrays.copyOf(states,size*2);positions=Arrays.copyOf(positions,size*6);}
            positions[size*3]=x;positions[size*3+1]=y;positions[size*3+2]=z;states[size]=id(state);
            table[at]=++size;
        }
        private int id(BlockState state) {
            Integer known=ids.get(state);if(known!=null)return known;
            int id=palette.size();palette.add(state);ids.put(state,id);return id;
        }
        private void rehash() {
            table=new int[table.length*2];
            for(int i=0;i<size;i++) {
                int at=hash(positions[i*3],positions[i*3+1],positions[i*3+2])&(table.length-1);
                while(table[at]!=0)at=(at+1)&(table.length-1);table[at]=i+1;
            }
        }
        PredictionVegetationBlocks build(){if(built)throw new IllegalStateException("Published block builder");built=true;return new PredictionVegetationBlocks(this);}
    }
}
