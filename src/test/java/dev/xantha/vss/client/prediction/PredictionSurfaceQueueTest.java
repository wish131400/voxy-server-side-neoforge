package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictionSurfaceQueueTest {
    @Test void repeatedResidentRefreshRanksOnlyNewTasks() {
        var ranks = new AtomicInteger();
        var queue = new PredictionSurfaceQueue<Integer>(key -> {
            ranks.incrementAndGet(); return new PredictionSurfaceQueue.Priority(key & 1, key);
        });
        for (int pass = 0; pass < 4; pass++)
            for (int key = 16383; key >= 0; key--) queue.add(key);
        assertEquals(16384, ranks.get(), "duplicates must not classify or compare resident work again");
        assertEquals(16384, queue.size());
        assertEquals(0, queue.removeFirst());
        assertEquals(2, queue.removeFirst());
        assertTrue(queue.add(0), "a refused or completed task may be offered again");
        assertEquals(0, queue.removeFirst());
        queue.clear();
        assertTrue(queue.isEmpty()); assertNull(queue.peekFirst());
        assertTrue(queue.add(1)); assertEquals(1, queue.removeFirst());
    }

    @Test void visibilityThenDistanceAndStableTiesSurviveRemovalAndReplacement() {
        var queue = new PredictionSurfaceQueue<String>(key ->
                new PredictionSurfaceQueue.Priority(key.equals("buffer") ? 1 : 0,
                        key.startsWith("near") ? 1 : 10));
        queue.addAll(List.of("far", "buffer", "near-a", "near-b"));
        assertEquals(List.of("near-a", "near-b", "far", "buffer"), List.copyOf(queue));
        assertTrue(queue.removeIf(key -> key.startsWith("near")));
        assertFalse(queue.contains("near-a"));
        assertTrue(queue.add("near-a"));
        assertEquals("near-a", queue.removeFirst());
        assertTrue(queue.remove("far")); assertFalse(queue.remove("far"));
        assertEquals("buffer", queue.removeFirst());
        assertThrows(java.util.NoSuchElementException.class, queue::removeFirst);
        assertThrows(NullPointerException.class, () -> queue.add(null));
    }
}
