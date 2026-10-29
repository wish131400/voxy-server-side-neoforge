package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionVoxyPreparationTest {
    @Test void fullSectionsCannotEvictReservedFaceAndColumnWorkingSets() {
        try(var source=new PredictionVoxySource(32768,256L*1024)) {
            var tile=fixture(0,0,64,41);source.put(tile);
            long face=VoxyPredictionBridge.position(3,0,0,0);
            var expected=source.face(face,MAPPER,2);
            long columns=(long)source.diagnostics().get("columnCacheBytes");
            long faces=(long)source.diagnostics().get("faceCacheBytes");
            assertTrue(columns>0);assertTrue(faces>0 && faces<=8448L);
            for(int x=4;x<=24;x++) {
                // Each section fits individually; their aggregate exceeds the section
                // budget. Oversized single sections deliberately bypass admission.
                var columnsForSection=new PredictionVoxySource.Column[4096];var random=new Random(41+x);
                for(int i=0;i<columnsForSection.length;i++)columnsForSection[i]=new PredictionVoxySource.Column(
                        List.of(new PredictionVoxySource.Run(-64,4+random.nextInt(27),Blocks.STONE.defaultBlockState())),"minecraft:plains");
                source.put(new PredictionVoxySource.Tile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x,0,0),
                        x*64,0,64,64,1,columnsForSection,List.of(),4096,true,0));
                source.section(VoxyPredictionBridge.position(0,x*2,0,0),MAPPER);
            }
            var diagnostics=source.diagnostics();
            assertTrue((long)diagnostics.get("cacheEvictions")>0,"Exercise full-section byte pressure");
            assertEquals(columns,diagnostics.get("columnCacheBytes"));
            assertEquals(faces,diagnostics.get("faceCacheBytes"));
            assertTrue((long)diagnostics.get("mappedBytes")<=(long)diagnostics.get("sectionCacheLimit"));
            assertTrue((long)diagnostics.get("mappedBytes")+columns+(long)diagnostics.get("faceCacheBytes")<=256L*1024);
            assertArrayEquals(expected,source.face(face,MAPPER,2));assertEquals(1L,source.diagnostics().get("faceCacheHits"));
        }
    }
    @Test void reducedFootprintHashesDoNotCollapseAdjacentGridCoordinates() throws Exception {
        Class<?> key=Arrays.stream(PredictionVoxySource.class.getDeclaredClasses())
                .filter(c->c.getSimpleName().equals("ColumnKey")).findFirst().orElseThrow();
        var constructor=key.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var columns=new PredictionVoxySource.Column[1];var hashes=new HashSet<Integer>();
        for(int z=0;z<64;z++)for(int x=0;x<64;x++)
            hashes.add(constructor.newInstance(columns,256,MAPPER,4,x*4,z*4,x*4+3,z*4+3,true).hashCode());
        assertTrue(hashes.size()>4000,"Rectangular footprint keys must mix both axes: "+hashes.size());
    }
    @Test void indexedFeaturesMatchLinearSelectionAndPreserveOverlappingWriteOrder() {
        var boxes=new ArrayList<PredictionVoxySource.Box>();var random=new Random(882);
        for(int i=0;i<8192;i++) {
            int x=random.nextInt(2048)-1024,z=random.nextInt(2048)-1024,y=random.nextInt(512)-128;
            boxes.add(new PredictionVoxySource.Box(x,y,z,x+1+random.nextInt(80),y+1+random.nextInt(80),z+1+random.nextInt(80),Blocks.STONE.defaultBlockState()));
        }
        var index=new PredictionVoxyFeatures(List.copyOf(boxes));
        long candidates=0;
        for(int n=0;n<100;n++) {
            int x=random.nextInt(2048)-1024,z=random.nextInt(2048)-1024,y=random.nextInt(512)-128;
            var expected=new ArrayList<Integer>();
            for(int i=0;i<boxes.size();i++){var b=boxes.get(i);if(b.minX()<x+32 && b.maxX()>x && b.minY()<y+32 && b.maxY()>y && b.minZ()<z+32 && b.maxZ()>z)expected.add(i);}
            int[] actual=index.intersect(x,y,z,x+32,y+32,z+32);candidates+=actual.length;
            assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(),actual);
        }
        assertTrue(candidates<boxes.size(),"Local queries must not replay the whole forest");
        System.out.printf(Locale.ROOT,"FEATURE_INDEX boxes=%d queries=100 selected=%d linearCandidates=%d%n",boxes.size(),candidates,boxes.size()*100);
    }
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    static final VoxyPredictionBridge.Mapper MAPPER=(s,b)-> s.isAir()?15L<<56:
            15L<<56 | (long)(s.is(Blocks.WATER)?2:s.is(Blocks.DIRT)?3:1)<<27 | (b.endsWith("desert")?1L<<47:0);
    static PredictionVoxySource.Tile fixture(int bx,int bz,int axis,int seed) {
        var random=new Random(seed);var columns=new PredictionVoxySource.Column[axis*axis];
        for(int i=0;i<columns.length;i++) {
            int top=1+random.nextInt(110),cave=1+random.nextInt(16);
            columns[i]=new PredictionVoxySource.Column(List.of(
                    new PredictionVoxySource.Run(-64,-cave,Blocks.STONE.defaultBlockState()),
                    new PredictionVoxySource.Run(0,top,Blocks.DIRT.defaultBlockState()),
                    new PredictionVoxySource.Run(top,top+random.nextInt(7)+1,Blocks.WATER.defaultBlockState())),
                    i%3==0?"minecraft:desert":"minecraft:plains");
        }
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,Math.floorDiv(bx,axis),Math.floorDiv(bz,axis),0);
        return new PredictionVoxySource.Tile(key,bx,bz,axis,axis,1,columns,List.of(
                new PredictionVoxySource.Box(0,100,0,2,140,2,Blocks.STONE.defaultBlockState()),
                new PredictionVoxySource.Box(axis-3,70,axis-3,axis,130,axis,Blocks.DIRT.defaultBlockState())),4096,true,1);
    }
    /** Original rasterization, deliberately independent of the reduced-run implementation. */
    static long[] reference(PredictionVoxySource.Tile tile,long pos) {
        long[] result=new long[32768];Arrays.fill(result,15L<<56);
        int step=1<<VoxyPredictionBridge.level(pos),span=32*step,
                bx=VoxyPredictionBridge.x(pos)*span,by=VoxyPredictionBridge.y(pos)*span,bz=VoxyPredictionBridge.z(pos)*span;
        for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
            int wx=bx+x*step+step/2,wz=bz+z*step+step/2;
            if(wx<tile.baseX() || wz<tile.baseZ() || wx>=tile.baseX()+tile.span() || wz>=tile.baseZ()+tile.span())continue;
            int minX=Math.max(0,Math.floorDiv(bx+x*step-tile.baseX(),tile.step())),
                minZ=Math.max(0,Math.floorDiv(bz+z*step-tile.baseZ(),tile.step())),
                maxX=Math.min(tile.axis()-1,Math.floorDiv(bx+(x+1)*step-1-tile.baseX(),tile.step())),
                maxZ=Math.min(tile.axis()-1,Math.floorDiv(bz+(z+1)*step-1-tile.baseZ(),tile.step()));
            for(int cz=minZ;cz<=maxZ;cz++)for(int cx=minX;cx<=maxX;cx++) {
                var col=tile.columns()[cz*tile.axis()+cx];
                for(var run:col.runs()) {
                    int bottom=Math.max(0,Math.floorDiv(run.bottom()-by,step)),top=Math.min(32,-Math.floorDiv(by-run.top(),step));
                    for(int y=bottom;y<top;y++) {
                        long value=MAPPER.map(run.state(),col.biome());
                        if(step>1 && run.state().getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                            value|=16L;
                            if(y==Math.floorDiv(run.top()-1-by,step))value|=(-run.top())&(step-1);
                        }
                        result[y<<10|z<<5|x]=value;
                    }
                }
            }
            for(var box:tile.features()) {
                int loX=Math.floorDiv(tile.baseX()+box.minX()-bx,step),hiX=-Math.floorDiv(bx-tile.baseX()-box.maxX(),step),
                    loZ=Math.floorDiv(tile.baseZ()+box.minZ()-bz,step),hiZ=-Math.floorDiv(bz-tile.baseZ()-box.maxZ(),step);
                if(x<loX || x>=hiX || z<loZ || z>=hiZ)continue;
                int tx=Math.max(0,Math.min(tile.axis()-1,Math.floorDiv(box.minX(),tile.step()))),
                    tz=Math.max(0,Math.min(tile.axis()-1,Math.floorDiv(box.minZ(),tile.step())));
                long value=MAPPER.map(box.state(),tile.columns()[tz*tile.axis()+tx].biome());
                for(int y=Math.max(0,Math.floorDiv(box.minY()-by,step));y<Math.min(32,-Math.floorDiv(by-box.maxY(),step));y++)
                    result[y<<10|z<<5|x]=value;
            }
        }
        return result;
    }
    @Test void coldFacesAndReducedVolumesMatchOriginalRasterizationAtEveryLodAndSignedHeight() {
        for(int base:new int[]{-64,0}) {
            var tile=fixture(base,base,64,419);
            for(int lod=0;lod<=4;lod++)for(int sy:new int[]{-2,-1,0,1,2}) {
                long pos=VoxyPredictionBridge.position(lod,base<0?-1:0,sy,base<0?-1:0);
                long[] expected=reference(tile,pos);
                try(var source=new PredictionVoxySource()) {
                    source.put(tile);
                    for(int face=0;face<6;face++) {
                        long[] actual=source.face(pos,MAPPER,face);assertNotNull(actual);
                        for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(face,i)],actual[i],"lod="+lod+", face="+face+", i="+i);
                    }
                    assertEquals(0L,source.diagnostics().get("sectionBuilds"),"Cold sky-lit face reads must not build whole sections");
                    assertArrayEquals(expected,source.section(pos,MAPPER));
                }
            }
        }
    }
    @Test void intervalOverwritesMatchDenseWritesIncludingAirGapsAndNegativeCoordinates() {
        var random=new Random(195);long[] expected=new long[512];Arrays.fill(expected,15L<<56);
        var builder=new PredictionVoxyColumnRuns.Builder(15L<<56);
        for(int i=0;i<2000;i++) {
            int bottom=random.nextInt(510)-255,top=Math.min(256,bottom+random.nextInt(50));
            long value=random.nextInt(4)==0?15L<<56:random.nextInt(20)+1;
            builder.put(bottom,top,value);Arrays.fill(expected,bottom+256,top+256,value);
            if(i%50==0) {
                var runs=builder.build();for(int y=-256;y<256;y++)assertEquals(expected[y+256],runs.value(y,15L<<56));
            }
        }
    }
    @Test void directDestinationStaysIndependentAndUnavailableReadsNeverAllocate() {
        var calls=new AtomicInteger();long[] target=new long[32768];
        try(var source=new PredictionVoxySource()) {
            long pos=VoxyPredictionBridge.position(2,0,0,0);
            assertFalse(source.copySection(pos,MAPPER,true,()->{calls.incrementAndGet();return target;}));assertEquals(0,calls.get());
            var tile=fixture(0,0,64,91);source.put(tile);
            assertTrue(source.copySection(pos,MAPPER,true,()->{calls.incrementAndGet();return target;}));assertEquals(1,calls.get());
            assertEquals(1L,source.diagnostics().get("directSectionWrites"));assertArrayEquals(reference(tile,pos),target);
            Arrays.fill(target,-1);assertArrayEquals(reference(tile,pos),source.section(pos,MAPPER));
        }
    }
    @Test void faceDestinationsNeverExposeCachedStorageAndParallelSourcesKeepTheirOwnScratch() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try(var first=new PredictionVoxySource();var second=new PredictionVoxySource()) {
            var a=fixture(-64,-64,64,41);var b=fixture(-64,-64,64,78);first.put(a);second.put(b);
            var work=new ArrayList<java.util.concurrent.Callable<Void>>();
            for(int n=0;n<4;n++) {
                boolean one=(n&1)==0;var source=one?first:second;var tile=one?a:b;
                work.add(()->{
                    for(int lod=0;lod<=3;lod++) {
                        long pos=VoxyPredictionBridge.position(lod,-1,0,-1);long[] expected=reference(tile,pos);
                        var destination=new long[1024];
                        for(int face=0;face<6;face++)for(int round=0;round<3;round++) {
                            assertTrue(source.copyFace(pos,MAPPER,face,destination));
                            for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(face,i)],destination[i]);
                            Arrays.fill(destination,-1L);
                        }
                    }
                    return null;
                });
            }
            for(var result:pool.invokeAll(work))result.get(30,java.util.concurrent.TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }
    @Test void reducedRunsAreSharedAcrossHeightsAndRetiredWithTheirMapperTileAndBudget() {
        var budget=new PredictionMemoryBudget(8*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime);
        try(var source=new PredictionVoxySource()) {
            source.budget(budget);var tile=fixture(0,0,64,41);source.put(tile);
            source.face(VoxyPredictionBridge.position(3,0,0,0),MAPPER,2);
            long builds=(long)source.diagnostics().get("columnBuilds");assertTrue(builds>0);
            source.face(VoxyPredictionBridge.position(3,0,-1,0),MAPPER,3);
            assertEquals(builds,source.diagnostics().get("columnBuilds"));assertTrue((long)source.diagnostics().get("columnCacheHits")>0);
            assertTrue(budget.usedBytes()>0);source.releaseMapper(MAPPER);assertEquals(0,budget.usedBytes());
            VoxyPredictionBridge.Mapper replacement=(s,b)->MAPPER.map(s,b)^1L<<48;
            long pos=VoxyPredictionBridge.position(3,0,0,0);long[] face=source.face(pos,replacement,2);
            assertNotNull(face);assertTrue((long)source.diagnostics().get("columnCacheBytes")>0);
            source.remove(tile.key());assertEquals(0,budget.usedBytes());assertNull(source.face(pos,replacement,2));
        }
        assertEquals(0,budget.usedBytes());
    }
    @Test void coldFacesKeepOverlappingTerrainAndCompletedVegetationOwnershipAfterReplacement() {
        var parent=fixture(-64,-64,64,31);var sampled=fixture(-32,-32,32,57);
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,-1,1);
        for(boolean complete:new boolean[]{false,true})try(var source=new PredictionVoxySource()) {
            var child=new PredictionVoxySource.Tile(key,-32,-32,32,32,1,sampled.columns(),List.of(),4096,complete,0);
            source.put(parent);source.put(child);
            for(int lod=0;lod<=4;lod++)for(int sy:new int[]{-1,0,1}) {
                long pos=VoxyPredictionBridge.position(lod,-1,sy,-1);
                long[] expected=source.section(pos,MAPPER);source.releaseMapper(MAPPER);
                for(int face=0;face<6;face++) {
                    long[] actual=source.face(pos,MAPPER,face);
                    for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(face,i)],actual[i]);
                }
            }
            source.put(new PredictionVoxySource.Tile(parent.key(),-64,-64,64,64,1,
                    fixture(-64,-64,64,89).columns(),List.of(),4096,true,0));
            long pos=VoxyPredictionBridge.position(3,-1,0,-1);long[] expected=source.section(pos,MAPPER);
            source.releaseMapper(MAPPER);var actual=source.face(pos,MAPPER,4);
            for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(4,i)],actual[i]);
        }
    }
    @Test void inFlightColumnReductionCannotRepopulateRemovedTilesOrClosedSources() throws Exception {
        for(boolean close:new boolean[]{false,true}) {
            var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
            var budget=new PredictionMemoryBudget(8*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime);
            var first=new java.util.concurrent.atomic.AtomicBoolean(true);
            VoxyPredictionBridge.Mapper mapper=(state,biome)->{
                if(first.getAndSet(false)) {
                    entered.countDown();
                    try {assertTrue(resume.await(5,java.util.concurrent.TimeUnit.SECONDS));}
                    catch(InterruptedException failure) {throw new AssertionError(failure);}
                }
                return MAPPER.map(state,biome);
            };
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try(var source=new PredictionVoxySource()) {
                var tile=fixture(0,0,64,41);source.budget(budget);source.put(tile);
                var pending=pool.submit(()->source.face(VoxyPredictionBridge.position(3,0,0,0),mapper,2));
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                if(close)source.close();else source.remove(tile.key());
                resume.countDown();assertNotNull(pending.get(5,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0,budget.usedBytes());assertEquals(0L,source.diagnostics().get("columnCacheBytes"));
                assertNull(source.face(VoxyPredictionBridge.position(3,0,0,0),mapper,2));
            } finally {resume.countDown();pool.shutdownNow();}
        }
    }
    @Test void rejectedCacheReservationsStillReturnExactVoxelsAndNetherFacesRetainFullLighting() {
        try(var source=new PredictionVoxySource()) {
            source.budget(new PredictionMemoryBudget(1,0,()->Long.MAX_VALUE,System::nanoTime));
            var tile=fixture(0,0,64,41);source.put(tile);long pos=VoxyPredictionBridge.position(3,0,0,0);
            long[] expected=reference(tile,pos),actual=source.face(pos,MAPPER,2);
            for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(2,i)],actual[i]);
            assertEquals(0L,source.diagnostics().get("columnCacheBytes"));
            assertTrue((long)source.diagnostics().get("columnBuildNanos")>0);
        }
        VoxyPredictionBridge.Mapper nether=new VoxyPredictionBridge.Mapper() {
            public boolean hasSkyLight(){return false;}
            public long map(net.minecraft.world.level.block.state.BlockState s,String b){return MAPPER.map(s,b)&~(15L<<56);}
        };
        try(var source=new PredictionVoxySource()) {
            source.put(fixture(0,0,64,75));long pos=VoxyPredictionBridge.position(2,0,0,0);
            long[] expected=source.section(pos,nether);source.releaseMapper(nether);
            for(int face=0;face<6;face++) {
                var actual=source.face(pos,nether,face);
                for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(face,i)],actual[i]);
            }
            assertEquals(0L,source.diagnostics().get("faceBuilds"));
            assertTrue((long)source.diagnostics().get("sectionBuilds")>0);
        }
    }
    private static PredictionVoxySource.Tile republished(PredictionVoxySource.Tile tile,List<PredictionVoxySource.Box> features,boolean complete) {
        var columns=Arrays.stream(tile.columns()).map(c->new PredictionVoxySource.Column(List.copyOf(c.runs()),c.biome()))
                .toArray(PredictionVoxySource.Column[]::new);
        return new PredictionVoxySource.Tile(tile.key(),tile.baseX(),tile.baseZ(),tile.span(),tile.axis(),tile.step(),
                columns,features,tile.terrainBytes(),complete,tile.cityFeatures());
    }
    @Test void identicalPublicationsKeepMappedSectionsAndReducedColumnsWhileDecorationRetainsTerrain() {
        try(var source=new PredictionVoxySource()) {
            var tile=fixture(0,0,64,315);source.put(tile);long pos=VoxyPredictionBridge.position(2,0,0,0);
            var existing=source.mappedSection(pos,MAPPER);
            long builds=(long)source.diagnostics().get("columnBuilds");assertTrue(builds>0);
            for(int i=0;i<20;i++)source.put(republished(tile,List.copyOf(tile.features()),true));
            assertSame(existing,source.mappedSection(pos,MAPPER));
            assertEquals(20L,source.diagnostics().get("unchangedPublications"));
            assertEquals(0L,source.diagnostics().get("cacheInvalidations"));
            assertEquals(builds,source.diagnostics().get("columnBuilds"));
            source.put(republished(tile,List.of(),true));
            assertNotSame(existing,source.mappedSection(pos,MAPPER));
            assertEquals(builds,source.diagnostics().get("columnBuilds"),"Decoration changes must keep identical coarse terrain");
            assertEquals(1L,source.diagnostics().get("retainedColumnPublications"));
        }
    }
    @Test void completionBiomeAndTerrainChangesStillInvalidateRenderedContent() {
        try(var source=new PredictionVoxySource()) {
            var tile=fixture(0,0,64,71);source.put(tile);long pos=VoxyPredictionBridge.position(2,0,0,0);
            var old=source.mappedSection(pos,MAPPER);
            var pending=republished(tile,tile.features(),false);source.put(pending);
            var changed=source.mappedSection(pos,MAPPER);assertNotSame(old,changed);
            var columns=pending.columns().clone();
            columns[0]=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,145,Blocks.DIRT.defaultBlockState())),"minecraft:desert");
            source.put(new PredictionVoxySource.Tile(tile.key(),0,0,64,64,1,columns,tile.features(),4096,false,0));
            assertNotSame(changed,source.mappedSection(pos,MAPPER));
            assertEquals(0L,source.diagnostics().get("unchangedPublications"));
        }
    }
    @Test void terrainEditsInvalidateIntersectingSectionsAndKeepTheRestOfTheTileMapped() {
        try(var source=new PredictionVoxySource()) {
            var tile=fixture(0,0,64,71);source.put(tile);
            long near=VoxyPredictionBridge.position(0,0,0,0),far=VoxyPredictionBridge.position(0,1,0,1);
            var oldNear=source.mappedSection(near,MAPPER);var oldFar=source.mappedSection(far,MAPPER);
            var columns=tile.columns().clone();
            columns[0]=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,120,Blocks.STONE.defaultBlockState())),columns[0].biome());
            source.put(new PredictionVoxySource.Tile(tile.key(),0,0,64,64,1,columns,tile.features(),4096,true,tile.cityFeatures()));
            assertNotSame(oldNear,source.mappedSection(near,MAPPER));assertSame(oldFar,source.mappedSection(far,MAPPER));
            assertArrayEquals(reference(new PredictionVoxySource.Tile(tile.key(),0,0,64,64,1,columns,tile.features(),4096,true,0),near),source.section(near,MAPPER));
        }
    }
    @Test void repeatedFaceReadsReuseImmutablePlanesAndContentChangesRevokeThem() {
        try(var source=new PredictionVoxySource()) {
            var tile=fixture(0,0,64,71);source.put(tile);long pos=VoxyPredictionBridge.position(2,0,0,0);
            var expected=source.face(pos,MAPPER,2);long[] first=expected.clone();Arrays.fill(expected,-1);
            for(int i=0;i<20;i++)assertArrayEquals(first,source.face(pos,MAPPER,2));
            assertEquals(1L,source.diagnostics().get("faceBuilds"));assertEquals(20L,source.diagnostics().get("faceCacheHits"));
            source.put(republished(tile,tile.features(),true));assertArrayEquals(first,source.face(pos,MAPPER,2));
            assertEquals(1L,source.diagnostics().get("faceBuilds"));
            var replacement=fixture(0,0,64,97);source.put(replacement);
            var actual=source.face(pos,MAPPER,2);var reference=reference(replacement,pos);
            for(int i=0;i<1024;i++)assertEquals(reference[VoxyFaceData.index(2,i)],actual[i]);
            assertEquals(2L,source.diagnostics().get("faceBuilds"));
        }
    }
    @Test void repeatedCoarseNeighborBenchmarkReportsTimeAndChecksIdenticalResults() {
        var tile=fixture(0,0,256,915);long pos=VoxyPredictionBridge.position(3,0,0,0);
        long[] expected=reference(tile,pos);long checksum=0;
        try(var source=new PredictionVoxySource()) {
            source.put(tile);for(int i=0;i<8;i++)source.face(pos,MAPPER,2);
            long start=System.nanoTime();
            for(int i=0;i<20;i++) {var data=reference(tile,pos);checksum^=data[i];}
            long baseline=System.nanoTime()-start;start=System.nanoTime();
            for(int i=0;i<20;i++) {var face=source.face(pos,MAPPER,2);checksum^=face[i];}
            long optimized=System.nanoTime()-start;
            var actual=source.face(pos,MAPPER,2);for(int i=0;i<1024;i++)assertEquals(expected[i],actual[i]);
            System.out.printf(java.util.Locale.ROOT,"COARSE_FACE_REPLAY baselineMs=%.3f optimizedMs=%.3f checksum=%d diagnostics=%s%n",baseline/1e6,optimized/1e6,checksum,source.diagnostics());
        }
    }

    @Test void reverseIntervalsPreserveExplicitAirAndArbitraryOverlaps() {
        var random=new Random(9123);long air=15L<<56;
        for(int trial=0;trial<200;trial++) {
            int count=100;int[] lo=new int[count],hi=new int[count];long[] values=new long[count];
            var forward=new PredictionVoxyColumnRuns.Builder(air);
            for(int i=0;i<count;i++) {lo[i]=random.nextInt(200)-100;hi[i]=lo[i]+random.nextInt(40);values[i]=i%3==0?air:1+i%7;forward.put(lo[i],hi[i],values[i]);}
            var reverse=new PredictionVoxyColumnRuns.Builder(air);var coverage=new PredictionVoxyColumnRuns.Coverage();
            for(int i=count-1;i>=0;i--)coverage.put(reverse,lo[i],hi[i],values[i]);
            var expected=forward.build();var actual=reverse.build();
            for(int y=-110;y<150;y++)assertEquals(expected.value(y,air),actual.value(y,air),"trial="+trial+" y="+y);
        }
    }
    @Test void recentColumnHitsReceiveASecondChanceWithoutExtendingTheBudget() throws Exception {
        try(var source=new PredictionVoxySource()) {
            source.put(fixture(0,0,64,71));
            source.section(VoxyPredictionBridge.position(1,0,0,0),MAPPER);
            // A new Y section reads the same horizontal reductions.
            source.section(VoxyPredictionBridge.position(1,0,1,0),MAPPER);
            var field=PredictionVoxySource.class.getDeclaredField("reducedColumns");field.setAccessible(true);
            int before=((Map<?,?>)field.get(source)).size();assertTrue(before>1);
            var evict=PredictionVoxySource.class.getDeclaredMethod("evictColumn");evict.setAccessible(true);evict.invoke(source);
            assertEquals(before-1,((Map<?,?>)field.get(source)).size());
            assertTrue(((Number)source.diagnostics().get("columnSecondChances")).longValue()>0);
        }
    }

    @Test void compactFacesReuseMorePlanesWithinTheSameByteBudget() {
        try(var source=new PredictionVoxySource(32768,256L*1024)) {
            var column=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,256,
                    Blocks.STONE.defaultBlockState())),"minecraft:plains");
            var columns=new PredictionVoxySource.Column[1024];Arrays.fill(columns,column);
            for(int n=0;n<24;n++) {
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,n*2,0,0);
                source.put(new PredictionVoxySource.Tile(key,n*64,0,32,32,1,columns,List.of(),4096,true,0));
                assertNotNull(source.face(VoxyPredictionBridge.position(0,n*2,0,0),MAPPER,2));
            }
            var before=source.diagnostics();assertEquals(24,before.get("cachedFaces"));
            assertTrue((long)before.get("faceCacheBytes")<=(long)before.get("faceCacheLimit"));
            for(int n=0;n<24;n++)assertNotNull(source.face(VoxyPredictionBridge.position(0,n*2,0,0),MAPPER,2));
            assertEquals(before.get("faceBuilds"),source.diagnostics().get("faceBuilds"));
            assertEquals(24L,source.diagnostics().get("faceCacheHits"));
        }
    }

}
