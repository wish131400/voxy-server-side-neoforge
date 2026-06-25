package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BatchChunkRequestC2SPayload(
        int[] requestIds,
        long[] packedPositions,
        long[] clientTimestamps,
        boolean[] allowGeneration,
        int count) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BatchChunkRequestC2SPayload> TYPE = VSSPayloadCodecs.type("batch_chunk_request");
    public static final StreamCodec<RegistryFriendlyByteBuf, BatchChunkRequestC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(BatchChunkRequestC2SPayload::encode, BatchChunkRequestC2SPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(BatchChunkRequestC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.count);
        for (int i = 0; i < payload.count; i++) {
            buf.writeVarInt(payload.requestIds[i]);
            buf.writeLong(payload.packedPositions[i]);
            buf.writeLong(payload.clientTimestamps[i]);
            buf.writeBoolean(payload.allowGeneration[i]);
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
        boolean[] allowGeneration = new boolean[count];
        for (int i = 0; i < count; i++) {
            requestIds[i] = buf.readVarInt();
            packedPositions[i] = buf.readLong();
            clientTimestamps[i] = buf.readLong();
            allowGeneration[i] = buf.readBoolean();
        }
        return new BatchChunkRequestC2SPayload(requestIds, packedPositions, clientTimestamps, allowGeneration, count);
    }
}
