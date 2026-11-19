package dev.xantha.vss.client.prediction;

import dev.xantha.vss.mixin.client.SurfaceRulesContextAccessor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

/** Non-loading UTF cell queries for the already approximate surface-preview stage. */
public final class FreeTerraForgedSurfaceRules {
    private static final ThreadLocal<Column> ACTIVE = new ThreadLocal<>();
    private static final ClassValue<MethodHandle> PROVIDE = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                return MethodHandles.publicLookup().unreflect(type.getMethod("provideChunk", int.class, int.class))
                        .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("UTF surface chunk API unavailable", e); }
        }
    };
    private static final ClassValue<MethodHandle> TEST = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                Class<?> base = type;
                while (!base.getSimpleName().equals("CellCondition")) base = base.getSuperclass();
                Class<?> cell = base.getClassLoader().loadClass(
                        "com.pandaismyname1.ultraterraforged.world.worldgen.cell.Cell");
                var method = base.getDeclaredMethod("test", cell, int.class, int.class);
                method.setAccessible(true);
                return MethodHandles.lookup().unreflect(method).asType(
                        MethodType.methodType(boolean.class, Object.class, Object.class, int.class, int.class));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("UTF surface condition API unavailable", e); }
        }
    };

    private final Object cache;
    private final MethodHandle create, reset, apply, height, scale;

    FreeTerraForgedSurfaceRules(Object context) throws ReflectiveOperationException {
        var type = context.getClass();
        cache = type.getField("cache").get(context);
        Object lookup = type.getField("lookup").get(context);
        Object levels = type.getField("levels").get(context);
        Class<?> cell = type.getClassLoader().loadClass(
                "com.pandaismyname1.ultraterraforged.world.worldgen.cell.Cell");
        var handles = MethodHandles.publicLookup();
        create = handles.unreflectConstructor(cell.getConstructor()).asType(MethodType.methodType(Object.class));
        reset = handles.unreflect(cell.getMethod("reset")).asType(MethodType.methodType(Object.class, Object.class));
        // The overload without load=true uses an existing filtered tile, or the
        // mod's own point sampler. It never generates a missing full tile.
        apply = handles.unreflect(lookup.getClass().getMethod("apply", cell, int.class, int.class)).bindTo(lookup)
                .asType(MethodType.methodType(boolean.class, Object.class, int.class, int.class));
        height = handles.unreflectGetter(cell.getField("height"))
                .asType(MethodType.methodType(float.class, Object.class));
        scale = handles.unreflect(levels.getClass().getMethod("scale", float.class)).bindTo(levels);
    }

    <T> T preview(Supplier<T> action) {
        Column previous = ACTIVE.get();
        ACTIVE.set(new Column(this));
        try { return action.get(); }
        finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    public static boolean pointRules(Object cache) {
        Column column = ACTIVE.get();
        return column != null && column.owner.cache == cache;
    }

    public static Object chunk(Object cache, int x, int z) {
        try { return (Object) PROVIDE.get(cache.getClass()).invokeExact(cache, x, z); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException("UTF surface chunk query failed", e); }
    }

    public static boolean nearGround(Object context, int depth) {
        var position = (SurfaceRulesContextAccessor) context;
        Column column = column();
        try {
            Object cell = column.cell(position.vss$blockX(), position.vss$blockZ());
            float height = (float) column.owner.height.invokeExact(cell);
            int ground = (int) column.owner.scale.invokeExact(height);
            int y = position.vss$blockY();
            return y <= ground + 1 && y >= ground - depth;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException("UTF preview ground query failed", e); }
    }

    public static boolean test(Object condition, Object context) {
        var position = (SurfaceRulesContextAccessor) context;
        int x = position.vss$blockX(), z = position.vss$blockZ();
        try {
            Object cell = column().cell(x, z);
            return (boolean) TEST.get(condition.getClass()).invokeExact(condition, cell, x, z);
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException("UTF preview surface condition failed", e); }
    }

    private static Column column() {
        Column value = ACTIVE.get();
        if (value == null) throw new IllegalStateException("UTF preview rule used outside its sampling scope");
        return value;
    }

    private static final class Column {
        final FreeTerraForgedSurfaceRules owner;
        Object cell;
        int x, z;
        boolean valid;
        Column(FreeTerraForgedSurfaceRules owner) { this.owner = owner; }
        Object cell(int x, int z) throws Throwable {
            if (!valid || this.x != x || this.z != z) {
                if (cell == null) cell = (Object) owner.create.invokeExact();
                cell = (Object) owner.reset.invokeExact(cell);
                boolean cached = (boolean) owner.apply.invokeExact(cell, x, z);
                this.x = x; this.z = z; valid = true;
            }
            return cell;
        }
    }
}
