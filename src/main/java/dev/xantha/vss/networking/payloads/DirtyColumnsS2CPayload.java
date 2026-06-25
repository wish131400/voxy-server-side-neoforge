package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record DirtyColumnsS2CPayload(long[] dirtyPositions, long[] dirtyTimestamps) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DirtyColumnsS2CPayload> TYPE = VSSPayloadCodecs.type("dirty_columns");
    public static final StreamCodec<RegistryFriendlyByteBuf, DirtyColumnsS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(DirtyColumnsS2CPayload::encode, DirtyColumnsS2CPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(DirtyColumnsS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.dirtyPositions.length);
        for (int i = 0; i < payload.dirtyPositions.length; i++) {
            buf.writeLong(payload.dirtyPositions[i]);
            long timestamp = i < payload.dirtyTimestamps.length ? payload.dirtyTimestamps[i] : 0L;
            buf.writeLong(timestamp);
        }
    }

    public static DirtyColumnsS2CPayload decode(FriendlyByteBuf buf) {
        int rawLen = Math.max(buf.readVarInt(), 0);
        int len = Math.min(rawLen, VSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        long[] positions = new long[len];
        long[] timestamps = new long[len];
        for (int i = 0; i < len; i++) {
            positions[i] = buf.readLong();
            timestamps[i] = buf.readLong();
        }
        int excess = rawLen - len;
        if (excess > 0) {
            buf.skipBytes((int) Math.min((long) excess * Long.BYTES * 2L, buf.readableBytes()));
        }
        return new DirtyColumnsS2CPayload(positions, timestamps);
    }
}
