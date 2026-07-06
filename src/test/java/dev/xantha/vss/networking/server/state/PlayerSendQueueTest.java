package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PlayerSendQueueTest {

    @Test
    void priorityPayloadIsPeekedBeforeNormalPayloads() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();

        enqueue(queue, payload(1, 1, 1), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(2, 20, 20), true, 10, 1_000_000, cleared);

        assertEquals(2, queue.peek(0, 0).payload().requestId());
        assertEquals(2, queue.peekPriority(0, 0).payload().requestId());
        assertEquals(1, queue.peekNormal(0, 0).payload().requestId());
        assertEquals(1, queue.priorityQueuedPayloadCount());
    }

    @Test
    void normalPollDoesNotConsumePriorityPayloads() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();

        enqueue(queue, payload(1, 1, 1), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(2, 0, 0), true, 10, 1_000_000, cleared);

        assertEquals(1, queue.pollNormal(queue.peekNormal(0, 0)).payload().requestId());
        assertEquals(2, queue.peekPriority(0, 0).payload().requestId());
        assertNull(queue.peekNormal(0, 0));
    }

    @Test
    void normalPayloadsAreOrderedByDistanceThenSequence() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();

        enqueue(queue, payload(1, 8, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(2, 2, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(3, -2, 0), false, 10, 1_000_000, cleared);

        assertEquals(2, queue.poll(queue.peek(0, 0)).payload().requestId());
        assertEquals(3, queue.poll(queue.peek(0, 0)).payload().requestId());
        assertEquals(1, queue.poll(queue.peek(0, 0)).payload().requestId());
        assertNull(queue.peek(0, 0));
    }

    @Test
    void overflowRejectsIncomingPayloadAndTrimsNormalQueue() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();

        enqueue(queue, payload(1, 0, 0), false, 1, 1_000_000, cleared);
        enqueue(queue, payload(2, 1, 0), false, 1, 1_000_000, cleared);

        assertEquals(0, queue.queuedPayloadCount());
        assertEquals(2, cleared.get(0));
        assertEquals(1, cleared.get(1));
    }

    @Test
    void pollingUpdatesCountsAndBytes() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();
        VoxelColumnS2CPayload payload = payload(1, 0, 0);

        enqueue(queue, payload, false, 10, 1_000_000, cleared);
        long queuedBytes = queue.queuedBytes();

        queue.poll(queue.peek(0, 0));

        assertEquals(payload.estimatedWireBytes(false), queuedBytes);
        assertEquals(0L, queue.queuedBytes());
        assertEquals(0, queue.queuedPayloadCount());
    }

    private static VoxelColumnS2CPayload payload(int requestId, int cx, int cz) {
        return new VoxelColumnS2CPayload(requestId, cx, cz, null, 1L, new byte[128]);
    }

    private static void enqueue(
            PlayerSendQueue queue,
            VoxelColumnS2CPayload payload,
            boolean priority,
            int queueLimit,
            int queueBytesLimit,
            ArrayList<Integer> cleared) {
        queue.enqueue(payload, priority, queueLimit, queueBytesLimit, false, cleared::add);
    }
}
