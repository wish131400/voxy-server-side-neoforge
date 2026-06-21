package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;

public record DirtyColumnsS2CPayload(long[] dirtyPositions) {
    public static void encode(DirtyColumnsS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.dirtyPositions.length);
        for (long position : payload.dirtyPositions) {
            buf.writeLong(position);
        }
    }

    public static DirtyColumnsS2CPayload decode(FriendlyByteBuf buf) {
        int rawLen = Math.max(buf.readVarInt(), 0);
        int len = Math.min(rawLen, VSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        long[] positions = new long[len];
        for (int i = 0; i < len; i++) {
            positions[i] = buf.readLong();
        }
        int excess = rawLen - len;
        if (excess > 0) {
            buf.skipBytes((int) Math.min((long) excess * Long.BYTES, buf.readableBytes()));
        }
        return new DirtyColumnsS2CPayload(positions);
    }
}
