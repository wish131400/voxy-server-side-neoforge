package dev.xantha.vss.client;

import com.google.common.collect.ImmutableList;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class VSSVoxyOptionsIntegration {
    private static OptionPage vssOptionPage;

    private VSSVoxyOptionsIntegration() {
    }

    @SuppressWarnings("unchecked")
    public static void addPage(Object sodiumOptionsScreen) {
        try {
            Field pagesField = findPagesField(sodiumOptionsScreen.getClass());
            if (pagesField == null) {
                return;
            }
            pagesField.setAccessible(true);
            List<OptionPage> pages = (List<OptionPage>) pagesField.get(sodiumOptionsScreen);
            addPage(pages);
        } catch (Throwable ignored) {
        }
    }

    public static void addPage(List<OptionPage> pages) {
        if (pages == null) {
            return;
        }
        OptionPage page = page();
        if (pages.contains(page) || containsPageNamed(pages, page.getName().getString())) {
            return;
        }

        int insertAt = findVoxyPageIndex(pages) + 1;
        if (insertAt <= 0) {
            insertAt = pages.size();
        }
        pages.add(insertAt, page);
        VSSLogger.info("Added Voxy Server Side options page to Embeddium/Sodium options");
    }

    private static boolean containsPageNamed(List<OptionPage> pages, String name) {
        for (OptionPage existing : pages) {
            if (name.equals(existing.getName().getString())) {
                return true;
            }
        }
        return false;
    }

    private static int findVoxyPageIndex(List<OptionPage> pages) {
        OptionPage voxyPage = getVoxyOptionPage();
        if (voxyPage != null) {
            int index = pages.indexOf(voxyPage);
            if (index >= 0) {
                return index;
            }
        }

        for (int i = 0; i < pages.size(); i++) {
            String pageName = pages.get(i).getName().getString();
            if ("Voxy".equals(pageName)) {
                return i;
            }
        }
        return -1;
    }

    private static OptionPage getVoxyOptionPage() {
        try {
            Class<?> pagesClass = Class.forName("me.cortex.voxy.client.config.VoxyConfigScreenPages");
            Field pageField = pagesClass.getField("voxyOptionPage");
            Object value = pageField.get(null);
            return value instanceof OptionPage page ? page : null;
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

    private static OptionPage page() {
        if (vssOptionPage != null) {
            return vssOptionPage;
        }

        List<OptionGroup> groups = new ArrayList<>();
        ClientStorage clientStorage = new ClientStorage();
        ServerStorage serverStorage = new ServerStorage();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, clientStorage)
                        .setName(Component.translatable("vss.voxy_options.receive_server_lods"))
                        .setTooltip(Component.translatable("vss.voxy_options.receive_server_lods.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.receiveServerLods = value, config -> config.receiveServerLods)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, clientStorage)
                        .setName(Component.translatable("vss.voxy_options.off_thread_processing"))
                        .setTooltip(Component.translatable("vss.voxy_options.off_thread_processing.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.offThreadSectionProcessing = value, config -> config.offThreadSectionProcessing)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, clientStorage)
                        .setName(Component.translatable("vss.voxy_options.client_lod_distance"))
                        .setTooltip(Component.translatable("vss.voxy_options.client_lod_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 512, 16, VSSVoxyOptionsIntegration::formatChunksAuto))
                        .setBinding((config, value) -> config.lodDistanceChunks = value, config -> config.lodDistanceChunks)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(int.class, clientStorage)
                        .setName(Component.translatable("vss.voxy_options.desired_bandwidth"))
                        .setTooltip(Component.translatable("vss.voxy_options.desired_bandwidth.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 100, 1, VSSVoxyOptionsIntegration::formatMiBAuto))
                        .setBinding((config, value) -> {
                            config.desiredBandwidthMiB = value;
                            Minecraft.getInstance().execute(VSSClientNetworking::sendBandwidthPreference);
                        }, config -> config.desiredBandwidthMiB)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.server_sync"))
                        .setTooltip(Component.translatable("vss.voxy_options.server_sync.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.enabled = value, config -> config.enabled)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.enableChunkGeneration = value, config -> config.enableChunkGeneration)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.server_bandwidth"))
                        .setTooltip(Component.translatable("vss.voxy_options.server_bandwidth.tooltip"))
                        .setControl(option -> new SliderControl(
                                option,
                                1,
                                VSSServerConfig.MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                                1,
                                VSSVoxyOptionsIntegration::formatMiB))
                        .setBinding(
                                (config, value) -> config.bytesPerSecondLimitPerPlayer = Math.multiplyExact(value, VSSServerConfig.BYTES_PER_MIB),
                                VSSServerConfig::getPerPlayerBandwidthMiBRounded)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.server_queue_memory"))
                        .setTooltip(Component.translatable("vss.voxy_options.server_queue_memory.tooltip"))
                        .setControl(option -> new SliderControl(
                                option,
                                VSSServerConfig.MIN_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                                VSSServerConfig.MAX_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                                4,
                                VSSVoxyOptionsIntegration::formatMiB))
                        .setBinding(
                                (config, value) -> config.sendQueueBytesLimitPerPlayer = Math.multiplyExact(value, VSSServerConfig.BYTES_PER_MIB),
                                VSSServerConfig::getSendQueueBytesMiBRounded)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.sync_rate"))
                        .setTooltip(Component.translatable("vss.voxy_options.sync_rate.tooltip"))
                        .setControl(option -> new SliderControl(option, 20, 1000, 20, VSSVoxyOptionsIntegration::formatRequestsPerSecond))
                        .setBinding((config, value) -> config.syncOnLoadRateLimitPerPlayer = value, config -> config.syncOnLoadRateLimitPerPlayer)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation_player_concurrency"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation_player_concurrency.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 64, 1, VSSVoxyOptionsIntegration::formatColumns))
                        .setBinding((config, value) -> config.generationConcurrencyLimitPerPlayer = value, config -> config.generationConcurrencyLimitPerPlayer)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation_global_concurrency"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation_global_concurrency.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 128, 1, VSSVoxyOptionsIntegration::formatColumns))
                        .setBinding((config, value) -> config.generationConcurrencyLimitGlobal = value, config -> config.generationConcurrencyLimitGlobal)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation_starts_per_tick"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation_starts_per_tick.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 32, 1, VSSVoxyOptionsIntegration::formatColumns))
                        .setBinding((config, value) -> config.generationStartsPerTickLimit = value, config -> config.generationStartsPerTickLimit)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation_completions_per_tick"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation_completions_per_tick.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 32, 1, VSSVoxyOptionsIntegration::formatColumns))
                        .setBinding((config, value) -> config.generationCompletionsPerTickLimit = value, config -> config.generationCompletionsPerTickLimit)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation_packing_threads"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation_packing_threads.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 8, 1, VSSVoxyOptionsIntegration::formatThreads))
                        .setBinding((config, value) -> config.generationPackingThreads = value, config -> config.generationPackingThreads)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation_packing_queue"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation_packing_queue.tooltip"))
                        .setControl(option -> new SliderControl(option, 1, 256, 1, VSSVoxyOptionsIntegration::formatColumns))
                        .setBinding((config, value) -> config.generationPackingQueueLimit = value, config -> config.generationPackingQueueLimit)
                        .setImpact(OptionImpact.HIGH)
                        .build())
                .add(OptionImpl.createBuilder(int.class, serverStorage)
                        .setName(Component.translatable("vss.voxy_options.generation_timeout"))
                        .setTooltip(Component.translatable("vss.voxy_options.generation_timeout.tooltip"))
                        .setControl(option -> new SliderControl(option, 5, 120, 5, VSSVoxyOptionsIntegration::formatSeconds))
                        .setBinding((config, value) -> config.generationTimeoutSeconds = value, config -> config.generationTimeoutSeconds)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .build());

        vssOptionPage = new OptionPage(Component.translatable("vss.voxy_options.title"), ImmutableList.copyOf(groups));
        return vssOptionPage;
    }

    private static Component formatChunksAuto(int value) {
        return value == 0
                ? Component.translatable("vss.voxy_options.auto")
                : Component.translatable("vss.voxy_options.chunks", value);
    }

    private static Component formatMiBAuto(int value) {
        return value == 0
                ? Component.translatable("vss.voxy_options.server_limit")
                : Component.translatable("vss.voxy_options.mib_per_second", value);
    }

    private static Component formatMiB(int value) {
        return Component.translatable("vss.voxy_options.mib_per_second", value);
    }

    private static Component formatRequestsPerSecond(int value) {
        return Component.translatable("vss.voxy_options.requests_per_second", value);
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

    private static final class ClientStorage implements OptionStorage<VSSClientConfig> {
        @Override
        public VSSClientConfig getData() {
            return VSSClientConfig.CONFIG;
        }

        @Override
        public void save() {
            VSSClientConfig.CONFIG.normalizeAndSave();
        }
    }

    private static final class ServerStorage implements OptionStorage<VSSServerConfig> {
        @Override
        public VSSServerConfig getData() {
            return VSSServerConfig.CONFIG;
        }

        @Override
        public void save() {
            VSSServerConfig.CONFIG.normalizeAndSave();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getSingleplayerServer() != null) {
                VSSServerNetworking.refreshSessionConfigs(minecraft.getSingleplayerServer());
            }
        }
    }
}
