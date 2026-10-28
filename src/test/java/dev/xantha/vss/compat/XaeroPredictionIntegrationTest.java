package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import xaero.map.*;
import xaero.map.region.*;

class XaeroPredictionIntegrationTest {
    private MapProcessor processor;
    private XaeroMapCompat bridge;
    private final AtomicBoolean enabled=new AtomicBoolean(true),active=new AtomicBoolean(true),loaded=new AtomicBoolean();
    private static final XaeroPredictionBridge.Quality COARSE=new XaeroPredictionBridge.Quality(4,4,false);
    private static final XaeroPredictionBridge.Quality FINE=new XaeroPredictionBridge.Quality(1,1,true);

    @BeforeAll static void bootstrap() { XaeroTileExtractorTest.bootstrapMinecraft(); }
    @BeforeEach void setup() throws Exception {
        processor=new MapProcessor(); processor.world=new Object(); processor.mainWorld=processor.world;
        processor.mapWorld.currentDimensionId=Level.OVERWORLD;
        var session=new WorldMapSession(); session.processor=processor; WorldMapSession.current=session;
        bridge=createBridge(); XaeroMapCompat.facadeForTest(bridge);
    }
    private XaeroMapCompat createBridge() throws Exception {
        var result=new XaeroMapCompat(XaeroMapCompat.Handles.resolve(Class::forName),new XaeroMapCompat.LevelOps() {
            public Object dimension(Object world) { return Level.OVERWORLD; }
            public boolean isChunkLoaded(Object world,int x,int z) { return loaded.get(); }
        },enabled::get,active::get,c -> {},c -> {});
        result.pumpNanosBudget=Long.MAX_VALUE; return result;
    }
    @AfterEach void cleanup() throws Exception {
        XaeroPredictionBridge.reset(); XaeroPredictionBridge.awaitProjectionForTest();
        XaeroPredictionStore.awaitIoForTest(); XaeroMapCompat.resetFacadeForTest();
        WorldMapSession.current=null; XaeroStubEvents.clear();
    }
    private static XaeroPredictionBridge.Surface surface(int height,boolean tree) {
        var runs=new ArrayList<XaeroPredictionBridge.Run>();
        runs.add(new XaeroPredictionBridge.Run(-64,height,Blocks.GRASS_BLOCK.defaultBlockState()));
        if(tree) runs.add(new XaeroPredictionBridge.Run(height,height+8,Blocks.OAK_LEAVES.defaultBlockState()));
        var pixels=new XaeroPredictionBridge.Pixel[256];
        Arrays.fill(pixels,new XaeroPredictionBridge.Pixel(List.copyOf(runs),"minecraft:forest"));
        return new XaeroPredictionBridge.Surface(pixels,tree?FINE:COARSE,() -> true);
    }
    private static XaeroTileExtractor.PreparedTile tile(int x,int z,int height,boolean tree) {
        return XaeroTileExtractor.extractPrediction(x,z,-64,320,surface(height,tree));
    }
    private void predict(int x,int z,int height,boolean tree) {
        bridge.offerPredicted(Level.OVERWORLD,tile(x,z,height,tree),tree?FINE:COARSE,() -> true);
    }
    private void drain() throws Exception {
        for(int n=0;n<3;n++) { bridge.pump(); XaeroPredictionStore.awaitIoForTest(); }
    }
    private MapTile map(int x,int z) {
        return processor.getLeafMapRegion(Integer.MAX_VALUE,x>>5,z>>5,true)
                .getChunk((x>>2)&7,(z>>2)&7).getTile(x&3,z&3);
    }
    @Test void predictedTerrainThenVegetationRefineAndRealDeliveryWinsBothQueueAndMap() throws Exception {
        predict(-1,-2,64,false); drain();
        assertTrue(map(-1,-2).blocks[0][0].state.is(Blocks.GRASS_BLOCK));
        predict(-1,-2,64,true); drain();
        assertTrue(map(-1,-2).blocks[0][0].state.is(Blocks.OAK_LEAVES));
        predict(-1,-2,70,true);
        bridge.offerPrepared(Level.OVERWORLD,tile(-1,-2,80,false)); drain();
        assertEquals(79,map(-1,-2).blocks[0][0].height);
        predict(-1,-2,60,true); drain();
        assertEquals(79,map(-1,-2).blocks[0][0].height,"Real VSS map data must never be replaced by prediction");
    }
    @Test void preexistingNativeMapAndItsInPlaceChangesAreProtected() throws Exception {
        bridge.offerPrepared(Level.OVERWORLD,tile(0,0,90,false)); drain();
        predict(0,0,30,true); drain(); assertEquals(89,map(0,0).blocks[0][0].height);
        predict(2,0,40,false); drain();
        map(2,0).blocks[7][8].height=100; // Xaero can mutate a reused MapTile object
        predict(2,0,60,true); drain();
        assertEquals(100,map(2,0).blocks[7][8].height);
    }
    @Test void persistedProvenanceAllowsRefinementAfterReentryButNeverCoarseDowngrade() throws Exception {
        predict(1,1,50,false); drain();
        bridge.onSessionEnd(); XaeroPredictionStore.awaitIoForTest();
        MapTile previous=map(1,1),reloaded=new MapTile(1,1);
        for(int x=0;x<16;x++) for(int z=0;z<16;z++) reloaded.blocks[x][z]=previous.blocks[x][z];
        reloaded.loaded=true; reloaded.writtenOnce=true;
        var region=processor.getLeafMapRegion(Integer.MAX_VALUE,0,0,true);
        synchronized(region.writerThreadPauseSync) {
            region.getChunk(0,0).setTile(1,1,reloaded,processor.shapeCache,processor);
        }
        bridge=createBridge(); XaeroMapCompat.facadeForTest(bridge);
        predict(1,1,60,true); drain();
        assertEquals(67,map(1,1).blocks[0][0].height);
        predict(1,1,30,false); drain();
        assertEquals(67,map(1,1).blocks[0][0].height);
    }
    @Test void predictionsNeverFillOrBackpressureTheRealQueueAndStaleWorkIsDiscarded() throws Exception {
        for(int x=0;x<300;x++) predict(x,0,40,false);
        assertEquals(0,bridge.queuedForTest()); assertFalse(bridge.shouldBackpressureInputNow());
        assertEquals(128,bridge.counterForTest("prediction_queued"));
        assertTrue(bridge.counterForTest("prediction_bytes")<=XaeroMapCompat.MAX_PREDICTION_BYTES);
        assertFalse(bridge.predictionHasRoomNow());
        bridge.clearQueue();
        bridge.offerPredicted(Level.OVERWORLD,tile(0,0,40,false),COARSE,() -> false);
        assertFalse(bridge.hasPendingWorkNow());
        predict(0,0,40,false); enabled.set(false); bridge.pump();
        assertFalse(bridge.hasPendingWorkNow());
    }
    @Test void loadedNativeNeighbourhoodOwnsTheMapAndDifferentDimensionCannotLeak() throws Exception {
        bridge.offerPrepared(Level.OVERWORLD,tile(0,0,80,false)); drain(); loaded.set(true);
        predict(0,0,30,true); drain(); assertEquals(79,map(0,0).blocks[0][0].height);
        bridge.offerPredicted(Level.NETHER,tile(1,0,40,false),COARSE,() -> true); drain();
        assertTrue(bridge.counterForTest("dropped_stale")>0);
    }
    @Test void backgroundProjectionIsLazyReplaysOnEnableAndRevokesOldSession() throws Exception {
        class Source implements XaeroPredictionBridge.Source {
            int reads;
            public long mapRevision() { return 1; }
            public List<XaeroPredictionBridge.Area> mapAreas() {
                return List.of(new XaeroPredictionBridge.Area("huge",0,0,1000000,1000000,1));
            }
            public XaeroPredictionBridge.Surface mapChunk(int x,int z) { reads++; return surface(50,false); }
        }
        var source=new Source(); XaeroPredictionBridge.attach(Level.OVERWORLD,source,-64,320);
        XaeroPredictionBridge.tick(); XaeroPredictionBridge.awaitProjectionForTest();
        assertTrue(source.reads>0 && source.reads<=8); drain();
        assertEquals(49,map(0,0).blocks[0][0].height);
        enabled.set(false); XaeroPredictionBridge.tick(); bridge.pump();
        enabled.set(true); XaeroPredictionBridge.attach(Level.OVERWORLD,source,-64,320);
        XaeroPredictionBridge.tick(); XaeroPredictionBridge.awaitProjectionForTest();
        assertTrue(bridge.hasPendingWorkNow());
        bridge.onSessionEnd(); drain(); assertEquals(0,bridge.queuedForTest());
    }
    @Test void waterUsesTheSameOverlaysAndBiomesAsTheExistingMapExtractor() {
        var pixels=new XaeroPredictionBridge.Pixel[256];
        Arrays.fill(pixels,new XaeroPredictionBridge.Pixel(List.of(
                new XaeroPredictionBridge.Run(-64,45,Blocks.SAND.defaultBlockState()),
                new XaeroPredictionBridge.Run(45,63,Blocks.WATER.defaultBlockState())),"minecraft:ocean"));
        var result=XaeroTileExtractor.extractPrediction(-1,-1,-64,320,
                new XaeroPredictionBridge.Surface(pixels,FINE,() -> true));
        assertEquals(44,result.floorY()[0]); assertEquals(62,result.topY()[0]);
        assertEquals("minecraft:ocean",result.biome()[0].location().toString());
        assertEquals(1,result.overlays()[0].length); assertTrue(result.overlays()[0][0].state().is(Blocks.WATER));
    }
    @Test void cursorCoversMisalignedNegativeBoundsExactlyAndKeepsTextureChunksTogether() {
        var cells=XaeroPredictionBridge.cursorForTest(new XaeroPredictionBridge.Area("edge",-7,-6,3,5,1),1000);
        assertEquals(110,cells.size()); assertEquals(110,new HashSet<>(cells).size());
        Set<Long> textureChunks=new HashSet<>(); long previous=Long.MIN_VALUE;
        for(long cell:cells) {
            int x=(int)(cell>>32),z=(int)cell;
            assertTrue(x>=-7 && x<3 && z>=-6 && z<5);
            long texture=((long)(x>>2)<<32)|((z>>2)&0xffffffffL);
            if(texture!=previous) assertTrue(textureChunks.add(texture),"A texture chunk must be completed in one contiguous group");
            previous=texture;
        }
    }
}
