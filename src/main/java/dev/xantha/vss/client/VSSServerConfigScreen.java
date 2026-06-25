package dev.xantha.vss.client;

import dev.xantha.vss.config.VSSServerConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class VSSServerConfigScreen extends Screen {
    private final Screen parent;
    private final VSSServerConfig config;
    private final List<ConfigNumberField> fields = new ArrayList<>();
    private Button enabledButton;
    private Button generationButton;
    private Component status = Component.empty();

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
        int y = 58;
        int rowHeight = 30;

        enabledButton = addRenderableWidget(Button.builder(enabledLabel(), button -> {
            config.enabled = !config.enabled;
            button.setMessage(enabledLabel());
        }).bounds(inputX, y - 4, inputWidth, 20).build());
        y += rowHeight;

        generationButton = addRenderableWidget(Button.builder(generationLabel(), button -> {
            config.enableChunkGeneration = !config.enableChunkGeneration;
            button.setMessage(generationLabel());
        }).bounds(inputX, y - 4, inputWidth, 20).build());
        y += rowHeight;

        addField("vss.config.server.bandwidth_mib", config.getPerPlayerBandwidthMiBRounded(), 1,
                VSSServerConfig.MAX_BYTES_PER_SECOND_LIMIT_PER_PLAYER / VSSServerConfig.BYTES_PER_MIB,
                value -> config.bytesPerSecondLimitPerPlayer = Math.multiplyExact(value, VSSServerConfig.BYTES_PER_MIB),
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

        addField("vss.config.server.generation_packing_threads", config.generationPackingThreads, 1, 8,
                value -> config.generationPackingThreads = value, left, inputX, inputWidth, y);
        y += rowHeight;

        addField("vss.config.server.generation_packing_queue", config.generationPackingQueueLimit, 1, 1024,
                value -> config.generationPackingQueueLimit = value, left, inputX, inputWidth, y);
        y += rowHeight;

        int buttonY = height - 32;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            if (saveConfig()) {
                minecraft.setScreen(parent);
            }
        }).bounds(width / 2 - 155, buttonY, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> minecraft.setScreen(parent))
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
        status = Component.translatable("vss.config.server.saved").withStyle(ChatFormatting.GREEN);
        return true;
    }

    private Component enabledLabel() {
        return Component.translatable(config.enabled ? "vss.config.server.enabled.on" : "vss.config.server.enabled.off");
    }

    private Component generationLabel() {
        return Component.translatable(config.enableChunkGeneration ? "vss.config.server.generation.on" : "vss.config.server.generation.off");
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
        for (ConfigNumberField field : fields) {
            graphics.drawString(font, field.label, field.labelX, field.y + 2, 0xFFFFFF);
        }
        graphics.drawString(font, Component.translatable("vss.config.server.enabled"), fields.isEmpty() ? 20 : fields.get(0).labelX, 56, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("vss.config.server.generation"), fields.isEmpty() ? 20 : fields.get(0).labelX, 86, 0xFFFFFF);
        if (status != Component.empty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 50, 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
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
