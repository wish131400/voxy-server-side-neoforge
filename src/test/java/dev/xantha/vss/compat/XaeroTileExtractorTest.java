package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.xantha.vss.api.VoxelColumnData;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class XaeroTileExtractorTest {
    private static final int BOTTOM_Y = -64;
    private static final int TOP_Y = 320;
    private static Registry<net.minecraft.world.level.biome.Biome> biomeRegistry;

    @BeforeAll
    static void bootstrapMinecraft() {
        LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.RegistryLookup<net.minecraft.world.level.biome.Biome> lookup =
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        MappedRegistry<net.minecraft.world.level.biome.Biome> registry =
                new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.listElements().forEach(ref ->
                registry.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        registry.freeze();
        biomeRegistry = registry;
    }

    private static LevelChunkSection section() {
        return new LevelChunkSection(biomeRegistry);
    }

    private static VoxelColumnData column(int sectionY, LevelChunkSection section, DataLayer light) {
        return new VoxelColumnData(new VoxelColumnData.SectionData[] {
                new VoxelColumnData.SectionData(sectionY, section, light, null)
        }, 1L);
    }

    private static XaeroTileExtractor.PreparedTile extract(VoxelColumnData data) {
        return XaeroTileExtractor.extract(3, 5, BOTTOM_Y, TOP_Y, data);
    }

    private static int index(int x, int z) {
        return x * 16 + z;
    }

    @Test
    void voidColumnProducesTheEraseShape() {
        var tile = extract(new VoxelColumnData(new VoxelColumnData.SectionData[0], 1L));
        int i = index(8, 8);
        assertEquals(Blocks.AIR.defaultBlockState(), tile.floorState()[i]);
        assertEquals(BOTTOM_Y, tile.floorY()[i]);
        assertEquals(BOTTOM_Y, tile.topY()[i]);
        assertEquals(0, tile.light()[i]);
        assertNull(tile.overlays()[i]);
    }

    @Test
    void opaqueSurfaceBecomesTheFloor() {
        var section = section();
        section.setBlockState(2, 4, 3, Blocks.GRASS_BLOCK.defaultBlockState());
        var tile = extract(column(-4, section, null));
        int i = index(2, 3);
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), tile.floorState()[i]);
        assertEquals(-60, tile.floorY()[i]);
        assertEquals(-60, tile.topY()[i]);
        assertFalse(tile.glowing()[i]);
    }

    @Test
    void waterFormsOneOverlayRunAboveTheFloor() {
        var section = section();
        section.setBlockState(2, 5, 3, Blocks.STONE.defaultBlockState());
        section.setBlockState(2, 6, 3, Blocks.WATER.defaultBlockState());
        section.setBlockState(2, 7, 3, Blocks.WATER.defaultBlockState());
        section.setBlockState(2, 8, 3, Blocks.WATER.defaultBlockState());
        var tile = extract(column(-4, section, null));
        int i = index(2, 3);
        assertEquals(Blocks.STONE.defaultBlockState(), tile.floorState()[i]);
        assertEquals(-56, tile.topY()[i]);
        assertNotNull(tile.overlays()[i]);
        assertEquals(1, tile.overlays()[i].length);
        assertEquals(3, tile.overlays()[i][0].opacity());
    }

    @Test
    void blockLightIsSampledAboveTheFloor() {
        var section = section();
        section.setBlockState(6, 4, 6, Blocks.STONE.defaultBlockState());
        var light = new DataLayer();
        light.set(6, 4, 6, 4);
        light.set(6, 5, 6, 13);
        var tile = extract(column(-4, section, light));
        assertEquals(13, tile.light()[index(6, 6)]);
    }
}
