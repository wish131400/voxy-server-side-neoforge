package dev.xantha.vss.api;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

public record VoxelColumnData(SectionData[] sections, long columnTimestamp, boolean replaceMissingSections) {
    public VoxelColumnData(SectionData[] sections, long columnTimestamp) {
        this(sections, columnTimestamp, false);
    }

    public record SectionData(int sectionY, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {
    }
}
