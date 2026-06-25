package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BandwidthUpdateC2SPayload(long desiredRate) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BandwidthUpdateC2SPayload> TYPE = VSSPayloadCodecs.type("bandwidth_update");
    public static final StreamCodec<RegistryFriendlyByteBuf, BandwidthUpdateC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(BandwidthUpdateC2SPayload::encode, BandwidthUpdateC2SPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(BandwidthUpdateC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarLong(payload.desiredRate);
    }

    public static BandwidthUpdateC2SPayload decode(FriendlyByteBuf buf) {
        return new BandwidthUpdateC2SPayload(buf.readVarLong());
    }
}
