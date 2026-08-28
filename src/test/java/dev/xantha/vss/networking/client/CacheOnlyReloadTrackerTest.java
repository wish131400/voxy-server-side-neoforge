package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CacheOnlyReloadTrackerTest {
    @Test
    void completesEachKnownDimensionIndependently() {
        CacheOnlyReloadTracker tracker = new CacheOnlyReloadTracker();
        tracker.begin(Set.of("minecraft:overworld", "minecraft:the_nether"), "minecraft:overworld");

        assertTrue(tracker.isActive());
        assertTrue(tracker.completeActive());
        assertFalse(tracker.isActive());
        assertEquals(1, tracker.pendingCount());

        tracker.enter("minecraft:the_end");
        assertFalse(tracker.isActive());
        assertEquals(1, tracker.pendingCount());

        tracker.enter("minecraft:the_nether");
        assertTrue(tracker.isActive());
        assertTrue(tracker.completeActive());
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void currentDimensionIsReloadedEvenWithoutStoredPresence() {
        CacheOnlyReloadTracker tracker = new CacheOnlyReloadTracker();
        tracker.begin(Set.of(), "minecraft:overworld");

        assertTrue(tracker.isActive());
        assertEquals(1, tracker.pendingCount());
    }

    @Test
    void leavingAnIncompleteDimensionKeepsItPending() {
        CacheOnlyReloadTracker tracker = new CacheOnlyReloadTracker();
        tracker.begin(Set.of("minecraft:overworld"), "minecraft:overworld");

        tracker.enter("minecraft:the_end");
        assertFalse(tracker.isActive());
        assertEquals(1, tracker.pendingCount());

        tracker.enter("minecraft:overworld");
        assertTrue(tracker.isActive());
    }

    @Test
    void replayCompletionWaitsForDecodeAndXaeroQueues() {
        assertFalse(LodRequestManager.cacheOnlyReplayReadyToComplete(
                true, true, 0, 0, true, false));
        assertFalse(LodRequestManager.cacheOnlyReplayReadyToComplete(
                true, true, 0, 0, false, true));
        assertTrue(LodRequestManager.cacheOnlyReplayReadyToComplete(
                true, true, 0, 0, false, false));
    }
}
