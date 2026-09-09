package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldgenRegistryDependenciesTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void dependencyClosureRestoresUnsyncedRegistriesAndForwardHolders() {
        var configKey = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("example:noise_config"));
        var dependentKey = ResourceKey.<Holder<Integer>>createRegistryKey(ResourceLocation.parse("example:noise_region"));
        var unusedKey = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("example:modifiers"));
        var config = new MappedRegistry<Integer>(configKey, Lifecycle.stable());
        var configId = ResourceKey.create(configKey, ResourceLocation.parse("example:overworld"));
        var holder = config.register(configId, 731, RegistrationInfo.BUILT_IN);
        config.freeze();
        var dependent = new MappedRegistry<Holder<Integer>>(dependentKey, Lifecycle.stable());
        var regionId = ResourceKey.create(dependentKey, ResourceLocation.parse("example:region"));
        var regionHolder = dependent.register(regionId, holder, RegistrationInfo.BUILT_IN);
        dependent.freeze();
        var unused = new MappedRegistry<Integer>(unusedKey, Lifecycle.stable());
        unused.freeze();
        var holderCodec = RegistryFileCodec.create(configKey, Codec.INT);
        var regionCodec = RegistryFileCodec.create(dependentKey, holderCodec);
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(
                new RegistryDataLoader.RegistryData<>(dependentKey, holderCodec, false),
                new RegistryDataLoader.RegistryData<>(configKey, Codec.INT, false),
                new RegistryDataLoader.RegistryData<>(unusedKey, Codec.INT, false));
        var server = new RegistryAccess.ImmutableRegistryAccess(List.of(config, dependent, unused));
        var dependencies = new WorldgenRegistryDependencies(server, codecs);
        var encoded = regionCodec.encodeStart(dependencies.ops(), regionHolder).getOrThrow();
        var extra = dependencies.encode();
        assertTrue(extra.has("example:noise_region"));
        assertTrue(extra.has("example:noise_config"));
        assertFalse(extra.has("example:modifiers"));
        var root = root(extra);
        var oldClient = ClientWorldgenRegistries.decode(root(new JsonObject()), RegistryAccess.EMPTY, codecs);
        assertTrue(regionCodec.parse(oldClient.ops(), encoded).error().isPresent(), "reproduce missing client registry");
        var client = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs);
        assertEquals(731, regionCodec.parse(client.ops(), encoded).getOrThrow().value().value());
        assertEquals(731, client.access().registryOrThrow(configKey).get(configId));
        assertThrows(IllegalStateException.class, () -> ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, List.of()));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
    @SuppressWarnings("unchecked")
    void runtimeTemplateListsRoundTripWithoutAnyCodecLookup() throws Exception {
        var jar = java.nio.file.Path.of(System.getProperty("vss.lithostitchedJar"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             var archive = new java.util.zip.ZipFile(jar.toFile())) {
            var type = loader.loadClass("dev.worldgen.lithostitched.worldgen.modifier.template.TemplateList");
            var codec = (Codec<Object>) type.getField("CODEC").get(null);
            var key = ResourceKey.<Object>createRegistryKey(ResourceLocation.parse("lithostitched:template_list"));
            var registry = new MappedRegistry<Object>(key, Lifecycle.stable());
            String prefix = "data/lithostitched/lithostitched/template_list/";
            for (var entry : archive.stream().filter(e -> e.getName().startsWith(prefix)
                    && e.getName().endsWith(".json")).toList()) {
                try (var reader = new java.io.InputStreamReader(archive.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) {
                    var json = com.google.gson.JsonParser.parseReader(reader);
                    var id = ResourceLocation.fromNamespaceAndPath("lithostitched",
                            entry.getName().substring(prefix.length(), entry.getName().length() - 5));
                    registry.register(ResourceKey.create(key, id),
                            codec.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow(), RegistrationInfo.BUILT_IN);
                }
            }
            registry.freeze();
            assertTrue(registry.size() > 5);
            List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, codec, false));
            var server = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
            var oldClient = ClientWorldgenRegistries.decode(root(new JsonObject()), RegistryAccess.EMPTY, codecs);
            assertThrows(IllegalStateException.class, () -> oldClient.access().registryOrThrow(key));
            var dependencies = new WorldgenRegistryDependencies(server, codecs);
            var client = ClientWorldgenRegistries.decode(root(dependencies.encode()), RegistryAccess.EMPTY, codecs);
            var actual = client.access().registryOrThrow(key);
            assertEquals(registry.size(), actual.size());
            var randomTemplate = type.getMethod("getRandom", net.minecraft.util.RandomSource.class);
            for (var entry : registry.entrySet()) {
                var expectedRandom = net.minecraft.util.RandomSource.create(78123);
                var actualRandom = net.minecraft.util.RandomSource.create(78123);
                for (int i = 0; i < 32; i++) assertEquals(
                        randomTemplate.invoke(entry.getValue(), expectedRandom),
                        randomTemplate.invoke(actual.get(entry.getKey()), actualRandom));
            }
        }
    }

    @Test void emptyRuntimeRegistryIsPreservedAndAbsentModIsNotRequired() {
        var key = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("lithostitched:template_list"));
        var registry = new MappedRegistry<Integer>(key, Lifecycle.stable());
        registry.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, Codec.INT, false));
        var server = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        var dependencies = new WorldgenRegistryDependencies(server, codecs);
        var client = ClientWorldgenRegistries.decode(root(dependencies.encode()), RegistryAccess.EMPTY, codecs);
        assertEquals(0, client.access().registryOrThrow(key).size());
        assertTrue(new WorldgenRegistryDependencies(RegistryAccess.EMPTY, codecs).encode().isEmpty());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
    @SuppressWarnings("unchecked")
    void installedFastNoiseRegistryRoundTripsAndUsesServerSeed() throws Exception {
        var jar = java.nio.file.Path.of(System.getProperty("vss.lithostitchedJar"));
        var urls = new java.util.ArrayList<java.net.URL>();
        urls.add(jar.toUri().toURL());
        try (var archive = new java.util.zip.ZipFile(jar.toFile())) {
            for (var entry : archive.stream().filter(e -> e.getName().startsWith("META-INF/jarjar/")
                    && e.getName().endsWith(".jar")).toList()) {
                var nested = temporary.resolve(java.nio.file.Path.of(entry.getName()).getFileName());
                try (var input = archive.getInputStream(entry)) { java.nio.file.Files.copy(input, nested); }
                urls.add(nested.toUri().toURL());
            }
        }
        try (var loader = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), getClass().getClassLoader())) {
            var type = loader.loadClass("dev.worldgen.lithostitched.impl.worldgen.fastnoise.PerlinNoiseType");
            var perlinCodec = type.getField("CODEC").get(null);
            var codecTypes = (Registry<com.mojang.serialization.MapCodec<?>>) loader.loadClass(
                    "dev.worldgen.lithostitched.api.registry.LithostitchedBuiltInRegistries")
                    .getField("FAST_NOISE_CONFIG_TYPE").get(null);
            Registry.register(codecTypes, ResourceLocation.parse("lithostitched:perlin"),
                    (com.mojang.serialization.MapCodec<?>) perlinCodec);
            for (String noise : List.of("Cellular", "Simplex")) {
                Registry.register(codecTypes, ResourceLocation.parse("lithostitched:" + noise.toLowerCase(java.util.Locale.ROOT)),
                        (com.mojang.serialization.MapCodec<?>) loader.loadClass(
                                "dev.worldgen.lithostitched.impl.worldgen.fastnoise." + noise + "NoiseType")
                                .getField("CODEC").get(null));
            }
            var configType = type.getSuperclass();
            var codec = (Codec<Object>) configType.getField("CODEC").get(null);
            var key = ResourceKey.<Object>createRegistryKey(ResourceLocation.parse("lithostitched:fast_noise_config"));
            var config = type.getConstructor(float.class, int.class).newInstance(0.013f, 731);
            long seed = 981231298731L;
            type.getMethod("bind", long.class).invoke(config, seed);
            var registry = new MappedRegistry<Object>(key, Lifecycle.stable());
            registry.register(ResourceKey.create(key, ResourceLocation.parse("vss:test_perlin")),
                    config, RegistrationInfo.BUILT_IN);
            // Decode the exact region configs shipped inside the installed mod.
            for (String dimension : List.of("overworld", "the_nether", "the_end")) {
                String path = "data/lithostitched/lithostitched/fast_noise_config/region/" + dimension + ".json";
                try (var input = loader.getResourceAsStream(path)) {
                    assertNotNull(input, path);
                    var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
                    var value = codec.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
                    configType.getMethod("bind", long.class).invoke(value, seed);
                    registry.register(ResourceKey.create(key, ResourceLocation.parse("lithostitched:region/" + dimension)), value, RegistrationInfo.BUILT_IN);
                }
            }
            registry.freeze();
            var holder = registry.getHolderOrThrow(ResourceKey.create(key, ResourceLocation.parse("lithostitched:region/overworld")));
            List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, codec, false));
            var server = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
            var dependencies = new WorldgenRegistryDependencies(server, codecs);
            var densityType = loader.loadClass("dev.worldgen.lithostitched.impl.worldgen.densityfunction.FastNoiseDensityFunction");
            var densityCodec = ((com.mojang.serialization.MapCodec<net.minecraft.world.level.levelgen.DensityFunction>)
                    densityType.getField("DATA_CODEC").get(null)).codec();
            var zero = net.minecraft.world.level.levelgen.DensityFunctions.zero();
            var expected = (net.minecraft.world.level.levelgen.DensityFunction) densityType.getConstructor(
                    Holder.class, double.class, double.class, net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class, net.minecraft.world.level.levelgen.DensityFunction.class)
                    .newInstance(holder, 1.0, 0.0, zero, zero, zero);
            var encoded = densityCodec.encodeStart(dependencies.ops(), expected).getOrThrow();
            var root = root(dependencies.encode());
            var client = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs);
            var actual = densityCodec.parse(client.ops(), encoded).getOrThrow();
            var point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(731, 90, -391);
            assertNotEquals(expected.compute(point), actual.compute(point), "unbound noise reproduces wrong seed");
            client.bindWorldSeed(seed);
            for (var entry : registry.entrySet()) {
                Object decodedConfig = client.access().registryOrThrow(key).get(entry.getKey());
                assertNotSame(entry.getValue(), decodedConfig);
                var sample = configType.getMethod("sample", double.class, double.class, double.class);
                for (int i = 0; i < 64; i++) assertEquals(
                        sample.invoke(entry.getValue(), i * 371.0, 0.0, -i * 97.0),
                        sample.invoke(decodedConfig, i * 371.0, 0.0, -i * 97.0));
            }
            for (int i = 0; i < 128; i++) {
                point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(i * 71 - 2048, i, -i * 37);
                assertEquals(expected.compute(point), actual.compute(point), 0.0);
            }
            var second = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs);
            second.bindWorldSeed(seed + 1);
            assertEquals(expected.compute(point), actual.compute(point), 0.0, "another snapshot must not reseed existing samplers");
            assertNotEquals(actual.compute(point), densityCodec.parse(second.ops(), encoded).getOrThrow().compute(point));
        }
    }

    static JsonObject root(JsonObject extra) {
        var root = new JsonObject();
        root.add("noises", new JsonObject());
        root.add("density_functions", new JsonObject());
        root.add("custom_registries", extra);
        return root;
    }
}
