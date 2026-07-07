package dev.xantha.vss.networking.server.sending;

import dev.xantha.vss.common.BandwidthProfile;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ColumnPayloadSplitter {
    private static final int MAX_SECTIONS_PER_GROUP = 8;

    private ColumnPayloadSplitter() {
    }

    public static List<VoxelColumnS2CPayload> splitForBandwidth(
            ServerLevel level,
            VoxelColumnS2CPayload payload,
            long effectiveBandwidthBytesPerSecond,
            boolean networkCompressionEnabled) {
        int targetWireBytes = targetWireBytes(effectiveBandwidthBytesPerSecond);
        if (payload.estimatedWireBytes(networkCompressionEnabled) <= targetWireBytes) {
            return List.of(payload);
        }

        byte[] rawSections = payload.decompressedSections();
        if (rawSections == null || rawSections.length <= 1) {
            return List.of(payload);
        }

        List<SerializedSection> sections = readSections(level, payload, rawSections);
        if (sections.size() <= 1) {
            return List.of(payload);
        }

        sections.sort(Comparator.comparingInt(SerializedSection::sectionY).reversed());
        int[] replacementSectionYs = replacementSectionYs(sections);
        ArrayList<VoxelColumnS2CPayload> splitPayloads = new ArrayList<>();
        ArrayList<SerializedSection> currentGroup = new ArrayList<>(MAX_SECTIONS_PER_GROUP);
        int currentBytes = 0;

        for (SerializedSection section : sections) {
            boolean groupFull = currentGroup.size() >= MAX_SECTIONS_PER_GROUP;
            boolean groupTooLarge = !currentGroup.isEmpty() && currentBytes + section.bytes().length > targetWireBytes;
            if (groupFull || groupTooLarge) {
                addGroup(splitPayloads, payload, currentGroup, false, false, new int[0]);
                currentGroup.clear();
                currentBytes = 0;
            }
            currentGroup.add(section);
            currentBytes += section.bytes().length;
        }

        if (!currentGroup.isEmpty()) {
            addGroup(
                    splitPayloads,
                    payload,
                    currentGroup,
                    payload.completeColumn(),
                    true,
                    payload.completeColumn() ? replacementSectionYs : new int[0]);
        }

        if (splitPayloads.size() <= 1) {
            return List.of(payload);
        }
        return splitPayloads;
    }

    static int targetWireBytes(long effectiveBandwidthBytesPerSecond) {
        return BandwidthProfile.targetWireBytes(effectiveBandwidthBytesPerSecond);
    }

    private static List<SerializedSection> readSections(
            ServerLevel level,
            VoxelColumnS2CPayload payload,
            byte[] rawSections) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(rawSections));
        try {
            int sectionCount = buf.readVarInt();
            if (sectionCount <= 0 || sectionCount > 64) {
                return List.of();
            }

            Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            ArrayList<SerializedSection> sections = new ArrayList<>(sectionCount);
            for (int i = 0; i < sectionCount && buf.isReadable(); i++) {
                int start = buf.readerIndex();
                int sectionY = buf.readByte();
                LevelChunkSection section = new LevelChunkSection(biomeRegistry);
                section.read(buf);
                if (buf.readBoolean()) {
                    buf.skipBytes(DataLayer.SIZE);
                }
                if (buf.readBoolean()) {
                    buf.skipBytes(DataLayer.SIZE);
                }
                int end = buf.readerIndex();
                byte[] bytes = new byte[end - start];
                System.arraycopy(rawSections, start, bytes, 0, bytes.length);
                sections.add(new SerializedSection(sectionY, bytes));
            }
            return sections;
        } catch (Exception e) {
            VSSLogger.debug("Failed to split oversized LOD column at "
                    + payload.chunkX() + "," + payload.chunkZ()
                    + ": " + e.getMessage());
            return List.of();
        } finally {
            buf.release();
        }
    }

    private static void addGroup(
            ArrayList<VoxelColumnS2CPayload> payloads,
            VoxelColumnS2CPayload source,
            List<SerializedSection> sections,
            boolean completeColumn,
            boolean completesRequest,
            int[] replacementSectionYs) {
        byte[] groupedSections = writeGroup(sections);
        payloads.add(new VoxelColumnS2CPayload(
                source.requestId(),
                source.chunkX(),
                source.chunkZ(),
                source.dimension(),
                source.columnTimestamp(),
                groupedSections,
                completeColumn,
                completesRequest,
                replacementSectionYs));
    }

    private static byte[] writeGroup(List<SerializedSection> sections) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(sections.size());
            for (SerializedSection section : sections) {
                buf.writeBytes(section.bytes());
            }
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static int[] replacementSectionYs(List<SerializedSection> sections) {
        int[] sectionYs = new int[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            sectionYs[i] = sections.get(i).sectionY();
        }
        return sectionYs;
    }

    private record SerializedSection(int sectionY, byte[] bytes) {
    }
}
