package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

/** One interpolation graph per surface chunk, using the original density and aquifer. */
final class PredictionChunkTerrain {
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessor heights;
    private final NoiseSettings noise;
    private final Aquifer.FluidPicker fluids;
    private final boolean batch;
    private final LongAdder chunks = new LongAdder(), orderedColumns = new LongAdder();

    PredictionChunkTerrain(ClientTerrainSampler source) {
        this(source.generatorContext(), source.randomStateContext(),
                LevelHeightAccessor.create(source.profile().minY(), source.profile().height()));
    }
    PredictionChunkTerrain(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        this.generator=generator; this.random=random; this.heights=heights;
        // Lightweight test/custom contexts can construct a wrapper before a generator is bound.
        noise=generator==null ? null : generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
        batch=noise!=null && 16 % noise.getCellWidth()==0 && FreeTerraForgedExteriorOrder.canBatch(random.router());
        fluids=batch ? fluids(generator) : null;
    }
    private static Aquifer.FluidPicker fluids(NoiseBasedChunkGenerator generator) {
        try {
            var field=Arrays.stream(NoiseBasedChunkGenerator.class.getDeclaredFields())
                    .filter(f->f.getType()==Supplier.class).findFirst().orElseThrow();
            field.setAccessible(true);
            var getter=MethodHandles.lookup().unreflectGetter(field)
                    .asType(MethodType.methodType(Supplier.class,NoiseBasedChunkGenerator.class));
            return (Aquifer.FluidPicker)((Supplier<?>)getter.invokeExact(generator)).get();
        } catch(RuntimeException | Error failure) { throw failure; }
        catch(Throwable failure) { throw new IllegalStateException("Prediction chunk fluid picker",failure); }
    }
    private static void active() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException();
    }
    void fill(ChunkPos pos, ProtoChunk chunk) {
        active(); chunks.increment();
        if(!batch) { fillOrdered(pos,chunk); return; }
        var settings=generator.generatorSettings().value();
        int width=noise.getCellWidth(), height=noise.getCellHeight();
        var work = FreeTerraForgedCompat.withSurfaceChunk(random, chunk,
                () -> new Work(16 / width, random, pos.getMinBlockX(), pos.getMinBlockZ(), noise, settings, fluids));
        var cursor=new BlockPos.MutableBlockPos();
        work.initializeForFirstCellX();
        try {
            for(int cx=0;cx<16/width;cx++) {
                active(); work.advanceCellX(cx);
                for(int cz=0;cz<16/width;cz++)for(int cy=work.cellsY()-1;cy>=0;cy--) {
                    active(); work.selectCellYZ(cy,cz);
                    for(int dy=height-1;dy>=0;dy--) {
                        int y=(work.firstCellY()+cy)*height+dy;
                        work.updateForY(y,(double)dy/height);
                        for(int dx=0;dx<width;dx++) {
                            int x=pos.getMinBlockX()+cx*width+dx;
                            work.updateForX(x,(double)dx/width);
                            for(int dz=0;dz<width;dz++) {
                                int z=pos.getMinBlockZ()+cz*width+dz;
                                work.updateForZ(z,(double)dz/width);
                                var state=work.state();
                                if(state==null)state=settings.defaultBlock();
                                if(!state.isAir())chunk.setBlockState(cursor.set(x,y,z),state,false);
                            }
                        }
                    }
                }
                work.swapSlices();
            }
        } finally { work.stopInterpolation(); }
    }
    private void fillOrdered(ChunkPos pos,ProtoChunk chunk) {
        var cursor=new BlockPos.MutableBlockPos();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            active(); orderedColumns.increment();
            int bx=pos.getMinBlockX()+x,bz=pos.getMinBlockZ()+z;
            var raw=generator.getBaseColumn(bx,bz,heights,random);
            for(int y=heights.getMinBuildHeight();y<heights.getMaxBuildHeight();y++) {
                var state=raw.getBlock(y);
                if(!state.isAir())chunk.setBlockState(cursor.set(bx,y,bz),state,false);
            }
        }
    }
    String diagnostics() { return "mode="+(batch?"chunkBatch":"orderedColumns")+",chunks="+chunks.sum()+",orderedColumns="+orderedColumns.sum(); }
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES=new DensityFunctions.BeardifierOrMarker() {
        @Override public double compute(DensityFunction.FunctionContext point){return 0;}
        @Override public double minValue(){return 0;}
        @Override public double maxValue(){return 0;}
    };
    private static final class Work extends NoiseChunk {
        Work(int count,RandomState random,int x,int z,NoiseSettings noise,NoiseGeneratorSettings settings,Aquifer.FluidPicker fluids) {
            super(count,random,x,z,noise,NO_STRUCTURES,settings,fluids,Blender.empty());
        }
        int firstCellY(){return cellNoiseMinY;}
        int cellsY(){return cellCountY;}
        BlockState state(){return getInterpolatedState();}
    }
}
