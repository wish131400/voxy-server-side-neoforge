package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.processing.LodByteCompression;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class VoxelColumnS2CPayloadTest {

    @Test
    void encodedPayloadKeepsColumnBytesCompressedUntilProcessing() {
        byte[] sections = new byte[4096];
        Arrays.fill(sections, (byte) 7);
        LodByteCompression.Result compressed = LodByteCompression.compressForNetwork(sections, false);

        VoxelColumnS2CPayload decoded = new VoxelColumnS2CPayload(
                42,
                12,
                -3,
                null,
                99L,
                compressed.bytes(),
                compressed.method(),
                compressed.originalLength(),
                true);

        assertEquals(sections.length, decoded.rawSectionBytesLength());
        assertTrue(decoded.estimatedBytes() < sections.length);
        assertArrayEquals(sections, decoded.decompressedSections());
    }

    @Test
    void payloadDerivesRequestCompletionFromTransferMetadata() {
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(
                7,
                3,
                -5,
                null,
                123L,
                new byte[] {0},
                true,
                91L,
                0,
                2,
                new int[] {-4, 8});

        assertEquals(7, payload.requestId());
        assertEquals(3, payload.chunkX());
        assertEquals(-5, payload.chunkZ());
        assertEquals(123L, payload.columnTimestamp());
        assertTrue(payload.completeColumn());
        assertFalse(payload.completesRequest());
        assertEquals(91L, payload.transferId());
        assertEquals(0, payload.partIndex());
        assertEquals(2, payload.partCount());
        assertArrayEquals(new int[] {-4, 8}, payload.replacementSectionYs());
        assertArrayEquals(new byte[] {0}, payload.decompressedSections());
    }

    @Test
    void wireHeaderRoundTripPreservesTransferMetadata() {
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(
                9,
                4,
                -2,
                null,
                1234L,
                new byte[] {0},
                true,
                77L,
                1,
                2,
                new int[] {-4, 8});
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VoxelColumnS2CPayload.writeTransferMetadata(payload, buf);
            VoxelColumnS2CPayload.TransferMetadata decoded =
                    VoxelColumnS2CPayload.readTransferMetadata(buf);

            assertEquals(77L, decoded.transferId());
            assertEquals(1, decoded.partIndex());
            assertEquals(2, decoded.partCount());
            assertEquals(9, decoded.requestId());
        } finally {
            buf.release();
        }
    }

    @Test
    void unassignedTransferCannotBeWrittenToWire() {
        VoxelColumnS2CPayload payload =
                new VoxelColumnS2CPayload(1, 0, 0, null, 1L, new byte[] {0});
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> VoxelColumnS2CPayload.writeTransferMetadata(payload, buf));
        } finally {
            buf.release();
        }
    }
}
