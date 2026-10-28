package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import dev.xantha.vss.compat.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionVoxySource.*;

class PredictionNewWorldLoadingTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static final VoxyPredictionBridge.Mapper MAPPER=PredictionVoxyPreparationTest.MAPPER;
    private static Tile parent(boolean complete) {
        var columns=new Column[4096];
        Arrays.fill(columns,new Column(List.of(new Run(-64,48,Blocks.STONE.defaultBlockState())),"minecraft:plains"));
        return new Tile(new PredictionTileKey(Level.OVERWORLD,0,0,1),0,0,128,64,2,columns,
                List.of(new Box(0,48,0,128,80,128,Blocks.OAK_LEAVES.defaultBlockState())),4096,complete,0);
    }
    private static Tile child(int x,int z,boolean complete) {
        var columns=new Column[4096];
        Arrays.fill(columns,new Column(List.of(new Run(-64,48,Blocks.DIRT.defaultBlockState())),"minecraft:plains"));
        return new Tile(new PredictionTileKey(Level.OVERWORLD,x,z,0),x*64,z*64,64,64,1,columns,List.of(),4096,complete,0);
    }
    @Test void compactPagesKeepTheHorizontalWorkingSetAcrossVerticalSectionEviction() {
        try(var source=new PredictionVoxySource(2,8L*1024*1024)) {
            var tile=PredictionVoxyPreparationTest.fixture(0,0,256,829);source.put(tile);
            for(int z=0;z<2;z++)for(int x=0;x<2;x++) {
                long pos=VoxyPredictionBridge.position(2,x,0,z);
                assertArrayEquals(PredictionVoxyPreparationTest.reference(tile,pos),source.section(pos,MAPPER));
            }
            long builds=(long)source.diagnostics().get("columnBuilds");
            long bytes=(long)source.diagnostics().get("columnCacheBytes");
            assertEquals(4096,builds);assertEquals(64,source.diagnostics().get("columnCachePages"));
            assertTrue(bytes<=1024L*1024);
            assertTrue(builds*480L>1024L*1024,"The previous per-column bookkeeping alone exceeds this partition");
            for(int z=0;z<2;z++)for(int x=0;x<2;x++) {
                long pos=VoxyPredictionBridge.position(2,x,-1,z);
                assertArrayEquals(PredictionVoxyPreparationTest.reference(tile,pos),source.section(pos,MAPPER));
            }
            assertEquals(builds,source.diagnostics().get("columnBuilds"));
            assertEquals(0L,source.diagnostics().get("columnEvictions"));
            assertTrue((long)source.diagnostics().get("cacheEvictions")>0);
            System.out.printf(Locale.ROOT,"COLUMN_PAGE_WORKING_SET columns=%d pages=%s bytes=%d previousMinimumBytes=%d%n",
                    builds,source.diagnostics().get("columnCachePages"),bytes,builds*480L);
        }
    }
    @Test void unalignedAndPartialFootprintsPreserveRasterizationAndEveryFace() {
        for(int base:new int[]{-61,-64,0})for(int axis:new int[]{21,64}) {
            var sampled=PredictionVoxyPreparationTest.fixture(base,base,axis,991);
            var tile=new Tile(new PredictionTileKey(Level.OVERWORLD,base<0?-1:0,base<0?-1:0,0),
                    base,base,64,axis,axis==21?3:1,sampled.columns(),List.of(),4096,true,0);
            for(int lod=1;lod<=4;lod++)for(int sy:new int[]{-1,0})try(var source=new PredictionVoxySource()) {
                source.put(tile);long pos=VoxyPredictionBridge.position(lod,base<0?-1:0,sy,base<0?-1:0);
                var expected=PredictionVoxyPreparationTest.reference(tile,pos);
                for(int face=0;face<6;face++) {
                    var actual=source.face(pos,MAPPER,face);
                    for(int i=0;i<1024;i++)assertEquals(expected[VoxyFaceData.index(face,i)],actual[i],
                            "base="+base+" axis="+axis+" lod="+lod+" face="+face+" i="+i);
                }
                assertArrayEquals(expected,source.section(pos,MAPPER));
            }
        }
    }
    @Test void coveredForestsDoNotQueryDecodeOrInvalidateFineSections() {
        try(var source=new PredictionVoxySource()) {
            var parent=parent(true);source.put(parent);
            for(int z=0;z<2;z++)for(int x=0;x<2;x++)source.put(child(x,z,true));
            long pos=VoxyPredictionBridge.position(1,0,0,0);
            var first=source.mappedSection(pos,MAPPER);
            assertEquals(0L,source.diagnostics().get("featureQueries"));
            assertTrue((long)source.diagnostics().get("skippedFeatureTiles")>0);
            var changed=new Tile(parent.key(),0,0,128,64,2,parent.columns(),
                    List.of(new Box(0,48,0,128,96,128,Blocks.OAK_LOG.defaultBlockState())),4096,true,0);
            source.put(changed);
            assertSame(first,source.mappedSection(pos,MAPPER),"A completely covered parent cannot change fine output");
            source.remove(child(0,0,true).key());
            var revealed=source.mappedSection(pos,MAPPER);
            assertNotSame(first,revealed);
            assertTrue((long)source.diagnostics().get("featureQueries")>0,"Removing a child must reveal parent plants");
            assertNotEquals(first.array()[25*1024],revealed.array()[25*1024]);
        }
    }
    @Test void localVegetationEditsKeepDistantSectionsAndStillUpdateTheirOwnBorder() {
        try(var source=new PredictionVoxySource()) {
            var key=new PredictionTileKey(Level.OVERWORLD,0,0,3);
            var columns=new Column[4096];Arrays.fill(columns,new Column(List.of(new Run(-64,48,Blocks.STONE.defaultBlockState())),"minecraft:plains"));
            var original=new Tile(key,0,0,512,64,8,columns,List.of(new Box(2,48,2,3,60,3,Blocks.OAK_LOG.defaultBlockState())),4096,true,0);
            source.put(original);
            long near=VoxyPredictionBridge.position(0,0,1,0),far=VoxyPredictionBridge.position(0,12,1,12);
            var firstNear=source.mappedSection(near,MAPPER);var firstFar=source.mappedSection(far,MAPPER);
            source.put(new Tile(key,0,0,512,64,8,columns,List.of(new Box(2,48,2,3,64,3,Blocks.OAK_LEAVES.defaultBlockState())),4096,true,0));
            assertNotSame(firstNear,source.mappedSection(near,MAPPER));
            assertSame(firstFar,source.mappedSection(far,MAPPER),"One changed tree must not invalidate the entire 512-block tile");
            source.remove(key);assertNull(source.mappedSection(far,MAPPER),"Deleting coverage still clears the entire ownership footprint");
        }
    }
    @Test void completedEmptyVegetationSuppressesParentsButIncompleteChildrenRetainThem() {
        try(var source=new PredictionVoxySource()) {
            source.put(parent(true));source.put(child(0,0,false));
            long pos=VoxyPredictionBridge.position(0,0,1,0);
            var inherited=source.mappedSection(pos,MAPPER);
            assertNotEquals(15L<<56,inherited.array()[20*1024]);
            source.put(child(0,0,true));
            var empty=source.mappedSection(pos,MAPPER);
            assertNotSame(inherited,empty);assertEquals(15L<<56,empty.array()[20*1024]);
            source.remove(child(0,0,true).key());
            assertEquals(inherited.array()[20*1024],source.section(pos,MAPPER)[20*1024]);
        }
    }
    @Test void publicationMasksPreservePartialCoverageNeighborsAndCompletedPlantPriority() {
        var parent=parent(true);var peers=new ArrayList<Tile>();
        for(int z=0;z<2;z++)for(int x=0;x<2;x++)peers.add(child(x,z,true));
        long covered=VoxyPredictionBridge.position(1,0,0,0);
        assertFalse(new PredictionVoxyChange(parent,parent,peers,true).test(covered));
        peers.removeIf(t->t.baseX()==64 && t.baseZ()==0);
        assertTrue(new PredictionVoxyChange(parent,parent,peers,true).test(covered),
                "The fine section's adjacent native voxel still samples this parent");
        peers.clear();peers.add(child(0,0,false));
        assertTrue(new PredictionVoxyChange(parent,parent,peers,false).test(VoxyPredictionBridge.position(0,0,0,0)),
                "Complete parent plants outrank an incomplete fine terrain tile");
    }
    @Test void publicationMasksMatchIndependentOwnerSelectionAcrossSignedCoordinatesAndNativeLevels() {
        var random=new Random(172918);
        for(int round=0;round<120;round++) {
            int base=(round%2==0?-128:0),step=1<<random.nextInt(3);
            var model=parent(random.nextBoolean());
            var target=new Tile(model.key(),base,base,64*step,64,step,model.columns(),model.features(),4096,
                    model.surfaceComplete(),0);
            var peers=new ArrayList<Tile>();
            for(int z=0;z<4;z++)for(int x=0;x<4;x++) {
                int peerStep=1<<random.nextInt(3),span=64*peerStep;
                var original=child(x,z,random.nextBoolean());
                peers.add(new Tile(new PredictionTileKey(Level.OVERWORLD,x+10,z+10,random.nextInt(3)),
                        base+x*32,base+z*32,span,64,peerStep,original.columns(),List.of(),4096,original.surfaceComplete(),0));
            }
            for(int lod=0;lod<=4;lod++) {
                long pos=VoxyPredictionBridge.position(lod,round%2==0?-1:0,0,round%2==0?-1:0);
                int nativeStep=1<<lod,span=32*nativeStep;
                long bx=(long)VoxyPredictionBridge.x(pos)*span-nativeStep,bz=(long)VoxyPredictionBridge.z(pos)*span-nativeStep;
                var terrain=new ArrayList<>(peers);terrain.add(target);
                terrain.sort(Comparator.comparingInt(Tile::step).thenComparingInt(t->t.key().lod()));
                var plants=new ArrayList<>(terrain);plants.removeIf(t->!t.surfaceComplete()&&!t.hasVegetation());
                plants.sort(Comparator.comparing(t->!t.surfaceComplete()));
                boolean expected=false;
                for(int z=0;z<34&&!expected;z++)for(int x=0;x<34&&!expected;x++) {
                    long wx=bx+x*nativeStep+nativeStep/2,wz=bz+z*nativeStep+nativeStep/2;
                    expected=owner(terrain,wx,wz)==target || owner(plants,wx,wz)==target;
                }
                // Ties are deliberately conservative: either same-priority tile
                // can own a cell, so the mask may notify more, never fewer.
                if(expected)assertTrue(new PredictionVoxyChange(null,target,peers,true).test(pos),
                        "round="+round+" lod="+lod);
            }
        }
    }
    private static Tile owner(List<Tile> ordered,long x,long z) {
        for(var tile:ordered)if(x>=tile.baseX()&&z>=tile.baseZ()&&x<(long)tile.baseX()+tile.span()
                &&z<(long)tile.baseZ()+tile.span())return tile;
        return null;
    }
    @Test void layoutReductionRetiresOldPlansBeforeTakingNewLoadingSnapshots() throws Exception {
        int distance=dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks;
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        try {
            dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks=8192;
            try(var manager=new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,profile,(x,z)->64),
                    new PredictionMemoryBudget(16L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
                var resident=PredictionCoverageWorkTest.tile(0,0,0,manager.layout());
                @SuppressWarnings("unchecked") var ready=(Map<PredictionTileKey,PredictionTileManager.PredictionTile>)PredictionLoadingTestSupport.get(manager,"ready");
                ready.put(resident.key(),resident);
                var obsolete=new PredictionTileKey(Level.OVERWORLD,0,0,7);
                PredictionLoadingTestSupport.set(manager,"scheduledPlan",List.of(obsolete));
                PredictionLoadingTestSupport.set(manager,"scheduledLeaves",List.of(obsolete));
                dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks=1024;
                var tick=PredictionTileManager.class.getDeclaredMethod("tickWork",double.class,double.class,double.class,
                        double.class,VssLodFocus.class,int.class);tick.setAccessible(true);
                tick.invoke(manager,0D,80D,0D,20D,new VssLodFocus(0,0,128,20),0);
                assertTrue(((List<?>)PredictionLoadingTestSupport.get(manager,"scheduledPlan")).isEmpty());
                assertSame(resident,ready.get(resident.key()));
                var request=PredictionTileManager.class.getDeclaredMethod("schedulingInput",long.class);request.setAccessible(true);
                var schedule=(PredictionSchedulePreparation.Input)request.invoke(manager,System.nanoTime());
                var loading=new PredictionLoadingPreparation.Input(schedule,List.of(),Set.of(),Set.of(),Map.of(),Map.of(),768,
                        PredictionVisibilityFeedback.Snapshot.EMPTY,0,0);
                assertDoesNotThrow(()->PredictionLoadingPreparation.build(loading));
                assertTrue(schedule.layout().levelCount()<8);
            }
        } finally {dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks=distance;}
    }
}
