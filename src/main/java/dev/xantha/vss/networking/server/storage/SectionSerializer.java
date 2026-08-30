package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.common.processing.LoadedColumnData;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.OptionalInt;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

public final class SectionSerializer {
    private static final byte[] EMPTY_COLUMN_BYTES = new byte[] {0};
    private static final byte[] EMPTY_LIGHT_DATA = new byte[DataLayer.SIZE];

    private SectionSerializer() {
    }

    public static LoadedColumnData emptyColumn(int cx, int cz, boolean completeColumn) {
        return new LoadedColumnData(cx, cz, EMPTY_COLUMN_BYTES.clone(), EMPTY_COLUMN_BYTES.length, completeColumn);
    }

    public static LoadedColumnData serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        return serializeColumn(level, (ChunkAccess) chunk, cx, cz, true);
    }

    public static LoadedColumnData serializeChunkAccess(ServerLevel level, ChunkAccess chunk, int cx, int cz) {
        return serializeColumn(level, chunk, cx, cz, false);
    }

    public static ColumnSnapshot snapshotColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        int minSectionY = level.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);
        boolean requiresSkyLight = level.dimensionType().hasSkyLight();

        ArrayList<SectionSnapshot> includedSections = new ArrayList<>(sections.length);
        int highestIncludedSectionY = Integer.MIN_VALUE;
        boolean missingSkyLight = false;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }

            int sectionY = minSectionY + i;
            SectionPos sectionPos = SectionPos.of(cx, sectionY, cz);
            LightData blockLight = copyLightData(blockLightListener, sectionPos);
            section.acquire();
            try {
                if (section.hasOnlyAir() && blockLight.data() == null) {
                    continue;
                }

                PalettedContainer<BlockState> states = section.getStates().copy();
                // `recreate()` only initializes a new container from the first
                // palette value; it drops all per-cell biome entries.  Keep a
                // detached copy so custom biome grass/foliage colors survive
                // asynchronous packing and transmission to Voxy.
                PalettedContainer<Holder<Biome>> biomes = copyBiomes(level, section.getBiomes());
                LightData skyLight = copyLightData(skyLightListener, sectionPos);
                if (!skyLight.present()) {
                    missingSkyLight = true;
                }
                includedSections.add(new SectionSnapshot(sectionY, states, biomes, blockLight.data(), skyLight.data()));
                highestIncludedSectionY = Math.max(highestIncludedSectionY, sectionY);
            } finally {
                section.release();
            }
        }

        boolean completeColumn = hasCompleteRequiredLighting(requiresSkyLight, missingSkyLight)
                && isCompleteColumn(level, chunk, highestIncludedSectionY, includedSections.isEmpty());
        return new ColumnSnapshot(cx, cz, includedSections.toArray(SectionSnapshot[]::new), completeColumn);
    }

    public static LoadedColumnData serializeSnapshot(ColumnSnapshot snapshot) {
        SectionSnapshot[] sections = snapshot.sections();
        if (sections.length == 0) {
            return emptyColumn(snapshot.chunkX(), snapshot.chunkZ(), snapshot.completeColumn());
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(sections.length * 1024));
        try {
            buf.writeVarInt(sections.length);
            int[] sectionLengths = new int[sections.length];
            for (int i = 0; i < sections.length; i++) {
                SectionSnapshot info = sections[i];
                int sectionStart = buf.writerIndex();
                LevelChunkSection section = new LevelChunkSection(info.states(), info.biomes());
                buf.writeByte(info.sectionY());
                section.write(buf);
                buf.writeBoolean(info.blockLight() != null);
                if (info.blockLight() != null) {
                    buf.writeBytes(info.blockLight());
                }
                buf.writeBoolean(info.skyLight() != null);
                if (info.skyLight() != null) {
                    buf.writeBytes(info.skyLight());
                }
                sectionLengths[i] = buf.writerIndex() - sectionStart;
            }

            byte[] serialized = new byte[buf.readableBytes()];
            buf.readBytes(serialized);
            return new LoadedColumnData(
                    snapshot.chunkX(),
                    snapshot.chunkZ(),
                    serialized,
                    serialized.length,
                    snapshot.completeColumn(),
                    sectionYs(sections),
                    sectionLengths);
        } finally {
            buf.release();
        }
    }

    private static LoadedColumnData serializeColumn(ServerLevel level, ChunkAccess chunk, int cx, int cz, boolean includeLiveLight) {
        int minSectionY = level.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();
        LevelLightEngine lightEngine = includeLiveLight ? level.getLightEngine() : null;
        LayerLightEventListener blockLightListener = lightEngine != null ? lightEngine.getLayerListener(LightLayer.BLOCK) : null;
        boolean requiresSkyLight = includeLiveLight && level.dimensionType().hasSkyLight();

        ArrayList<SectionInfo> includedSections = new ArrayList<>(sections.length);
        int highestIncludedSectionY = Integer.MIN_VALUE;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }
            int sectionY = minSectionY + i;
            SectionPos sectionPos = SectionPos.of(cx, sectionY, cz);
            DataLayer blockLight = blockLightListener != null ? blockLightListener.getDataLayerData(sectionPos) : null;
            boolean hasBlockLight = blockLight != null && hasNonZeroData(blockLight);
            if (section.hasOnlyAir() && !hasBlockLight) {
                continue;
            }
            includedSections.add(new SectionInfo(i, sectionY, sectionPos, blockLight, hasBlockLight));
            highestIncludedSectionY = Math.max(highestIncludedSectionY, sectionY);
        }

        if (includedSections.isEmpty()) {
            boolean completeColumn = isCompleteColumn(level, chunk, Integer.MIN_VALUE, true);
            return emptyColumn(cx, cz, completeColumn);
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(sections.length * 1024));
        try {
            buf.writeVarInt(includedSections.size());
            LayerLightEventListener skyLightListener = lightEngine != null ? lightEngine.getLayerListener(LightLayer.SKY) : null;
            boolean missingSkyLight = false;
            int[] sectionLengths = new int[includedSections.size()];
            for (int i = 0; i < includedSections.size(); i++) {
                SectionInfo info = includedSections.get(i);
                int sectionStart = buf.writerIndex();
                LevelChunkSection section = sections[info.index];
                buf.writeByte(info.sectionY);
                section.write(buf);
                buf.writeBoolean(info.hasBlockLight);
                if (info.hasBlockLight) {
                    buf.writeBytes(info.blockLight.getData());
                }
                DataLayer skyLight = skyLightListener != null ? skyLightListener.getDataLayerData(info.sectionPos) : null;
                if (skyLight == null) {
                    missingSkyLight = true;
                }
                boolean hasSkyLight = skyLight != null && hasNonZeroData(skyLight);
                buf.writeBoolean(hasSkyLight);
                if (hasSkyLight) {
                    buf.writeBytes(skyLight.getData());
                }
                sectionLengths[i] = buf.writerIndex() - sectionStart;
            }

            byte[] serialized = new byte[buf.readableBytes()];
            buf.readBytes(serialized);
            boolean completeColumn = hasCompleteRequiredLighting(requiresSkyLight, missingSkyLight)
                    && isCompleteColumn(level, chunk, highestIncludedSectionY, false);
            return new LoadedColumnData(
                    cx,
                    cz,
                    serialized,
                    serialized.length,
                    completeColumn,
                    sectionYs(includedSections),
                    sectionLengths);
        } finally {
            buf.release();
        }
    }

    private static boolean isCompleteColumn(ServerLevel level, ChunkAccess chunk, int highestIncludedSectionY, boolean emptyColumn) {
        OptionalInt surfaceSection = highestSurfaceSection(level, chunk);
        if (surfaceSection.isEmpty()) {
            return emptyColumn;
        }
        return highestIncludedSectionY >= surfaceSection.getAsInt();
    }

    private static OptionalInt highestSurfaceSection(ServerLevel level, ChunkAccess chunk) {
        int minBuildHeight = level.getMinBuildHeight();
        int highestSurfaceBlockY = Integer.MIN_VALUE;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                int surfaceBlockY = height - 1;
                if (surfaceBlockY >= minBuildHeight) {
                    highestSurfaceBlockY = Math.max(highestSurfaceBlockY, surfaceBlockY);
                }
            }
        }
        return highestSurfaceBlockY == Integer.MIN_VALUE
                ? OptionalInt.empty()
                : OptionalInt.of(SectionPos.blockToSectionCoord(highestSurfaceBlockY));
    }

    private static boolean hasNonZeroData(DataLayer layer) {
        return !Arrays.equals(layer.getData(), EMPTY_LIGHT_DATA);
    }

    private static int[] sectionYs(SectionSnapshot[] sections) {
        int[] sectionYs = new int[sections.length];
        for (int i = 0; i < sections.length; i++) {
            sectionYs[i] = sections[i].sectionY();
        }
        return sectionYs;
    }

    private static int[] sectionYs(ArrayList<SectionInfo> sections) {
        int[] sectionYs = new int[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            sectionYs[i] = sections.get(i).sectionY();
        }
        return sectionYs;
    }

    private static PalettedContainer<Holder<Biome>> copyBiomes(
            ServerLevel level,
            PalettedContainerRO<Holder<Biome>> source) {
        if (source instanceof PalettedContainer<?> container) {
            @SuppressWarnings("unchecked")
            PalettedContainer<Holder<Biome>> typed = (PalettedContainer<Holder<Biome>>) container;
            return typed.copy();
        }

        // NeoForge normally uses PalettedContainer, but retain a serialization
        // fallback for compatibility implementations that expose only the
        // PalettedContainerRO interface.
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(source.getSerializedSize()));
        try {
            source.write(buf);
            LevelChunkSection detached = new LevelChunkSection(
                    level.registryAccess().registryOrThrow(Registries.BIOME));
            detached.readBiomes(buf);
            @SuppressWarnings("unchecked")
            PalettedContainer<Holder<Biome>> typed =
                    (PalettedContainer<Holder<Biome>>) detached.getBiomes();
            return typed;
        } finally {
            buf.release();
        }
    }

    static boolean hasCompleteRequiredLighting(boolean requiresSkyLight, boolean missingSkyLight) {
        return !requiresSkyLight || !missingSkyLight;
    }

    private static LightData copyLightData(LayerLightEventListener listener, SectionPos sectionPos) {
        DataLayer layer = listener != null ? listener.getDataLayerData(sectionPos) : null;
        if (layer == null) {
            return LightData.missing();
        }
        if (!hasNonZeroData(layer)) {
            return LightData.empty();
        }
        return new LightData(Arrays.copyOf(layer.getData(), layer.getData().length), true);
    }

    private record SectionInfo(int index, int sectionY, SectionPos sectionPos, DataLayer blockLight, boolean hasBlockLight) {
    }

    private record LightData(byte[] data, boolean present) {
        private static LightData empty() {
            return new LightData(null, true);
        }

        private static LightData missing() {
            return new LightData(null, false);
        }
    }

    public record ColumnSnapshot(int chunkX, int chunkZ, SectionSnapshot[] sections, boolean completeColumn) {
        private static final long ESTIMATED_CONTAINER_BYTES_PER_SECTION = 24L * 1024L;

        public long estimatedRetainedBytes() {
            long bytes = 128L + (long) sections.length * ESTIMATED_CONTAINER_BYTES_PER_SECTION;
            for (SectionSnapshot section : sections) {
                bytes += section.blockLight() != null ? section.blockLight().length : 0;
                bytes += section.skyLight() != null ? section.skyLight().length : 0;
            }
            return bytes;
        }
    }

    public record SectionSnapshot(
            int sectionY,
            PalettedContainer<BlockState> states,
            PalettedContainerRO<Holder<Biome>> biomes,
            byte[] blockLight,
            byte[] skyLight) {
    }
}
