package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class PredictionSurfaceRefinementTest {
    @Test void farTemplateCanBeRankedBeforeFullSurfaceWithoutReleasingExactFallback() {
        var key=new PredictionTileKey(Level.OVERWORLD,100,0,0);var layout=VssLodLayout.of(8192,6,true,false);
        var samples=new ClientColumnSample[]{PredictionSurfaceRefinement.basic(PredictionVoxelFixtures.sample(64))};
        var mesh=PredictionVoxelFixtures.flat(key,64,4,64);
        var tile=new PredictionTile(key,new int[0],new int[0],samples,mesh,new PredictionDepthBound(64,64),0,1,16,4);
        var residents=new RenderSnapshot(Level.OVERWORLD,layout,Map.of(key,tile),Map.of());
        var input=new PredictionVoxyWorkPreparation.Input(layout,null,null,0,80,0,1000,-64,320,8192,0,0,
                Set.of(key),Set.of(key),Set.of(),Set.of(),residents,Map.of(),1024);
        assertTrue(PredictionVoxyWorkPreparation.build(input).surfaces().contains(key));
        assertTrue(PredictionVoxyWorkPreparation.build(input).terrain().contains(key));
        var base=mesh.voxyTile;
        mesh.voxyTile=new PredictionVoxySource.Tile(base.key(),base.baseX(),base.baseZ(),base.span(),base.axis(),base.step(),
                base.columns(),List.of(),base.terrainBytes(),false,0,null,true,base.step(),0);
        assertFalse(PredictionVoxyWorkPreparation.build(input).surfaces().contains(key),"Exact fallback must still wait for surface hooks");
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}

    @Test void basicCoverageCannotBecomeExactByIncreasingResolution() {
        var exact=PredictionVoxelFixtures.sample(64);
        var basic=PredictionSurfaceRefinement.basic(exact);
        assertTrue(basic.surfacePending());assertFalse(basic.reusableFor(false));assertTrue(basic.reusableFor(true));
        assertTrue(basic.reusableForDisplay());
        assertTrue(PredictionSurfaceRefinement.basicPass(true,null,64,64));
        assertTrue(PredictionSurfaceRefinement.basicPass(true,new ClientColumnSample[]{basic},16,64));
        assertFalse(PredictionSurfaceRefinement.basicPass(true,new ClientColumnSample[]{basic},64,64));
        assertFalse(PredictionSurfaceRefinement.basicPass(true,new ClientColumnSample[]{exact},32,64));
    }

    @Test void capturedColumnsKeepAuthorityAndCompletedColumnsAreReused() {
        var sample=PredictionVoxelFixtures.sample(64);
        var captured=new ClientColumnSample(64,64,0,1,0,0,0,0,0,ClientColumnSample.FLAG_CAPTURED,
                0,1,1,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        assertSame(captured,PredictionSurfaceRefinement.basic(captured));
        var calls=new AtomicInteger();var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1);
        try(var sampler=new ClientTerrainSampler(42,profile){
            @Override ClientColumnSample sampleInterior(int x,int z){calls.incrementAndGet();return sample;}
        }) {
            var samples=new ClientColumnSample[]{captured};
            PredictionSurfaceRefinement.completeGrid(sampler,0,0,1,1,samples,()->true);
            assertSame(captured,samples[0]);assertEquals(0,calls.get());
            assertThrows(java.util.concurrent.CancellationException.class,()->
                    PredictionSurfaceRefinement.completeGrid(sampler,0,0,1,1,new ClientColumnSample[1],()->false));
        }
    }

    @Test void pendingHooksRemainScheduledAtTheTargetAxisAndBlockDecoration() {
        var key=new PredictionTileKey(Level.OVERWORLD,0,0,0);var layout=VssLodLayout.of(4096,6,true,false);
        var samples=new ClientColumnSample[]{PredictionSurfaceRefinement.basic(PredictionVoxelFixtures.sample(64))};
        var tile=new PredictionTile(key,new int[0],new int[0],samples,PredictionVoxelFixtures.flat(key,64,1,64),
                new PredictionDepthBound(64,64),0,1,64,1);
        var input=new PredictionSchedulePreparation.Input(layout,null,null,0,80,0,1000,-64,320,0,List.of(key),
                new RenderSnapshot(Level.OVERWORLD,layout,Map.of(key,tile),Map.of()),Set.of(key),Set.of(key),Set.of(),
                Set.of(),Set.of(),Set.of(),Set.of(),Map.of(key,64),Map.of(key,64),Map.of(),Map.of(),
                PredictionMediumCoverage.EMPTY,false,false,false,false,Map.of(),Set.of(),Set.of(),
                Collections.nCopies(layout.levelCount(),8),null,Map.of(),Map.of(),Map.of(),System.nanoTime(),false);
        var result=PredictionSchedulePreparation.build(input);
        assertEquals(1,result.work().size());assertFalse(result.work().get(0).surface());
        var nativeInput=new PredictionVoxyWorkPreparation.Input(layout,null,null,0,80,0,1000,-64,320,512,0,0,
                Set.of(key),Set.of(key),Set.of(),Set.of(),input.residents(),Map.of(),0);
        var nativeWork=PredictionVoxyWorkPreparation.build(nativeInput);
        assertEquals(List.of(key),nativeWork.terrain());assertTrue(nativeWork.surfaces().isEmpty());
        samples[0]=PredictionVoxelFixtures.sample(64);
        result=PredictionSchedulePreparation.build(input);
        assertEquals(1,result.work().size());assertTrue(result.work().get(0).surface());
    }

    @Test void completionConsumesNegativeCoordinateChunksTogether() {
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1);
        var sequence=new ArrayList<Long>();
        try(var sampler=new ClientTerrainSampler(42,profile){
            @Override ClientColumnSample sampleInterior(int x,int z){sequence.add((long)(x>>4)<<32|(z>>4)&0xffffffffL);return PredictionVoxelFixtures.sample(64);}
        }) {
            PredictionSurfaceRefinement.completeGrid(sampler,-16,-16,4,10,new ClientColumnSample[100],()->true);
            var seen=new HashSet<Long>();Long previous=null;
            for(Long chunk:sequence)if(!chunk.equals(previous)){assertTrue(seen.add(chunk),"chunk revisited after eviction risk");previous=chunk;}
            assertEquals(100,sequence.size());assertEquals(16,seen.size());
        }
    }

    @Test void firstGridAvoidsHooksThenCompletionReplacesEveryPendingColumn() throws Exception {
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1);
        var basicCalls=new AtomicInteger();var hookCalls=new AtomicInteger();
        var sampler=new ClientTerrainSampler(42,profile){
            @Override boolean stagedSurface(){return true;}
            @Override boolean interiorTerrain(){return true;}
            @Override boolean basicInteriorTerrain(){return false;}
            @Override public ClientColumnSample samplePreview(int x,int z,int step){basicCalls.incrementAndGet();return PredictionSurfaceRefinement.basic(PredictionVoxelFixtures.sample(64));}
            @Override ClientColumnSample sampleInterior(int x,int z){hookCalls.incrementAndGet();return completeColumn(80);}
        };
        try(var manager=new PredictionTileManager(Level.OVERWORLD,sampler,new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,
                0,()->Long.MAX_VALUE,System::nanoTime,1),null,PredictionCpuBudget.fixed(1))) {
            var method=PredictionTileManager.class.getDeclaredMethod("sampleGridFast",int.class,int.class,int.class,int.class,
                    ClientColumnSample[].class,boolean.class,boolean.class,boolean.class,java.util.function.BooleanSupplier.class);
            method.setAccessible(true);var samples=new ClientColumnSample[16];
            method.invoke(manager,0,0,16,4,samples,true,false,true,(java.util.function.BooleanSupplier)()->true);
            assertEquals(16,basicCalls.get());assertEquals(0,hookCalls.get());assertTrue(PredictionSurfaceRefinement.pending(samples));
            method.invoke(manager,0,0,16,4,samples,true,false,false,(java.util.function.BooleanSupplier)()->true);
            assertTrue(hookCalls.get()>0 && hookCalls.get()<=8);assertTrue(PredictionSurfaceRefinement.pending(samples));
            for(int i=0;i<16 && PredictionSurfaceRefinement.pending(samples);i++)
                method.invoke(manager,0,0,16,4,samples,true,false,false,(java.util.function.BooleanSupplier)()->true);
            assertEquals(16,hookCalls.get());assertFalse(PredictionSurfaceRefinement.pending(samples));
            for(var sample:samples)assertEquals(80,sample.surfaceY());
        }
    }

    @Test void surfaceWrapperForwardsBackendGridWithoutDowngradingCompletedSamples() {
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1);
        var calls=new AtomicInteger();
        var completed=completeColumn(80);
        try(var sampler=new PredictionSurfaceTerrainSampler(new ClientTerrainSampler(42,profile){
            @Override boolean supportsLodGrid(){return true;}
            @Override boolean sampleLodGridRequest(int x,int z,int step,int size,ClientColumnSample[] target,
                    boolean preview,java.util.function.BooleanSupplier valid) {
                calls.incrementAndGet();
                for(int i=0;i<target.length;i++)if(target[i]==null)target[i]=PredictionVoxelFixtures.sample(64);
                return true;
            }
        })) {
            assertTrue(sampler.supportsLodGrid());
            var samples=new ClientColumnSample[16];samples[3]=completed;
            assertTrue(sampler.sampleLodGridRequest(-16,-16,4,4,samples,true,()->true));
            assertEquals(1,calls.get());assertSame(completed,samples[3]);
            assertFalse(samples[3].surfacePending());assertTrue(samples[0].surfacePending());
        }
    }

    private static ClientColumnSample completeColumn(int top) {
        return PredictionColumnVolume.sample(0,96,y->y<top?PredictionMaterialPalette.stoneIndex():-1,id->0).asSample();
    }

    @Test void boundedSlicesFinishWholeChunksAndRetainEveryCompletedColumn() {
        var profile=new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","overworld",1);
        var calls=new AtomicInteger();var clock=new java.util.concurrent.atomic.AtomicLong();
        var samples=new ClientColumnSample[100];Arrays.fill(samples,PredictionSurfaceRefinement.basic(PredictionVoxelFixtures.sample(64)));
        try(var sampler=new ClientTerrainSampler(42,profile){
            @Override ClientColumnSample sampleInterior(int x,int z){calls.incrementAndGet();clock.addAndGet(100);return completeColumn(80);}
        }) {
            int slices=0;
            while(PredictionSurfaceRefinement.pending(samples) && slices++<30) {
                int before=calls.get();
                PredictionSurfaceRefinement.completeChunks(sampler,-16,-16,4,10,samples,()->true,clock::get,1,8);
                assertTrue(calls.get()>before && calls.get()-before<=16,"one entire 4x4-column chunk per expired slice");
            }
            assertEquals(16,slices);assertEquals(100,calls.get());assertFalse(PredictionSurfaceRefinement.pending(samples));
            PredictionSurfaceRefinement.completeGridSlice(sampler,-16,-16,4,10,samples,()->true);
            assertEquals(100,calls.get(),"completed progress must survive later turns");
        }
    }

    @Test void croppedHaloProgressSurvivesPublicationAndCannotBecomeReadyEarly() {
        var key=new PredictionTileKey(Level.OVERWORLD,-1,-1,0);
        var full=new ClientColumnSample[36];Arrays.fill(full,completeColumn(80));
        full[0]=PredictionSurfaceRefinement.basic(PredictionVoxelFixtures.sample(64));
        var visible=new ClientColumnSample[25];Arrays.fill(visible,full[1]);
        var data=PredictionVoxelFixtures.flat(key,4,16,80);data.surfaceRefinement=full;
        var tile=new PredictionTile(key,new int[25],new int[25],visible,data,new PredictionDepthBound(64,80),0,1,4,16);
        assertFalse(PredictionSurfaceRefinement.pending(visible));assertTrue(PredictionSurfaceRefinement.pending(tile));
        assertSame(full[0],PredictionTileManager.retainedSample(tile,0,0,16,true,false));
        assertSame(full[1],PredictionTileManager.retainedSample(tile,1,0,16,true,false));
        assertNull(PredictionTileManager.retainedSample(tile,0,0,16,false,false));
        full[0]=full[1];assertFalse(PredictionSurfaceRefinement.pending(tile));
    }

    @Test void diskPersistsPendingSamplesAndKeepsCompletedProgressMonotonic() throws Exception {
        try(var cache=new PredictionDiskCache(directory,42)) {
            var samples=new ClientColumnSample[100];Arrays.fill(samples,PredictionSurfaceRefinement.basic(PredictionVoxelFixtures.sample(64)));
            try(var lease=cache.lease(PredictionDiskCache.Key.terrain(0,0,0))) {
                assertTrue(cache.writeTerrain(lease,samples));
                assertTrue(cache.writeTerrainLater(lease,new PredictionDiskCache.TerrainData(samples,Long.MIN_VALUE,null,null,null)).get());
                var saved=cache.readTerrainData(lease,0);
                assertArrayEquals(samples,saved.samples());assertTrue(PredictionSurfaceRefinement.pending(saved.samples()));
                var completed=samples.clone();completed[0]=completeColumn(80);
                assertTrue(cache.writeTerrainLater(lease,new PredictionDiskCache.TerrainData(completed,Long.MIN_VALUE,null,null,null)).get());
                assertFalse(cache.writeTerrainLater(lease,new PredictionDiskCache.TerrainData(samples,Long.MIN_VALUE,null,null,null)).get());
                assertEquals(80,cache.readTerrainData(lease,0).samples()[0].surfaceY());
            }
        }
    }

    @Test void incompleteChildrenCannotKeepAParentInAnEndlessReductionLoop() {
        var key=new PredictionTileKey(Level.OVERWORLD,0,0,1);
        var children=PredictionParentLodTest.children(key);
        var input=PredictionParentLod.capture(key,8,PredictionParentLodTest.LAYOUT,children,Set.of(),Map.of());
        assertNotNull(input);
        input.children()[0].samples()[0]=PredictionSurfaceRefinement.basic(input.children()[0].samples()[0]);
        assertNull(PredictionParentLod.reduce(input),"parent must perform its own pending hooks if children cannot finish");
        input.children()[0].samples()[0]=PredictionVoxelFixtures.sample(64);
        assertNotNull(PredictionParentLod.reduce(input));
    }
}
