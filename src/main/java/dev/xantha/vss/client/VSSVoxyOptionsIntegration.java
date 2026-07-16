package dev.xantha.vss.client;

import com.google.common.collect.ImmutableList;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

public final class VSSVoxyOptionsIntegration {
    private static final String OLD_OPTION_PAGE = "net.caffeinemc.mods.sodium.client.gui.options.OptionPage";
    private static final String OLD_OPTION_GROUP = "net.caffeinemc.mods.sodium.client.gui.options.OptionGroup";
    private static final String OLD_OPTION_IMPL = "net.caffeinemc.mods.sodium.client.gui.options.OptionImpl";
    private static final String OLD_OPTION_IMPACT = "net.caffeinemc.mods.sodium.client.gui.options.OptionImpact";
    private static final String OLD_OPTION_STORAGE = "net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage";
    private static final String OLD_OPTION = "net.caffeinemc.mods.sodium.client.gui.options.Option";
    private static final String OLD_SLIDER_CONTROL = "net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl";
    private static final String OLD_TICK_BOX_CONTROL = "net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl";
    private static final String OLD_VALUE_FORMATTER = "net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatter";

    private static final String SODIUM_OPTIONS_API = "toni.sodiumoptionsapi.api.OptionGUIConstruction";
    private static final String SODIUM08_CONFIG_ENTRY_POINT = "net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint";
    private static final String SODIUM08_CONFIG_MANAGER = "net.caffeinemc.mods.sodium.client.config.ConfigManager";
    private static final String SODIUM08_STORAGE_HANDLER = "net.caffeinemc.mods.sodium.api.config.StorageEventHandler";
    private static final String SODIUM08_OPTION_IMPACT = "net.caffeinemc.mods.sodium.api.config.option.OptionImpact";
    private static final String SODIUM08_VALUE_FORMATTER = "net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter";

    private VSSVoxyOptionsIntegration() {
    }

    public static boolean isSodiumPresent() {
        return classExists(OLD_OPTION_PAGE) || classExists(SODIUM08_CONFIG_ENTRY_POINT);
    }

    public static Screen createSodiumConfigScreen(Screen parent) {
        Screen sodium08Screen = createSodium08ConfigScreen(parent);
        if (sodium08Screen != null) {
            return sodium08Screen;
        }
        return createOldSodiumConfigScreen(parent);
    }

    public static boolean registerSodiumOptionsApiBridge() {
        if (!classExists(OLD_OPTION_PAGE) || !classExists(SODIUM_OPTIONS_API)) {
            return false;
        }

        try {
            Class<?> eventInterface = Class.forName(SODIUM_OPTIONS_API);
            Field eventField = eventInterface.getField("EVENT");
            Object event = eventField.get(null);
            Object listener = proxy(eventInterface, (proxy, method, args) -> {
                if ("onGroupConstruction".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof List<?> pages) {
                    addPage(pages);
                    return null;
                }
                return objectMethod(proxy, method, args, "VSS Sodium Options API listener");
            });

            invokeByName(event, "register", listener);
            VSSLogger.info("Registered Sodium Options API bridge for VSS options");
            return true;
        } catch (Throwable t) {
            VSSLogger.warn("Failed to register Sodium Options API bridge for VSS options", t);
            return false;
        }
    }

    public static boolean registerSodium08ConfigBridge() {
        if (!classExists(SODIUM08_CONFIG_ENTRY_POINT) || !classExists(SODIUM08_CONFIG_MANAGER)) {
            return false;
        }

        try {
            Class<?> entryPointInterface = Class.forName(SODIUM08_CONFIG_ENTRY_POINT);
            Object entryPoint = proxy(entryPointInterface, (proxy, method, args) -> {
                if ("registerConfigLate".equals(method.getName()) && args != null && args.length == 1) {
                    addSodium08Config(args[0]);
                    return null;
                }
                if ("registerConfigEarly".equals(method.getName())) {
                    return null;
                }
                return objectMethod(proxy, method, args, "VSS Sodium 0.8 config entry point");
            });

            Supplier<Object> supplier = () -> entryPoint;
            Class<?> configManager = Class.forName(SODIUM08_CONFIG_MANAGER);
            Method register = configManager.getMethod("registerConfigEntryPoint", Supplier.class, String.class);
            register.invoke(null, supplier, VSSConstants.MOD_ID);
            VSSLogger.info("Registered Sodium 0.8 config bridge for VSS options");
            return true;
        } catch (Throwable t) {
            VSSLogger.warn("Failed to register Sodium 0.8 config bridge for VSS options", t);
            return false;
        }
    }

