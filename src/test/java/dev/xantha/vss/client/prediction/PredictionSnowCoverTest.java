package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import java.util.Arrays;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionSnowCoverTest {
    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void snowFlagSelectsSnowTextureWithoutChangingGroundStrata() {
        var snow = sample(90, 90, 0, ClientColumnSample.FLAG_SNOW, Blocks.GRASS_BLOCK);
        assertEquals(id(Blocks.SNOW_BLOCK), PredictionMeshBuilder.surfaceSpriteBlock(snow));
        assertEquals(id(Blocks.GRASS_BLOCK), PredictionMaterialPalette.groundBlock(snow));
        assertEquals(id(Blocks.DIRT), snow.underBlockIndex());
        assertEquals(id(Blocks.STONE), snow.deepBlockIndex());
        var captured = sample(90, 90, 0, ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_CAPTURED, Blocks.DIRT);
        assertEquals(id(Blocks.SNOW_BLOCK), PredictionMeshBuilder.surfaceSpriteBlock(captured));
    }

    @Test
    void waterWarmGroundVoidAndUnsupportedSurfacesDoNotGetSnowTexture() {
        var warm = sample(90, 90, 0, 0, Blocks.GRASS_BLOCK);
        assertEquals(id(Blocks.GRASS_BLOCK), PredictionMeshBuilder.surfaceSpriteBlock(warm));
        var lake = sample(58, 63, 1, ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE, Blocks.GRAVEL);
        assertEquals(id(Blocks.GRAVEL), PredictionMeshBuilder.surfaceSpriteBlock(lake));
        var unsupported = sample(90, 90, 0, ClientColumnSample.FLAG_SNOW, Blocks.SHORT_GRASS);
        assertEquals(id(Blocks.SHORT_GRASS), PredictionMeshBuilder.surfaceSpriteBlock(unsupported));
        var empty = sample(-64, -64, 0, ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_NO_SURFACE, Blocks.AIR);
        assertEquals(id(Blocks.AIR), PredictionMeshBuilder.surfaceSpriteBlock(empty));
    }

    @Test
    void snowAppearanceIsPresentBeforeDecorationAtEveryLodStep() {
        var samples = new ClientColumnSample[9 * 9];
        Arrays.fill(samples, sample(90, 90, 0, ClientColumnSample.FLAG_SNOW, Blocks.GRASS_BLOCK));
        int snowColor = PredictionMaterialPalette.colorFor(samples[0], 0xff44aa22);
        assertEquals(PredictionMaterialPalette.colorForIndex(id(Blocks.SNOW_BLOCK), 0), snowColor);
        int[] colors = new int[samples.length];
        Arrays.fill(colors, snowColor);
        for (int step : new int[]{1, 2, 4, 8, 16, 32, 64}) {
            var mesh = PredictionMeshBuilder.build(samples, colors, 63, 0xb22d78c5, step, 9);
            for (int i = 0; i < mesh.vertexCount(); i++) {
                assertEquals(90, mesh.y(i));
                assertEquals(snowColor & 0xffffff, mesh.color(i) & 0xffffff, "Snow lost at LOD step " + step);
            }
        }
    }

    @Test
    void vanillaSurfaceRuleKeepsGrassButPublishesItsSnowCover() {
        var vanilla = VanillaRegistries.createLookup();
        var settings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        vanilla.lookupOrThrow(Registries.BIOME).listElements().forEach(h -> biomes.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        var heights = LevelHeightAccessor.create(-64, 384);
        for (String name : new String[]{"snowy_plains", "plains", "desert"}) {
            var biome = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(name)));
            var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings);
            var random = RandomState.create(settings.value(), vanilla.lookupOrThrow(Registries.NOISE), 0);
            var resolver = new ClientSurfaceResolver(generator, random, heights, biomes);
            var resolved = resolver.resolve(sample(90, 90, 0, 0, Blocks.GRASS_BLOCK), 0, 0, (x, z) -> 90);
            boolean cold = biome.value().coldEnoughToSnow(new BlockPos(0, 90, 0));
            assertEquals(cold, resolved.snow(), name);
            if (cold) {
                assertEquals(id(Blocks.GRASS_BLOCK), resolved.topBlockIndex(), "Ground stays below the snow layer");
                assertEquals(id(Blocks.SNOW_BLOCK), PredictionMeshBuilder.surfaceSpriteBlock(resolved));
            } else assertNotEquals(id(Blocks.SNOW_BLOCK), PredictionMeshBuilder.surfaceSpriteBlock(resolved));
        }
    }

    private static int id(net.minecraft.world.level.block.Block block) { return BuiltInRegistries.BLOCK.getId(block); }

    private static ClientColumnSample sample(int y, int waterY, int fluid, int flags, net.minecraft.world.level.block.Block block) {
        return new ClientColumnSample(y, waterY, 0, id(block), 0, 0, 0, 0, fluid, flags, 0,
                id(Blocks.DIRT), id(Blocks.STONE), ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
}
