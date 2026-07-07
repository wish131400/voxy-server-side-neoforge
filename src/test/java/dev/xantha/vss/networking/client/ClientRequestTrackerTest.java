package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientRequestTrackerTest {

    @Test
    void trackedRequestPreservesFlagsUntilRemoved() {
        ClientRequestTracker tracker = newTracker();

        int requestId = tracker.track(42L, true, true, 1_000_000_000L, System.nanoTime());

        assertTrue(tracker.contains(42L));
        assertTrue(tracker.isGenerationRequest(requestId));
        assertTrue(tracker.isDirtyRefreshRequest(requestId));
        assertEquals(1, tracker.size());
        assertEquals(1, tracker.generationSize());
        assertEquals(1, tracker.dirtyRefreshSize());

        assertEquals(42L, tracker.remove(requestId));
        assertFalse(tracker.contains(42L));
        assertEquals(Long.MIN_VALUE, tracker.remove(requestId));
    }

    @Test
    void drainTimedOutReturnsDueRequestsAndKeepsFutureRequests() {
        ClientRequestTracker tracker = newTracker();

        long now = System.nanoTime();
        tracker.track(1L, false, false, 1_000_000_000L, now);
        tracker.track(2L, true, false, -1L, now);

        List<ClientRequestTracker.TimedOutRequest> timedOut = tracker.drainTimedOut(now);

        assertEquals(1, timedOut.size());
        assertEquals(2L, timedOut.get(0).packed());
        assertTrue(timedOut.get(0).generationRequest());
        assertFalse(timedOut.get(0).dirtyRefreshRequest());
        assertTrue(tracker.contains(1L));
        assertFalse(tracker.contains(2L));
    }

    @Test
    void cancelRemovesTrackedPosition() {
        ClientRequestTracker tracker = newTracker();

        tracker.track(7L, false, false, 1_000_000_000L, System.nanoTime());
        tracker.cancel(7L);

        assertFalse(tracker.contains(7L));
        assertEquals(0, tracker.size());
    }

    @Test
    void forEachInFlightVisitsTrackedPositionsWithoutSnapshot() {
        ClientRequestTracker tracker = newTracker();
        long now = System.nanoTime();
        tracker.track(1L, false, false, 1_000_000_000L, now);
        tracker.track(2L, true, false, 1_000_000_000L, now);

        ArrayList<Long> inFlight = new ArrayList<>();
        ArrayList<Long> generation = new ArrayList<>();
        tracker.forEachInFlight(inFlight::add);
        tracker.forEachGenerationInFlight(generation::add);

        assertEquals(List.of(1L, 2L), inFlight.stream().sorted().toList());
        assertEquals(List.of(2L), generation);
    }

    @Test
    void completedRequestsCompactDeadlineTombstones() {
        ClientRequestTracker tracker = newTracker();
        long now = System.nanoTime();
        int[] requestIds = new int[128];
        for (int i = 0; i < requestIds.length; i++) {
            requestIds[i] = tracker.track(i, false, false, 1_000_000_000L, now);
        }

        for (int requestId : requestIds) {
            tracker.remove(requestId);
        }

        assertEquals(0, tracker.size());
        assertEquals(0, tracker.deadlineHeapSize());
    }

    @Test
    void removedDeadlineTombstonesDoNotTimeoutLater() {
        ClientRequestTracker tracker = newTracker();
        long now = System.nanoTime();
        int removed = tracker.track(1L, false, false, 1_000_000_000L, now);
        tracker.track(2L, true, false, 1_000_000_000L, now);

        tracker.remove(removed);
        List<ClientRequestTracker.TimedOutRequest> timedOut = tracker.drainTimedOut(now + 1_000_000_001L);

        assertEquals(1, timedOut.size());
        assertEquals(2L, timedOut.get(0).packed());
    }

    @Test
    void refreshedDeadlineDoesNotTimeoutAtOriginalDeadline() {
        ClientRequestTracker tracker = newTracker();
        long now = 1_000L;
        int requestId = tracker.track(1L, false, false, 1_000L, now);

        tracker.refreshDeadline(requestId, 5_000L, now + 500L);

        assertTrue(tracker.drainTimedOut(now + 1_001L).isEmpty());
        List<ClientRequestTracker.TimedOutRequest> timedOut = tracker.drainTimedOut(now + 5_501L);
        assertEquals(1, timedOut.size());
        assertEquals(1L, timedOut.get(0).packed());
    }

    private static ClientRequestTracker newTracker() {
        return new ClientRequestTracker(ignored -> {
        });
    }
}
