package dev.xantha.vss.compat;

import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The small ABI differences between the maintained Voxy render backends. */
public final class VoxyRenderAccess {
    private VoxyRenderAccess() { }

    private record Backlog(java.lang.reflect.Field service, Method count) { }
    private static final ClassValue<java.util.Optional<Backlog>> BACKLOGS = new ClassValue<>() {
        @Override protected java.util.Optional<Backlog> computeValue(Class<?> renderer) {
            try {
                var service = renderer.getDeclaredField("renderGen"); service.setAccessible(true);
                return java.util.Optional.of(new Backlog(service, service.getType().getMethod("getTaskCount")));
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return java.util.Optional.empty(); }
        }
    };
    /** Optional telemetry only: community renderers without this accessor keep ordinary delivery. */
    static int meshBacklog(Object renderer) {
        if (renderer == null) return -1;
        var binding = BACKLOGS.get(renderer.getClass());
        if (binding.isEmpty()) return -1;
        try { return ((Number) binding.get().count.invoke(binding.get().service.get(renderer))).intValue(); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return -1; }
    }

    /** Voxy distance is in 512-block sections; VSS requests use 16-block chunks. */
    static float renderDistance(float configuredSections, int serverChunks, int predictionBlocks) {
        return Math.max(configuredSections, Math.max((float) Math.ceil(Math.max(0, serverChunks) / 32.0),
                (float) Math.ceil(Math.max(0, predictionBlocks) / 512.0)));
    }

    static float farPlane(float original, int distanceChunks) {
        // Voxy loads horizontal rings of top-level sections. Include their corners and vertical extent.
        return Math.max(original, (float) ((Math.max(0, distanceChunks) * 16.0 + 512.0) * Math.sqrt(3.0)));
    }

    public static MethodHandle distanceField(Class<?> config) throws ReflectiveOperationException {
        return MethodHandles.publicLookup().unreflectGetter(config.getField("sectionRenderDistance"))
                .asType(MethodType.methodType(float.class, Object.class));
    }

    public record Distance(Method setter, boolean integral) {
        public static Distance bind(Class<?> renderer) throws NoSuchMethodException {
            try { return new Distance(renderer.getMethod("setRenderDistance", float.class), false); }
            catch (NoSuchMethodException legacy) {
                return new Distance(renderer.getMethod("setRenderDistance", int.class), true);
            }
        }
        public void set(Object renderer, float sections) throws ReflectiveOperationException {
            // Keep the boxed primitive types distinct; reflection does not narrow Float to int.
            if (integral) setter.invoke(renderer, (int) Math.ceil(sections));
            else setter.invoke(renderer, sections);
        }
    }

    private record Withdrawal(Method empty, Method submit) { }
    private static final ClassValue<Withdrawal> WITHDRAWALS = new ClassValue<>() {
        @Override protected Withdrawal computeValue(Class<?> nodes) {
            try {
                Method submit = java.util.Arrays.stream(nodes.getDeclaredMethods())
                        .filter(m -> m.getName().equals("submitGeometryResult") && m.getParameterCount() == 1)
                        .findFirst().orElseThrow(NoSuchMethodException::new);
                submit.setAccessible(true);
                return new Withdrawal(submit.getParameterTypes()[0].getMethod("empty", long.class), submit);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported Voxy geometry withdrawal", e); }
        }
    };

    /** Only used after checking current native data has neither blocks nor occupied children. */
    static void withdraw(Object nodes, long position) throws ReflectiveOperationException {
        var methods = WITHDRAWALS.get(nodes.getClass());
        methods.submit.invoke(nodes, methods.empty.invoke(null, position));
    }

    /** A uniform native section can be inspected without allocating a 256 KiB array. */
    public record Voxels(long[] data, long uniform) {
        public long get(int index) { return data == null ? uniform : data[index]; }
    }

    public static final class Sections {
        private final Method raw, uniform, writable;
        public Sections(Class<?> section) throws NoSuchMethodException {
            Method nullable;
            try { nullable = section.getMethod("_rawOrNull"); }
            catch (NoSuchMethodException legacy) { nullable = null; }
            if (nullable != null) {
                raw = nullable;
                uniform = section.getMethod("getUniformValue");
                writable = section.getMethod("materialize");
            } else {
                raw = section.getMethod("_unsafeGetRawDataArray");
                uniform = null;
                writable = raw;
            }
        }
        public Voxels read(Object section) throws ReflectiveOperationException {
            long[] data = (long[]) raw.invoke(section);
            return new Voxels(data, data == null && uniform != null
                    ? ((Number) uniform.invoke(section)).longValue() : 0L);
        }
        public long[] writable(Object section) throws ReflectiveOperationException {
            return (long[]) writable.invoke(section);
        }
    }
}
