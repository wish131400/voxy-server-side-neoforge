package dev.xantha.vss.client.prediction;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import dev.xantha.vss.api.worldgen.DensityFunctionTraits;

/** Single generation thread benchmark only. Bounded inputs; never substitutes biome identities. */
final class HorizontalSharing {
    static String mode;
    static Store current;
    static long handle;
    static void select(String value) {mode=value;current=null;handle=0;}
    static DensityFunction[] wrap(DensityFunction[] roots) {
        if(!mode.contains("java") && !mode.contains("cross"))return roots;
        Store store=new Store();current=store;
        DensityFunction[] result=roots.clone();
        for(int i=0;i<6;i++)result[i]=rewrite(roots[i],i,store,new IdentityHashMap<>());
        if(!Arrays.equals(store.cuts,new int[]{1,1,1,1,1,1}))throw new AssertionError("Unexpected cuts "+Arrays.toString(store.cuts));
        return result;
    }
    static DensityFunction rewrite(DensityFunction f,int lane,Store store,IdentityHashMap<DensityFunction,DensityFunction> seen) {
        if(seen.containsKey(f))return seen.get(f);
        var info=new PredictionRawDensity().inspect(f);
        if(!info.pure() || info.stateful())return f;
        if(info.horizontal()) {
            store.cuts[lane]++;
            var cut=new Cut(f,lane,store);seen.put(f,cut);return cut;
        }
        if(f instanceof DensityMemo.Memoized memo) {
            var child=rewrite(memo.delegate(),lane,store,seen);
            return new DensityMemo.Memoized(child,memo.slotId(),memo.owner(),memo.info);
        }
        if(!f.getClass().isRecord())return f;
        try {
            var parts=f.getClass().getRecordComponents();Object[] values=new Object[parts.length];Class<?>[] types=new Class<?>[parts.length];
            boolean changed=false;
            for(int i=0;i<parts.length;i++) {
                types[i]=parts[i].getType();Object old=PredictionRawDensity.recordValue(f,parts[i]),next=old;
                if(old instanceof DensityFunction child)next=rewrite(child,lane,store,seen);
                else if(old instanceof Holder<?> h && h.value() instanceof DensityFunction child) {
                    var replacement=rewrite(child,lane,store,seen);
                    if(replacement!=child)next=Holder.direct(replacement);
                }
                values[i]=next;changed|=next!=old;
            }
            if(!changed)return f;
            var constructor=f.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);
            var replacement=(DensityFunction)constructor.newInstance(values);seen.put(f,replacement);return replacement;
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    private record Cut(DensityFunction original,int lane,Store store) implements DensityFunction.SimpleFunction,DensityFunctionTraits.Aware {
        @Override public double compute(DensityFunction.FunctionContext c) {
            double value=store.get(original,lane,c);
            if(mode.contains("verify")) {
                double expected=original.compute(c);store.verified++;
                if(Double.doubleToRawLongBits(value)!=Double.doubleToRawLongBits(expected))throw new AssertionError("Raw climate mismatch lane="+lane+" at "+c.blockX()+","+c.blockY()+","+c.blockZ());
            }
            return value;
        }
        @Override public double minValue(){return original.minValue();}
        @Override public double maxValue(){return original.maxValue();}
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec(){return original.codec();}
        @Override public DensityFunctionTraits.Traits vssDensityTraits(){return DensityFunctionTraits.Traits.coordinate(true);}
    }
    static int slot(long key) {long h=key*0x9e3779b97f4a7c15L;h^=h>>>32;h*=0xbf58476d1ce4e5b9L;return (int)(h^(h>>>32))&16383;}
    static long key(int x,int z){return (long)x<<32|z&0xffffffffL;}
    static final class Store {
        final int[] cuts=new int[6],masks=new int[16384];
        final long[] keys=new long[16384];final double[] values=new double[16384*6];
        final ByteBuffer input=ByteBuffer.allocateDirect(16*8).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer output=ByteBuffer.allocateDirect(16*56).order(ByteOrder.LITTLE_ENDIAN);
        long hits,misses,nativeCalls,nativeLanes,verified,fallbacks,crossHits;
        int prepare(int x,int z) {long k=key(x,z);int s=slot(k);if(masks[s]!=0 && keys[s]!=k)masks[s]=0;keys[s]=k;return s;}
        double get(DensityFunction original,int lane,DensityFunction.FunctionContext c) {
            int x=c.blockX(),z=c.blockZ(),s=prepare(x,z),bit=1<<lane;
            if((masks[s]&bit)!=0){hits++;return values[s*6+lane];}
            misses++;
            if(mode.contains("cross") && handle!=0 && (masks[s]&64)==0) {
                int ox=Math.floorDiv(x,16)*16,oz=Math.floorDiv(z,16)*16;
                // Only quart aligned queries are batched; no coordinate rounding of a requested input.
                int count=(x%4==0 && z%4==0)?16:1;
                input.clear();
                for(int i=0;i<count;i++)input.putInt(count==1?x:ox+i/4*4).putInt(count==1?z:oz+i%4*4);
                if(RustWorldgenBackend.horizontalLookup(handle,input,output,count)!=count)throw new AssertionError("Shared input batch failed");
                nativeCalls++;
                for(int i=0;i<count;i++) {
                    int mask=(int)output.getLong(i*56);
                    int px=input.getInt(i*8),pz=input.getInt(i*8+4),target=prepare(px,pz);
                    for(int j=0;j<6;j++)if((mask&(1<<j))!=0){values[target*6+j]=output.getDouble(i*56+8+j*8);nativeLanes++;}
                    masks[target]|=mask|64;
                }
                s=prepare(x,z);if((masks[s]&bit)!=0){crossHits++;return values[s*6+lane];}
            }
            fallbacks++;double value=original.compute(c);values[s*6+lane]=value;masks[s]|=bit;return value;
        }
    }
    static void reset(long nativeHandle) {handle=nativeHandle;if(current!=null){current.hits=current.misses=current.nativeCalls=current.nativeLanes=current.verified=current.fallbacks=current.crossHits=0;}RustWorldgenBackend.horizontalStats(handle,true);}
    static Map<String,Object> stats() {
        var map=new LinkedHashMap<String,Object>();
        if(current!=null){map.put("javaHits",current.hits);map.put("javaMisses",current.misses);map.put("nativeBatches",current.nativeCalls);map.put("nativeLanesCopied",current.nativeLanes);map.put("rawValuesVerified",current.verified);map.put("javaFallbackComputations",current.fallbacks);map.put("crossMissesResolved",current.crossHits);map.put("cuts",current.cuts);map.put("javaCachePayloadBytes",16384*(8+4+6*8)+16*(8+56));}
        map.put("native",new com.google.gson.Gson().fromJson(RustWorldgenBackend.horizontalStats(handle,false),Object.class));return map;
    }
}