    public static void addPage(Object sodiumOptionsScreen) {
        try {
            Field pagesField = findPagesField(sodiumOptionsScreen.getClass());
            if (pagesField == null) {
                return;
            }
            pagesField.setAccessible(true);
            Object pages = pagesField.get(sodiumOptionsScreen);
            if (pages instanceof List<?> list) {
                addPage(list);
            }
        } catch (Throwable e) {
            VSSLogger.warn("Failed to add VSS options page to Sodium options screen", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addPage(List<?> pages) {
        if (pages == null || !classExists(OLD_OPTION_PAGE)) {
            return;
        }

        try {
            Object page = oldPage();
            if (page == null) {
                return;
            }

            String name = pageName(page);
            if (containsPageNamed(pages, name)) {
                return;
            }

            int insertAt = findVoxyPageIndex(pages) + 1;
            if (insertAt <= 0) {
                insertAt = pages.size();
            }

            ((List) pages).add(insertAt, page);
            VSSLogger.info("Added Voxy Server Side options page to Sodium options");
        } catch (Throwable e) {
            VSSLogger.warn("Failed to build VSS options page; leaving video settings unchanged", e);
        }
    }

    private static void addSodium08Config(Object configBuilder) {
        try {
            Object modOptions = invokeByName(configBuilder, "registerModOptions", VSSConstants.MOD_ID, "Voxy Server Side", modVersion());
            Object page = invokeByName(configBuilder, "createOptionPage");
            invokeByName(page, "setName", Component.translatable("vss.voxy_options.title"));

            invokeByName(page, "addOptionGroup", sodium08Group(
                    configBuilder,
                    "vss.voxy_options.group.client",
                    sodium08BooleanOption(
                            configBuilder,
                            "receive_server_lods",
                            "vss.voxy_options.receive_server_lods",
                            "vss.voxy_options.receive_server_lods.tooltip",
                            "MEDIUM",
                            true,
                            value -> VSSClientConfig.CONFIG.receiveServerLods = value,
                            () -> VSSClientConfig.CONFIG.receiveServerLods,
                            VSSVoxyOptionsIntegration::saveClientConfig),
                    sodium08BooleanOption(
                            configBuilder,
                            "off_thread_processing",
                            "vss.voxy_options.off_thread_processing",
                            "vss.voxy_options.off_thread_processing.tooltip",
                            "LOW",
                            true,
                            value -> VSSClientConfig.CONFIG.offThreadSectionProcessing = value,
                            () -> VSSClientConfig.CONFIG.offThreadSectionProcessing,
                            VSSVoxyOptionsIntegration::saveClientConfig)));

            invokeByName(page, "addOptionGroup", sodium08Group(
                    configBuilder,
                    "vss.voxy_options.group.client_limits",
                    sodium08IntOption(
                            configBuilder,
                            "client_lod_distance",
                            "vss.voxy_options.client_lod_distance",
                            "vss.voxy_options.client_lod_distance.tooltip",
                            "LOW",
                            0,
                            0,
                            512,
                            16,
                            value -> VSSClientConfig.CONFIG.lodDistanceChunks = value,
                            () -> VSSClientConfig.CONFIG.lodDistanceChunks,
                            VSSVoxyOptionsIntegration::formatChunksAuto,
                            VSSVoxyOptionsIntegration::saveClientConfig),
                    sodium08IntOption(
                            configBuilder,
                            "desired_bandwidth",
                            "vss.voxy_options.desired_bandwidth",
                            "vss.voxy_options.desired_bandwidth.tooltip",
                            "LOW",
                            0,
                            0,
                            100000,
                            1,
                            value -> {
                                VSSClientConfig.CONFIG.desiredBandwidthKbps = value;
                                Minecraft.getInstance().execute(VSSClientNetworking::sendBandwidthPreference);
                            },
                            () -> VSSClientConfig.CONFIG.desiredBandwidthKbps,
                            VSSVoxyOptionsIntegration::formatKbpsAuto,
                            VSSVoxyOptionsIntegration::saveClientConfig)));

            if (canEditLocalServerConfig()) {
                invokeByName(page, "addOptionGroup", sodium08Group(
                    configBuilder,
                    "vss.voxy_options.group.server",
                    sodium08BooleanOption(
                            configBuilder,
                            "server_sync",
                            "vss.voxy_options.server_sync",
                            "vss.voxy_options.server_sync.tooltip",
                            "HIGH",
                            true,
                            value -> VSSServerConfig.CONFIG.enabled = value,
                            () -> VSSServerConfig.CONFIG.enabled,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08BooleanOption(
                            configBuilder,
                            "generation",
                            "vss.voxy_options.generation",
                            "vss.voxy_options.generation.tooltip",
                            "HIGH",
                            true,
                            value -> VSSServerConfig.CONFIG.enableChunkGeneration = value,
                            () -> VSSServerConfig.CONFIG.enableChunkGeneration,
                            VSSVoxyOptionsIntegration::saveServerConfig)));

                invokeByName(page, "addOptionGroup", sodium08Group(
                    configBuilder,
                    "vss.voxy_options.group.far_players",
                    sodium08BooleanOption(
                            configBuilder,
                            "far_player_sync",
                            "vss.voxy_options.far_player_sync",
                            "vss.voxy_options.far_player_sync.tooltip",
                            "LOW",
                            true,
                            value -> VSSServerConfig.CONFIG.farPlayerSyncEnabled = value,
                            () -> VSSServerConfig.CONFIG.farPlayerSyncEnabled,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "far_player_sync_interval",
                            "vss.voxy_options.far_player_sync_interval",
                            "vss.voxy_options.far_player_sync_interval.tooltip",
                            "LOW",
                            10,
                            1,
                            100,
                            1,
                            value -> VSSServerConfig.CONFIG.farPlayerSyncIntervalTicks = value,
                            () -> VSSServerConfig.CONFIG.farPlayerSyncIntervalTicks,
                            VSSVoxyOptionsIntegration::formatTicks,
                            VSSVoxyOptionsIntegration::saveServerConfig)));

                invokeByName(page, "addOptionGroup", sodium08Group(
                    configBuilder,
                    "vss.voxy_options.group.server_limits",
                    sodium08IntOption(
                            configBuilder,
                            "server_lod_distance",
                            "vss.voxy_options.server_lod_distance",
                            "vss.voxy_options.server_lod_distance.tooltip",
                            "MEDIUM",
                            128,
                            VSSServerConfig.MIN_LOD_DISTANCE_CHUNKS,
                            VSSServerConfig.MAX_LOD_DISTANCE_CHUNKS,
                            1,
                            value -> VSSServerConfig.CONFIG.lodDistanceChunks = value,
                            () -> VSSServerConfig.CONFIG.lodDistanceChunks,
                            VSSVoxyOptionsIntegration::formatChunks,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "server_bandwidth",
                            "vss.voxy_options.server_bandwidth",
                            "vss.voxy_options.server_bandwidth.tooltip",
                            "MEDIUM",
                            500,
                            VSSServerConfig.MIN_BANDWIDTH_KBPS_PER_PLAYER,
                            VSSServerConfig.MAX_BANDWIDTH_KBPS_PER_PLAYER,
                            1,
                            VSSServerConfig.CONFIG::setPerPlayerBandwidthKbpsUnsaved,
                            VSSServerConfig.CONFIG::getPerPlayerBandwidthKbpsRounded,
                            VSSVoxyOptionsIntegration::formatKbps,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "server_queue_count",
                            "vss.voxy_options.server_queue_count",
                            "vss.voxy_options.server_queue_count.tooltip",
                            "MEDIUM",
                            256,
                            1,
                            100000,
                            1,
                            value -> VSSServerConfig.CONFIG.sendQueueLimitPerPlayer = value,
                            () -> VSSServerConfig.CONFIG.sendQueueLimitPerPlayer,
                            VSSVoxyOptionsIntegration::formatColumns,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "server_queue_memory",
                            "vss.voxy_options.server_queue_memory",
                            "vss.voxy_options.server_queue_memory.tooltip",
                            "HIGH",
                            16,
                            VSSServerConfig.MIN_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                            VSSServerConfig.MAX_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                            4,
                            value -> VSSServerConfig.CONFIG.sendQueueBytesLimitPerPlayer = Math.multiplyExact(value, VSSServerConfig.BYTES_PER_MIB),
                            VSSServerConfig.CONFIG::getSendQueueBytesMiBRounded,
                            VSSVoxyOptionsIntegration::formatMiB,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "sync_near_rate",
                            "vss.voxy_options.sync_near_rate",
                            "vss.voxy_options.sync_near_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.DEFAULT_NEAR_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            value -> VSSServerConfig.CONFIG.nearSyncRateLimitPerTick = value,
                            () -> VSSServerConfig.CONFIG.nearSyncRateLimitPerTick,
                            VSSVoxyOptionsIntegration::formatNearRequestsPerTick,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "sync_mid_rate",
                            "vss.voxy_options.sync_mid_rate",
                            "vss.voxy_options.sync_mid_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.DEFAULT_MID_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            value -> VSSServerConfig.CONFIG.midSyncRateLimitPerTick = value,
                            () -> VSSServerConfig.CONFIG.midSyncRateLimitPerTick,
                            VSSVoxyOptionsIntegration::formatRequestsPerTick,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "sync_far_rate",
                            "vss.voxy_options.sync_far_rate",
                            "vss.voxy_options.sync_far_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.DEFAULT_FAR_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            value -> VSSServerConfig.CONFIG.farSyncRateLimitPerTick = value,
                            () -> VSSServerConfig.CONFIG.farSyncRateLimitPerTick,
                            VSSVoxyOptionsIntegration::formatRequestsPerTick,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "sync_distant_rate",
                            "vss.voxy_options.sync_distant_rate",
                            "vss.voxy_options.sync_distant_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.DEFAULT_DISTANT_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            value -> VSSServerConfig.CONFIG.distantSyncRateLimitPerTick = value,
                            () -> VSSServerConfig.CONFIG.distantSyncRateLimitPerTick,
                            VSSVoxyOptionsIntegration::formatRequestsPerTick,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "dirty_broadcast_interval",
                            "vss.voxy_options.dirty_broadcast_interval",
                            "vss.voxy_options.dirty_broadcast_interval.tooltip",
                            "MEDIUM",
                            10,
                            VSSServerConfig.MIN_DIRTY_BROADCAST_INTERVAL_TICKS,
                            VSSServerConfig.MAX_DIRTY_BROADCAST_INTERVAL_TICKS,
                            1,
                            value -> VSSServerConfig.CONFIG.dirtyBroadcastIntervalTicks = value,
                            () -> VSSServerConfig.CONFIG.dirtyBroadcastIntervalTicks,
                            VSSVoxyOptionsIntegration::formatTicks,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "disk_reader_threads",
                            "vss.voxy_options.disk_reader_threads",
                            "vss.voxy_options.disk_reader_threads.tooltip",
                            "MEDIUM",
                            VSSServerConfig.DEFAULT_DISK_READER_THREADS,
                            VSSServerConfig.MIN_DISK_READER_THREADS,
                            VSSServerConfig.MAX_DISK_READER_THREADS,
                            1,
                            value -> VSSServerConfig.CONFIG.diskReaderThreads = value,
                            () -> VSSServerConfig.CONFIG.diskReaderThreads,
                            VSSVoxyOptionsIntegration::formatThreads,
                            VSSVoxyOptionsIntegration::saveServerConfig)));

                invokeByName(page, "addOptionGroup", sodium08Group(
                    configBuilder,
                    "vss.voxy_options.group.generation",
                    sodium08IntOption(
                            configBuilder,
                            "generation_player_concurrency",
                            "vss.voxy_options.generation_player_concurrency",
                            "vss.voxy_options.generation_player_concurrency.tooltip",
                            "HIGH",
                            4,
                            1,
                            1000,
                            1,
                            value -> VSSServerConfig.CONFIG.generationConcurrencyLimitPerPlayer = value,
                            () -> VSSServerConfig.CONFIG.generationConcurrencyLimitPerPlayer,
                            VSSVoxyOptionsIntegration::formatColumns,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "generation_global_concurrency",
                            "vss.voxy_options.generation_global_concurrency",
                            "vss.voxy_options.generation_global_concurrency.tooltip",
                            "HIGH",
                            32,
                            1,
                            1000,
                            1,
                            value -> VSSServerConfig.CONFIG.generationConcurrencyLimitGlobal = value,
                            () -> VSSServerConfig.CONFIG.generationConcurrencyLimitGlobal,
                            VSSVoxyOptionsIntegration::formatColumns,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "generation_starts_per_tick",
                            "vss.voxy_options.generation_starts_per_tick",
                            "vss.voxy_options.generation_starts_per_tick.tooltip",
                            "HIGH",
                            2,
                            1,
                            256,
                            1,
                            value -> VSSServerConfig.CONFIG.generationStartsPerTickLimit = value,
                            () -> VSSServerConfig.CONFIG.generationStartsPerTickLimit,
                            VSSVoxyOptionsIntegration::formatColumns,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "generation_completions_per_tick",
                            "vss.voxy_options.generation_completions_per_tick",
                            "vss.voxy_options.generation_completions_per_tick.tooltip",
                            "HIGH",
                            4,
                            1,
                            256,
                            1,
                            value -> VSSServerConfig.CONFIG.generationCompletionsPerTickLimit = value,
                            () -> VSSServerConfig.CONFIG.generationCompletionsPerTickLimit,
                            VSSVoxyOptionsIntegration::formatColumns,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "generation_packing_threads",
                            "vss.voxy_options.generation_packing_threads",
                            "vss.voxy_options.generation_packing_threads.tooltip",
                            "HIGH",
                            2,
                            1,
                            8,
                            1,
                            value -> VSSServerConfig.CONFIG.generationPackingThreads = value,
                            () -> VSSServerConfig.CONFIG.generationPackingThreads,
                            VSSVoxyOptionsIntegration::formatThreads,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "generation_packing_queue",
                            "vss.voxy_options.generation_packing_queue",
                            "vss.voxy_options.generation_packing_queue.tooltip",
                            "HIGH",
                            32,
                            1,
                            1024,
                            1,
                            value -> VSSServerConfig.CONFIG.generationPackingQueueLimit = value,
                            () -> VSSServerConfig.CONFIG.generationPackingQueueLimit,
                            VSSVoxyOptionsIntegration::formatColumns,
                            VSSVoxyOptionsIntegration::saveServerConfig),
                    sodium08IntOption(
                            configBuilder,
                            "generation_timeout",
                            "vss.voxy_options.generation_timeout",
                            "vss.voxy_options.generation_timeout.tooltip",
                            "MEDIUM",
                            45,
                            1,
                            600,
                            1,
                            value -> VSSServerConfig.CONFIG.generationTimeoutSeconds = value,
                            () -> VSSServerConfig.CONFIG.generationTimeoutSeconds,
                            VSSVoxyOptionsIntegration::formatSeconds,
                            VSSVoxyOptionsIntegration::saveServerConfig)));
            }

            invokeByName(modOptions, "addPage", page);
            VSSLogger.info("Registered VSS options page with Sodium 0.8 config API");
        } catch (Throwable t) {
            VSSLogger.warn("Failed to add VSS options to Sodium 0.8 config API", t);
        }
    }

    private static Object sodium08Group(Object configBuilder, String nameKey, Object... options) throws ReflectiveOperationException {
        Object group = invokeByName(configBuilder, "createOptionGroup");
        invokeByName(group, "setName", Component.translatable(nameKey));
        for (Object option : options) {
            invokeByName(group, "addOption", option);
        }
        return group;
    }

    private static Object sodium08BooleanOption(
            Object configBuilder,
            String path,
            String nameKey,
            String tooltipKey,
            String impact,
            boolean defaultValue,
            Consumer<Boolean> setter,
            Supplier<Boolean> getter,
            Runnable save) throws ReflectiveOperationException {
        Object builder = invokeByName(configBuilder, "createBooleanOption", id(path));
        invokeByName(builder, "setName", Component.translatable(nameKey));
        invokeByName(builder, "setTooltip", Component.translatable(tooltipKey));
        invokeByName(builder, "setImpact", enumConstant(SODIUM08_OPTION_IMPACT, impact));
        invokeByName(builder, "setDefaultValue", defaultValue);
        invokeByName(builder, "setStorageHandler", sodium08StorageHandler(save));
        invokeByName(builder, "setBinding", setter, getter);
        return builder;
    }

    private static Object sodium08IntOption(
            Object configBuilder,
            String path,
            String nameKey,
            String tooltipKey,
            String impact,
            int defaultValue,
            int min,
            int max,
            int step,
            Consumer<Integer> setter,
            Supplier<Integer> getter,
            IntFunction<Component> formatter,
            Runnable save) throws ReflectiveOperationException {
        Object builder = invokeByName(configBuilder, "createIntegerOption", id(path));
        invokeByName(builder, "setName", Component.translatable(nameKey));
        invokeByName(builder, "setTooltip", Component.translatable(tooltipKey));
        invokeByName(builder, "setImpact", enumConstant(SODIUM08_OPTION_IMPACT, impact));
        invokeByName(builder, "setDefaultValue", defaultValue);
        invokeByName(builder, "setRange", min, max, step);
        invokeByName(builder, "setValueFormatter", sodium08Formatter(formatter));
        invokeByName(builder, "setStorageHandler", sodium08StorageHandler(save));
        invokeByName(builder, "setBinding", setter, getter);
        return builder;
    }

    private static Object oldPage() {
        try {
            List<Object> groups = new ArrayList<>();
            Object clientStorage = oldStorage(VSSClientConfig.CONFIG, VSSVoxyOptionsIntegration::saveClientConfig);
            Object serverStorage = oldStorage(VSSServerConfig.CONFIG, VSSVoxyOptionsIntegration::saveServerConfig);

            groups.add(oldGroup(
                    oldBooleanOption(
                            clientStorage,
                            "vss.voxy_options.receive_server_lods",
                            "vss.voxy_options.receive_server_lods.tooltip",
                            "MEDIUM",
                            (VSSClientConfig config, Boolean value) -> config.receiveServerLods = value,
                            config -> config.receiveServerLods),
                    oldBooleanOption(
                            clientStorage,
                            "vss.voxy_options.off_thread_processing",
                            "vss.voxy_options.off_thread_processing.tooltip",
                            "LOW",
                            (VSSClientConfig config, Boolean value) -> config.offThreadSectionProcessing = value,
                            config -> config.offThreadSectionProcessing)));

            groups.add(oldGroup(
                    oldIntOption(
                            clientStorage,
                            "vss.voxy_options.client_lod_distance",
                            "vss.voxy_options.client_lod_distance.tooltip",
                            "LOW",
                            0,
                            512,
                            16,
                            VSSVoxyOptionsIntegration::formatChunksAuto,
                            (VSSClientConfig config, Integer value) -> config.lodDistanceChunks = value,
                            config -> config.lodDistanceChunks),
                    oldIntOption(
                            clientStorage,
                            "vss.voxy_options.desired_bandwidth",
                            "vss.voxy_options.desired_bandwidth.tooltip",
                            "LOW",
                            0,
                            100000,
                            1,
                            VSSVoxyOptionsIntegration::formatKbpsAuto,
                            (VSSClientConfig config, Integer value) -> {
                                config.desiredBandwidthKbps = value;
                                Minecraft.getInstance().execute(VSSClientNetworking::sendBandwidthPreference);
                            },
                            config -> config.desiredBandwidthKbps)));

            if (canEditLocalServerConfig()) {
                groups.add(oldGroup(
                    oldBooleanOption(
                            serverStorage,
                            "vss.voxy_options.server_sync",
                            "vss.voxy_options.server_sync.tooltip",
                            "HIGH",
                            (VSSServerConfig config, Boolean value) -> config.enabled = value,
                            config -> config.enabled),
                    oldBooleanOption(
                            serverStorage,
                            "vss.voxy_options.generation",
                            "vss.voxy_options.generation.tooltip",
                            "HIGH",
                            (VSSServerConfig config, Boolean value) -> config.enableChunkGeneration = value,
                            config -> config.enableChunkGeneration)));

                groups.add(oldGroup(
                    oldBooleanOption(
                            serverStorage,
                            "vss.voxy_options.far_player_sync",
                            "vss.voxy_options.far_player_sync.tooltip",
                            "LOW",
                            (VSSServerConfig config, Boolean value) -> config.farPlayerSyncEnabled = value,
                            config -> config.farPlayerSyncEnabled),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.far_player_sync_interval",
                            "vss.voxy_options.far_player_sync_interval.tooltip",
                            "LOW",
                            1,
                            100,
                            1,
                            VSSVoxyOptionsIntegration::formatTicks,
                            (VSSServerConfig config, Integer value) -> config.farPlayerSyncIntervalTicks = value,
                            config -> config.farPlayerSyncIntervalTicks)));

            groups.add(oldGroup(
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.server_lod_distance",
                            "vss.voxy_options.server_lod_distance.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_LOD_DISTANCE_CHUNKS,
                            VSSServerConfig.MAX_LOD_DISTANCE_CHUNKS,
                            1,
                            VSSVoxyOptionsIntegration::formatChunks,
                            (VSSServerConfig config, Integer value) -> config.lodDistanceChunks = value,
                            config -> config.lodDistanceChunks),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.server_bandwidth",
                            "vss.voxy_options.server_bandwidth.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_BANDWIDTH_KBPS_PER_PLAYER,
                            VSSServerConfig.MAX_BANDWIDTH_KBPS_PER_PLAYER,
                            1,
                            VSSVoxyOptionsIntegration::formatKbps,
                            VSSServerConfig::setPerPlayerBandwidthKbpsUnsaved,
                            VSSServerConfig::getPerPlayerBandwidthKbpsRounded),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.server_queue_count",
                            "vss.voxy_options.server_queue_count.tooltip",
                            "MEDIUM",
                            1,
                            100000,
                            1,
                            VSSVoxyOptionsIntegration::formatColumns,
                            (VSSServerConfig config, Integer value) -> config.sendQueueLimitPerPlayer = value,
                            config -> config.sendQueueLimitPerPlayer),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.server_queue_memory",
                            "vss.voxy_options.server_queue_memory.tooltip",
                            "HIGH",
                            VSSServerConfig.MIN_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                            VSSServerConfig.MAX_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                            4,
                            VSSVoxyOptionsIntegration::formatMiB,
                            (VSSServerConfig config, Integer value) -> config.sendQueueBytesLimitPerPlayer = Math.multiplyExact(value, VSSServerConfig.BYTES_PER_MIB),
                            VSSServerConfig::getSendQueueBytesMiBRounded),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.sync_near_rate",
                            "vss.voxy_options.sync_near_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            VSSVoxyOptionsIntegration::formatNearRequestsPerTick,
                            (VSSServerConfig config, Integer value) -> config.nearSyncRateLimitPerTick = value,
                            config -> config.nearSyncRateLimitPerTick),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.sync_mid_rate",
                            "vss.voxy_options.sync_mid_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            VSSVoxyOptionsIntegration::formatRequestsPerTick,
                            (VSSServerConfig config, Integer value) -> config.midSyncRateLimitPerTick = value,
                            config -> config.midSyncRateLimitPerTick),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.sync_far_rate",
                            "vss.voxy_options.sync_far_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            VSSVoxyOptionsIntegration::formatRequestsPerTick,
                            (VSSServerConfig config, Integer value) -> config.farSyncRateLimitPerTick = value,
                            config -> config.farSyncRateLimitPerTick),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.sync_distant_rate",
                            "vss.voxy_options.sync_distant_rate.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_SYNC_RATE_LIMIT_PER_TICK,
                            VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK,
                            1,
                            VSSVoxyOptionsIntegration::formatRequestsPerTick,
                            (VSSServerConfig config, Integer value) -> config.distantSyncRateLimitPerTick = value,
                            config -> config.distantSyncRateLimitPerTick),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.dirty_broadcast_interval",
                            "vss.voxy_options.dirty_broadcast_interval.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_DIRTY_BROADCAST_INTERVAL_TICKS,
                            VSSServerConfig.MAX_DIRTY_BROADCAST_INTERVAL_TICKS,
                            1,
                            VSSVoxyOptionsIntegration::formatTicks,
                            (VSSServerConfig config, Integer value) -> config.dirtyBroadcastIntervalTicks = value,
                            config -> config.dirtyBroadcastIntervalTicks),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.disk_reader_threads",
                            "vss.voxy_options.disk_reader_threads.tooltip",
                            "MEDIUM",
                            VSSServerConfig.MIN_DISK_READER_THREADS,
                            VSSServerConfig.MAX_DISK_READER_THREADS,
                            1,
                            VSSVoxyOptionsIntegration::formatThreads,
                            (VSSServerConfig config, Integer value) -> config.diskReaderThreads = value,
                            config -> config.diskReaderThreads)));

            groups.add(oldGroup(
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.generation_player_concurrency",
                            "vss.voxy_options.generation_player_concurrency.tooltip",
                            "HIGH",
                            1,
                            1000,
                            1,
                            VSSVoxyOptionsIntegration::formatColumns,
                            (VSSServerConfig config, Integer value) -> config.generationConcurrencyLimitPerPlayer = value,
                            config -> config.generationConcurrencyLimitPerPlayer),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.generation_global_concurrency",
                            "vss.voxy_options.generation_global_concurrency.tooltip",
                            "HIGH",
                            1,
                            1000,
                            1,
                            VSSVoxyOptionsIntegration::formatColumns,
                            (VSSServerConfig config, Integer value) -> config.generationConcurrencyLimitGlobal = value,
                            config -> config.generationConcurrencyLimitGlobal),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.generation_starts_per_tick",
                            "vss.voxy_options.generation_starts_per_tick.tooltip",
                            "HIGH",
                            1,
                            256,
                            1,
                            VSSVoxyOptionsIntegration::formatColumns,
                            (VSSServerConfig config, Integer value) -> config.generationStartsPerTickLimit = value,
                            config -> config.generationStartsPerTickLimit),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.generation_completions_per_tick",
                            "vss.voxy_options.generation_completions_per_tick.tooltip",
                            "HIGH",
                            1,
                            256,
                            1,
                            VSSVoxyOptionsIntegration::formatColumns,
                            (VSSServerConfig config, Integer value) -> config.generationCompletionsPerTickLimit = value,
                            config -> config.generationCompletionsPerTickLimit),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.generation_packing_threads",
                            "vss.voxy_options.generation_packing_threads.tooltip",
                            "HIGH",
                            1,
                            8,
                            1,
                            VSSVoxyOptionsIntegration::formatThreads,
                            (VSSServerConfig config, Integer value) -> config.generationPackingThreads = value,
                            config -> config.generationPackingThreads),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.generation_packing_queue",
                            "vss.voxy_options.generation_packing_queue.tooltip",
                            "HIGH",
                            1,
                            1024,
                            1,
                            VSSVoxyOptionsIntegration::formatColumns,
                            (VSSServerConfig config, Integer value) -> config.generationPackingQueueLimit = value,
                            config -> config.generationPackingQueueLimit),
                    oldIntOption(
                            serverStorage,
                            "vss.voxy_options.generation_timeout",
                            "vss.voxy_options.generation_timeout.tooltip",
                            "MEDIUM",
                            1,
                            600,
                            1,
                            VSSVoxyOptionsIntegration::formatSeconds,
                            (VSSServerConfig config, Integer value) -> config.generationTimeoutSeconds = value,
                            config -> config.generationTimeoutSeconds)));
            }

            Constructor<?> pageConstructor = Class.forName(OLD_OPTION_PAGE).getConstructor(Component.class, ImmutableList.class);
            return pageConstructor.newInstance(Component.translatable("vss.voxy_options.title"), ImmutableList.copyOf(groups));
        } catch (Throwable t) {
            VSSLogger.warn("Failed to build VSS Sodium options page", t);
            return null;
        }
    }

    private static Object oldGroup(Object... options) throws ReflectiveOperationException {
        Object builder = invokeStatic(Class.forName(OLD_OPTION_GROUP), "createBuilder");
        for (Object option : options) {
            invokeByName(builder, "add", option);
        }
        return invokeByName(builder, "build");
    }

    private static <S> Object oldBooleanOption(
            Object storage,
            String nameKey,
            String tooltipKey,
            String impact,
            BiConsumer<S, Boolean> setter,
            Function<S, Boolean> getter) throws ReflectiveOperationException {
        return oldOption(
                boolean.class,
                storage,
                nameKey,
                tooltipKey,
                impact,
                VSSVoxyOptionsIntegration::oldTickBoxControl,
                setter,
                getter);
    }

    private static <S> Object oldIntOption(
            Object storage,
            String nameKey,
            String tooltipKey,
            String impact,
            int min,
            int max,
            int interval,
            IntFunction<Component> formatter,
            BiConsumer<S, Integer> setter,
            Function<S, Integer> getter) throws ReflectiveOperationException {
        return oldOption(
                int.class,
                storage,
                nameKey,
                tooltipKey,
                impact,
                option -> oldSliderControl(option, min, max, interval, formatter),
                setter,
                getter);
    }

    private static <S, T> Object oldOption(
            Class<?> valueClass,
            Object storage,
            String nameKey,
            String tooltipKey,
            String impact,
            Function<Object, Object> controlFactory,
            BiConsumer<S, T> setter,
            Function<S, T> getter) throws ReflectiveOperationException {
        Object builder = invokeStatic(Class.forName(OLD_OPTION_IMPL), "createBuilder", valueClass, storage);
        invokeByName(builder, "setName", Component.translatable(nameKey));
        invokeByName(builder, "setTooltip", Component.translatable(tooltipKey));
        invokeByName(builder, "setControl", controlFactory);
        invokeByName(builder, "setBinding", setter, getter);
        invokeByName(builder, "setImpact", enumConstant(OLD_OPTION_IMPACT, impact));
        return invokeByName(builder, "build");
    }

    private static Object oldTickBoxControl(Object option) {
        try {
            Constructor<?> constructor = Class.forName(OLD_TICK_BOX_CONTROL).getConstructor(Class.forName(OLD_OPTION));
            return constructor.newInstance(option);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create Sodium tick box control", e);
        }
    }

    private static Object oldSliderControl(Object option, int min, int max, int interval, IntFunction<Component> formatter) {
        try {
            Constructor<?> constructor = Class.forName(OLD_SLIDER_CONTROL).getConstructor(
                    Class.forName(OLD_OPTION),
                    int.class,
                    int.class,
                    int.class,
                    Class.forName(OLD_VALUE_FORMATTER));
            return constructor.newInstance(option, min, max, interval, oldFormatter(formatter));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create Sodium slider control", e);
        }
    }

    private static Object oldStorage(Object data, Runnable save) throws ReflectiveOperationException {
        Class<?> storageInterface = Class.forName(OLD_OPTION_STORAGE);
        return proxy(storageInterface, (proxy, method, args) -> {
            if ("getData".equals(method.getName())) {
                return data;
            }
            if ("save".equals(method.getName())) {
                save.run();
                return null;
            }
            return objectMethod(proxy, method, args, "VSS Sodium option storage");
        });
    }

    private static Object oldFormatter(IntFunction<Component> formatter) throws ReflectiveOperationException {
        Class<?> formatterInterface = Class.forName(OLD_VALUE_FORMATTER);
        return proxy(formatterInterface, (proxy, method, args) -> {
            if ("format".equals(method.getName()) && args != null && args.length == 1) {
                return formatter.apply((Integer) args[0]);
            }
            return objectMethod(proxy, method, args, "VSS Sodium value formatter");
        });
    }

    private static Object sodium08StorageHandler(Runnable save) throws ReflectiveOperationException {
        Class<?> storageInterface = Class.forName(SODIUM08_STORAGE_HANDLER);
        return proxy(storageInterface, (proxy, method, args) -> {
            if ("afterSave".equals(method.getName())) {
                save.run();
                return null;
            }
            return objectMethod(proxy, method, args, "VSS Sodium 0.8 storage handler");
        });
    }

    private static Object sodium08Formatter(IntFunction<Component> formatter) throws ReflectiveOperationException {
        Class<?> formatterInterface = Class.forName(SODIUM08_VALUE_FORMATTER);
        return proxy(formatterInterface, (proxy, method, args) -> {
            if ("format".equals(method.getName()) && args != null && args.length == 1) {
                return formatter.apply((Integer) args[0]);
            }
            return objectMethod(proxy, method, args, "VSS Sodium 0.8 value formatter");
        });
    }

    private static boolean containsPageNamed(List<?> pages, String name) {
        for (Object existing : pages) {
            if (name.equals(pageName(existing))) {
                return true;
            }
        }
        return false;
    }

    private static int findVoxyPageIndex(List<?> pages) {
        Object voxyPage = getVoxyOptionPage();
        if (voxyPage != null) {
            int index = pages.indexOf(voxyPage);
            if (index >= 0) {
                return index;
            }
        }

        for (int i = 0; i < pages.size(); i++) {
            if ("Voxy".equals(pageName(pages.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static Object getVoxyOptionPage() {
        try {
            Class<?> pagesClass = Class.forName("me.cortex.voxy.client.config.VoxyConfigScreenPages");
            Field pageField = pagesClass.getField("voxyOptionPage");
            Object value = pageField.get(null);
            return Class.forName(OLD_OPTION_PAGE).isInstance(value) ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Screen createSodium08ConfigScreen(Screen parent) {
        try {
            Class<?> configManagerClass = Class.forName(SODIUM08_CONFIG_MANAGER);
            Object config = sodium08Config(configManagerClass);
            Object modOptions = findSodium08ModOptions(config);

            if (modOptions == null) {
                invokeStatic(configManagerClass, "registerConfigsLate");
                config = sodium08Config(configManagerClass);
                modOptions = findSodium08ModOptions(config);
                if (modOptions == null) {
                    return null;
                }
            }

            Object pages = invokeByName(modOptions, "pages");
            if (!(pages instanceof List<?> pageList) || pageList.isEmpty()) {
                return null;
            }

            Object page = pageList.get(0);
            Class<?> videoSettingsScreen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
            Object screen = invokeStatic(videoSettingsScreen, "createScreen", parent, page);
            return screen instanceof Screen sodiumScreen ? sodiumScreen : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object sodium08Config(Class<?> configManagerClass) throws ReflectiveOperationException {
        Field configField = configManagerClass.getField("CONFIG");
        return configField.get(null);
    }

    private static Object findSodium08ModOptions(Object config) throws ReflectiveOperationException {
        if (config == null) {
            return null;
        }
        Object modOptions = invokeByName(config, "getModOptions");
        if (!(modOptions instanceof List<?> list)) {
            return null;
        }
        for (Object entry : list) {
            Object configId = invokeByName(entry, "configId");
            if (VSSConstants.MOD_ID.equals(configId)) {
                return entry;
            }
        }
        return null;
    }

    private static Screen createOldSodiumConfigScreen(Screen parent) {
        try {
            Object page = oldPage();
            if (page == null) {
                return null;
            }

            Class<?> videoSettingsScreen = Class.forName("net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI");
            Object screen = invokeStatic(videoSettingsScreen, "createScreen", parent, page);
            return screen instanceof Screen sodiumScreen ? sodiumScreen : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findPagesField(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField("pages");
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static String pageName(Object page) {
        Object component = null;
        try {
            component = invokeByName(page, "getName");
        } catch (Throwable ignored) {
            try {
                component = invokeByName(page, "name");
            } catch (Throwable ignoredAgain) {
                return "";
            }
        }

        if (component instanceof Component text) {
            return text.getString();
        }
        return String.valueOf(component);
    }

    private static Component formatChunksAuto(int value) {
        return value == 0
                ? Component.translatable("vss.voxy_options.auto")
                : Component.translatable("vss.voxy_options.chunks", value);
    }

    private static Component formatChunks(int value) {
        return Component.translatable("vss.voxy_options.chunks", value);
    }

    private static Component formatKbpsAuto(int value) {
        return value == 0
                ? Component.translatable("vss.voxy_options.server_limit")
                : formatKbps(value);
    }

    private static Component formatKbps(int value) {
        if (value >= VSSServerConfig.KBPS_PER_MBPS) {
            return Component.translatable("vss.voxy_options.mbps", String.format("%.2f", value / (float) VSSServerConfig.KBPS_PER_MBPS));
        }
        return Component.translatable("vss.voxy_options.kbps", value);
    }

    private static Component formatMiB(int value) {
        return Component.translatable("vss.voxy_options.mib", value);
    }

    private static Component formatNearRequestsPerTick(int value) {
        return value <= 0
                ? Component.translatable("vss.voxy_options.unlimited")
                : formatRequestsPerTick(value);
    }

    private static Component formatRequestsPerTick(int value) {
        return Component.translatable("vss.voxy_options.requests_per_tick", value);
    }

    private static Component formatColumns(int value) {
        return Component.translatable("vss.voxy_options.columns", value);
    }

    private static Component formatThreads(int value) {
        return Component.translatable("vss.voxy_options.threads", value);
    }

    private static Component formatSeconds(int value) {
        return Component.translatable("vss.voxy_options.seconds", value);
    }

    private static Component formatTicks(int value) {
        return Component.translatable("vss.voxy_options.ticks", value);
    }

    private static boolean canEditLocalServerConfig() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null || minecraft.getSingleplayerServer() != null;
    }

    private static void saveClientConfig() {
        VSSClientConfig.CONFIG.normalizeAndSave();
    }

    private static void saveServerConfig() {
        if (!canEditLocalServerConfig()) {
            return;
        }
        VSSServerConfig.CONFIG.normalizeAndSave();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            VSSServerNetworking.bumpAndRefreshSessionConfigs(minecraft.getSingleplayerServer());
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(VSSConstants.MOD_ID, path);
    }

    private static String modVersion() {
        try {
            return ModList.get()
                    .getModContainerById(VSSConstants.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Object enumConstant(String className, String constant) throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        if (!Enum.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(className + " is not an enum");
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object value = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), constant);
        return value;
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, String name) {
        if ("toString".equals(method.getName()) && method.getParameterCount() == 0) {
            return name;
        }
        if ("hashCode".equals(method.getName()) && method.getParameterCount() == 0) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
            return proxy == args[0];
        }
        return null;
    }

    private static Object invokeStatic(Class<?> type, String name, Object... args) throws ReflectiveOperationException {
        return invoke(type, null, name, args);
    }

    private static Object invokeByName(Object target, String name, Object... args) throws ReflectiveOperationException {
        return invoke(target.getClass(), target, name, args);
    }

    private static Object invoke(Class<?> type, Object target, String name, Object... args) throws ReflectiveOperationException {
        Method method = findMethod(type, name, args);
        if (method == null) {
            throw new NoSuchMethodException(type.getName() + "." + name);
        }
        try {
            if (!method.canAccess(target)) {
                method.setAccessible(true);
            }
        } catch (RuntimeException ignored) {
        }
        return method.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && parametersMatch(method.getParameterTypes(), args)) {
                return method;
            }
        }

        Class<?> cursor = type;
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(name) && parametersMatch(method.getParameterTypes(), args)) {
                    return method;
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static boolean parametersMatch(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (!parameterMatches(parameters[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean parameterMatches(Class<?> parameter, Object arg) {
        if (arg == null) {
            return !parameter.isPrimitive();
        }
        Class<?> type = parameter.isPrimitive() ? primitiveWrapper(parameter) : parameter;
        return type.isAssignableFrom(arg.getClass());
    }

    private static Class<?> primitiveWrapper(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return Void.class;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, VSSVoxyOptionsIntegration.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
