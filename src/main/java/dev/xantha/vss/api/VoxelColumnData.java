package dev.xantha.vss.api;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

public record VoxelColumnData(SectionData[] sections, long columnTimestamp) {

    public record SectionData(int sectionY, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {
    }
}
