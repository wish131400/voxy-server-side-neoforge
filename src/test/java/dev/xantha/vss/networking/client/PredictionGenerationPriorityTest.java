package dev.xantha.vss.networking.client;

import dev.xantha.vss.client.prediction.PredictionLoadingProgress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionGenerationPriorityTest {
    @Test void realGenerationContinuesWhileAnyPredictionStageIsIncomplete() {
        var gate=new PredictionGenerationPriority();
        var states=new PredictionLoadingProgress[] {
            PredictionLoadingProgress.INITIALIZING,
            new PredictionLoadingProgress(100,10,100,0),
            new PredictionLoadingProgress(100,100,100,100,100,0,100,0),
            new PredictionLoadingProgress(100,100,100,100,100,100,100,100,true,
                new PredictionLoadingProgress.ViewProgress(100,100,100,100,0,50))
        };
        for(var state:states)for(long now:new long[]{0,1_000_000_000L,45_000_000_000L,120_000_000_000L}) {
            assertEquals(4,gate.limit(8,true,state,8,now));
            assertEquals(1,gate.limit(1,true,state,1,now));
        }
    }
    @Test void readinessAndTogglesApplyImmediatelyWithoutReacquiringAnExclusiveTurn() {
        var gate=new PredictionGenerationPriority();
        assertEquals(8,gate.limit(8,true,PredictionLoadingProgress.EMPTY,0));
        assertEquals(4,gate.limit(8,true,PredictionLoadingProgress.INITIALIZING,1));
        assertEquals(8,gate.limit(8,false,PredictionLoadingProgress.INITIALIZING,2));
        assertEquals(4,gate.limit(8,true,PredictionLoadingProgress.INITIALIZING,3));
        gate.reset();assertEquals(4,gate.limit(8,true,PredictionLoadingProgress.INITIALIZING,4));
    }
    @Test void cpuAndConfiguredLimitsStillBoundEveryAdmission() {
        var gate=new PredictionGenerationPriority();
        for(boolean enabled:new boolean[]{true,false}) {
            assertEquals(0,gate.limit(8,enabled,PredictionLoadingProgress.EMPTY,0,0));
            assertEquals(0,gate.limit(0,enabled,PredictionLoadingProgress.EMPTY,8,0));
            assertEquals(0,gate.limit(-1,enabled,PredictionLoadingProgress.EMPTY,8,0));
            assertEquals(2,gate.limit(8,enabled,PredictionLoadingProgress.EMPTY,2,0));
            assertEquals(8,gate.limit(8,enabled,PredictionLoadingProgress.EMPTY,100,0));
        }
    }
    @Test void realGenerationDoesNotConsumeCachedTransfersOrDirtyRefreshSlots() {
        int limit=new PredictionGenerationPriority().limit(8,true,PredictionLoadingProgress.INITIALIZING,0);
        var window=new RequestWindow(8,4,2,1,limit,3,2);
        assertTrue(window.canSend(false,true,false,1));
        assertTrue(window.canSend(true,false,false,1));
        assertTrue(window.canSend(false,false,false,1));
        assertTrue(window.canSend(false,false,true,1));
    }
}
