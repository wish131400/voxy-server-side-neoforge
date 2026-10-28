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
        assertEquals(64, captured.surfaceBottom(), "the one-block ground must not extend through captured air");
        assertTrue(captured.hasLowerSpan(), "buried blocks still occupy space below the natural surface");
        assertEquals(48, captured.lowerTop());
        assertTrue(PredictionWallEvidence.intervals(captured, 48, 64, 1).isEmpty());

        section.setBlockState(8, 0, 8, Blocks.OBSIDIAN.defaultBlockState());
        below.setBlockState(8, 15, 8, Blocks.COARSE_DIRT.defaultBlockState());
        var natural = new VoxelColumnData(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(4, section, null, null),
                new VoxelColumnData.SectionData(3, below, null, null)}, 1L, true);
        assertEquals(65, ClientCaptureExtractor.extract(0, 0, natural, sampler()).surfaceY(),
                "supported surface obsidian remains a valid ground material");
    }



    @Test
    void completeCaptureCutsOnlyActualBoundedAir() {
        var section = section();
        section.setBlockState(8,15,8,Blocks.COARSE_DIRT.defaultBlockState());
        section.setBlockState(8,0,8,Blocks.IRON_ORE.defaultBlockState());
        var data = new VoxelColumnData(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(0,section,null,null)},1L,true);
        var captured = ClientCaptureExtractor.extract(0,0,data,sampler());
        assertTrue(PredictionWallEvidence.hasInterior(captured));
        assertEquals(15,captured.surfaceBottom());
        assertEquals(1,captured.lowerTop());
        assertTrue(PredictionWallEvidence.intervals(captured,1,15,1).isEmpty());
        assertFalse(PredictionWallEvidence.intervals(captured,1,15,4).isEmpty());
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
