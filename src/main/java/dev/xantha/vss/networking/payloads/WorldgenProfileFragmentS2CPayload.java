package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded application-level fragments, independent of loader/compression framing. */
public record WorldgenProfileFragmentS2CPayload(int transfer, int total, int offset, byte[] data)
        implements CustomPacketPayload {
    public static final int CHUNK_BYTES = 512 * 1024;
    // Covers the existing registry + 64 generator limits, including metadata.
    public static final int MAX_BYTES = 144 * 1024 * 1024;
    public static final Type<WorldgenProfileFragmentS2CPayload> TYPE =
            VSSPayloadCodecs.type("worldgen_profile_fragment");
    public static final StreamCodec<RegistryFriendlyByteBuf, WorldgenProfileFragmentS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(WorldgenProfileFragmentS2CPayload::encode, WorldgenProfileFragmentS2CPayload::decode);

    public WorldgenProfileFragmentS2CPayload {
        if (total <= 0 || total > MAX_BYTES || offset < 0 || data == null
                || data.length == 0 || data.length > CHUNK_BYTES || offset > total - data.length) {
            throw new IllegalArgumentException("Invalid worldgen snapshot fragment");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void encode(WorldgenProfileFragmentS2CPayload value, FriendlyByteBuf buf) {
        buf.writeInt(value.transfer);
        buf.writeVarInt(value.total);
        buf.writeVarInt(value.offset);
        buf.writeByteArray(value.data);
    }

    public static WorldgenProfileFragmentS2CPayload decode(FriendlyByteBuf buf) {
        return new WorldgenProfileFragmentS2CPayload(buf.readInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readByteArray(CHUNK_BYTES));
    }
}
