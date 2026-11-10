package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionFarDecorationTest {
    @BeforeAll static void bootstrap() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }

    @Test void entireFootprintMustBeBeyondThePromotionBand() {
        for (int radius : new int[] {1024, 4096}) {
            int boundary = radius + PredictionFarPolicy.PROMOTION_BLOCKS;
            assertFalse(PredictionFarPolicy.outside(0,0,boundary,0,64,radius));
            assertTrue(PredictionFarPolicy.outside(0,0,boundary+1,0,64,radius));
            assertFalse(PredictionFarPolicy.outside(0,0,radius,0,1024,radius));
            assertFalse(PredictionFarPolicy.outside(0,0,-boundary-64,0,64,radius));
            assertTrue(PredictionFarPolicy.outside(0,0,-boundary-65,0,64,radius));
            assertFalse(PredictionFarPolicy.outside(10000,10000,9984,9984,1024,radius));
            assertTrue(PredictionFarPolicy.outside(0,0,radius,radius,64,radius));
        }
    }

    static PredictionVoxySource.Tile tile(boolean complete,boolean approximate) {
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,80,0,0);
        return new PredictionVoxySource.Tile(key,5120,0,64,1,64,
                new PredictionVoxySource.Column[]{new PredictionVoxySource.Column(
                        List.of(new PredictionVoxySource.Run(0,64,Blocks.STONE.defaultBlockState())),"minecraft:plains")},
                List.of(new PredictionVoxySource.Box(0,64,0,4,72,4,Blocks.OAK_LOG.defaultBlockState())),
                128,complete,0,null,approximate);
    }

    @Test void retainedAppearanceKeepsItsQualityAndExactCompletionPromotesIt() {
        var far=tile(true,true);
        var refined=tile(false,false).retainFeatures(far);
        assertTrue(refined.approximateDecorations());
        assertTrue(refined.withVegetation(PredictionVegetation.Tile.EMPTY,null,true).surfaceComplete());
        assertFalse(refined.withVegetation(PredictionVegetation.Tile.EMPTY,null,true).approximateDecorations());
        var exact=tile(true,false);
        assertFalse(exact.reuseContent(far).approximateDecorations());
        assertNotSame(far,exact.reuseContent(far),"Equal geometry cannot erase an exact quality upgrade");
    }

    @Test void sourcePublishesExactQualityEvenWhenGeometryMatches() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(true,true));
            var exact=source.put(tile(true,false),()->true);
            assertNotNull(exact);assertFalse(exact.approximateDecorations());
        }
    }

    @Test void exactDiskWriterRejectsApproximationBeforeWritingAnyBytes() {
        var out=new ByteArrayOutputStream();
        var data=new PredictionVoxelCache.Data(tile(true,true),new int[0],null,
                new ClientColumnSample[0],new PredictionDepthBound(0,72));
        assertThrows(IOException.class,()->PredictionVoxelCache.write(new DataOutputStream(out),data,0,
                new byte[32],null,null));
        assertEquals(0,out.size());
    }

    @Test @SuppressWarnings("unchecked") void promotionBypassesCompletedReadinessAndKeepsOldGeometry() throws Exception {
        var config=dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean enabled=config.predictionFarDecorations;
        int radius=config.predictionExactDistanceBlocks;
        config.predictionFarDecorations=true;config.predictionExactDistanceBlocks=4096;
        var sampler=org.mockito.Mockito.mock(RustTerrainSampler.class);
        org.mockito.Mockito.when(sampler.profile()).thenReturn(new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",1));
        org.mockito.Mockito.when(sampler.decorationContext()).thenReturn(sampler);
        org.mockito.Mockito.when(sampler.cacheBiomes()).thenReturn(List.of());
        try(var manager=new PredictionTileManager(Level.OVERWORLD,sampler,new PredictionMemoryBudget(
                512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),null)) {
            field("layout").set(manager,VssLodLayout.of(8192,2,true,false));
            field("unifiedVoxy").setBoolean(manager,true);
            var far=tile(true,true);var key=far.key();
            var payload=PredictionVoxelData.forVoxy(far);
            var resident=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    payload,new PredictionDepthBound(0,72),0,1,1,64);
            ((Map<Object,Object>)field("ready").get(manager)).put(key,resident);
            ((Set<Object>)field("surfaceReady").get(manager)).add(key);
            ((Set<Object>)field("desiredKeys").get(manager)).add(key);
            assertEquals(false,call(manager,"needsExactSurface",key));
            field("cameraBlockX").setDouble(manager,1024);
            assertEquals(true,call(manager,"needsExactSurface",key));
            call(manager,"refreshFarSurfaces");
            assertFalse(((Set<?>)field("surfaceReady").get(manager)).contains(key));
            assertEquals(true,call(manager,"surfaceBuildReady",key));
            assertSame(resident,((Map<?,?>)field("ready").get(manager)).get(key),"Promotion must retain old geometry while computing");
            var memory=(PredictionMemoryBudget)field("memoryBudget").get(manager);
            var publish=PredictionTileManager.class.getDeclaredMethod("publishTileLocked",
                    PredictionTileManager.PredictionTile.class,PredictionMemoryBudget.Reservation.class,
                    long.class,long.class,boolean.class,boolean.class,boolean.class,PredictionRelief.class);
            publish.setAccessible(true);
            long revision=((java.util.concurrent.atomic.AtomicLong)field("meshRevision").get(manager)).get();
            try(var claim=memory.tryReserveBuild()) {
                assertNotNull(claim);
                assertEquals(false,publish.invoke(manager,resident,claim,revision,0L,true,false,false,null),
                        "An old far job must not publish after entering the exact band");
            }
            // The near-band terrain refresh must get through before exact decoration can run.
            var columns=new PredictionVoxySource.Column[16];Arrays.fill(columns,far.columns()[0]);
            var ground=new PredictionVoxySource.Tile(key,5120,0,64,4,16,columns,List.of(),512,false,0).retainFeatures(far);
            var refreshed=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    PredictionVoxelData.forVoxy(ground),new PredictionDepthBound(0,72),0,3,4,16);
            ((Set<Object>)field("dirtyTiles").get(manager)).add(key);
            try(var claim=memory.tryReserveBuild()) {
                assertNotNull(claim);
                assertEquals(true,publish.invoke(manager,refreshed,claim,revision,0L,false,false,false,PredictionRelief.of(refreshed)),
                        "Retained templates cannot reject a dirty terrain refresh inside the exact band");
            }
            call(manager,"refreshFarSurfaces");
            assertFalse(((Set<?>)field("dirtyTiles").get(manager)).contains(key));
            assertEquals(true,call(manager,"surfaceBuildReady",key),"Exact decoration becomes eligible after terrain publishes");
            field("cameraBlockX").setDouble(manager,0);
            var exactResident=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    PredictionVoxelData.forVoxy(tile(true,false)),new PredictionDepthBound(0,72),0,2,1,64);
            ((Map<Object,Object>)field("ready").get(manager)).put(key,exactResident);
            try(var claim=memory.tryReserveBuild()) {
                assertNotNull(claim);
                assertEquals(false,publish.invoke(manager,resident,claim,revision,0L,true,false,false,null),
                        "A late far result must not downgrade a completed exact surface");
            }
        } finally { config.predictionFarDecorations=enabled;config.predictionExactDistanceBlocks=radius; }
    }

    @Test @SuppressWarnings("unchecked") void freshCoveragePrecedesFallbackAndSlotsReleaseOnCancellation()throws Exception {
        var config=dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean enabled=config.predictionFarDecorations;int radius=config.predictionExactDistanceBlocks,workers=config.predictionRefinementWorkers;
        config.predictionFarDecorations=true;config.predictionExactDistanceBlocks=4096;config.predictionRefinementWorkers=4;
        var sampler=org.mockito.Mockito.mock(RustTerrainSampler.class);
        org.mockito.Mockito.when(sampler.profile()).thenReturn(new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",1));
        org.mockito.Mockito.when(sampler.decorationContext()).thenReturn(sampler);
        org.mockito.Mockito.when(sampler.cacheBiomes()).thenReturn(List.of());
        var held=new ArrayList<AutoCloseable>();
        try(var manager=new PredictionTileManager(Level.OVERWORLD,sampler,new PredictionMemoryBudget(
                512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,4),null)) {
            field("layout").set(manager,VssLodLayout.of(8192,2,true,false));field("unifiedVoxy").setBoolean(manager,true);
            var executor=(java.util.concurrent.ThreadPoolExecutor)field("executor").get(manager);
            executor.setMaximumPoolSize(4);executor.setCorePoolSize(4);
            var ready=(Map<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile>)field("ready").get(manager);
            var keys=new ArrayList<PredictionTileManager.PredictionTileKey>();
            for(int tx:new int[]{80,120}) {
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,tx,0,0);keys.add(key);
                var columns=new PredictionVoxySource.Column[16*16];Arrays.fill(columns,tile(false,false).columns()[0]);
                var t=new PredictionVoxySource.Tile(key,tx*64,0,64,16,4,columns,
                        tx==80?tile(false,false).features():List.of(),2048,false,0,null,tx==80);
                ready.put(key,new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                        PredictionVoxelData.forVoxy(t),new PredictionDepthBound(0,72),0,1,16,4));
            }
            field("viewBands").set(manager,Map.of(keys.get(0),PredictionWorkView.VISIBLE,keys.get(1),PredictionWorkView.VISIBLE));
            call(manager,"queueSurfaceCompletion",keys.get(0));call(manager,"queueSurfaceCompletion",keys.get(1));
            var queue=(PredictionSurfaceQueue<?>)field("visibleSurfaceWork").get(manager);
            assertEquals(keys.get(1),queue.peekFirst(),"Uncovered far terrain gets templates before a nearer tile fills slow fallback");
            var priority=PredictionTileManager.class.getDeclaredMethod("workPriority",PredictionTileManager.PredictionTileKey.class,boolean.class);priority.setAccessible(true);
            assertTrue((int)priority.invoke(manager,keys.get(1),true)<(int)priority.invoke(manager,keys.get(0),true));
            var reserve=PredictionTileManager.class.getDeclaredMethod("tryReserveBuildSlots",boolean.class,boolean.class);reserve.setAccessible(true);
            for(int i=0;i<3;i++) {var slot=(AutoCloseable)reserve.invoke(manager,true,true);assertNotNull(slot);held.add(slot);}
            assertNull(reserve.invoke(manager,true,true),"Fallback cannot occupy all surface workers");
            var coverage=(AutoCloseable)reserve.invoke(manager,true,false);assertNotNull(coverage);held.add(coverage);
            for(var slot:held) {slot.close();slot.close();}held.clear();
            assertEquals(0,((java.util.concurrent.atomic.AtomicInteger)field("activeFarCompletions").get(manager)).get());
            assertEquals(0,((java.util.concurrent.atomic.AtomicInteger)field("activeSurfaceBuilds").get(manager)).get());
        } finally {for(var slot:held)slot.close();config.predictionFarDecorations=enabled;config.predictionExactDistanceBlocks=radius;config.predictionRefinementWorkers=workers;}
    }


    @Test void biomeDensityDoesNotFlattenBambooAndTreeWeightsToOneCandidate() {
        for(double count:new double[]{.25,9.6,30.1,50.1,58.1,80}) {
            double expected=count*25/256;long total=0;int samples=100000;
            for(int i=0;i<samples;i++)total+=PredictionFarDecoration.densityPlacements(expected,PredictionFarDecoration.mix(i));
            assertEquals(expected,(double)total/samples,.008,"Expected biome counts survive dense template scattering: "+count);
        }
        assertEquals(0,PredictionFarDecoration.densityPlacements(0,0));
        assertEquals(5,PredictionFarDecoration.densityPlacements(5,0));
        assertEquals(6,PredictionFarDecoration.densityPlacements(5.5,0));
    }
    @Test void terrainRefinementRetainsActualDecorationResolutionIncludingEmptyTemplates() {
        for(boolean empty:new boolean[]{false,true}) {
            var old=tile(true,true);
            if(empty)old=new PredictionVoxySource.Tile(old.key(),old.baseX(),old.baseZ(),64,1,64,
                    old.columns(),List.of(),128,false,0,null,true);
            var columns=new PredictionVoxySource.Column[64*64];Arrays.fill(columns,old.columns()[0]);
            var refined=new PredictionVoxySource.Tile(old.key(),old.baseX(),old.baseZ(),64,64,1,
                    columns,List.of(),16384,false,0).retainFeatures(old);
            assertTrue(refined.approximateDecorations(),"An empty template result is still a known template stage");
            assertEquals(64,refined.decorationStep());assertTrue(refined.needsDecorationRefinement());
            assertEquals(old.templateFeatures(),refined.templateFeatures());
            assertEquals(old.surfaceComplete(),refined.surfaceComplete());
        }
    }

    private static java.lang.reflect.Field field(String name)throws Exception {
        var f=PredictionTileManager.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static Object call(Object target,String name,Object... args)throws Exception {
        var types=args.length==0?new Class<?>[0]:new Class<?>[]{PredictionTileManager.PredictionTileKey.class};
        var m=PredictionTileManager.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
    @Test void templateCaptureCanReadVerifiedResourcesAndKeepsWoodAndLeaves() throws Exception {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",1);
        var sampler=org.mockito.Mockito.mock(ClientTerrainSampler.class);
        var placed=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.levelgen.placement.PlacedFeature>(
                net.minecraft.core.registries.Registries.PLACED_FEATURE,com.mojang.serialization.Lifecycle.stable());
        var access=new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(java.util.List.of(placed.freeze()));
        org.mockito.Mockito.when(sampler.decorationContext()).thenReturn(sampler);
        org.mockito.Mockito.when(sampler.decorationAccess()).thenReturn(access);
        org.mockito.Mockito.when(sampler.profile()).thenReturn(profile);
        org.mockito.Mockito.when(sampler.structureTemplates()).thenReturn(new com.google.gson.JsonObject());
        org.mockito.Mockito.when(sampler.cacheBiomes()).thenReturn(java.util.List.of());
        var feature=new net.minecraft.world.level.levelgen.feature.Feature<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration>(
                net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration> placement) {
                assertTrue(PredictionTemplateAccess.virtual(placement.level()));
                assertNotNull(PredictionTemplateAccess.manager(placement.level()));
                placement.level().setBlock(placement.origin(),Blocks.OAK_LOG.defaultBlockState(),2);
                placement.level().setBlock(placement.origin().above(),Blocks.OAK_LEAVES.defaultBlockState(),2);
                return true;
            }
        };
        var configured=new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(feature,
                net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE);
        var far=new PredictionFarDecoration(sampler,false,4,()->true);
        var capture=PredictionFarDecoration.class.getDeclaredMethod("capture",net.minecraft.world.level.levelgen.feature.ConfiguredFeature.class);
        capture.setAccessible(true);
        @SuppressWarnings("unchecked") var models=(java.util.List<PredictionFarDecoration.Template>)capture.invoke(far,configured);
        assertEquals(3,models.size());assertEquals(0,far.templateFailures);
        for(var model:models) {
            assertTrue(model.spans().stream().anyMatch(span->span.state().is(Blocks.OAK_LOG)));
            assertTrue(model.spans().stream().anyMatch(span->span.state().is(Blocks.OAK_LEAVES)));
        }
    }

}
