package dev.xantha.vss.client;

import dev.xantha.vss.config.VSSServerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class VSSClientConfigScreens {
    private VSSClientConfigScreens() {
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(VSSClientConfigScreens::createConfigScreen));
    }

    private static Screen createConfigScreen(Minecraft client, Screen parent) {
        return new VSSServerConfigScreen(parent, VSSServerConfig.CONFIG);
    }
}
