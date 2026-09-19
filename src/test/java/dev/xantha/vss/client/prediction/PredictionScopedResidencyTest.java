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
}
