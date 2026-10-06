package dev.xantha.vss.networking.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VSSClientCommandsTest {
    @BeforeAll static void initialize() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "command-tests"));
        net.minecraft.SharedConstants.tryDetectVersion();
    }

    @Test void localHelpAndAliasesNeedNoOperatorPermission() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        VSSClientCommands.registerCommands(dispatcher);
        CommandSourceStack source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO,
                null, 0, "test", Component.literal("test"), null, null);
        for (String input : new String[] {"vssclient", "vssclient help prediction", "vssclient 帮助 地图",
                "vssclient 状态", "vssclient 地图 开启", "vssclient 地图 关闭", "vssclient 地图 重载",
                "vssclient 预测 渲染采集 0.5 0.65", "vssclient 预测 参考采集"}) {
            var parsed = dispatcher.parse(input, source);
            assertFalse(parsed.getReader().canRead(), input + " => " + parsed.getExceptions());
            assertNotNull(parsed.getContext().getCommand(), input);
        }
    }
}
