package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.apache.commons.lang3.mutable.MutableObject;

/** Exact partial-height occupancy through Minecraft's own NoiseChunk and aquifer. */
final class PredictionJavaExterior {
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessor heights;
    private final NoiseSettings noise;
    private final Aquifer.FluidPicker fluids;
    private final boolean ordered;
    private final boolean cacheRejectedRoofs = !"off".equals(System.getProperty("vss.javaRejectedRoofs"));
    private final ThreadLocal<PredictionHeightCache> rejectedRoofs = ThreadLocal.withInitial(PredictionHeightCache::new);

    PredictionJavaExterior(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        this.generator=generator;this.random=random;this.heights=heights;
        noise=generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
        ordered=PredictionDensityOrder.requiresColumnOrder(random.router());
        try { fluids=(Aquifer.FluidPicker)((Supplier<?>)Access.FLUIDS.invokeExact(generator)).get(); }
        catch(RuntimeException|Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException("Minecraft exterior fluid picker",failure);}
    }
    boolean ordered() { return ordered; }

    boolean[] sample(int x,int z,int step,int bottom,int top,BooleanSupplier valid) {
        if(top<=bottom)return null;
        if(step!=1&&step!=2&&step!=4)throw new IllegalArgumentException("exterior footprint step");
        int cellHeight=noise.getCellHeight(),cellWidth=noise.getCellWidth();
        int minCell=Math.floorDiv(noise.minY(),cellHeight),count=noise.height()/cellHeight;
        if(bottom<noise.minY()||top>(minCell+count)*cellHeight)return null;
        check(valid);
        if (!ordered && cacheRejectedRoofs && rejectedRoofs.get().get(x,z) == top) return null;
        boolean[] occupied=new boolean[top-bottom];
        if(ordered)return ordered(x,z,step,bottom,top,occupied,valid);
        int missing=occupied.length;
        // Up to four horizontal noise cells. Anchor's cell is visited first.
        for(int cz=Math.floorDiv(z,cellWidth);cz<=Math.floorDiv(z+step-1,cellWidth);cz++)
            for(int cx=Math.floorDiv(x,cellWidth);cx<=Math.floorDiv(x+step-1,cellWidth);cx++) {
                check(valid);
                int bx=cx*cellWidth,bz=cz*cellWidth;
                var work=new Work(random,bx,bz,noise,generator.generatorSettings().value(),fluids);
                work.initializeForFirstCellX();
                try {
                    work.advanceCellX(0);
                    for(int cy=Math.floorDiv(top-1,cellHeight);cy>=Math.floorDiv(bottom,cellHeight);cy--) {
                        check(valid);
                        int low=Math.max(bottom,cy*cellHeight),high=Math.min(top,(cy+1)*cellHeight);
                        boolean needed=false;
                        for(int y=low;y<high;y++)if(!occupied[y-bottom]){needed=true;break;}
                        if(!needed)continue;
                        work.selectCellYZ(cy-minCell,0);
                        for(int y=high-1;y>=low;y--) {
                            if(occupied[y-bottom])continue;
                            work.updateForY(y,(double)Math.floorMod(y,cellHeight)/cellHeight);
                            boolean found=false;
                            for(int zz=Math.max(z,bz);zz<Math.min(z+step,bz+cellWidth)&&!found;zz++)
                                for(int xx=Math.max(x,bx);xx<Math.min(x+step,bx+cellWidth);xx++) {
                                    work.updateForX(xx,(double)(xx-bx)/cellWidth);
                                    work.updateForZ(zz,(double)(zz-bz)/cellWidth);
                                    BlockState state=work.state();
                                    boolean solid=state==null?!generator.generatorSettings().value().defaultBlock().isAir():!state.isAir();
                                    if(xx==x&&zz==z&&y==top-1&&!solid) {
                                        // The failed anchor is independent of the footprint
                                        // and requested bottom. Cache only this exact roof Y.
                                        if (cacheRejectedRoofs) rejectedRoofs.get().put(x,z,top);
                                        return null;
                                    }
                                    if(solid){occupied[y-bottom]=true;missing--;found=true;break;}
                                }
                        }
                        if(missing==0)return occupied;
                    }
                } finally {work.stopInterpolation();}
            }
        return occupied;
    }

