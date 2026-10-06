package dev.xantha.vss.networking.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VSSServerCommandsTest {
    @BeforeAll static void initialize() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "command-tests"));
        net.minecraft.SharedConstants.tryDetectVersion();
    }

    static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "test", Component.literal("test"), null, null);
    }

    @Test void coordinateCommandsAndChineseAliasesParseCompletely() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        VSSServerCommands.register(dispatcher);
        for (String input : new String[] {"vss", "vss help chunky", "vss 帮助 预生成",
                "vss chunky 0 0 512", "vss chunky start -17 -1 0", "vss 预生成 开始 -17 -1 16",
                "vss chunky rect -256 -256 255 255", "vss 预生成 矩形 255 255 -256 -256",
                "vss chunky here 128", "vss 预生成 当前位置 128", "vss chunky status", "vss 预生成 暂停",
                "vss 预生成 继续", "vss 预生成 取消", "vss 带宽 设置Mbps 20", "vss generation 设置全服 8"}) {
            ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, source(2));
            assertFalse(parsed.getReader().canRead(), input + " => " + parsed.getExceptions());
            assertNotNull(parsed.getContext().getCommand(), input);
        }
    }

    @Test void administrativeRootRejectsNonOperatorsIncludingChineseAliases() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        VSSServerCommands.register(dispatcher);
        assertFalse(dispatcher.getRoot().getChild("vss").canUse(source(1)));
        assertTrue(dispatcher.getRoot().getChild("vss").canUse(source(2)));
        for (String input : new String[] {"vss chunky 0 0 512", "vss 预生成 0 0 512", "vss help"})
            assertTrue(dispatcher.parse(input, source(1)).getReader().canRead(), input);
    }

    @Test void invalidRadiusCannotBecomeAnExecutableJob() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        VSSServerCommands.register(dispatcher);
        for (String input : new String[] {"vss chunky 0 0 -1", "vss chunky 0 0 8193", "vss chunky rect 0 0 100"})
            assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                    () -> dispatcher.execute(input, source(2)), input);
    }

    @Test void concurrencySettingsHaveBeenRemovedFromBothCommandTrees() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        VSSServerCommands.register(dispatcher);
        for (String root : new String[] {"chunky", "预生成"}) {
            var command = dispatcher.getRoot().getChild("vss").getChild(root);
            for (String removed : new String[] {"concurrency", "并发", "设置并发"})
                assertNull(command.getChild(removed), root + " " + removed);
        }
    }

    @Test void helpAndUnknownTopicsProduceUsableFeedback() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        VSSServerCommands.register(dispatcher);
        assertEquals(1, dispatcher.execute("vss", source(2)));
        assertEquals(1, dispatcher.execute("vss help chunky", source(2)));
        assertEquals(1, dispatcher.execute("vss 帮助 预生成", source(2)));
        assertEquals(0, dispatcher.execute("vss help unknown", source(2)));
        assertEquals(0, dispatcher.execute("vss unknown", source(2)));
    }
}
