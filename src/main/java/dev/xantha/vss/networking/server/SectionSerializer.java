package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.processing.LoadedColumnData;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

public final class SectionSerializer {
    private static final byte[] EMPTY_COLUMN_BYTES = new byte[] {0};

    private SectionSerializer() {
    }

    static LoadedColumnData emptyColumn(int cx, int cz) {
        return new LoadedColumnData(cx, cz, EMPTY_COLUMN_BYTES.clone(), EMPTY_COLUMN_BYTES.length);
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

        ArrayList<SectionSnapshot> includedSections = new ArrayList<>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }

            int sectionY = minSectionY + i;
            SectionPos sectionPos = SectionPos.of(cx, sectionY, cz);
            byte[] blockLight = copyNonZeroLightData(blockLightListener, sectionPos);
            section.acquire();
            try {
                if (section.hasOnlyAir() && blockLight == null) {
                    continue;
                }

                PalettedContainer<BlockState> states = section.getStates().copy();
                PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes().recreate();
                byte[] skyLight = copyNonZeroLightData(skyLightListener, sectionPos);
                includedSections.add(new SectionSnapshot(sectionY, states, biomes, blockLight, skyLight));
            } finally {
                section.release();
            }
        }

        return new ColumnSnapshot(cx, cz, includedSections.toArray(SectionSnapshot[]::new));
    }

    public static LoadedColumnData serializeSnapshot(ColumnSnapshot snapshot) {
        SectionSnapshot[] sections = snapshot.sections();
        if (sections.length == 0) {
            return emptyColumn(snapshot.chunkX(), snapshot.chunkZ());
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(sections.length * 1024));
        try {
            buf.writeVarInt(sections.length);
            for (SectionSnapshot info : sections) {
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
            }

            byte[] serialized = new byte[buf.readableBytes()];
            buf.readBytes(serialized);
            return new LoadedColumnData(snapshot.chunkX(), snapshot.chunkZ(), serialized, serialized.length);
        } finally {
            buf.release();
        }
    }

    private static LoadedColumnData serializeColumn(ServerLevel level, ChunkAccess chunk, int cx, int cz, boolean includeLiveLight) {
        int minSectionY = level.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();
        LevelLightEngine lightEngine = includeLiveLight ? level.getLightEngine() : null;
        LayerLightEventListener blockLightListener = lightEngine != null ? lightEngine.getLayerListener(LightLayer.BLOCK) : null;

        ArrayList<SectionInfo> includedSections = new ArrayList<>(sections.length);
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
        }

        if (includedSections.isEmpty()) {
            return emptyColumn(cx, cz);
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(sections.length * 1024));
        try {
            buf.writeVarInt(includedSections.size());
            LayerLightEventListener skyLightListener = lightEngine != null ? lightEngine.getLayerListener(LightLayer.SKY) : null;
            for (SectionInfo info : includedSections) {
                LevelChunkSection section = sections[info.index];
                buf.writeByte(info.sectionY);
                section.write(buf);
                buf.writeBoolean(info.hasBlockLight);
                if (info.hasBlockLight) {
                    buf.writeBytes(info.blockLight.getData());
                }
                DataLayer skyLight = skyLightListener != null ? skyLightListener.getDataLayerData(info.sectionPos) : null;
                boolean hasSkyLight = skyLight != null && hasNonZeroData(skyLight);
                buf.writeBoolean(hasSkyLight);
                if (hasSkyLight) {
                    buf.writeBytes(skyLight.getData());
                }
            }

            byte[] serialized = new byte[buf.readableBytes()];
            buf.readBytes(serialized);
            return new LoadedColumnData(cx, cz, serialized, serialized.length);
        } finally {
            buf.release();
        }
    }

    private static boolean hasNonZeroData(DataLayer layer) {
        for (byte b : layer.getData()) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    private static byte[] copyNonZeroLightData(LayerLightEventListener listener, SectionPos sectionPos) {
        DataLayer layer = listener != null ? listener.getDataLayerData(sectionPos) : null;
        if (layer == null || !hasNonZeroData(layer)) {
            return null;
        }
        return Arrays.copyOf(layer.getData(), layer.getData().length);
    }

    private record SectionInfo(int index, int sectionY, SectionPos sectionPos, DataLayer blockLight, boolean hasBlockLight) {
    }

    public record ColumnSnapshot(int chunkX, int chunkZ, SectionSnapshot[] sections) {
    }

    public record SectionSnapshot(
            int sectionY,
            PalettedContainer<BlockState> states,
            PalettedContainerRO<Holder<Biome>> biomes,
            byte[] blockLight,
            byte[] skyLight) {
    }
}
