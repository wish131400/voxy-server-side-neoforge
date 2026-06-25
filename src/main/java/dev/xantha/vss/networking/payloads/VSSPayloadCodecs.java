package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class VSSPayloadCodecs {
    private VSSPayloadCodecs() {
    }

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(VSSConstants.MOD_ID, path));
    }

    static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> codec(
            java.util.function.BiConsumer<T, RegistryFriendlyByteBuf> encoder,
            java.util.function.Function<RegistryFriendlyByteBuf, T> decoder) {
        return StreamCodec.ofMember(encoder::accept, decoder::apply);
    }
}
