package dev.xantha.vss.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

/**
 * Reflective bridge to Roxy's bytecode patch of Voxy's RenderGenerationService.
 * Roxy (NeoForge loader layer for Voxy, >= 0.2.0) rewrites the result publication
 * inside processJob from Consumer.accept(mesh) to a static
 * RoxyVoxyRenderPatch.acceptIfCurrent(consumer, mesh) call. Resolved lazily so the
 * class loads fine when Roxy is absent.
 */
public final class RoxyRenderPatchBridge {
    private static final MethodHandle ACCEPT_IF_CURRENT = resolve();

    private static MethodHandle resolve() {
        try {
            Class<?> patch = Class.forName("net.rasanovum.roxy.patch.RoxyVoxyRenderPatch");
            return MethodHandles.publicLookup().findStatic(patch, "acceptIfCurrent",
                    MethodType.methodType(void.class, Consumer.class, Object.class));
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean present() {
        return ACCEPT_IF_CURRENT != null;
    }

    public static void acceptIfCurrent(Consumer<Object> consumer, Object mesh) {
        try {
            ACCEPT_IF_CURRENT.invokeExact(consumer, mesh);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to delegate to RoxyVoxyRenderPatch.acceptIfCurrent", t);
        }
    }

    private RoxyRenderPatchBridge() {}
}
