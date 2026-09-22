package dev.xantha.vss.client.prediction;

import java.lang.reflect.RecordComponent;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Construction-time analysis of raw density queries; never uses NoiseChunk marker semantics. */
final class PredictionRawDensity {
    record Info(boolean pure, boolean horizontal, boolean stateful) {
        Info and(Info other) {
            return new Info(pure && other.pure, horizontal && other.horizontal, stateful || other.stateful);
        }
    }
    static final Info HORIZONTAL = new Info(true, true, false);
    static final Info VERTICAL = new Info(true, false, false);
    static final Info UNKNOWN = new Info(false, false, false);
    private static final Set<String> RECORDS = Set.of("Constant", "Ap2", "Clamp", "Mapped", "MulOrAdd",
            "RangeChoice", "Marker", "HolderHolder", "Spline", "Coordinate", "YClampedGradient",
            "Noise", "ShiftedNoise", "Shift", "ShiftA", "ShiftB", "WeirdScaledSampler", "BlendDensity");
    private final IdentityHashMap<Object, Info> seen = new IdentityHashMap<>();
    private int remaining = 65536;

    /** Record declaration order survives Forge's SRG member renaming. */
    static double yScale(Object noise) throws ReflectiveOperationException {
        var type = noise.getClass();
        var parts = type.getRecordComponents();
        int index = switch (type.getSimpleName()) {
            case "Noise" -> 2;
            case "ShiftedNoise" -> 4;
            default -> throw new NoSuchFieldException("Not a vanilla noise record");
        };
        if (parts == null || parts.length <= index || parts[index].getType() != double.class)
            throw new NoSuchFieldException("Unknown vanilla noise record layout");
        var read = parts[index].getAccessor();
        read.setAccessible(true);
        return ((Number) read.invoke(noise)).doubleValue();
    }

    Info inspect(Object value) {
        try { return inspect(value, 0); }
        catch (ReflectiveOperationException | RuntimeException unsupported) { return UNKNOWN; }
    }

    private Info inspect(Object value, int depth) throws ReflectiveOperationException {
        if (value == null || value instanceof Number || value instanceof String) return HORIZONTAL;
        if (value instanceof DensityMemo.NonMemoizable) return new Info(false, false, true);
        if (value instanceof DensityMemo.Memoized memo) return memo.info;
        if (value instanceof Enum<?> && !(value instanceof DensityFunction)) return HORIZONTAL;
        Info cached = seen.get(value);
        if (cached != null) return cached;
        if (depth > 128 || --remaining < 0) return UNKNOWN;
        seen.put(value, UNKNOWN);
        Info result;
        if (value instanceof Holder<?> holder) result = inspect(holder.value(), depth + 1);
        else if (value instanceof Iterable<?> list) {
            result = HORIZONTAL;
            for (Object child : list) result = result.and(inspect(child, depth + 1));
        } else {
            Class<?> type = value.getClass();
            String name = type.getName(), simple = type.getSimpleName();
            boolean vanilla = name.startsWith("net.minecraft.world.level.levelgen.DensityFunctions$");
            if (name.equals("net.minecraft.world.level.levelgen.synth.BlendedNoise")) result = VERTICAL;
            else if (vanilla && Set.of("EndIslandDensityFunction", "BlendAlpha", "BlendOffset", "BeardifierMarker").contains(simple))
                result = HORIZONTAL;
            else if (type.isRecord() && (vanilla && RECORDS.contains(simple)
                    || name.equals("net.minecraft.util.CubicSpline$Constant")
                    || name.equals("net.minecraft.util.CubicSpline$Multipoint"))) {
                result = HORIZONTAL;
                for (RecordComponent part : type.getRecordComponents()) {
                    Class<?> t = part.getType();
                    if (t.isPrimitive() || t.isEnum() || t == float[].class
                            || t == DensityFunction.NoiseHolder.class) continue;
                    var accessor = part.getAccessor();
                    accessor.setAccessible(true);
                    result = result.and(inspect(accessor.invoke(value), depth + 1));
                }
                if (Set.of("YClampedGradient", "Shift", "WeirdScaledSampler", "BlendDensity").contains(simple))
                    result = result.and(VERTICAL);
                if (simple.equals("Noise") || simple.equals("ShiftedNoise")) {
                    if (yScale(value) != 0) result = result.and(VERTICAL);
                }
                // Raw FlatCache / Cache2D delegate to their children. They are
                // horizontal only if the children actually are independent of Y.
            } else result = UNKNOWN;
        }
        seen.put(value, result);
        return result;
    }
}
