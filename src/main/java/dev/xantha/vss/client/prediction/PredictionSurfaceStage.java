package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Runs transformed surface hooks on native occupancy, retaining only immutable vertical runs. */
final class PredictionSurfaceStage implements AutoCloseable {
    private static final int MAX_CHUNKS=64;
    private static final long MAX_BYTES=16L*1024*1024;
    private final ClientTerrainSampler context;
    private final BiConsumer<ChunkPos,ProtoChunk> terrain;
    private final LevelHeightAccessor heights;
    private final LinkedHashMap<Long,Result> completed=new LinkedHashMap<>(64,.75f,true);
    private final ConcurrentHashMap<Long,CompletableFuture<Result>> pending=new ConcurrentHashMap<>();
    private long retainedBytes;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean warned=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.LongAdder failures=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder builds=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder hits=new java.util.concurrent.atomic.LongAdder(),evictions=new java.util.concurrent.atomic.LongAdder();
    long builds(){return builds.sum();}
    private record Result(PredictionColumnVolume[] columns,long bytes) { }

    PredictionSurfaceStage(ClientTerrainSampler context,BiConsumer<ChunkPos,ProtoChunk> terrain) {
        this.context=context;this.terrain=terrain;
        heights=LevelHeightAccessor.create(context.profile().minY(),context.profile().height());
    }
    private void active() {
        if(closed || Thread.currentThread().isInterrupted())throw new CancellationException("Surface context closed");
    }
    PredictionColumnVolume column(int x,int z) {
        return chunk(x,z).columns[(z&15)*16+(x&15)];
    }
    private Result chunk(int x,int z) {
        active();long key=(long)(x>>4)<<32 | (z>>4)&0xffffffffL;
        Result result;
        synchronized(completed){result=completed.get(key);}
        if(result!=null)hits.increment();
        if(result==null) {
            var future=new CompletableFuture<Result>();
            var previous=pending.putIfAbsent(key,future);
            if(previous!=null) {
                try { result=previous.join(); }
                catch(CompletionException failure) {
                    if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                    throw failure;
                }
            } else try {
                // A producer may have completed between the first lookup and admission.
                synchronized(completed){result=completed.get(key);}
                if(result==null)result=generate(new ChunkPos(x>>4,z>>4));
                active();
                synchronized(completed) {
                    active();
                    Result old=completed.put(key,result);
                    retainedBytes+=result.bytes-(old==null?0:old.bytes);
                    while(completed.size()>MAX_CHUNKS || retainedBytes>MAX_BYTES) {
                        retainedBytes-=completed.remove(completed.keySet().iterator().next()).bytes;
                        evictions.increment();
                    }
                }
                future.complete(result);
            } catch(Throwable failure) { future.completeExceptionally(failure);throw failure; }
            finally { pending.remove(key,future); }
        }
        active();return result;
    }
    ClientColumnSample sample(int x,int z) {
        return sample(column(x,z),x,z);
    }
    private ClientColumnSample sample(PredictionColumnVolume volume,int x,int z) {
        var sample=volume.asSample();
        int floor=sample.hasSurface()?sample.surfaceY():context.profile().minY();
        int water=floor,fluid=0;
        for(int i=volume.size()-1;i>=0;i--)if(volume.fluid(i)!=0 && volume.top(i)>water) {
            water=volume.top(i);fluid=volume.fluid(i);break;
        }
        int index=context.captureBiomeIndex(context.noiseBiome(x>>2,(Math.max(floor,water)-1)>>2,z>>2),-1);
        return new ClientColumnSample(floor,water,index,sample.topBlockIndex(),0,0,0,0,fluid,sample.flags(),0,
                volume.blockAt(floor-2),volume.blockAt(floor-7),sample.surfaceBottom(),sample.lowerTop(),
                sample.lowerBottom(),sample.spanFloor(),volume);
    }
    /** Hold one immutable result throughout the rectangle, even if another worker evicts its LRU entry. */
    void refineChunk(int baseX,int baseZ,int step,int grid,ClientColumnSample[] samples,
            int fromX,int toX,int fromZ,int toZ,java.util.function.BooleanSupplier current) {
        Result result=null;
        for(int z=fromZ;z<toZ;z++)for(int x=fromX;x<toX;x++) {
            int i=z*grid+x;
            if(!PredictionSurfaceRefinement.required(samples[i]))continue;
            active();if(!current.getAsBoolean())throw new CancellationException();
            int bx=baseX+(x-VssLodLayout.SAMPLE_MARGIN)*step,bz=baseZ+(z-VssLodLayout.SAMPLE_MARGIN)*step;
            if(result==null)result=chunk(bx,bz);
            samples[i]=sample(result.columns[(bz&15)*16+(bx&15)],bx,bz);
        }
    }
    String diagnostics() {
        synchronized(completed){return "builds="+builds.sum()+",hits="+hits.sum()+",evictions="+evictions.sum()
                +",entries="+completed.size()+",bytes="+retainedBytes+",failures="+failures.sum();}
    }
    private Result generate(ChunkPos pos) {
        active();builds.increment();
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,heights,
                context.decorationAccess().registryOrThrow(Registries.BIOME),null);
        terrain.accept(pos,chunk);
        chunk.fillBiomesFromNoise((x,y,z,sampler)->context.noiseBiome(x,y,z),context.randomStateContext().sampler());
        try { apply(chunk); }
        catch(CancellationException cancelled){throw cancelled;}
        catch(RuntimeException failure) {
            failures.increment();
            if(warned.compareAndSet(false,true))dev.xantha.vss.common.VSSLogger.warn(
                    "VSS isolated surface hook requires additional world state; retaining native terrain and Java rules for affected chunks",failure);
            // Discard partially changed geometry before applying the rule-only fallback.
            chunk=new ProtoChunk(pos,UpgradeData.EMPTY,heights,
                    context.decorationAccess().registryOrThrow(Registries.BIOME),null);
            terrain.accept(pos,chunk);
            return extract(chunk,true);
        }
        active();return extract(chunk,false);
    }
    private Result extract(ProtoChunk chunk,boolean fallback) {
        var result=new PredictionColumnVolume[256];long bytes=48+256*8;
        var cursor=new BlockPos.MutableBlockPos();var registry=BuiltInRegistries.BLOCK;
        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            active();int bx=chunk.getPos().getMinBlockX()+x,bz=chunk.getPos().getMinBlockZ()+z;
            var column=PredictionColumnVolume.sample(heights.getMinBuildHeight(),heights.getHeight(),y->{
                var state=chunk.getBlockState(cursor.set(bx,y,bz));return state.isAir()?-1:registry.getId(state.getBlock());
            },id->{var state=registry.byId(id).defaultBlockState();return state.getFluidState().isEmpty()?0:state.is(Blocks.LAVA)?2:1;});
            if(fallback && column.size()!=0) column=context.resolveInterior(column.asSample(),bx,bz,
                    (xx,zz)->chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,xx&15,zz&15)+1).volume();
            result[z*16+x]=column;bytes+=column.bytes();
        }
        return new Result(result,bytes);
    }
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES=new DensityFunctions.BeardifierOrMarker() {
        @Override public double compute(DensityFunction.FunctionContext position){return 0;}
        @Override public double minValue(){return 0;}
        @Override public double maxValue(){return 0;}
    };
    private void apply(ProtoChunk chunk) {
        var generator=context.generatorContext();var random=context.randomStateContext();
        var settings=generator.generatorSettings().value();
        var fluid=new Aquifer.FluidStatus(settings.seaLevel(),settings.defaultFluid());
        var noise = FreeTerraForgedCompat.withSurfaceChunk(random, chunk,
                () -> NoiseChunk.forChunk(chunk, random, NO_STRUCTURES, settings, (x, y, z) -> fluid, Blender.empty()));
        random.surfaceSystem().buildSurface(random,new BiomeManager(context::noiseBiome,BiomeManager.obfuscateSeed(context.profile().seed())),
                context.decorationAccess().registryOrThrow(Registries.BIOME),settings.useLegacyRandomSource(),
                new WorldGenerationContext(generator,heights),chunk,noise,settings.surfaceRule());
    }
    long failures(){return failures.sum();}
    @Override public void close() {
        closed=true;
        synchronized(completed){completed.clear();retainedBytes=0;}
        pending.values().forEach(future->future.completeExceptionally(new CancellationException("Surface context closed")));
    }
}
