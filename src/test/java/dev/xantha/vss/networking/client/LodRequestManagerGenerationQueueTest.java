package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodRequestManagerGenerationQueueTest {

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
