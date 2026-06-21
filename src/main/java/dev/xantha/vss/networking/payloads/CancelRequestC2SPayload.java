package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

public record CancelRequestC2SPayload(int requestId) {
    public static void encode(CancelRequestC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.requestId);
    }

    public static CancelRequestC2SPayload decode(FriendlyByteBuf buf) {
        return new CancelRequestC2SPayload(buf.readVarInt());
    }
}
