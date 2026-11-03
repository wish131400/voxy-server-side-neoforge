package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Version-specific filtered storage. Pooled legacy tiles never escape a query. */
final class FreeTerraForgedCells implements AutoCloseable {
    private final Object context, cache;
    private final MethodHandle provide, lookup;
    private final boolean tiled;

    FreeTerraForgedCells(Object context) throws ReflectiveOperationException {
        this.context = context;
        var handles = MethodHandles.publicLookup();
        var type = context.getClass();
        var loader = type.getClassLoader();
        String prefix = FreeTerraForgedVariant.fromClass(type).worldgenClass("");
        boolean legacy;
        try { type.getField("cache"); legacy = true; }
        catch (NoSuchFieldException chunkCache) { legacy = false; }
        tiled = legacy;
        cache = type.getField(tiled ? "cache" : "filteredChunkCache").get(context);
        var method = cache.getClass().getMethod(tiled ? "provideAtChunk" : "getFilteredChunk", int.class, int.class);
        provide = handles.unreflect(method).bindTo(cache)
                .asType(MethodType.methodType(Object.class, int.class, int.class));
        lookup = (tiled ? handles.unreflect(method.getReturnType().getMethod("lookup", int.class, int.class))
                : handles.unreflect(loader.loadClass(prefix + "cell.filter.ChunkFilterGrid")
                    .getMethod("getPaddedCell", method.getReturnType(), int.class, int.class)))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
    }

    boolean tiled() { return tiled; }

    Object cellAt(int x, int z) {
        try {
            Object storage = (Object) provide.invokeExact(x >> 4, z >> 4);
            return (Object) lookup.invokeExact(storage, tiled ? x : x & 15, tiled ? z : z & 15);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("FreeTerraForged filtered cell query failed", failure); }
    }

    @Override public void close() throws ReflectiveOperationException {
        if (tiled) FreeTerraForgedCompat.releaseTileCache(cache);
        else context.getClass().getMethod("close").invoke(context);
    }
}
