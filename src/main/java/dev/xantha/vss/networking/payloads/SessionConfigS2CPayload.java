package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;

public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        int serverCapabilities,
        int syncOnLoadRateLimitPerPlayer,
        int syncOnLoadConcurrencyLimitPerPlayer,
        int generationRateLimitPerPlayer,
        int generationConcurrencyLimitPerPlayer,
        boolean generationEnabled,
        long playerBandwidthLimit) {
    public static void encode(SessionConfigS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.protocolVersion);
        buf.writeBoolean(payload.enabled);
        buf.writeVarInt(payload.lodDistanceChunks);
        buf.writeVarInt(payload.serverCapabilities);
        buf.writeVarInt(payload.syncOnLoadRateLimitPerPlayer);
        buf.writeVarInt(payload.syncOnLoadConcurrencyLimitPerPlayer);
        buf.writeVarInt(payload.generationRateLimitPerPlayer);
        buf.writeVarInt(payload.generationConcurrencyLimitPerPlayer);
        buf.writeBoolean(payload.generationEnabled);
        buf.writeVarLong(payload.playerBandwidthLimit);
    }

    public static SessionConfigS2CPayload decode(FriendlyByteBuf buf) {
        return new SessionConfigS2CPayload(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarLong());
    }
}
