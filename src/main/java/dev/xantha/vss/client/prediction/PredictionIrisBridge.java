package dev.xantha.vss.client.prediction;

import java.lang.reflect.Method;
import java.util.Optional;

/** Shader-pack detection for frame capture. Geometry is rendered by Voxy's own Iris pipeline. */
public final class PredictionIrisBridge {
    private PredictionIrisBridge() { }
    private static Method currentPack;
    private static boolean irisLookedUp;
    public static boolean shadersActive() {
        try {
            if (!irisLookedUp) {
                irisLookedUp = true;
                try { currentPack = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack"); }
                catch (ClassNotFoundException absent) { return false; }
            }
            return currentPack != null && ((Optional<?>) currentPack.invoke(null)).isPresent();
        } catch (ReflectiveOperationException failure) {
            // Unknown Iris API must not draw vanilla output into a shader pack.
            return true;
        }
    }
}
