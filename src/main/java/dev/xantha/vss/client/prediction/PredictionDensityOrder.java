package dev.xantha.vss.client.prediction;

import java.lang.reflect.RecordComponent;
import java.util.IdentityHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

/** Proves when partial-height queries may change the vanilla column traversal. */
final class PredictionDensityOrder {
    private record Info(boolean vertical, boolean ordered, boolean unknown) {
        Info plus(Info b) { return new Info(vertical || b.vertical, ordered || b.ordered, unknown || b.unknown); }
    }
    private static final Info PURE = new Info(false,false,false), UNKNOWN = new Info(true,true,true);
    private final IdentityHashMap<Object,Info> seen = new IdentityHashMap<>();
    private int remaining = 32768;

    static boolean requiresColumnOrder(NoiseRouter router) {
        try { return new PredictionDensityOrder().inspect(router,0).ordered; }
        catch (ReflectiveOperationException | RuntimeException unsupported) { return true; }
    }

    private Info inspect(Object value, int depth) throws ReflectiveOperationException {
        if (value == null || value instanceof Number || value instanceof String
                || value instanceof Enum<?> && !(value instanceof DensityFunction)) return PURE;
        if (depth > 128 || --remaining < 0) return UNKNOWN;
        Info cached=seen.get(value); if(cached!=null)return cached;
        seen.put(value,UNKNOWN); // Cycles and opaque mod nodes must never be assumed pure.
        Info result;
        if(value instanceof Holder<?> holder) result=inspect(holder.value(),depth+1);
        else if(value instanceof Iterable<?> list) {
            result=PURE;for(Object child:list)result=result.plus(inspect(child,depth+1));
        } else if(value instanceof DensityFunctions.MarkerOrMarked marker) {
            Info child=inspect(marker.wrapped(),depth+1);
            String kind=((Enum<?>)marker.type()).name();
            result=switch(kind) {
                case "FlatCache" -> new Info(false,child.unknown,child.unknown);
                case "Cache2D" -> new Info(child.vertical,child.ordered||child.vertical,child.unknown);
                default -> child;
            };
        } else {
            Class<?> type=value.getClass(); String name=type.getName(), simple=type.getSimpleName();
            boolean vanilla=name.startsWith("net.minecraft.world.level.levelgen.DensityFunctions$");
            if(value instanceof DensityFunction && !vanilla) {
                // BlendedNoise is a pure positional leaf. Compiled/modded graphs
                // are opaque here and retain the original iterator order.
                result=name.equals("net.minecraft.world.level.levelgen.synth.BlendedNoise")
                        ? new Info(true,false,false) : UNKNOWN;
            } else if(vanilla && java.util.Set.of("Constant","BlendAlpha","BlendOffset","EndIslandDensityFunction","ShiftA","ShiftB").contains(simple)) {
                result=PURE;
            } else if(type.isRecord() && (vanilla || value instanceof NoiseRouter
                    || name.startsWith("net.minecraft.util.CubicSpline$"))) {
                result=PURE;
                for(RecordComponent component:type.getRecordComponents()) {
                    Class<?> t=component.getType();
                    if(t.isPrimitive() || t.isArray() || t.isEnum() || t==DensityFunction.NoiseHolder.class) continue;
                    var read=component.getAccessor(); read.setAccessible(true);
                    result=result.plus(inspect(read.invoke(value),depth+1));
                }
                if(vanilla && java.util.Set.of("YClampedGradient","Shift","WeirdScaledSampler","BlendDensity").contains(simple))
                    result=new Info(true,result.ordered,result.unknown);
                if(vanilla && (simple.equals("Noise") || simple.equals("ShiftedNoise"))) {
                    if(PredictionRawDensity.yScale(value)!=0)result=new Info(true,result.ordered,result.unknown);
                }
            } else if(vanilla && simple.equals("BeardifierMarker")) result=new Info(true,false,false);
            else result=UNKNOWN;
        }
        seen.put(value,result);return result;
    }
}
