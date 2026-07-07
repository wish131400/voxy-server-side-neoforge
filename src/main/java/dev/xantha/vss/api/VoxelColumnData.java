package dev.xantha.vss.api;

import java.util.Arrays;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

public record VoxelColumnData(
        SectionData[] sections,
        long columnTimestamp,
        boolean replaceMissingSections,
        int[] replacementSectionYs,
        boolean completesRequest) {
    public VoxelColumnData(SectionData[] sections, long columnTimestamp) {
        this(sections, columnTimestamp, false);
    }

    public VoxelColumnData(SectionData[] sections, long columnTimestamp, boolean replaceMissingSections) {
        this(sections, columnTimestamp, replaceMissingSections, new int[0]);
    }

    public VoxelColumnData(
            SectionData[] sections,
            long columnTimestamp,
            boolean replaceMissingSections,
            int[] replacementSectionYs) {
        this(sections, columnTimestamp, replaceMissingSections, replacementSectionYs, true);
    }

    public VoxelColumnData {
        if (sections == null) {
            sections = new SectionData[0];
        }
        if (replacementSectionYs == null) {
            replacementSectionYs = new int[0];
        } else {
            replacementSectionYs = Arrays.copyOf(replacementSectionYs, replacementSectionYs.length);
        }
    }

    @Override
    public int[] replacementSectionYs() {
        return Arrays.copyOf(replacementSectionYs, replacementSectionYs.length);
    }

    public record SectionData(int sectionY, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {
    }
}
