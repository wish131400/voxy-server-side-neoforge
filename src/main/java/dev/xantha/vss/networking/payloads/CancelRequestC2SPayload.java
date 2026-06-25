package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record CancelRequestC2SPayload(int requestId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CancelRequestC2SPayload> TYPE = VSSPayloadCodecs.type("cancel_request");
    public static final StreamCodec<RegistryFriendlyByteBuf, CancelRequestC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(CancelRequestC2SPayload::encode, CancelRequestC2SPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(CancelRequestC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.requestId);
    }

    public static CancelRequestC2SPayload decode(FriendlyByteBuf buf) {
        return new CancelRequestC2SPayload(buf.readVarInt());
    }
}
