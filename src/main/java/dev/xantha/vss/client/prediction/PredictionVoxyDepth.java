package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Borrows normal Voxy's un-clamped depth for the remainder of the current frame. */
public final class PredictionVoxyDepth {
    private static Frame frame;
    private static boolean loggedFailure;

    private PredictionVoxyDepth() { }

    public static void capture(Object pipeline, Object viewport, int targetFramebuffer) {
        if (!VSSClientConfig.CONFIG.enablePrediction) return;
        try {
            Object fb = pipeline.getClass().getField("fb").get(pipeline);
            Object texture = fb.getClass().getMethod("getDepthTex").invoke(fb);
            Class<?> type = viewport.getClass();
            frame = new Frame((int) texture.getClass().getField("id").get(texture), targetFramebuffer,
                    type.getField("width").getInt(viewport), type.getField("height").getInt(viewport),
                    new Vec3(type.getField("cameraX").getDouble(viewport),
                            type.getField("cameraY").getDouble(viewport), type.getField("cameraZ").getDouble(viewport)),
                    new Matrix4f((Matrix4f) type.getField("MVP").get(viewport)).invert());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            frame = null;
            if (!loggedFailure && VSSClientConfig.CONFIG.debugLogging) {
                loggedFailure = true;
                VSSLogger.debug("VSS normal Voxy depth unavailable: " + failure);
            }
        }
    }

    static void clear() { frame = null; }

    static Frame current(int framebuffer, int width, int height, Vec3 camera) {
        Frame value = frame;
        return value != null && value.texture() > 0 && value.framebuffer() == framebuffer
                && value.width() == width && value.height() == height
                && value.camera().distanceToSqr(camera) < 1e-8 ? value : null;
    }

    record Frame(int texture, int framebuffer, int width, int height, Vec3 camera, Matrix4f inverseMvp) { }
}
