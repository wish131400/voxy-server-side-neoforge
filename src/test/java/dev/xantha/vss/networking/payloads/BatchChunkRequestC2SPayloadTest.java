package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class BatchChunkRequestC2SPayloadTest {
    @Test
    void delayedNetworkReadSurvivesScannerBufferReuse() {
        int[] ids = {3, 8, 99};
        long[] positions = {11L, -22L, 99L};
        long[] timestamps = {101L, 202L, 99L};
        boolean[] generation = {true, false, true};
        boolean[] probes = {false, true, true};
        BatchChunkRequestC2SPayload pending = new BatchChunkRequestC2SPayload(
                ids, positions, timestamps, generation, probes, 2);
        java.util.Arrays.fill(ids, 999);
        java.util.Arrays.fill(positions, 999L);
        java.util.Arrays.fill(timestamps, 999L);
        java.util.Arrays.fill(generation, false);
        java.util.Arrays.fill(probes, false);
        // Integrated transport can hand the object to the server without encoding.
        assertArrayEquals(new int[] {3, 8}, pending.requestIds());
        assertArrayEquals(new long[] {11L, -22L}, pending.packedPositions());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BatchChunkRequestC2SPayload.encode(pending, buffer);
            BatchChunkRequestC2SPayload decoded = BatchChunkRequestC2SPayload.decode(buffer);
            assertEquals(2, decoded.count());
            assertArrayEquals(new int[] {3, 8}, decoded.requestIds());
            assertArrayEquals(new long[] {11L, -22L}, decoded.packedPositions());
            assertArrayEquals(new long[] {101L, 202L}, decoded.clientTimestamps());
            assertArrayEquals(new boolean[] {true, false}, decoded.allowGeneration());
            assertArrayEquals(new boolean[] {false, true}, decoded.cacheProbe());
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripPreservesCacheProbeFlags() {
        BatchChunkRequestC2SPayload original = new BatchChunkRequestC2SPayload(
                new int[] {3, 8, 13},
                new long[] {11L, -22L, 33L},
                new long[] {101L, 0L, 303L},
                new boolean[] {false, false, true},
                new boolean[] {true, false, true},
                3);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BatchChunkRequestC2SPayload.encode(original, buffer);
            BatchChunkRequestC2SPayload decoded = BatchChunkRequestC2SPayload.decode(buffer);

            assertEquals(3, decoded.count());
            assertArrayEquals(original.requestIds(), decoded.requestIds());
            assertArrayEquals(original.packedPositions(), decoded.packedPositions());
            assertArrayEquals(original.clientTimestamps(), decoded.clientTimestamps());
            assertArrayEquals(original.allowGeneration(), decoded.allowGeneration());
            assertArrayEquals(original.cacheProbe(), decoded.cacheProbe());
        } finally {
            buffer.release();
        }
    }
}
