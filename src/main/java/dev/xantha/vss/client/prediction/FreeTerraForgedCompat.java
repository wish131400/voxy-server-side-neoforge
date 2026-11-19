package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/** Optional FreeTerraForged context bridge for tile and epoch releases. */
final class FreeTerraForgedCompat {
    private FreeTerraForgedCompat() { }

    static net.minecraft.world.level.levelgen.NoiseRouter predictionRouter(RandomState state) {
        if (!isState(state)) return state.router();
        try {
            var api = new Api(state.getClass().getClassLoader(), variant(state));
            if (api.legacyRtf) LegacyRtfRuntime.validateRouter(api.stateType, state);
            return FreeTerraForgedDensity.map(api.context.invoke(state), state.router());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged prediction density context unavailable", failure);
        }
    }

    private static boolean isState(Object state) {
        return variant(state) != null;
    }

    static FreeTerraForgedVariant variant(Object state) {
        FreeTerraForgedVariant fallback = null;
        for (Class<?> type = state.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> api : type.getInterfaces()) for (var variant : FreeTerraForgedVariant.values()) {
                if (!api.getName().equals(variant.stateClass())) continue;
                if (fallback == null) fallback = variant;
                try {
                    if (api.getMethod("generatorContext").invoke(state) != null) return variant;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("FreeTerraForged state context unavailable", failure);
                }
            }
        }
        return fallback;
    }

    static RandomState create(boolean required, boolean overworld, RegistryAccess registries,
                              String namespace, NoiseGeneratorSettings settings, boolean rtfOverworldSettings,
                              Supplier<RandomState> factory) {
        if (!required) return factory.get();
        try {
            var loader = FreeTerraForgedCompat.class.getClassLoader();
            var variant = namespace != null ? FreeTerraForgedVariant.fromNamespace(namespace)
                    : java.util.Arrays.stream(FreeTerraForgedVariant.values())
                    .filter(candidate -> candidate.hasPreset(registries)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("FreeTerraForged snapshot contains no active preset"));
            Api api = new Api(loader, variant);
            // Epoch releases initialize only their plan-owned generators. Other
            // dimensions use the ordinary router and have no terrain context.
            if (api.epochBased) return factory.get();
            // NeoTerraForged compares the RandomState settings with the
            // registered overworld value by identity.
            boolean neoTerra = variant == FreeTerraForgedVariant.LEGACY && !api.legacyRtf;
            if (neoTerra && !rtfOverworldSettings) return factory.get();
            RegistryAccess initializerRegistries = neoTerra ? withOverworldSettings(registries, settings) : registries;
            return scoped(api.overworld, overworld, () -> {
                RandomState state = factory.get();
                api.initialize(state, initializerRegistries);
                return state;
            });
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged prediction API unavailable", failure);
        }
    }

    private static RegistryAccess withOverworldSettings(RegistryAccess registries, NoiseGeneratorSettings settings) {
        var noiseSettings = new MappedRegistry<NoiseGeneratorSettings>(Registries.NOISE_SETTINGS,
                com.mojang.serialization.Lifecycle.stable());
        noiseSettings.register(NoiseGeneratorSettings.OVERWORLD, settings, RegistrationInfo.BUILT_IN);
        var entries = new java.util.ArrayList<net.minecraft.core.Registry<?>>();
        registries.registries().filter(entry -> !entry.key().equals(Registries.NOISE_SETTINGS))
                .forEach(entry -> entries.add(entry.value()));
        entries.add(noiseSettings.freeze());
        return new RegistryAccess.ImmutableRegistryAccess(entries);
    }

    static <T> T scoped(ThreadLocal<Boolean> context, boolean overworld, Supplier<T> action) {
        Boolean previous = context.get();
        context.set(overworld);
        try { return action.get(); }
        finally {
            if (previous == null) context.remove();
            else context.set(previous);
        }
    }

    static void copyClimateContext(Object source, Object target) {
        for (Class<?> api : source.getClass().getInterfaces()) {
            if (api.getName().equals(FreeTerraForgedVariant.CURRENT.worldgenClass("biome.FTFClimateSampler"))
                    || api.getName().equals(FreeTerraForgedVariant.ULTRA.worldgenClass("biome.UTFClimateSampler")))
                copyClimateContext(api, source, target);
        }
    }

    private static void copyClimateContext(Class<?> api, Object source, Object target) {
        try {
            if (api.getName().equals(FreeTerraForgedVariant.ULTRA.worldgenClass("biome.UTFClimateSampler"))) {
                api.getMethod("setSpawnSearchCenter", net.minecraft.core.BlockPos.class).invoke(target,
                        api.getMethod("getSpawnSearchCenter").invoke(source));
                return;
            }
            Method semantics;
            try { semantics = api.getMethod("climateQuerySemantics"); }
            catch (NoSuchMethodException tileRelease) { semantics = null; }
            if (semantics != null) {
                var plan = api.getMethod("getWorldgenPlan");
                api.getMethod("setWorldgenPlan", plan.getReturnType()).invoke(target, plan.invoke(source));
                Object query = semantics.invoke(source);
                if (query != null) {
                    Class<?> type = query.getClass();
                    var policy = type.getMethod("policy");
                    var preset = type.getMethod("preset");
                    var context = type.getMethod("surfaceContext");
                    api.getMethod("setClimateQuerySemantics", policy.getReturnType(), preset.getReturnType(),
                            long.class, context.getReturnType()).invoke(target, policy.invoke(query),
                            preset.invoke(query), type.getMethod("seed").invoke(query), context.invoke(query));
                }
                copySpawnSearch(api, source, target);
                return;
            }
            var preset = api.getMethod("getUndergroundBiomeBandingPreset");
            var context = api.getMethod("getUndergroundBiomeSurfaceContext");
            api.getMethod("setUndergroundBiomeBandingPreset", preset.getReturnType(), long.class).invoke(target,
                    preset.invoke(source), api.getMethod("getUndergroundBiomeBandingSeed").invoke(source));
            api.getMethod("setUndergroundBiomeSurfaceContext", context.getReturnType()).invoke(target, context.invoke(source));
            api.getMethod("setSpawnSearchCenter", net.minecraft.core.BlockPos.class).invoke(target,
                    api.getMethod("getSpawnSearchCenter").invoke(source));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged climate context unavailable", failure);
        }
    }

    private static void copySpawnSearch(Class<?> api, Object source, Object target)
            throws ReflectiveOperationException {
        var get = api.getMethod("getSpawnSearch");
        Object search = get.invoke(source);
        if (search == null) search = get.invoke(target);
        if (search == null) {
            // The released Mixin can leave a new Climate.Sampler's search unset.
            // Restore its WORLD_ORIGIN/ZERO default; setSpawnSearch rejects null.
            Class<?> type = Class.forName(FreeTerraForgedVariant.CURRENT.packagePrefix
                    + "data.worldgen.preset.settings.SpawnType", false, api.getClassLoader());
            search = get.getReturnType().getConstructor(type, net.minecraft.core.BlockPos.class)
                    .newInstance(type.getField("WORLD_ORIGIN").get(null), net.minecraft.core.BlockPos.ZERO);
        }
        api.getMethod("setSpawnSearch", get.getReturnType()).invoke(target, search);
    }

    /** Release only this prediction context, after its VSS workers have stopped. */
    static void releaseTileCache(Object tileCache) throws ReflectiveOperationException {
        if (tileCache == null) return;
        var field = tileCache.getClass().getDeclaredField("cache");
        field.setAccessible(true);
        releaseCache(field.get(tileCache));
    }

    static void releaseCache(Object ownedCache) throws ReflectiveOperationException {
        // Upstream Cache.close only cancels polling; CacheManager otherwise
        // retains the cache and its tile arrays across multiplayer disconnects.
        ownedCache.getClass().getMethod("close").invoke(ownedCache);
        var mapField = ownedCache.getClass().getDeclaredField("map");
        mapField.setAccessible(true);
        Object map = mapField.get(ownedCache);
        mapField.getType().getMethod("clear").invoke(map);
        var variant = FreeTerraForgedVariant.fromClass(ownedCache.getClass());
        // UTF's scheduler already holds caches weakly; there is no global list.
        if (variant == FreeTerraForgedVariant.ULTRA) return;
        Class<?> manager = Class.forName(variant.packagePrefix + "concurrent.cache.CacheManager",
                false, ownedCache.getClass().getClassLoader());
        var cachesField = manager.getDeclaredField("CACHES");
        cachesField.setAccessible(true);
        ((java.util.List<?>) cachesField.get(null)).remove(ownedCache);
    }

    static final class Api {
        final ThreadLocal<Boolean> overworld;
        final FreeTerraForgedVariant variant;
        final Class<?> stateType;
        final Method initialize;
        final Method context;
        final boolean epochBased;
        /** ReTerraForged: its state initializes only from a ServerLevel, so VSS runs LegacyRtfRuntime instead. */
        final boolean legacyRtf;

        Api(ClassLoader loader) throws ReflectiveOperationException {
            this(loader, FreeTerraForgedVariant.detect(loader));
        }

        @SuppressWarnings("unchecked")
        Api(ClassLoader loader, FreeTerraForgedVariant variant) throws ReflectiveOperationException {
            this.variant = variant;
            stateType = loader.loadClass(variant.stateClass());
            Method initializer;
            boolean epoch;
            boolean legacy = false;
            try { initializer = stateType.getMethod("initialize", RegistryAccess.class); epoch = false; }
            catch (NoSuchMethodException epochRelease) {
                if (variant == FreeTerraForgedVariant.LEGACY && LegacyRtfRuntime.legacyState(stateType)) {
                    initializer = null;
                    epoch = false;
                    legacy = true;
                } else {
                    initializer = stateType.getMethod("initialize", loader.loadClass(variant.worldgenClass("runtime.WorldgenEpoch")));
                    epoch = true;
                }
            }
            initialize = initializer;
            epochBased = epoch;
            legacyRtf = legacy;
            ThreadLocal<Boolean> dimensionContext;
            try {
                dimensionContext = (ThreadLocal<Boolean>) loader.loadClass(variant.worldgenClass(variant.abbreviation + "WorldGenContext"))
                        .getField("IS_VANILLA_OVERWORLD").get(null);
            } catch (ClassNotFoundException legacyRelease) {
                if (!variant.discoversContextFromMarkers() && !epochBased) throw legacyRelease;
                // Earlier releases discover their context from CellSampler markers.
                dimensionContext = ThreadLocal.withInitial(() -> false);
            }
            overworld = dimensionContext;
            context = stateType.getMethod("generatorContext");
        }

        void initialize(Object state, RegistryAccess registries) {
            if (legacyRtf) {
                boolean bound = Boolean.TRUE.equals(overworld.get());
                LegacyRtfRuntime.initialize(stateType, state, registries, bound, "minecraft:overworld");
                return;
            }
            try {
                if (!stateType.isInstance(state)) throw new IllegalStateException("FreeTerraForged RandomState mixin is not active");
                initialize.invoke(state, registries);
                if (variant == FreeTerraForgedVariant.ULTRA)
                    FreeTerraForgedPredictionTasks.own(context.invoke(state));
                // Vanilla presets legitimately have no CellSampler even with the mod installed.
                // A missing preset, however, cannot initialize any RTF terrain correctly.
                if (stateType.getMethod("preset").invoke(state) == null) {
                    throw new IllegalStateException("FreeTerraForged snapshot contains no active preset");
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("FreeTerraForged prediction initialization failed", failure);
            }
        }
    }

    /** FreeTerraForged's NoiseChunk mixin expects the chunk normally supplied by its generator. */
    static <T> T withSurfaceChunk(RandomState state, ChunkAccess chunk, Supplier<T> action) {
        if (!isState(state)) return action.get();
        try {
            Class<?> active = activeChunk(state.getClass().getClassLoader(), variant(state));
            if (active == null) return action.get();
            Method get = active.getMethod("get");
            Method set = active.getMethod("set", ChunkAccess.class);
            Object previous = get.invoke(null);
            set.invoke(null, chunk);
            try { return action.get(); }
            finally { set.invoke(null, previous); }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged surface chunk context unavailable", failure);
        }
    }

    static Class<?> activeChunk(ClassLoader loader) {
        try {
            return activeChunk(loader, FreeTerraForgedVariant.detect(loader));
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    private static Class<?> activeChunk(ClassLoader loader, FreeTerraForgedVariant variant) {
        try {
            return Class.forName(variant.worldgenClass("ActiveChunk"), false, loader);
        } catch (ClassNotFoundException legacyRelease) {
            return null;
        }
    }
}
