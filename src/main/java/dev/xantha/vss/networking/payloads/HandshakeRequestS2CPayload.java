package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record HandshakeRequestS2CPayload() implements CustomPacketPayload {
    public static final Type<HandshakeRequestS2CPayload> TYPE = VSSPayloadCodecs.type("handshake_request");
    public static final StreamCodec<RegistryFriendlyByteBuf, HandshakeRequestS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(HandshakeRequestS2CPayload::encode, HandshakeRequestS2CPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(HandshakeRequestS2CPayload payload, FriendlyByteBuf buf) {
    }

    public static HandshakeRequestS2CPayload decode(FriendlyByteBuf buf) {
        return new HandshakeRequestS2CPayload();
    }
}
