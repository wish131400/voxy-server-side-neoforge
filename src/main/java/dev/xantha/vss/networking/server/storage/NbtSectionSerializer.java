package dev.xantha.vss.networking.server.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import dev.xantha.vss.common.processing.LoadedColumnData;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;

public final class NbtSectionSerializer {
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] EMPTY_LIGHT_DATA = new byte[2048];

    private NbtSectionSerializer() {
    }

    public static LoadedColumnData readAndSerializeSections(ServerLevel level, ChunkStorage storage, int cx, int cz, long timeoutMillis) throws Exception {
        Optional<CompoundTag> optionalTag;
        try {
            optionalTag = storage.read(new ChunkPos(cx, cz)).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        }
        return serializeTag(level, cx, cz, optionalTag);
    }

    public static LoadedColumnData readAndSerializeSections(ServerLevel level, ChunkStorage storage, int cx, int cz) throws Exception {
        Optional<CompoundTag> optionalTag = storage.read(new ChunkPos(cx, cz)).get(10L, TimeUnit.SECONDS);
        return serializeTag(level, cx, cz, optionalTag);
    }

    public static CompletableFuture<Optional<CompoundTag>> readSectionsAsync(
            ChunkStorage storage,
            int cx,
            int cz,
            long timeoutMillis) {
        return storage.read(new ChunkPos(cx, cz)).orTimeout(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    public static LoadedColumnData serializeTag(
            ServerLevel level,
            int cx,
            int cz,
            Optional<CompoundTag> optionalTag) {
        return serializeTag(level.registryAccess(), level.getMinBuildHeight(), level.getHeight(), cx, cz, optionalTag);
    }

    static LoadedColumnData serializeTag(
            RegistryAccess registries, int minY, int height, int cx, int cz,
            Optional<CompoundTag> optionalTag) {
        if (optionalTag.isEmpty()) {
            return null;
        }

        CompoundTag chunkNbt = optionalTag.get();
        ChunkType type = ChunkSerializer.getChunkTypeFromTag(chunkNbt);
        if (type != ChunkType.LEVELCHUNK) {
            return null;
        }

        ListTag sections = chunkNbt.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        if (sections.isEmpty() || height <= 0) {
            return null;
        }

        Registry<Biome> biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Codec<PalettedContainer<BlockState>> blockStateCodec = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY,
                BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState());
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(),
                biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                defaultBiome);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(sections.size() * 1024));
        int includedCount = 0;
        int minSectionY = SectionPos.blockToSectionCoord(minY);
        int maxSectionY = minSectionY + (height >> 4);
        boolean skippedUnserializableSection = false;
        ArrayList<Integer> includedSectionYs = new ArrayList<>(sections.size());
        ArrayList<Integer> includedSectionLengths = new ArrayList<>(sections.size());
        try {
            for (Tag tag : sections) {
                if (!(tag instanceof CompoundTag sectionTag) || !sectionTag.contains("Y", Tag.TAG_ANY_NUMERIC)) {
                    skippedUnserializableSection = true;
                    continue;
                }

                int sectionY = sectionTag.getByte("Y");
                if (sectionY < minSectionY || sectionY >= maxSectionY) {
                    // Vanilla and C2ME serialize one extra light section above/below
                    // the block range. Omitting those does not omit any terrain.
                    if ((sectionY != minSectionY - 1 && sectionY != maxSectionY)
                            || sectionTag.contains("block_states") || sectionTag.contains("biomes")) {
                        skippedUnserializableSection = true;
                    }
                    continue;
                }

                ParsedSection section = parseSection(sectionTag, blockStateCodec, biomeCodec, ops, biomeRegistry, defaultBiome);
                if (section == null) {
                    if (sectionTag.contains("block_states")) {
                        skippedUnserializableSection = true;
                    }
                    continue;
                }

                byte[] blockLight = getByteArray(sectionTag, ChunkSerializer.BLOCK_LIGHT_TAG);
                boolean hasBlockLight = blockLight.length == 2048 && hasNonZeroData(blockLight);
                if (section.nonEmptyBlockCount() == 0 && !hasBlockLight) {
                    continue;
                }

                byte[] skyLight = getByteArray(sectionTag, ChunkSerializer.SKY_LIGHT_TAG);
                boolean hasSkyLight = skyLight.length == 2048 && hasNonZeroData(skyLight);
                int sectionStart = buf.writerIndex();
                buf.writeByte(sectionY);
                writeSection(buf, section);
                buf.writeBoolean(hasBlockLight);
                if (hasBlockLight) {
                    buf.writeBytes(blockLight);
                }
                buf.writeBoolean(hasSkyLight);
                if (hasSkyLight) {
                    buf.writeBytes(skyLight);
                }
                includedSectionLengths.add(buf.writerIndex() - sectionStart);
                includedCount++;
                includedSectionYs.add(sectionY);
            }

            if (includedCount == 0) {
                return SectionSerializer.emptyColumn(cx, cz,
                        !skippedUnserializableSection);
            }

            byte[] serialized;
            FriendlyByteBuf column = new FriendlyByteBuf(Unpooled.buffer(buf.readableBytes() + 5));
            try {
                // A tall dimension can include >=128 sections. Write its final
                // VarInt separately so a wider count cannot overwrite section Y.
                column.writeVarInt(includedCount);
                column.writeBytes(buf);
                serialized = new byte[column.readableBytes()];
                column.readBytes(serialized);
            } finally {
                column.release();
            }
            // Full chunk NBT is authoritative: missing air sections and stale
            // heightmaps do not mean terrain was omitted. Parsing/range failures
            // above still prevent an incomplete column from replacing real LOD.
            boolean completeColumn = !skippedUnserializableSection;
            int[] sectionYs = includedSectionYs.stream().mapToInt(Integer::intValue).toArray();
            int[] sectionLengths = includedSectionLengths.stream().mapToInt(Integer::intValue).toArray();
            return new LoadedColumnData(
                    cx,
                    cz,
                    serialized,
                    serialized.length,
                    completeColumn,
                    sectionYs,
                    sectionLengths);
        } finally {
            buf.release();
        }
    }

    private static ParsedSection parseSection(
            CompoundTag sectionTag,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            DynamicOps<Tag> ops,
            Registry<Biome> biomeRegistry,
            Holder<Biome> defaultBiome) {
        Tag blockStatesTag = sectionTag.get("block_states");
        if (blockStatesTag == null) {
            return null;
        }

        Optional<PalettedContainer<BlockState>> blockStates = blockStateCodec.parse(ops, blockStatesTag).result();
        if (blockStates.isEmpty()) {
            return null;
        }

        Tag biomesTag = sectionTag.get("biomes");
        PalettedContainerRO<Holder<Biome>> biomes = biomesTag != null
                ? biomeCodec.parse(ops, biomesTag).result().orElseGet(() -> defaultBiomes(biomeRegistry, defaultBiome))
                : defaultBiomes(biomeRegistry, defaultBiome);
        PalettedContainer<BlockState> states = blockStates.get();
        int[] nonEmptyBlockCount = new int[1];
        states.count((state, count) -> {
            if (!state.isAir()) {
                nonEmptyBlockCount[0] += count;
            }
            if (!state.getFluidState().isEmpty()) {
                nonEmptyBlockCount[0] += count;
            }
        });
        return new ParsedSection(states, biomes, nonEmptyBlockCount[0]);
    }

    private static void writeSection(FriendlyByteBuf buf, ParsedSection section) {
        buf.writeShort(section.nonEmptyBlockCount());
        section.blockStates().write(buf);
        section.biomes().write(buf);
    }

    private static PalettedContainerRO<Holder<Biome>> defaultBiomes(Registry<Biome> biomeRegistry, Holder<Biome> defaultBiome) {
        return new PalettedContainer<>(biomeRegistry.asHolderIdMap(), defaultBiome, PalettedContainer.Strategy.SECTION_BIOMES);
    }

    private static byte[] getByteArray(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_BYTE_ARRAY) ? tag.getByteArray(key) : EMPTY;
    }

    private static boolean hasNonZeroData(byte[] data) {
        return data.length != EMPTY_LIGHT_DATA.length || !Arrays.equals(data, EMPTY_LIGHT_DATA);
    }

    private record ParsedSection(
            PalettedContainer<BlockState> blockStates,
            PalettedContainerRO<Holder<Biome>> biomes,
            int nonEmptyBlockCount) {
    }
}
