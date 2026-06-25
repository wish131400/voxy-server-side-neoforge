package dev.xantha.vss.networking.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

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
        long playerBandwidthLimit) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SessionConfigS2CPayload> TYPE = VSSPayloadCodecs.type("session_config");
    public static final StreamCodec<RegistryFriendlyByteBuf, SessionConfigS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(SessionConfigS2CPayload::encode, SessionConfigS2CPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

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
