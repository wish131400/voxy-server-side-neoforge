package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record HandshakeC2SPayload(int protocolVersion, int capabilities) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HandshakeC2SPayload> TYPE = VSSPayloadCodecs.type("handshake");
    public static final StreamCodec<RegistryFriendlyByteBuf, HandshakeC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(HandshakeC2SPayload::encode, HandshakeC2SPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(HandshakeC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeVarInt(payload.capabilities);
    }

    public static HandshakeC2SPayload decode(FriendlyByteBuf buf) {
        return new HandshakeC2SPayload(buf.readVarInt(), buf.readVarInt());
    }
}
