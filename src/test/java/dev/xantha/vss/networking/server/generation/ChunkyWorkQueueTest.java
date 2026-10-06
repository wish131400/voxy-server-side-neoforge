package dev.xantha.vss.networking.server.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkyWorkQueueTest {
    @Test void lazyCursorAdmitsMoreThanTheRemovedLimitsWithoutWaitingForCompletion() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 2047, 2047));
        assertEquals(0, queue.inFlightCount());
        for (int i = 0; i < 5000; i++) assertNotNull(queue.reserve());
        assertEquals(5000, queue.inFlightCount());
        assertEquals(0L, queue.processed());
        assertFalse(queue.allReserved());
        assertFalse(queue.finished());
    }

    @Test void pendingWritesDoNotBlockNewPositionsAndCompletionRemainsIdempotent() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(-1, -1, 0, 0));
        var first = queue.reserve();
        first.stage = ChunkyWorkQueue.Stage.WRITING;
        var next = queue.reserve();
        assertNotNull(next);
        assertNotEquals(first.requestId, next.requestId);
        assertEquals(0L, queue.completed());
        queue.finish(first, true);
        queue.finish(first, true);
        assertEquals(1L, queue.completed());
        assertEquals(1, queue.inFlightCount());
        assertFalse(queue.finished());
    }

    @Test void parallelReadersClaimEveryPositionExactlyOnce() throws Exception {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(-32, -32, 31, 31));
        var positions = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                ChunkyWorkQueue.Work work;
                while ((work = queue.reserve()) != null) {
                    assertTrue(positions.add(work.requestId));
                    queue.finish(work, true);
                }
            }));
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(4096, positions.size());
            assertEquals(4096, queue.completed());
            assertTrue(queue.finished());
        } finally { executor.shutdownNow(); }
    }

    @Test void cancellationClosesTheCursorAndDiscardsLateCallbacks() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 2047, 2047));
        var work = queue.reserve();
        queue.close();
        assertNull(queue.reserve());
        assertNull(queue.get(work.requestId));
        assertEquals(0, queue.inFlightCount());
        queue.finish(work, true);
        assertEquals(0, queue.processed());
    }

    @Test void failedAndReusedColumnsAreAccountedSeparatelyAndFinishRequiresDrain() {
        ChunkyWorkQueue queue = new ChunkyWorkQueue(new ChunkyArea(0, 0, 1, 0));
        var first = queue.reserve();
        var second = queue.reserve();
        assertTrue(queue.allReserved());
        assertFalse(queue.finished());
        first.reused = true;
        queue.finish(first, true);
        queue.finish(second, false);
        assertTrue(queue.finished());
        assertEquals(1L, queue.completed());
        assertEquals(1L, queue.reused());
        assertEquals(1L, queue.failed());
        assertEquals(2L, queue.processed());
        assertNull(queue.reserve());
    }
}
