package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.util.KeyDispatchDataCodec;

/** The same cell-field reads used by RTF's full-chunk CacheChunk, without quart-coordinate rounding. */
final class FreeTerraForgedDensity {
    private static final ThreadLocal<Boolean> COARSE = ThreadLocal.withInitial(() -> false);

    static boolean coarse() { return COARSE.get(); }

    static <T> T coarse(boolean value, java.util.function.Supplier<T> action) {
        return FreeTerraForgedCompat.scoped(COARSE, value, action);
    }

    static NoiseRouter map(Object context, NoiseRouter router) throws ReflectiveOperationException {
        if (context == null) return router;
        FreeTerraForgedNativeFilters.register(context);
        var loader = context.getClass().getClassLoader();
        var cellType = loader.loadClass("raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler");
        var fieldMethod = cellType.getMethod("field");
        var lookup = context.getClass().getField("lookup").get(context);
        Object heightmap = lookup.getClass().getMethod("getHeightmap").invoke(lookup);
        Object cache = context.getClass().getField("cache").get(context);
        var provideMethod = cache.getClass().getMethod("provideAtChunk", int.class, int.class);
        var handles = MethodHandles.publicLookup();
        MethodHandle provide = handles.unreflect(provideMethod).bindTo(cache)
                .asType(MethodType.methodType(Object.class, int.class, int.class));
        MethodHandle cell = handles.unreflect(provideMethod.getReturnType().getMethod("lookup", int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        var mapped = new IdentityHashMap<DensityFunction, DensityFunction>();
        return router.mapAll(function -> {
            if (!cellType.isInstance(function)) return function;
            return mapped.computeIfAbsent(function, original -> {
                try {
                    Object field = fieldMethod.invoke(original);
                    MethodHandle read = handles.unreflect(fieldMethod.getReturnType().getMethod("read",
                            loader.loadClass("raccoonman.reterraforged.world.worldgen.cell.Cell"), heightmap.getClass()))
                            .bindTo(field).asType(MethodType.methodType(float.class, Object.class, Object.class));
                    return new ExactCell(original, heightmap, provide, cell, read);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("FreeTerraForged cell-field API unavailable", failure);
                }
            });
        });
    }

    private record ExactCell(DensityFunction source, Object heightmap, MethodHandle provide,
                             MethodHandle cell, MethodHandle read, ThreadLocal<Column> columns)
            implements DensityFunction.SimpleFunction {
        ExactCell(DensityFunction source, Object heightmap, MethodHandle provide, MethodHandle cell, MethodHandle read) {
            this(source, heightmap, provide, cell, read, ThreadLocal.withInitial(Column::new));
        }
        @Override public double compute(FunctionContext point) {
            if (coarse()) return source.compute(point);
            Column value = columns.get();
            int x = point.blockX(), z = point.blockZ();
            if (!value.valid || value.x != x || value.z != z) {
                try {
                    Object tile = (Object) provide.invokeExact(x >> 4, z >> 4);
                    Object data = (Object) cell.invokeExact(tile, x, z);
                    value.value = (float) read.invokeExact(data, heightmap);
                    value.x = x; value.z = z; value.valid = true;
                } catch (RuntimeException | Error failure) { throw failure; }
                catch (Throwable failure) { throw new IllegalStateException("FreeTerraForged cell sampling failed", failure); }
            }
            return value.value;
        }
        @Override public double minValue() { return source.minValue(); }
        @Override public double maxValue() { return source.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Runtime prediction cell");
        }
    }
    private static final class Column { int x, z; float value; boolean valid; }
    private FreeTerraForgedDensity() { }
}
