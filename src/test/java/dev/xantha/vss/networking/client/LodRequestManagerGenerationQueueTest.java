package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodRequestManagerGenerationQueueTest {
    @Test
    void queuedColumnExtendsMatchingRequestWithoutUsingAGenerationSlot() {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> { });
        LodRequestManager manager = new LodRequestManager("test", tracker);
        long now = System.nanoTime();
        int requestId = tracker.track(42L, false, false, 30_000_000_000L, now);
        manager.onColumnQueued(requestId);
        assertTrue(tracker.drainTimedOut(now + 31_000_000_000L).isEmpty());
        assertFalse(tracker.isGenerationRequest(requestId));
        assertEquals(0, tracker.generationSize());
        manager.onColumnQueued(requestId + 1);
        assertEquals(1, tracker.drainTimedOut(now + 121_000_000_000L).size());
    }


    @Test
    void generationAcknowledgementTransitionsOnlyMatchingRequest() {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {
        });
        int requestId = tracker.track(42L, false, false, 1_000L, 0L);

        assertFalse(tracker.isGenerationRequest(requestId));
        assertFalse(LodRequestManager.transitionToGenerationWaiting(
                tracker, requestId + 1, 10_000L, 1L));
        assertFalse(tracker.isGenerationRequest(requestId));

        assertTrue(LodRequestManager.transitionToGenerationWaiting(
                tracker, requestId, 10_000L, 1L));
        assertTrue(tracker.isGenerationRequest(requestId));
        assertEquals(1, tracker.generationSize());

        assertTrue(LodRequestManager.transitionToGenerationWaiting(
                tracker, requestId, 10_000L, 2L));
        assertEquals(1, tracker.generationSize());
    }
}
