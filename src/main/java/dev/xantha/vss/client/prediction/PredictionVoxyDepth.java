package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Borrows normal Voxy's un-clamped depth for the remainder of the current frame. */
public final class PredictionVoxyDepth {
    private static Frame frame;
    private static boolean loggedFailure;
    private static final ConcurrentHashMap<AccessorKey, Accessor> ACCESSORS = new ConcurrentHashMap<>();
    private static AccessorKey lastKey;
    private static Accessor lastAccessor;

    private PredictionVoxyDepth() { }

    public static void capture(Object pipeline, Object viewport, int targetFramebuffer) {
        capture(pipeline, viewport, targetFramebuffer, null);
    }

    /** The normal pipeline supplies the actual raster convention; properties describe the projection only. */
    public static void capture(Object pipeline, Object viewport, int targetFramebuffer, Boolean rasterZeroToOne) {
        frame = null;
        if (!VSSClientConfig.CONFIG.enablePrediction || pipeline == null || viewport == null) return;
        AccessorKey key = lastKey;
        Accessor accessor = lastAccessor;
        if (key == null || key.pipeline() != pipeline.getClass() || key.viewport() != viewport.getClass()) {
            key = new AccessorKey(pipeline.getClass(), viewport.getClass());
            accessor = ACCESSORS.computeIfAbsent(key, PredictionVoxyDepth::resolve);
            lastKey = key;
            lastAccessor = accessor;
        }
        if (!accessor.supported()) { logFailure(accessor.failure()); return; }
        try {
            Object fb = accessor.fb().get(pipeline);
            if (fb == null) return;
            Object texture = accessor.depthTexture().invoke(fb);
            Matrix4f mvp = (Matrix4f) accessor.mvp().get(viewport);
            if (texture == null || mvp == null) return;
            boolean zeroToOne = false, reverseZ = false;
            if (accessor.properties() != null) {
                Object properties = accessor.properties().get(pipeline);
                if (properties == null) return;
                zeroToOne = (boolean) accessor.zeroToOne().invoke(properties);
                reverseZ = (boolean) accessor.reverseZ().invoke(properties);
            }
            if (rasterZeroToOne != null) zeroToOne = rasterZeroToOne;
            frame = new Frame(accessor.textureId().getInt(texture), targetFramebuffer,
                    accessor.width().getInt(viewport), accessor.height().getInt(viewport),
                    new Vec3(accessor.cameraX().getDouble(viewport), accessor.cameraY().getDouble(viewport),
                            accessor.cameraZ().getDouble(viewport)),
                    new Matrix4f(mvp).invert(), zeroToOne, reverseZ);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // A structurally incompatible API must not throw again every frame.
            accessor = Accessor.unsupported(failure);
            ACCESSORS.put(key, accessor);
            lastAccessor = accessor;
            logFailure(failure);
        }
    }

    private static Accessor resolve(AccessorKey key) {
        try {
            Field fb = key.pipeline().getField("fb");
            Method depthTexture = fb.getType().getMethod("getDepthTex");
            Field textureId = depthTexture.getReturnType().getField("id");
            Field properties = null;
            Method zeroToOne = null, reverseZ = null;
            try {
                properties = key.pipeline().getField("properties");
                zeroToOne = properties.getType().getMethod("isZero2One");
                reverseZ = properties.getType().getMethod("isReverseZ");
            } catch (NoSuchFieldException missing) {
                // Voxy's legacy normal pipeline uses [-1,1] clip depth and
                // increasing Z. Only the verified legacy class layout opts in.
                if (!key.pipeline().getName().equals("me.cortex.voxy.client.core.NormalRenderPipeline")
                        || !fb.getType().getName().equals("me.cortex.voxy.client.core.rendering.util.DepthFramebuffer")) {
                    throw missing;
                }
            }
            Class<?> viewport = key.viewport();
            return new Accessor(true, null, fb, properties, depthTexture, textureId, zeroToOne, reverseZ,
                    viewport.getField("width"), viewport.getField("height"), viewport.getField("cameraX"),
                    viewport.getField("cameraY"), viewport.getField("cameraZ"), viewport.getField("MVP"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return Accessor.unsupported(failure);
        }
    }

    private static void logFailure(Throwable failure) {
        if (!loggedFailure && VSSClientConfig.CONFIG.debugLogging) {
            loggedFailure = true;
            VSSLogger.debug("VSS normal Voxy depth unavailable: " + failure);
        }
    }

    static void clear() { frame = null; }

    static void clearAccessorCache() {
        clear(); ACCESSORS.clear(); lastKey = null; lastAccessor = null; loggedFailure = false;
    }

    static Frame current(int framebuffer, int width, int height, Vec3 camera) {
        Frame value = frame;
        return value != null && value.texture() > 0 && value.framebuffer() == framebuffer
                && value.width() == width && value.height() == height
                && value.camera().distanceToSqr(camera) < 1e-8 ? value : null;
    }

    record Frame(int texture, int framebuffer, int width, int height, Vec3 camera, Matrix4f inverseMvp,
                 boolean zeroToOne, boolean reverseZ) {
        Frame(int texture, int framebuffer, int width, int height, Vec3 camera, Matrix4f inverseMvp) {
            this(texture, framebuffer, width, height, camera, inverseMvp, false, false);
        }
    }

    private record AccessorKey(Class<?> pipeline, Class<?> viewport) { }
    private record Accessor(boolean supported, Throwable failure, Field fb, Field properties,
                            Method depthTexture, Field textureId, Method zeroToOne, Method reverseZ,
                            Field width, Field height, Field cameraX, Field cameraY, Field cameraZ, Field mvp) {
        static Accessor unsupported(Throwable failure) {
            return new Accessor(false, failure, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }
}
