package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.FreeTerraForgedRuntime;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;

/** Released epoch/compiler/cache APIs; invoked once per isolated release JVM. */
final class FreeTerraForgedRuntimeTest {
    private static final String PREFIX = "etcodehome.freeterraforged.world.worldgen.";

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void verify(ClassLoader loader, RegistryAccess source, RegistryAccess decoded) throws Exception {
        var vanilla = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var settings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var dimension = vanilla.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var plain = biomes.register(Biomes.PLAINS, new Biome.BiomeBuilder().hasPrecipitation(true)
                .temperature(0.8f).downfall(0.4f).specialEffects(new BiomeSpecialEffects.Builder()
                    .fogColor(1).waterColor(2).waterFogColor(3).skyColor(4).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build(),
                RegistrationInfo.BUILT_IN);
        biomes.register(Biomes.DESERT, plain.value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        var additional = new ArrayList<Registry<?>>();
        additional.add(biomes);
        for (var key : List.of(Registries.STRUCTURE, Registries.STRUCTURE_SET, Registries.TEMPLATE_POOL,
                Registries.PROCESSOR_LIST))
            additional.add(new MappedRegistry(key, Lifecycle.stable()).freeze());
        for (String name : List.of("biome_modifier", "structure_rule"))
            additional.add(new MappedRegistry(ResourceKey.createRegistryKey(
                    ResourceLocation.parse("freeterraforged:worldgen/" + name)), Lifecycle.stable()).freeze());
        var roots = new ArrayList<NoiseBasedChunkGenerator>();
        var contexts = new ArrayList<Object>();
        var functions = new ArrayList<DensityFunction>();
        var metadata = metadata();
        if (Arrays.stream(loader.loadClass(PREFIX + "runtime.WorldgenEpoch").getMethods())
                .noneMatch(m -> m.getName().equals("biomeReplacementConfig"))) metadata.remove("biome_replacement");
        try {
            for (RegistryAccess input : List.of(source, decoded)) {
                var values = new LinkedHashMap<ResourceKey<?>, Registry<?>>();
                input.registries().forEach(entry -> values.put(entry.key(), entry.value()));
                additional.forEach(registry -> values.put(registry.key(), registry));
                var access = new RegistryAccess.ImmutableRegistryAccess(List.copyOf(values.values()));
                var climate = new Climate.ParameterPoint(Climate.Parameter.point(0), Climate.Parameter.point(0),
                        Climate.Parameter.point(0), Climate.Parameter.point(0), Climate.Parameter.point(0),
                        Climate.Parameter.point(0), 0);
                var biomeSource = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.of(
                        com.mojang.datafixers.util.Pair.of(climate, (Holder<Biome>) plain))));
                var generator = FreeTerraForgedRuntime.generator(loader, biomeSource, Holder.direct(settings));
                roots.add(generator);
                assertTrue(FreeTerraForgedRuntime.supports(generator));
                assertSame(biomeSource, FreeTerraForgedRuntime.acquisitionSource(generator));
                Object epoch = FreeTerraForgedRuntime.epoch(generator, metadata, ResourceLocation.parse("minecraft:overworld"),
                        918273L, access, dimension);
                var api = new FreeTerraForgedCompat.Api(loader);
                assertTrue(api.epochBased);
                var mixinType = loader.loadClass("etcodehome.freeterraforged.mixin.MixinRandomState");
                var constructor = mixinType.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object mixin = constructor.newInstance();
                var field = loader.loadClass(PREFIX + "densityfunction.CellSampler$Field");
                Object marker = loader.loadClass(PREFIX + "densityfunction.CellSampler$Marker")
                        .getConstructor(field).newInstance(field.getField("HEIGHT").get(null));
                var components = NoiseRouter.class.getRecordComponents();
                Object[] entries = new Object[components.length];
                Arrays.fill(entries, marker);
                NoiseRouter router = NoiseRouter.class.getConstructor(Arrays.stream(components)
                        .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(entries);
                var redirect = Arrays.stream(mixinType.getDeclaredMethods()).filter(m -> m.getName().equals("RandomState"))
                        .findFirst().orElseThrow();
                redirect.setAccessible(true);
                NoiseRouter mapped = (NoiseRouter) redirect.invoke(mixin, router,
                        (DensityFunction.Visitor) function -> function, settings, null, 918273L);
                Object[] binding = new Object[1];
                Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{api.stateType}, (p, method, args) -> {
                    // No running Mixin environment: bind/close the owned runtime
                    // here; all terrain initialization uses the actual release.
                    if (method.getName().equals("bindPlan")) { binding[0] = args[0]; return null; }
                    if (method.getName().equals("close")) {
                        Object context = api.context.invoke(p);
                        if (context != null) context.getClass().getMethod("close").invoke(context);
                        return null;
                    }
                    var target = mixinType.getDeclaredMethod("freeterraforged$FTFRandomState$" + method.getName(), method.getParameterTypes());
                    target.setAccessible(true);
                    return target.invoke(mixin, args);
                });
                FreeTerraForgedRuntime.initialize(generator, epoch, proxy);
                assertNotNull(binding[0], "released compiler must build an executable runtime binding");
                if (metadata.has("biome_replacement")) assertTrue(generator.getBiomeSource().possibleBiomes().stream().anyMatch(b -> b.is(Biomes.DESERT)),
                        "runtime must execute the server's plains-to-desert replacement");
                Object context = api.context.invoke(proxy);
                contexts.add(context);
                if (Boolean.getBoolean("vss.ftfExteriorReleased"))
                    FreeTerraForgedExteriorReleasedTest.verify(loader, context, mixin, access);
                JsonObject snapshot = FreeTerraForgedRuntime.snapshot(generator);
                assertEquals(metadata, snapshot, "snapshot must preserve server replacement content and epoch inputs");
                assertClimateCopy(loader, context);
                var density = FreeTerraForgedDensity.map(context, mapped).finalDensity();
                functions.add(density);
                Object cache = context.getClass().getField("filteredChunkCache").get(context);
                var cacheMap = cache.getClass().getDeclaredField("cache");
                cacheMap.setAccessible(true);
                var before = ((Map<?, ?>) cacheMap.get(cache)).size();
                assertTrue(Double.isFinite(FreeTerraForgedDensity.coarse(true, () -> density.compute(
                        new DensityFunction.SinglePointContext(777, 80, -567)))));
                assertEquals(before, ((Map<?, ?>) cacheMap.get(cache)).size(), "preview must not force filtered regions");
                var cells = new FreeTerraForgedCells(context);
                assertFalse(cells.tiled());
                var read = field.getMethod("readFinite", loader.loadClass(PREFIX + "cell.Cell"),
                        loader.loadClass(PREFIX + "cell.heightmap.Heightmap"));
                Object heightmap = context.getClass().getField("heightmap").get(context);
                for (int[] xz : new int[][]{{0,0},{15,15},{16,16},{-1,-1},{-17,31},{63,63},{64,64},{511,-257}}) {
                    Object expectedChunk = cache.getClass().getMethod("getFilteredChunk", int.class, int.class)
                            .invoke(cache, xz[0] >> 4, xz[1] >> 4);
                    Object expectedCell = loader.loadClass(PREFIX + "cell.filter.ChunkFilterGrid")
                            .getMethod("getPaddedCell", expectedChunk.getClass(), int.class, int.class)
                            .invoke(null, expectedChunk, xz[0] & 15, xz[1] & 15);
                    assertSame(expectedCell, cells.cellAt(xz[0], xz[1]));
                    double expected = ((Number) read.invoke(field.getField("HEIGHT").get(null), expectedCell, heightmap)).doubleValue();
                    assertEquals(expected, density.compute(new DensityFunction.SinglePointContext(xz[0], 80, xz[1])), 0.0);
                }
                var water = new FreeTerraForgedWater(context);
                Object dry = loader.loadClass(PREFIX + "cell.Cell").getConstructor().newInstance();
                assertEquals(Integer.MIN_VALUE, water.surfaceY(dry));
                var cell = dry.getClass();
                cell.getField("terrain").set(dry, loader.loadClass(PREFIX + "cell.terrain.TerrainType").getField("RIVER").get(null));
                cell.getField("riverWaterLevel").setFloat(dry, 1f);
                cell.getField("waterTable").setFloat(dry, 0.8f);
                cell.getField("globalContinentScale").setFloat(dry, 4000f);
                cell.getField("continentSizeModifier").setFloat(dry, 1f);
                Object levels = context.getClass().getField("levels").get(context);
                var scale = levels.getClass().getMethod("scale", float.class);
                float ocean = levels.getClass().getField("water").getFloat(levels);
                float uplift = (float) loader.loadClass(PREFIX + "cell.rivermap.ContinentalHydrology")
                        .getMethod("getComplexWaterHeight", float.class, float.class, float.class)
                        .invoke(null, 0.8f, 4000f, 1f);
                assertEquals(1 + Math.max((int) scale.invoke(levels, ocean), (int) scale.invoke(levels, ocean + uplift)),
                        water.surfaceY(dry), "elevated water must retain the release's hydrology");
            }
            for (int i = 0; i < 16; i++) {
                var point = new DensityFunction.SinglePointContext(i * 9 - 17, 80, -i * 5);
                assertEquals(functions.getFirst().compute(point), functions.getLast().compute(point), 0.0,
                        "preset/noise round-trip must retain filtered terrain");
            }
        } finally {
            for (var root : roots) ((AutoCloseable) root).close();
            for (Object context : contexts) {
                Object cache = context.getClass().getField("filteredChunkCache").get(context);
                var map = cache.getClass().getDeclaredField("cache"); map.setAccessible(true);
                assertTrue(((Map<?, ?>) map.get(cache)).isEmpty(), "disconnect must release prediction chunks");
            }
        }
    }

