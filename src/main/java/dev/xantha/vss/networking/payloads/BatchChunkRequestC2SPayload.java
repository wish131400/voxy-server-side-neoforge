package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;

public record BatchChunkRequestC2SPayload(int[] requestIds, long[] packedPositions, long[] clientTimestamps, int count) {
    public static void encode(BatchChunkRequestC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.count);
        for (int i = 0; i < payload.count; i++) {
            buf.writeVarInt(payload.requestIds[i]);
            buf.writeLong(payload.packedPositions[i]);
            buf.writeLong(payload.clientTimestamps[i]);
        }
    }

    public static BatchChunkRequestC2SPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > VSSConstants.MAX_BATCH_CHUNK_REQUESTS) {
            throw new IllegalArgumentException("Batch chunk request count out of range: " + count);
        }
        int[] requestIds = new int[count];
        long[] packedPositions = new long[count];
        long[] clientTimestamps = new long[count];
        for (int i = 0; i < count; i++) {
            requestIds[i] = buf.readVarInt();
            packedPositions[i] = buf.readLong();
            clientTimestamps[i] = buf.readLong();
        }
        return new BatchChunkRequestC2SPayload(requestIds, packedPositions, clientTimestamps, count);
    }
}
