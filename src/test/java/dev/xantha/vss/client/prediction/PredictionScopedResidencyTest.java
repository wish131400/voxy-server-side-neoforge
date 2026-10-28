package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionScopedResidencyTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }


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





    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pruneReady","prepared"})
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
            if (reclaimMethod.equals("prepared")) PredictionDemandTestSupport.reclaim(manager, 0, 0);
            else {
                var reclaim=PredictionTileManager.class.getDeclaredMethod(reclaimMethod,int.class,int.class); reclaim.setAccessible(true);
                reclaim.invoke(manager,0,0);
            }
            var prune=PredictionTileManager.class.getDeclaredMethod("pruneReady",int.class,int.class); prune.setAccessible(true);
            assertSame(detail,ready.get(detail.key()),"extra telescope work must not reclaim the detail it is replacing");
            assertFalse(ready.containsKey(other.key()),"unrelated covered detail stays reclaimable under pressure");
            focus.set(manager,null);
            prune.invoke(manager,0,0);
            assertFalse(ready.containsKey(detail.key()),"the focus guard cannot pin detail after release");
        }
    }
}
