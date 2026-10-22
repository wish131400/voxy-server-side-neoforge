package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.api.column.ColumnDecision;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.api.column.ColumnInput;
import dev.xantha.vss.api.column.SectionDecision;
import dev.xantha.vss.common.processing.ColumnCoverage;
import dev.xantha.vss.common.processing.LoadedColumnData;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The single source-side filter boundary for live snapshots and disk NBT. Runs
 * before compression/cache admission; no live chunk or light-engine access occurs.
 * All byte ranges/manifests are rebuilt here, not by third-party callbacks.
 */
public final class ColumnFilterPipeline {
    private ColumnFilterPipeline() { }

    public record Context(ResourceKey<Level> dimension, int minSectionY, int maxSectionY,
            Registry<Biome> biomes) {
        public static Context capture(ServerLevel level) {
            return new Context(level.dimension(), level.getMinSection(),
                    level.getMinSection() + level.getSectionsCount(),
                    level.registryAccess().registryOrThrow(Registries.BIOME));
        }
    }

    public static LoadedColumnData apply(Context context, LoadedColumnData source) {
        ColumnFilters.Snapshot filters = ColumnFilters.snapshot();
        if (source == null || !source.completeColumn() || filters.isEmpty()) return source;
        if (context == null) throw new IllegalArgumentException("Missing column filter source context");
        int[] topSolidY = new int[256];
        Arrays.fill(topSolidY, Integer.MIN_VALUE);
        Map<Integer, ParsedSection> parsed = parse(context, source.sectionBytes(), topSolidY);
        ArrayList<ColumnInput.Section> sections = new ArrayList<>();
        for (int y = context.minSectionY(); y < context.maxSectionY(); y++) {
            ParsedSection section = parsed.get(y);
            sections.add(new ColumnInput.Section(y, section == null || section.onlyAir(),
                    section == null ? Integer.MIN_VALUE : section.maxBlockY(),
                    section == null ? null : section.blockLight(), section == null ? null : section.skyLight()));
        }
        ColumnDecision decision = filters.decide(new ColumnInput(context.dimension(), source.chunkX(), source.chunkZ(),
                context.minSectionY(), context.maxSectionY(), topSolidY, sections));
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ArrayList<Integer> ys = new ArrayList<>();
            ArrayList<Integer> lengths = new ArrayList<>();
            for (int y = context.minSectionY(); y < context.maxSectionY(); y++) {
                SectionDecision selected = decision.section(y);
                if (selected.action() == SectionDecision.Action.UNTOUCHED) continue;
                if (y < Byte.MIN_VALUE || y > Byte.MAX_VALUE) throw new IllegalArgumentException("Section Y outside wire range");
                int start = body.writerIndex();
                body.writeByte(y);
                ParsedSection section = parsed.get(y);
                if (selected.action() == SectionDecision.Action.UPDATE && section != null) {
                    body.writeBytes(source.sectionBytes(), section.blockStart(), section.blockLength());
                } else {
                    new LevelChunkSection(context.biomes()).write(body);
                }
                writeLight(body, section == null || selected.lightMode() != SectionDecision.LightMode.KEEP
                        ? null : section.blockLight());
                writeLight(body, section == null || selected.lightMode() == SectionDecision.LightMode.NONE
                        ? null : section.skyLight());
                ys.add(y);
                lengths.add(body.writerIndex() - start);
            }
            if (ys.size() > 64) throw new IllegalArgumentException("Filtered column exceeds 64 wire sections");
            output.writeVarInt(ys.size());
            output.writeBytes(body);
            byte[] bytes = new byte[output.readableBytes()];
            output.readBytes(bytes);
            // Even an all-UPDATE filter is a scoped result: its policy/cache identity
            // must remain distinguishable from an unfiltered authoritative column.
            return new LoadedColumnData(source.chunkX(), source.chunkZ(), bytes, bytes.length, false,
                    ys.stream().mapToInt(Integer::intValue).toArray(),
                    lengths.stream().mapToInt(Integer::intValue).toArray(), new ColumnCoverage(filters.cacheKey()));
        } finally {
            body.release();
            output.release();
        }
    }

    private static Map<Integer, ParsedSection> parse(Context context, byte[] bytes, int[] topSolidY) {
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int count = input.readVarInt();
            // Source dimensions may contain more than 64 sections. The policy
            // may select a smaller band; enforce the wire limit on that output.
            if (count < 0 || count > 256 || count > context.maxSectionY() - context.minSectionY())
                throw new IllegalArgumentException("Invalid source section count");
            Map<Integer, ParsedSection> sections = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int y = input.readByte();
                if (y < context.minSectionY() || y >= context.maxSectionY() || sections.containsKey(y)) {
                    throw new IllegalArgumentException("Invalid source section Y: " + y);
                }
                int start = input.readerIndex();
                LevelChunkSection section = new LevelChunkSection(context.biomes());
                section.read(input);
                int length = input.readerIndex() - start;
                sections.put(y, new ParsedSection(start, length, section.hasOnlyAir(), collectHeights(section, y, topSolidY),
                        readLight(input), readLight(input)));
            }
            if (input.isReadable()) throw new IllegalArgumentException("Trailing source column bytes");
            return sections;
        } finally {
            input.release();
        }
    }

    private static int collectHeights(LevelChunkSection section, int sectionY, int[] topSolidY) {
        if (section.hasOnlyAir()) return Integer.MIN_VALUE;
        // Compute both metadata forms in one pass over the already detached
        // section. Each horizontal column stops at its first non-air block;
        // no voxel is queried twice and stale source Heightmaps are irrelevant.
        int maxBlockY = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 15; y >= 0; y--) {
                    if (!section.getBlockState(x, y, z).isAir()) {
                        int blockY = (sectionY << 4) + y;
                        maxBlockY = Math.max(maxBlockY, blockY);
                        int index = (z << 4) | x;
                        // Source sections need not arrive in height order.
                        topSolidY[index] = Math.max(topSolidY[index], blockY);
                        break;
                    }
                }
            }
        }
        return maxBlockY;
    }

    private static byte[] readLight(FriendlyByteBuf input) {
        if (!input.readBoolean()) return null;
        byte[] light = new byte[DataLayer.SIZE];
        input.readBytes(light);
        return light;
    }

    private static void writeLight(FriendlyByteBuf output, byte[] light) {
        output.writeBoolean(light != null);
        if (light != null) output.writeBytes(light);
    }

    private record ParsedSection(int blockStart, int blockLength, boolean onlyAir, int maxBlockY,
            byte[] blockLight, byte[] skyLight) { }
}
