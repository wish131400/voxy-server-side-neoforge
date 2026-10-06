package dev.xantha.vss.networking.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xantha.vss.networking.command.VSSCommandHelp;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class VSSClientCommands {
    private VSSClientCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.register(Commands.literal("vssclient")
                .executes(context -> VSSCommandHelp.client(context.getSource(), ""))
                .then(helpCommand())
                .then(Commands.literal("stats")
                        .executes(context -> showStats(context.getSource()))
                        .then(Commands.literal("details").executes(context -> {
                            context.getSource().sendSuccess(
                                    () -> Component.translatable(
                                            "vss.command.client_stats",
                                            VSSClientNetworking.diagnostics()),
                                    false);
                            return 1;
                        })))
                .then(Commands.literal("prediction")
                        .executes(context -> VSSCommandHelp.client(context.getSource(), "prediction"))
                        .then(Commands.literal("rendercapture")
                                .executes(context -> captureRender(context.getSource(), 0.5, 0.65))
                                .then(Commands.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1))
                                        .then(Commands.argument("y", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1))
                                                .executes(context -> captureRender(context.getSource(),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "x"),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "y"))))))
                        .then(Commands.literal("capture")
                                .executes(context -> {
                                    var source = context.getSource();
                                    source.sendSuccess(() -> Component.translatable("vss.command.reference_capture.started"), false);
                                    dev.xantha.vss.client.prediction.RustReferenceExport.start().whenComplete((path, failure) ->
                                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                                if (failure == null) source.sendSuccess(() -> Component.translatable("vss.command.reference_capture.done", path.toString()), false);
                                                else source.sendFailure(Component.translatable("vss.command.reference_capture.failed", failure.getMessage()));
                                            }));
                                    return 1;
                                })))
                .then(Commands.literal("xaero")
                        .executes(context -> VSSCommandHelp.client(context.getSource(), "xaero"))
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
                                })))
                .then(Commands.argument("unknown_command", StringArgumentType.word())
                        .executes(context -> VSSCommandHelp.client(context.getSource(),
                                StringArgumentType.getString(context, "unknown_command")))));
        VSSCommandHelp.clientAliases(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> helpCommand() {
        return Commands.literal("help")
                .executes(context -> VSSCommandHelp.client(context.getSource(), ""))
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                VSSCommandHelp.CLIENT_HELP_TOPICS, builder))
                        .executes(context -> VSSCommandHelp.client(context.getSource(),
                                StringArgumentType.getString(context, "command"))));
    }

    private static int captureRender(net.minecraft.commands.CommandSourceStack source, double x, double y) {
        var result = dev.xantha.vss.client.prediction.PredictionRenderCapture.request(x, y);
        if (!result.isCompletedExceptionally()) source.sendSuccess(() -> Component.translatable("vss.command.render_capture.started"), false);
        result.whenComplete((path, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (failure == null) source.sendSuccess(() -> Component.translatable("vss.command.render_capture.done", path.toString()), false);
            else source.sendFailure(Component.translatable("vss.command.render_capture.failed", failure.getMessage()));
        }));
        return result.isCompletedExceptionally() ? 0 : 1;
    }

    private static int showStats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("vss.command.client_stats.summary",
                Component.translatable(VSSClientNetworking.isServerEnabled() ? "vss.command.enabled" : "vss.command.disabled"),
                VSSClientNetworking.getEffectiveLodDistanceChunks(), VSSClientNetworking.getColumnsReceived(),
                String.format(java.util.Locale.ROOT, "%.2f", VSSClientNetworking.getBytesReceived() / 1048576.0),
                VSSClientNetworking.getColumnsDropped(),
                Component.translatable(VSSClientNetworking.isPredictionActive() ? "vss.command.enabled" : "vss.command.disabled")), false);
        return 1;
    }
}
