package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

class FreeTerraForgedExteriorTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static FreeTerraForgedExterior fast(PredictionJavaExteriorTest.Context context) {
        return new FreeTerraForgedExterior(context.generator(), context.random(), context.heights());
    }
    private static boolean[] sample(FreeTerraForgedExterior fast, int x, int z, int step, int low, int high) {
        return fast.sample(new int[]{x}, new int[]{z}, step, new int[]{low}, new int[]{high}, 1, () -> true)[0];
    }

    @Test void expandingAColumnOnlyEvaluatesItsMissingInterval() throws Exception {
        var context = PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(DensityFunctions.constant(1), false));
        try (var fast = fast(context)) {
            assertArrayEquals(context.originalRange(0,0,1,100,161),sample(fast,0,0,1,100,161));
            assertEquals(61,fast.evaluatedBlocks());
            assertArrayEquals(context.originalRange(0,0,1,47,161),sample(fast,0,0,1,47,161));
            assertEquals(114,fast.evaluatedBlocks(),"the original 61 blocks must not be evaluated again");
            assertArrayEquals(context.originalRange(0,0,1,47,180),sample(fast,0,0,1,47,180));
            assertEquals(133,fast.evaluatedBlocks(),"upward extension must also preserve completed occupancy");
        }
    }

    @Test void neighbouringNoiseCellsShareOneRequestOwnedContext() throws Exception {
        var context = PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(DensityFunctions.constant(1), false));
        try (var fast = fast(context)) {
            int[] xs=new int[16],zs=new int[16],lows=new int[16],tops=new int[16];
            for(int i=0;i<16;i++){xs[i]=(i%4)*4;zs[i]=(i/4)*4;lows[i]=47;tops[i]=161;}
            var values=fast.sample(xs,zs,1,lows,tops,16,()->true);
            assertEquals(16,fast.builds());assertEquals(1,fast.contexts());
            for(int i=0;i<16;i++)assertArrayEquals(context.originalRange(xs[i],zs[i],1,47,161),values[i]);
        }
    }

    @Test void floodFrontiersAndNeighbourPassesReuseContextWhenXMovesBackwards() throws Exception {
        var y = DensityFunctions.yClampedGradient(-64,192,-64,192);
        var layers = DensityFunctions.rangeChoice(y,160,161,DensityFunctions.constant(1),
                DensityFunctions.rangeChoice(y,71,98,DensityFunctions.constant(1),DensityFunctions.constant(-1)));
        var context = PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(
                DensityFunctions.cacheOnce(layers), false));
        try (var fast = fast(context)) {
            try (var request = fast.request()) {
                for (int[] p : List.of(new int[]{0,0},new int[]{12,12},new int[]{4,8},new int[]{8,4},new int[]{0,4}))
                    assertArrayEquals(context.originalRange(p[0],p[1],4,47,161),sample(fast,p[0],p[1],4,47,161));
                assertEquals(1,fast.contexts(),"frontiers and anchor/neighbour passes own one graph for this chunk");
                assertTrue(fast.diagnostics().contains("sliceRepositions="));
            }
            assertArrayEquals(context.originalRange(0,0,1,-64,161),sample(fast,0,0,1,-64,161));
            assertEquals(2,fast.contexts(),"the completed request must release its mutable graph");
        }
    }

    @Test void requestWorkingSetIsBoundedAndCancellationReleasesItsGraphs() throws Exception {
        var context = PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(DensityFunctions.constant(1),false));
        try (var fast = fast(context)) {
            assertThrows(CancellationException.class, () -> {
                try (var request = fast.request()) {
                    for (int i=0;i<40;i++) sample(fast,i*16,0,1,100,161);
                    sample(fast,0,0,1,47,161);
                    assertEquals(41,fast.contexts(),"an evicted request context must be constructed again");
                    fast.sample(new int[]{0},new int[]{0},1,new int[]{-64},new int[]{161},1,()->false);
                }
            });
            assertArrayEquals(context.originalRange(0,0,1,-64,161),sample(fast,0,0,1,-64,161));
            assertEquals(42,fast.contexts(),"cancellation must leave no old request on the worker");
        }
    }

    @Test void exactCellBatchMatchesOriginalAcrossVanillaDimensionsAquifersAndBorders() throws Exception {
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        for (var dimension : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END)) {
            var context = PredictionJavaExteriorTest.context(lookup.lookupOrThrow(
                    net.minecraft.core.registries.Registries.NOISE_SETTINGS).getOrThrow(dimension).value());
            try (var fast = fast(context)) {
                assertTrue(fast.batchesCells(), dimension.toString());
                for (int[] p : List.of(new int[]{-1,-5}, new int[]{3,7}, new int[]{1604,-1456})) {
                    var original = context.generator().getBaseColumn(p[0], p[1], context.heights(), context.random());
                    int high = context.heights().getMaxBuildHeight();
                    while (high > context.heights().getMinBuildHeight() && original.getBlock(high - 1).isAir()) high--;
                    if (high <= context.heights().getMinBuildHeight()) continue;
                    for (int step : new int[]{1,2,4}) for (int depth : new int[]{13,79,151}) {
                        int low = Math.max(context.heights().getMinBuildHeight(), high - depth);
                        assertArrayEquals(context.originalRange(p[0],p[1],step,low,high), sample(fast,p[0],p[1],step,low,high),
                                dimension + " " + Arrays.toString(p) + " " + step + " " + depth);
                    }
                }
            }
        }
    }

    @Test void coldNeighbourBatchSharesGraphThenLodsAndRejectedRoofsReuseExactResults() throws Exception {
        var y = DensityFunctions.yClampedGradient(-64,192,-64,192);
        var layers = DensityFunctions.rangeChoice(y,160,161,DensityFunctions.constant(1),
                DensityFunctions.rangeChoice(y,71,98,DensityFunctions.constant(1),DensityFunctions.constant(-1)));
        var context = PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(layers, false));
        for (var density : List.of(layers, PredictionJavaExteriorTest.orderedLayers())) {
            var rejectedContext = PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(density, false));
            try (var rejected = fast(rejectedContext)) {
                assertNull(sample(rejected,0,0,4,47,150));
                long builds = rejected.builds();
                assertNull(sample(rejected,0,0,4,-64,150));
                assertEquals(builds,rejected.builds(),"a failed roof is reusable even when the requested floor moves down");
                assertArrayEquals(rejectedContext.originalRange(0,0,4,47,161),sample(rejected,0,0,4,47,161),
                        "a roof rejection must not claim uncomputed lower terrain is cached");
            }
        }
        try (var fast = fast(context)) {
            assertTrue(fast.batchesCells());
            int[] xs={0,1,2,3},zs={0,0,0,0},low={47,47,47,47},high={161,161,161,161};
            var results = fast.sample(xs,zs,1,low,high,4,()->true);
            assertEquals(1,fast.builds(), "four new neighbouring columns must share one mapped graph");
            for(int i=0;i<4;i++) assertArrayEquals(context.originalRange(xs[i],zs[i],1,47,161),results[i]);
            assertArrayEquals(context.originalRange(0,0,4,47,161),sample(fast,0,0,4,47,161));
            long builds=fast.builds();
            for (int step : new int[]{1,2,4}) assertArrayEquals(context.originalRange(0,0,step,60,150),sample(fast,0,0,step,60,150));
            assertEquals(builds,fast.builds(), "cached failed roofs must not construct another graph");
            assertNull(sample(fast,0,0,4,60,150));
            assertFalse(results[0][100-47], "the gap between islands must survive batching");
        }
    }

    @Test void opaqueAndVerticalCacheGraphsKeepOriginalTraversalAndResults() throws Exception {
        AtomicInteger evaluations=new AtomicInteger();
        DensityFunction opaque=new DensityFunction.SimpleFunction() {
            @Override public double compute(FunctionContext p) { evaluations.incrementAndGet(); return p.blockY()==160||p.blockY()<98?1:-1; }
            @Override public double minValue(){return -1;}
            @Override public double maxValue(){return 1;}
            @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec(){throw new UnsupportedOperationException();}
        };
        for (var density:List.of(opaque, PredictionJavaExteriorTest.orderedLayers())) {
            var context=PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(density,false));
            try(var fast=fast(context)) {
                assertFalse(fast.batchesCells());
                for (int[] p:List.of(new int[]{3,-1},new int[]{0,0})) for(int step:new int[]{1,2,4})
                    assertArrayEquals(context.originalRange(p[0],p[1],step,47,161),sample(fast,p[0],p[1],step,47,161));
                long builds=fast.builds();int calls=evaluations.get();
                assertArrayEquals(context.originalRange(0,0,1,60,161),sample(fast,0,0,1,60,161));
                assertEquals(builds,fast.builds());
                calls=evaluations.get(); sample(fast,0,0,1,60,161);
                assertEquals(calls,evaluations.get(), "opaque compiled/modded columns should also reuse completed occupancy");
            }
        }
    }

    @Test void cancellationConcurrencyAndWorldCloseDoNotPublishPartialOccupancy() throws Exception {
        for(var context:List.of(PredictionJavaExteriorTest.orderedOverworld(),PredictionJavaExteriorTest.modifiedOverworld(f->f)))
        try(var fast=fast(context)) {
            AtomicInteger checks=new AtomicInteger();
            assertThrows(CancellationException.class,()->fast.sample(new int[]{3},new int[]{7},4,new int[]{-64},new int[]{65},1,
                    ()->checks.incrementAndGet()<5));
            assertArrayEquals(context.originalRange(3,7,4,-64,65),sample(fast,3,7,4,-64,65));
        }
        var simple=PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(DensityFunctions.constant(1),false));
        var fast=fast(simple);
        var workers=Executors.newFixedThreadPool(6);
        try {
            var start=new CountDownLatch(1); var jobs=new ArrayList<Future<boolean[]>>();
            for(int i=0;i<6;i++) jobs.add(workers.submit(()->{start.await();return sample(fast,0,0,1,-64,161);}));
            start.countDown();
            for(var job:jobs) assertArrayEquals(simple.originalRange(0,0,1,-64,161),job.get(30,TimeUnit.SECONDS));
            assertEquals(1,fast.builds(), "simultaneous requests for one column must share the computation");
            fast.close();
            assertThrows(CancellationException.class,()->sample(fast,0,0,1,-64,161));
            assertTrue(fast.diagnostics().contains("cells=0,"));
        } finally { workers.shutdownNow(); fast.close(); }
        try(var reopened=fast(simple)) {
            sample(reopened,0,0,1,-64,161);assertEquals(1,reopened.builds(), "a new world sampler starts with an independent cache");
        }
    }
}
