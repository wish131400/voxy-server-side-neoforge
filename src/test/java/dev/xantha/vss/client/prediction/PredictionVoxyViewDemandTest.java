package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;

class PredictionVoxyViewDemandTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static PredictionVoxySource.DetailInput input(double x,double y,double z,double fov,double yaw,int radius) {
        var projection=new Matrix4f().perspective((float)Math.toRadians(fov),1.7f,.1f,1024);
        var model=new Matrix4f().rotateY((float)Math.toRadians(yaw));
        if(y>320)model.rotateX((float)Math.atan2(y-64,radius-1));
        var observed=new PredictionViewObservation().observe(projection,model,new Vec3(x,y,z),1080,
                fov<20,true,System.nanoTime());
        return new PredictionVoxySource.DetailInput(x,z,VssLodLayout.of(radius,6,true,false),observed.view(),
                Level.OVERWORLD,null,-64,320);
    }
    @Test void zoomCreatesFinerTargetsWithoutAnyNativeRequestOrResidentTile() {
        var normal=input(32,80,0,70,0,4096);var zoom=input(32,80,0,5,0,4096);
        var regular=PredictionVoxyViewDemand.build(normal);var magnified=PredictionVoxyViewDemand.build(zoom);
        assertTrue(regular.stream().anyMatch(k->k.lod()>0));
        var target=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,-50,0);
        assertFalse(regular.contains(target));assertTrue(magnified.contains(target));
        try(var source=new PredictionVoxySource()) {
            var changes=source.refreshDetails(zoom);
            assertFalse(changes.empty());
            assertTrue(source.detailRequests().contains(new PredictionVoxySource.Detail(0,-50,0)),
                    "An empty native section must not be a prerequisite for observing the target");
            assertEquals(1L,source.diagnostics().get("viewDemandBuilds"));
            source.refreshDetails(zoom);assertEquals(1L,source.diagnostics().get("viewDemandBuilds"));
        }
    }
    @Test void edgeTargetsCoverTheWholeConfiguredHorizonAtAltitudeAndSignedCoordinates() {
        for(double x:new double[]{32,-3761})for(int radius:new int[]{1024,4096,65536}) {
            var input=input(x,1998,-2460,70,0,radius);
            var keys=PredictionVoxyViewDemand.build(input);
            assertFalse(keys.isEmpty());
            double z=input.z()-radius+1;
            assertTrue(keys.stream().anyMatch(k->{int span=input.layout().tileBlocks(k.lod());
                return x>=k.tileX()*(double)span && x<(k.tileX()+1D)*span
                        && z>=k.tileZ()*(double)span && z<(k.tileZ()+1D)*span;}));
            assertEquals(keys.size(),new HashSet<>(keys).size());
            assertTrue(keys.stream().allMatch(k->k.lod()<=4 && PredictionWorkOrder.distanceSquared(k,input.layout(),input.x(),input.z())
                    <=(double)radius*radius));
        }
    }
    @Test void disabledLoadingDoesNotConsumeObservationAndReenableCreatesDemand() {
        var input=input(-32,80,0,5,0,4096);
        try(var source=new PredictionVoxySource()) {
            source.detailLoading(false);assertTrue(source.refreshDetails(input).empty());
            assertTrue(source.detailRequests().isEmpty());
            source.detailLoading(true);assertFalse(source.refreshDetails(input).empty());
            assertFalse(source.detailRequests().isEmpty());
        }
    }
    @Test void smallTurnInsideZoomRefreshesDemandWhileOrdinaryHeadBobDoesNot() {
        var initial=input(32,80,0,5,0,4096);var turned=input(32,80,0,5,.5,4096);
        assertTrue(turned.view().changedFrom(initial.view()));
        assertFalse(input(32,80,0,70,.5,4096).view().changedFrom(input(32,80,0,70,0,4096).view()));
        try(var source=new PredictionVoxySource()) {
            source.refreshDetails(initial);var before=source.detailRequests();source.refreshDetails(turned);
            assertEquals(2L,source.diagnostics().get("viewDemandBuilds"));
            assertTrue(source.detailRequests().containsAll(before));
        }
    }
}
