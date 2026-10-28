package dev.xantha.vss.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** Both Voxy key layouts. Ordinary sections retain the original bit-exact fast path. */
public final class VoxySectionPosition {
    private static volatile MethodHandle extendedEncoder;
    private VoxySectionPosition() { }

    public static void bind(Class<?> engine) throws ReflectiveOperationException {
        var method = engine.getMethod("getWorldSectionId", int.class, int.class, int.class, int.class);
        long probe = ((Number) method.invoke(null, 0, 0, 128, 0)).longValue();
        extendedEncoder = (probe & 1L) != 0 ? MethodHandles.publicLookup().unreflect(method) : null;
    }

    public static boolean extended() { return extendedEncoder != null; }
    public static int yPeriod() { return extended() ? 4096 : 256; }
    public static long key(int lod, int x, int y, int z) {
        MethodHandle encoder = extendedEncoder;
        if (encoder != null && (y < -128 || y > 127)) {
            try { return (long) encoder.invokeExact(lod, x, y, z); }
            catch (Throwable failure) { throw new IllegalArgumentException("Voxy section coordinates", failure); }
        }
        return (long) lod << 60 | (long) (y & 255) << 52
                | (long) (z & 0xFFFFFF) << 28 | (long) (x & 0xFFFFFF) << 4;
    }
    public static int level(long key) { return (int) (key >>> 60); }
    public static int x(long key) { return (key & 1L) == 0 ? (int) (key << 36 >> 40) : (int) (key << 40 >> 41); }
    public static int y(long key) { return (key & 1L) == 0 ? (int) (key << 4 >> 56) : (int) (key << 4 >> 52); }
    public static int z(long key) { return (key & 1L) == 0 ? (int) (key << 12 >> 40) : (int) (key << 16 >> 40); }
}
