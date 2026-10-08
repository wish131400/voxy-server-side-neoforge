package dev.xantha.vss.networking.command;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Shared, localized references for the server and local client command trees. */
public final class VSSCommandHelp {
    public static final List<String> SERVER_TOPICS = List.of("stats", "bandwidth", "queue", "request_limits",
            "distance", "farplayers", "dirty", "storage", "generation", "chunky", "disk_cache", "help");
    public static final List<String> CLIENT_TOPICS = List.of("stats", "xaero", "prediction", "help");
    private static final Map<String, String> SERVER_ALIASES = Map.ofEntries(
            Map.entry("状态", "stats"), Map.entry("带宽", "bandwidth"), Map.entry("队列", "queue"),
            Map.entry("请求限速", "request_limits"), Map.entry("距离", "distance"), Map.entry("远处玩家", "farplayers"),
            Map.entry("刷新", "dirty"), Map.entry("存储", "storage"), Map.entry("生成", "generation"),
            Map.entry("磁盘缓存", "disk_cache"), Map.entry("预生成", "chunky"), Map.entry("帮助", "help"));
    private static final Map<String, String> CLIENT_ALIASES = Map.of(
            "状态", "stats", "地图", "xaero", "预测", "prediction", "帮助", "help");
    public static final List<String> SERVER_HELP_TOPICS = java.util.stream.Stream.concat(
            SERVER_TOPICS.stream(), SERVER_ALIASES.keySet().stream().sorted()).toList();
    public static final List<String> CLIENT_HELP_TOPICS = java.util.stream.Stream.concat(
            CLIENT_TOPICS.stream(), CLIENT_ALIASES.keySet().stream().sorted()).toList();

    private VSSCommandHelp() { }

    public static int server(CommandSourceStack source, String requested) {
        return show(source, "vss", "vss.command.help.", SERVER_TOPICS, SERVER_ALIASES, requested);
    }

    public static int client(CommandSourceStack source, String requested) {
        return show(source, "vssclient", "vss.command.client_help.", CLIENT_TOPICS, CLIENT_ALIASES, requested);
    }

    private static int show(CommandSourceStack source, String root, String prefix, List<String> topics,
            Map<String, String> aliases, String requested) {
        String topic = requested == null ? "" : requested.trim().split("\\s+", 2)[0];
        topic = aliases.getOrDefault(topic, topic);
        if (topic.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(prefix + "title").withStyle(ChatFormatting.GOLD), false);
            for (String entry : topics) {
                String helpKey = prefix + translationTopic(entry);
                source.sendSuccess(() -> Component.translatable(helpKey).withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + root + " help " + entry))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("vss.command.help.click")))), false);
            }
            source.sendSuccess(() -> Component.translatable(prefix + "hint").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        if (!topics.contains(topic)) {
            source.sendFailure(Component.translatable("vss.command.help.unknown", requested, "/" + root + " help"));
            return 0;
        }
        String key = prefix + translationTopic(topic);
        source.sendSuccess(() -> Component.translatable(key).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable(key + ".usage"), false);
        source.sendSuccess(() -> Component.translatable(key + ".example").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable(prefix + "permission").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static String translationTopic(String topic) {
        return "farplayers".equals(topic) ? "far_players" : topic;
    }

    /** Copy a literal's children/requirements, preserving exact aliases and argument parsing. */
    public static void alias(CommandNode<CommandSourceStack> parent, String alias, String targetName) {
        if (parent == null) return;
        CommandNode<CommandSourceStack> target = parent.getChild(targetName);
        if (target == null || parent.getChild(alias) != null) return;
        LiteralCommandNode<CommandSourceStack> node = new LiteralCommandNode<>(alias, target.getCommand(),
                target.getRequirement(), target.getRedirect(), target.getRedirectModifier(), target.isFork());
        for (CommandNode<CommandSourceStack> child : target.getChildren()) node.addChild(child);
        parent.addChild(node);
    }

    public static void serverAliases(CommandNode<CommandSourceStack> root) {
        for (String name : SERVER_TOPICS) {
            CommandNode<CommandSourceStack> node = root.getChild(name);
            alias(node, "查看", "get");
            alias(node, "状态", "stats");
            alias(node, "开启", "enable");
            alias(node, "关闭", "disable");
            if (!name.equals("bandwidth")) alias(node, "设置", "set");
            switch (name) {
                case "bandwidth" -> {
                    alias(node, "设置", "set_kbps");
                    alias(node, "设置字节", "set_bytes");
                    alias(node, "设置Kbps", "set_kbps");
                    alias(node, "设置Mbps", "set_mbps");
                    alias(node, "设置MiB", "set_mib");
                }
                case "queue" -> { alias(node, "设置数量", "set_count"); alias(node, "设置MiB", "set_mib"); }
                case "request_limits" -> {
                    alias(node, "设置近处", "set_near"); alias(node, "设置中距", "set_mid");
                    alias(node, "设置远处", "set_far"); alias(node, "设置超远", "set_distant");
                }
                case "farplayers", "dirty" -> alias(node, "设置间隔", "set_interval");
                case "storage" -> alias(node, "设置读盘线程", "set_disk_readers");
                case "generation" -> {
                    alias(node, "设置每玩家", "set_player_concurrency");
                    alias(node, "设置全服", "set_global_concurrency");
                }
                case "chunky" -> {
                    alias(node, "开始", "start"); alias(node, "矩形", "rect"); alias(node, "当前位置", "here");
                    alias(node, "查看", "status"); alias(node, "状态", "status"); alias(node, "暂停", "pause");
                    alias(node, "继续", "resume"); alias(node, "取消", "cancel");
                }
                default -> { }
            }
        }
        // Extend the pre-existing Chinese roots without changing the meaning of their setters.
        for (var entry : SERVER_ALIASES.entrySet()) {
            alias(root, entry.getKey(), entry.getValue());
            CommandNode<CommandSourceStack> target = root.getChild(entry.getValue());
            CommandNode<CommandSourceStack> translated = root.getChild(entry.getKey());
            if (target != translated) for (CommandNode<CommandSourceStack> child : target.getChildren())
                if (translated.getChild(child.getName()) == null) translated.addChild(child);
        }
    }

    public static void clientAliases(CommandNode<CommandSourceStack> root) {
        CommandNode<CommandSourceStack> xaero = root.getChild("xaero");
        alias(xaero, "开启", "enable"); alias(xaero, "关闭", "disable"); alias(xaero, "重载", "reload");
        CommandNode<CommandSourceStack> prediction = root.getChild("prediction");
        alias(prediction, "渲染采集", "rendercapture"); alias(prediction, "参考采集", "capture");
        for (var entry : CLIENT_ALIASES.entrySet()) alias(root, entry.getKey(), entry.getValue());
    }
}
