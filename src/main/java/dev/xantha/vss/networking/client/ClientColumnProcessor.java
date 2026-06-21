package dev.xantha.vss.networking.client;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class ClientColumnProcessor {
    static final int MAX_QUEUED_COLUMNS = 2000;
    private static final long MAX_QUEUED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_SECTIONS_PER_COLUMN = 64;
    private static final long DROP_WARN_INTERVAL_MS = 5000L;

    private final ConcurrentLinkedQueue<QueuedColumn> columnQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong queueBytes = new AtomicLong();
    private final AtomicLong columnsDropped = new AtomicLong();
    private final AtomicBoolean processing = new AtomicBoolean();
    private volatile ExecutorService executor = createExecutor();
    private volatile boolean shuttingDown;
    private volatile long lastDropWarnMs;

    void offer(VoxelColumnS2CPayload payload, boolean replaceMissingSections) {
        if (shuttingDown) {
            return;
        }
        int estimatedBytes = payload.estimatedBytes();
        if (queueSize.get() < MAX_QUEUED_COLUMNS && queueBytes.get() + estimatedBytes <= MAX_QUEUED_BYTES) {
            columnQueue.add(new QueuedColumn(payload, replaceMissingSections));
            queueSize.incrementAndGet();
            queueBytes.addAndGet(estimatedBytes);
            return;
        }

        long dropped = columnsDropped.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastDropWarnMs > DROP_WARN_INTERVAL_MS) {
            lastDropWarnMs = now;
            VSSLogger.warn("Column processing queue full (" + MAX_QUEUED_COLUMNS + " columns, "
                    + (MAX_QUEUED_BYTES / 1024L / 1024L) + " MiB), " + dropped + " columns dropped total");
        }
    }

    void scheduleProcessing(boolean serverEnabled) {
        if (shuttingDown) {
            return;
        }
        if (!serverEnabled || !VSSClientConfig.CONFIG.receiveServerLods || !VSSApi.hasVoxelConsumers()) {
            clearQueue();
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clearQueue();
            return;
        }
        if (columnQueue.isEmpty()) {
            return;
        }

        if (VSSClientConfig.CONFIG.offThreadSectionProcessing) {
            if (processing.compareAndSet(false, true)) {
                try {
                    executor.execute(() -> {
                        try {
                            drainColumnQueue(level);
                        } finally {
                            processing.set(false);
                        }
                    });
                } catch (Exception e) {
                    processing.set(false);
                }
            }
        } else {
            drainColumnQueue(level);
        }
    }

    private void drainColumnQueue(ClientLevel level) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        QueuedColumn queuedColumn;
        while (!Thread.currentThread().isInterrupted() && (queuedColumn = columnQueue.poll()) != null) {
            VoxelColumnS2CPayload payload = queuedColumn.payload();
            queueSize.decrementAndGet();
            int estimatedBytes = payload.estimatedBytes();
            queueBytes.updateAndGet(value -> Math.max(0L, value - estimatedBytes));
            byte[] decompressed = payload.decompressedSections();
            if (!level.dimension().equals(payload.dimension()) || decompressed == null || decompressed.length == 0) {
                continue;
            }

            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
                try {
                    int sectionCount = Math.max(0, Math.min(buf.readVarInt(), MAX_SECTIONS_PER_COLUMN));
                    VoxelColumnData.SectionData[] sections = new VoxelColumnData.SectionData[sectionCount];
                    for (int i = 0; i < sectionCount; i++) {
                        int sectionY = buf.readByte();
                        LevelChunkSection section = new LevelChunkSection(biomeRegistry);
                        section.read(buf);

                        DataLayer blockLight = null;
                        if (buf.readBoolean()) {
                            byte[] light = new byte[DataLayer.SIZE];
                            buf.readBytes(light);
                            blockLight = new DataLayer(light);
                        }

                        DataLayer skyLight = null;
                        if (buf.readBoolean()) {
                            byte[] light = new byte[DataLayer.SIZE];
                            buf.readBytes(light);
                            skyLight = new DataLayer(light);
                        }

                        sections[i] = new VoxelColumnData.SectionData(sectionY, section, blockLight, skyLight);
                    }
                    VSSApi.dispatchColumn(
                            level,
                            payload.dimension(),
                            payload.chunkX(),
                            payload.chunkZ(),
                            new VoxelColumnData(sections, payload.columnTimestamp(), queuedColumn.replaceMissingSections()));
                } finally {
                    buf.release();
                }
            } catch (Exception e) {
                VSSLogger.error("Failed to process voxel column at " + payload.chunkX() + "," + payload.chunkZ(), e);
            }
        }
    }

    void shutdown() {
        shuttingDown = true;
        ExecutorService old = executor;
        old.shutdownNow();
        clearQueue();
        try {
            old.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        processing.set(false);
        executor = createExecutor();
        shuttingDown = false;
    }

    int getQueuedCount() {
        return queueSize.get();
    }

    long getColumnsDropped() {
        return columnsDropped.get();
    }

    void resetStats() {
        columnsDropped.set(0L);
        lastDropWarnMs = 0L;
    }

    private void clearQueue() {
        columnQueue.clear();
        queueSize.set(0);
        queueBytes.set(0L);
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "VSS-ColumnProcessor");
            thread.setDaemon(true);
            return thread;
        });
    }

    private record QueuedColumn(VoxelColumnS2CPayload payload, boolean replaceMissingSections) {
    }
}
