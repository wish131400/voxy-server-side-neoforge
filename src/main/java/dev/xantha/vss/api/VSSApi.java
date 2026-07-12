package dev.xantha.vss.api;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class VSSApi {
    private static final List<VoxelColumnConsumer> COLUMN_CONSUMERS = new CopyOnWriteArrayList<>();
    private static final List<VoxelColumnProcessingConsumer> PROCESSING_COLUMN_CONSUMERS = new CopyOnWriteArrayList<>();

    private VSSApi() {
    }

    public static void registerColumnConsumer(VoxelColumnConsumer consumer) {
        COLUMN_CONSUMERS.add(consumer);
        VSSLogger.info("Registered voxel column consumer: " + consumer.getClass().getName());
    }

    public static void registerColumnProcessingConsumer(VoxelColumnProcessingConsumer consumer) {
        PROCESSING_COLUMN_CONSUMERS.add(consumer);
        VSSLogger.info("Registered voxel column processing consumer: " + consumer.getClass().getName());
    }

    public static boolean hasVoxelConsumers() {
        return !COLUMN_CONSUMERS.isEmpty() || !PROCESSING_COLUMN_CONSUMERS.isEmpty();
    }

    public static boolean isServerEnabled() {
        return VSSClientNetworking.isServerEnabled();
    }

    public static int getServerLodDistance() {
        return VSSClientNetworking.getServerLodDistance();
    }

    public static void dispatchColumn(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData) {
        for (VoxelColumnConsumer consumer : COLUMN_CONSUMERS) {
            try {
                consumer.onVoxelColumnReceived(level, dimension, chunkX, chunkZ, columnData);
            } catch (Exception e) {
                VSSLogger.error("Voxel column consumer threw exception", e);
            }
        }
    }

    public static boolean dispatchColumnAndReport(
            ClientLevel level,
            ResourceKey<Level> dimension,
            int chunkX,
            int chunkZ,
            VoxelColumnData columnData) {
        boolean handled = false;
        boolean accepted = true;
        for (VoxelColumnConsumer consumer : COLUMN_CONSUMERS) {
            handled = true;
            try {
                consumer.onVoxelColumnReceived(level, dimension, chunkX, chunkZ, columnData);
            } catch (Exception e) {
                accepted = false;
                VSSLogger.error("Voxel column consumer threw exception", e);
            }
        }
        for (VoxelColumnProcessingConsumer consumer : PROCESSING_COLUMN_CONSUMERS) {
            handled = true;
            try {
                accepted &= consumer.processVoxelColumn(level, dimension, chunkX, chunkZ, columnData);
            } catch (Exception e) {
                accepted = false;
                VSSLogger.error("Voxel column processing consumer threw exception", e);
            }
        }
        return handled && accepted;
    }
}
