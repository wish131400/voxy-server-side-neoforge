package dev.xantha.vss.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Prediction-only liquid surface data, carried through the native mesher before greedy merging. */
public final class VoxyFluidSurface {
    private VoxyFluidSurface() { }
    private static final long FLUID = 1L << 52;
    private static final long MODEL = 0xffffL << 26;
    private static final long HEIGHT = 15L << 42;

    /** Raw Mapper's low 27 bits are unused. Only the last occupied liquid cell has a remainder. */
    public static long body(long value, BlockState state, int step) {
        return step>1 && state.getBlock() instanceof LiquidBlock ? value|16L : value;
    }
    public static long top(long value, BlockState state, int cap, int step) {
        return step > 1 && state.getBlock() instanceof LiquidBlock
                ? value | 16L | Math.floorMod(-cap, step) : value;
    }
    /** Maintained native fluid/biome-palette pipelines own their quad payload bits. */
    public static boolean legacyMesher(Class<?> factory) {
        for(var method:factory.getDeclaredMethods())
            if(method.getName().equals("applyBiomeBlend"))return false;
        return true;
    }
    public static long pack(long quad, long state, long metadata) {
        return (metadata & FLUID) != 0 ? quad | ((state & 15L) << 42) : quad;
    }
    @FunctionalInterface public interface Wall {
        void emit(int face, int x, int y, int z, long quad);
    }

    /** Add only the exposed strips native occupancy culling cannot see between unequal liquid caps.
     * Walls extend into the lower liquid, so they need no second height or floating-point mesh format.
     * Border comparisons use the same six native neighbor planes as ordinary face culling.
     */
    public static void transitions(long[] data, long[] raw, long[] neighbors, Wall output) {
        for(int y=0;y<32;y++)for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
            int i=y<<10|z<<5|x;
            if((data[i*2+1]&FLUID)==0)continue;
            long a=data[i*2];
            if(x<31) pair(a,data[(i+1)*2],data[(i+1)*2+1],5,x,y,z,x+1,y,z,output);
            if(z<31) pair(a,data[(i+32)*2],data[(i+32)*2+1],3,x,y,z,x,y,z+1,output);
            if(x==0) border(a,raw[i],neighbors[y*32+z],4,x,y,z,output);
            if(x==31) border(a,raw[i],neighbors[1024+y*32+z],5,x,y,z,output);
            if(z==0) border(a,raw[i],neighbors[4096+y*32+x],2,x,y,z,output);
            if(z==31) border(a,raw[i],neighbors[5120+y*32+x],3,x,y,z,output);
        }
    }
    private static void pair(long a,long b,long bm,int face,int x,int y,int z,int nx,int ny,int nz,Wall out) {
        if((bm&FLUID)==0 || ((a^b)&MODEL)!=0 || ((a|b)&HEIGHT)==0)return;
        int ad=(int)(a>>>42&15),bd=(int)(b>>>42&15);
        if(ad<bd)out.emit(face,x,y,z,a);
        else if(bd<ad)out.emit(face^1,nx,ny,nz,b);
    }
    private static void border(long quad,long self,long neighbor,int face,int x,int y,int z,Wall out) {
        // Raw equality also excludes different water levels/states and waterlogged solid blocks.
        if(((self^neighbor)&(0xfffffL<<27))!=0 || ((self|neighbor)&15)==0)return;
        if((self&15)<(neighbor&15))out.emit(face,x,y,z,quad);
    }

    private record Access(MethodHandle data,MethodHandle neighbors,MethodHandle mesher,
                          MethodHandle axis,MethodHandle position,MethodHandle offset,MethodHandle emit) { }
    private static final ClassValue<Access> ACCESS=new ClassValue<>() {
        @Override protected Access computeValue(Class<?> factory) {
            try {
                var lookup=MethodHandles.privateLookupIn(factory,MethodHandles.lookup());
                var mesher=factory.getDeclaredField("blockMesher").getType();
                var ml=MethodHandles.privateLookupIn(mesher,MethodHandles.lookup());
                return new Access(lookup.findGetter(factory,"sectionData",long[].class),
                        lookup.findGetter(factory,"neighboringFaces",long[].class),
                        lookup.findGetter(factory,"blockMesher",mesher),
                        ml.findSetter(mesher,"axis",int.class),ml.findSetter(mesher,"auxiliaryPosition",int.class),
                        ml.findSetter(mesher,"doAuxiliaryFaceOffset",boolean.class),
                        ml.findVirtual(mesher,"emitQuad",MethodType.methodType(void.class,int.class,int.class,int.class,int.class,long.class)));
            } catch(ReflectiveOperationException e) {throw new IllegalStateException("Unsupported native liquid mesher",e);}
        }
    };
    private static boolean neighborHeights(long[] neighbors) {
        for(int face=0;face<6;face++)if(face!=2 && face!=3)for(int i=face*1024;i<(face+1)*1024;i++)
            if((neighbors[i]&16L)!=0 && (neighbors[i]&15L)!=0)return true;
        return false;
    }
    public static void meshTransitions(Object factory,long[] raw,boolean ownFluid) {
        var a=ACCESS.get(factory.getClass());
        try {
            long[] neighbors=(long[])a.neighbors.invoke(factory);
            if(!ownFluid && !neighborHeights(neighbors))return;
            Object mesher=a.mesher.invoke(factory);
            a.offset.invoke(mesher,false);
            transitions((long[])a.data.invoke(factory),raw,neighbors,(face,x,y,z,quad)-> {
                try {
                    int axis=face>>1;
                    a.axis.invoke(mesher,axis);
                    a.position.invoke(mesher,axis==1?z:x);
                    // Mesher receives top-right 2D coordinates; X-axis emission swaps them internally.
                    a.emit.invoke(mesher,axis==1?x:z,y,1,1,(quad&~1L)|(face&1));
                } catch(Throwable e) {throw new IllegalStateException("Native liquid transition",e);}
            });
            a.offset.invoke(mesher,true);
        } catch(Throwable e) {throw new IllegalStateException("Native liquid transition",e);}
    }
}
