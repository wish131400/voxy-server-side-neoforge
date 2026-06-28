package dev.xantha.vss.client;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

final class VSSServerConfigScreen extends Screen {
    private static final int HEADER_HEIGHT = 50;
    private static final int FOOTER_HEIGHT = 42;

    private final Screen parent;
    private final VSSServerConfig config;
    private final List<ConfigNumberField> fields = new ArrayList<>();
    private Button enabledButton;
    private Button generationButton;
    private Button farPlayersButton;
    private Button doneButton;
    private Button cancelButton;
    private Component status = Component.empty();
    private int scrollOffset;
    private int maxScrollOffset;
    private int listTop;
    private int listBottom;
    private int enabledBaseY;
    private int generationBaseY;
    private int farPlayersBaseY;

    VSSServerConfigScreen(Screen parent, VSSServerConfig config) {
        super(Component.translatable("vss.config.server.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        fields.clear();
        int contentWidth = Math.min(360, width - 40);
        int left = (width - contentWidth) / 2;
        int labelWidth = 170;
        int inputX = left + labelWidth + 10;
        int inputWidth = contentWidth - labelWidth - 10;
        int rowHeight = height < 360 ? 25 : 30;
        int y = HEADER_HEIGHT + 8;
        listTop = HEADER_HEIGHT;
        listBottom = Math.max(listTop + rowHeight, height - FOOTER_HEIGHT);

        enabledBaseY = y;
        enabledButton = addRenderableWidget(Button.builder(enabledLabel(), button -> {
            config.enabled = !config.enabled;
            button.setMessage(enabledLabel());
        }).bounds(inputX, y - 4, inputWidth, 20).build());
        y += rowHeight;

        generationBaseY = y;
        generationButton = addRenderableWidget(Button.builder(generationLabel(), button -> {
            config.enableChunkGeneration = !config.enableChunkGeneration;
            button.setMessage(generationLabel());
        }).bounds(inputX, y - 4, inputWidth, 20).build());
        y += rowHeight;

        farPlayersBaseY = y;
        farPlayersButton = addRenderableWidget(Button.builder(farPlayersLabel(), button -> {
            config.farPlayerSyncEnabled = !config.farPlayerSyncEnabled;
            button.setMessage(farPlayersLabel());
        }).bounds(inputX, y - 4, inputWidth, 20).build());
        y += rowHeight;

        addField("vss.config.server.bandwidth_kbps", config.getPerPlayerBandwidthKbpsRounded(),
                VSSServerConfig.MIN_BANDWIDTH_KBPS_PER_PLAYER, VSSServerConfig.MAX_BANDWIDTH_KBPS_PER_PLAYER,
                config::setPerPlayerBandwidthKbpsUnsaved,
                left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.lod_distance", config.lodDistanceChunks,
                VSSServerConfig.MIN_LOD_DISTANCE_CHUNKS, VSSServerConfig.MAX_LOD_DISTANCE_CHUNKS,
                value -> config.lodDistanceChunks = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.send_queue", config.sendQueueLimitPerPlayer, 1, 100000,
                value -> config.sendQueueLimitPerPlayer = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.send_queue_mib", config.getSendQueueBytesMiBRounded(),
                VSSServerConfig.MIN_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                VSSServerConfig.MAX_SEND_QUEUE_BYTES_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                value -> config.sendQueueBytesLimitPerPlayer = Math.multiplyExact(value, VSSServerConfig.BYTES_PER_MIB),
                left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.sync_rate", config.syncOnLoadRateLimitPerPlayer, 1, 1000,
                value -> config.syncOnLoadRateLimitPerPlayer = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.sync_concurrency", config.syncOnLoadConcurrencyLimitPerPlayer, 1, 1000,
                value -> config.syncOnLoadConcurrencyLimitPerPlayer = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.dirty_broadcast_interval", config.dirtyBroadcastIntervalTicks,
                VSSServerConfig.MIN_DIRTY_BROADCAST_INTERVAL_TICKS, VSSServerConfig.MAX_DIRTY_BROADCAST_INTERVAL_TICKS,
                value -> config.dirtyBroadcastIntervalTicks = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.disk_reader_threads", config.diskReaderThreads,
                VSSServerConfig.MIN_DISK_READER_THREADS, VSSServerConfig.MAX_DISK_READER_THREADS,
                value -> config.diskReaderThreads = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.far_player_sync_interval", config.farPlayerSyncIntervalTicks, 1, 100,
                value -> config.farPlayerSyncIntervalTicks = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_player_concurrency", config.generationConcurrencyLimitPerPlayer, 1, 1000,
                value -> config.generationConcurrencyLimitPerPlayer = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_global_concurrency", config.generationConcurrencyLimitGlobal, 1, 1000,
                value -> config.generationConcurrencyLimitGlobal = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_starts_per_tick", config.generationStartsPerTickLimit, 1, 256,
                value -> config.generationStartsPerTickLimit = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_completions_per_tick", config.generationCompletionsPerTickLimit, 1, 256,
                value -> config.generationCompletionsPerTickLimit = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_packing_threads", config.generationPackingThreads, 1, 8,
                value -> config.generationPackingThreads = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_packing_queue", config.generationPackingQueueLimit, 1, 1024,
                value -> config.generationPackingQueueLimit = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_timeout", config.generationTimeoutSeconds, 1, 600,
                value -> config.generationTimeoutSeconds = value, left, inputX, inputWidth, y);
        y += rowHeight;

        maxScrollOffset = Math.max(0, y - listBottom);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScrollOffset);

        int buttonY = height - 32;
        doneButton = addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            if (saveConfig()) {
                minecraft.setScreen(parent);
            }
        }).bounds(width / 2 - 155, buttonY, 150, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> minecraft.setScreen(parent))
                .bounds(width / 2 + 5, buttonY, 150, 20)
                .build());
    }

    private void addField(String labelKey, int initialValue, int min, int max, IntSetter setter, int left, int inputX, int inputWidth, int y) {
        EditBox editBox = new EditBox(font, inputX, y - 4, inputWidth, 20, Component.translatable(labelKey));
        editBox.setFilter(value -> value.isEmpty() || value.matches("\\d{0,9}"));
        editBox.setValue(Integer.toString(initialValue));
        fields.add(new ConfigNumberField(Component.translatable(labelKey), editBox, min, max, setter, left, y));
        addRenderableWidget(editBox);
    }

    private boolean saveConfig() {
        for (ConfigNumberField field : fields) {
            if (!field.apply()) {
                status = Component.translatable("vss.config.server.invalid", field.label, field.min, field.max)
                        .withStyle(ChatFormatting.RED);
                return false;
            }
        }
        config.normalizeAndSave();
        if (minecraft != null && minecraft.getSingleplayerServer() != null) {
            VSSServerNetworking.bumpAndRefreshSessionConfigs(minecraft.getSingleplayerServer());
        }
        status = Component.translatable("vss.config.server.saved").withStyle(ChatFormatting.GREEN);
        return true;
    }

    private Component enabledLabel() {
        return Component.translatable(config.enabled ? "vss.config.server.enabled.on" : "vss.config.server.enabled.off");
    }

    private Component generationLabel() {
        return Component.translatable(config.enableChunkGeneration ? "vss.config.server.generation.on" : "vss.config.server.generation.off");
    }

    private Component farPlayersLabel() {
        return Component.translatable(config.farPlayerSyncEnabled ? "vss.config.server.far_players.on" : "vss.config.server.far_players.off");
    }

    @Override
    public void tick() {
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("vss.config.server.note"), width / 2, 36, 0xA0A0A0);
        updateScrolledControls();
        graphics.enableScissor(0, listTop, width, listBottom);
        for (ConfigNumberField field : fields) {
            int y = scrolledY(field.y);
            if (isRowVisible(y)) {
                graphics.drawString(font, field.label, field.labelX, y + 2, 0xFFFFFF);
            }
        }
        int labelX = fields.isEmpty() ? 20 : fields.get(0).labelX;
        int enabledY = enabledButton.getY() + 4;
        int generationY = generationButton.getY() + 4;
        int farPlayersY = farPlayersButton.getY() + 4;
        if (isRowVisible(enabledY)) {
            graphics.drawString(font, Component.translatable("vss.config.server.enabled"), labelX, enabledY, 0xFFFFFF);
        }
        if (isRowVisible(generationY)) {
            graphics.drawString(font, Component.translatable("vss.config.server.generation"), labelX, generationY, 0xFFFFFF);
        }
        if (isRowVisible(farPlayersY)) {
            graphics.drawString(font, Component.translatable("vss.config.server.far_players"), labelX, farPlayersY, 0xFFFFFF);
        }
        renderScrolledWidgets(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        if (status != Component.empty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 52, 0xFFFFFF);
        }
        renderFooterWidgets(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScrollOffset > 0) {
            scrollOffset = Mth.clamp(scrollOffset - (int) Math.round(scrollY * 18.0D), 0, maxScrollOffset);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void renderScrolledWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (Renderable renderable : renderables) {
            if (renderable != doneButton && renderable != cancelButton) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private void renderFooterWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (Renderable renderable : renderables) {
            if (renderable == doneButton || renderable == cancelButton) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private void updateScrolledControls() {
        enabledButton.setY(scrolledY(enabledBaseY - 4));
        generationButton.setY(scrolledY(generationBaseY - 4));
        farPlayersButton.setY(scrolledY(farPlayersBaseY - 4));
        enabledButton.visible = isRowVisible(enabledButton.getY());
        generationButton.visible = isRowVisible(generationButton.getY());
        farPlayersButton.visible = isRowVisible(farPlayersButton.getY());
        for (ConfigNumberField field : fields) {
            field.editBox.setY(scrolledY(field.y - 4));
            field.editBox.setVisible(isRowVisible(field.editBox.getY()));
        }
        doneButton.visible = true;
        cancelButton.visible = true;
    }

    private int scrolledY(int baseY) {
        return baseY - scrollOffset;
    }

    private boolean isRowVisible(int y) {
        return y + 20 > listTop && y < listBottom;
    }

    private record ConfigNumberField(Component label, EditBox editBox, int min, int max, IntSetter setter, int labelX, int y) {
        boolean apply() {
            try {
                int value = Integer.parseInt(editBox.getValue());
                if (value < min || value > max) {
                    return false;
                }
                setter.set(value);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    @FunctionalInterface
    private interface IntSetter {
        void set(int value);
    }
}
