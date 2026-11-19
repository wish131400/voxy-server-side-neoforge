package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
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
        if (releaseLoader == null) {
            Path jar = Path.of(System.getProperty("vss.freeTerraForgedJar"));
            String prefix = "com.pandaismyname1.ultraterraforged.";
            String target = prefix + "world.worldgen.tile.TileGenerator";
            boolean ultra;
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) { ultra = zip.getEntry(target.replace('.','/') + ".class") != null; }
            if (ultra) {
                var harness = new dev.xantha.vss.testutil.VoxyMixinHarness(jar.toString(), """
                        {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin.client",
                         "compatibilityLevel":"JAVA_21","client":["UltraTerraForgedPredictionTasksMixin","UltraTerraForgedPredictionTileCacheMixin",
                         "SurfaceRulesContextAccessor","UltraTerraForgedPredictionNearGroundMixin","UltraTerraForgedPredictionCellConditionMixin"]}
                        """);
                byte[] transformed = harness.apply(target);
                String cacheTarget = prefix + "world.worldgen.tile.TileCache";
                byte[] transformedCache = harness.apply(cacheTarget);
                var extra = new java.util.HashMap<String,byte[]>();
                for (String name : List.of("NearGroundCondition", "CellCondition")) {
                    String condition = prefix + "world.worldgen.surface.condition." + name;
                    byte[] bytes = harness.apply(condition); extra.put(condition, bytes);
                    var applied = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(bytes).accept(applied,0);
                    assertTrue(applied.methods.stream().anyMatch(m -> m.name.contains("vss$previewChunk")));
                }
                byte[] accessor = harness.apply("net.minecraft.world.level.levelgen.SurfaceRules$Context");
                var accessed = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(accessor).accept(accessed,0);
                assertTrue(accessed.interfaces.contains("dev/xantha/vss/mixin/client/SurfaceRulesContextAccessor"));
                var cacheNode = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(transformedCache).accept(cacheNode,0);
                assertTrue(cacheNode.methods.stream().anyMatch(m -> m.name.contains("vss$registerBeforePredictionBatches")));
                var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(transformed).accept(node,0);
                assertTrue(node.methods.stream().anyMatch(m -> m.name.contains("vss$predictionBatch")), "apply the production redirect to the real UTF release");
                releaseLoader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, FreeTerraForgedCompatTest.class.getClassLoader()) {
                    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (!name.startsWith(prefix)) return super.loadClass(name,resolve);
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> type = findLoadedClass(name);
                            if (type == null) type = name.equals(target) ? defineClass(name,transformed,0,transformed.length)
                                    : name.equals(cacheTarget) ? defineClass(name,transformedCache,0,transformedCache.length)
                                    : extra.containsKey(name) ? defineClass(name,extra.get(name),0,extra.get(name).length) : findClass(name);
                            if (resolve) resolveClass(type);
                            return type;
                        }
                    }
                };
            } else releaseLoader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, FreeTerraForgedCompatTest.class.getClassLoader());
        }
        return releaseLoader;
    }

    static FreeTerraForgedVariant releaseVariant() throws Exception {
        return FreeTerraForgedVariant.detect(releaseLoader());
    }

    static Object defaultPreset(ClassLoader loader) throws Exception {
        var variant = FreeTerraForgedVariant.detect(loader);
        if (variant == FreeTerraForgedVariant.ULTRA)
            return loader.loadClass(variant.packagePrefix + "data.preset.settings.BuiltinPresets")
                    .getMethod("makeDefault").invoke(null);
        return loader.loadClass(variant.packagePrefix + "data.worldgen.preset.settings.Presets")
                .getMethod("make" + variant.abbreviation + "Default").invoke(null);
    }
    @BeforeAll static void bootstrap() throws Exception {
        var list = net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        var field = net.neoforged.fml.loading.FMLLoader.class.getDeclaredField("loadingModList");
        field.setAccessible(true); field.set(null, list);
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

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
            var variant = api.variant;
            String prefix = variant.packagePrefix;
            Object preset = defaultPreset(loader);
            Class<?> presetType = preset.getClass();
            Codec<Object> codec = (Codec<Object>) presetType.getField(variant == FreeTerraForgedVariant.ULTRA ? "CODEC" : "DIRECT_CODEC").get(null);
            var key = ResourceKey.<Object>createRegistryKey(ResourceLocation.parse(variant.namespace + ":worldgen/preset"));
            var id = ResourceKey.create(key, ResourceLocation.parse(variant.namespace + ":preset"));
            var registry = new MappedRegistry<Object>(key, Lifecycle.stable());
            registry.register(id, preset, RegistrationInfo.BUILT_IN);
            registry.freeze();
            var server = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
            assertTrue(variant.hasPreset(server));
            assertFalse(variant.hasPreset(RegistryAccess.EMPTY));
            List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, codec, false));
            var dependencies = new WorldgenRegistryDependencies(server, codecs);
            assertTrue(dependencies.encode().entrySet().isEmpty(), "preset is a runtime dependency, not a density holder");
            dependencies.require(key.location());
            var client = ClientWorldgenRegistries.decode(WorldgenRegistryDependenciesTest.root(dependencies.encode()), RegistryAccess.EMPTY, codecs);
            Object decoded = client.access().registryOrThrow(key).get(id);
            assertNotSame(preset, decoded);
            assertEquals(codec.encodeStart(JsonOps.INSTANCE, preset).getOrThrow(),
                    codec.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
            assertThrows(IllegalStateException.class, () -> dependencies.require(ResourceLocation.parse(variant.namespace + ":missing")));
            assertThrows(IllegalStateException.class, () -> api.initialize(new Object(), client.access()));

            // Verify the full surface/tile interface against the actual published JAR.
            Class<?> context = loader.loadClass(prefix + "world.worldgen.GeneratorContext");
            Class<?> cache = context.getField(api.epochBased ? "filteredChunkCache" : "cache").getType();
            assertNotNull(cache.getMethod(api.epochBased ? "getFilteredChunk" : "provideAtChunk", int.class, int.class));
            Class<?> active = FreeTerraForgedCompat.activeChunk(loader);
            if (active != null) {
                assertNotNull(active.getMethod("get"));
                assertNotNull(active.getMethod("set", net.minecraft.world.level.chunk.ChunkAccess.class));
            }

            assertNoiseSnapshotSamples(loader, preset, codec, key, registry);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void assertNoiseSnapshotSamples(ClassLoader loader, Object preset, Codec<Object> presetCodec,
            ResourceKey<Registry<Object>> presetKey, Registry<Object> presets) throws Exception {
        var variant = FreeTerraForgedVariant.detect(loader);
        String prefix = variant.packagePrefix;
        for (String type : List.of("module.Noises", "domain.Domains", "function.CurveFunctions")) {
            loader.loadClass(prefix + "world.worldgen.noise." + type).getMethod("bootstrap").invoke(null);
        }
        // Standalone tests have no mod event bus. Apply only the actual mod's
        // queued custom codec registrations to its isolated registry objects.
        if (variant == FreeTerraForgedVariant.ULTRA) {
            var registryUtil = loader.loadClass(prefix + "platform.neoforge.RegistryUtilImpl");
            var entriesField = registryUtil.getDeclaredField("ENTRIES"); entriesField.setAccessible(true);
            var registers = (java.util.Map<?, ?>) entriesField.get(null);
            for (var field : loader.loadClass(prefix + "registries.UTFBuiltInRegistries").getFields()) {
                if (!(field.get(null) instanceof Registry target)) continue;
                var entries = (java.util.List<?>) registers.get(target.key());
                if (entries == null) continue;
                for (Object entry : entries) {
                    var name = entry.getClass().getDeclaredMethod("name"); name.setAccessible(true);
                    var value = entry.getClass().getDeclaredMethod("value"); value.setAccessible(true);
                    Registry.register(target, (ResourceLocation) name.invoke(entry), value.invoke(entry));
                }
            }
        } else {
        var registryUtil = loader.loadClass(prefix + "platform.neoforge.RegistryUtilImpl");
        boolean epoch = new FreeTerraForgedCompat.Api(loader).epochBased;
        var registersField = registryUtil.getDeclaredField(epoch ? "REGISTRATIONS" : "REGISTERS");
        registersField.setAccessible(true);
        var registers = (java.util.Map<?, ?>) registersField.get(null);
        var entriesField = net.neoforged.neoforge.registries.DeferredRegister.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        for (var field : loader.loadClass(prefix + "registries." + variant.abbreviation + "BuiltInRegistries").getFields()) {
            if (!(field.get(null) instanceof Registry target)) continue;
            Object deferred = registers.get(target.key());
            if (deferred == null) continue;
            if (epoch) {
                for (Object entry : (java.util.List<?>) deferred) {
                    var id = entry.getClass().getDeclaredMethod("id"); id.setAccessible(true);
                    var value = entry.getClass().getDeclaredMethod("value"); value.setAccessible(true);
                    Registry.register(target, (ResourceLocation) id.invoke(entry), value.invoke(entry));
                }
                continue;
            }
            var entries = (java.util.Map<net.neoforged.neoforge.registries.DeferredHolder<?, ?>, java.util.function.Supplier<?>>)
                    entriesField.get(deferred);
            for (var entry : entries.entrySet()) Registry.register(target, entry.getKey().getId(), entry.getValue().get());
        }
        }
        var noiseKey = ResourceKey.<Object>createRegistryKey(ResourceLocation.parse(variant.namespace + ":worldgen/noise"));
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
        loader.loadClass(prefix + (variant == FreeTerraForgedVariant.ULTRA ? "data.preset.PresetNoiseData" : "data.worldgen.preset.PresetNoiseData")).getMethod("bootstrap",
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
        if (new FreeTerraForgedCompat.Api(loader).epochBased)
            FreeTerraForgedRuntimeTest.verify(loader, sourceAccess, decodedAccess);
        else assertActualInitializerSamples(loader, sourceAccess, decodedAccess);
    }

    private static void assertActualInitializerSamples(ClassLoader loader, RegistryAccess source,
            RegistryAccess decoded) throws Exception {
        var api = new FreeTerraForgedCompat.Api(loader);
        String prefix = api.variant.worldgenClass("");
        var contexts = new java.util.ArrayList<Object>();
        try {
            var fieldType = loader.loadClass(prefix + (api.variant.singleArgumentCellFields() ? "cell.CellField" : "densityfunction.CellSampler$Field"));
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
                var mixinType = loader.loadClass(api.variant.packagePrefix + "mixin.MixinRandomState");
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
                            var target = mixinType.getDeclaredMethod(api.variant.namespace + "$" + api.variant.abbreviation
                                    + "RandomState$" + method.getName(), method.getParameterTypes());
                            target.setAccessible(true);
                            return target.invoke(mixin, args);
                        });
                FreeTerraForgedCompat.scoped(api.overworld, true, () -> {
                    if (api.variant == FreeTerraForgedVariant.ULTRA && access == source) {
                        // The server baseline keeps UTF's original asynchronous pool.
                        try { api.initialize.invoke(proxy,access); }
                        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    } else api.initialize(proxy, access);
                    return null;
                });
                Object context = api.context.invoke(proxy);
                assertNotNull(context, "the real initializer must build a terrain context");
                assertEquals(api.variant, FreeTerraForgedCompat.variant(proxy));
                contexts.add(context);
                assertClimateContextCopy(loader, api.variant, context);
                if (api.variant == FreeTerraForgedVariant.ULTRA && access == decoded)
                    UltraTerraForgedExteriorReleasedTest.verify(loader, context, mixin, access);
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
            if (api.variant == FreeTerraForgedVariant.ULTRA) {
                UltraTerraForgedSurfaceReleasedTest.verify(loader, contexts.getLast());
                for (int i = 0; i < 64; i++) {
                    int x = i * 3 % 64, z = i * 7 % 64;
                    Object a = tiles.getFirst().getClass().getMethod("lookup",int.class,int.class).invoke(tiles.getFirst(),x,z);
                    Object b = tiles.getLast().getClass().getMethod("lookup",int.class,int.class).invoke(tiles.getLast(),x,z);
                    for (var field : a.getClass().getFields()) if (field.getType().isPrimitive() && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                        assertEquals(field.get(a),field.get(b), "UTF caller batches preserve filtered cell " + x + "," + z + ":" + field.getName());
                }
                System.out.println("Released UTF serial batches match original async pool: 64 filtered cells, all primitive fields");
            }
            var readHeight = api.variant.singleArgumentCellFields()
                    ? fieldType.getMethod("read", loader.loadClass(prefix + "cell.Cell"))
                    : fieldType.getMethod("read", loader.loadClass(prefix + "cell.Cell"),
                            loader.loadClass(prefix + "cell.heightmap.Heightmap"));
            for (int i = 0; i < 8; i++) {
                int x = 17 + i * 5, z = 9 + i * 3;
                Object tile = tiles.getFirst();
                Object cell = tile.getClass().getMethod("lookup", int.class, int.class).invoke(tile, x, z);
                double expected;
                if (api.variant.singleArgumentCellFields()) expected = ((Number) readHeight.invoke(height, cell)).doubleValue();
                else {
                    Object lookup = contexts.getFirst().getClass().getField("lookup").get(contexts.getFirst());
                    Object heightmap = lookup.getClass().getMethod("getHeightmap").invoke(lookup);
                    expected = ((Number) readHeight.invoke(height, cell, heightmap)).doubleValue();
                }
                var point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(x, 80, z);
                assertEquals(expected, functions.getFirst().compute(point), 0.0);
                assertEquals(expected, functions.getLast().compute(point), 0.0);
            }
            Object cell = loader.loadClass(prefix + "cell.Cell").getConstructor().newInstance();
            var cellClass = cell.getClass();
            var water = new FreeTerraForgedWater(contexts.getFirst());
            assertEquals(Integer.MIN_VALUE, water.surfaceY(cell), "dry cells must not acquire river water");
            if (api.variant == FreeTerraForgedVariant.ULTRA) {
                Object levels = contexts.getFirst().getClass().getField("levels").get(contexts.getFirst());
                var level = cellClass.getField("waterLevel");
                int sea = levels.getClass().getField("waterY").getInt(levels);
                int worldHeight = levels.getClass().getField("worldHeight").getInt(levels);
                level.setFloat(cell, (float) sea / worldHeight);
                assertEquals(Integer.MIN_VALUE, water.surfaceY(cell), "UTF does not refill the sea");
                level.setFloat(cell, (sea + 32.5f) / worldHeight);
                assertEquals(sea + 33, water.surfaceY(cell), "UTF waterLevel is an absolute height; terrain kind is irrelevant");
                UltraTerraForgedTileCacheReleasedTest.verify(contexts.getLast());
                var firstCache = contexts.getFirst().getClass().getField("cache").get(contexts.getFirst());
                var secondCache = contexts.getLast().getClass().getField("cache").get(contexts.getLast());
                var raw = firstCache.getClass().getDeclaredField("cache"); raw.setAccessible(true);
                Object old = raw.get(firstCache), current = raw.get(secondCache);
                FreeTerraForgedCompat.releaseTileCache(firstCache);
                assertEquals(false, old.getClass().getMethod("isPolling").invoke(old));
                assertEquals(true, current.getClass().getMethod("isPolling").invoke(current), "closing old prediction must leave new world's cache polling");
                assertTrue(Double.isFinite(functions.getLast().compute(new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(11, 80, -13))));
                return;
            }
            boolean elevatedWater = java.util.Arrays.stream(cellClass.getFields())
                    .anyMatch(field -> field.getName().equals("riverWaterLevel"));
            if (!elevatedWater) {
                cellClass.getField("terrain").set(cell, loader.loadClass(prefix + "cell.terrain.TerrainType").getField("RIVER").get(null));
                assertEquals(Integer.MIN_VALUE, water.surfaceY(cell), "legacy rivers retain the generator fluid picker");
                return;
            }
            cellClass.getField("terrain").set(cell, loader.loadClass(prefix + "cell.terrain.TerrainType").getField("RIVER").get(null));
            cellClass.getField("riverWaterLevel").setFloat(cell, 1);
            Object levels = contexts.getFirst().getClass().getField("levels").get(contexts.getFirst());
            int oceanTop = 1 + (int) levels.getClass().getMethod("scale", float.class).invoke(levels,
                    levels.getClass().getField("water").getFloat(levels));
            assertEquals(oceanTop, water.surfaceY(cell), "water block Y must become its upper face, with sea floor clamp");
            cellClass.getField("waterTable").setFloat(cell, 0.8f);
            Class<?> hydrology = loader.loadClass(prefix + "cell.rivermap.ContinentalHydrology");
            boolean complexWater = java.util.Arrays.stream(hydrology.getMethods())
                    .anyMatch(method -> method.getName().equals("getComplexWaterHeight"));
            float uplift;
            if (complexWater) {
                cellClass.getField("globalContinentScale").setFloat(cell, 4000f);
                cellClass.getField("continentSizeModifier").setFloat(cell, 1f);
                uplift = (float) hydrology.getMethod("getComplexWaterHeight", float.class, float.class, float.class)
                        .invoke(null, 0.8f, 4000f, 1f);
            } else {
                uplift = (float) hydrology.getMethod("getWeightedWaterHeight", float.class).invoke(null, 0.8f);
            }
            int expectedWater = 1 + Math.max(oceanTop - 1, (int) levels.getClass().getMethod("scale", float.class)
                    .invoke(levels, levels.getClass().getField("water").getFloat(levels) + uplift));
            assertEquals(expectedWater, water.surfaceY(cell), "match this release's own surface hydrology");
            assertTrue(water.surfaceY(cell) > oceanTop, "uplift rivers must not be flattened to sea level");
        } finally {
            if (api.variant == FreeTerraForgedVariant.ULTRA) {
                for (Object context : contexts) FreeTerraForgedCompat.releaseTileCache(context.getClass().getField("cache").get(context));
            } else {
            var registryField = loader.loadClass(api.variant.packagePrefix + "concurrent.cache.CacheManager").getDeclaredField("CACHES");
            registryField.setAccessible(true);
            var caches = (java.util.List<?>) registryField.get(null);
            for (Object context : contexts) {
                int before = caches.size();
                FreeTerraForgedCompat.releaseTileCache(context.getClass().getField("cache").get(context));
                assertEquals(before - 1, caches.size(), "release only this context's tile cache");
            }
            }
            // The test owns this isolated classloader's scheduler, never the game's scheduler.
            Class<?> cacheClass = loader.loadClass(api.variant.packagePrefix + "concurrent.cache.Cache");
            ((java.util.concurrent.ExecutorService) cacheClass.getField("SCHEDULER").get(null)).shutdownNow();
        }
    }

    private static void assertClimateContextCopy(ClassLoader loader, FreeTerraForgedVariant variant, Object context) throws Exception {
        assertDoesNotThrow(() -> FreeTerraForgedCompat.copyClimateContext(new Object(), new Object()));
        if (variant == FreeTerraForgedVariant.ULTRA) {
            var api = loader.loadClass(variant.worldgenClass("biome.UTFClimateSampler"));
            var spawn = new net.minecraft.core.BlockPos(-173, 97, 4097);
            Object source = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api}, (p, method, args) -> spawn);
            var copied = new java.util.concurrent.atomic.AtomicReference<Object>();
            Object target = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api}, (p, method, args) -> { copied.set(args[0]); return null; });
            FreeTerraForgedCompat.copyClimateContext(source, target);
            assertEquals(spawn, copied.get());
            return;
        }
        if (variant != FreeTerraForgedVariant.CURRENT) return;
        var api = loader.loadClass(variant.worldgenClass("biome.FTFClimateSampler"));
        Object preset = context.getClass().getField("preset").get(context);
        var spawn = new net.minecraft.core.BlockPos(731, 80, -127);
        Object source = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api}, (proxy, method, args) -> switch (method.getName()) {
            case "getUndergroundBiomeBandingPreset" -> preset;
            case "getUndergroundBiomeBandingSeed" -> 918273645123L;
            case "getUndergroundBiomeSurfaceContext" -> context;
            case "getSpawnSearchCenter" -> spawn;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        var copied = new java.util.HashMap<String, Object[]>();
        Object target = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{api}, (proxy, method, args) -> {
            copied.put(method.getName(), args);
            return null;
        });
        FreeTerraForgedCompat.copyClimateContext(source, target);
        assertEquals(3, copied.size());
        assertArrayEquals(new Object[]{preset, 918273645123L}, copied.get("setUndergroundBiomeBandingPreset"));
        assertArrayEquals(new Object[]{context}, copied.get("setUndergroundBiomeSurfaceContext"));
        assertArrayEquals(new Object[]{spawn}, copied.get("setSpawnSearchCenter"));
    }
}
