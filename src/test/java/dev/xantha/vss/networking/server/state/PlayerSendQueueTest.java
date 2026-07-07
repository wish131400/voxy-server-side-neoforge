package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayList;
import java.util.List;
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
    void completePollSequenceMatchesDistancePriorityAndInsertionOrder() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();

        enqueue(queue, payload(1, 10, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(2, 2, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(3, -2, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(4, 30, 0), true, 10, 1_000_000, cleared);
        enqueue(queue, payload(5, -1, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(6, 1, 0), true, 10, 1_000_000, cleared);

        assertEquals(List.of(6, 4, 5, 2, 3, 1), drainAllRequestIds(queue, 0, 0));
    }

    @Test
    void completePollSequenceUpdatesAfterRecenter() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();

        enqueue(queue, payload(1, -8, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(2, 8, 0), false, 10, 1_000_000, cleared);
        enqueue(queue, payload(3, 0, 0), false, 10, 1_000_000, cleared);
        queue.prepareOrder(0, 0);
        queue.prepareOrder(10, 0);

        assertEquals(List.of(2, 3, 1), drainAllRequestIds(queue, 10, 0));
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

    @Test
    void queuedBytesUseVssCompressedWireSize() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();
        byte[] rawColumn = new byte[4096];
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(1, 0, 0, null, 1L, rawColumn);

        queue.enqueue(payload, false, 10, 1_000_000, true, cleared::add);

        assertEquals(payload.estimatedWireBytes(true), queue.queuedBytes());
        assertEquals(queue.peek(0, 0).wireBytes(), queue.queuedBytes());
        assertEquals(payload.rawEstimatedBytes(), queue.peek(0, 0).rawBytes());
    }

    @Test
    void queuedPayloadRecordsBytesAheadAtEnqueue() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();
        VoxelColumnS2CPayload first = payload(1, 0, 0);
        VoxelColumnS2CPayload second = payload(2, 1, 0);

        enqueue(queue, first, false, 10, 1_000_000, cleared);
        long firstWireBytes = first.estimatedWireBytes(false);
        enqueue(queue, second, false, 10, 1_000_000, cleared);

        assertEquals(0L, queue.poll(queue.peek(0, 0)).queuedBytesAheadAtEnqueue());
        assertEquals(firstWireBytes, queue.poll(queue.peek(0, 0)).queuedBytesAheadAtEnqueue());
    }

    @Test
    void splitPayloadsArePolledAsOneAtomicBatch() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();
        List<VoxelColumnS2CPayload> splitPayloads = List.of(
                splitPayload(7, 0, 0, false),
                splitPayload(7, 0, 0, false),
                splitPayload(7, 0, 0, true));

        queue.enqueue(splitPayloads, false, 10, 1_000_000, false, cleared::add);

        PlayerRequestState.QueuedPayloadBatch batch = queue.peekNormalBatch(0, 0);
        int expectedWireBytes = splitPayloads.stream()
                .mapToInt(payload -> payload.estimatedWireBytes(false))
                .sum();
        assertEquals(3, queue.queuedPayloadCount());
        assertEquals(3, batch.payloadCount());
        assertEquals(7, batch.requestId());
        assertEquals(expectedWireBytes, batch.wireBytes());

        assertEquals(batch, queue.pollNormalBatch(batch));

        assertEquals(0, queue.queuedPayloadCount());
        assertEquals(0L, queue.queuedBytes());
        assertNull(queue.peekNormalBatch(0, 0));
    }

    @Test
    void splitPayloadsCanBeConsumedOnePartAtATimeWithoutClearingBatch() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();
        List<VoxelColumnS2CPayload> splitPayloads = List.of(
                splitPayload(7, 0, 0, false),
                splitPayload(7, 0, 0, false),
                splitPayload(7, 0, 0, true));

        queue.enqueue(splitPayloads, false, 10, 1_000_000, false, cleared::add);
        PlayerRequestState.QueuedPayloadBatch batch = queue.peekNormalBatch(0, 0);
        long initialBytes = queue.queuedBytes();

        PlayerRequestState.QueuedPayload first = queue.consumeBatchPayload(batch);

        assertEquals(7, first.payload().requestId());
        assertEquals(2, queue.queuedPayloadCount());
        assertEquals(2, batch.payloadCount());
        assertEquals(batch, queue.peekNormalBatch(0, 0));
        assertEquals(initialBytes - first.wireBytes(), queue.queuedBytes());

        queue.consumeBatchPayload(batch);
        PlayerRequestState.QueuedPayload last = queue.consumeBatchPayload(batch);

        assertEquals(7, last.payload().requestId());
        assertEquals(0, queue.queuedPayloadCount());
        assertEquals(0L, queue.queuedBytes());
        assertNull(queue.peekNormalBatch(0, 0));
    }

    private static VoxelColumnS2CPayload payload(int requestId, int cx, int cz) {
        return new VoxelColumnS2CPayload(requestId, cx, cz, null, 1L, new byte[128]);
    }

    private static VoxelColumnS2CPayload splitPayload(int requestId, int cx, int cz, boolean completesRequest) {
        return new VoxelColumnS2CPayload(
                requestId,
                cx,
                cz,
                null,
                1L,
                new byte[128],
                completesRequest,
                completesRequest,
                new int[0]);
    }

    private static List<Integer> drainAllRequestIds(PlayerSendQueue queue, int playerCx, int playerCz) {
        ArrayList<Integer> requestIds = new ArrayList<>();
        PlayerRequestState.QueuedPayload queued;
        while ((queued = queue.peek(playerCx, playerCz)) != null) {
            requestIds.add(queue.poll(queued).payload().requestId());
        }
        return requestIds;
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
