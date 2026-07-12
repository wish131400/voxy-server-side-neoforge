package dev.xantha.vss.api;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A column consumer that can report whether it applied a complete column.
 */
@FunctionalInterface
public interface VoxelColumnProcessingConsumer {
    boolean processVoxelColumn(
            ClientLevel level,
            ResourceKey<Level> dimension,
            int chunkX,
            int chunkZ,
            VoxelColumnData columnData);
}
