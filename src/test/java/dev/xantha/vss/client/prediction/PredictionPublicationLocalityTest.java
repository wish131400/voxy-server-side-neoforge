package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.client.prediction.PredictionVoxySource.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionPublicationLocalityTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final VoxyPredictionBridge.Mapper MAPPER = (state, biome) ->
            (long)(net.minecraft.world.level.block.Block.getId(state)+1) << 27
                    | (biome.equals("minecraft:desert") ? 1L << 47 : 0);
    private static Column column(int top) {
        return new Column(List.of(new Run(-64,top,Blocks.STONE.defaultBlockState())),"minecraft:plains");
    }
    private static Tile tile(Column[] columns, List<Box> features) {
        return new Tile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,-1,0),
                -64,-64,64,64,1,columns,features,4096,true,0);
    }
    private static Object preparedIdentity(PredictionVoxySource source,long pos,VoxyPredictionBridge.Mapper mapper) {
        source.mappedSection(pos,mapper,false);
        int lod=VoxyPredictionBridge.level(pos),sx=VoxyPredictionBridge.x(pos),sy=VoxyPredictionBridge.y(pos),sz=VoxyPredictionBridge.z(pos);
        for(int face=0;face<6;face++)source.face(VoxyPredictionBridge.position(lod,
                sx+(face==0?-1:face==1?1:0),sy+(face==2?-1:face==3?1:0),sz+(face==4?-1:face==5?1:0)),mapper,face^1);
        return source.notificationIdentity(pos,mapper);
    }
    @Test void notificationEvidenceUsesPreparedInputsAndDetectsNeighborsAndMapperReplacement() {
        var columns=new Column[4096];Arrays.fill(columns,column(32));
        try(var source=new PredictionVoxySource()) {
            source.put(tile(columns,List.of()));
            long pos=VoxyPredictionBridge.position(0,-2,0,-2);
            assertNull(source.notificationIdentity(pos,MAPPER));
            assertEquals(0L,source.diagnostics().get("sectionBuilds"));
            assertEquals(0L,source.diagnostics().get("faceBuilds"));
            Object first=preparedIdentity(source,pos,MAPPER);
            assertNotNull(first);assertEquals(first,source.notificationIdentity(pos,MAPPER));
            var changed=columns.clone();changed[0]=column(33);
            source.put(tile(changed,List.of()));
            assertNotEquals(first,preparedIdentity(source,pos,MAPPER),"Top neighbor plane changes even if center remains solid");
            Object current=source.notificationIdentity(pos,MAPPER);
            source.releaseMapper(MAPPER);
            assertNull(source.notificationIdentity(pos,MAPPER),"A missing cache must not trigger preparation");
            assertEquals(current,preparedIdentity(source,pos,MAPPER),"Rebuilt equal compact data keeps its content identity");
            VoxyPredictionBridge.Mapper other=(state,biome)->MAPPER.map(state,biome)^1L<<47;
            assertNull(source.notificationIdentity(pos,other));
            assertNotEquals(current,preparedIdentity(source,pos,other));
        }
    }
    @Test void notificationProbeNeverRasterizesEvictedInputsOrPromotesOneShotSections() {
        var columns=new Column[4096];Arrays.fill(columns,column(32));
        try(var source=new PredictionVoxySource(2)) {
            source.put(tile(columns,List.of()));
            long pos=VoxyPredictionBridge.position(0,-2,0,-2);
            assertNotNull(preparedIdentity(source,pos,MAPPER));
            long builds=(long)source.diagnostics().get("sectionBuilds"),faces=(long)source.diagnostics().get("faceBuilds");
            for(int i=0;i<25;i++)assertNotNull(source.notificationIdentity(pos,MAPPER));
            assertEquals(builds,source.diagnostics().get("sectionBuilds"));
            assertEquals(faces,source.diagnostics().get("faceBuilds"));
            assertEquals(0,source.diagnostics().get("protectedSections"));
            source.section(VoxyPredictionBridge.position(0,-1,0,-2),MAPPER);
            source.section(VoxyPredictionBridge.position(0,-2,0,-1),MAPPER);
            builds=(long)source.diagnostics().get("sectionBuilds");
            faces=(long)source.diagnostics().get("faceBuilds");
            assertNull(source.notificationIdentity(pos,MAPPER));
            assertEquals(builds,source.diagnostics().get("sectionBuilds"));
            assertEquals(faces,source.diagnostics().get("faceBuilds"));
        }
    }
    @Test void distantPublicationKeepsPreparedLocalEvidence() {
        var columns=new Column[4096];Arrays.fill(columns,column(32));
        try(var source=new PredictionVoxySource()) {
            source.put(tile(columns,List.of()));
            long pos=VoxyPredictionBridge.position(0,-2,0,-2);
            Object first=preparedIdentity(source,pos,MAPPER);assertNotNull(first);
            source.put(new Tile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,100,100,0),
                    6400,6400,64,64,1,columns,List.of(),4096,true,0));
            assertEquals(first,source.notificationIdentity(pos,MAPPER));
        }
    }
    @Test void notificationProbeDoesNotWaitForAnInflightSection() throws Exception {
        var columns=new Column[4096];Arrays.fill(columns,column(32));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        VoxyPredictionBridge.Mapper blocked=(state,biome)->{
            entered.countDown();
            try {assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));}
            catch(InterruptedException e){throw new AssertionError(e);}
            return MAPPER.map(state,biome);
        };
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var source=new PredictionVoxySource()) {
            source.put(tile(columns,List.of()));
            long pos=VoxyPredictionBridge.position(0,-2,0,-2);
            var build=worker.submit(()->source.section(pos,blocked));
            try {
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->assertNull(source.notificationIdentity(pos,blocked)));
            } finally {release.countDown();}
            assertNotNull(build.get(5,java.util.concurrent.TimeUnit.SECONDS));
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void surfaceEditRetainsDeepSectionsAndFacesBeforeAnyComposition() {
        var columns=new Column[4096]; Arrays.fill(columns,column(130));
        var changed=columns.clone(); changed[0]=column(131);
        try(var source=new PredictionVoxySource()) {
            source.put(tile(columns,List.of()));
            long deep=VoxyPredictionBridge.position(0,-2,1,-2),surface=VoxyPredictionBridge.position(0,-2,4,-2);
            var retained=source.mappedSection(deep,MAPPER); var old=source.section(surface,MAPPER).clone();
            var border=source.face(VoxyPredictionBridge.position(0,-2,2,-2),MAPPER,0);
            long faceBuilds=(long)source.diagnostics().get("faceBuilds");
            source.put(tile(changed,List.of()));
            assertSame(retained,source.mappedSection(deep,MAPPER));
            assertArrayEquals(border,source.face(VoxyPredictionBridge.position(0,-2,2,-2),MAPPER,0));
            assertEquals(faceBuilds,source.diagnostics().get("faceBuilds"));
            assertFalse(Arrays.equals(old,source.section(surface,MAPPER)));
        }
    }
    @Test void biomeAnchorStillRecolorsPlantsFarAboveItsTerrain() {
        var columns=new Column[4096]; Arrays.fill(columns,column(8));
        var changed=columns.clone(); changed[0]=new Column(columns[0].runs(),"minecraft:desert");
        var boxes=List.of(new Box(0,120,0,1,150,1,Blocks.OAK_LEAVES.defaultBlockState()));
        long pos=VoxyPredictionBridge.position(0,-2,4,-2);
        try(var source=new PredictionVoxySource();var fresh=new PredictionVoxySource()) {
            source.put(tile(columns,boxes)); var old=source.section(pos,MAPPER).clone();
            source.put(tile(changed,boxes));fresh.put(tile(changed,boxes));
            assertFalse(Arrays.equals(old,fresh.section(pos,MAPPER)));
            assertArrayEquals(fresh.section(pos,MAPPER),source.section(pos,MAPPER));
        }
    }
    @Test void intervalDifferenceMatchesDenseStateChangesAndPreservesFluidMarkers() {
        var random=new Random(721);
        for(int round=0;round<100;round++) {
            var a=new ArrayList<Run>();var b=new ArrayList<Run>();
            for(var runs:List.of(a,b))for(int i=0;i<8;i++) {
                int bottom=random.nextInt(96)-48,top=bottom+1+random.nextInt(24);
                var state=switch(random.nextInt(3)) {case 0->Blocks.STONE;case 1->Blocks.DIRT;default->Blocks.AIR;};
                runs.add(new Run(bottom,top,state.defaultBlockState()));
            }
            var delta=new PredictionTerrainChange();delta.compare(new Column(a,"a"),new Column(b,"a"));
            for(int y=-64;y<96;y++) {
                var sa=Blocks.AIR.defaultBlockState();var sb=sa;
                for(var run:a)if(y>=run.bottom() && y<run.top())sa=run.state();
                for(var run:b)if(y>=run.bottom() && y<run.top())sb=run.state();
                if(sa!=sb)assertTrue(y>=delta.bottom() && y<delta.top());
            }
        }
        var water=Blocks.WATER.defaultBlockState();
        var delta=new PredictionTerrainChange();
        delta.compare(new Column(List.of(new Run(0,16,water)),"a"),
                new Column(List.of(new Run(0,8,water),new Run(8,16,water)),"a"));
        assertTrue(delta.bottom()<=7 && delta.top()>=16);
    }
    @Test void cachedPublicationMatchesFreshCompositionAtEveryNativeLod() {
        var random=new Random(2721);
        for(int round=0;round<8;round++) {
            var columns=new Column[4096];Arrays.fill(columns,column(130));
            var changed=columns.clone();
            for(int i=0;i<8;i++) {
                int index=random.nextInt(changed.length),top=random.nextInt(220)-32;
                changed[index]=new Column(List.of(new Run(-64,top,Blocks.STONE.defaultBlockState()),
                        new Run(top,top+3,(i%2==0?Blocks.WATER:Blocks.DIRT).defaultBlockState())),
                        i%3==0?"minecraft:desert":"minecraft:plains");
            }
            try(var source=new PredictionVoxySource();var fresh=new PredictionVoxySource()) {
                source.put(tile(columns,List.of()));fresh.put(tile(changed,List.of()));
                var positions=new ArrayList<Long>();
                for(int lod=0;lod<=4;lod++)for(int y=-2;y<=6;y++) {
                    long pos=VoxyPredictionBridge.position(lod,-1,y,-1);positions.add(pos);source.section(pos,MAPPER);
                }
                source.put(tile(changed,List.of()));
                for(long pos:positions)assertArrayEquals(fresh.section(pos,MAPPER),source.section(pos,MAPPER),
                        "lod="+VoxyPredictionBridge.level(pos)+", y="+VoxyPredictionBridge.y(pos));
            }
        }
    }
}
