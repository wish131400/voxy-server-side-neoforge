package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
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

    static boolean isExactCell(Object function) { return function instanceof ExactCell; }

    static <T> T coarse(boolean value, java.util.function.Supplier<T> action) {
        return FreeTerraForgedCompat.scoped(COARSE, value, action);
    }

    static NoiseRouter map(Object context, NoiseRouter router) throws ReflectiveOperationException {
        if (context == null) return router;
        var cells = new FreeTerraForgedCells(context);
        if (cells.tiled()) FreeTerraForgedNativeFilters.register(context);
        var loader = context.getClass().getClassLoader();
        var variant = FreeTerraForgedVariant.fromClass(context.getClass());
        var cellType = loader.loadClass(variant.worldgenClass("densityfunction.CellSampler"));
        var fieldMethod = cellType.getMethod("field");
        var lookup = context.getClass().getField("lookup").get(context);
        Object heightmap = lookup.getClass().getMethod("getHeightmap").invoke(lookup);
        var handles = MethodHandles.publicLookup();
        var mapped = new IdentityHashMap<DensityFunction, DensityFunction>();
        return router.mapAll(function -> {
            if (!cellType.isInstance(function)) return function;
            return mapped.computeIfAbsent(function, original -> {
                try {
                    Object field = fieldMethod.invoke(original);
                    MethodHandle read = handles.unreflect(fieldMethod.getReturnType().getMethod(cells.tiled() ? "read" : "readFinite",
                            loader.loadClass(variant.worldgenClass("cell.Cell")), heightmap.getClass()))
                            .bindTo(field).asType(MethodType.methodType(float.class, Object.class, Object.class));
                    return new ExactCell(original, heightmap, cells, read);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("FreeTerraForged cell-field API unavailable", failure);
                }
            });
        });
    }

    private record ExactCell(DensityFunction source, Object heightmap, FreeTerraForgedCells cells,
                             MethodHandle read, ThreadLocal<Column> columns)
            implements DensityFunction.SimpleFunction, DensityMemo.NonMemoizable {
        ExactCell(DensityFunction source, Object heightmap, FreeTerraForgedCells cells, MethodHandle read) {
            this(source, heightmap, cells, read, ThreadLocal.withInitial(Column::new));
        }
        @Override public double compute(FunctionContext point) {
            if (coarse()) return source.compute(point);
            Column value = columns.get();
            int x = point.blockX(), z = point.blockZ();
            if (!value.valid || value.x != x || value.z != z) {
                try {
                    Object data = cells.cellAt(x, z);
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
