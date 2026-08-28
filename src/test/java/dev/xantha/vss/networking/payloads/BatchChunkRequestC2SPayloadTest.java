package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class BatchChunkRequestC2SPayloadTest {
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
