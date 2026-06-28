package dev.xantha.vss.client;

import dev.xantha.vss.config.VSSServerConfig;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public final class VSSClientConfigScreens {
    private VSSClientConfigScreens() {
    }

    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, VSSClientConfigScreens::createConfigScreen);
    }

    private static Screen createConfigScreen(ModContainer container, Screen parent) {
        Screen sodiumScreen = VSSVoxyOptionsIntegration.createSodiumConfigScreen(parent);
        if (sodiumScreen != null) {
            return sodiumScreen;
        }
        return new VSSServerConfigScreen(parent, VSSServerConfig.CONFIG);
    }
}