    /** Keep stateful/modded traversal but stop at the requested floor; no NoiseColumn allocation. */
    private boolean[] ordered(int x,int z,int step,int bottom,int top,boolean[] occupied,BooleanSupplier valid) {
        int firstY=(Math.floorDiv(noise.minY(),noise.getCellHeight())+noise.height()/noise.getCellHeight())*noise.getCellHeight()-1;
        for(int dz=0;dz<step;dz++)for(int dx=0;dx<step;dx++) {
            check(valid);
            var probe=new Probe(firstY,bottom,top,dx==0&&dz==0,occupied,valid);
            try { OptionalInt ignored=(OptionalInt)Access.ITERATE.invokeExact(generator,heights,random,x+dx,z+dz,
                    (MutableObject<?>)null,(Predicate<BlockState>)probe); }
            catch(RuntimeException|Error failure){throw failure;}
            catch(Throwable failure){throw new IllegalStateException("Minecraft exterior range iterator",failure);}
            if(probe.badRoof)return null;
            boolean complete=true;for(boolean solid:occupied)if(!solid){complete=false;break;}
            if(complete)return occupied;
        }
        return occupied;
    }
    private static void check(BooleanSupplier valid){if(Thread.currentThread().isInterrupted()||!valid.getAsBoolean())throw new CancellationException();}
    private static final class Probe implements Predicate<BlockState> {
        private int y; private final int bottom,top;private final boolean anchor;
        private final boolean[] occupied;private final BooleanSupplier valid;private boolean badRoof;
        Probe(int y,int bottom,int top,boolean anchor,boolean[] occupied,BooleanSupplier valid){
            this.y=y;this.bottom=bottom;this.top=top;this.anchor=anchor;this.occupied=occupied;this.valid=valid;
        }
        @Override public boolean test(BlockState state){
            if((y&15)==0)check(valid);
            int current=y--;
            if(current<top && current>=bottom){
                boolean solid=!state.isAir();
                if(anchor&&current==top-1&&!solid){badRoof=true;return true;}
                occupied[current-bottom]|=solid;
            }
            return current<=bottom;
        }
    }
    private static final class Work extends NoiseChunk {
        Work(RandomState random,int x,int z,NoiseSettings noise,NoiseGeneratorSettings settings,Aquifer.FluidPicker fluids){
            super(1,random,x,z,noise,NO_STRUCTURES,settings,fluids,Blender.empty());
        }
        BlockState state(){return getInterpolatedState();}
    }
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES=new DensityFunctions.BeardifierOrMarker(){
        @Override public double compute(DensityFunction.FunctionContext c){return 0;}
        @Override public double minValue(){return 0;}
        @Override public double maxValue(){return 0;}
    };
    private static final class Access {
        static final MethodHandle ITERATE,FLUIDS;
        static {
            try {
                Class<NoiseBasedChunkGenerator> type=NoiseBasedChunkGenerator.class;
                var field=Arrays.stream(type.getDeclaredFields()).filter(f->f.getType()==Supplier.class).findFirst().orElseThrow();
                field.setAccessible(true);FLUIDS=MethodHandles.lookup().unreflectGetter(field)
                    .asType(MethodType.methodType(Supplier.class,NoiseBasedChunkGenerator.class));
                Class<?>[] parameters={LevelHeightAccessor.class,RandomState.class,int.class,int.class,MutableObject.class,Predicate.class};
                var method=Arrays.stream(type.getDeclaredMethods()).filter(m->m.getReturnType()==OptionalInt.class&&Arrays.equals(m.getParameterTypes(),parameters)).findFirst().orElseThrow();
                method.setAccessible(true);ITERATE=MethodHandles.lookup().unreflect(method);
            }catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
        }
    }
}
