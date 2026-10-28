package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Biome identity must survive the native/cache-to-Voxy handoff, not just RGB tint values. */
class PredictionVoxyBiomeTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.tryParse("minecraft:overworld"), 42L, -64, 384, "noise", "minecraft:overworld", 123L);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    private static Holder<Biome> holder(String name) {
        return TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.tryParse(name)));
    }
    private static ClientColumnSample sample(int biome, int surface, int water) {
        int none = ClientColumnSample.NO_SPAN;
        return new ClientColumnSample(surface, water, biome, BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK),
                0, 0, 0, 0, water > surface ? 1 : 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                BuiltInRegistries.BLOCK.getId(Blocks.DIRT), BuiltInRegistries.BLOCK.getId(Blocks.STONE),
                none, none, none, none);
    }
    private static ClientColumnSample[] grid(ClientColumnSample sample) {
        var result = new ClientColumnSample[16]; Arrays.fill(result, sample); return result;
    }
    private static PredictionVoxySource.Tile build(ClientTerrainSampler sampler, ClientColumnSample[] samples,
            int x, int z, int step, PredictionVegetation.Tile plants) {
        return PredictionVoxySource.build(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, x, z, 0),
                samples, 4, step, sampler, plants, null);
    }
    private static final class Biomes extends ClientTerrainSampler {
        final List<String> names;
        final Holder<Biome> value;
        final List<BlockPos> queries = new ArrayList<>();
        Biomes(List<String> names, String value) { super(42L, PROFILE); this.names = names; this.value = holder(value); }
        @Override List<String> cacheBiomes() { return names; }
        @Override Holder<Biome> noiseBiome(int x, int y, int z) { queries.add(new BlockPos(x, y, z)); return value; }
    }

    @ParameterizedTest @ValueSource(strings = {"minecraft:dark_forest", "minecraft:swamp"})
    void missingNativeIdentityReachesGrassWaterAndLeaves(String name) {
        var sampler = new Biomes(List.of(name), name);
        var plants = PredictionVegetation.Tile.of(Map.of(new BlockPos(1, 12, 1), Blocks.OAK_LEAVES.defaultBlockState()),
                0, 0, 2, 1, 1);
        var tile = build(sampler, grid(sample(-1, 8, 11)), 0, 0, 1, plants);
        assertTrue(Arrays.stream(tile.columns()).allMatch(c -> name.equals(c.biome())));
        Map<Block, Set<String>> mapped = new HashMap<>();
        VoxyPredictionBridge.Mapper mapper = (state, biome) -> {
            mapped.computeIfAbsent(state.getBlock(), ignored -> new HashSet<>()).add(biome);
            return 1L << 27;
        };
        try (var source = new PredictionVoxySource()) {
            source.put(tile);
            assertNotNull(source.section(VoxyPredictionBridge.position(0, 0, 0, 0), mapper));
            for (Block block : List.of(Blocks.GRASS_BLOCK, Blocks.WATER, Blocks.OAK_LEAVES))
                assertEquals(Set.of(name), mapped.get(block), block.toString());
            int queries = sampler.queries.size();
            source.section(VoxyPredictionBridge.position(0, 0, 0, 0), mapper);
            source.section(VoxyPredictionBridge.position(0, 0, 1, 0), mapper);
            assertEquals(queries, sampler.queries.size(), "Voxy mesh workers must not query worldgen");
        }
    }

    @Test void knownPaletteIdentityDoesNotQueryNoise() {
        var sampler = new Biomes(List.of("minecraft:dark_forest"), "minecraft:swamp");
        var tile = build(sampler, grid(sample(0, 8, 8)), 0, 0, 1, PredictionVegetation.Tile.EMPTY);
        assertTrue(Arrays.stream(tile.columns()).allMatch(c -> c.biome().equals("minecraft:dark_forest")));
        assertTrue(sampler.queries.isEmpty());
    }

    @Test void negativeWorldCoordinatesAndDrySurfaceHeightUseQuartCoordinates() {
        var sampler = new Biomes(List.of(), "minecraft:dark_forest");
        build(sampler, grid(sample(-1, -4, -4)), -1, -2, 3, PredictionVegetation.Tile.EMPTY);
        assertEquals(List.of(new BlockPos(-2, -2, -3), new BlockPos(-1, -2, -3),
                new BlockPos(-2, -2, -3), new BlockPos(-1, -2, -3)), sampler.queries);
    }

    @Test void waterUsesItsExposedHeightInsteadOfTheSeaFloor() {
        var sampler = new Biomes(List.of(), "minecraft:swamp");
        build(sampler, grid(sample(-1, 3, 17)), 0, 0, 1, PredictionVegetation.Tile.EMPTY);
        assertEquals(4, sampler.queries.size());
        assertTrue(sampler.queries.stream().allMatch(p -> p.getY() == 4));
    }

    @Test void unknownOrUnnamedPaletteEntriesResolveFromTheBiomeSource() {
        for (int index : new int[]{0, 10, ClientColumnSample.NO_BLOCK}) {
            var sampler = new Biomes(List.of(""), "minecraft:swamp");
            var tile = build(sampler, grid(sample(index, 8, 8)), 0, 0, 1, PredictionVegetation.Tile.EMPTY);
            assertEquals("minecraft:swamp", tile.columns()[0].biome());
            assertEquals(4, sampler.queries.size());
        }
    }

    @Test void heightOnlySourcesKeepTheFallbackWithoutRequiringANoiseGenerator() {
        try (var sampler = ClientTerrainSampler.custom(42L, PROFILE, (x, z) -> 8)) {
            var tile = build(sampler, grid(sample(-1, 8, 8)), 0, 0, 1, PredictionVegetation.Tile.EMPTY);
            assertEquals("minecraft:plains", tile.columns()[0].biome());
        }
    }

    @Test void customLodSourcesCanProvideBiomesWithoutADecodedNoiseGenerator() {
        var access = TerrainPredictionFixtures.access();
        var worldgen = ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), access);
        var plan = new dev.xantha.vss.common.worldgen.TerrainPredictionPlan(
                dev.xantha.vss.common.worldgen.TerrainPredictionPlan.Kind.LOD_SOURCE, "test:biomes", 1, false, "");
        var context = new PredictionLodContext(PROFILE, plan, new com.google.gson.JsonObject(), worldgen, access);
        var source = new PredictionLodSource() {
            @Override public ClientColumnSample sample(int x, int z, int step) { return PredictionVoxyBiomeTest.sample(-1, 8, 8); }
            @Override public Holder<Biome> biome(int x, int y, int z) { return holder("minecraft:swamp"); }
        };
        try (var sampler = new PredictionLodSampler(context, source)) {
            var tile = build(sampler, grid(sample(-1, 8, 8)), 0, 0, 1, PredictionVegetation.Tile.EMPTY);
            assertEquals("minecraft:swamp", tile.columns()[0].biome());
        }
    }

    @Test void persistedUnknownIdentityIsResolvedAfterCacheReopen() {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease, grid(sample(-1, 8, 8)))); cache.flush();
        }
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            var restored = cache.readTerrain(lease, 16);
            assertNotNull(restored); assertEquals(-1, restored[5].biomeIndex());
            var sampler = new Biomes(List.of("minecraft:dark_forest"), "minecraft:dark_forest");
            var tile = build(sampler, restored, 0, 0, 1, PredictionVegetation.Tile.EMPTY);
            assertEquals("minecraft:dark_forest", tile.columns()[0].biome());
        }
    }

    @Test void resolvedIdentityIsPersistedAndReopenedWithoutRepeatingClimateQueries() {
        var key=PredictionDiskCache.Key.terrain(0,0,0);
        var sampler=new Biomes(List.of("minecraft:swamp"),"minecraft:swamp");
        var samples=grid(sample(-1,3,17));
        var original=samples[0];
        assertEquals(16,sampler.resolveBiomeIndices(samples,4,-8,-8,1));
        assertEquals(4,sampler.queries.size(),"The margin grid intersects four distinct quart columns");
        assertEquals(original.withBiomeIndex(0),samples[0]);
        assertTrue(sampler.queries.stream().allMatch(p->p.getY()==4));
        try(var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease,grid(original)));cache.flush();
            assertTrue(cache.writeTerrain(lease,samples));cache.flush();
        }
        try(var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(key)) {
            var restored=cache.readTerrain(lease,16);assertNotNull(restored);
            assertEquals(0,sampler.resolveBiomeIndices(restored,4,-8,-8,1));
            build(sampler,restored,-4,-4,1,PredictionVegetation.Tile.EMPTY);
            assertEquals(4,sampler.queries.size(),"Both normalization and Voxy reuse the persisted palette identity");
        }
    }

    @Test void identityNormalizationPreservesKnownBiomesAndUnmappedFallbacks() {
        var known=new Biomes(List.of("minecraft:dark_forest"),"minecraft:swamp");
        var samples=grid(sample(0,8,8));var original=samples[0];
        assertEquals(0,known.resolveBiomeIndices(samples,4,0,0,1));
        assertSame(original,samples[0]);assertTrue(known.queries.isEmpty());
        samples=grid(sample(-1,8,8));
        assertEquals(0,known.resolveBiomeIndices(samples,4,0,0,1));
        assertEquals(-1,samples[0].biomeIndex(),"An unknown external biome cannot become an invented palette ID");
    }

    @Test void actualNativeSamplesCarryIdentityWithoutRepeatingJavaClimateQueries() throws Exception {
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        assertTrue(RustTerrainSampler.available());
        var document = LithostitchedNativeTest.document();
        document.add("possible_biomes", new com.google.gson.JsonArray());
        document.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:dark_forest\"}"));
        long world = RustWorldgenBackend.create(42L, 0L, document.toString());
        var access = TerrainPredictionFixtures.access();
        var settings = access.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                new net.minecraft.world.level.biome.FixedBiomeSource(holder("minecraft:dark_forest")), settings);
        var random = net.minecraft.world.level.levelgen.RandomState.create(settings.value(),
                access.lookupOrThrow(Registries.NOISE), 42L);
        var context = new ClientTerrainSampler(42L, PROFILE, generator, random,
                net.minecraft.world.level.LevelHeightAccessor.create(-64, 384), 63) {
            @Override Holder<Biome> noiseBiome(int x,int y,int z) {
                throw new AssertionError("Native identity must not query Java climate");
            }
        };
        try (var sampler = new RustTerrainSampler(world, PROFILE, context)) {
            var nativeSample = sampler.sample(0, 0);
            assertTrue(nativeSample.biomeIndex()>=0, "Native identity must be mapped into the decoded palette");
            assertEquals("minecraft:dark_forest",sampler.cacheBiomes().get(nativeSample.biomeIndex()));
            var samples=grid(nativeSample);
            assertEquals(0,sampler.resolveBiomeIndices(samples,4,0,0,1), "Native samples already carry identity");
            assertTrue(sampler.diagnostics().contains("javaFallbacks=0"));
            var tile = build(sampler, samples, 0, 0, 1, PredictionVegetation.Tile.EMPTY);
            assertEquals("minecraft:dark_forest", tile.columns()[0].biome());
        }
    }
}
