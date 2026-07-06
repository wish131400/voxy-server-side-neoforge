package dev.xantha.vss;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.VSSNetworking;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import dev.xantha.vss.networking.server.broadcast.FarPlayerBroadcaster;
import dev.xantha.vss.networking.server.command.VSSServerCommands;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(VSSConstants.MOD_ID)
public final class VSSMod {
    private static final String CLIENT_BOOTSTRAP_CLASS = "dev.xantha.vss.client.VSSClientBootstrap";

    public VSSMod(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, VSSNetworking::register);
        NeoForge.EVENT_BUS.register(VSSServerNetworking.class);
        NeoForge.EVENT_BUS.register(FarPlayerBroadcaster.class);
        NeoForge.EVENT_BUS.register(VSSServerCommands.class);
        if (FMLEnvironment.dist.isClient()) {
            initClient(modContainer);
        }
    }

    private static void initClient(ModContainer modContainer) {
        try {
            Class<?> bootstrapClass = Class.forName(CLIENT_BOOTSTRAP_CLASS);
            Method init = bootstrapClass.getMethod("init", ModContainer.class);
            init.invoke(null, modContainer);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to initialize VSS client hooks", cause);
        } catch (ReflectiveOperationException e) {
            VSSLogger.error("Failed to initialize VSS client hooks", e);
        }
    }
}
