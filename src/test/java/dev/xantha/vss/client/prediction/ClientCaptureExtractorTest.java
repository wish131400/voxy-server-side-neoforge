package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientCaptureExtractorTest {
    @BeforeAll
    static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void dryBareCaptureRemovesPredictedTreesPlantsAndWater() {
        var biomeRegistry = new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(
                Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        var vanillaLookup = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        biomeRegistry.register(net.minecraft.world.level.biome.Biomes.PLAINS,
                vanillaLookup.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value(),
                net.minecraft.core.RegistrationInfo.BUILT_IN);
        biomeRegistry.freeze();
        var section = new LevelChunkSection(biomeRegistry);
        section.setBlockState(8, 0, 8, Blocks.COARSE_DIRT.defaultBlockState());
        var data = new VoxelColumnData(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(4, section, null, null)}, 1L, true);
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 1L);
        var sampler = new ClientTerrainSampler(42L, profile) {
            @Override public ClientColumnSample sample(int x, int z) {
                return new ClientColumnSample(60, 70, 0, 0, 0, 1, 255, 12, 1,
                        ClientColumnSample.FLAG_TREE_HERE | ClientColumnSample.FLAG_SNOW, 1, 0, 0,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        };
        var captured = ClientCaptureExtractor.extract(0, 0, data, sampler);
        assertEquals(65, captured.surfaceY());
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.COARSE_DIRT), captured.topBlockIndex());
        assertEquals(0, captured.treeKind());
        assertEquals(0, captured.flags() & ClientColumnSample.FLAG_TREE_HERE);
        assertEquals(0, captured.groundFeatureKind());
        assertEquals(0, captured.fluid());
        assertFalse(captured.snow());
        assertTrue(captured.captured());
    }

    @Test
    void completeEmptyCaptureErasesPredictedSurfaceWhilePartialDataDoesNot() {
        var sampler = sampler();
        var empty = new VoxelColumnData(new VoxelColumnData.SectionData[0], 1L, true);
        var captured = ClientCaptureExtractor.extract(0, 0, empty, sampler);
        assertTrue(captured.captured());
        assertFalse(captured.hasSurface());
        assertFalse(captured.hasFluid());
        assertEquals(0, captured.structureIndex());
        ClientColumnSample[] grid = new ClientColumnSample[81];
        java.util.Arrays.fill(grid, captured);
        for (int spacing : new int[]{1, 2, 4, 8, 16, 32}) {
            var mesh = PredictionMeshBuilder.build(grid, null, 63, 0, spacing, 9,
                    true, null, null, null, 0, 0, PredictionVegetation.Tile.EMPTY);
            assertEquals(0, mesh.vertexCount(), "empty authoritative terrain cannot render a floor");
            assertEquals(0, mesh.waterVertexCount());
        }
        var partial = ClientCaptureExtractor.extract(0, 0,
                new VoxelColumnData(empty.sections(), 1L, false, new int[0], false), sampler);
        assertFalse(partial.captured());
        assertTrue(partial.hasSurface());
        assertEquals(64, partial.surfaceY());
        assertTrue(ClientCaptureExtractor.extract(0, 0,
                new VoxelColumnData(empty.sections(), 1L, false), sampler).captured(),
                "a fresh complete column need not replace existing Voxy storage");
    }

    @Test
    void portalFrameAndUndergroundBuildingDoNotBecomeExtrudedTerrain() {
        var section = section();
        section.setBlockState(8, 0, 8, Blocks.COARSE_DIRT.defaultBlockState());
        for (int y = 4; y <= 13; y++) section.setBlockState(8, y, 8, Blocks.OBSIDIAN.defaultBlockState());
        var below = section();
        for (int y = 0; y < 16; y++) below.setBlockState(8, y, 8, Blocks.STONE_BRICKS.defaultBlockState());
        var data = new VoxelColumnData(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(4, section, null, null),
                new VoxelColumnData.SectionData(2, below, null, null)}, 1L, true);
        var captured = ClientCaptureExtractor.extract(0, 0, data, sampler());
        assertEquals(65, captured.surfaceY(), "portal lintel must not raise the terrain to its roof");
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.COARSE_DIRT), captured.topBlockIndex());
        assertEquals(0, captured.structureIndex());
        assertFalse(captured.floating());
        assertFalse(captured.hasLowerSpan());

        section.setBlockState(8, 0, 8, Blocks.OBSIDIAN.defaultBlockState());
        below.setBlockState(8, 15, 8, Blocks.COARSE_DIRT.defaultBlockState());
        var natural = new VoxelColumnData(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(4, section, null, null),
                new VoxelColumnData.SectionData(3, below, null, null)}, 1L, true);
        assertEquals(65, ClientCaptureExtractor.extract(0, 0, natural, sampler()).surfaceY(),
                "supported surface obsidian remains a valid ground material");
    }

    private static ClientTerrainSampler sampler() {
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 1L);
        return new ClientTerrainSampler(42L, profile) {
            @Override public ClientColumnSample sample(int x, int z) {
                return new ClientColumnSample(64, 64, 0,
                        BuiltInRegistries.BLOCK.getId(Blocks.COARSE_DIRT), 255, 1, 255, 12, 0,
                        ClientColumnSample.FLAG_TREE_HERE, 1, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        };
    }

    static LevelChunkSection section() {
        var registry = new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(
                Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        registry.register(net.minecraft.world.level.biome.Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                        .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value(),
                net.minecraft.core.RegistrationInfo.BUILT_IN);
        registry.freeze();
        return new LevelChunkSection(registry);
    }
}
