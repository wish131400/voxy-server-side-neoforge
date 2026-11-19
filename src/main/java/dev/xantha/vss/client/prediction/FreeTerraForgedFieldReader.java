package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Resolves a release's field contract once, before worker threads sample cells. */
final class FreeTerraForgedFieldReader {
    final Object heightmap;
    private final MethodHandle read;

    FreeTerraForgedFieldReader(Object context, Class<?> fieldType, boolean tiled)
            throws ReflectiveOperationException {
        var variant = FreeTerraForgedVariant.fromClass(context.getClass());
        Class<?> cell = context.getClass().getClassLoader().loadClass(variant.worldgenClass("cell.Cell"));
        var handles = MethodHandles.publicLookup();
        if (variant.singleArgumentCellFields()) {
            // UTF's fields already contain their final value. Its mutable,
            // thread-local Heightmap must not be captured on this thread.
            heightmap = null;
            read = MethodHandles.dropArguments(handles.unreflect(fieldType.getMethod("read", cell)), 2,
                    Object.class).asType(MethodType.methodType(float.class, Object.class, Object.class, Object.class));
        } else {
            Object lookup = context.getClass().getField("lookup").get(context);
            heightmap = lookup.getClass().getMethod("getHeightmap").invoke(lookup);
            read = handles.unreflect(fieldType.getMethod(tiled ? "read" : "readFinite", cell, heightmap.getClass()))
                    .asType(MethodType.methodType(float.class, Object.class, Object.class, Object.class));
        }
    }

    MethodHandle bind(Object field) { return read.bindTo(field); }
}
