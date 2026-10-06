package dev.xantha.vss.networking.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.xantha.vss.networking.command.VSSCommandHelp;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.networking.server.generation.ChunkyArea;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class VSSChunkyCommands {
    private VSSChunkyCommands() { }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("chunky")
                .executes(context -> VSSCommandHelp.server(context.getSource(), "chunky"))
                .then(squareArguments())
                .then(Commands.literal("start").then(squareArguments()))
                .then(Commands.literal("here")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, ChunkyArea.MAX_RADIUS_BLOCKS))
                                .executes(context -> square(context, net.minecraft.util.Mth.floor(context.getSource().getPosition().x),
                                        net.minecraft.util.Mth.floor(context.getSource().getPosition().z)))))
                .then(Commands.literal("rect")
                        .then(coordinate("x1").then(coordinate("z1").then(coordinate("x2").then(coordinate("z2")
                                .executes(context -> start(context.getSource(), () -> ChunkyArea.rectangle(
                                        integer(context, "x1"), integer(context, "z1"), integer(context, "x2"), integer(context, "z2")))))))))
                .then(Commands.literal("status").executes(context -> VSSServerNetworking.chunky().status(context.getSource())))
                .then(Commands.literal("pause").executes(context -> VSSServerNetworking.chunky().pause(context.getSource())))
                .then(Commands.literal("resume").executes(context -> VSSServerNetworking.chunky().resume(context.getSource())))
                .then(Commands.literal("cancel").executes(context -> VSSServerNetworking.chunky().cancel(context.getSource())));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> squareArguments() {
        return coordinate("x").then(coordinate("z")
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, ChunkyArea.MAX_RADIUS_BLOCKS))
                        .executes(context -> square(context, integer(context, "x"), integer(context, "z")))));
    }

    private static int square(CommandContext<CommandSourceStack> context, int x, int z) {
        return start(context.getSource(), () -> ChunkyArea.square(x, z, integer(context, "radius")));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> coordinate(String name) {
        return Commands.argument(name, IntegerArgumentType.integer(-ChunkyArea.MAX_BLOCK_COORDINATE, ChunkyArea.MAX_BLOCK_COORDINATE));
    }

    private static int integer(CommandContext<CommandSourceStack> context, String name) {
        return IntegerArgumentType.getInteger(context, name);
    }

    private static int start(CommandSourceStack source, java.util.function.Supplier<ChunkyArea> area) {
        try { return VSSServerNetworking.chunky().start(source, area.get()); }
        catch (IllegalArgumentException error) {
            source.sendFailure(Component.translatable(error.getMessage(), ChunkyArea.MAX_RADIUS_BLOCKS, ChunkyArea.MAX_COLUMNS));
            return 0;
        }
    }
}
