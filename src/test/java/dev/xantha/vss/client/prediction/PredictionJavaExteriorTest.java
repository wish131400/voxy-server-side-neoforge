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
        boolean[] originalRange(int x,int z,int step,int bottom,int top) throws Exception {
            var method=Arrays.stream(NoiseBasedChunkGenerator.class.getDeclaredMethods())
                    .filter(m->m.getReturnType()==OptionalInt.class&&m.getParameterCount()==6).findFirst().orElseThrow();
            method.setAccessible(true);
            boolean[] result=new boolean[top-bottom];boolean[] badRoof={false};
            for(int dz=0;dz<step;dz++)for(int dx=0;dx<step;dx++) {
                int[] y={heights.getMaxBuildHeight()-1};boolean anchor=dx==0&&dz==0;
                java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> probe=state->{
                    int current=y[0]--;
                    if(current<top&&current>=bottom) {
                        if(anchor&&current==top-1&&state.isAir()){badRoof[0]=true;return true;}
                        result[current-bottom]|=!state.isAir();
                    }
                    return current<=bottom;
                };
                method.invoke(generator,heights,random,x+dx,z+dz,null,probe);
                if(badRoof[0])return null;
                boolean complete=true;for(boolean solid:result)if(!solid){complete=false;break;}
                if(complete)return result;
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

    static DensityFunction orderedLayers() {
        var y=DensityFunctions.yClampedGradient(-64,192,-64,192);
        var layers=DensityFunctions.rangeChoice(y,160,161,DensityFunctions.constant(1),
                DensityFunctions.rangeChoice(y,71,98,DensityFunctions.constant(1),DensityFunctions.constant(-1)));
        return DensityFunctions.cacheOnce(DensityFunctions.add(layers,
                DensityFunctions.cache2d(DensityFunctions.yClampedGradient(-64,192,-0.2,0.2))));
    }

    @Test void knownOrderedGraphsReplayAcrossCellBordersRoofsFloorsAndCancellation() throws Exception {
        for(var density:List.of(orderedLayers(),DensityFunctions.interpolated(orderedLayers()))) {
            var c=context(synthetic(density,false));var fast=c.fast();
            assertTrue(fast.ordered());assertTrue(fast.reusesContexts());
            for(int[] p:List.of(new int[]{-1,-5},new int[]{0,0},new int[]{3,7},new int[]{15,-1}))
                for(int step:new int[]{1,2,4})for(int[] range:List.of(new int[]{47,161},new int[]{-64,98},new int[]{60,150})) {
                    var expected=c.originalRange(p[0],p[1],step,range[0],range[1]);
                    for(int repeat=0;repeat<2;repeat++)assertArrayEquals(expected,
                            fast.sample(p[0],p[1],step,range[0],range[1],()->true),Arrays.toString(p)+" "+Arrays.toString(range));
                }
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(java.util.concurrent.CancellationException.class,
                    ()->fast.sample(0,0,4,47,161,()->checks.incrementAndGet()<8));
            assertArrayEquals(c.originalRange(0,0,4,47,161),fast.sample(0,0,4,47,161,()->true),
                    "cancelled interpolation must not poison the next worker query");
        }
    }

    @Test void nestedConstructionCachesMatchFreshVanillaContextsOnEveryReuse() throws Exception {
        var shared=DensityFunctions.cache2d(DensityFunctions.yClampedGradient(-64,192,-0.2,0.2));
        var seeded=DensityFunctions.add(DensityFunctions.flatCache(shared),
                DensityFunctions.add(orderedLayers(),shared));
        // The same Cache2D is evaluated while FlatCache is constructed and
        // while a column is sampled. Other arrangements exercise both cached
        // arrays and values read by nested interpolation during initialization.
        var graphs=List.of(DensityFunctions.cacheOnce(seeded),
                DensityFunctions.interpolated(DensityFunctions.cacheOnce(seeded)),
                DensityFunctions.cacheAllInCell(DensityFunctions.cacheOnce(seeded)),
                DensityFunctions.cacheAllInCell(DensityFunctions.interpolated(DensityFunctions.cacheOnce(seeded))),
                DensityFunctions.add(orderedLayers(),
                        DensityFunctions.interpolated(DensityFunctions.cacheAllInCell(DensityFunctions.cacheOnce(seeded)))));
        int nonEmpty=0;
        for(var density:graphs) {
            var c=context(synthetic(density,false));var fast=c.fast();
            assertTrue(fast.ordered());assertTrue(fast.reusesContexts());
            for(int[] p:List.of(new int[]{0,0},new int[]{3,3},new int[]{-1,-5},new int[]{15,-1}))
                for(int step:new int[]{1,2,4})for(int[] range:List.of(new int[]{47,161},new int[]{-64,98},new int[]{60,150})) {
                    var expected=c.originalRange(p[0],p[1],step,range[0],range[1]);
                    if(expected!=null)nonEmpty++;
                    for(int repeat=0;repeat<2;repeat++)assertArrayEquals(expected,
                            fast.sample(p[0],p[1],step,range[0],range[1],()->true),
                            "nested cache "+Arrays.toString(p)+" "+Arrays.toString(range)+" step="+step);
                }
        }
        assertTrue(nonEmpty>0,"nested-cache comparisons must include actual occupied columns");
    }

    @Test void opaqueModDensityRetainsTheExactOriginalEvaluationTrace() throws Exception {
        var trace=new ArrayList<String>();
        DensityFunction opaque=new DensityFunction.SimpleFunction() {
            @Override public double compute(DensityFunction.FunctionContext p) {
                trace.add(p.blockX()+","+p.blockY()+","+p.blockZ());
                return p.blockY()==160||p.blockY()>=71&&p.blockY()<98?1:-1;
            }
            @Override public double minValue(){return -1;}
            @Override public double maxValue(){return 1;}
            @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec(){throw new UnsupportedOperationException();}
        };
        var c=context(synthetic(opaque,false));var fast=c.fast();
        assertTrue(fast.ordered());assertFalse(fast.reusesContexts());
        trace.clear();var expected=c.originalRange(3,-1,4,47,161);var expectedTrace=List.copyOf(trace);
        assertNotNull(expected);assertFalse(expectedTrace.isEmpty());
        trace.clear();assertArrayEquals(expected,fast.sample(3,-1,4,47,161,()->true));
        assertEquals(expectedTrace,trace,"opaque stateful nodes must see the original per-column traversal");
        assertEquals(0,fast.contextBuilds(),"opaque nodes must not use a retained mapped graph");
    }

    static Context modifiedOverworld(java.util.function.UnaryOperator<DensityFunction> change) {
        var lookup=VanillaRegistries.createLookup();
        var vanilla=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var r=vanilla.noiseRouter();
        var modified=new NoiseRouter(r.barrierNoise(),r.fluidLevelFloodednessNoise(),r.fluidLevelSpreadNoise(),r.lavaNoise(),
                r.temperature(),r.vegetation(),r.continents(),r.erosion(),r.depth(),r.ridges(),r.initialDensityWithoutJaggedness(),
                change.apply(r.finalDensity()),
                r.veinToggle(),r.veinRidged(),r.veinGap());
        var settings=new NoiseGeneratorSettings(vanilla.noiseSettings(),vanilla.defaultBlock(),vanilla.defaultFluid(),modified,
                vanilla.surfaceRule(),vanilla.spawnTarget(),vanilla.seaLevel(),vanilla.disableMobGeneration(),
                vanilla.isAquifersEnabled(),vanilla.oreVeinsEnabled(),vanilla.useLegacyRandomSource());
        return context(settings);
    }

    static Context orderedOverworld() {
        return modifiedOverworld(density->DensityFunctions.add(density,DensityFunctions.mul(DensityFunctions.cache2d(
                DensityFunctions.yClampedGradient(-64,320,-0.1,0.1)),DensityFunctions.constant(0.05))));
    }

    @Test void nestedFlatCacheAndInterpolationPreserveRealOverworldColumns() throws Exception {
        var shared=DensityFunctions.cache2d(DensityFunctions.yClampedGradient(-64,320,-0.2,0.2));
        var seeded=DensityFunctions.add(DensityFunctions.flatCache(shared),shared);
        var contexts=List.of(
                modifiedOverworld(density->DensityFunctions.cacheOnce(DensityFunctions.add(density,
                        DensityFunctions.mul(seeded,DensityFunctions.constant(0.05))))),
                modifiedOverworld(density->DensityFunctions.cacheAllInCell(DensityFunctions.cacheOnce(DensityFunctions.add(density,
                        DensityFunctions.mul(DensityFunctions.add(seeded,DensityFunctions.interpolated(
                                DensityFunctions.cacheOnce(shared))),DensityFunctions.constant(0.05)))))));
        for(var c:contexts) {
            var fast=c.fast();assertTrue(fast.ordered());assertTrue(fast.reusesContexts());
            int nonEmpty=0;
            for(int[] p:List.of(new int[]{1604,-1456},new int[]{1607,-1453},new int[]{-1,-5},new int[]{3,7})) {
                var column=c.generator.getBaseColumn(p[0],p[1],c.heights,c.random);
                int top=c.heights.getMaxBuildHeight();
                while(top>c.heights.getMinBuildHeight()&&column.getBlock(top-1).isAir())top--;
                if(top==c.heights.getMinBuildHeight())continue;
                for(int depth:new int[]{37,123})for(int step:new int[]{1,4}) {
                    int bottom=Math.max(c.heights.getMinBuildHeight(),top-depth);
                    var expected=c.originalRange(p[0],p[1],step,bottom,top);assertNotNull(expected);nonEmpty++;
                    for(int repeat=0;repeat<2;repeat++)assertArrayEquals(expected,
                            fast.sample(p[0],p[1],step,bottom,top,()->true),
                            "nested vanilla overworld "+Arrays.toString(p)+" depth="+depth+" step="+step);
                }
            }
            assertTrue(nonEmpty>0,"the real noise/aquifer graph must produce occupied test columns");
        }
    }

    @Test void knownOrderedAquiferCachesCannotLeakBetweenColumns() throws Exception {
        var c=orderedOverworld();var fast=c.fast();assertTrue(fast.ordered());assertTrue(fast.reusesContexts());
        for(int[] p:List.of(new int[]{1605,-1456},new int[]{-1,-5},new int[]{3,7},new int[]{-4588,-1531})) {
            var col=c.generator.getBaseColumn(p[0],p[1],c.heights,c.random);
            int top=c.heights.getMaxBuildHeight();while(top>c.heights.getMinBuildHeight()&&col.getBlock(top-1).isAir())top--;
            if(top==c.heights.getMinBuildHeight())continue;
            int bottom=Math.max(c.heights.getMinBuildHeight(),top-123);
            for(int repeat=0;repeat<2;repeat++)for(int step:new int[]{1,2,4})
                assertArrayEquals(c.originalRange(p[0],p[1],step,bottom,top),
                        fast.sample(p[0],p[1],step,bottom,top,()->true),"ordered aquifer "+Arrays.toString(p)+" "+step);
        }
    }

    @Test void neighbouringOrderedColumnsShareOneMappedGraph() throws Exception {
        var c=context(synthetic(orderedLayers(),false));var fast=c.fast();
        assertTrue(fast.reusesContexts());
        var expected=c.originalRange(0,0,4,47,161);assertNotNull(expected);assertFalse(expected[100-47]);
        for(int i=0;i<3;i++) {
            assertArrayEquals(expected,c.originalRange(0,0,4,47,161));
            assertArrayEquals(expected,fast.sample(0,0,4,47,161,()->true));
        }
        assertEquals(1,fast.contextBuilds(),"16 neighbouring columns should map one noise-cell context");
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean allocation&&allocation.isThreadAllocatedMemorySupported()) {
            allocation.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();
            long before=allocation.getThreadAllocatedBytes(thread),start=System.nanoTime();
            for(int i=0;i<20;i++)assertArrayEquals(expected,c.originalRange(0,0,4,47,161));
            long originalNanos=System.nanoTime()-start,originalBytes=allocation.getThreadAllocatedBytes(thread)-before;
            before=allocation.getThreadAllocatedBytes(thread);start=System.nanoTime();
            for(int i=0;i<20;i++)assertArrayEquals(expected,fast.sample(0,0,4,47,161,()->true));
            long cachedNanos=System.nanoTime()-start,cachedBytes=allocation.getThreadAllocatedBytes(thread)-before;
            System.out.printf(Locale.ROOT,"Java ordered exterior 20 footprints: original=%.3f ms / %d bytes; reuse=%.3f ms / %d bytes; mapped contexts=%d%n",
                    originalNanos/1e6,originalBytes,cachedNanos/1e6,cachedBytes,fast.contextBuilds());
            // A small synthetic graph can be cheaper after escape analysis;
            // graph reuse is verified above, and allocation reduction is tested
            // separately with the actual vanilla Overworld density graph.
        }
    }

    @Test void retainedVanillaGraphReducesOriginalOrderedColumnAllocation() throws Exception {
        var c=orderedOverworld();var fast=c.fast();assertTrue(fast.reusesContexts());
        int x=1604,z=-1456;
        var col=c.generator.getBaseColumn(x,z,c.heights,c.random);
        int top=c.heights.getMaxBuildHeight();while(top>c.heights.getMinBuildHeight()&&col.getBlock(top-1).isAir())top--;
        int bottom=Math.max(c.heights.getMinBuildHeight(),top-123);
        var expected=c.originalRange(x,z,4,bottom,top);assertNotNull(expected);
        for(int i=0;i<3;i++) {
            assertArrayEquals(expected,c.originalRange(x,z,4,bottom,top));
            assertArrayEquals(expected,fast.sample(x,z,4,bottom,top,()->true));
        }
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean allocation&&allocation.isThreadAllocatedMemorySupported()) {
            allocation.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();
            long before=allocation.getThreadAllocatedBytes(thread),start=System.nanoTime();
            for(int i=0;i<20;i++)assertArrayEquals(expected,c.originalRange(x,z,4,bottom,top));
            long originalNanos=System.nanoTime()-start,originalBytes=allocation.getThreadAllocatedBytes(thread)-before;
            before=allocation.getThreadAllocatedBytes(thread);start=System.nanoTime();
            for(int i=0;i<20;i++)assertArrayEquals(expected,fast.sample(x,z,4,bottom,top,()->true));
            long cachedNanos=System.nanoTime()-start,cachedBytes=allocation.getThreadAllocatedBytes(thread)-before;
            System.out.printf(Locale.ROOT,"Java ordered overworld 20 footprints: original=%.3f ms / %d bytes; reuse=%.3f ms / %d bytes; mapped contexts=%d%n",
                    originalNanos/1e6,originalBytes,cachedNanos/1e6,cachedBytes,fast.contextBuilds());
            assertTrue(cachedBytes<originalBytes/4,"the real vanilla graph should avoid repeated density graph allocation");
        }
    }
}
