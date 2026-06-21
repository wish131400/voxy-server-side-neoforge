package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;

public record BatchResponseS2CPayload(byte[] responseTypes, int[] requestIds, int count) {
    public static void encode(BatchResponseS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.count);
        for (int i = 0; i < payload.count; i++) {
            buf.writeByte(payload.responseTypes[i]);
            buf.writeVarInt(payload.requestIds[i]);
        }
    }

    public static BatchResponseS2CPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > VSSConstants.MAX_BATCH_RESPONSES) {
            throw new IllegalArgumentException("Batch response count out of range: " + count);
        }
        byte[] responseTypes = new byte[count];
        int[] requestIds = new int[count];
        for (int i = 0; i < count; i++) {
            responseTypes[i] = buf.readByte();
            requestIds[i] = buf.readVarInt();
        }
        return new BatchResponseS2CPayload(responseTypes, requestIds, count);
    }
}
