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
    static final int MAX_QUEUED_COLUMNS = 1024;
    private static final long MAX_QUEUED_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_COLUMNS_PER_DRAIN = 48;
    private static final int MAX_SECTIONS_DISPATCHED_PER_DRAIN = 512;
    private static final int MAX_SECTIONS_PER_COLUMN = 64;
    private static final long DROP_WARN_INTERVAL_MS = 5000L;
    private static final long COLUMN_PROCESS_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;

    private final ConcurrentLinkedQueue<QueuedColumn> priorityColumnQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<QueuedColumn> columnQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicInteger priorityQueueSize = new AtomicInteger();
    private final AtomicLong queueBytes = new AtomicLong();
    private final AtomicLong columnsDropped = new AtomicLong();
    private final AtomicBoolean processing = new AtomicBoolean();
    private final AtomicInteger sessionEpoch = new AtomicInteger();
    private volatile ExecutorService executor;
    private volatile boolean shuttingDown = true;
    private volatile long lastDropWarnMs;
    private volatile long lastColumnProcessDiagnosticNanos;

    void beginSession() {
        sessionEpoch.incrementAndGet();
        clearQueue();
        processing.set(false);
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown() || currentExecutor.isTerminated()) {
            executor = createExecutor();
        }
        shuttingDown = false;
    }

    boolean offer(VoxelColumnS2CPayload payload, boolean knownRequest, boolean priority, boolean replaceMissingSections) {
        if (shuttingDown) {
            return false;
        }
        if (knownRequest && payload.decompressedSections().length <= 1) {
            processEmptyColumn(payload, sessionEpoch.get(), replaceMissingSections);
            return true;
        }

        int estimatedBytes = payload.estimatedBytes();
        if (queueSize.get() < MAX_QUEUED_COLUMNS && queueBytes.get() + estimatedBytes <= MAX_QUEUED_BYTES) {
            if (priority) {
                priorityColumnQueue.add(new QueuedColumn(payload, replaceMissingSections));
                priorityQueueSize.incrementAndGet();
            } else {
                columnQueue.add(new QueuedColumn(payload, replaceMissingSections));
            }
            queueSize.incrementAndGet();
            queueBytes.addAndGet(estimatedBytes);
            return true;
        }

        long dropped = columnsDropped.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastDropWarnMs > DROP_WARN_INTERVAL_MS) {
            lastDropWarnMs = now;
            VSSLogger.warn("Column processing queue full (" + MAX_QUEUED_COLUMNS + " columns, "
                    + (MAX_QUEUED_BYTES / 1024L / 1024L) + " MiB), " + dropped + " columns dropped total");
        }
        return false;
    }

    private void processEmptyColumn(VoxelColumnS2CPayload payload, int epoch, boolean replaceMissingSections) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            ClientLevel level = minecraft.level;
            if (sessionEpoch.get() != epoch
                    || shuttingDown
                    || !VSSClientNetworking.isClientLodSessionActive()
                    || level == null
                    || !level.dimension().equals(payload.dimension())) {
                return;
            }
            logColumnProcess(payload, 0, Integer.MAX_VALUE, Integer.MIN_VALUE, replaceMissingSections);
            VSSApi.dispatchColumn(
                    level,
                    payload.dimension(),
                    payload.chunkX(),
                    payload.chunkZ(),
                    new VoxelColumnData(
                            new VoxelColumnData.SectionData[0],
                            payload.columnTimestamp(),
                            replaceMissingSections));
        });
    }

    void scheduleProcessing(boolean serverEnabled) {
        if (shuttingDown) {
            return;
        }
        if (!serverEnabled || !VSSClientConfig.CONFIG.receiveServerLods) {
            clearQueue();
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clearQueue();
            return;
        }
        if (priorityColumnQueue.isEmpty() && columnQueue.isEmpty()) {
            return;
        }

        if (VSSClientConfig.CONFIG.offThreadSectionProcessing) {
            if (processing.compareAndSet(false, true)) {
                int epoch = sessionEpoch.get();
                ExecutorService currentExecutor = executor;
                if (currentExecutor == null || currentExecutor.isShutdown()) {
                    processing.set(false);
                    return;
                }
                try {
                    currentExecutor.execute(() -> {
                        try {
                            drainColumnQueue(level, epoch);
                        } finally {
                            processing.set(false);
                        }
                    });
                } catch (Exception e) {
                    processing.set(false);
                }
            }
        } else {
            drainColumnQueue(level, sessionEpoch.get());
        }
    }

    private void drainColumnQueue(ClientLevel level, int epoch) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        boolean hasConsumers = VSSApi.hasVoxelConsumers();

        QueuedColumn queuedColumn;
        int processedColumns = 0;
        int dispatchedSections = 0;
        while (processedColumns < MAX_COLUMNS_PER_DRAIN
                && dispatchedSections < MAX_SECTIONS_DISPATCHED_PER_DRAIN
                && !Thread.currentThread().isInterrupted()
                && sessionEpoch.get() == epoch
                && (queuedColumn = pollQueuedColumn()) != null) {
            if (shuttingDown || !VSSClientNetworking.isClientLodSessionActive()) {
                clearQueue();
                return;
            }
            VoxelColumnS2CPayload payload = queuedColumn.payload();
            queueSize.decrementAndGet();
            int estimatedBytes = payload.estimatedBytes();
            queueBytes.updateAndGet(value -> Math.max(0L, value - estimatedBytes));
            byte[] decompressed = payload.decompressedSections();
            if (sessionEpoch.get() != epoch || !level.dimension().equals(payload.dimension()) || decompressed == null || decompressed.length == 0) {
                continue;
            }

            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
                try {
                    int sectionCount = Math.max(0, Math.min(buf.readVarInt(), MAX_SECTIONS_PER_COLUMN));
                    VoxelColumnData.SectionData[] sections = new VoxelColumnData.SectionData[sectionCount];
                    int minSectionY = Integer.MAX_VALUE;
                    int maxSectionY = Integer.MIN_VALUE;
                    for (int i = 0; i < sectionCount; i++) {
                        int sectionY = buf.readByte();
                        minSectionY = Math.min(minSectionY, sectionY);
                        maxSectionY = Math.max(maxSectionY, sectionY);
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
                    if (sessionEpoch.get() != epoch || shuttingDown) {
                        return;
                    }
                    if (!VSSClientNetworking.isClientLodSessionActive()) {
                        clearQueue();
                        return;
                    }
                    if (sessionEpoch.get() != epoch || shuttingDown || !VSSClientNetworking.isClientLodSessionActive()) {
                        clearQueue();
                        return;
                    }
                    if (hasConsumers) {
                        logColumnProcess(payload, sections.length, minSectionY, maxSectionY, queuedColumn.replaceMissingSections());
                        VSSApi.dispatchColumn(
                                level,
                                payload.dimension(),
                                payload.chunkX(),
                                payload.chunkZ(),
                                new VoxelColumnData(sections, payload.columnTimestamp(), queuedColumn.replaceMissingSections()));
                    } else {
                        if (processedColumns == 0) {
                            VSSLogger.debug("Processing LOD column at " + payload.chunkX() + "," + payload.chunkZ()
                                    + " with " + sections.length + " sections, but no consumers registered");
                        }
                    }
                    processedColumns++;
                    dispatchedSections += sections.length;
                } finally {
                    buf.release();
                }
            } catch (Exception e) {
                VSSLogger.error("Failed to process voxel column at " + payload.chunkX() + "," + payload.chunkZ(), e);
                VSSClientNetworking.onColumnProcessingFailed(payload.dimension(), payload.chunkX(), payload.chunkZ());
            }
        }
    }

    void shutdown() {
        shuttingDown = true;
        sessionEpoch.incrementAndGet();
        ExecutorService old = executor;
        clearQueue();
        processing.set(false);
        executor = null;
        if (old != null) {
            old.shutdownNow();
        }
        VSSLogger.debug("Stopped VSS column processor and cleared queued LOD columns");
    }

    private void logColumnProcess(
            VoxelColumnS2CPayload payload,
            int sectionCount,
            int minSectionY,
            int maxSectionY,
            boolean replaceMissingSections) {
        long now = System.nanoTime();
        if (now - lastColumnProcessDiagnosticNanos < COLUMN_PROCESS_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastColumnProcessDiagnosticNanos = now;
        String sectionRange = sectionCount > 0 ? minSectionY + ".." + maxSectionY : "empty";
        VSSLogger.info("LOD column processed: chunk=" + payload.chunkX() + "," + payload.chunkZ()
                + ", sections=" + sectionCount
                + ", y=" + sectionRange
                + ", complete=" + payload.completeColumn()
                + ", replaceMissing=" + replaceMissingSections
                + ", rawBytes=" + payload.rawSectionBytesLength()
                + ", queue=" + getQueuedCount());
    }

    boolean isActive() {
        return !shuttingDown;
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
        lastColumnProcessDiagnosticNanos = 0L;
    }

    private void clearQueue() {
        priorityColumnQueue.clear();
        columnQueue.clear();
        queueSize.set(0);
        priorityQueueSize.set(0);
        queueBytes.set(0L);
    }

    private QueuedColumn pollQueuedColumn() {
        QueuedColumn queuedColumn = priorityColumnQueue.poll();
        if (queuedColumn != null) {
            priorityQueueSize.updateAndGet(value -> Math.max(0, value - 1));
            return queuedColumn;
        }
        return columnQueue.poll();
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
