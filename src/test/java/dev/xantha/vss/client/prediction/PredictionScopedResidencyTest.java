package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionScopedResidencyTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void scopeReleaseSelectsCachedMediumAndNextLookReusesTheSameFineMesh() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var layout=VssLodLayout.of(8192,6,true,false);
        var ordinaryFine=PredictionCoverageWorkTest.tile(64,0,0,layout);
        var fine=new PredictionTile(ordinaryFine.key(),ordinaryFine.heights(),ordinaryFine.groundHeights(),
                ordinaryFine.samples(),ordinaryFine.mesh(),ordinaryFine.depthBound(),0,1,
                ordinaryFine.cellAxis(),ordinaryFine.spacingBlocks(),true);
        var medium=PredictionCoverageWorkTest.tile(8,0,3,layout);
        var root=PredictionCoverageWorkTest.tile(0,0,7,layout);
        var snapshot=new RenderSnapshot(Level.OVERWORLD,layout,
                Map.of(fine.key(),fine,medium.key(),medium,root.key(),root),Map.of());
        var method=PredictionRenderer.class.getDeclaredMethod("resolveCoverage",PredictionTile.class,
                int.class,int.class,double.class,RenderSnapshot.class,VssLodFocus.class);
        method.setAccessible(true);
        var focus=new VssLodFocus(4128,32,1024,8);
        for(var look:new VssLodFocus[]{focus,null,focus,null}) {
            var fineMask=(PredictionRenderer.TileCoverage)method.invoke(null,fine,0,0,448D,snapshot,look);
            var mediumMask=(PredictionRenderer.TileCoverage)method.invoke(null,medium,0,0,448D,snapshot,look);
            assertEquals(look!=null,fineMask.allowed()[0]);
            assertEquals(look==null,mediumMask.allowed()[0],"one owner, no blank frame or overlap");
            assertSame(fine,snapshot.tiles().get(fine.key()),"view changes must not discard cached generation");
        }
    }

    @Test void delayedAncestorsNeverDowngradeOrdinaryRefinement() {
        var layout=VssLodLayout.of(8192,6,true,false);
        var medium=PredictionCoverageWorkTest.tile(0,0,2,layout);
        var coarse=PredictionCoverageWorkTest.tile(0,0,4,layout);
        var intermediate=PredictionCoverageWorkTest.tile(0,0,3,layout);
        var fine=PredictionCoverageWorkTest.tile(0,0,1,layout);
        for(var tiles:java.util.List.of(Map.of(medium.key(),medium),
                Map.of(medium.key(),medium,coarse.key(),coarse),
                Map.of(medium.key(),medium,coarse.key(),coarse,intermediate.key(),intermediate))) {
            var snapshot=new RenderSnapshot(Level.OVERWORLD,layout,tiles,Map.of());
            assertSame(medium,snapshot.coveringTileAtDetail(0,0,3));
        }
        var snapshot=new RenderSnapshot(Level.OVERWORLD,layout,
                Map.of(fine.key(),fine,medium.key(),medium,coarse.key(),coarse),Map.of());
        assertSame(fine,snapshot.coveringTileAtDetail(0,0,3));
    }

    @Test void missingMediumKeepsCoverageAndNegativeCoordinatesStayIndependent() {
        var layout=VssLodLayout.of(8192,6,true,false);
        var fine=PredictionCoverageWorkTest.tile(-1,-1,0,layout);
        var root=PredictionCoverageWorkTest.tile(-1,-1,7,layout);
        var snapshot=new RenderSnapshot(Level.OVERWORLD,layout,Map.of(fine.key(),fine,root.key(),root),Map.of());
        assertSame(fine,snapshot.coveringTileAtDetail(-1,-1,3),"do not fall back to a giant root while medium loads");
        assertSame(root,snapshot.coveringTileAtDetail(-20,-20,3));
        assertNull(snapshot.coveringTileAtDetail(1,1,3));
        assertSame(fine,snapshot.coveringTileAtDetail(-1,-1,0));
    }

    @Test void activeTerrainFocusKeepsItsFinestUploadedMeshOutsideTheDecorationPatch() throws Exception {
        var layout=VssLodLayout.of(8192,6,true,false);
        var base=PredictionCoverageWorkTest.tile(16,-1,2,layout);
        var fine=new PredictionTile(base.key(),base.heights(),base.groundHeights(),base.samples(),base.mesh(),
                base.depthBound(),0,1,base.cellAxis(),base.spacingBlocks(),true);
        var parent=PredictionCoverageWorkTest.tile(8,-1,3,layout);
        var focus=new VssLodFocus(3400,-128,1024,512);
        assertTrue(PredictionWorkOrder.scoped(fine.key(),layout,focus));
        assertFalse(PredictionWorkOrder.surfaceEligible(fine.key(),layout,0,0,512,focus));
        var method=PredictionRenderer.class.getDeclaredMethod("resolveCoverage",PredictionTile.class,
                int.class,int.class,double.class,RenderSnapshot.class,VssLodFocus.class);
        method.setAccessible(true);
        for (var tiles:java.util.List.of(Map.of(fine.key(),fine),Map.of(fine.key(),fine,parent.key(),parent))) {
            var snapshot=new RenderSnapshot(Level.OVERWORLD,layout,tiles,Map.of());
            var mask=(PredictionRenderer.TileCoverage)method.invoke(null,fine,0,0,448D,snapshot,focus);
            assertEquals(mask.considered(),mask.rendered(),"a delayed ordinary parent cannot coarsen the active terrain focus");
            if (tiles.size()>1) {
                var fallback=(PredictionRenderer.TileCoverage)method.invoke(null,parent,0,0,448D,snapshot,focus);
                assertFalse(fallback.allowed()[32*64],"only one uploaded mesh owns the focused chunk");
                var released=(PredictionRenderer.TileCoverage)method.invoke(null,fine,0,0,448D,snapshot,null);
                assertEquals(0,released.rendered(),"telescope-only detail may relax after the telescope closes");
            }
        }
    }

    @Test void finishedCacheUpgradePreservesOrdinaryCoverageWithoutKeepingASecondMesh() throws Exception {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        var sampler=ClientTerrainSampler.custom(42,profile,(x,z)->64);
        try (var manager=new PredictionTileManager(Level.OVERWORLD,sampler,
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
            var layout=manager.layout();
            var key=new PredictionTileKey(Level.OVERWORLD,-9,-1,3);
            var ordinary=new PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],null,
                    new PredictionDepthBound(64,64),0,1,16,32);
            var mesh=PredictionMeshCodecTest.fixture(32);
            var samples=new ClientColumnSample[34*34];
            java.util.Arrays.fill(samples,sampler.sample(0,0));
            var method=PredictionTileManager.class.getDeclaredMethod("restoreFinishedMesh",PredictionTileKey.class,
                    VssLodLayout.class,int.class,ClientColumnSample[].class,PredictionMesh.class,boolean.class,PredictionTile.class,
                    dev.xantha.vss.common.worldgen.LostCityPreview.Tile.class);
            method.setAccessible(true);
            var upgraded=(PredictionTile)method.invoke(manager,key,layout,16,samples,mesh,true,ordinary,null);
            assertFalse(upgraded.scopeOnly(),"an ordinary resident cannot become telescope-only during refinement");
            var parent=PredictionCoverageWorkTest.tile(-5,-1,4,layout);
            var snapshot=new RenderSnapshot(Level.OVERWORLD,layout,Map.of(upgraded.key(),upgraded,parent.key(),parent),Map.of());
            assertSame(upgraded,snapshot.coveringTileAtDetail(-288,-1,5));
            var fresh=(PredictionTile)method.invoke(manager,key,layout,16,samples,mesh,true,null,null);
            assertTrue(fresh.scopeOnly(),"new telescope-only work must still be distinguishable from ordinary work");
            var continued=(PredictionTile)method.invoke(manager,key,layout,16,samples,mesh,true,fresh,null);
            assertTrue(continued.scopeOnly());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pruneReady","makeRoomForSurface"})
    @SuppressWarnings("unchecked")
    void memoryReclamationProtectsTheCurrentFocusAndReleasesItWhenTheFocusEnds(String reclaimMethod) throws Exception {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        var sampler=ClientTerrainSampler.custom(42,profile,(x,z)->64);
        try (var manager=new PredictionTileManager(Level.OVERWORLD,sampler,
                new PredictionMemoryBudget(PredictionMemoryBudget.BUILD_BYTES-1,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
            var layout=manager.layout();
            var detail=PredictionCoverageWorkTest.tile(16,0,2,layout);
            var parent=PredictionCoverageWorkTest.tile(8,0,3,layout);
            var other=PredictionCoverageWorkTest.tile(0,-16,2,layout);
            var otherParent=PredictionCoverageWorkTest.tile(0,-8,3,layout);
            var field=PredictionTileManager.class.getDeclaredField("ready"); field.setAccessible(true);
            var ready=(Map<PredictionTileKey,PredictionTile>)field.get(manager);
            for (var tile:java.util.List.of(detail,parent,other,otherParent)) ready.put(tile.key(),tile);
            var focus=PredictionTileManager.class.getDeclaredField("buildFocus"); focus.setAccessible(true);
            focus.set(manager,new VssLodFocus(4128,32,1024,512));
            var target=new PredictionTileKey(Level.OVERWORLD,32,0,1);
            var desired=PredictionTileManager.class.getDeclaredField("desiredKeys"); desired.setAccessible(true);
            ((java.util.Set<PredictionTileKey>)desired.get(manager)).add(target);
            var mediumPending=PredictionTileManager.class.getDeclaredField("mediumCoveragePending"); mediumPending.setAccessible(true);
            mediumPending.setBoolean(manager,true);
            var reclaim=PredictionTileManager.class.getDeclaredMethod(reclaimMethod,int.class,int.class); reclaim.setAccessible(true);
            reclaim.invoke(manager,0,0);
            var prune=PredictionTileManager.class.getDeclaredMethod("pruneReady",int.class,int.class); prune.setAccessible(true);
            assertSame(detail,ready.get(detail.key()),"extra telescope work must not reclaim the detail it is replacing");
            assertFalse(ready.containsKey(other.key()),"unrelated covered detail stays reclaimable under pressure");
            focus.set(manager,null);
            prune.invoke(manager,0,0);
            assertFalse(ready.containsKey(detail.key()),"the focus guard cannot pin detail after release");
        }
    }
}
