package dev.xantha.vss.api;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

@FunctionalInterface
public interface VoxelColumnConsumer {
    void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData);
}
