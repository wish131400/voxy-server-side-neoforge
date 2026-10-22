package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.api.column.ColumnDecision;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.api.column.ColumnInput;
import dev.xantha.vss.api.column.SectionDecision;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ColumnFilterSurfaceHeightTest {
    private static final AtomicReference<ColumnInput> observed = new AtomicReference<>();
    private static final int BAND_CHUNK = 32;

    @BeforeAll static void register() {
        ColumnFilterFixtures.biomes();
        ColumnFilters.register("test:surface_height", 2, column -> {
            observed.set(column);
            if (column.chunkX() != BAND_CHUNK) return ColumnDecision.updateAll();
            if (!column.hasTopSolidY()) return ColumnDecision.updateAll();
            int[] heights = column.topSolidY();
            var selected = new HashMap<Integer, SectionDecision>();
            for (var section : column.sections()) {
                // Confirmed source air must still clear obsolete Voxy content.
                boolean keep = section.onlyAir();
                long bottom = (long) section.sectionY() * 16;
                long top = bottom + 15;
                for (int surface : heights) {
                    if (surface != Integer.MIN_VALUE && surface >= bottom && (long) surface - 32 <= top) {
                        keep = true;
                        break;
                    }
                }
                if (keep) selected.put(section.sectionY(), SectionDecision.UPDATE);
            }
            return new ColumnDecision(SectionDecision.UNTOUCHED, selected);
        });
    }

    @ParameterizedTest
    @CsvSource({"-128,0,15,0,-2048", "-4,15,0,15,-49", "-1,0,15,15,-16",
            "-1,15,0,0,-1", "0,0,15,0,0", "0,15,0,15,15", "127,15,15,15,2047"})
    void horizontalHeightsUseAbsoluteYAndZMajorIndex(int sectionY, int y, int x, int z, int expected) {
        var section = section(x, y, z, Blocks.STONE);
        var context = context(sectionY, sectionY + 1);
        ColumnFilterPipeline.apply(context, source(0, new int[] {sectionY}, section));
        var input = observed.get();
        assertTrue(input.hasTopSolidY());
        int[] expectedHeights = new int[256]; Arrays.fill(expectedHeights, Integer.MIN_VALUE);
        expectedHeights[(z << 4) | x] = expected;
        assertArrayEquals(expectedHeights, input.topSolidY());
        assertEquals(expected, input.topSolidY(x, z));
        assertEquals(expected, input.section(sectionY).maxBlockY());
    }

    @Test void all256ColumnsRetainTheirOwnTopRatherThanTheSectionMaximum() {
        var section = new LevelChunkSection(ColumnFilterFixtures.biomes());
        int[] expected = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = (x + z * 3) & 15;
                section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState());
                expected[(z << 4) | x] = -32 + y;
            }
        }
        ColumnFilterPipeline.apply(context(-2, -1), source(0, new int[] {-2}, section));
        assertArrayEquals(expected, observed.get().topSolidY());
        assertEquals(-17, observed.get().section(-2).maxBlockY());
    }

    @Test void unorderedSectionsKeepTheHighestBlockAtEachHorizontalPosition() {
        ColumnFilterPipeline.apply(context(-2, 3), source(0, new int[] {2, -2, 0},
                section(1, 1, 2, Blocks.STONE), section(1, 15, 2, Blocks.STONE),
                section(1, 15, 2, Blocks.STONE)));
        assertEquals(33, observed.get().topSolidY(1, 2));
        assertEquals(33, observed.get().section(2).maxBlockY());
        assertEquals(15, observed.get().section(0).maxBlockY());
        assertEquals(-17, observed.get().section(-2).maxBlockY());
    }

    static Stream<Block> nonGroundBlocks() {
        return Stream.of(Blocks.WATER, Blocks.LAVA, Blocks.OAK_LEAVES, Blocks.TALL_GRASS);
    }

    @ParameterizedTest @MethodSource("nonGroundBlocks")
    void plantsFoliageAndFluidsCountAsNonAir(Block block) {
        var section = section(3, 1, 7, Blocks.STONE);
        section.setBlockState(3, 13, 7, block.defaultBlockState());
        ColumnFilterPipeline.apply(context(-1, 0), source(0, new int[] {-1}, section));
        assertEquals(-3, observed.get().topSolidY(3, 7));
    }

    @Test void confirmedAirHasAvailableMetadataWithEmptySentinels() {
        ColumnFilterPipeline.apply(context(-2, 3), SectionSerializer.emptyColumn(0, 7, true));
        assertTrue(observed.get().hasTopSolidY());
        assertTrue(Arrays.stream(observed.get().topSolidY()).allMatch(y -> y == Integer.MIN_VALUE));
        var air = new LevelChunkSection(ColumnFilterFixtures.biomes());
        ColumnFilterPipeline.apply(context(-2, 3), source(0, new int[] {0}, air));
        assertTrue(observed.get().hasTopSolidY());
        assertTrue(Arrays.stream(observed.get().topSolidY()).allMatch(y -> y == Integer.MIN_VALUE));
    }

    @Test void detachedSnapshotIgnoresLaterChangesToLiveSource() {
        var section = section(3, 6, 9, Blocks.STONE);
        var snapshot = new SectionSerializer.ColumnSnapshot(0, 7, new SectionSerializer.SectionSnapshot[] {
                new SectionSerializer.SectionSnapshot(-1, section.getStates().copy(), section.getBiomes(), null, null)
        }, true, context(-2, 3));
        section.setBlockState(3, 15, 9, Blocks.STONE.defaultBlockState());
        SectionSerializer.serializeSnapshot(snapshot);
        assertEquals(-10, observed.get().topSolidY(3, 9));
    }

    @Test void diskNbtUsesDetachedStatesDespiteMissingOrInvalidHeightmaps() {
        var tag = new CompoundTag(); tag.putString("Status", "minecraft:full");
        var sections = new ListTag(); var section = new CompoundTag(); section.putByte("Y", (byte) -1);
        var states = new CompoundTag(); var palette = new ListTag(); var stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone"); palette.add(stone); states.put("palette", palette);
        section.put("block_states", states); sections.add(section); tag.put("sections", sections);
        for (boolean staleHeightmap : new boolean[] {false, true}) {
            if (staleHeightmap) {
                var heightmaps = new CompoundTag(); heightmaps.putLongArray("WORLD_SURFACE", new long[] {0});
                tag.put("Heightmaps", heightmaps);
            }
            NbtSectionSerializer.serializeTag(level(-2, 3), 0, 7, Optional.of(tag));
            assertTrue(observed.get().hasTopSolidY());
            assertTrue(Arrays.stream(observed.get().topSolidY()).allMatch(y -> y == -1));
        }
    }

    @Test void chunkAccessUsesTheSameHorizontalHeightContract() {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] {
                null, section(15, 13, 2, Blocks.WATER), null, null, null});
        SectionSerializer.serializeChunkAccess(level(-2, 3), chunk, 0, 7);
        assertTrue(observed.get().hasTopSolidY());
        assertEquals(-3, observed.get().topSolidY(15, 2));
        assertEquals(Integer.MIN_VALUE, observed.get().topSolidY(2, 15));
    }

    @Test void surfaceBandPreservesValleyBesideMountainAndExplicitlyUpdatesNewAir() {
        var mountain = section(0, 6, 0, Blocks.STONE); // Y=150
        var valley = section(15, 12, 15, Blocks.STONE); // Y=60
        var deep = section(0, 0, 0, Blocks.STONE);
        var result = ColumnFilterPipeline.apply(context(-4, 12), source(BAND_CHUNK, new int[] {9, 3, 0}, mountain, valley, deep));
        assertEquals(150, observed.get().topSolidY(0, 0));
        assertEquals(60, observed.get().topSolidY(15, 15));
        assertTrue(Arrays.stream(result.sectionYs()).anyMatch(y -> y == 3), "Valley is below the old global cutoff of 118");
        assertTrue(Arrays.stream(result.sectionYs()).anyMatch(y -> y == 9));
        assertFalse(Arrays.stream(result.sectionYs()).anyMatch(y -> y == 0));
        assertFalse(result.completeColumn()); assertTrue(result.coverage().isFiltered());
        assertEquals(ColumnFilters.snapshot().cacheKey(), result.coverage().filterKey());
        // Removing the valley makes section 3 confirmed source air, not UNTOUCHED.
        result = ColumnFilterPipeline.apply(context(-4, 12), source(BAND_CHUNK, new int[] {9, 0}, mountain, deep));
        assertTrue(observed.get().section(3).onlyAir());
        assertEquals(Integer.MIN_VALUE, observed.get().topSolidY(15, 15));
        assertTrue(Arrays.stream(result.sectionYs()).anyMatch(y -> y == 3));
    }

    private static ColumnFilterPipeline.Context context(int min, int max) {
        return new ColumnFilterPipeline.Context(Level.OVERWORLD, min, max, ColumnFilterFixtures.biomes());
    }

    private static LevelChunkSection section(int x, int y, int z, Block block) {
        var section = new LevelChunkSection(ColumnFilterFixtures.biomes());
        section.setBlockState(x, y, z, block.defaultBlockState());
        return section;
    }

    private static ServerLevel level(int min, int max) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD); when(level.getMinSection()).thenReturn(min);
        when(level.getSectionsCount()).thenReturn(max - min); when(level.getMinBuildHeight()).thenReturn(min * 16);
        when(level.getHeight()).thenReturn((max - min) * 16);
        when(level.registryAccess()).thenReturn(new RegistryAccess.ImmutableRegistryAccess(List.of(ColumnFilterFixtures.biomes())));
        return level;
    }

    private static LoadedColumnData source(int chunkX, int[] sectionYs, LevelChunkSection... sections) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(sectionYs.length);
            for (int i = 0; i < sectionYs.length; i++) {
                buffer.writeByte(sectionYs[i]); sections[i].write(buffer);
                buffer.writeBoolean(false); buffer.writeBoolean(false);
            }
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
            return new LoadedColumnData(chunkX, 7, bytes, bytes.length, true);
        } finally { buffer.release(); }
    }
}
