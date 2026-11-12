package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Surface materials of a profile measured against vanilla's seeded biome zoom (Twilight Forest's biome source) follow
 * the biome BiomeManager selects for each block, as written terrain does; other profiles keep the raw quart lookup.
 */
class SurfaceBiomeZoomTest {
    private static final long SEED = 7_046_381_115_771L;
    private static final int SURFACE = 100;

    @BeforeAll
    static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    /** Desert and plains alternate per quart, so the fuzzy zoom changes the biome near every quart edge. */
    private static final class Checker extends BiomeSource {
        final Holder<Biome> desert, plains;
        Checker(Holder<Biome> desert, Holder<Biome> plains) { this.desert = desert; this.plains = plains; }
        @Override protected MapCodec<? extends BiomeSource> codec() { throw new UnsupportedOperationException(); }
        @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return Stream.of(desert, plains); }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
            return ((x ^ z) & 1) == 0 ? desert : plains;
        }
    }

    private record Fixture(ClientSurfaceResolver resolver, Checker source, PredictionBiomeCache cache) { }

    private static Fixture fixture() {
        var access = TerrainPredictionFixtures.access();
        var biomes = access.registryOrThrow(Registries.BIOME);
        var source = new Checker(biomes.getHolderOrThrow(Biomes.DESERT), biomes.getHolderOrThrow(Biomes.PLAINS));
        var settings = access.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator = new NoiseBasedChunkGenerator(source, settings);
        var random = RandomState.create(settings.value(), access.lookupOrThrow(Registries.NOISE), SEED);
        var cache = new PredictionBiomeCache(source, random.sampler());
        return new Fixture(new ClientSurfaceResolver(generator, random, LevelHeightAccessor.create(-64, 384), biomes, cache), source, cache);
    }

    private static int top(ClientSurfaceResolver resolver, int x, int z) {
        var sample = new ClientColumnSample(SURFACE, ClientColumnSample.NO_SPAN, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        return resolver.resolve(sample, x, z, new ClientTerrainSampler.TerrainFunction() {
            @Override public int surfaceY(int bx, int bz) { return SURFACE; }
        }).topBlockIndex();
    }

    private static int expected(Holder<Biome> biome) {
        return BuiltInRegistries.BLOCK.getId(biome.is(Biomes.DESERT) ? Blocks.SAND : Blocks.GRASS_BLOCK);
    }

    @Test
    void onlyTwilightForestsBiomeSourceSelectsTheSeededZoom() {
        var twilight = new JsonObject();
        var source = new JsonObject();
        source.addProperty("type", "twilightforest:twilight_biomes");
        twilight.add("biome_source", source);
        assertTrue(ClientSurfaceResolver.seededZoomSurface(twilight));
        var vanilla = new JsonObject();
        var multiNoise = new JsonObject();
        multiNoise.addProperty("type", "minecraft:multi_noise");
        vanilla.add("biome_source", multiNoise);
        assertFalse(ClientSurfaceResolver.seededZoomSurface(vanilla));
        assertFalse(ClientSurfaceResolver.seededZoomSurface(new JsonObject()));
        assertFalse(ClientSurfaceResolver.seededZoomSurface(null));
    }

    @Test
    void decoderBindsTheZoomForTwilightForestProfilesOnly() throws Exception {
        var fallback = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        net.minecraft.core.RegistryAccess access;
        try (var resources = new net.minecraft.server.packs.resources.MultiPackResourceManager(
                net.minecraft.server.packs.PackType.SERVER_DATA,
                java.util.List.of(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource()))) {
            var dynamic = net.minecraft.resources.RegistryDataLoader.load(resources, fallback,
                    net.minecraft.resources.RegistryDataLoader.WORLDGEN_REGISTRIES);
            var values = new java.util.LinkedHashMap<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>,
                    net.minecraft.core.Registry<?>>();
            fallback.registries().forEach(entry -> values.put(entry.key(), entry.value()));
            dynamic.registries().forEach(entry -> values.put(entry.key(), entry.value()));
            access = new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(values);
        }
        var registries = ClientWorldgenRegistries.decode(
                dev.xantha.vss.networking.server.session.WorldgenRegistrySnapshotTestBridge.encode(access), fallback);
        var settings = access.registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var serverOps = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, access);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.parse("twilightforest:twilight_forest"), SEED, -64, 384, "noise",
                "twilightforest:twilight_noise_gen", 0L);
        var biomes = registries.access().registryOrThrow(Registries.BIOME);
        for (String type : new String[]{"twilightforest:twilight_biomes", "minecraft:multi_noise"}) {
            var root = new JsonObject();
            root.add("settings", NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(serverOps, settings).getOrThrow());
            var source = new JsonObject();
            source.addProperty("type", type);
            root.add("biome_source", source);
            var sampler = ClientWorldgenProfileDecoder.decodeJavaSampler(profile, root, registries, registries.access(),
                    new Checker(biomes.getHolderOrThrow(Biomes.DESERT), biomes.getHolderOrThrow(Biomes.PLAINS)));
            try {
                var surface = ClientTerrainSampler.class.getDeclaredField("surfaceMaterials");
                surface.setAccessible(true);
                Object resolver = surface.get(sampler);
                assertNotNull(resolver, type);
                var zoom = ClientSurfaceResolver.class.getDeclaredField("zoom");
                zoom.setAccessible(true);
                assertEquals(type.startsWith("twilightforest:"), zoom.get(resolver) != null, type);
            } finally {
                PredictionResources.releaseSampler(sampler);
            }
        }
    }

    @Test
    void boundZoomGivesEachColumnTheMaterialOfTheBiomeBiomeManagerSelects() {
        var raw = fixture();
        var zoomed = fixture();
        zoomed.resolver().bindBiomeZoom(SEED);
        var manager = new BiomeManager(zoomed.cache()::get, BiomeManager.obfuscateSeed(SEED));
        int differ = 0;
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            var quart = raw.source().getNoiseBiome(x >> 2, (SURFACE - 1) >> 2, z >> 2, null);
            var fuzzy = manager.getBiome(new BlockPos(x, SURFACE - 1, z));
            assertEquals(expected(quart), top(raw.resolver(), x, z), "raw quart lookup at " + x + "," + z);
            assertEquals(expected(fuzzy), top(zoomed.resolver(), x, z), "seeded zoom at " + x + "," + z);
            if (!quart.equals(fuzzy)) differ++;
        }
        assertTrue(differ > 50, "the fixture must separate the two lookups: " + differ);
    }
}
