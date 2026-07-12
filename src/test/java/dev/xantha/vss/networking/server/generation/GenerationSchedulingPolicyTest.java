package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GenerationSchedulingPolicyTest {

    @Test
    void allCallbacksForSamePlayerMustFitBeforeTicketStarts() {
        UUID player = UUID.randomUUID();
        Map<UUID, Integer> active = Map.of(player, 3);

        assertFalse(GenerationSchedulingPolicy.hasPerPlayerCapacity(
                active,
                List.of(player, player),
                4));
        assertTrue(GenerationSchedulingPolicy.hasPerPlayerCapacity(
                active,
                List.of(player),
                4));
    }

    @Test
    void snapshotHandoffReleasesGenerationSlotsBeforePackingCompletes() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, Integer> active = new HashMap<>();
        active.put(first, 2);
        active.put(second, 1);

        GenerationSchedulingPolicy.releaseSlots(active, List.of(first, second));

        assertEquals(1, active.get(first));
        assertFalse(active.containsKey(second));
    }

    @Test
    void priorityThenRingThenSequenceDefinesStableQueueOrder() {
        assertTrue(GenerationSchedulingPolicy.compare(true, 20, 2L, false, 1, 1L) < 0);
        assertTrue(GenerationSchedulingPolicy.compare(false, 2, 2L, false, 20, 1L) < 0);
        assertTrue(GenerationSchedulingPolicy.compare(false, 2, 1L, false, 2, 2L) < 0);
    }

    @Test
    void priorityQueuePollsDirtyThenNearestThenOldest() {
        PriorityQueue<TestEntry> queue = new PriorityQueue<>();
        queue.add(new TestEntry("far", false, 20, 1L));
        queue.add(new TestEntry("new-near", false, 2, 3L));
        queue.add(new TestEntry("old-near", false, 2, 2L));
        queue.add(new TestEntry("priority", true, 30, 4L));

        assertEquals("priority", queue.remove().name());
        assertEquals("old-near", queue.remove().name());
        assertEquals("new-near", queue.remove().name());
        assertEquals("far", queue.remove().name());
    }

    @Test
    void staleHeapRevisionIsRejected() {
        assertTrue(GenerationSchedulingPolicy.isCurrentRevision(4L, 4L));
        assertFalse(GenerationSchedulingPolicy.isCurrentRevision(4L, 3L));
    }

    @Test
    void oldPackingCompletionCannotRemoveReplacementCallback() {
        Object oldCallback = new Object();
        Object replacement = new Object();
        Map<Integer, Object> callbacks = new HashMap<>();
        callbacks.put(7, replacement);

        assertFalse(GenerationSchedulingPolicy.removeIdentity(callbacks, 7, oldCallback));
        assertEquals(replacement, callbacks.get(7));
        assertTrue(GenerationSchedulingPolicy.removeIdentity(callbacks, 7, replacement));
        assertFalse(callbacks.containsKey(7));
    }

    private record TestEntry(String name, boolean priority, int ring, long sequence)
            implements Comparable<TestEntry> {
        @Override
        public int compareTo(TestEntry other) {
            return GenerationSchedulingPolicy.compare(
                    priority,
                    ring,
                    sequence,
                    other.priority,
                    other.ring,
                    other.sequence);
        }
    }
}
