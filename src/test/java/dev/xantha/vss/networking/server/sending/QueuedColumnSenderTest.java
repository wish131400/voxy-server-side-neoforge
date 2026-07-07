package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.assertSame;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.state.PlayerSendQueue;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueuedColumnSenderTest {

    @Test
    void nearerNormalBatchBeatsFartherPriorityBatch() {
        PlayerRequestState.QueuedPayloadBatch priority = batch(payload(1, 24, 0, true), true);
        PlayerRequestState.QueuedPayloadBatch normal = batch(payload(2, 4, 0, false), false);

        assertSame(normal, QueuedColumnSender.chooseBatch(priority, normal, 0, 0, true, 0, 8));
    }

    @Test
    void nearerPriorityBatchBeatsFartherNormalBatch() {
        PlayerRequestState.QueuedPayloadBatch priority = batch(payload(1, 4, 0, true), true);
        PlayerRequestState.QueuedPayloadBatch normal = batch(payload(2, 24, 0, false), false);

        assertSame(priority, QueuedColumnSender.chooseBatch(priority, normal, 0, 0, false, 0, 8));
    }

    @Test
    void equalRingUsesFairPriorityToggle() {
        PlayerRequestState.QueuedPayloadBatch priority = batch(payload(1, 4, 0, true), true);
        PlayerRequestState.QueuedPayloadBatch normal = batch(payload(2, -4, 0, false), false);

        assertSame(priority, QueuedColumnSender.chooseBatch(priority, normal, 0, 0, true, 0, 8));
        assertSame(normal, QueuedColumnSender.chooseBatch(priority, normal, 0, 0, false, 0, 8));
    }

    @Test
    void equalRingPriorityBudgetLetsNormalDrain() {
        PlayerRequestState.QueuedPayloadBatch priority = batch(payload(1, 4, 0, true), true);
        PlayerRequestState.QueuedPayloadBatch normal = batch(payload(2, -4, 0, false), false);

        assertSame(normal, QueuedColumnSender.chooseBatch(priority, normal, 0, 0, true, 8, 8));
    }

    @Test
    void inProgressPriorityBatchCompletesBeforeNearerNormalBatch() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();
        queue.enqueue(
                List.of(
                        splitPayload(7, 20, 0, false),
                        splitPayload(7, 20, 0, true)),
                true,
                10,
                1_000_000,
                false,
                cleared::add);
        PlayerRequestState.QueuedPayloadBatch priority = queue.peekPriorityBatch(0, 0);
        queue.consumeBatchPayload(priority);
        queue.enqueue(payload(8, 1, 0, false), false, 10, 1_000_000, false, cleared::add);

        PlayerRequestState.QueuedPayloadBatch normal = queue.peekNormalBatch(0, 0);

        assertSame(priority, QueuedColumnSender.chooseBatch(priority, normal, 0, 0, false, 8, 8));
    }

    @Test
    void inProgressNormalBatchCompletesBeforeNearerPriorityBatch() {
        PlayerSendQueue queue = new PlayerSendQueue();
        ArrayList<Integer> cleared = new ArrayList<>();
        queue.enqueue(
                List.of(
                        splitPayload(7, 20, 0, false),
                        splitPayload(7, 20, 0, true)),
                false,
                10,
                1_000_000,
                false,
                cleared::add);
        PlayerRequestState.QueuedPayloadBatch normal = queue.peekNormalBatch(0, 0);
        queue.consumeBatchPayload(normal);
        queue.enqueue(payload(8, 1, 0, true), true, 10, 1_000_000, false, cleared::add);

        PlayerRequestState.QueuedPayloadBatch priority = queue.peekPriorityBatch(0, 0);

        assertSame(normal, QueuedColumnSender.chooseBatch(priority, normal, 0, 0, true, 0, 8));
    }

    private static PlayerRequestState.QueuedPayloadBatch batch(VoxelColumnS2CPayload payload, boolean priority) {
        PlayerRequestState.QueuedPayload queuedPayload = new PlayerRequestState.QueuedPayload(
                payload,
                payload.estimatedWireBytes(false),
                payload.rawEstimatedBytes(),
                priority,
                payload.requestId(),
                0L,
                0L);
        return new PlayerRequestState.QueuedPayloadBatch(
                List.of(queuedPayload),
                priority,
                payload.requestId(),
                0L,
                queuedPayload.wireBytes(),
                queuedPayload.rawBytes(),
                0L);
    }

    private static VoxelColumnS2CPayload payload(int requestId, int cx, int cz, boolean priority) {
        return new VoxelColumnS2CPayload(
                requestId,
                cx,
                cz,
                null,
                1L,
                new byte[128],
                true,
                true,
                new int[0]);
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
}
