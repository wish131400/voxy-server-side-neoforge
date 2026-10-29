package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionVoxySourceTest {
    @Test void deferredRemovalDoesNotBlockTheCallerOrDeleteReactivatedTiles() throws Exception {
        try(var source=new PredictionVoxySource()) {
            var tile=tile(0,0,0,32,1,8);source.put(tile);
            var current=new java.util.concurrent.atomic.AtomicBoolean(true);
            var cancelled=new java.util.concurrent.CountDownLatch(1);
            synchronized(source) {
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                        ()->source.removeLater(tile.key(),current::get,cancelled::countDown));
                current.set(false);
            }
            assertTrue(cancelled.await(5,java.util.concurrent.TimeUnit.SECONDS));
            long pos=VoxyPredictionBridge.position(0,0,0,0);
            assertNotNull(source.section(pos,MAPPER));
            assertTrue(source.mayHavePrediction(pos,MAPPER));
            assertFalse(source.mayHavePrediction(VoxyPredictionBridge.position(0,0,100,0),MAPPER));
            var removed=new java.util.concurrent.CountDownLatch(1);
            source.removeLater(tile.key(),()->true,removed::countDown);
            assertTrue(removed.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertNull(source.section(pos,MAPPER));assertFalse(source.mayHavePrediction(pos,MAPPER));
        }
    }
    @Test void detailLoadingControlDoesNotWaitForTheMeshWorkerSourceMonitor() {
        try(var source=new PredictionVoxySource()) {
            // The mesh worker may own this monitor while the render thread updates the switch.
            synchronized(source) {
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> source.detailLoading(false));
            }
        }
    }

    @Test void flatOceanVoxelsRetainNativeOccupancyThroughCoarseTilesAndScratchReuse() {
        for(int step:new int[]{1,2,4,8,16,32})try(var source=new PredictionVoxySource()) {
            var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,3);
            var runs=List.of(new PredictionVoxySource.Run(-64,40,Blocks.STONE.defaultBlockState()),
                    new PredictionVoxySource.Run(40,63,Blocks.WATER.defaultBlockState()));
            var columns=new PredictionVoxySource.Column[(512/step)*(512/step)];
            Arrays.fill(columns,new PredictionVoxySource.Column(runs,"minecraft:ocean"));
            source.put(new PredictionVoxySource.Tile(key,0,0,512,512/step,step,columns,List.of(),4096));
            for(int lod=1;lod<=4;lod++) {
                long pos=VoxyPredictionBridge.position(lod,0,0,0);
                var mapped=source.mappedSection(pos,MAPPER);
                int voxel=1<<lod;
                long[] expected=mapped.array().clone();
                int lastWater=Math.floorDiv(62,voxel);
                assertEquals(2L<<27,expected[lastWater<<10]&~31L);
                assertEquals(1L,expected[lastWater<<10]&15L,"Sea level 63 retains its one-block rounding remainder");
                assertEquals(0L,expected[(lastWater-1)<<10]&15L,"Only the top cell carries surface precision");
                long[] above=lastWater==31 ? source.section(VoxyPredictionBridge.position(lod,0,1,0),MAPPER) : expected;
                assertEquals(0,above[((lastWater+1)&31)<<10] & (0xfffffL<<27),
                        "The native voxel ceiling defines the mesh boundary");
                source.mappedSection(VoxyPredictionBridge.position(lod,1,-1,0),MAPPER); // Reuses pooled scratch.
                assertArrayEquals(expected,mapped.array(),"Scratch reuse must not mutate published voxels");
                source.releaseMapper(MAPPER);
                assertArrayEquals(expected,source.mappedSection(pos,MAPPER).array());
            }
        }
    }
    @Test void notificationReadsDoNotPromoteColdSectionsOverRepeatedMeshReads() {
        try(var source=new PredictionVoxySource(4)) {
            source.put(tile(0,0,10,8,8192,64));
            long hot=VoxyPredictionBridge.position(0,0,0,0);
            var expected=source.mappedSection(hot,MAPPER);assertSame(expected,source.mappedSection(hot,MAPPER));
            for(int x=1;x<40;x++) {
                long cold=VoxyPredictionBridge.position(0,x,0,0);
                source.mappedSection(cold,MAPPER,false);source.mappedSection(cold,MAPPER,false);
            }
            assertSame(expected,source.mappedSection(hot,MAPPER),"Notification hits must not displace the mesh working set");
        }
    }
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final VoxyPredictionBridge.Mapper MAPPER = (state, biome) ->
            state.is(Blocks.WATER) ? 2L << 27 : state.is(Blocks.DIRT) ? 3L << 27 : 1L << 27;
    private static PredictionVoxySource.Tile tile(int tileX, int tileZ, int lod, int axis, int step, int top) {
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, tileX, tileZ, lod);
        var col = new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(0, top,
                lod == 0 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState())), "minecraft:plains");
        var columns = new PredictionVoxySource.Column[axis * axis]; Arrays.fill(columns, col);
        return new PredictionVoxySource.Tile(key, tileX * axis * step, tileZ * axis * step,
                axis * step, axis, step, columns, List.of(), 256);
    }
    @Test void finerColumnsReplaceCoarseColumnsAndRemovingThemRestoresParent() {
        try (var source = new PredictionVoxySource()) {
            source.put(tile(0,0,1,32,2,8));
            source.put(tile(0,0,0,32,1,4));
            long pos = VoxyPredictionBridge.position(0,0,0,0);
            var data = source.section(pos, MAPPER);
            assertEquals(3L << 27, data[3 << 10]);
            assertEquals(0, data[5 << 10] & (0xfffffL << 27), "Known fine air must erase parent terrain");
            source.remove(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0));
            assertEquals(1L << 27, source.section(pos, MAPPER)[5 << 10]);
        }
    }
    @Test void subdivisionWaitsForPublishedSpacingAndDeduplicatesNegativeHorizontalRequests() {
        try (var source = new PredictionVoxySource()) {
            source.put(tile(-1,-1,3,8,64,64));
            long pos = VoxyPredictionBridge.position(4,-1,0,-1);
            assertFalse(source.requestDetail(pos,(x,z)->false));
            assertFalse(source.requestDetail(VoxyPredictionBridge.position(4,-1,7,-1),(x,z)->false));
            assertEquals(Set.of(new PredictionVoxySource.Detail(-1,-1,3)),source.detailRequests());
            // Native LOD 4 rendering a fine voxel array is not evidence of eight-block samples.
            assertNotNull(source.section(pos,MAPPER));
            assertFalse(source.requestDetail(pos,(x,z)->false));
            source.put(tile(-1,-1,3,32,16,64));
            assertFalse(source.requestDetail(pos,(x,z)->false));
            source.put(tile(-1,-1,3,64,8,64));
            assertTrue(source.requestDetail(pos,(x,z)->false));
            source.remove(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,-1,3));
            assertTrue(source.requestDetail(pos,(x,z)->false),"An absent overlay cannot block real Voxy");
        }
    }
    @Test void latePredictionOnAnAlreadySubdividedNodeStillRequestsSamplesAfterMovement() {
        try (var source = new PredictionVoxySource()) {
            long leaf = VoxyPredictionBridge.position(0,64,1,0);
            source.requestMeshDetail(leaf,(x,z)->false);
            assertTrue(source.detailRequests().isEmpty(),"Native-only nodes do not create prediction work");
            source.window(2064,64,8192);
            source.put(tile(4,0,3,8,64,64));
            assertNotNull(source.section(leaf,MAPPER),"A late coarse overlay can already be drawn by a bottom-level node");
            source.requestMeshDetail(leaf,(x,z)->false);
            source.requestMeshDetail(VoxyPredictionBridge.position(0,65,5,1),(x,z)->false);
            assertEquals(Set.of(new PredictionVoxySource.Detail(32,0,0)),source.detailRequests(),
                    "Bottom-level nodes never subdivide again; their mesh request must create horizontal demand");
            source.put(tile(32,0,0,64,1,16));
            assertEquals(0,((Number)source.diagnostics().get("detailPending")).intValue());
            assertEquals(1,((Number)source.diagnostics().get("detailResidents")).intValue());
            source.requestMeshDetail(leaf,(x,z)->false);
            assertEquals(0,((Number)source.diagnostics().get("detailPending")).intValue());
        }
    }
    @Test void meshDemandUsesNativeSpacingAndFloorDivisionAtEveryNativeLevel() {
        for(int lod=0;lod<=4;lod++)try(var source=new PredictionVoxySource()) {
            source.window(0,0,8192);
            source.put(tile(-1,-1,7,8,1024,64));
            long pos=VoxyPredictionBridge.position(lod,-3,-1,-1);
            source.requestMeshDetail(pos,(x,z)->false);
            assertEquals(Set.of(new PredictionVoxySource.Detail(-2,-1,lod)),source.detailRequests());
        }
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,0,3,8,64,64));
            source.requestMeshDetail(VoxyPredictionBridge.position(0,0,1,0),(x,z)->true);
            assertTrue(source.detailRequests().isEmpty(),"Authoritative real columns need no prediction sampling");
        }
    }
    @Test void fullTerrainPublicationReleasesPendingCapacityWithoutWaitingForANodeRetry() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,0,3,8,64,64));
            assertFalse(source.requestDetail(VoxyPredictionBridge.position(1,0,0,0),(x,z)->false));
            source.detailChanges();
            source.put(tile(0,0,0,32,2,32));
            assertEquals(1,((Number)source.diagnostics().get("detailPending")).intValue(),"Preview publication is still pending");
            source.put(tile(0,0,0,64,1,32));
            assertEquals(0,((Number)source.diagnostics().get("detailPending")).intValue(),
                    "Completed samples must leave the bounded pending queue immediately");
            assertEquals(1,((Number)source.diagnostics().get("detailResidents")).intValue());
            assertTrue(source.detailChanges().empty(),"Completion preserves desired membership and vegetation demand");
        }
    }
    @Test void deferredMeshOnlyDemandRetriesWhileStationaryWithoutAnotherMeshOrGpuRequest() {
        try(var source=new PredictionVoxySource()) {
            source.window(0,0,65536);
            source.put(tile(0,0,10,8,8192,64));
            for(int i=0;i<4200;i++)
                source.requestMeshDetail(VoxyPredictionBridge.position(0,(i%65)*2,1,(i/65)*2),(x,z)->false);
            assertEquals(4096,source.detailRequests().size());
            var all=new java.util.HashSet<PredictionVoxySource.Detail>();
            for(int i=0;i<4200;i++)all.add(new PredictionVoxySource.Detail(i%65,i/65,0));
            var deferred=new java.util.HashSet<>(all);deferred.removeAll(source.detailRequests());
            assertEquals(104,deferred.size());
            for(var key:source.detailRequests())source.put(tile(key.x(),key.z(),key.lod(),64,1,64));
            for(int tick=0;tick<140;tick++)source.detailChanges();
            assertTrue(source.detailRequests().containsAll(deferred),"Admission must recover after capacity frees without a GPU retry");
            assertEquals(104,((Number)source.diagnostics().get("detailPending")).intValue());
            source.window(-10000,-10000,1024);
            assertEquals(0,((Number)source.diagnostics().get("meshDetailPending")).intValue());
        }
    }
    @Test void allFourFineChildrenAreRequiredAndRealColumnsCanCompleteAHole() {
        try (var source = new PredictionVoxySource()) {
            source.put(tile(0,0,1,8,16,32));
            long pos = VoxyPredictionBridge.position(2,0,0,0);
            for(int z=0;z<2;z++)for(int x=0;x<2;x++) {
                if(x==1 && z==1)continue;
                source.put(tile(x,z,0,32,2,32));
            }
            assertFalse(source.requestDetail(pos,(x,z)->false));
            assertTrue(source.requestDetail(pos,(x,z)->x>=4 && z>=4));
            source.put(tile(1,1,0,32,2,32));
            assertTrue(source.requestDetail(pos,(x,z)->false));
        }
    }
    @Test void requestWindowAndLifecycleReleaseOldDemandWithoutSharingItWithAnotherWorld() {
        var source = new PredictionVoxySource();
        source.put(tile(0,0,1,8,16,32));
        assertFalse(source.requestDetail(VoxyPredictionBridge.position(2,0,0,0),(x,z)->false));
        source.window(10000,10000,1024);
        assertTrue(source.detailRequests().isEmpty());
        assertTrue(source.requestDetail(VoxyPredictionBridge.position(2,0,0,0),(x,z)->false));
        source.close();
        assertTrue(source.detailRequests().isEmpty());
        try(var another=new PredictionVoxySource()) {
            assertTrue(another.detailRequests().isEmpty());
            assertTrue(another.requestDetail(VoxyPredictionBridge.position(2,0,0,0),(x,z)->false));
        }
    }
    @Test void outerTwentyPercentAcceptsNativeRefinementAcrossTheFull4096ChunkHorizon() {
        try (var source = new PredictionVoxySource()) {
            source.window(0, 0, 65536);
            source.put(tile(0, 0, 10, 8, 8192, 64));
            for (double fraction : new double[]{.8, .85, .9, .95, .99, .999}) {
                int x = (int) (65536 * fraction) / 512;
                long pos = VoxyPredictionBridge.position(4, x, 0, 0);
                assertNotNull(source.section(pos, MAPPER), "full horizon has published terrain at " + fraction);
                assertFalse(source.requestDetail(pos, (cx, cz) -> false), "outer refinement is requested at " + fraction);
                assertTrue(source.detailRequests().contains(new PredictionVoxySource.Detail(x, 0, 3)));
            }
            assertTrue(source.requestDetail(VoxyPredictionBridge.position(4, 129, 0, 0), (x, z) -> false),
                    "requests beyond the configured horizon do not create work");
        }
    }

    @Test void repeatedRequestsStayBoundedEvenAcrossThousandsOfVisibleColumns() {
        try(var source=new PredictionVoxySource()) {
            source.window(32768,32768,65536);
            source.put(tile(0,0,10,8,8192,64));
            for(int i=0;i<4200;i++)
                assertFalse(source.requestDetail(VoxyPredictionBridge.position(4,i%65,0,i/65),(x,z)->false));
            assertEquals(4096,source.detailRequests().size());
        }
    }
    @Test void negativeSectionCoordinatesDoNotLeakAcrossTileEdges() {
        try (var source = new PredictionVoxySource()) {
            source.put(tile(-1,-1,0,32,1,4));
            assertNotNull(source.section(VoxyPredictionBridge.position(0,-1,0,-1), MAPPER));
            assertNull(source.section(VoxyPredictionBridge.position(0,0,0,-1), MAPPER));
        }
    }
    @Test void fineTerrainKeepsParentVegetationUntilItsOwnSurfaceIsComplete() {
        try (var source = new PredictionVoxySource()) {
            var parent = tile(0,0,1,32,2,4);
            var tree = new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.DIRT.defaultBlockState());
            source.put(new PredictionVoxySource.Tile(parent.key(),0,0,64,32,2,parent.columns(),List.of(tree),320));
            long pos = VoxyPredictionBridge.position(0,0,0,0);
            assertEquals(3L<<27,source.section(pos,MAPPER)[8<<10|8<<5|8]);
            source.put(tile(0,0,0,32,1,4));
            assertEquals(3L<<27,source.section(pos,MAPPER)[8<<10|8<<5|8],
                    "A terrain-only child has no evidence that its parent vegetation is empty");
        }
    }

    @Test void sameFootprintTerrainUpgradeKeepsPublishedVegetation() {
        try (var source = new PredictionVoxySource()) {
            var preview = tile(0,0,0,16,2,4);
            var tree = new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.DIRT.defaultBlockState());
            var decorated = new PredictionVoxySource.Tile(preview.key(),0,0,32,16,2,preview.columns(),List.of(tree),320);
            source.put(decorated);
            source.put(tile(0,0,0,32,1,4).retainFeatures(decorated));
            assertEquals(3L<<27,source.section(VoxyPredictionBridge.position(0,0,0,0),MAPPER)[8<<10|8<<5|8],
                    "Replacing the terrain grid cannot delete the only available tree representation");
        }
    }

    @Test void partialPlacementSnapshotsAccumulateWithoutLruEvictionDeletingPublishedPlants() {
        var ground=tile(0,0,0,32,1,4);
        var a=new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.DIRT.defaultBlockState());
        var b=new PredictionVoxySource.Box(12,4,12,13,10,13,Blocks.STONE.defaultBlockState());
        var first=new PredictionVoxySource.Tile(ground.key(),0,0,32,32,1,ground.columns(),List.of(a),320);
        var next=new PredictionVoxySource.Tile(ground.key(),0,0,32,32,1,ground.columns(),List.of(b),320);
        var retained=next.retainFeatures(first);
        assertEquals(Set.of(a,b),new HashSet<>(retained.features()));
        assertFalse(retained.surfaceComplete());
        for(int i=0;i<20;i++)retained=(i%2==0?first:next).retainFeatures(retained);
        assertEquals(2,retained.features().size(),"Repeated partial publications must not duplicate retained boxes");
        var empty=new PredictionVoxySource.Tile(ground.key(),0,0,32,32,1,ground.columns(),List.of(),256,true,0);
        assertSame(empty,empty.retainFeatures(retained),"Completed empty still replaces all earlier partial results");
    }

    @Test void partialPlacementHandoffUsesFreshStateAndCityWithoutReorderingUnchangedPlants() {
        var ground=tile(0,0,0,32,1,4);
        var oldCity=new PredictionVoxySource.Box(1,4,1,4,12,4,Blocks.STONE.defaultBlockState());
        var city=new PredictionVoxySource.Box(2,4,2,5,12,5,Blocks.STONE.defaultBlockState());
        var a=new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.DIRT.defaultBlockState());
        var b=new PredictionVoxySource.Box(12,4,12,13,10,13,Blocks.STONE.defaultBlockState());
        var old=new PredictionVoxySource.Tile(ground.key(),0,0,32,32,1,ground.columns(),List.of(oldCity,a,b),320,false,1);
        var fresh=new PredictionVoxySource.Tile(ground.key(),0,0,32,32,1,ground.columns(),List.of(city,b,a),320,false,1);
        var retained=fresh.retainFeatures(old);
        assertEquals(List.of(city,a,b),retained.features(),"Source-map iteration order must not churn published geometry");
        var changed=new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.STONE.defaultBlockState());
        var next=new PredictionVoxySource.Tile(ground.key(),0,0,32,32,1,ground.columns(),List.of(changed),320);
        assertEquals(List.of(changed,b),next.retainFeatures(retained).features());
    }

    @Test void completedChildReplacesPreviewAndCompletedEmptyDoesNotRegrowParentTrees() {
        try (var source = new PredictionVoxySource()) {
            var parent = tile(0,0,1,32,2,4);
            var proxy = new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.STONE.defaultBlockState());
            source.put(new PredictionVoxySource.Tile(parent.key(),0,0,64,32,2,parent.columns(),List.of(proxy),320));
            var fine = tile(0,0,0,32,1,4);
            var exact = new PredictionVoxySource.Box(12,4,12,13,10,13,Blocks.DIRT.defaultBlockState());
            long pos = VoxyPredictionBridge.position(0,0,0,0);
            var completed = new PredictionVoxySource.Tile(fine.key(),0,0,32,32,1,fine.columns(),List.of(exact),320,true,0);
            source.put(completed);
            long[] data = source.section(pos,MAPPER);
            assertEquals(0,data[8<<10|8<<5|8] & (0xfffffL<<27),"The preview must leave when exact plants arrive");
            assertEquals(3L<<27,data[8<<10|12<<5|12]);
            var empty = new PredictionVoxySource.Tile(fine.key(),0,0,32,32,1,fine.columns(),List.of(),256,true,0);
            assertSame(empty,empty.retainFeatures(completed),"Completed empty is evidence, never a missing preview");
            source.put(empty);
            data=source.section(pos,MAPPER);
            assertEquals(0,data[8<<10|8<<5|8] & (0xfffffL<<27));
            assertEquals(0,data[8<<10|12<<5|12] & (0xfffffL<<27));
        }
    }

    @Test void completedParentVegetationSurvivesChildPreviewAndBuildingsUseCurrentTerrainOwner() {
        try (var source = new PredictionVoxySource()) {
            var parent = tile(0,0,1,32,2,4);
            var tree = new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.DIRT.defaultBlockState());
            var building = new PredictionVoxySource.Box(20,4,20,24,12,24,Blocks.STONE.defaultBlockState());
            var decorated = new PredictionVoxySource.Tile(parent.key(),0,0,64,32,2,parent.columns(),List.of(building,tree),384,true,1);
            source.put(decorated);
            var fine = tile(0,0,0,32,1,4);
            var proxy = new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.STONE.defaultBlockState());
            source.put(new PredictionVoxySource.Tile(fine.key(),0,0,32,32,1,fine.columns(),List.of(proxy),320));
            long[] data=source.section(VoxyPredictionBridge.position(0,0,0,0),MAPPER);
            assertEquals(3L<<27,data[8<<10|8<<5|8],"Complete tree beats a temporary simplified child");
            assertEquals(0,data[8<<10|20<<5|20] & (0xfffffL<<27),"City geometry follows the current ground owner");
            var retained=fine.retainFeatures(decorated);
            assertSame(fine,retained,"Feature handoff requires the same tile footprint");
        }
    }

    @Test void sameFootprintHandoffRetainsOnlyVegetationAndKeepsNewCityGeometry() {
        var old=tile(-1,-1,0,16,2,4);
        var city=new PredictionVoxySource.Box(1,4,1,5,12,5,Blocks.STONE.defaultBlockState());
        var tree=new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.DIRT.defaultBlockState());
        var previous=new PredictionVoxySource.Tile(old.key(),-32,-32,32,16,2,old.columns(),List.of(city,tree),384,true,1);
        var fresh=tile(-1,-1,0,32,1,4);
        var newCity=new PredictionVoxySource.Box(2,4,2,6,12,6,Blocks.STONE.defaultBlockState());
        fresh=new PredictionVoxySource.Tile(fresh.key(),-32,-32,32,32,1,fresh.columns(),List.of(newCity),320,false,1);
        var retained=fresh.retainFeatures(previous);
        assertEquals(List.of(newCity,tree),retained.features());
        assertTrue(retained.surfaceComplete()); assertEquals(256L+retained.columns().length*4L+retained.boxes().bytes()+retained.featureIndex().bytes(),retained.bytes());
        assertTrue(PredictionVoxelData.forVoxy(retained).voxyTile.surfaceComplete());
    }
    @Test void coarsePredictionRemainsAvailableDuringPartialFineArrival() {
        try (var source = new PredictionVoxySource()) {
            source.put(tile(0,0,1,32,2,8));
            long pos = VoxyPredictionBridge.position(1,0,0,0);
            assertNotNull(source.section(pos,MAPPER));
            for (int z=0;z<2;z++) for(int x=0;x<2;x++) {
                source.put(tile(x,z,0,32,1,4));
                assertNotNull(source.section(pos,MAPPER));
            }
            assertEquals(3L<<27,source.section(pos,MAPPER)[0]);
            source.remove(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0));
            assertEquals(1L<<27,source.section(pos,MAPPER)[0]);
        }
    }
    @Test void surfaceSummaryKeepsTheExteriorClosedAndFluidsSurvive() {
        var runs = new ArrayList<PredictionVoxySource.Run>();
        var sample = new ClientColumnSample(64,68,0,PredictionMaterialPalette.grassBlockIndex(),0,0,0,0,
                1,ClientColumnSample.FLAG_SURFACE_ONLY,0,PredictionMaterialPalette.dirtIndex(),
                PredictionMaterialPalette.stoneIndex(),ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        PredictionVoxySource.terrain(runs,sample,4,-64);
        assertTrue(runs.stream().anyMatch(r -> r.bottom() == -64 && r.top() >= 60),
                "Unmeasured underground is the heightfield interior, not proven air under a floating shell");
        assertTrue(runs.stream().anyMatch(r -> r.state().is(Blocks.WATER) && r.bottom()==64 && r.top()==68));
    }
    @Test void explicitFloatingSpansAreNotExtendedToTheDimensionFloor() {
        var sample = new ClientColumnSample(100,100,0,PredictionMaterialPalette.grassBlockIndex(),0,0,0,0,
                0,ClientColumnSample.FLAG_SURFACE_ONLY,0,PredictionMaterialPalette.dirtIndex(),
                PredictionMaterialPalette.stoneIndex(),90,20,10,-64);
        var runs = new ArrayList<PredictionVoxySource.Run>();
        PredictionVoxySource.terrain(runs,sample,4,-64);
        assertTrue(runs.stream().noneMatch(r -> r.bottom()<10 || r.bottom()<90 && r.top()>20));
        assertTrue(runs.stream().anyMatch(r -> r.bottom()==90));
        assertTrue(runs.stream().anyMatch(r -> r.bottom()==10 && r.top()==20));
    }
    @Test void productionHeightfieldBuildHasNoInternalBottomFacesAtAnyVoxyLevel() {
        // Native surface/display records carry SURFACE_ONLY even when their height is accurate.
        int grid=10,step=32;
        var samples=new ClientColumnSample[grid*grid];
        for(int z=0;z<grid;z++) for(int x=0;x<grid;x++) {
            int top=80+x*4+z*4;
            samples[z*grid+x]=new ClientColumnSample(top,top,0,PredictionMaterialPalette.grassBlockIndex(),
                    0,0,0,0,0,ClientColumnSample.FLAG_SURFACE_ONLY|ClientColumnSample.FLAG_APPROXIMATE,
                    0,PredictionMaterialPalette.dirtIndex(),PredictionMaterialPalette.stoneIndex(),
                    ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        }
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.tryParse("minecraft:overworld"),42,-64,384,"noise","minecraft:overworld",123);
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,-1,2);
        try(var source=new PredictionVoxySource()) {
            var tile=PredictionVoxySource.build(key,samples,grid,step,new ClientTerrainSampler(42,profile),
                    PredictionVegetation.Tile.EMPTY,null);
            assertEquals(-256,tile.baseX()); assertEquals(256,tile.span());
            source.put(tile);
            for(int lod=0;lod<=4;lod++) {
                int size=32<<lod,voxel=1<<lod;
                int sx=Math.floorDiv(-128,size),sz=Math.floorDiv(-128,size);
                long[] lower=source.section(VoxyPredictionBridge.position(lod,sx,-1,sz),MAPPER);
                assertNotNull(lower);
                int xx=Math.floorMod(-128,size)/voxel,zz=Math.floorMod(-128,size)/voxel;
                assertNotEquals(0,lower[31<<10|zz<<5|xx]&(0xfffffL<<27),"Open heightfield underside at LOD "+lod);
                int top=samples[5*grid+5].surfaceY(); // x/z=-128 -> sample (5,5).
                int boundary=-Math.floorDiv(-top,voxel);
                int sy=Math.floorDiv(boundary,32),yy=Math.floorMod(boundary,32);
                long[] upper=source.section(VoxyPredictionBridge.position(lod,sx,sy,sz),MAPPER);
                assertEquals(0,upper[yy<<10|zz<<5|xx]&(0xfffffL<<27),"Height must stay within the native voxel rounding");
                long[] cap=source.section(VoxyPredictionBridge.position(lod,sx,Math.floorDiv(boundary-1,32),sz),MAPPER);
                assertNotEquals(0,cap[Math.floorMod(boundary-1,32)<<10|zz<<5|xx]&(0xfffffL<<27));
            }
        }
    }
    @Test void volumeKeepsCaveAirAndSeparatedSpans() {
        var sample = PredictionColumnVolume.ofRuns(new int[]{0,8,PredictionMaterialPalette.stoneIndex(),0,
                20,24,PredictionMaterialPalette.dirtIndex(),0}).asSample();
        var runs = new ArrayList<PredictionVoxySource.Run>();
        PredictionVoxySource.terrain(runs,sample,1,-64);
        assertEquals(2,runs.size());
        assertTrue(runs.stream().noneMatch(r -> r.bottom() < 20 && r.top() > 8));
    }
    @Test void changingMapperAndClosingWorldInvalidateAllMappedSnapshots() {
        var source = new PredictionVoxySource(); source.put(tile(0,0,0,32,1,4));
        long pos = VoxyPredictionBridge.position(0,0,0,0);
        assertEquals(3L<<27,source.section(pos,MAPPER)[0]);
        VoxyPredictionBridge.Mapper other = (s,b) -> 9L<<27;
        assertEquals(9L<<27,source.section(pos,other)[0]);
        source.close();
        assertNull(source.section(pos,MAPPER));
        source.put(tile(0,0,0,32,1,4));
        assertNull(source.section(pos,MAPPER),"Late old-world completion cannot republish");
    }
    @Test void featuresRetainTheirFootprintAndCoarseLodsKeepNarrowPeaks() {
        try(var source=new PredictionVoxySource()) {
            var base=tile(0,0,0,32,1,4);
            var columns=base.columns().clone();
            columns[0]=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(0,20,Blocks.DIRT.defaultBlockState())),"minecraft:plains");
            source.put(new PredictionVoxySource.Tile(base.key(),0,0,32,32,1,columns,
                    List.of(new PredictionVoxySource.Box(8,4,8,9,12,9,Blocks.DIRT.defaultBlockState())),256));
            long[] fine=source.section(VoxyPredictionBridge.position(0,0,0,0),MAPPER);
            assertEquals(3L<<27,fine[8<<10|8<<5|8]);
            assertEquals(0,fine[8<<10|8<<5|9] & (0xfffffL<<27));
            // The coarse voxel center misses (0,0), but reducing its complete footprint keeps the peak.
            assertEquals(3L<<27,source.section(VoxyPredictionBridge.position(2,0,0,0),MAPPER)[4<<10]);
        }
    }
    @Test void mappedSnapshotsAreLocallyInvalidatedAndReleaseTheirBudgetOnClose() {
        var budget=new PredictionMemoryBudget(64L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
        var source=new PredictionVoxySource(); source.budget(budget);
        source.put(tile(0,0,0,32,1,4)); source.put(tile(2,0,0,32,1,4));
        long first=VoxyPredictionBridge.position(0,0,0,0), distant=VoxyPredictionBridge.position(0,2,0,0);
        long[] one=source.section(first,MAPPER),two=source.section(distant,MAPPER);
        assertEquals(source.diagnostics().get("mappedBytes"),budget.usedBytes());
        assertTrue(budget.usedBytes()<4096,"Flat native sections retain compact voxel runs");
        source.put(tile(0,0,0,32,1,8));
        assertSame(two,source.section(distant,MAPPER)); assertNotSame(one,source.section(first,MAPPER));
        assertEquals(source.diagnostics().get("mappedBytes"),budget.usedBytes()); source.close(); assertEquals(0,budget.usedBytes());
    }
    @Test void rendererMappingReleasePreservesVoxelsAndCannotDiscardANewerRenderersCache() {
        var budget=new PredictionMemoryBudget(64L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
        var source=new PredictionVoxySource();source.budget(budget);
        var open=new java.util.concurrent.atomic.AtomicBoolean(true);
        VoxyPredictionBridge.Mapper old=new VoxyPredictionBridge.Mapper() {
            public boolean isOpen(){return open.get();}
            public long map(net.minecraft.world.level.block.state.BlockState s,String b){return 1L<<27;}
        };
        try(source) {
            source.put(tile(0,0,0,32,1,4));long pos=VoxyPredictionBridge.position(0,0,0,0);
            assertEquals(1L<<27,source.section(pos,old)[0]);assertTrue(budget.usedBytes()>0);
            open.set(false);source.releaseMapper(old);assertEquals(0,budget.usedBytes());
            assertNull(source.section(pos,old),"A stopped renderer cannot retain itself again through a late read");
            var fresh=source.section(pos,MAPPER);assertNotNull(fresh);assertEquals(3L<<27,fresh[0]);
            long bytes=budget.usedBytes();assertTrue(bytes>0);
            source.releaseMapper(old);
            assertSame(fresh,source.section(pos,MAPPER));assertEquals(bytes,budget.usedBytes());
        }
        assertEquals(0,budget.usedBytes());
    }
    @Test void rendererCloseDuringMappingCannotRetainOldWorldOrOverwriteReplacementMappings() throws Exception {
        var budget=new PredictionMemoryBudget(64L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
        try(var source=new PredictionVoxySource()) {
            source.budget(budget);source.put(tile(0,0,0,32,1,4));
            var open=new java.util.concurrent.atomic.AtomicBoolean(true);
            var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
            VoxyPredictionBridge.Mapper old=new VoxyPredictionBridge.Mapper() {
                public boolean isOpen(){return open.get();}
                public long map(net.minecraft.world.level.block.state.BlockState state,String biome) {
                    entered.countDown();
                    try{assertTrue(resume.await(5,java.util.concurrent.TimeUnit.SECONDS));}
                    catch(InterruptedException e){throw new AssertionError(e);}
                    return 1L<<27;
                }
            };
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                long pos=VoxyPredictionBridge.position(0,0,0,0);
                var pending=worker.submit(()->source.section(pos,old));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                open.set(false);source.releaseMapper(old);
                var fresh=source.section(pos,MAPPER);long bytes=budget.usedBytes();assertTrue(bytes>0);
                resume.countDown();assertEquals(1L<<27,pending.get(5,java.util.concurrent.TimeUnit.SECONDS)[0]);
                assertSame(fresh,source.section(pos,MAPPER));assertEquals(bytes,budget.usedBytes());
                assertNull(source.section(pos,old));
            } finally {resume.countDown();worker.shutdownNow();}
        }
        assertEquals(0,budget.usedBytes());
    }
    @Test void aRepeatedSixHundredSectionTraversalRetainsCompactResultsBeyondTheOldCountLimit() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,0,9,1,32768,4));
            for(int pass=0;pass<2;pass++)for(int i=0;i<600;i++)
                assertNotNull(source.mappedSection(VoxyPredictionBridge.position(0,i%32,0,i/32),MAPPER));
            assertEquals(600L,source.diagnostics().get("sectionBuilds"));
            assertEquals(600L,source.diagnostics().get("cacheHits"));
            assertEquals(600,source.diagnostics().get("cachedSections"));
            assertTrue((long)source.diagnostics().get("mappedBytes")<1024*1024);
            System.out.println("600-section replay: "+source.diagnostics());
        }
    }
    @Test void parallelReadsShareOneBuildAndAnUnrelatedPublicationCannotRevokeIt() throws Exception {
        try (var source=new PredictionVoxySource()) {
            source.put(tile(0,0,0,32,1,4));
            var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            VoxyPredictionBridge.Mapper mapper=(state,biome)->{
                if(calls.incrementAndGet()==1) {
                    entered.countDown();
                    try { assertTrue(resume.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
                    catch(InterruptedException e){throw new AssertionError(e);}
                }
                return MAPPER.map(state,biome);
            };
            var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                long pos=VoxyPredictionBridge.position(0,0,0,0);
                var first=workers.submit(()->source.section(pos,mapper));
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var second=workers.submit(()->source.section(pos,mapper));
                source.put(tile(4,0,0,32,1,8)); // Unrelated tile is published while this build is in progress.
                resume.countDown();
                long[] data=first.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertSame(data,second.get(5,java.util.concurrent.TimeUnit.SECONDS));
                assertSame(data,source.section(pos,mapper));
                assertEquals(1,calls.get(),"Identical state/biome pairs share mapping across columns");
                assertEquals(1L,source.diagnostics().get("sectionBuilds"));
            } finally {resume.countDown();workers.shutdownNow();}
        }
    }
    @Test void relatedPublicationRevokesAnInFlightBuildWithoutPublishingOldVoxelsToTheCache() throws Exception {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,0,0,32,1,4));
            var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            VoxyPredictionBridge.Mapper mapper=(state,biome)->{
                if(once.compareAndSet(false,true)) {
                    entered.countDown();try {assertTrue(resume.await(5,java.util.concurrent.TimeUnit.SECONDS));}
                    catch(InterruptedException e){throw new AssertionError(e);}
                }return MAPPER.map(state,biome);
            };
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                long pos=VoxyPredictionBridge.position(0,0,0,0);
                var old=worker.submit(()->source.section(pos,mapper));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                source.put(tile(0,0,0,32,1,8));resume.countDown();
                assertEquals(0,old.get(5,java.util.concurrent.TimeUnit.SECONDS)[6<<10]&(0xfffffL<<27));
                assertEquals(3L<<27,source.section(pos,mapper)[6<<10]);
            } finally {resume.countDown();worker.shutdownNow();}
        }
    }
    @Test void completedVegetationInvalidatesOnlyItsHeightAndRemovingAFineTileRestoresTallerParent() {
        try(var source=new PredictionVoxySource()) {
            var coarse=tile(0,0,1,32,2,80);source.put(coarse);
            var fine=tile(0,0,0,32,1,4);source.put(fine);
            long low=VoxyPredictionBridge.position(0,0,0,0),high=VoxyPredictionBridge.position(0,0,2,0);
            var ground=source.section(low,MAPPER);var knownAir=source.section(high,MAPPER);
            assertEquals(0,knownAir[0]&(0xfffffL<<27));
            var box=new PredictionVoxySource.Box(0,40,0,1,45,1,Blocks.OAK_LEAVES.defaultBlockState());
            source.put(new PredictionVoxySource.Tile(fine.key(),fine.baseX(),fine.baseZ(),fine.span(),fine.axis(),fine.step(),
                    fine.columns(),List.of(box),fine.bytes()+64,true,0));
            assertSame(ground,source.section(low,MAPPER),"A plant-only update above this section must retain its ground cache");
            source.remove(fine.key());
            assertEquals(1L<<27,source.section(high,MAPPER)[0],"Fine known air must not remain cached after revealing a taller parent");
        }
    }
    @Test void sectionsOutsideAllVerticalOccupancyUseSharedEmptyDataWithoutMappingOrRetention() {
        try(var source=new PredictionVoxySource()) {
            source.put(tile(0,0,0,32,1,4));
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            VoxyPredictionBridge.Mapper mapper=(state,biome)->{calls.incrementAndGet();return MAPPER.map(state,biome);};
            var above=source.section(VoxyPredictionBridge.position(0,0,100,0),mapper);
            assertSame(above,source.section(VoxyPredictionBridge.position(0,0,-100,0),mapper));
            assertEquals(0,calls.get());assertEquals(0L,source.diagnostics().get("sectionBuilds"));
            assertTrue(Arrays.stream(above).allMatch(v->v==(15L<<56)));
        }
    }

    @Test void oneShotTraversalCannotEvictHotSectionsAndLocalInvalidationStillRevokesThem() {
        var budget=new PredictionMemoryBudget(64L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
        try(var source=new PredictionVoxySource(8)) {
            source.budget(budget);source.put(tile(0,0,9,1,32768,4));
            long hot=VoxyPredictionBridge.position(0,0,0,0);
            var original=source.mappedSection(hot,MAPPER);
            assertSame(original,source.mappedSection(hot,MAPPER));
            for(int x=1;x<=32;x++)assertNotNull(source.mappedSection(VoxyPredictionBridge.position(0,x,0,0),MAPPER));
            assertSame(original,source.mappedSection(hot,MAPPER),"A full cold scan must not evict a reused section");
            assertEquals(33L,source.diagnostics().get("sectionBuilds"));
            assertEquals(8,source.diagnostics().get("cachedSections"));
            assertEquals(source.diagnostics().get("mappedBytes"),budget.usedBytes());
            source.put(tile(0,0,0,32,1,8));
            assertNotSame(original,source.mappedSection(hot,MAPPER));
            assertEquals(3L<<27,source.section(hot,MAPPER)[6<<10]);
        }
        assertEquals(0,budget.usedBytes());
    }
    @Test void repeatedMaterialMappingKeepsBiomeIdentityAndFeatureStatesSeparate() {
        try(var source=new PredictionVoxySource()) {
            var base=tile(0,0,0,32,1,4);var cols=base.columns().clone();
            for(int x=0;x<32;x++)cols[x]=new PredictionVoxySource.Column(cols[x].runs(),"minecraft:desert");
            var feature=new PredictionVoxySource.Box(0,4,0,1,7,1,Blocks.OAK_LOG.defaultBlockState());
            source.put(new PredictionVoxySource.Tile(base.key(),0,0,32,32,1,cols,List.of(feature),512,true,0));
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var data=source.section(VoxyPredictionBridge.position(0,0,0,0),(state,biome)->{
                calls.incrementAndGet();return (state.is(Blocks.OAK_LOG)?3L:biome.equals("minecraft:desert")?2L:1L)<<27;
            });
            assertEquals(2L<<27,data[0]);assertEquals(1L<<27,data[1<<5]);assertEquals(3L<<27,data[4<<10]);
            assertEquals(3,calls.get(),"Mapping is once per distinct state and biome within one build");
        }
    }

}
