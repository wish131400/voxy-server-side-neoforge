package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionFlatTerrainSamplerTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    private static PredictionFlatTerrainSampler sampler(int minY, int height, int[] counts, Block... states) {
        var root = new JsonObject(); var data = new JsonObject(); var layers = new JsonArray();
        var worldgen = ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), TerrainPredictionFixtures.access());
        for (int i = 0; i < counts.length; i++) {
            var layer = new JsonObject(); layer.addProperty("height", counts[i]);
            layer.add("state", net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(worldgen.ops(), states[i].defaultBlockState()).getOrThrow());
            layers.add(layer);
        }
        data.add("layers", layers); data.addProperty("biome", "minecraft:plains"); root.add("flat_layers", data);
        root.add(TerrainPredictionPlan.FIELD, new TerrainPredictionPlan(TerrainPredictionPlan.Kind.FLAT_LAYERS,
                "vss:flat_layers", 1, false, "").encode());
        return new PredictionFlatTerrainSampler(new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42, minY, height, "flat", "flat", 0), root, worldgen);
    }
    @Test void solidAndFluidLayersKeepAbsoluteDimensionCoordinatesAndActualMaterials() {
        var sampler = sampler(-64, 384, new int[]{2, 3, 1, 4}, Blocks.BEDROCK, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.WATER);
        var sample = sampler.sampleSurface(0, 0);
        assertEquals(-58, sample.surfaceY()); assertEquals(-54, sample.fluidY()); assertEquals(1, sample.fluid());
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK), sample.topBlockIndex());
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.DIRT), sample.underBlockIndex());
        assertSame(sample, sampler.sampleForLod(20000000, -20000000, 64));
        assertTrue(sampler.exactWorldgen()); assertFalse(sampler.interiorTerrain());
        assertEquals(java.util.List.of("minecraft:plains"), sampler.cacheBiomes());
    }
    @Test void voidIsNotTurnedIntoAPhantomGroundAndFluidOnlyColumnsStayVisible() {
        var empty = sampler(-64, 384, new int[]{5}, Blocks.AIR).sample(1, 2);
        assertEquals(-64, empty.surfaceY()); assertFalse(empty.hasSurface()); assertFalse(empty.hasFluid());
        var water = sampler(-64, 384, new int[]{5}, Blocks.WATER).sample(1, 2);
        assertFalse(water.hasSurface()); assertTrue(water.hasFluid()); assertEquals(-59, water.fluidY());
    }
    @Test void solidAboveFluidDoesNotLeaveAFalseOceanAtTheSurface() {
        var sample = sampler(0, 256, new int[]{10, 1}, Blocks.WATER, Blocks.STONE).sample(1, 2);
        assertEquals(11, sample.surfaceY()); assertEquals(11, sample.fluidY()); assertFalse(sample.hasFluid());
    }


    @Test void layerHeightAndUnknownMaterialsFailInsteadOfChangingTheTerrain() {
        assertThrows(IllegalArgumentException.class, () -> sampler(0, 256, new int[]{-1}, Blocks.STONE));
        assertThrows(IllegalArgumentException.class, () -> sampler(0, 256, new int[]{4097}, Blocks.STONE));
        var clipped = sampler(-64, 128, new int[]{300}, Blocks.STONE).sample(1, 2);
        assertEquals(64, clipped.surfaceY());
    }
    @Test void columnVolumesCopiedByThePublicFactoryAreImmutable() {
        int block = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        int[] runs = {-64, 0, block, 0}; var volume = PredictionColumnVolume.ofRuns(runs);
        runs[1] = 20; assertEquals(0, volume.top(0));
        assertThrows(IllegalArgumentException.class, () -> PredictionColumnVolume.ofRuns(new int[]{0, -1, block, 0}));
    }
}
