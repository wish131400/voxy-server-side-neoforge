package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionDetailBandsTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void fineDistanceIsIndependentOfTheHorizonAndOuterTerrainReachesMedium() {
        for (int horizon : new int[]{1024, 8192, 10000, 65536}) {
            int fine=Math.min(1536,horizon);
            assertEquals(64, PredictionDetailBands.cellAxis(fine - .001, horizon));
            assertEquals(32, PredictionDetailBands.cellAxis(fine, horizon));
            assertEquals(32, PredictionDetailBands.cellAxis(horizon, horizon));
        }
    }

    @Test void altitudeAndProjectionStopInvisibleFineWorkButScopeOverridesBoth() {
        var layout=VssLodLayout.of(8192,2,true,true);
        var tile=new PredictionTileKey(Level.OVERWORLD,1,0,3);
        assertEquals(64,PredictionDetailBands.cellAxis(tile,layout,0,64,0,null,1300,-64,320));
        assertEquals(32,PredictionDetailBands.cellAxis(tile,layout,0,5020,0,null,1300,-64,320));
        assertEquals(16,PredictionDetailBands.cellAxis(tile,layout,0,40000,0,null,1300,-64,320));
        var focus=new VssLodFocus(768,256,1024,10000);
        assertEquals(64,PredictionDetailBands.cellAxis(tile,layout,0,40000,0,focus,1300,-64,320));
    }

    @Test void highAltitudeViewHasABoundedMediumBudgetInsteadOfTheOldFiveKilometreFineBand() {
        var layout=VssLodLayout.of(8192,2,true,true);
        double x=-709,y=5020,z=-718;
        var leaves=PredictionLodPlanner.plan(Level.OVERWORLD,x,y,z,layout,null,1300);
        long previous=0,current=0;
        for(var key:leaves) {
            int span=layout.tileBlocks(key.lod());
            double distance=Math.hypot((key.tileX()+.5)*span-x,(key.tileZ()+.5)*span-z);
            int oldAxis=distance<8192*.6 ? 64 : distance<8192*.9 ? 32 : 8;
            int axis=PredictionDetailBands.cellAxis(key,layout,x,y,z,null,1300,-64,320);
            assertTrue(axis>=16 && axis<=32,"high-altitude terrain must advance beyond pure coarse coverage");
            previous+=(long)(oldAxis+2)*(oldAxis+2);
            current+=(long)(axis+2)*(axis+2);
        }
        System.out.println("HIGH_ALTITUDE_SAME_LEAVES tiles="+leaves.size()+",oldTargetPoints="+previous+",newTargetPoints="+current);
        assertTrue(current<previous*.65,"screen-aware medium targets must materially reduce stationary work");
    }

    @Test void normalPlanKeepsMediumHorizonCoverageWithoutExpandingVegetation() {
        for (int horizon : new int[]{8192, 10000, 65536}) {
            var layout = VssLodLayout.of(horizon,6,true,true);
            double x = 282, z = -85;
            var leaves = PredictionLodPlanner.plan(Level.OVERWORLD,x,170,z,layout,null,700);
            assertTrue(leaves.size() <= 1024 + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
            for (int angle = 0; angle < 360; angle += 30) {
                for (double fraction : new double[]{.5,.78,.99}) {
                    double px = x + horizon * fraction * Math.cos(Math.toRadians(angle));
                    double pz = z + horizon * fraction * Math.sin(Math.toRadians(angle));
                    var owners = leaves.stream().filter(key -> contains(key,layout,px,pz)).toList();
                    assertEquals(1,owners.size(), "Every point needs exactly one planned owner");
                    var owner = owners.getFirst();
                    assertEquals(PredictionDetailBands.cellAxis(horizon*fraction,horizon),
                            PredictionDetailBands.cellAxis(owner,layout,x,z,null),
                            "horizon="+horizon+", fraction="+fraction+", angle="+angle+", tile="+owner);
                    assertFalse(PredictionWorkOrder.surfaceEligible(owner,layout,x,z,768,null));
                }
            }
        }
    }

    @Test void telescopeOverridesTheOuterBandAndOrdinaryLocalDetail() {
        var layout = VssLodLayout.of(65536,6,true,true);
        var target = new PredictionTileKey(Level.OVERWORLD,1000,0,0);
        var near = new PredictionTileKey(Level.OVERWORLD,1,0,0);
        var focus = new VssLodFocus(64032,32,1024,9000);
        assertEquals(32,PredictionDetailBands.cellAxis(target,layout,0,0,null));
        assertEquals(64,PredictionDetailBands.cellAxis(target,layout,0,0,focus));
        assertTrue(PredictionWorkOrder.surfaceEligible(target,layout,0,0,768,focus));
        assertFalse(PredictionWorkOrder.surfaceEligible(target,layout,0,0,768,null));
        assertTrue(PredictionWorkOrder.priority(near,layout,0,32,false,focus)
                > PredictionWorkOrder.priority(target,layout,64000D*64000,0,false,focus));
    }

    @Test void radialSkeletonDoesNotConsumeTheNearbySubdivisionAllowance() {
        var layout = VssLodLayout.of(65536,2,true,true);
        var leaves = PredictionLodPlanner.plan(Level.OVERWORLD,282,151,-85,layout,null,700);
        assertTrue(leaves.stream().filter(key -> key.lod() == 0).count() >= 128,
                "Adding distant rings must not shrink the existing nearby block-detail coverage");
    }

    @Test void completedOuterPreviewDoesNotRequestUnnecessaryParentUpgrades() {
        var layout = VssLodLayout.of(8192,6,true,true);
        var middle = new PredictionTileKey(Level.OVERWORLD,0,0,1);
        var parent = new PredictionTileKey(Level.OVERWORLD,0,0,2);
        assertTrue(PredictionTransitionPlan.exposedTargets(Set.of(middle,parent),Set.of(middle),
                Map.of(middle,8,parent,8),layout.levelCount(),Map.of(middle,8)).isEmpty(),
                "A finished outer preview must not keep requesting parent upgrades");
    }

    @Test void highAltitudeKeepsTheHorizontalHorizonAndScopedDetailEligible() {
        var layout=VssLodLayout.of(8192,6,true,true);
        double x=-605,z=-776;
        for(double y:new double[]{4998,10000}) {
            var leaves=PredictionLodPlanner.plan(Level.OVERWORLD,x,y,z,layout,null,1298);
            for(int angle=0;angle<360;angle+=15) {
                double px=x+8192*.98*Math.cos(Math.toRadians(angle));
                double pz=z+8192*.98*Math.sin(Math.toRadians(angle));
                assertTrue(leaves.stream().anyMatch(k->contains(k,layout,px,pz)),
                        "Altitude must not remove ground inside the configured horizon: y="+y+",angle="+angle);
            }
        }
        var focus=new VssLodFocus(x+7800,z,1024,10000);
        var scoped=PredictionLodPlanner.plan(Level.OVERWORLD,x,4998,z,layout,focus,1298);
        assertTrue(scoped.stream().anyMatch(k->k.lod()==0 && contains(k,layout,focus.x(),focus.z())),
                "A telescope target inside the horizontal horizon must remain eligible for block detail");
    }

    private static boolean contains(PredictionTileKey key, VssLodLayout layout, double x, double z) {
        int span = layout.tileBlocks(key.lod());
        return x >= key.tileX()*(double)span && x < (key.tileX()+1D)*span
                && z >= key.tileZ()*(double)span && z < (key.tileZ()+1D)*span;
    }
}
