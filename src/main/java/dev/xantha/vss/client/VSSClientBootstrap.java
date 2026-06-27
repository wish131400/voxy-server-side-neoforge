package dev.xantha.vss.client;

import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

public final class VSSClientBootstrap {
    private VSSClientBootstrap() {
    }

    public static void init(ModContainer modContainer) {
        VSSClientConfigScreens.register(modContainer);
        VSSSodiumOptionsEventBridge.register();
        VSSEmbeddiumOptionsEventBridge.register();
        ModCompat.init();
        NeoForge.EVENT_BUS.register(VSSClientNetworking.class);
        NeoForge.EVENT_BUS.register(FarPlayerClientRenderer.class);
    }
}
