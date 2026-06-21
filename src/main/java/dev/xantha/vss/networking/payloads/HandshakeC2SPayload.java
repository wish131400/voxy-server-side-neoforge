package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

public record HandshakeC2SPayload(int protocolVersion, int capabilities) {
    public static void encode(HandshakeC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeVarInt(payload.capabilities);
    }

    public static HandshakeC2SPayload decode(FriendlyByteBuf buf) {
        return new HandshakeC2SPayload(buf.readVarInt(), buf.readVarInt());
    }
}
