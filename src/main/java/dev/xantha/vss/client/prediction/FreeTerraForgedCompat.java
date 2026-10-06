package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/** Optional FreeTerraForged context bridge; prediction tile filters run in Rust. */
final class FreeTerraForgedCompat {
    private FreeTerraForgedCompat() { }

    static net.minecraft.world.level.levelgen.NoiseRouter predictionRouter(RandomState state) {
        if (!isState(state)) return state.router();
        try {
            var api = new Api(state.getClass().getClassLoader(), variant(state));
            return FreeTerraForgedDensity.map(api.context.invoke(state), state.router());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged prediction density context unavailable", failure);
        }
    }

    private static boolean isState(Object state) {
        return variant(state) != null;
    }

    private static FreeTerraForgedVariant variant(Object state) {
        for (Class<?> type = state.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> api : type.getInterfaces()) for (var variant : FreeTerraForgedVariant.values()) {
                if (api.getName().equals(variant.stateClass())) return variant;
            }
        }
        return null;
    }

    static RandomState create(boolean required, boolean overworld, RegistryAccess registries,
                              Supplier<RandomState> factory) {
        if (!required) return factory.get();
        try {
            var loader = FreeTerraForgedCompat.class.getClassLoader();
            var variant = java.util.Arrays.stream(FreeTerraForgedVariant.values())
                    .filter(candidate -> candidate.hasPreset(registries)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("FreeTerraForged snapshot contains no active preset"));
            Api api = new Api(loader, variant);
            return scoped(api.overworld, overworld, () -> {
                RandomState state = factory.get();
                api.initialize(state, registries);
                return state;
            });
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged prediction API unavailable", failure);
        }
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
        Class<?> api = java.util.Arrays.stream(source.getClass().getInterfaces())
                .filter(type -> type.getName().equals(FreeTerraForgedVariant.CURRENT.worldgenClass("biome.FTFClimateSampler")))
                .findFirst().orElse(null);
        if (api == null) return;
        try {
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
        Class<?> manager = Class.forName(FreeTerraForgedVariant.fromClass(ownedCache.getClass()).packagePrefix + "concurrent.cache.CacheManager",
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

        Api(ClassLoader loader) throws ReflectiveOperationException {
            this(loader, FreeTerraForgedVariant.detect(loader));
        }

        @SuppressWarnings("unchecked")
        Api(ClassLoader loader, FreeTerraForgedVariant variant) throws ReflectiveOperationException {
            this.variant = variant;
            ThreadLocal<Boolean> dimensionContext;
            try {
                dimensionContext = (ThreadLocal<Boolean>) loader.loadClass(variant.worldgenClass(variant.abbreviation + "WorldGenContext"))
                        .getField("IS_VANILLA_OVERWORLD").get(null);
            } catch (ClassNotFoundException legacyRelease) {
                if (variant != FreeTerraForgedVariant.LEGACY) throw legacyRelease;
                // Earlier releases discover their context from CellSampler markers.
                dimensionContext = ThreadLocal.withInitial(() -> false);
            }
            overworld = dimensionContext;
            stateType = loader.loadClass(variant.stateClass());
            initialize = stateType.getMethod("initialize", RegistryAccess.class);
            context = stateType.getMethod("generatorContext");
        }

        void initialize(Object state, RegistryAccess registries) {
            try {
                if (!stateType.isInstance(state)) throw new IllegalStateException("FreeTerraForged RandomState mixin is not active");
                initialize.invoke(state, registries);
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
