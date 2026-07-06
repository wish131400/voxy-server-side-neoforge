package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeferredColumnQueueTest {

    @Test
    void duplicateNormalDefersKeepSingleQueuedEntry() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);

        queue.defer(1L);
        queue.defer(1L);

        assertEquals(1, queue.size());
        assertEquals(1, queue.queuedEntries());
        assertIterableEquals(List.of(1L), queue.pollUniqueCandidates(10));
    }

    @Test
    void urgentDefersMoveColumnToFront() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);

        queue.defer(1L);
        queue.defer(2L);
        queue.defer(1L, true);

        assertIterableEquals(List.of(1L, 2L), queue.pollUniqueCandidates(10));
    }

    @Test
    void capacityEvictsOldestQueuedColumn() {
        DeferredColumnQueue queue = new DeferredColumnQueue(2);

        queue.defer(1L);
        queue.defer(2L);
        queue.defer(3L);

        assertFalse(queue.contains(1L));
        assertTrue(queue.contains(2L));
        assertTrue(queue.contains(3L));
        assertIterableEquals(List.of(2L, 3L), queue.pollUniqueCandidates(10));
    }

    @Test
    void removedColumnsAreSkippedWhenPollingCandidates() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);

        queue.defer(1L);
        queue.remove(1L);

        assertIterableEquals(List.of(), queue.pollUniqueCandidates(10));
        assertEquals(0, queue.queuedEntries());
    }

    @Test
    void requeueAddsAnotherQueueEntryForExistingColumn() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);

        queue.defer(1L);
        assertIterableEquals(List.of(1L), queue.pollUniqueCandidates(10));
        queue.requeue(1L, false);

        assertTrue(queue.contains(1L));
        assertIterableEquals(List.of(1L), queue.pollUniqueCandidates(10));
    }
}
