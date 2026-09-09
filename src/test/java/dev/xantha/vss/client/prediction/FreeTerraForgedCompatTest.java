package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class FreeTerraForgedCompatTest {
    private static URLClassLoader releaseLoader;
    // NeoForge's process-wide registry catalog permits one instance per registry.
    // Keep the published mod's classes in one loader for this test JVM.
    static synchronized URLClassLoader releaseLoader() throws Exception {
        if (releaseLoader == null) releaseLoader = new URLClassLoader(new java.net.URL[]{
                Path.of(System.getProperty("vss.freeTerraForgedJar")).toUri().toURL()}, FreeTerraForgedCompatTest.class.getClassLoader());
        return releaseLoader;
    }
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void dimensionScopeRestoresCallerOnSuccessAndFailure() {
        ThreadLocal<Boolean> flag = ThreadLocal.withInitial(() -> false);
        assertEquals(17, FreeTerraForgedCompat.scoped(flag, true, () -> {
            assertTrue(flag.get());
            FreeTerraForgedCompat.scoped(flag, false, () -> { assertFalse(flag.get()); return null; });
            assertTrue(flag.get());
            return 17;
        }));
        assertFalse(flag.get());
        assertThrows(IllegalStateException.class, () -> FreeTerraForgedDensity.coarse(true, () -> {
            assertTrue(FreeTerraForgedDensity.coarse());
            throw new IllegalStateException("coarse build cancelled");
        }));
        assertFalse(FreeTerraForgedDensity.coarse());
        assertThrows(IllegalStateException.class, () -> FreeTerraForgedCompat.scoped(flag, true, () -> {
            throw new IllegalStateException("decode failed");
        }));
        assertFalse(flag.get());
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.freeTerraForgedJar", matches = ".+")
    @SuppressWarnings("unchecked")
    void releasedApiAndRuntimeOnlyPresetRoundTrip() throws Exception {
        {
            var loader = releaseLoader();
            var api = new FreeTerraForgedCompat.Api(loader);
            assertNotNull(api.context);
            assertFalse(api.overworld.get());
            FreeTerraForgedCompat.scoped(api.overworld, true, () -> { assertTrue(api.overworld.get()); return null; });
            assertFalse(api.overworld.get());
            String prefix = "raccoonman.reterraforged.";
            Class<?> presetType = loader.loadClass(prefix + "data.worldgen.preset.settings.Preset");
            Codec<Object> codec = (Codec<Object>) presetType.getField("DIRECT_CODEC").get(null);
            Object preset = loader.loadClass(prefix + "data.worldgen.preset.settings.Presets")
                    .getMethod("makeRTFDefault").invoke(null);
            var key = ResourceKey.<Object>createRegistryKey(ResourceLocation.parse("reterraforged:worldgen/preset"));
            var id = ResourceKey.create(key, ResourceLocation.parse("reterraforged:preset"));
            var registry = new MappedRegistry<Object>(key, Lifecycle.stable());
            registry.register(id, preset, RegistrationInfo.BUILT_IN);
            registry.freeze();
            var server = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
            List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, codec, false));
            var dependencies = new WorldgenRegistryDependencies(server, codecs);
            assertTrue(dependencies.encode().entrySet().isEmpty(), "preset is a runtime dependency, not a density holder");
            dependencies.require(key.location());
            var client = ClientWorldgenRegistries.decode(WorldgenRegistryDependenciesTest.root(dependencies.encode()), RegistryAccess.EMPTY, codecs);
            Object decoded = client.access().registryOrThrow(key).get(id);
            assertNotSame(preset, decoded);
            assertEquals(codec.encodeStart(JsonOps.INSTANCE, preset).getOrThrow(),
                    codec.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
            assertThrows(IllegalStateException.class, () -> dependencies.require(ResourceLocation.parse("reterraforged:missing")));
            assertThrows(IllegalStateException.class, () -> api.initialize(new Object(), client.access()));

            // Verify the full surface/tile interface against the actual published JAR.
            Class<?> context = loader.loadClass(prefix + "world.worldgen.GeneratorContext");
            Class<?> cache = context.getField("cache").getType();
            assertNotNull(cache.getMethod("provideAtChunk", int.class, int.class));
            Class<?> active = loader.loadClass(prefix + "world.worldgen.ActiveChunk");
            assertNotNull(active.getMethod("get"));
            assertNotNull(active.getMethod("set", net.minecraft.world.level.chunk.ChunkAccess.class));

            assertNoiseSnapshotSamples(loader, preset, codec, key, registry);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertNoiseSnapshotSamples(ClassLoader loader, Object preset, Codec<Object> presetCodec,
            ResourceKey<Registry<Object>> presetKey, Registry<Object> presets) throws Exception {
        String prefix = "raccoonman.reterraforged.";
        for (String type : List.of("module.Noises", "domain.Domains", "function.CurveFunctions")) {
            loader.loadClass(prefix + "world.worldgen.noise." + type).getMethod("bootstrap").invoke(null);
        }
        // Standalone tests have no mod event bus. Apply only the actual mod's
        // queued custom codec registrations to its isolated registry objects.
        var registersField = loader.loadClass(prefix + "platform.neoforge.RegistryUtilImpl").getDeclaredField("REGISTERS");
        registersField.setAccessible(true);
        var registers = (java.util.Map<?, ?>) registersField.get(null);
        var entriesField = net.neoforged.neoforge.registries.DeferredRegister.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        for (var field : loader.loadClass(prefix + "registries.RTFBuiltInRegistries").getFields()) {
            if (!(field.get(null) instanceof Registry target)) continue;
            Object deferred = registers.get(target.key());
            if (deferred == null) continue;
            var entries = (java.util.Map<net.neoforged.neoforge.registries.DeferredHolder<?, ?>, java.util.function.Supplier<?>>)
                    entriesField.get(deferred);
            for (var entry : entries.entrySet()) Registry.register(target, entry.getKey().getId(), entry.getValue().get());
        }
        var noiseKey = ResourceKey.<Object>createRegistryKey(ResourceLocation.parse("reterraforged:worldgen/noise"));
        var noises = new MappedRegistry<Object>(noiseKey, Lifecycle.stable());
        var registration = noises.createRegistrationLookup();
        var bootstrap = new net.minecraft.data.worldgen.BootstrapContext<Object>() {
            @Override public Holder.Reference<Object> register(ResourceKey<Object> key, Object value, Lifecycle lifecycle) {
                return noises.register(key, value, RegistrationInfo.BUILT_IN);
            }
            @Override public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> key) {
                if (key.equals(noiseKey)) return (HolderGetter<S>) registration;
                throw new IllegalStateException("Unexpected noise bootstrap dependency " + key);
            }
        };
        loader.loadClass(prefix + "data.worldgen.preset.PresetNoiseData").getMethod("bootstrap",
                preset.getClass(), net.minecraft.data.worldgen.BootstrapContext.class).invoke(null, preset, bootstrap);
        noises.freeze();
        Class<?> noiseType = loader.loadClass(prefix + "world.worldgen.noise.module.Noise");
        Codec<Object> codec = (Codec<Object>) noiseType.getField("DIRECT_CODEC").get(null);
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(
                new RegistryDataLoader.RegistryData<>(presetKey, presetCodec, false),
                new RegistryDataLoader.RegistryData<>(noiseKey, codec, false));
        var source = new RegistryAccess.ImmutableRegistryAccess(List.of(presets, noises));
        var dependencies = new WorldgenRegistryDependencies(source, codecs);
        dependencies.require(presetKey.location());
        dependencies.require(noiseKey.location());
        var root = WorldgenRegistryDependenciesTest.root(dependencies.encode());
        root.getAsJsonObject("density_functions").addProperty("vss:test", 0.0);
        var decodedAccess = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY, codecs).access();
        var decoded = decodedAccess.registryOrThrow(noiseKey);
        var compute = noiseType.getMethod("compute", float.class, float.class, int.class);
        int entriesChecked = 0;
        for (var entry : noises.entrySet()) {
            Object copy = decoded.get(entry.getKey());
            for (int i = 0; i < 8; i++) assertEquals(
                    compute.invoke(entry.getValue(), i * 317.0f - 731, -i * 127.0f, 918273),
                    compute.invoke(copy, i * 317.0f - 731, -i * 127.0f, 918273), entry.getKey().toString());
            entriesChecked++;
        }
        assertTrue(entriesChecked > 10, "use the real default preset noise graph");

        var density = new MappedRegistry<net.minecraft.world.level.levelgen.DensityFunction>(
                net.minecraft.core.registries.Registries.DENSITY_FUNCTION, Lifecycle.stable());
        density.freeze();
        var sourceAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(presets, noises, density));
        assertActualInitializerSamples(loader, sourceAccess, decodedAccess);
    }

    private static void assertActualInitializerSamples(ClassLoader loader, RegistryAccess source,
            RegistryAccess decoded) throws Exception {
        String prefix = "raccoonman.reterraforged.world.worldgen.";
        var api = new FreeTerraForgedCompat.Api(loader);
        var contexts = new java.util.ArrayList<Object>();
        try {
            var fieldType = loader.loadClass(prefix + "densityfunction.CellSampler$Field");
            var height = fieldType.getField("HEIGHT").get(null);
            var marker = loader.loadClass(prefix + "densityfunction.CellSampler$Marker")
                    .getConstructor(fieldType).newInstance(height);
            var routerClass = net.minecraft.world.level.levelgen.NoiseRouter.class;
            Class<?>[] componentTypes = java.util.Arrays.stream(routerClass.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] components = new Object[componentTypes.length];
            java.util.Arrays.fill(components, marker);
            var router = routerClass.getConstructor(componentTypes).newInstance(components);
            var functions = new java.util.ArrayList<net.minecraft.world.level.levelgen.DensityFunction>();
            for (RegistryAccess access : List.of(source, decoded)) {
                // Execute the released Mixin's real constructor redirect and initializer.
                // A proxy supplies its @Implements interface outside a running Mixin environment.
                var mixinType = loader.loadClass("raccoonman.reterraforged.mixin.MixinRandomState");
                var constructor = mixinType.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object mixin = constructor.newInstance();
                var samplerField = mixinType.getDeclaredField("sampler");
                samplerField.setAccessible(true);
                var zero = net.minecraft.world.level.levelgen.DensityFunctions.zero();
                samplerField.set(mixin, new net.minecraft.world.level.biome.Climate.Sampler(zero, zero, zero, zero, zero, zero, List.of()));
                var redirect = java.util.Arrays.stream(mixinType.getDeclaredMethods())
                        .filter(m -> m.getName().equals("RandomState")).findFirst().orElseThrow();
                redirect.setAccessible(true);
                var mapped = FreeTerraForgedCompat.scoped(api.overworld, true, () -> {
                    try {
                        return (net.minecraft.world.level.levelgen.NoiseRouter) redirect.invoke(mixin, router,
                                (net.minecraft.world.level.levelgen.DensityFunction.Visitor) function -> function,
                                null, null, 918273L);
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                });
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api.stateType},
                        (p, method, args) -> {
                            var target = mixinType.getDeclaredMethod("reterraforged$RTFRandomState$" + method.getName(), method.getParameterTypes());
                            target.setAccessible(true);
                            return target.invoke(mixin, args);
                        });
                FreeTerraForgedCompat.scoped(api.overworld, true, () -> { api.initialize(proxy, access); return null; });
                Object context = api.context.invoke(proxy);
                assertNotNull(context, "the real initializer must build a terrain context");
                contexts.add(context);
                functions.add(FreeTerraForgedDensity.map(context, mapped).finalDensity());
                assertFalse(api.overworld.get());
            }
            for (int i = 0; i < 16; i++) {
                var point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(i * 3 - 10, 80, -i * 5);
                double expected = functions.getFirst().compute(point);
                assertTrue(Double.isFinite(expected));
                assertEquals(expected, functions.getLast().compute(point), 0.0);
            }
            // Filtered tile data is the path used by real chunk generation.
            // Use previously unsampled positions so the mod's point cache has
            // never stored an unfiltered result for these columns.
            var tiles = new java.util.ArrayList<Object>();
            for (Object context : contexts) {
                Object tileCache = context.getClass().getField("cache").get(context);
                tiles.add(tileCache.getClass().getMethod("provideAtChunk", int.class, int.class).invoke(tileCache, 0, 0));
            }
            var readHeight = fieldType.getMethod("read", loader.loadClass(prefix + "cell.Cell"),
                    loader.loadClass(prefix + "cell.heightmap.Heightmap"));
            for (int i = 0; i < 8; i++) {
                int x = 17 + i * 5, z = 9 + i * 3;
                Object tile = tiles.getFirst();
                Object cell = tile.getClass().getMethod("lookup", int.class, int.class).invoke(tile, x, z);
                Object lookup = contexts.getFirst().getClass().getField("lookup").get(contexts.getFirst());
                Object heightmap = lookup.getClass().getMethod("getHeightmap").invoke(lookup);
                double expected = ((Number) readHeight.invoke(height, cell, heightmap)).doubleValue();
                var point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(x, 80, z);
                assertEquals(expected, functions.getFirst().compute(point), 0.0);
                assertEquals(expected, functions.getLast().compute(point), 0.0);
            }
            Object cell = loader.loadClass(prefix + "cell.Cell").getConstructor().newInstance();
            var cellClass = cell.getClass();
            var water = new FreeTerraForgedWater(contexts.getFirst());
            assertEquals(Integer.MIN_VALUE, water.surfaceY(cell), "dry cells must not acquire river water");
            cellClass.getField("terrain").set(cell, loader.loadClass(prefix + "cell.terrain.TerrainType").getField("RIVER").get(null));
            cellClass.getField("riverWaterLevel").setFloat(cell, 1);
            Object levels = contexts.getFirst().getClass().getField("levels").get(contexts.getFirst());
            int oceanTop = 1 + (int) levels.getClass().getMethod("scale", float.class).invoke(levels,
                    levels.getClass().getField("water").getFloat(levels));
            assertEquals(oceanTop, water.surfaceY(cell), "water block Y must become its upper face, with sea floor clamp");
            cellClass.getField("waterTable").setFloat(cell, 0.8f);
            cellClass.getField("globalContinentScale").setFloat(cell, 4000f);
            cellClass.getField("continentSizeModifier").setFloat(cell, 1f);
            assertTrue(water.surfaceY(cell) > oceanTop, "uplift rivers must not be flattened to sea level");
        } finally {
            var registryField = loader.loadClass("raccoonman.reterraforged.concurrent.cache.CacheManager").getDeclaredField("CACHES");
            registryField.setAccessible(true);
            var caches = (java.util.List<?>) registryField.get(null);
            for (Object context : contexts) {
                int before = caches.size();
                FreeTerraForgedCompat.releaseTileCache(context.getClass().getField("cache").get(context));
                assertEquals(before - 1, caches.size(), "release only this context's tile cache");
            }
            // The test owns this isolated classloader's scheduler, never the game's scheduler.
            Class<?> cacheClass = loader.loadClass("raccoonman.reterraforged.concurrent.cache.Cache");
            ((java.util.concurrent.ExecutorService) cacheClass.getField("SCHEDULER").get(null)).shutdownNow();
        }
    }
}
