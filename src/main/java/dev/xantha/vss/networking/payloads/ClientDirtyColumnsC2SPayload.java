package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;

public record ClientDirtyColumnsC2SPayload(long[] dirtyPositions) {
    public static void encode(ClientDirtyColumnsC2SPayload payload, FriendlyByteBuf buf) {
        int count = Math.min(payload.dirtyPositions.length, VSSConstants.MAX_CLIENT_DIRTY_COLUMN_HINTS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(payload.dirtyPositions[i]);
        }
    }

    public static ClientDirtyColumnsC2SPayload decode(FriendlyByteBuf buf) {
        int rawLen = buf.readVarInt();
        if (rawLen < 0) {
            throw new IllegalArgumentException("Client dirty column hint count out of range: " + rawLen);
        }

        int len = Math.min(rawLen, VSSConstants.MAX_CLIENT_DIRTY_COLUMN_HINTS);
        long[] positions = new long[len];
        for (int i = 0; i < len; i++) {
            positions[i] = buf.readLong();
        }
        int excess = rawLen - len;
        if (excess > 0) {
            buf.skipBytes((int) Math.min((long) excess * Long.BYTES, buf.readableBytes()));
        }
        return new ClientDirtyColumnsC2SPayload(positions);
    }
}
