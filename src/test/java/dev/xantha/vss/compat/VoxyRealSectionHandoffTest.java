package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.*;

class VoxyRealSectionHandoffTest {
    private static final long PREDICTED=1L<<27, AIR=15L<<56, REAL=2L<<27;
    private final Object engine=new Object();
    public static final class Voxels {
        public int x,y,z;
        Voxels(int x,int y,int z) { this.x=x;this.y=y;this.z=z; }
    }
    @BeforeEach void reset() { VoxyIngestTracker.resetWorld(); }

    @Test void localRealAirReplacesPredictionAtEveryLodWithoutClaimingOtherHeightsOrNeighbors() {
        for(int lod=0;lod<=4;lod++) {
            VoxyRealCoverage.reset();
            int cx=-(2<<lod),cy=-(2<<lod),cz=-(2<<lod),group=16>>lod;
            commit(engine,new Voxels(cx,cy,cz));
            for(int kind=0;kind<3;kind++) {
                long[] prediction=new long[32768];Arrays.fill(prediction,PREDICTED);
                long[] data=new long[32768];Arrays.fill(data,AIR);
                VoxyRenderAccess.Voxels real=kind==0?null:kind==1?new VoxyRenderAccess.Voxels(null,AIR):new VoxyRenderAccess.Voxels(data,0);
                VoxyPredictionBridge.mergeRealVoxels(prediction,real,lod,-1,-1,-1,VoxyRealCoverage.of(engine));
                assertEquals(AIR,prediction[0],"real air LOD "+lod+" storage "+kind);
                assertEquals(PREDICTED,prediction[group],"unknown adjacent column");
                assertEquals(PREDICTED,prediction[group<<10],"unknown adjacent height");
            }
        }
    }

    @Test void allSixFacesFollowExactlyTheSameOwnershipAsTheCenterAtNegativeCoordinates() {
        for(int lod=0;lod<=4;lod++) {
            VoxyRealCoverage.reset();
            int step=1<<lod,bx=-3*(32<<lod),by=-2*(32<<lod),bz=-(32<<lod);
            VoxyRealCoverage coverage=VoxyRealCoverage.of(engine);
            for(int face=0;face<6;face++)for(int i=0;i<1024;i++)if(i%7==0) {
                int at=VoxyFaceData.index(face,i);
                coverage.mark(Math.floorDiv(bx+(at&31)*step,16),Math.floorDiv(by+(at>>10)*step,16),Math.floorDiv(bz+(at>>5&31)*step,16));
            }
            long[] center=new long[32768];Arrays.fill(center,PREDICTED);
            VoxyPredictionBridge.mergeRealVoxels(center,null,lod,-3,-2,-1,coverage);
            for(int face=0;face<6;face++) {
                long[] border=new long[1024];Arrays.fill(border,PREDICTED);
                VoxyPredictionBridge.mergeRealFace(border,null,face,lod,-3,-2,-1,coverage,AIR);
                for(int i=0;i<1024;i++)assertEquals(center[VoxyFaceData.index(face,i)],border[i],"LOD "+lod+" face "+face+" sample "+i);
            }
        }
    }

    @Test void failedDroppedAndNormalReturnWithoutWriteNeverClaimAir() throws Throwable {
        Voxels v=new Voxels(0,0,0);
        Object failed=new Object(),dropped=new Object(),empty=new Object();
        VoxyIngestTracker.runRawIngest(new Object(),0,0,()->true);
        VoxyIngestTracker.published(dropped);VoxyIngestTracker.discarded(dropped);
        Object worker=VoxyIngestTracker.enterWorker();VoxyIngestTracker.workerTask(failed);
        VoxyIngestTracker.workerFailed(worker,new RuntimeException("before write"));
        worker=VoxyIngestTracker.enterWorker();VoxyIngestTracker.published(empty);VoxyIngestTracker.workerTask(empty);
        VoxyIngestTracker.finishWorker(worker);
        VoxyIngestTracker.committed(engine,v); // no worker scope
        assertFalse(VoxyRealCoverage.of(engine).containsSection(0,0,0));
    }

    @Test void oldWorldCallbackCannotClaimNewWorldEvenWhenCoordinatesAndEngineAreReused() {
        Object task=new Object();VoxyIngestTracker.published(task);
        Object token=VoxyIngestTracker.enterWorker();VoxyIngestTracker.workerTask(task);
        VoxyIngestTracker.resetWorld();
        VoxyIngestTracker.committed(engine,new Voxels(-1,2,-3));
        VoxyIngestTracker.finishWorker(token);
        assertFalse(VoxyRealCoverage.of(engine).containsSection(-1,2,-3));
        commit(engine,new Voxels(-1,2,-3));
        assertTrue(VoxyRealCoverage.of(engine).containsSection(-1,2,-3));
        assertFalse(VoxyRealCoverage.of(new Object()).containsSection(-1,2,-3));
        var old=VoxyRealCoverage.of(engine);VoxyRealCoverage.reset();
        assertFalse(old.containsSection(-1,2,-3));
    }

    @Test void rendererResetDoesNotRetireActualNativeWorkOrPreviouslyCommittedCoverage() {
        Object task=new Object();VoxyIngestTracker.published(task);
        Object token=VoxyIngestTracker.enterWorker();VoxyIngestTracker.workerTask(task);
        VoxyIngestTracker.reset();
        VoxyIngestTracker.committed(engine,new Voxels(4,5,6));
        VoxyIngestTracker.finishWorker(token);
        assertTrue(VoxyRealCoverage.of(engine).containsSection(4,5,6));
    }

    @Test void actualBlocksStillWinOutsideCoverageAndRepeatedWritesKeepOwnership() {
        commit(engine,new Voxels(0,0,0));commit(engine,new Voxels(0,0,0));
        long[] prediction=new long[32768],real=new long[32768];Arrays.fill(prediction,PREDICTED);
        real[31<<10|31<<5|31]=REAL;
        VoxyPredictionBridge.mergeReal(prediction,real,4,0,0,0,VoxyRealCoverage.of(engine));
        assertEquals(0,prediction[0]);assertEquals(REAL,prediction[32767]);assertEquals(PREDICTED,prediction[1]);
    }

    static void commit(Object engine,Voxels v) {
        Object task=new Object();VoxyIngestTracker.published(task);
        Object worker=VoxyIngestTracker.enterWorker();VoxyIngestTracker.workerTask(task);
        try { VoxyIngestTracker.committed(engine,v); }
        finally { VoxyIngestTracker.finishWorker(worker); }
    }
}
