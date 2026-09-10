package dev.xantha.vss.client.prediction;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/** Optional FreeTerraForged context bridge; prediction tile filters run in Rust. */
final class FreeTerraForgedCompat {
    private static final String PREFIX = "raccoonman.reterraforged.world.worldgen.";

    private FreeTerraForgedCompat() { }

    static net.minecraft.world.level.levelgen.NoiseRouter predictionRouter(RandomState state) {
        if (!isState(state)) return state.router();
        try {
            var api = new Api(state.getClass().getClassLoader());
            return FreeTerraForgedDensity.map(api.context.invoke(state), state.router());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged prediction density context unavailable", failure);
        }
    }

    private static boolean isState(Object state) {
        return java.util.Arrays.stream(state.getClass().getInterfaces())
                .anyMatch(type -> type.getName().equals(PREFIX + "RTFRandomState"));
    }

    static RandomState create(boolean required, boolean overworld, RegistryAccess registries,
                              Supplier<RandomState> factory) {
        if (!required) return factory.get();
        try {
            var loader = FreeTerraForgedCompat.class.getClassLoader();
            Api api = new Api(loader);
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
        Class<?> manager = Class.forName("raccoonman.reterraforged.concurrent.cache.CacheManager",
                false, ownedCache.getClass().getClassLoader());
        var cachesField = manager.getDeclaredField("CACHES");
        cachesField.setAccessible(true);
        ((java.util.List<?>) cachesField.get(null)).remove(ownedCache);
    }

    static final class Api {
        final ThreadLocal<Boolean> overworld;
        final Class<?> stateType;
        final Method initialize;
        final Method context;

        @SuppressWarnings("unchecked")
        Api(ClassLoader loader) throws ReflectiveOperationException {
            ThreadLocal<Boolean> dimensionContext;
            try {
                dimensionContext = (ThreadLocal<Boolean>) loader.loadClass(PREFIX + "RTFWorldGenContext")
                        .getField("IS_VANILLA_OVERWORLD").get(null);
            } catch (ClassNotFoundException legacyRelease) {
                // Earlier releases discover their context from CellSampler markers.
                dimensionContext = ThreadLocal.withInitial(() -> false);
            }
            overworld = dimensionContext;
            stateType = loader.loadClass(PREFIX + "RTFRandomState");
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
            Class<?> active = activeChunk(state.getClass().getClassLoader());
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
            return Class.forName(PREFIX + "ActiveChunk", false, loader);
        } catch (ClassNotFoundException legacyRelease) {
            return null;
        }
    }
}
