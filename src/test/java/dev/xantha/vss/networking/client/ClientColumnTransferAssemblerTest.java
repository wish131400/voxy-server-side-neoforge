package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.networking.client.ClientColumnTransferAssembler.OfferStatus;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientColumnTransferAssemblerTest {

    @Test
    void outOfOrderPartsCompleteAsOneOrderedLogicalColumn() {
        ClientColumnTransferAssembler assembler = new ClientColumnTransferAssembler(4, 1_000_000L);

        assertEquals(
                OfferStatus.ACCEPTED,
                assembler.offer(part(7, 41L, 1, 2, 0, new int[] {3}), true, true, 0, 0L, 1L).status());
        ClientColumnTransferAssembler.OfferResult completed =
                assembler.offer(part(7, 41L, 0, 2, 0, new int[0]), true, true, 0, 0L, 2L);

        assertEquals(OfferStatus.COMPLETED, completed.status());
        assertNotNull(completed.column());
        assertEquals(List.of(0, 1), completed.column().parts().stream()
                .map(VoxelColumnS2CPayload::partIndex)
                .toList());
        assertEquals(0, assembler.activeLogicalColumns());
        assertEquals(0L, assembler.activeBytes());
    }

    @Test
    void duplicatePartRejectsAndRemovesWholeTransfer() {
        ClientColumnTransferAssembler assembler = new ClientColumnTransferAssembler(4, 1_000_000L);
        VoxelColumnS2CPayload first = part(7, 42L, 0, 2, 0, new int[0]);

        assertEquals(OfferStatus.ACCEPTED, assembler.offer(first, false, false, 0, 0L, 1L).status());
        assertEquals(OfferStatus.REJECTED, assembler.offer(first, false, false, 0, 0L, 2L).status());
        assertEquals(0, assembler.activeLogicalColumns());
    }

    @Test
    void metadataConflictRejectsExistingTransfer() {
        ClientColumnTransferAssembler assembler = new ClientColumnTransferAssembler(4, 1_000_000L);

        assembler.offer(part(7, 43L, 0, 2, 0, new int[0]), false, false, 0, 0L, 1L);
        ClientColumnTransferAssembler.OfferResult conflict =
                assembler.offer(part(7, 43L, 1, 2, 1, new int[] {3}), false, false, 0, 0L, 2L);

        assertEquals(OfferStatus.REJECTED, conflict.status());
        assertEquals(0, assembler.activeLogicalColumns());
    }

    @Test
    void queuedLogicalColumnsShareTheCapacityBudget() {
        ClientColumnTransferAssembler assembler = new ClientColumnTransferAssembler(1, 1_000_000L);

        ClientColumnTransferAssembler.OfferResult rejected =
                assembler.offer(part(7, 44L, 0, 1, 0, new int[] {3}), false, false, 1, 0L, 1L);

        assertEquals(OfferStatus.REJECTED, rejected.status());
        assertEquals(0, assembler.activeLogicalColumns());
    }

    @Test
    void idleTransferExpiresWithExactRequestIdentity() {
        ClientColumnTransferAssembler assembler = new ClientColumnTransferAssembler(4, 1_000_000L);
        assembler.offer(part(9, 45L, 0, 2, 0, new int[0]), false, false, 0, 0L, 10L);

        List<ClientColumnTransferAssembler.FailedTransfer> expired = assembler.expireIdle(30L, 20L);

        assertEquals(1, expired.size());
        assertEquals(9, expired.get(0).requestId());
        assertEquals(45L, expired.get(0).transferId());
        assertTrue(assembler.clear().isEmpty());
    }

    private static VoxelColumnS2CPayload part(
            int requestId,
            long transferId,
            int partIndex,
            int partCount,
            int chunkX,
            int[] replacementSectionYs) {
        return new VoxelColumnS2CPayload(
                requestId,
                chunkX,
                0,
                null,
                100L,
                new byte[] {0},
                true,
                transferId,
                partIndex,
                partCount,
                replacementSectionYs);
    }
}

