package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.processing.LodByteCompression;
import java.util.Arrays;
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
    void payloadPreservesRequestCompletionAndReplacementManifest() {
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(
                7,
                3,
                -5,
                null,
                123L,
                new byte[] {0},
                true,
                false,
                new int[] {-4, 8});

        assertEquals(7, payload.requestId());
        assertEquals(3, payload.chunkX());
        assertEquals(-5, payload.chunkZ());
        assertEquals(123L, payload.columnTimestamp());
        assertTrue(payload.completeColumn());
        assertFalse(payload.completesRequest());
        assertArrayEquals(new int[] {-4, 8}, payload.replacementSectionYs());
        assertArrayEquals(new byte[] {0}, payload.decompressedSections());
    }
}
