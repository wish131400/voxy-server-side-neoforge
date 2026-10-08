package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Dedicated servers never resolve client classes. LAN hosts keep the shared disk path. */
final class IntegratedPregenBridge {
    private static final String CLIENT = "dev.xantha.vss.networking.client.VSSClientNetworking";

    static boolean supports(MinecraftServer server) {
        if (server.isDedicatedServer()) return false;
        try {
            return Boolean.TRUE.equals(Class.forName(CLIENT).getMethod("isLocalPregenServer", MinecraftServer.class).invoke(null, server));
        } catch (ReflectiveOperationException e) { return false; }
    }

    static boolean offer(MinecraftServer server, ResourceKey<Level> dimension, EncodedColumnData data,
            BooleanSupplier valid, Consumer<Boolean> completed) {
        if (server.isDedicatedServer()) return false;
        try {
            return Boolean.TRUE.equals(Class.forName(CLIENT).getMethod("tryImportLocalPregen",
                    MinecraftServer.class, ResourceKey.class, EncodedColumnData.class, BooleanSupplier.class, Consumer.class)
                    .invoke(null, server, dimension, data, valid, completed));
        } catch (ReflectiveOperationException e) {
            VSSLogger.warn("Local pregen bridge unavailable; using disk cache: " + e.getMessage());
            return false;
        }
    }
}
