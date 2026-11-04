package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.*;
import java.util.concurrent.CancellationException;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;

/** Released FTF filtered cells -> production grid/exterior -> distant decoration -> Voxy runs. */
final class FreeTerraForgedLoadingReleasedTest {
    static void verify(PredictionJavaExteriorTest.Context graph,Object context) throws Exception {
        var api=new FreeTerraForgedCompat.Api(FreeTerraForgedLoadingReleasedTest.class.getClassLoader());
        var delegate=org.mockito.AdditionalAnswers.delegatesTo(graph.random());
        var random=mock(RandomState.class,withSettings().extraInterfaces(api.stateType).defaultAnswer(invocation ->
                invocation.getMethod().getName().equals("generatorContext") ? context : delegate.answer(invocation)));
        var access=TerrainPredictionFixtures.access();
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(access.registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(Biomes.FOREST)),graph.generator().generatorSettings());
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(),918273L,-64,384,"noise","minecraft:overworld",1);
        // The released-runtime owner is closed by its enclosing fixture, after all assertions.
        try(var sampler=new FreeTerraForgedTerrainSampler(profile,generator,random,graph.heights(),null,access,()->{})) {
            verifyRegionSharing(context);
            assertTrue(sampler.supportsLodGrid());assertTrue(PredictionFarDecorations.supported(sampler));
            int grid=18,step=4,bx=0,bz=0;
            var samples=new ClientColumnSample[grid*grid];
            var captured=PredictionVoxelFixtures.sample(250);samples[1]=captured;
            assertTrue(sampler.sampleLodGrid(bx-step,bz-step,step,grid,grid,samples,0,grid,false));
            assertSame(captured,samples[1],"captured grid authority survives batching");
            for(int i=0;i<samples.length;i++) if(i!=1)assertSame(samples[i],sampler.sampleForLod(
                    bx+(i%grid-1)*step,bz+(i/grid-1)*step,1),"LOD grids reuse the same exact immutable column");
            samples[1]=sampler.sampleForLod(bx,bz-step,1);
            PredictionExteriorColumns.enrich(samples,grid,step,bx,bz,sampler,()->true);
            for(var s:samples) assertSame(s,sampler.retainAfterCapture(s),"immutable exact seed occupancy survives capture refresh");
            var s=samples[0];
            for(int flag:new int[]{ClientColumnSample.FLAG_CAPTURED,ClientColumnSample.FLAG_CAPTURE_MIP,
                    ClientColumnSample.FLAG_PARENT_LOD,ClientColumnSample.FLAG_CITY_GROUND,
                    ClientColumnSample.FLAG_APPROXIMATE,ClientColumnSample.FLAG_DISPLAY}) {
                var mutable=new ClientColumnSample(s.surfaceY(),s.fluidY(),s.biomeIndex(),s.topBlockIndex(),s.structureIndex(),
                        s.treeKind(),s.treeDensity(),s.treeHeight(),s.fluid(),s.flags()|flag,s.groundFeatureKind(),
                        s.underBlockIndex(),s.deepBlockIndex(),s.surfaceBottom(),s.lowerTop(),s.lowerBottom(),s.spanFloor(),s.volume());
                assertNull(sampler.retainAfterCapture(mutable),"mutable/summary provenance must be resampled: "+flag);
            }
            var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
            var terrain=PredictionVoxySource.build(key,samples,grid,step,sampler,PredictionVegetation.Tile.EMPTY,null,false);
            assertFalse(Arrays.stream(terrain.columns()).allMatch(c->c.runs().isEmpty()));
            System.out.println("Released FTF grid/exterior/Voxy: "+sampler.densityCompilerDiagnostics());
            // Use a real forest registry and the Java/FTF environment path. No Rust handle exists.
            FarVanillaTags.bootstrap();
            var models=new PredictionFarDecoration(sampler,true,8,()->true);
            assertFalse(models.replaced.isEmpty(),models.initialization().toString());
            var service=new PredictionFarDecorations(sampler);
            // This test invokes far generation directly; policy distance is covered by manager regressions.
            var first=service.build(terrain,null,true,p->false,()->true);
            var repeat=service.build(terrain,null,true,p->false,()->true);
            assertTrue(first.approximateDecorations());assertTrue(first.surfaceComplete());
            assertFalse(first.features().isEmpty(),service.diagnostics());
            assertSame(terrain.columns(),first.columns());assertEquals(first.features(),repeat.features());
            assertThrows(CancellationException.class,()->service.build(terrain,null,true,p->false,()->false));
            assertNull(PredictionFarDecoration.current());
            var out=new java.io.ByteArrayOutputStream();
            assertThrows(java.io.IOException.class,()->PredictionVoxelCache.write(new java.io.DataOutputStream(out),
                    new PredictionVoxelCache.Data(first,new int[0],null,new ClientColumnSample[0],new PredictionDepthBound(-64,320)),
                    0,new byte[32],null,null));
            assertEquals(0,out.size());
            System.out.println("Released FTF Java far path: "+service.diagnostics());
        }
    }

    /** Simulate the released bounded cache being evicted by concurrent terrain tasks between phases. */
    private static void verifyRegionSharing(Object context) throws Exception {
        Object upstream=context.getClass().getField("filteredChunkCache").get(context);
        var field=upstream.getClass().getDeclaredField("cache");field.setAccessible(true);
        var cache=(Map<?,?>)field.get(upstream);
        var clearMru=upstream.getClass().getMethod("clearThreadCaches");
        var surface=new FreeTerraForgedCells(context);var density=new FreeTerraForgedCells(context);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long[] elapsed=new long[2],bytes=new long[2];
        for(int round=-1;round<3;round++) {
            for(int which:round%2==0?new int[]{0,1}:new int[]{1,0}) {
                cache.clear();clearMru.invoke(null);
                long start=System.nanoTime(),allocation=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                try(var request=which==1?surface.request():(ClientTerrainSampler.SamplingScope)()->{}) {
                    int[][] coords={{-16,-16},{-1,-1},{-32,-17},{-48,-48}};
                    Object[] cells=new Object[coords.length];
                    float[] heights=new float[coords.length];
                    for(int i=0;i<coords.length;i++) {
                        cells[i]=surface.cellAt(coords[i][0],coords[i][1]);
                        heights[i]=cells[i].getClass().getField("height").getFloat(cells[i]);
                    }
                    cache.clear();clearMru.invoke(null);
                    for(int i=0;i<coords.length;i++) {
                        var repeated=density.cellAt(coords[i][0],coords[i][1]);
                        assertEquals(heights[i],repeated.getClass().getField("height").getFloat(repeated));
                        if(which==1)assertSame(cells[i],repeated,"surface and density readers share the same request chunk");
                    }
                    assertEquals(which==1,cache.isEmpty(),"request reuse avoids upstream terrain reconstruction after eviction");
                }
                long duration=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocation;
                if(round>=0) {elapsed[which]+=duration;bytes[which]+=allocated;}
                assertNull(FreeTerraForgedRegions.get(context,0),"completed task releases scratch");
            }
        }
        System.out.printf(Locale.ROOT,"FTF_PHASE_REUSE rounds=3 upstreamEvictionBetweenPhases=true beforeMs=%.3f afterMs=%.3f beforeBytes=%d afterBytes=%d %s%n",
                elapsed[0]/3e6,elapsed[1]/3e6,bytes[0]/3,bytes[1]/3,surface.diagnostics());
    }
}
