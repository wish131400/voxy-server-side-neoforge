package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL45C.*;

/** Normal Voxy must composite opaque terrain before prediction, and water afterwards. */
public final class PredictionNormalTranslucencyBridge {
    private static final HashMap<Class<?>, Access> ACCESS = new HashMap<>();
    private static Object completedViewport;
    private static int completedFramebuffer;
    private static boolean composingOpaque;
    private static boolean preparing;
    private static boolean failed;

    private PredictionNormalTranslucencyBridge() { }

    static void beginFrame() { completedViewport = null; }
    static boolean preparing() { return preparing; }
    static boolean rendered(int framebuffer) {
        return completedViewport != null && completedFramebuffer == framebuffer;
    }

    /** Called after SSAO, while Voxy's depth and colour contain only opaque surfaces. */
    public static void beforeTranslucent(Object pipeline, Object viewport, int framebuffer) {
        Minecraft mc = Minecraft.getInstance();
        if (failed || composingOpaque || completedViewport != null || !VSSClientConfig.CONFIG.enablePrediction
                || PredictionIrisBridge.shadersActive() || mc.level == null
                || mc.getMainRenderTarget().frameBufferId != framebuffer
                || Boolean.getBoolean("vss.disableNormalWaterOrdering")) return;
        try {
            Access access = ACCESS.get(pipeline.getClass());
            if (access == null) {
                access = new Access(pipeline.getClass(), viewport.getClass());
                ACCESS.put(pipeline.getClass(), access);
            }
            int width = access.width.getInt(viewport), height = access.height.getInt(viewport);
            if (width != mc.getMainRenderTarget().width || height != mc.getMainRenderTarget().height) return;
            Matrix4f projection = new Matrix4f((Matrix4f) access.projection.get(viewport));
            Matrix4f modelView = new Matrix4f((Matrix4f) access.modelView.get(viewport));
            Vec3 camera = new Vec3(access.x.getDouble(viewport), access.y.getDouble(viewport), access.z.getDouble(viewport));
            try (var state = new PredictionIrisBridge.State(PredictionExactCoverageMask.TEXTURE_UNITS, false);
                 var raster = new RasterState()) {
                // Reuse Voxy's own normal opaque colour/depth/fog conversion.
                // This call is not recursive: finish does not call postOpaquePreTranslucent.
                composingOpaque = true;
                try { access.finish.invoke(pipeline, viewport, framebuffer, width, height); }
                finally { composingOpaque = false; }
                // Unknown final shaders cannot protect a nearer prediction beyond vanilla's far plane.
                if (glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "VssPredictionBeforeWater") < 0) {
                    failed = true;
                    return;
                }
                glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
                preparing = true;
                try {
                    if (PredictionRenderer.renderBeforeVoxyWater(new PredictionRenderer.Frame(modelView, projection, camera))) {
                        completedViewport = viewport;
                        completedFramebuffer = framebuffer;
                    }
                } finally { preparing = false; }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failed = true;
            VSSLogger.warn("VSS normal water ordering unavailable; retaining the ordinary prediction pass", failure);
        }
    }

    /** Protect closer prediction even beyond Minecraft's saturated far-depth bin. Bindings are saved by the mixin. */
    public static void bindFinal(Object viewport, int framebuffer) {
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        int enabled = glGetUniformLocation(program, "VssPredictionBeforeWater");
        if (enabled < 0) return;
        boolean active = !composingOpaque && completedViewport == viewport && completedFramebuffer == framebuffer;
        glUniform1i(enabled, active ? 1 : 0);
        if (active) {
            glBindTextureUnit(4, PredictionRenderer.normalDepthTexture());
            glBindSampler(4, 0);
            glUniform1i(glGetUniformLocation(program, "VssPredictionOpaqueDepth"), 4);
            glBindTextureUnit(5, PredictionRenderer.normalSeedTexture());
            glBindSampler(5, 0);
            glUniform1i(glGetUniformLocation(program, "VssPredictionSeedDepth"), 5);
            glDepthFunc(GL_LEQUAL);
        }
    }

    private static final class Access {
        final Method finish;
        final Field projection, modelView, x, y, z, width, height;
        Access(Class<?> pipeline, Class<?> viewport) throws ReflectiveOperationException {
            Class<?> base = viewport;
            while (base != null && !base.getName().equals("me.cortex.voxy.client.core.rendering.Viewport")) base = base.getSuperclass();
            if (base == null) throw new NoSuchMethodException("Unknown Voxy viewport");
            finish = pipeline.getDeclaredMethod("finish", base, int.class, int.class, int.class);
            finish.setAccessible(true);
            projection = viewport.getField("vanillaProjection"); modelView = viewport.getField("modelView");
            x = viewport.getField("cameraX"); y = viewport.getField("cameraY"); z = viewport.getField("cameraZ");
            width = viewport.getField("width"); height = viewport.getField("height");
        }
    }

    /** Extra raster state touched by the normal target, outside Iris State's contract. */
    private static final class RasterState implements AutoCloseable {
        final int origin = glGetInteger(GL_CLIP_ORIGIN), mode = glGetInteger(GL_CLIP_DEPTH_MODE);
        final boolean stencil = glIsEnabled(GL_STENCIL_TEST);
        final int frontMask = glGetInteger(GL_STENCIL_WRITEMASK), backMask = glGetInteger(GL_STENCIL_BACK_WRITEMASK);
        final double clear = glGetDouble(GL_DEPTH_CLEAR_VALUE);
        final byte[] color = new byte[4];
        RasterState() {
            var values = org.lwjgl.BufferUtils.createByteBuffer(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, values); values.get(color);
        }
        @Override public void close() {
            glClipControl(origin, mode); glClearDepth(clear);
            if (stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
            glStencilMaskSeparate(GL_FRONT, frontMask); glStencilMaskSeparate(GL_BACK, backMask);
            PredictionGlState.colorMask(color[0] != 0, color[1] != 0, color[2] != 0, color[3] != 0);
        }
    }
}
