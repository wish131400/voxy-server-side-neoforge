package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;

public record ServerIdentityConfigurationTask(
        ServerConfigurationPacketListener listener) implements ICustomConfigurationTask {
    public static final Type TYPE = new Type(
            ResourceLocation.fromNamespaceAndPath(VSSConstants.MOD_ID, "server_identity"));

    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        sender.accept(ServerIdentityS2CPayload.fromConfig());
        listener.finishCurrentTask(TYPE);
    }

    @Override
    public Type type() {
        return TYPE;
    }
}
