package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
    private final boolean reuseContexts;
    // Eight mapped graphs per worker, scoped to this immutable sampler snapshot.
    // Opaque mod nodes keep the original per-column iterator below.
    private final ThreadLocal<ContextCache> contexts = ThreadLocal.withInitial(ContextCache::new);
    private final boolean cacheRejectedRoofs = !"off".equals(System.getProperty("vss.javaRejectedRoofs"));
    private final ThreadLocal<PredictionHeightCache> rejectedRoofs = ThreadLocal.withInitial(PredictionHeightCache::new);

    PredictionJavaExterior(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        this.generator=generator;this.random=random;this.heights=heights;
        noise=generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
        ordered=PredictionDensityOrder.requiresColumnOrder(random.router());
        reuseContexts=!"off".equals(System.getProperty("vss.javaExteriorContexts"))
                && generator.getClass()==NoiseBasedChunkGenerator.class
                && ContextReset.AVAILABLE && PredictionDensityOrder.canReuseColumnContext(random.router());
        try { fluids=(Aquifer.FluidPicker)((Supplier<?>)Access.FLUIDS.invokeExact(generator)).get(); }
        catch(RuntimeException|Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException("Minecraft exterior fluid picker",failure);}
    }
    boolean ordered() { return ordered; }
    boolean reusesContexts() { return reuseContexts; }
    long contextBuilds() { return contexts.get().builds; }

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
                var work=workspace(bx,bz);
                boolean started=false;
                try {
                    work.reset(false);
                    started=true;
                    work.initializeForFirstCellX();
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
                } catch(RuntimeException|Error failure) {
                    discard(work);throw failure;
                } finally {if(started)work.stopInterpolation();}
            }
        return occupied;
    }

    /** Keep stateful/modded traversal but stop at the requested floor; no NoiseColumn allocation. */
    private boolean[] ordered(int x,int z,int step,int bottom,int top,boolean[] occupied,BooleanSupplier valid) {
        int firstY=(Math.floorDiv(noise.minY(),noise.getCellHeight())+noise.height()/noise.getCellHeight())*noise.getCellHeight()-1;
        for(int dz=0;dz<step;dz++)for(int dx=0;dx<step;dx++) {
            check(valid);
            var probe=new Probe(firstY,bottom,top,dx==0&&dz==0,occupied,valid);
            int xx=x+dx,zz=z+dz;
            if(reuseContexts&&!contexts.get().unusable) {
                int width=noise.getCellWidth();
                var work=workspace(Math.floorDiv(xx,width)*width,Math.floorDiv(zz,width)*width);
                if(work.reusable()) replay(work,xx,zz,probe,valid);
                else original(xx,zz,probe);
            } else original(xx,zz,probe);
            if(probe.badRoof)return null;
            boolean complete=true;for(boolean solid:occupied)if(!solid){complete=false;break;}
            if(complete)return occupied;
        }
        return occupied;
    }

    /** Replays the vanilla column iterator, including cells above the requested roof. */
    private void replay(Work work,int x,int z,Probe probe,BooleanSupplier valid) {
        int height=noise.getCellHeight(),width=noise.getCellWidth();
        int minCell=Math.floorDiv(noise.minY(),height),count=noise.height()/height;
        double fx=(double)Math.floorMod(x,width)/width,fz=(double)Math.floorMod(z,width)/width;
        boolean started=false;
        try {
            // Ordered auxiliary density caches must begin as they would in a new
            // NoiseChunk. Random aquifer centers are independent of density state.
            work.reset(true);
            started=true;
            work.initializeForFirstCellX();
            work.advanceCellX(0);
            for(int cy=count-1;cy>=0;cy--) {
                check(valid);work.selectCellYZ(cy,0);
                for(int dy=height-1;dy>=0;dy--) {
                    int y=(minCell+cy)*height+dy;
                    work.updateForY(y,(double)dy/height);
                    work.updateForX(x,fx);work.updateForZ(z,fz);
                    BlockState state=work.state();
                    if(probe.test(state==null?generator.generatorSettings().value().defaultBlock():state))return;
                }
            }
        } catch(RuntimeException|Error failure) {
            discard(work);throw failure;
        } finally {if(started)work.stopInterpolation();}
    }

    private void original(int x,int z,Probe probe) {
        try { OptionalInt ignored=(OptionalInt)Access.ITERATE.invokeExact(generator,heights,random,x,z,
                (MutableObject<?>)null,(Predicate<BlockState>)probe); }
        catch(RuntimeException|Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException("Minecraft exterior range iterator",failure);}
    }

    private Work workspace(int x,int z) {
        var cache=contexts.get();
        int slot=cache.slot(x,z);
        Work work=reuseContexts?cache.work[slot]:null;
        if(work==null||cache.x[slot]!=x||cache.z[slot]!=z) {
            work=new Work(random,x,z,noise,generator.generatorSettings().value(),fluids);
            cache.builds++;
            if(reuseContexts&&work.reusable()) {
                cache.work[slot]=work;cache.x[slot]=x;cache.z[slot]=z;
            } else if(reuseContexts) {
                // A transformed/unsupported wrapper must not cause both a trial
                // graph build and an original iterator build on every column.
                cache.unusable=true;Arrays.fill(cache.work,null);
            }
        }
        return work;
    }

    private void discard(Work work) {
        if(!reuseContexts)return;
        var cache=contexts.get();
        for(int i=0;i<cache.work.length;i++)if(cache.work[i]==work)cache.work[i]=null;
    }

    private static final class ContextCache {
        private final Work[] work=new Work[8];
        private final int[] x=new int[8],z=new int[8];
        private long builds;private int last,next;private boolean unusable;
        int slot(int x,int z) {
            if(work[last]!=null&&this.x[last]==x&&this.z[last]==z)return last;
            for(int i=0;i<work.length;i++)
                if(work[i]!=null&&this.x[i]==x&&this.z[i]==z)return last=i;
            for(int i=0;i<work.length;i++)if(work[i]==null)return last=i;
            int slot=next;next=(next+1)&(work.length-1);return last=slot;
        }
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
        // wrap() is called from NoiseChunk's constructor, before our initializers.
        private ArrayList<NodeReset> resets;
        private NodeReset ownReset;
        private boolean unsupportedReset;
        Work(RandomState random,int x,int z,NoiseSettings noise,NoiseGeneratorSettings settings,Aquifer.FluidPicker fluids){
            super(1,random,x,z,noise,NO_STRUCTURES,settings,fluids,Blender.empty());
            if(settings.isAquifersEnabled()&&aquifer().getClass()!=Aquifer.NoiseBasedAquifer.class)
                unsupportedReset=true;
            if(!unsupportedReset&&ContextReset.AVAILABLE) {
                try {
                    ownReset=new NodeReset(this,ContextReset.CONTEXT.fields);
                    ownReset.capture();
                    // FlatCache construction can already have evaluated nested
                    // caches. Preserve that exact state, rather than inventing
                    // invalid cache keys on the next column.
                    if(resets!=null)for(NodeReset reset:resets)reset.capture();
                } catch(RuntimeException unsupported) {unsupportedReset=true;}
                catch(Error failure){throw failure;}
                catch(Throwable unsupported) {unsupportedReset=true;}
            }
        }
        BlockState state(){return getInterpolatedState();}

        boolean reusable(){return ContextReset.AVAILABLE&&!unsupportedReset;}

        @Override protected DensityFunction wrap(DensityFunction value) {
            DensityFunction mapped=super.wrap(value);
            if(value instanceof DensityFunctions.MarkerOrMarked marker) {
                String kind=((Enum<?>)marker.type()).name();
                if(kind.equals("Cache2D")||kind.equals("CacheOnce")
                        ||kind.equals("CacheAllInCell")||kind.equals("Interpolated")) {
                    if(resets==null)resets=new ArrayList<>();
                    for(NodeReset reset:resets)if(reset.node==mapped)return mapped;
                    NodeReset reset=ContextReset.node(mapped,kind);
                if(reset==null)unsupportedReset=true;else resets.add(reset);
                }
            }
            return mapped;
        }

        void reset(boolean ordered) {
            if(!reusable())return;
            try {
                ownReset.restore();
                if(resets!=null)for(NodeReset reset:resets)reset.restore();
                if(ordered) {
                    ((it.unimi.dsi.fastutil.longs.Long2IntMap)ContextReset.PRELIMINARY.invokeExact((NoiseChunk)this)).clear();
                    if(aquifer().getClass()==Aquifer.NoiseBasedAquifer.class) {
                        Object aquifer=aquifer();
                        Arrays.fill((Aquifer.FluidStatus[])ContextReset.FLUID_STATUSES.invokeExact(aquifer),null);
                    }
                }
            } catch(RuntimeException|Error failure){throw failure;}
            catch(Throwable failure){throw new IllegalStateException("Minecraft exterior context reset",failure);}
        }
    }

    private record FieldAccess(MethodHandle get,MethodHandle set) {}
    private record NodeShape(Class<?> type,FieldAccess[] fields) {}

    private static final class NodeReset {
        private final Object node;
        private final FieldAccess[] fields;
        private final Object[] values,snapshots;
        NodeReset(Object node,FieldAccess[] fields) {
            this.node=node;this.fields=fields;
            values=new Object[fields.length];snapshots=new Object[fields.length];
        }
        void capture() throws Throwable {
            for(int i=0;i<fields.length;i++) {
                Object value=fields[i].get.invokeExact(node);
                values[i]=value;
                if(value instanceof double[] array)snapshots[i]=array.clone();
                else if(value instanceof double[][] arrays) {
                    double[][] copy=new double[arrays.length][];
                    for(int j=0;j<arrays.length;j++)copy[j]=arrays[j].clone();
                    snapshots[i]=copy;
                }
            }
        }
        void restore() throws Throwable {
            for(int i=0;i<fields.length;i++) {
                Object value=values[i];
                if(snapshots[i] instanceof double[] snapshot)
                    System.arraycopy(snapshot,0,(double[])value,0,snapshot.length);
                else if(snapshots[i] instanceof double[][] snapshot) {
                    double[][] arrays=(double[][])value;
                    for(int j=0;j<snapshot.length;j++)
                        System.arraycopy(snapshot[j],0,arrays[j],0,snapshot[j].length);
                }
                // Values are boxed once when the graph is built. Array storage
                // is restored in place, without per-column reset allocations.
                if(fields[i].set!=null)fields[i].set.invokeExact(node,value);
            }
        }
    }

    /** Shape-based field access also works after Forge method/field remapping. */
    private static final class ContextReset {
        private static final MethodHandle PRELIMINARY,FLUID_STATUSES;
        private static final NodeShape CONTEXT,CACHE_2D,CACHE_ONCE,CACHE_CELL,INTERPOLATOR;
        private static final boolean AVAILABLE;
        static {
            MethodHandle preliminary=null,statuses=null;boolean available=false;
            NodeShape context=null,cache2d=null,cacheOnce=null,cacheCell=null,interpolator=null;
            try {
                var p=Arrays.stream(NoiseChunk.class.getDeclaredFields())
                        .filter(f->it.unimi.dsi.fastutil.longs.Long2IntMap.class.isAssignableFrom(f.getType())).toList();
                var s=Arrays.stream(Aquifer.NoiseBasedAquifer.class.getDeclaredFields())
                        .filter(f->f.getType()==Aquifer.FluidStatus[].class).toList();
                if(p.size()==1&&s.size()==1) {
                    p.get(0).setAccessible(true);s.get(0).setAccessible(true);
                    preliminary=MethodHandles.lookup().unreflectGetter(p.get(0))
                            .asType(MethodType.methodType(it.unimi.dsi.fastutil.longs.Long2IntMap.class,NoiseChunk.class));
                    statuses=MethodHandles.lookup().unreflectGetter(s.get(0))
                            .asType(MethodType.methodType(Aquifer.FluidStatus[].class,Object.class));
                    context=shape(NoiseChunk.class,Map.of(int.class,7,long.class,3,boolean.class,2,Blender.BlendingOutput.class,1));
                    cache2d=shape(Class.forName("net.minecraft.world.level.levelgen.NoiseChunk$Cache2D"),
                            Map.of(long.class,1,double.class,1));
                    cacheOnce=shape(Class.forName("net.minecraft.world.level.levelgen.NoiseChunk$CacheOnce"),
                            Map.of(long.class,2,double.class,1,double[].class,1));
                    cacheCell=shape(Class.forName("net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell"),
                            Map.of(double[].class,1));
                    interpolator=shape(Class.forName("net.minecraft.world.level.levelgen.NoiseChunk$NoiseInterpolator"),
                            Map.of(double.class,15,double[][].class,2));
                    available=context!=null&&cache2d!=null&&cacheOnce!=null&&cacheCell!=null&&interpolator!=null;
                }
            } catch(ReflectiveOperationException|RuntimeException unsupported) { /* Original iterator remains available. */ }
            PRELIMINARY=preliminary;FLUID_STATUSES=statuses;AVAILABLE=available;
            CONTEXT=context;CACHE_2D=cache2d;CACHE_ONCE=cacheOnce;CACHE_CELL=cacheCell;INTERPOLATOR=interpolator;
        }

        static NodeReset node(DensityFunction node,String kind) {
            NodeShape shape=switch(kind) {
                case "Cache2D" -> CACHE_2D;
                case "CacheOnce" -> CACHE_ONCE;
                case "CacheAllInCell" -> CACHE_CELL;
                case "Interpolated" -> INTERPOLATOR;
                default -> null;
            };
            return shape!=null&&node.getClass()==shape.type?new NodeReset(node,shape.fields):null;
        }

        private static NodeShape shape(Class<?> type,Map<Class<?>,Integer> expected) throws ReflectiveOperationException {
            var fields=Arrays.stream(type.getDeclaredFields()).filter(ContextReset::mutableState).toList();
            var actual=new HashMap<Class<?>,Integer>();
            for(Field field:fields)actual.merge(field.getType(),1,Integer::sum);
            if(!actual.equals(expected))return null;
            FieldAccess[] access=new FieldAccess[fields.size()];
            for(int i=0;i<access.length;i++) {
                Field field=fields.get(i);field.setAccessible(true);
                MethodHandle get=MethodHandles.lookup().unreflectGetter(field)
                        .asType(MethodType.methodType(Object.class,Object.class));
                MethodHandle set=Modifier.isFinal(field.getModifiers())?null:MethodHandles.lookup().unreflectSetter(field)
                        .asType(MethodType.methodType(void.class,Object.class,Object.class));
                access[i]=new FieldAccess(get,set);
            }
            return new NodeShape(type,access);
        }

        private static boolean mutableState(Field field) {
            return !Modifier.isStatic(field.getModifiers())&&(!Modifier.isFinal(field.getModifiers())
                    ||field.getType()==double[].class||field.getType()==double[][].class);
        }
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
