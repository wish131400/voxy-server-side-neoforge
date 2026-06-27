package dev.xantha.vss.networking.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.xantha.vss.config.VSSServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class VSSServerCommands {
    private VSSServerCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vss")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats")
                        .executes(context -> showStats(context.getSource())))
                .then(Commands.literal("状态")
                        .executes(context -> showStats(context.getSource())))
                .then(Commands.literal("bandwidth")
                        .executes(context -> showBandwidth(context.getSource()))
                        .then(Commands.literal("get")
                                .executes(context -> showBandwidth(context.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument(
                                                "bytes_per_second",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER,
                                                        VSSServerConfig.MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER))
                                        .executes(context -> setBandwidthBytes(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "bytes_per_second")))))
                        .then(Commands.literal("set_bytes")
                                .then(Commands.argument(
                                                "bytes_per_second",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_BYTES_PER_SECOND_LIMIT_PER_PLAYER,
                                                        VSSServerConfig.MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER))
                                        .executes(context -> setBandwidthBytes(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "bytes_per_second")))))
                        .then(Commands.literal("set_kbps")
                                .then(Commands.argument(
                                                "kbps",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_BANDWIDTH_KBPS_PER_PLAYER,
                                                        VSSServerConfig.MAX_BANDWIDTH_KBPS_PER_PLAYER))
                                        .executes(context -> setBandwidthKbps(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "kbps")))))
                        .then(Commands.literal("set_mbps")
                                .then(Commands.argument(
                                                "mbps",
                                                IntegerArgumentType.integer(
                                                        1,
                                                        VSSServerConfig.MAX_BANDWIDTH_KBPS_PER_PLAYER / VSSServerConfig.KBPS_PER_MBPS))
                                        .executes(context -> setBandwidthKbps(
                                                context.getSource(),
                                                Math.multiplyExact(
                                                        IntegerArgumentType.getInteger(context, "mbps"),
                                                        VSSServerConfig.KBPS_PER_MBPS)))))
                        .then(Commands.literal("set_mib")
                                .then(Commands.argument(
                                                "mib_per_second",
                                                IntegerArgumentType.integer(
                                                        1,
                                                        VSSServerConfig.MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB))
                                        .executes(context -> setBandwidthMiB(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "mib_per_second"))))))
                .then(Commands.literal("带宽")
                        .executes(context -> showBandwidth(context.getSource()))
                        .then(Commands.literal("查看")
                                .executes(context -> showBandwidth(context.getSource())))
                        .then(Commands.literal("设置")
                                .then(Commands.argument(
                                                "kbps",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_BANDWIDTH_KBPS_PER_PLAYER,
                                                        VSSServerConfig.MAX_BANDWIDTH_KBPS_PER_PLAYER))
                                        .executes(context -> setBandwidthKbps(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "kbps"))))))
                .then(Commands.literal("queue")
                        .executes(context -> showQueue(context.getSource()))
                        .then(Commands.literal("get")
                                .executes(context -> showQueue(context.getSource())))
                        .then(Commands.literal("set_count")
                                .then(Commands.argument("columns", IntegerArgumentType.integer(1, 100000))
                                        .executes(context -> setQueueColumns(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "columns")))))
                        .then(Commands.literal("set_mib")
                                .then(Commands.argument(
                                                "mib",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                                                        VSSServerConfig.MAX_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB))
                                        .executes(context -> setQueueMiB(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "mib"))))))
                .then(Commands.literal("队列")
                        .executes(context -> showQueue(context.getSource()))
                        .then(Commands.literal("查看")
                                .executes(context -> showQueue(context.getSource())))
                        .then(Commands.literal("设置数量")
                                .then(Commands.argument("列数", IntegerArgumentType.integer(1, 100000))
                                        .executes(context -> setQueueColumns(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "列数")))))
                        .then(Commands.literal("设置MiB")
                                .then(Commands.argument(
                                                "MiB",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                                                        VSSServerConfig.MAX_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB))
                                        .executes(context -> setQueueMiB(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "MiB"))))))
                .then(Commands.literal("distance")
                        .executes(context -> showDistance(context.getSource()))
                        .then(Commands.literal("get")
                                .executes(context -> showDistance(context.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument(
                                                "chunks",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_LOD_DISTANCE_CHUNKS,
                                                        VSSServerConfig.MAX_LOD_DISTANCE_CHUNKS))
                                        .executes(context -> setDistance(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "chunks"))))))
                .then(Commands.literal("距离")
                        .executes(context -> showDistance(context.getSource()))
                        .then(Commands.literal("查看")
                                .executes(context -> showDistance(context.getSource())))
                        .then(Commands.literal("设置")
                                .then(Commands.argument(
                                                "区块",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_LOD_DISTANCE_CHUNKS,
                                                        VSSServerConfig.MAX_LOD_DISTANCE_CHUNKS))
                                        .executes(context -> setDistance(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "区块"))))))
                .then(Commands.literal("farplayers")
                        .executes(context -> showFarPlayers(context.getSource()))
                        .then(Commands.literal("get")
                                .executes(context -> showFarPlayers(context.getSource())))
                        .then(Commands.literal("enable")
                                .executes(context -> setFarPlayersEnabled(context.getSource(), true)))
                        .then(Commands.literal("disable")
                                .executes(context -> setFarPlayersEnabled(context.getSource(), false)))
                        .then(Commands.literal("set_interval")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> setFarPlayersInterval(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "ticks"))))))
                .then(Commands.literal("远处玩家")
                        .executes(context -> showFarPlayers(context.getSource()))
                        .then(Commands.literal("查看")
                                .executes(context -> showFarPlayers(context.getSource())))
                        .then(Commands.literal("开启")
                                .executes(context -> setFarPlayersEnabled(context.getSource(), true)))
                        .then(Commands.literal("关闭")
                                .executes(context -> setFarPlayersEnabled(context.getSource(), false)))
                        .then(Commands.literal("设置间隔")
                                .then(Commands.argument("tick", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> setFarPlayersInterval(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "tick"))))))
                .then(Commands.literal("dirty")
                        .executes(context -> showDirtyRefresh(context.getSource()))
                        .then(Commands.literal("get")
                                .executes(context -> showDirtyRefresh(context.getSource())))
                        .then(Commands.literal("set_interval")
                                .then(Commands.argument(
                                                "ticks",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_DIRTY_BROADCAST_INTERVAL_TICKS,
                                                        VSSServerConfig.MAX_DIRTY_BROADCAST_INTERVAL_TICKS))
                                        .executes(context -> setDirtyRefreshInterval(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "ticks"))))))
                .then(Commands.literal("刷新")
                        .executes(context -> showDirtyRefresh(context.getSource()))
                        .then(Commands.literal("查看")
                                .executes(context -> showDirtyRefresh(context.getSource())))
                        .then(Commands.literal("设置间隔")
                                .then(Commands.argument(
                                                "tick",
                                                IntegerArgumentType.integer(
                                                        VSSServerConfig.MIN_DIRTY_BROADCAST_INTERVAL_TICKS,
                                                        VSSServerConfig.MAX_DIRTY_BROADCAST_INTERVAL_TICKS))
                                        .executes(context -> setDirtyRefreshInterval(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "tick"))))))
                .then(Commands.literal("generation")
                        .executes(context -> showGeneration(context.getSource()))
                        .then(Commands.literal("get")
                                .executes(context -> showGeneration(context.getSource())))
                        .then(Commands.literal("stats")
                                .executes(context -> showGenerationStats(context.getSource())))
                        .then(Commands.literal("enable")
                                .executes(context -> setGenerationEnabled(context.getSource(), true)))
                        .then(Commands.literal("disable")
                                .executes(context -> setGenerationEnabled(context.getSource(), false)))
                        .then(Commands.literal("set_player_concurrency")
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> setGenerationPlayerConcurrency(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("set_global_concurrency")
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> setGenerationGlobalConcurrency(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("set_starts_per_tick")
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 256))
                                        .executes(context -> setGenerationStartsPerTick(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("set_completions_per_tick")
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 256))
                                        .executes(context -> setGenerationCompletionsPerTick(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("set_packing_threads")
                                .then(Commands.argument("threads", IntegerArgumentType.integer(1, 8))
                                        .executes(context -> setGenerationPackingThreads(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "threads")))))
                        .then(Commands.literal("set_packing_queue")
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 1024))
                                        .executes(context -> setGenerationPackingQueue(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("set_timeout")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                        .executes(context -> setGenerationTimeout(
                                                context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds"))))))
                .then(Commands.literal("生成")
                        .executes(context -> showGeneration(context.getSource()))
                        .then(Commands.literal("查看")
                                .executes(context -> showGeneration(context.getSource())))
                        .then(Commands.literal("状态")
                                .executes(context -> showGenerationStats(context.getSource())))
                        .then(Commands.literal("开启")
                                .executes(context -> setGenerationEnabled(context.getSource(), true)))
                        .then(Commands.literal("关闭")
                                .executes(context -> setGenerationEnabled(context.getSource(), false)))
                        .then(Commands.literal("设置每玩家")
                                .then(Commands.argument("数量", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> setGenerationPlayerConcurrency(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "数量")))))
                        .then(Commands.literal("设置全服")
                                .then(Commands.argument("数量", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> setGenerationGlobalConcurrency(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "数量")))))
                        .then(Commands.literal("设置每tick启动")
                                .then(Commands.argument("数量", IntegerArgumentType.integer(1, 256))
                                        .executes(context -> setGenerationStartsPerTick(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "数量")))))
                        .then(Commands.literal("设置每tick打包")
                                .then(Commands.argument("数量", IntegerArgumentType.integer(1, 256))
                                        .executes(context -> setGenerationCompletionsPerTick(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "数量")))))
                        .then(Commands.literal("设置打包线程")
                                .then(Commands.argument("线程数", IntegerArgumentType.integer(1, 8))
                                        .executes(context -> setGenerationPackingThreads(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "线程数")))))
                        .then(Commands.literal("设置打包队列")
                                .then(Commands.argument("数量", IntegerArgumentType.integer(1, 1024))
                                        .executes(context -> setGenerationPackingQueue(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "数量")))))
                        .then(Commands.literal("设置超时")
                                .then(Commands.argument("秒", IntegerArgumentType.integer(1, 600))
                                        .executes(context -> setGenerationTimeout(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "秒")))))));
    }

    private static int showStats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("vss.command.stats")
                .withStyle(ChatFormatting.GREEN)
                .append(VSSServerNetworking.diagnosticsComponent()), false);
        return 1;
    }

    private static int showBandwidth(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.bandwidth.show")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.translatable(
                        "vss.command.bandwidth.details",
                        formatBits(config.getPerPlayerBandwidthKbpsRounded()),
                        config.bandwidthBytesPerSecond())), false);
        return config.bandwidthBytesPerSecond();
    }

    private static int setBandwidthBytes(CommandSourceStack source, int bytesPerSecond) {
        VSSServerConfig.CONFIG.setPerPlayerBandwidthBytes(bytesPerSecond);
        return reportUpdated(source);
    }

    private static int setBandwidthKbps(CommandSourceStack source, int kbps) {
        VSSServerConfig.CONFIG.setPerPlayerBandwidthKbps(kbps);
        return reportUpdated(source);
    }

    private static int setBandwidthMiB(CommandSourceStack source, int mibPerSecond) {
        VSSServerConfig.CONFIG.setPerPlayerBandwidthMiB(mibPerSecond);
        return reportUpdated(source);
    }

    private static int reportUpdated(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        VSSServerNetworking.bumpAndRefreshSessionConfigs(source.getServer());
        source.sendSuccess(() -> Component.translatable("vss.command.bandwidth.saved")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.translatable("vss.command.bandwidth.saved.details", formatBits(config.getPerPlayerBandwidthKbpsRounded()))), true);
        return config.bandwidthBytesPerSecond();
    }

    private static int showQueue(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.queue.show")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.translatable(
                        "vss.command.queue.details",
                        config.sendQueueLimitPerPlayer,
                        formatBytes(config.sendQueueBytesLimitPerPlayer))), false);
        return config.sendQueueLimitPerPlayer;
    }

    private static int setQueueColumns(CommandSourceStack source, int columns) {
        VSSServerConfig.CONFIG.sendQueueLimitPerPlayer = columns;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportQueueUpdated(source);
    }

    private static int setQueueMiB(CommandSourceStack source, int mib) {
        VSSServerConfig.CONFIG.sendQueueBytesLimitPerPlayer = Math.multiplyExact(mib, VSSServerConfig.BYTES_PER_MIB);
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportQueueUpdated(source);
    }

    private static int reportQueueUpdated(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        VSSServerNetworking.bumpAndRefreshSessionConfigs(source.getServer());
        source.sendSuccess(() -> Component.translatable("vss.command.queue.saved")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.translatable(
                        "vss.command.queue.details",
                        config.sendQueueLimitPerPlayer,
                        formatBytes(config.sendQueueBytesLimitPerPlayer))), true);
        return config.sendQueueLimitPerPlayer;
    }

    private static int showDistance(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.distance.show")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.translatable(
                        "vss.command.distance.details",
                        config.lodDistanceChunks)), false);
        return config.lodDistanceChunks;
    }

    private static int setDistance(CommandSourceStack source, int chunks) {
        VSSServerConfig.CONFIG.lodDistanceChunks = chunks;
        VSSServerConfig.CONFIG.normalizeAndSave();
        VSSServerNetworking.bumpAndRefreshSessionConfigs(source.getServer());
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.distance.saved")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.translatable(
                        "vss.command.distance.saved.details",
                        config.lodDistanceChunks)), true);
        return config.lodDistanceChunks;
    }

    private static int showFarPlayers(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.far_players.show")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.translatable(
                        "vss.command.far_players.details",
                        config.farPlayerSyncEnabled
                                ? Component.translatable("vss.command.enabled")
                                : Component.translatable("vss.command.disabled"),
                        config.farPlayerSyncIntervalTicks,
                        config.lodDistanceChunks)), false);
        return config.farPlayerSyncEnabled ? 1 : 0;
    }

    private static int setFarPlayersEnabled(CommandSourceStack source, boolean enabled) {
        VSSServerConfig.CONFIG.farPlayerSyncEnabled = enabled;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportFarPlayersUpdated(source);
    }

    private static int setFarPlayersInterval(CommandSourceStack source, int ticks) {
        VSSServerConfig.CONFIG.farPlayerSyncIntervalTicks = ticks;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportFarPlayersUpdated(source);
    }

    private static int reportFarPlayersUpdated(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.far_players.saved")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.translatable(
                        "vss.command.far_players.details",
                        config.farPlayerSyncEnabled
                                ? Component.translatable("vss.command.enabled")
                                : Component.translatable("vss.command.disabled"),
                        config.farPlayerSyncIntervalTicks,
                        config.lodDistanceChunks)), true);
        return config.farPlayerSyncEnabled ? 1 : 0;
    }

    private static int showDirtyRefresh(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.dirty.show")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.translatable(
                        "vss.command.dirty.details",
                        config.dirtyBroadcastIntervalTicks,
                        DirtyColumnBroadcaster.diagnosticsComponent())), false);
        return config.dirtyBroadcastIntervalTicks;
    }

    private static int setDirtyRefreshInterval(CommandSourceStack source, int ticks) {
        VSSServerConfig.CONFIG.dirtyBroadcastIntervalTicks = ticks;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportDirtyRefreshUpdated(source);
    }

    private static int reportDirtyRefreshUpdated(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.dirty.saved")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.translatable(
                        "vss.command.dirty.details",
                        config.dirtyBroadcastIntervalTicks,
                        DirtyColumnBroadcaster.diagnosticsComponent())), true);
        return config.dirtyBroadcastIntervalTicks;
    }

    private static int showGeneration(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        source.sendSuccess(() -> Component.translatable("vss.command.generation.show")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.translatable(
                        "vss.command.generation.details",
                        config.enableChunkGeneration
                                ? Component.translatable("vss.command.enabled")
                                : Component.translatable("vss.command.disabled"),
                        config.generationConcurrencyLimitPerPlayer,
                        config.generationConcurrencyLimitGlobal,
                        config.generationStartsPerTickLimit,
                        config.generationCompletionsPerTickLimit,
                        config.generationPackingThreads,
                        config.generationPackingQueueLimit,
                        config.generationTimeoutSeconds)), false);
        return config.enableChunkGeneration ? 1 : 0;
    }

    private static int showGenerationStats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("vss.command.generation.stats")
                .withStyle(ChatFormatting.GREEN)
                .append(VSSServerNetworking.generationDiagnosticsComponent()), false);
        return 1;
    }

    private static int setGenerationEnabled(CommandSourceStack source, boolean enabled) {
        VSSServerConfig.CONFIG.enableChunkGeneration = enabled;
        VSSServerConfig.CONFIG.normalizeAndSave();
        VSSServerNetworking.bumpAndRefreshSessionConfigs(source.getServer());
        source.sendSuccess(() -> Component.translatable("vss.command.generation.toggle")
                .withStyle(ChatFormatting.YELLOW)
                .append(enabled
                        ? Component.translatable("vss.command.enabled")
                        : Component.translatable("vss.command.disabled"))
                .append(Component.translatable("vss.command.generation.synced")), true);
        return enabled ? 1 : 0;
    }

    private static int setGenerationPlayerConcurrency(CommandSourceStack source, int limit) {
        VSSServerConfig.CONFIG.generationConcurrencyLimitPerPlayer = limit;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportGenerationUpdated(source);
    }

    private static int setGenerationGlobalConcurrency(CommandSourceStack source, int limit) {
        VSSServerConfig.CONFIG.generationConcurrencyLimitGlobal = limit;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportGenerationUpdated(source);
    }

    private static int setGenerationStartsPerTick(CommandSourceStack source, int limit) {
        VSSServerConfig.CONFIG.generationStartsPerTickLimit = limit;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportGenerationUpdated(source);
    }

    private static int setGenerationCompletionsPerTick(CommandSourceStack source, int limit) {
        VSSServerConfig.CONFIG.generationCompletionsPerTickLimit = limit;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportGenerationUpdated(source);
    }

    private static int setGenerationPackingThreads(CommandSourceStack source, int threads) {
        VSSServerConfig.CONFIG.generationPackingThreads = threads;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportGenerationUpdated(source);
    }

    private static int setGenerationPackingQueue(CommandSourceStack source, int limit) {
        VSSServerConfig.CONFIG.generationPackingQueueLimit = limit;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportGenerationUpdated(source);
    }

    private static int setGenerationTimeout(CommandSourceStack source, int seconds) {
        VSSServerConfig.CONFIG.generationTimeoutSeconds = seconds;
        VSSServerConfig.CONFIG.normalizeAndSave();
        return reportGenerationUpdated(source);
    }

    private static int reportGenerationUpdated(CommandSourceStack source) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        VSSServerNetworking.bumpAndRefreshSessionConfigs(source.getServer());
        source.sendSuccess(() -> Component.translatable("vss.command.generation.saved")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.translatable(
                        "vss.command.generation.saved.details",
                        config.generationConcurrencyLimitPerPlayer,
                        config.generationConcurrencyLimitGlobal,
                        config.generationStartsPerTickLimit,
                        config.generationCompletionsPerTickLimit,
                        config.generationPackingThreads,
                        config.generationPackingQueueLimit,
                        config.generationTimeoutSeconds)), true);
        return 1;
    }

    static String formatBytes(int bytesPerSecond) {
        if (bytesPerSecond >= VSSServerConfig.BYTES_PER_MIB) {
            return String.format("%.2f MiB", bytesPerSecond / (double) VSSServerConfig.BYTES_PER_MIB);
        }
        if (bytesPerSecond >= 1024) {
            return String.format("%.2f KiB", bytesPerSecond / 1024.0);
        }
        return bytesPerSecond + " B";
    }

    static String formatBits(int kbps) {
        if (kbps >= VSSServerConfig.KBPS_PER_MBPS) {
            return String.format("%.2f Mbps", kbps / (double) VSSServerConfig.KBPS_PER_MBPS);
        }
        return kbps + " Kbps";
    }
}
