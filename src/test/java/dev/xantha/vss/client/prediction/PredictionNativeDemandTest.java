package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;

class PredictionNativeDemandTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void nativeProjectionSurvivesLaterVanillaObservationAndUsesItsOwnResolution() {
        var observations = new PredictionViewObservation();
        var zoom = new Matrix4f().perspective((float)Math.toRadians(10), 2, .1f, 1000);
        var ordinary = new Matrix4f().perspective((float)Math.toRadians(70), 2, .1f, 1000);
        var actual = observations.observe(zoom, new Matrix4f(), Vec3.ZERO, 540, false, true, 100);
        assertEquals(VssLodProjection.selectionScale(zoom,540), actual.scale());
        assertNull(observations.observe(ordinary,new Matrix4f(),Vec3.ZERO,1080,false,false,101));
        assertSame(actual,observations.current());
        var spyglass = observations.observe(zoom,new Matrix4f(),Vec3.ZERO,540,true,true,102);
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,-10,0);
        var layout = VssLodLayout.of(8192,6,true,false);
        assertEquals(actual.view().projectedSize(key,layout,-64,320,0),
                spyglass.view().projectedSize(key,layout,-64,320,0));
        assertNotNull(observations.observe(ordinary,new Matrix4f(),Vec3.ZERO,1080,false,false,1_000_000_103L));
        observations.reset(); assertNull(observations.current());
    }

    @Test void zoomAndTurningDoNotRebuildBootstrapOrCreateSecondDetailPlan() {
        var normal = PredictionWorkView.of(0,80,0,0,0,-1,70,2);
        var zoom = PredictionWorkView.of(0,80,0,0,0,1,5,2);
        var input = PredictionPlanningPipelineTest.planInput();
        var first = nativeInput(input,normal,540); var second = nativeInput(input,zoom,8000);
        var exact = new PredictionExactCoverageIndex().planningView(Level.OVERWORLD,0);
        var calculation = new PredictionPlanPreparation.Calculation();
        var a = calculation.build(first,exact); var b = calculation.build(second,exact);
        assertEquals(a.plan(),b.plan()); assertEquals(a.terrain(),b.terrain());
        assertEquals(1,calculation.topologyBuilds()); assertEquals(1,calculation.topologyHits());
        assertFalse(a.leaves().isEmpty()); assertTrue(a.leaves().size()<300);
        assertTrue(a.transitions().isEmpty()); assertTrue(a.idleTargets().isEmpty());
        assertTrue(a.surfaces().isEmpty(),"Native detail requests own decoration");
        var current = new PredictionPlanningValidity.Current(second.layout(),zoom,null,0,80,0,
                second.settings(),second.revision(),second.removalRevision(),0,false,false);
        assertTrue(PredictionPlanningValidity.plan(a,current,key->false),"Turning cannot discard spatial cover");
    }

    @Test void bootstrapCoversSignedCoordinatesAndBothDimensionsAtEverySupportedHorizon() {
        for (var dimension:List.of(Level.OVERWORLD,Level.NETHER)) for(int distance:new int[]{1024,8192,65536}) {
            var layout=VssLodLayout.of(distance,6,true,false);
            double x=-12345.25,z=6789.5;
            var leaves=PredictionPlanPreparation.bootstrap(dimension,x,z,layout,0);
            assertTrue(leaves.size()<300);
            int span=layout.tileBlocks(leaves.get(0).lod());
            for(int angle=0;angle<360;angle+=10) for(double ratio:new double[]{0,.5,.999}) {
                double px=x+distance*ratio*Math.cos(Math.toRadians(angle));
                double pz=z+distance*ratio*Math.sin(Math.toRadians(angle));
                assertTrue(leaves.contains(new PredictionTileManager.PredictionTileKey(dimension,
                        (int)Math.floor(px/span),(int)Math.floor(pz/span),leaves.get(0).lod())));
            }
        }
    }

    private static PredictionPlanPreparation.Input nativeInput(PredictionPlanPreparation.Input i,PredictionWorkView view,double pixels) {
        return new PredictionPlanPreparation.Input(i.dimension(),i.layout(),view,null,i.x(),i.y(),i.z(),pixels,
                i.vanillaRadius(),i.surfaceExtension(),i.minY(),i.maxY(),true,i.initialAxes(),i.settings(),i.revision(),
                i.removalRevision(),i.residents(),i.relief(),i.edited(),i.mixedEdits(),i.ownershipCandidates(),
                i.previousOwned(),i.uploaded(),i.voxyRadius(),i.mediumBias(),i.visibility(),i.dirty(),i.viewBands(),
                i.viewDependencies(),i.failed(),i.deferred(),i.idle(),i.ordinaryBusy(),i.memoryReady(),
                i.idlePending(),i.now(),true);
    }
}
