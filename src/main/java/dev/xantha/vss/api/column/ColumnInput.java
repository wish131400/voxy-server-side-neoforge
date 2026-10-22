package dev.xantha.vss.api.column;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Detached complete source column. Every section in [minSectionY, maxSectionY) is
 * represented, including source air omitted by the ordinary serializer. Light
 * presence describes serialized data, not proof that Minecraft's light engine is ready.
 * No player-dependent state is supplied: results are shared by all players and cached.
 *
 * @param topSolidY 256 highest non-air absolute block Ys, indexed by (z << 4) | x;
 *        includes foliage, plants and fluids despite the compatibility name.
 *        Integer.MIN_VALUE means an empty horizontal column. Null marks unknown
 *        metadata (for example an input constructed through the original constructor).
 *        This is not a ground height or a guarantee that lower surfaces are hidden.
 */
public record ColumnInput(ResourceKey<Level> dimension, int chunkX, int chunkZ,
        int minSectionY, int maxSectionY, int[] topSolidY, List<Section> sections) {
    /** Retains the original constructor descriptor; horizontal heights are unknown. */
    public ColumnInput(ResourceKey<Level> dimension, int chunkX, int chunkZ,
            int minSectionY, int maxSectionY, List<Section> sections) {
        this(dimension, chunkX, chunkZ, minSectionY, maxSectionY, null, sections);
    }

    public ColumnInput {
        Objects.requireNonNull(dimension, "dimension");
        sections = List.copyOf(sections);
        if (maxSectionY <= minSectionY || sections.size() != maxSectionY - minSectionY) {
            throw new IllegalArgumentException("Incomplete column filter snapshot");
        }
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).sectionY() != minSectionY + i) {
                throw new IllegalArgumentException("Unordered column filter snapshot");
            }
        }
        if (topSolidY != null) {
            if (topSolidY.length != 256) throw new IllegalArgumentException("Expected 256 horizontal heights");
            topSolidY = topSolidY.clone();
            long minY = (long) minSectionY * 16;
            long maxY = (long) maxSectionY * 16;
            for (int y : topSolidY) {
                if (y != Integer.MIN_VALUE && (y < minY || y >= maxY)) {
                    throw new IllegalArgumentException("Horizontal height outside column range");
                }
            }
        }
    }

    /** Whether the complete horizontal height map is available, even if all 256 columns are air. */
    public boolean hasTopSolidY() { return topSolidY != null; }

    /** Defensive copy, always 256 entries; unknown metadata yields all Integer.MIN_VALUE. */
    @Override public int[] topSolidY() {
        if (topSolidY != null) return topSolidY.clone();
        int[] unknown = new int[256];
        Arrays.fill(unknown, Integer.MIN_VALUE);
        return unknown;
    }

    /** Allocation-free access with local X/Z in [0, 15]; unknown/air yields Integer.MIN_VALUE. */
    public int topSolidY(int localX, int localZ) {
        if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) {
            throw new IndexOutOfBoundsException("Expected local X/Z in [0, 15]");
        }
        return topSolidY == null ? Integer.MIN_VALUE : topSolidY[(localZ << 4) | localX];
    }

    public Section section(int sectionY) {
        return sections.get(sectionY - minSectionY);
    }

    /**
     * Detached section metadata; light arrays are copied on input and output.
     *
     * @param sectionY section coordinate, in units of 16 blocks
     * @param onlyAir whether the source section contains only air
     * @param maxBlockY absolute block Y of the highest non-air block, including
     *        foliage and fluids, not a ground height; {@link Integer#MIN_VALUE}
     *        means empty or unknown (use {@code onlyAir} to distinguish them).
     *        VSS computes this for every non-empty section supplied to a filter.
     * @param blockLight block light, 2048 bytes or null
     * @param skyLight sky light, 2048 bytes or null
     */
    public record Section(int sectionY, boolean onlyAir, int maxBlockY, byte[] blockLight, byte[] skyLight) {
        /** Retains the original constructor descriptor; non-air height is unknown. */
        public Section(int sectionY, boolean onlyAir, byte[] blockLight, byte[] skyLight) {
            this(sectionY, onlyAir, Integer.MIN_VALUE, blockLight, skyLight);
        }

        public Section {
            blockLight = copyLight(blockLight);
            skyLight = copyLight(skyLight);
        }

        @Override public byte[] blockLight() { return blockLight == null ? null : blockLight.clone(); }
        @Override public byte[] skyLight() { return skyLight == null ? null : skyLight.clone(); }
        public boolean hasBlockLight() { return blockLight != null; }
        public boolean hasSkyLight() { return skyLight != null; }

        private static byte[] copyLight(byte[] light) {
            if (light != null && light.length != 2048) throw new IllegalArgumentException("Invalid section light array");
            return light == null ? null : light.clone();
        }
    }
}
