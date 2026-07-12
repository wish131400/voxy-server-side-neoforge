package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.PositionUtil;
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
        assertIterableEquals(List.of(1L), queue.pollClosestCandidates(10, false));
    }

    @Test
    void urgentDefersMoveColumnToFront() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);

        long normal = position(1, 0);
        long urgent = position(8, 0);
        queue.recenter(0, 0);
        queue.defer(normal);
        queue.defer(urgent, true);

        assertIterableEquals(List.of(urgent, normal), queue.pollClosestCandidates(10, false));
    }

    @Test
    void capacityEvictsFurthestNormalColumn() {
        DeferredColumnQueue queue = new DeferredColumnQueue(2);

        long near = position(1, 0);
        long far = position(8, 0);
        long middle = position(4, 0);
        queue.recenter(0, 0);
        queue.defer(near);
        queue.defer(far);
        queue.defer(middle);

        assertTrue(queue.contains(near));
        assertTrue(queue.contains(middle));
        assertFalse(queue.contains(far));
        assertIterableEquals(List.of(near, middle), queue.pollClosestCandidates(10, false));
    }

    @Test
    void removedColumnsAreSkippedWhenPollingCandidates() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);

        queue.defer(1L);
        queue.remove(1L);

        assertIterableEquals(List.of(), queue.pollClosestCandidates(10, false));
        assertEquals(0, queue.queuedEntries());
    }

    @Test
    void requeueAddsAnotherQueueEntryForExistingColumn() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);

        queue.defer(1L);
        assertIterableEquals(List.of(1L), queue.pollClosestCandidates(10, false));
        queue.requeue(1L, false);

        assertTrue(queue.contains(1L));
        assertIterableEquals(List.of(1L), queue.pollClosestCandidates(10, false));
    }

    @Test
    void repeatedRequeueKeepsOnePhysicalQueueEntry() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);
        long packed = position(3, 4);
        queue.recenter(0, 0);

        for (int i = 0; i < 1_000; i++) {
            queue.requeue(packed, false);
        }

        assertEquals(1, queue.size());
        assertEquals(1, queue.queuedEntries());
        assertIterableEquals(List.of(packed), queue.pollClosestCandidates(10, false));
        assertEquals(0, queue.queuedEntries());
    }

    @Test
    void recenterChangesPollOrder() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);
        long west = position(-8, 0);
        long east = position(8, 0);

        queue.recenter(0, 0);
        queue.defer(west);
        queue.defer(east);

        queue.recenter(10, 0);

        assertIterableEquals(List.of(east, west), queue.pollClosestCandidates(10, false));
    }

    @Test
    void urgentColumnsAreProtectedFromNormalEviction() {
        DeferredColumnQueue queue = new DeferredColumnQueue(2);
        long urgentFar = position(32, 0);
        long normalNear = position(1, 0);
        long incomingNear = position(0, 0);

        queue.recenter(0, 0);
        queue.defer(urgentFar, true);
        queue.defer(normalNear);
        queue.defer(incomingNear);

        assertTrue(queue.contains(urgentFar));
        assertFalse(queue.contains(normalNear));
        assertTrue(queue.contains(incomingNear));
    }

    @Test
    void fullQueueRejectsNewNormalColumnWhenItIsFurthest() {
        DeferredColumnQueue queue = new DeferredColumnQueue(2);
        long near = position(1, 0);
        long middle = position(4, 0);
        long far = position(8, 0);

        queue.recenter(0, 0);
        queue.defer(near);
        queue.defer(middle);
        queue.defer(far);

        assertTrue(queue.contains(near));
        assertTrue(queue.contains(middle));
        assertFalse(queue.contains(far));
        assertIterableEquals(List.of(near, middle), queue.pollClosestCandidates(10, false));
    }

    @Test
    void urgentOnlyPollingSkipsNormalCandidates() {
        DeferredColumnQueue queue = new DeferredColumnQueue(10);
        long normal = position(1, 0);
        long urgent = position(4, 0);

        queue.recenter(0, 0);
        queue.defer(normal);
        queue.defer(urgent, true);

        assertIterableEquals(List.of(urgent), queue.pollClosestCandidates(10, true));
        assertTrue(queue.contains(normal));
        assertIterableEquals(List.of(normal), queue.pollClosestCandidates(10, false));
    }

    private static long position(int cx, int cz) {
        return PositionUtil.packPosition(cx, cz);
    }
}
