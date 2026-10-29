package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionLodBackendsTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    @AfterEach void unregister() {
        PredictionLodBackends.unregister("example:lod");
        PredictionTerrainBackends.unregister("example:legacy");
    }
    @TempDir Path cache;
    private static JsonObject generator(int revision, int height) {
        var result = new JsonObject();
        result.add(TerrainPredictionPlan.FIELD, new TerrainPredictionPlan(
                TerrainPredictionPlan.Kind.LOD_SOURCE, "example:lod", revision, false, "").encode());
        var parameters = new JsonObject(); parameters.addProperty("height", height);
        result.add("vss_lod_parameters", parameters); return result;
    }
    private static DimensionProfile profile(JsonObject root) {
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        return new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384,
                "example.Generator", "custom", 0, 0, bytes.length, bytes);
    }
    private static PredictionLodContext context(JsonObject generator) {
        return new PredictionLodContext(profile(generator), TerrainPredictionPlan.read(generator), generator,
                ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), TerrainPredictionFixtures.access()),
                TerrainPredictionFixtures.access());
    }
    private static ClientColumnSample sample(int height) {
        int block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        return new ClientColumnSample(height, height, -1, block, 0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY,
                0, block, block, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    @Test void fullProfileSelectsFactoryOnceAndKeepsDirectSamplingAfterUnregistration() throws Exception {
        var calls = new AtomicInteger();
        PredictionLodBackends.register("example:lod", 1, 3, context -> {
            calls.incrementAndGet(); int height = context.parameters().get("height").getAsInt();
            ClientColumnSample sample = sample(height);
            return (x, z, step) -> sample;
        });
        JsonObject generator = generator(2, 110);
        byte[] registries = TerrainPredictionFixtures.emptyRegistries().toString().getBytes(StandardCharsets.UTF_8);
        var payload = new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42, 1,
                0, registries.length, registries, List.of(profile(generator)));
        var samplers = ClientWorldgenProfileDecoder.decode(payload, TerrainPredictionFixtures.access());
        var sampler = samplers.values().iterator().next();
        assertEquals(1, calls.get());
        PredictionLodBackends.unregister("example:lod");
        for (int i = 0; i < 1000; i++) assertEquals(110, sampler.sampleForLod(i, -i, 32).surfaceY());
        assertEquals(1, calls.get());
        assertTrue(sampler.predictionBackendDiagnostics().contains("executor=source")); sampler.close();
    }
    @Test void missingOrWrongRevisionNeverFallsBackToUnrelatedNoise() {
        assertThrows(IllegalStateException.class, () -> PredictionLodBackends.open(context(generator(1, 90))));
        PredictionLodBackends.register("example:lod", 2, 3, ignored -> (x, z, step) -> sample(120));
        assertThrows(IllegalStateException.class, () -> PredictionLodBackends.open(context(generator(1, 90))));
        assertThrows(IllegalStateException.class, () -> PredictionLodBackends.open(context(generator(4, 90))));
    }
    @Test void registeredLegacyBridgeIsReportedAsAnExplicitOverrideAndHasItsOwnCache() throws Exception {
        PredictionTerrainBackends.register(new PredictionTerrainBackend() {
            public String id() { return "example:legacy"; }
            public int priority() { return 1000; }
            public java.util.Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed, net.minecraft.core.RegistryAccess access) {
                return profile.generatorType().equals("example.Generator") ? java.util.Optional.of(ClientTerrainSampler.custom(seed, profile, (x, z) -> 100))
                        : java.util.Optional.empty();
            }
        });
        var generator = new JsonObject();
        generator.add(TerrainPredictionPlan.FIELD, TerrainPredictionPlan.unavailable("needs a bridge").encode());
        byte[] registries = TerrainPredictionFixtures.emptyRegistries().toString().getBytes(StandardCharsets.UTF_8);
        var payload = new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 42, 1,
                0, registries.length, registries, List.of(profile(generator)));
        var sampler = ClientWorldgenProfileDecoder.decode(payload, TerrainPredictionFixtures.access()).values().iterator().next();
        assertEquals(100, sampler.surfaceY(20, -40));
        assertTrue(sampler.predictionBackendDiagnostics().contains("kind=BACKEND_OVERRIDE,snapshotKind=UNAVAILABLE"));
        assertTrue(sampler.predictionBackendDiagnostics().contains("selectedBackend=example:legacy"));
        assertNotNull(sampler.predictionCacheNamespace()); sampler.close();
    }
    @Test void batchesPreserveCapturesAndHonorLodSpacingAndPreview() {
        var steps = new AtomicInteger();
        PredictionLodBackends.register("example:lod", 1, 1, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { steps.set(step); return PredictionLodBackendsTest.sample(100 + x - z); }
            public ClientColumnSample preview(int x, int z, int step) { steps.set(step); return PredictionLodBackendsTest.sample(90); }
        });
        var sampler = PredictionLodBackends.open(context(generator(1, 90)));
        var target = new ClientColumnSample[16]; var captured = sample(200); target[6] = captured;
        assertTrue(sampler.sampleLodGrid(-32, 64, 16, 3, 2, target, 5, 4, false));
        assertSame(captured, target[6]); assertEquals(16, steps.get());
        assertNull(target[4]); assertEquals(4, target[5].surfaceY());
        assertEquals(36, target[7].surfaceY());
        assertTrue(sampler.sampleLodGrid(-32, 64, 32, 1, 1, target, 0, 4, true));
        assertEquals(90, target[0].surfaceY()); assertEquals(32, steps.get()); sampler.close();
    }
    @Test void providerClientAndParameterVersionsIsolatePersistentMeshes() {
        PredictionLodBackends.register("example:lod", 1, 5, ignored -> (x, z, step) -> sample(120));
        var a = PredictionLodBackends.open(context(generator(1, 100)));
        var b = PredictionLodBackends.open(context(generator(2, 100)));
        var c = PredictionLodBackends.open(context(generator(1, 110)));
        var storage = new PredictionCacheStorage(cache, null);
        assertNotEquals(storage.directory(a), storage.directory(b)); assertNotEquals(storage.directory(a), storage.directory(c));
        assertEquals(storage.directory(a), storage.directory(PredictionLodBackends.open(context(generator(1, 100)))));
        PredictionLodBackends.register("example:lod", 1, 5, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
            public int cacheRevision() { return 2; }
        });
        assertNotEquals(storage.directory(a), storage.directory(PredictionLodBackends.open(context(generator(1, 100)))));
        a.close(); b.close(); c.close();
    }
    @Test void disposalIsIdempotentAndFailedFactoryReleasesInitializedResources() {
        var closes = new AtomicInteger();
        PredictionLodBackends.register("example:lod", 1, 1, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
            public void close() { closes.incrementAndGet(); }
        });
        var sampler = PredictionLodBackends.open(context(generator(1, 90))); sampler.close(); sampler.close();
        assertEquals(1, closes.get());
        PredictionLodBackends.register("example:lod", 1, 1, ignored -> new PredictionLodSource() {
            public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
            public int cacheRevision() { return 0; }
            public void close() { closes.incrementAndGet(); }
        });
        assertThrows(IllegalArgumentException.class, () -> PredictionLodBackends.open(context(generator(1, 90))));
        assertEquals(2, closes.get());
    }

    private static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> key) {
        return TerrainPredictionFixtures.access().registryOrThrow(
                net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(key);
    }
    private static PredictionLodContext noiseContext(JsonObject root) {
        var access = TerrainPredictionFixtures.access();
        var noise = TerrainPredictionFixtures.noise();
        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, access);
        // VanillaRegistries values embed holders owned by their datagen lookup.
        // Encode the original settings against that owner; the client below then
        // reconstructs the same JSON into its isolated runtime registries.
        var sourceLookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var sourceSettings = sourceLookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
                .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD).value();
        var sourceOps = sourceLookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        root.add("settings", net.minecraft.world.level.levelgen.NoiseGeneratorSettings.DIRECT_CODEC
                .encodeStart(sourceOps, sourceSettings).getOrThrow());
        root.add("biome_source", net.minecraft.world.level.biome.BiomeSource.CODEC
                .encodeStart(ops, noise.getBiomeSource()).result().orElseThrow());
        var snapshot = TerrainPredictionFixtures.emptyRegistries();
        TerrainPredictionFixtures.access().registryOrThrow(net.minecraft.core.registries.Registries.NOISE)
                .holders().forEach(holder -> snapshot.getAsJsonObject("noises").add(holder.key().location().toString(),
                        net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC
                                .encodeStart(ops, holder.value()).result().orElseThrow()));
        return new PredictionLodContext(profile(root), TerrainPredictionPlan.read(root), root,
                ClientWorldgenRegistries.decode(snapshot, access), access);
    }
    @Test void capturesUseTheProviderPaletteEvenWhenNoiseUsesAnotherOrder() {
        var plains = biome(net.minecraft.world.level.biome.Biomes.PLAINS);
        var desert = biome(net.minecraft.world.level.biome.Biomes.DESERT);
        var actualPlains = TerrainPredictionFixtures.noise().getBiomeSource().possibleBiomes().iterator().next();
        for (boolean withNoise : List.of(false, true)) {
            PredictionLodBackends.register("example:lod", 1, 1, context -> {
                if (withNoise) assertNotNull(context.noiseContext());
                return new PredictionLodSource() {
                    public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
                    public List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes() { return List.of(desert, plains); }
                };
            });
            var root = generator(1, 90);
            try (var sampler = PredictionLodBackends.open(withNoise ? noiseContext(root) : context(root))) {
                assertEquals(List.of("minecraft:desert", "minecraft:plains"), sampler.cacheBiomes());
                assertEquals(1, sampler.captureBiomeIndex(actualPlains, 0));
                assertEquals(0, sampler.captureBiomeIndex(desert, 1));
                assertEquals(7, sampler.captureBiomeIndex(biome(net.minecraft.world.level.biome.Biomes.FOREST), 7));
                assertEquals(9, sampler.captureBiomeIndex(null, 9));
            }
        }
    }
    @Test void emptyProviderPaletteKeepsTheNoisePaletteContract() {
        PredictionLodBackends.register("example:lod", 1, 1, context -> {
            context.noiseContext();
            return (x, z, step) -> sample(120);
        });
        try (var sampler = PredictionLodBackends.open(noiseContext(generator(1, 90)))) {
            assertEquals(List.of("minecraft:plains"), sampler.cacheBiomes());
            assertEquals(0, sampler.captureBiomeIndex(biome(net.minecraft.world.level.biome.Biomes.PLAINS), 7));
        }
    }
    @Test void changedPaletteCannotRestoreMeshesWithTheOldBiomeIndices() {
        var plains = biome(net.minecraft.world.level.biome.Biomes.PLAINS);
        var desert = biome(net.minecraft.world.level.biome.Biomes.DESERT);
        var storage = new PredictionCacheStorage(cache, null);
        var directories = new java.util.ArrayList<Path>();
        for (var palette : List.of(List.of(plains, desert), List.of(desert, plains))) {
            PredictionLodBackends.register("example:lod", 1, 1, context -> new PredictionLodSource() {
                public ClientColumnSample sample(int x, int z, int step) { return PredictionLodBackendsTest.sample(120); }
                public List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes() { return palette; }
            });
            try (var sampler = PredictionLodBackends.open(context(generator(1, 90)))) {
                directories.add(storage.directory(sampler));
            }
        }
        assertNotEquals(directories.get(0), directories.get(1));
    }
}
