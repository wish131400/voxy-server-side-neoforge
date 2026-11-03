package dev.xantha.vss.client.prediction;

import java.lang.reflect.Array;
import java.util.IdentityHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

/** Inspect source graphs, including C2ME's retained fallback, without replacing compiled evaluation. */
final class FreeTerraForgedExteriorOrder {
    private record Info(boolean vertical, boolean unsafe) {
        Info plus(Info other) { return new Info(vertical || other.vertical, unsafe || other.unsafe); }
    }
    private static final Info HORIZONTAL = new Info(false, false), UNKNOWN = new Info(true, true);
    private final IdentityHashMap<Object, Info> seen = new IdentityHashMap<>();
    private int remaining = 32768;
    private String firstUnsafe;

    static String reason(NoiseRouter router) {
        var analysis = new FreeTerraForgedExteriorOrder();
        try { analysis.inspect(router, 0); return analysis.firstUnsafe; }
        catch (ReflectiveOperationException | RuntimeException failure) { return failure.toString(); }
    }

    static boolean canBatch(NoiseRouter router) {
        try { return !new FreeTerraForgedExteriorOrder().inspect(router, 0).unsafe; }
        catch (ReflectiveOperationException | RuntimeException unsupported) { return false; }
    }

    private Info inspect(Object value, int depth) throws ReflectiveOperationException {
        if (value == null || value instanceof Number || value instanceof String
                || value instanceof Enum<?> && !(value instanceof DensityFunction)) return HORIZONTAL;
        if (depth > 128 || --remaining < 0) return UNKNOWN;
        Info previous = seen.get(value);
        if (previous != null) return previous;
        seen.put(value, UNKNOWN);
        Info result = inspectNew(value, depth);
        if (result.unsafe && firstUnsafe == null) firstUnsafe = value.getClass().getName();
        seen.put(value, result);
        return result;
    }

    private Info inspectNew(Object value, int depth) throws ReflectiveOperationException {
        if (FreeTerraForgedDensity.isExactCell(value)) return HORIZONTAL;
        if (value instanceof Holder<?> holder) return inspect(holder.value(), depth + 1);
        if (value instanceof com.mojang.datafixers.util.Pair<?, ?> pair)
            return inspect(pair.getFirst(), depth + 1).plus(inspect(pair.getSecond(), depth + 1));
        if (value instanceof Iterable<?> list) {
            Info result = HORIZONTAL;
            for (Object child : list) result = result.plus(inspect(child, depth + 1));
            return result;
        }
        Class<?> type = value.getClass();
        String name = type.getName(), simple = type.getSimpleName();
        if (type.isArray()) {
            Info result = HORIZONTAL;
            if (!type.getComponentType().isPrimitive())
                for (int i = 0; i < Array.getLength(value); i++) result = result.plus(inspect(Array.get(value, i), depth + 1));
            return result;
        }
        // These two released C2ME classes keep the original density graph for blending.
        // Inspect it for ordering constraints; still evaluate the compiled graph in NoiseChunk.
        if (name.equals("com.ishland.c2me.opts.dfc.common.gen.jvm.SubCompiledDensityFunction")
                || name.equals("com.ishland.c2me.opts.dfc.common.gen.jvm.CompiledDensityFunction")) {
            Class<?> base = name.endsWith(".SubCompiledDensityFunction") ? type : type.getSuperclass();
            var fallback = base.getDeclaredMethod("getFallback");
            fallback.setAccessible(true);
            return inspect(fallback.invoke(value), depth + 1);
        }
        if (value instanceof DensityFunctions.MarkerOrMarked marker) {
            if (!name.equals("net.minecraft.world.level.levelgen.DensityFunctions$Marker")) return UNKNOWN;
            Info child = inspect(marker.wrapped(), depth + 1);
            return switch (((Enum<?>) marker.type()).name()) {
                case "FlatCache" -> new Info(false, child.unsafe);
                case "Cache2D" -> new Info(child.vertical, child.unsafe || child.vertical);
                default -> child;
            };
        }
        boolean vanilla = name.startsWith("net.minecraft.world.level.levelgen.DensityFunctions$");
        boolean ftf = name.startsWith("etcodehome.freeterraforged.world.worldgen.densityfunction.")
                || name.startsWith("raccoonman.reterraforged.world.worldgen.densityfunction.");
        if (ftf && (simple.equals("CellSampler") || simple.equals("NoiseFunction")
                || name.endsWith("CellSampler$Marker") || name.endsWith("NoiseFunction$Marker"))) return HORIZONTAL;
        // 0023 caches only its XZ surface lookup; the returned depth still depends on Y.
        if (ftf && simple.equals("ClimateDepthFunction")) return new Info(true, false);
        if (name.equals("net.minecraft.world.level.levelgen.synth.BlendedNoise")) return new Info(true, false);
        if (vanilla && java.util.Set.of("Constant", "BlendAlpha", "BlendOffset", "EndIslandDensityFunction",
                "ShiftA", "ShiftB").contains(simple)) return HORIZONTAL;
        if (vanilla && simple.equals("BeardifierMarker")) return new Info(true, false);
        boolean wrapper = ftf && java.util.Set.of("ConditionalFlatCache", "ClampToNearestUnit", "LinearSplineFunction").contains(simple);
        if (!type.isRecord() || !(vanilla || wrapper || value instanceof NoiseRouter
                || name.startsWith("net.minecraft.util.CubicSpline$"))) return UNKNOWN;
        Info result = HORIZONTAL;
        for (var component : type.getRecordComponents()) {
            Class<?> field = component.getType();
            if (field.isPrimitive() || field.isEnum() || field == DensityFunction.NoiseHolder.class) continue;
            var read = component.getAccessor();
            read.setAccessible(true);
            result = result.plus(inspect(read.invoke(value), depth + 1));
        }
        if (wrapper && simple.equals("ConditionalFlatCache") && result.vertical) return UNKNOWN;
        if (vanilla && java.util.Set.of("YClampedGradient", "Shift", "WeirdScaledSampler", "BlendDensity").contains(simple))
            result = new Info(true, result.unsafe);
        if (vanilla && (simple.equals("Noise") || simple.equals("ShiftedNoise")) && PredictionRawDensity.yScale(value) != 0)
            result = new Info(true, result.unsafe);
        return result;
    }
}
