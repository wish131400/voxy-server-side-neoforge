package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

public record BandwidthUpdateC2SPayload(long desiredRate) {
    public static void encode(BandwidthUpdateC2SPayload payload, FriendlyByteBuf buf) {
        buf.writeVarLong(payload.desiredRate);
    }

    public static BandwidthUpdateC2SPayload decode(FriendlyByteBuf buf) {
        return new BandwidthUpdateC2SPayload(buf.readVarLong());
    }
}
