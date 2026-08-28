package dev.xantha.vss.networking.client;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class VSSClientCommands {
    private VSSClientCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("vssclient")
                .then(Commands.literal("xaero")
                        .then(Commands.literal("disable")
                                .executes(context -> {
                                    VSSClientNetworking.setXaeroMapBridge(false);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "vss.command.xaero.disabled"), false);
                                    return 1;
                                }))
                        .then(Commands.literal("enable")
                                .executes(context -> {
                                    VSSClientNetworking.setXaeroMapBridge(true);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "vss.command.xaero.enabled"), false);
                                    return 1;
                                }))
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    int cleared = VSSClientNetworking.reloadXaeroMapData();
                                    if (cleared < 0) {
                                        context.getSource().sendFailure(Component.translatable(
                                                "vss.command.xaero_reload.no_session"));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "vss.command.xaero_reload.started", cleared), false);
                                    return 1;
                                }))));
    }
}
