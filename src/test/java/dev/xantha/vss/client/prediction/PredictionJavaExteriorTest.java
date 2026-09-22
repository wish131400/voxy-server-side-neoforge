package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

class PredictionJavaExteriorTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    record Context(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        PredictionJavaExterior fast() { return new PredictionJavaExterior(generator,random,heights); }
        boolean[] original(int x,int z,int step,int bottom,int top) {
            boolean[] result=new boolean[top-bottom];
            for(int dz=0;dz<step;dz++)for(int dx=0;dx<step;dx++) {
                var column=generator.getBaseColumn(x+dx,z+dz,heights,random);
                if(dx==0&&dz==0&&column.getBlock(top-1).isAir())return null;
                for(int y=bottom;y<top;y++)result[y-bottom]|=!column.getBlock(y).isAir();
            }
            return result;
        }
    }
    static Context context(NoiseGeneratorSettings settings) {
        var lookup=VanillaRegistries.createLookup();
        var source=new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        var generator=new NoiseBasedChunkGenerator(source,Holder.direct(settings));
        return new Context(generator,RandomState.create(settings,lookup.lookupOrThrow(Registries.NOISE),5052304137288917019L),
                LevelHeightAccessor.create(settings.noiseSettings().minY(),settings.noiseSettings().height()));
    }
    static NoiseRouter router(DensityFunction density) {
        var zero=DensityFunctions.zero();
        return new NoiseRouter(zero,zero,zero,zero,zero,zero,zero,zero,zero,zero,zero,density,zero,zero,zero);
    }
    static NoiseGeneratorSettings synthetic(DensityFunction density, boolean airDefault) {
        return new NoiseGeneratorSettings(NoiseSettings.create(-64,256,1,2),
                (airDefault?Blocks.AIR:Blocks.STONE).defaultBlockState(),Blocks.AIR.defaultBlockState(),
                router(density),SurfaceRules.state(Blocks.STONE.defaultBlockState()),List.of(),-64,false,false,false,false);
    }

    @Test void partialFootprintsMatchFullColumnsAcrossDimensionsAndNoiseCellBorders() {
        var lookup=VanillaRegistries.createLookup();
        for(var key:List.of(NoiseGeneratorSettings.OVERWORLD,NoiseGeneratorSettings.NETHER,NoiseGeneratorSettings.END)) {
            var c=context(lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value());
            var fast=c.fast();assertFalse(fast.ordered(),"vanilla should use the batch path: "+key);
            for(int[] pos:List.of(new int[]{1605,-1456},new int[]{-1,-5},new int[]{3,7},new int[]{-4588,-1531})) {
                var column=c.generator.getBaseColumn(pos[0],pos[1],c.heights,c.random);
                int top=c.heights.getMaxBuildHeight();
                while(top>c.heights.getMinBuildHeight()&&column.getBlock(top-1).isAir())top--;
                if(top==c.heights.getMinBuildHeight())continue;
                int bottom=Math.max(c.heights.getMinBuildHeight(),top-123);
                for(int step:new int[]{1,2,4}) assertArrayEquals(c.original(pos[0],pos[1],step,bottom,top),
                        fast.sample(pos[0],pos[1],step,bottom,top,()->true),key+" "+Arrays.toString(pos)+" step="+step);
            }
        }
    }

    @Test void thinLayersAndStatefulCachesKeepExactAirIntervals() {
        var y=DensityFunctions.yClampedGradient(-64,192,-64,192);
        var layers=DensityFunctions.rangeChoice(y,160,161,DensityFunctions.constant(1),
                DensityFunctions.rangeChoice(y,71,98,DensityFunctions.constant(1),DensityFunctions.constant(-1)));
        for(var density:List.of(layers,DensityFunctions.cache2d(layers),DensityFunctions.flatCache(layers))) {
            var c=context(synthetic(density,false));var fast=c.fast();
            for(int step:new int[]{1,2,4}) for(int x:new int[]{-5,3})
                assertArrayEquals(c.original(x,-1,step,47,161),fast.sample(x,-1,step,47,161,()->true));
        }
        assertFalse(PredictionDensityOrder.requiresColumnOrder(router(layers)));
        assertTrue(PredictionDensityOrder.requiresColumnOrder(router(DensityFunctions.cache2d(layers))));
        assertFalse(PredictionDensityOrder.requiresColumnOrder(router(DensityFunctions.flatCache(layers))));
    }

    @Test void unknownDensityRemainsOrderedAndInvalidRoofsAreNotInvented() {
        DensityFunction opaque=new DensityFunction.SimpleFunction() {
            @Override public double compute(DensityFunction.FunctionContext p){return p.blockY()>=160?1:-1;}
            @Override public double minValue(){return -1;}
            @Override public double maxValue(){return 1;}
            @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec(){throw new UnsupportedOperationException();}
        };
        var c=context(synthetic(opaque,false));assertTrue(c.fast().ordered());
        assertArrayEquals(c.original(-1,3,4,47,161),c.fast().sample(-1,3,4,47,161,()->true));
        assertNull(c.fast().sample(0,0,1,47,100,()->true));
        var air=context(synthetic(DensityFunctions.constant(1),true)).fast();
        assertNull(air.sample(0,0,1,47,161,()->true));
        assertNull(air.sample(0,0,1,47,47,()->true));
        assertThrows(java.util.concurrent.CancellationException.class,()->c.fast().sample(0,0,4,47,161,()->false));
        assertThrows(java.util.concurrent.CancellationException.class,()->air.sample(0,0,4,47,161,()->false));
    }

    @Test void rejectedRoofCacheDoesNotRejectDifferentRoofsOrSkipCancellation() {
        var density=DensityFunctions.yClampedGradient(40,60,1,-1);
        var c=context(synthetic(density,false));var fast=c.fast();
        assertNull(fast.sample(3,-5,4,0,65,()->true));
        assertNull(fast.sample(3,-5,2,10,65,()->true));
        assertArrayEquals(c.original(3,-5,4,0,40),fast.sample(3,-5,4,0,40,()->true));
        assertThrows(java.util.concurrent.CancellationException.class,()->fast.sample(3,-5,4,0,65,()->false));
    }
}
