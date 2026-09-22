package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** Consumes the active Voxy shader adapter; never substitutes vanilla colour/depth targets. */
public final class PredictionIrisBridge {
    private static final Map<Object, Bridge> BRIDGES = new IdentityHashMap<>();
    private static Method currentPack;
    private static boolean irisLookedUp;
    // Render-thread only. setupAndBindTranslucent runs BEFORE Voxy's draw,
    // including deferred packs; keep its exact pipeline/view for the draw tail.
    private static Object translucentPipeline;
    private static Object translucentViewport;

    public static void prepareTranslucent(Object pipeline, Object viewport) {
        translucentPipeline = pipeline;
        translucentViewport = viewport;
    }

    public static void finishTranslucent(Object viewport) {
        Object pipeline = translucentPipeline;
        boolean matches = translucentViewport == viewport;
        translucentPipeline = null;
        translucentViewport = null;
        if (pipeline != null && matches) render(pipeline, viewport, true);
    }

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

    public static void render(Object pipeline, Object viewport, boolean translucent) {
        if (!VSSClientConfig.CONFIG.enablePrediction) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null
                || !ClientPredictionState.hasReadyTiles(minecraft.level.dimension())) return;
        if (!PredictionGraphicsSupport.available(true)) return;
        Bridge bridge = BRIDGES.computeIfAbsent(pipeline, Bridge::new);
        if (bridge.failed) return;
        try (State state = new State(bridge.textureUnits())) {
            bridge.draw(viewport, translucent);
            int passBit = translucent ? 2 : 1;
            if ((bridge.reportedPasses & passBit) == 0) {
                bridge.reportedPasses |= passBit;
                VSSLogger.info("VSS prediction Iris callback completed: pass="
                        + (translucent ? "translucent" : "opaque")
                        + ", program=" + ((translucent ? bridge.water : bridge.opaque) != null)
                        + ", viewport=" + bridge.width + "x" + bridge.height);
            }
        } catch (Throwable failure) {
            bridge.failed = true;
            VSSLogger.warn("VSS prediction disabled for this Voxy/Iris pipeline; real LOD remains enabled", failure);
        }
    }

    static void beginFrame() {
        translucentPipeline = null;
        translucentViewport = null;
        for (Bridge bridge : BRIDGES.values()) {
            bridge.framePlan.clear(); bridge.depthView.clear();
        }
    }

    public static void release(Object pipeline) {
        Bridge bridge = BRIDGES.remove(pipeline);
        if (bridge != null) bridge.close();
    }

    record Pass(PredictionTerrainProgram program, int depthTexture, int depthFunc,
                int width, int height, boolean zeroToOne, boolean translucent,
                int[] materialIds, IntConsumer bindImages) {
        void bindFrame(PredictionRenderer.Frame frame) {
            program.setIrisFrame(new Matrix4f(frame.projection()).invert(), width, height, zeroToOne, materialIds,
                    depthFunc == GL11.GL_GEQUAL || depthFunc == GL11.GL_GREATER ? 0.0F : 1.0F);
            if (bindImages != null) bindImages.accept(PredictionExactCoverageMask.TEXTURE_UNITS);
            // Voxy installs sampler objects. Our atlas and metadata require their
            // own texture filtering; unbind those overrides on the VSS units.
            for (int unit = 0; unit < PredictionExactCoverageMask.TEXTURE_UNITS; unit++) GL33.glBindSampler(unit, 0);
        }
    }

    private static final class Bridge implements AutoCloseable {
        final Object pipeline;
        boolean failed;
        int reportedPasses;
        PredictionTerrainProgram opaque;
        PredictionTerrainProgram water;
        int depthCopy;
        int width;
        int height;
        int format;
        final PredictionFramePlan<Boolean> depthView = new PredictionFramePlan<>();
        PredictionTileManager.RenderSnapshot meshSnapshot;
        final PredictionFramePlan<PredictionRenderer.PreparedFrame> framePlan = new PredictionFramePlan<>();
        int cachedTextureUnits;
        long materialRevision = Long.MIN_VALUE;
        int[] materialIds = new int[256];
        Object data;
        IntConsumer imageBindings;
        it.unimi.dsi.fastutil.objects.Object2IntMap<BlockState> blockIds;

        Bridge(Object pipeline) { this.pipeline = pipeline; }

        int textureUnits() throws ReflectiveOperationException {
            if (cachedTextureUnits != 0) return cachedTextureUnits;
            Object images = call(field(pipeline, "data"), "getImageSet");
            int count = images == null ? 0 : (int) ((String) call(images, "layout")).lines()
                    .filter(line -> line.contains("BASE_SAMPLER_BINDING_INDEX+")).count();
            int units = PredictionExactCoverageMask.TEXTURE_UNITS + count;
            if (units > GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS)) {
                throw new IllegalStateException("Shader pack requires too many texture units for prediction: " + units);
            }
            return cachedTextureUnits = units;
        }

        @SuppressWarnings("unchecked")
        void initialize() throws ReflectiveOperationException {
            data = field(pipeline, "data");
            Object images = call(data, "getImageSet");
            if (images != null) imageBindings = (IntConsumer) call(images, "bindingFunction");
            Object taaBody = field(data, "TAA");
            String taa = taaBody == null ? "vec2 vssTaaShift() { return vec2(0.0); }"
                    : (String) call(pipeline, "taaFunction", "vssTaaShift");
            opaque = PredictionTerrainProgram.createIris(taa, source -> patch("patchOpaqueShader", source));
            String translucent = (String) call(data, "translucentFragPatch");
            if (translucent != null) {
                water = PredictionTerrainProgram.createIris(taa, source -> patch("patchTranslucentShader", source));
            }
            Class<?> settingsClass = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
            Object settings = settingsClass.getField("INSTANCE").get(null);
            blockIds = (it.unimi.dsi.fastutil.objects.Object2IntMap<BlockState>) call(settings, "getBlockStateIds");
        }

        String patch(String name, String source) {
            try {
                String patched = (String) call(pipeline, name, null, source);
                // Voxy reserves units 6+ for pack inputs; prediction has nine
                // local textures. Move the whole pack sampler set together.
                return patched.replace("#define BASE_SAMPLER_BINDING_INDEX 6", "#define BASE_SAMPLER_BINDING_INDEX " + PredictionExactCoverageMask.TEXTURE_UNITS);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Voxy shader adapter API unavailable", failure);
            }
        }

        void draw(Object viewport, boolean translucent) throws ReflectiveOperationException {
            if (opaque == null) initialize();
            PredictionTerrainProgram program = translucent ? water : opaque;
            if (program == null) return;
            // Opaque hook runs after every Voxy opaque/temporal draw and before
            // its depth copy. The translucent hook also runs on deferred packs.
            if (!translucent) call(pipeline, "setupAndBindOpaque", viewport);
            Object framebuffer = field(pipeline, translucent ? "fbTranslucent" : "fb");
            if (PredictionRenderCapture.active()) {
                PredictionRenderCapture.prediction(translucent ? "water-before" : "opaque-before",
                        GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
            }
            int texture = (int) field(call(framebuffer, "getDepthTex"), "id");
            int w = (int) field(viewport, "width");
            int h = (int) field(viewport, "height");
            int frameId = (int) field(viewport, "frameId");
            PredictionRenderer.Frame frame = new PredictionRenderer.Frame(
                    new Matrix4f((Matrix4f) field(viewport, "modelView")),
                    new Matrix4f((Matrix4f) field(viewport, "projection")),
                    new Vec3((double) field(viewport, "cameraX"), (double) field(viewport, "cameraY"),
                            (double) field(viewport, "cameraZ")));
            var level = Minecraft.getInstance().level;
            boolean newView = depthView.get(viewport, level, null, 0, 0, frameId, w, h, frame) == null;
            if (!translucent || newView) {
                framePlan.clear();
                meshSnapshot = level == null ? null : ClientPredictionState.renderSnapshot(level.dimension());
            }
            // Opaque ownership uses real opaque depth. The second pass must
            // see completed Voxy ice/water too, or coincident translucent faces
            // blend twice. Reuse the same copy texture and prepared mesh plan.
            // Fluid shader tolerance is 0.02 blocks (not terrain's broad band),
            // so distinct shallow beds, including our own, remain below water.
            {
                long copyStart = PredictionRenderTimings.start();
                int query = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.DEPTH_COPY);
                try { snapshotDepth(texture, w, h); }
                finally {
                    PredictionRenderTimings.gpuEnd(query);
                    PredictionRenderTimings.end(PredictionRenderTimings.Stage.DEPTH_COPY, copyStart);
                }
                depthView.put(viewport, level, null, 0, 0, frameId, w, h, frame, true);
            }
            long revision = VssLodSpriteTable.materialRevision();
            if (materialRevision != revision) {
                int[] ids = new int[256];
                int[] blocks = VssLodSpriteTable.materialBlocks();
                if (blockIds != null) for (int row = 0; row < blocks.length; row++) {
                    if (blocks[row] >= 0) ids[row] = Math.max(0,
                            blockIds.getInt(BuiltInRegistries.BLOCK.byId(blocks[row]).defaultBlockState()));
                }
                materialIds = ids;
                materialRevision = revision;
            }
            GL11.glViewport(0, 0, w, h);
            PredictionRenderer.renderIris(frame, new Pass(program, depthCopy,
                    GL11.glGetInteger(GL11.GL_DEPTH_FUNC), w, h,
                    GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE) == GL45.GL_ZERO_TO_ONE,
                    translucent, materialIds, imageBindings), meshSnapshot, framePlan, viewport, frameId);
            PredictionRenderCapture.current(translucent ? "water-after" : "opaque-after");
        }

        void snapshotDepth(int source, int w, int h) {
            int internalFormat = GL45.glGetTextureLevelParameteri(source, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (depthCopy == 0 || width != w || height != h || format != internalFormat) {
                if (depthCopy != 0) GL11.glDeleteTextures(depthCopy);
                depthCopy = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                GL45.glTextureStorage2D(depthCopy, 1, internalFormat, w, h);
                GL45.glTextureParameteri(depthCopy, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL45.glTextureParameteri(depthCopy, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                width = w; height = h; format = internalFormat;
            }
            // Exact format copy avoids depth/stencil blit incompatibilities and
            // sampling a depth attachment while simultaneously writing to it.
            GL43.glCopyImageSubData(source, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    depthCopy, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
        }

        @Override public void close() {
            meshSnapshot = null;
            framePlan.clear(); depthView.clear();
            if (opaque != null) opaque.close();
            if (water != null) water.close();
            if (depthCopy != 0) GL11.glDeleteTextures(depthCopy);
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object object, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(object, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** Restore raw pass state; isolated Voxy callbacks leave Minecraft/Iris caches untouched. */
    static final class State implements AutoCloseable {
        final long captureStart = PredictionRenderTimings.start();
        final boolean isolated;
        final boolean previousIsolation;
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int textureBuffer = GL11.glGetInteger(GL31.GL_TEXTURE_BUFFER);
        final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final int[] viewport = new int[4];
        final int[][] textures;
        final int[][] blend;
        static final int[] TARGETS = {GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_3D, GL30.GL_TEXTURE_2D_ARRAY,
                GL13.GL_TEXTURE_CUBE_MAP, GL31.GL_TEXTURE_BUFFER};
        static final int[] BINDINGS = {GL11.GL_TEXTURE_BINDING_2D, GL12.GL_TEXTURE_BINDING_3D,
                GL30.GL_TEXTURE_BINDING_2D_ARRAY, GL13.GL_TEXTURE_BINDING_CUBE_MAP, GL31.GL_TEXTURE_BINDING_BUFFER};

        State(int textureUnits) { this(textureUnits, true); }

        State(int textureUnits, boolean isolated) {
            this.isolated = isolated;
            this.previousIsolation = PredictionGlState.isolated;
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            textures = new int[textureUnits][6];
            for (int unit = 0; unit < textures.length; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                for (int target = 0; target < TARGETS.length; target++) textures[unit][target] = GL11.glGetInteger(BINDINGS[target]);
                textures[unit][5] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, unit);
            }
            GL13.glActiveTexture(active);
            blend = new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)][5];
            for (int i = 0; i < blend.length; i++) {
                blend[i][0] = GL30.glIsEnabledi(GL11.GL_BLEND, i) ? 1 : 0;
                blend[i][1] = GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB, i);
                blend[i][2] = GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB, i);
                blend[i][3] = GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA, i);
                blend[i][4] = GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA, i);
            }
            PredictionGlState.isolated = isolated;
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.STATE, captureStart);
        }

        @Override public void close() {
            long timingStart = PredictionRenderTimings.start();
            try { restore(); }
            finally {
                PredictionGlState.isolated = previousIsolation;
                PredictionRenderTimings.end(PredictionRenderTimings.Stage.STATE, timingStart);
            }
        }

        private void restore() {
            for (int unit = 0; unit < textures.length; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                for (int target = 0; target < TARGETS.length; target++) {
                    if (!isolated && target == 0 && unit < PredictionExactCoverageMask.TEXTURE_UNITS) {
                        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
                        RenderSystem.bindTexture(textures[unit][target]);
                    }
                    GL11.glBindTexture(TARGETS[target], textures[unit][target]);
                }
                GL33.glBindSampler(unit, textures[unit][5]);
            }
            if (!isolated) RenderSystem.activeTexture(active);
            GL13.glActiveTexture(active);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, textureBuffer);
            com.mojang.blaze3d.platform.GlStateManager._glUseProgram(program);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (!isolated) {
                RenderSystem.depthFunc(depthFunc);
                RenderSystem.depthMask(depthMask);
                if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
                if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
                if (blend[0][0] != 0) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
                // Keep the ordinary pass cache in sync before MRT overrides.
                RenderSystem.blendFuncSeparate(blend[0][1], blend[0][2], blend[0][3], blend[0][4]);
            }
            // Native Voxy state may intentionally differ from the Minecraft cache.
            // Do not restore it through Iris's blend/depth lock interception.
            GL11.glDepthFunc(depthFunc);
            GL11.glDepthMask(depthMask);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            for (int i = 0; i < blend.length; i++) {
                restoreBlend(i, blend[i][1], blend[i][2], blend[i][3], blend[i][4]);
                if (blend[i][0] != 0) GL30.glEnablei(GL11.GL_BLEND, i); else GL30.glDisablei(GL11.GL_BLEND, i);
            }
        }
    }

    static void restoreBlend(int index, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (GL.getCapabilities().OpenGL40) GL40.glBlendFuncSeparatei(index, srcRgb, dstRgb, srcAlpha, dstAlpha);
        else ARBDrawBuffersBlend.glBlendFuncSeparateiARB(index, srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    private PredictionIrisBridge() { }
}
