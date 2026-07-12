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
        if (!payload.hasTransferMetadata()) {
            throw new IllegalArgumentException("LOD transfer id must be assigned before splitting");
        }

        byte[] rawSections = payload.decompressedSections();
        ParsedSections parsed = readSections(level, payload, rawSections);
        int[] replacementSectionYs = payload.completeColumn() && parsed.valid()
                ? replacementSectionYs(parsed.sections())
                : payload.replacementSectionYs();
        VoxelColumnS2CPayload normalized = payload.withTransferMetadata(
                payload.transferId(),
                0,
                1,
                replacementSectionYs);
        int targetWireBytes = targetWireBytes(effectiveBandwidthBytesPerSecond);
        if (normalized.estimatedWireBytes(networkCompressionEnabled) <= targetWireBytes) {
            return List.of(normalized);
        }

        if (!parsed.valid() || parsed.sections().size() <= 1) {
            return List.of(normalized);
        }

        List<SerializedSection> sections = parsed.sections();
        sections.sort(Comparator.comparingInt(SerializedSection::sectionY).reversed());
        ArrayList<List<SerializedSection>> groups = new ArrayList<>();
        ArrayList<SerializedSection> currentGroup = new ArrayList<>(MAX_SECTIONS_PER_GROUP);
        int currentBytes = 0;

        for (SerializedSection section : sections) {
            boolean groupFull = currentGroup.size() >= MAX_SECTIONS_PER_GROUP;
            boolean groupTooLarge = !currentGroup.isEmpty() && currentBytes + section.bytes().length > targetWireBytes;
            if (groupFull || groupTooLarge) {
                groups.add(List.copyOf(currentGroup));
                currentGroup = new ArrayList<>(MAX_SECTIONS_PER_GROUP);
                currentBytes = 0;
            }
            currentGroup.add(section);
            currentBytes += section.bytes().length;
        }

        if (!currentGroup.isEmpty()) {
            groups.add(List.copyOf(currentGroup));
        }

        if (groups.size() <= 1) {
            return List.of(normalized);
        }
        if (groups.size() > VoxelColumnS2CPayload.MAX_TRANSFER_PARTS) {
            throw new IllegalArgumentException("LOD column exceeds transfer part limit: " + groups.size());
        }

        ArrayList<VoxelColumnS2CPayload> splitPayloads = new ArrayList<>(groups.size());
        for (int partIndex = 0; partIndex < groups.size(); partIndex++) {
            boolean finalPart = partIndex == groups.size() - 1;
            addGroup(
                    splitPayloads,
                    normalized,
                    groups.get(partIndex),
                    partIndex,
                    groups.size(),
                    finalPart && payload.completeColumn() ? replacementSectionYs : new int[0]);
        }
        return splitPayloads;
    }

    static int targetWireBytes(long effectiveBandwidthBytesPerSecond) {
        return BandwidthProfile.targetWireBytes(effectiveBandwidthBytesPerSecond);
    }

    private static ParsedSections readSections(
            ServerLevel level,
            VoxelColumnS2CPayload payload,
            byte[] rawSections) {
        if (rawSections == null || rawSections.length == 0) {
            return ParsedSections.invalid();
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(rawSections));
        try {
            int sectionCount = buf.readVarInt();
            if (sectionCount < 0 || sectionCount > 64) {
                return ParsedSections.invalid();
            }

            Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            ArrayList<SerializedSection> sections = new ArrayList<>(sectionCount);
            for (int i = 0; i < sectionCount; i++) {
                if (!buf.isReadable()) {
                    return ParsedSections.invalid();
                }
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
            if (buf.isReadable()) {
                return ParsedSections.invalid();
            }
            return new ParsedSections(sections, true);
        } catch (Exception e) {
            VSSLogger.debug("Failed to split oversized LOD column at "
                    + payload.chunkX() + "," + payload.chunkZ()
                    + ": " + e.getMessage());
            return ParsedSections.invalid();
        } finally {
            buf.release();
        }
    }

    private static void addGroup(
            ArrayList<VoxelColumnS2CPayload> payloads,
            VoxelColumnS2CPayload source,
            List<SerializedSection> sections,
            int partIndex,
            int partCount,
            int[] replacementSectionYs) {
        byte[] groupedSections = writeGroup(sections);
        payloads.add(new VoxelColumnS2CPayload(
                source.requestId(),
                source.chunkX(),
                source.chunkZ(),
                source.dimension(),
                source.columnTimestamp(),
                groupedSections,
                source.completeColumn(),
                source.transferId(),
                partIndex,
                partCount,
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

    private record ParsedSections(List<SerializedSection> sections, boolean valid) {
        private static ParsedSections invalid() {
            return new ParsedSections(new ArrayList<>(), false);
        }
    }
}