    static JsonObject metadata() {
        return com.google.gson.JsonParser.parseString("""
                {"schema":1,"settings_identity":"vss:test","resource_revision":2,"resource_fingerprint":"server-data",
                 "tag_sequence":3,"tag_fingerprint":"server-tags","biome_replacement":
                 {"revision":4,"fingerprint":"server-config","path":"server.toml","origin":"NATIVE",
                  "content":"minecraft:plains > minecraft:desert","legacy":false},"contributions":{"revisions":{},"failures":[]}}
                """).getAsJsonObject();
    }

    static void assertClimateCopy(ClassLoader loader, Object context) throws Exception {
        var api = loader.loadClass(PREFIX + "biome.FTFClimateSampler");
        var mixin = loader.loadClass("etcodehome.freeterraforged.mixin.MixinClimateSampler");
        var constructor = mixin.getDeclaredConstructor(); constructor.setAccessible(true);
        var instances = new ArrayList<Object>();
        for (int i = 0; i < 2; i++) {
            Object target = constructor.newInstance();
            instances.add(Proxy.newProxyInstance(loader, new Class<?>[]{api}, (p, method, args) -> {
                var implementation = mixin.getDeclaredMethod("freeterraforged$FTFClimateSampler$" + method.getName(), method.getParameterTypes());
                implementation.setAccessible(true);
                return implementation.invoke(target, args);
            }));
        }
        var policy = loader.loadClass(PREFIX + "biome.ClimateQueryPolicy");
        Object preset = context.getClass().getField("preset").get(context);
        api.getMethod("setClimateQuerySemantics", policy, preset.getClass(), long.class, context.getClass())
                .invoke(instances.getFirst(), policy.getField("WORLDGEN").get(null), preset, 918273645123L, context);
        FreeTerraForgedCompat.copyClimateContext(instances.getFirst(), instances.getLast());
        var semantics = api.getMethod("climateQuerySemantics");
        Object original = semantics.invoke(instances.getFirst()), copied = semantics.invoke(instances.getLast());
        for (String name : List.of("policy", "preset", "seed", "surfaceContext"))
            assertEquals(original.getClass().getMethod(name).invoke(original), copied.getClass().getMethod(name).invoke(copied));
        assertNotSame(original.getClass().getMethod("cacheOwner").invoke(original),
                copied.getClass().getMethod("cacheOwner").invoke(copied), "new samplers own independent query caches");
    }
}
