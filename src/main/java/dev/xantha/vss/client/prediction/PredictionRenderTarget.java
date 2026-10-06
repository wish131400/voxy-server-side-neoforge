package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.joml.Matrix4f;

/**
 * Small offscreen render target used by the predictive terrain pass.
 *
 * <p>The target reuses Minecraft's colour attachment and owns a reversed-depth
 * attachment containing only prediction depth. Terrain samples vanilla/Voxy
 * depth while drawing, so a foreground prediction can occlude a distant Voxy
 * section without replacing a closer real surface. Completed prediction depth
 * is converted back into the main target afterwards.</p>
 */
final class PredictionRenderTarget implements AutoCloseable {
    private static final float NEAR_PLANE = 1.0F;
    // Use the same fixed tie-break in the terrain test and depth export.
    static final float DEPTH_BIAS_BLOCKS = 0.02F;
    private static final String FULLSCREEN_VERTEX = """
            #version 150
            void main() {
                vec2 corner = vec2(gl_VertexID == 1 ? 3.0 : -1.0,
                                   gl_VertexID == 2 ? 3.0 : -1.0);
                gl_Position = vec4(corner, 0.0, 1.0);
            }
            """;
    private static final String WRITE_DEPTH_FRAGMENT = """
            #version 150
            uniform sampler2D LodDepth;
            uniform float NearPlane;
            uniform vec2 VanillaPlanes;
            uniform float DepthBias;
            out vec4 fragColor;
            void main() {
                float depth = texelFetch(LodDepth, ivec2(gl_FragCoord.xy), 0).r;
                if (depth <= 0.0) discard;
                float distance = NearPlane / depth;
                distance += DepthBias;
                float vanillaNdc = -VanillaPlanes.x + VanillaPlanes.y / distance;
                gl_FragDepth = clamp(vanillaNdc * 0.5 + 0.5, 0.0, 1.0);
                fragColor = vec4(0.0);
            }
            """;
    private static final String COPY_MAIN_DEPTH_FRAGMENT = """
            #version 150
            uniform sampler2D MainDepth;
            uniform sampler2D VoxyDepth;
            uniform bool VoxyDepthAvailable;
            uniform vec2 VanillaPlanes;
            uniform vec3 VoxyDepthTransform;
            uniform vec4 VoxyDistanceNumerator;
            uniform vec4 VoxyDistanceDenominator;
            void main() {
                float depth = texelFetch(MainDepth, ivec2(gl_FragCoord.xy), 0).r;
                // Minecraft's clear value and Voxy's far-plane clamp cannot
                // establish a finite occluder.  Keep those pixels at the
                // reversed-depth clear value so prediction remains visible;
                // the fragment shader still consults the borrowed Voxy depth
                // texture when it is available.
                float reversedDepth = 0.0;
                if (depth < 1.0 - 2.0 / 16777215.0) {
                    float denominator = depth * 2.0 - 1.0 + VanillaPlanes.x;
                    float distance = VanillaPlanes.y / denominator;
                    if (distance > 0.0) reversedDepth = clamp(1.0 / distance, 0.0, 1.0);
                }
                // Voxy's final colour/depth blit discards zero-alpha pixels.
                // Its offscreen depth alone is not evidence of a visible
                // surface; only refine pixels actually present in main depth.
                if (VoxyDepthAvailable && depth < 1.0) {
                    float raw = texelFetch(VoxyDepth, ivec2(gl_FragCoord.xy), 0).r;
                    if (raw > 0.0 && raw < 1.0) {
                        vec2 uv = gl_FragCoord.xy / vec2(textureSize(VoxyDepth, 0));
                        vec4 clip = vec4(uv * 2.0 - 1.0,
                                raw * VoxyDepthTransform.x + VoxyDepthTransform.y, 1.0);
                        float denominator = dot(VoxyDistanceDenominator, clip);
                        if (abs(denominator) > 1e-10) {
                            float distance = dot(VoxyDistanceNumerator, clip) / denominator;
                            if (distance > 0.0 && distance < 1e30)
                                reversedDepth = max(reversedDepth, clamp(1.0 / distance, 0.0, 1.0));
                        }
                    }
                }
                gl_FragDepth = reversedDepth;
            }
            """;

    private int framebuffer = -1;
    private int depthTexture = -1;
    private int seedTexture = -1;
    private int colorTexture = -1;
    private int fullscreenVertexArray = -1;
    private GlProgram writeDepth;
    private GlProgram copyDepth;
    private int width;
    private int height;

    void ensure(RenderTarget main) {
        if (main == null || main.width <= 0 || main.height <= 0) {
            return;
        }
        int mainColor = main.getColorTextureId();
        if (framebuffer != -1 && width == main.width && height == main.height
                && colorTexture == mainColor) {
            // Deleted texture names can be reused while this FBO still holds
            // the old object alive. Matching dimensions/id do not prove that
            // the attachment is the current main target's texture object.
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, mainColor, 0);
            main.bindWrite(false);
            return;
        }
        ensurePrograms();
        if (seedTexture != -1) { GL11.glDeleteTextures(seedTexture); seedTexture = -1; }
        if (framebuffer == -1) {
            framebuffer = GlStateManager.glGenFramebuffers();
        }
        if (depthTexture != -1) {
            TextureUtil.releaseTextureId(depthTexture);
        }
        width = main.width;
        height = main.height;
        colorTexture = mainColor;
        depthTexture = TextureUtil.generateTextureId();
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        PredictionGlState.bindTexture(depthTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12Compat.CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12Compat.CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14Compat.TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        try (var unpack = PredictionPixelUnpack.begin()) {
            GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                    width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);
        }
        PredictionGlState.bindTexture(0);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colorTexture, 0);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTexture, 0);
        int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("VSS prediction framebuffer incomplete: " + status);
        }
        main.bindWrite(false);
    }

    boolean available() {
        return framebuffer != -1 && depthTexture != -1;
    }

    int depthTextureId() {
        return depthTexture;
    }

    int seedTextureId() { return seedTexture; }

    /** Distinguish new prediction pixels from quantized real depth during Voxy's later water blit. */
    void captureSeed() {
        if (seedTexture == -1) {
            seedTexture = org.lwjgl.opengl.GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            org.lwjgl.opengl.GL45.glTextureStorage2D(seedTexture, 1, GL30.GL_DEPTH_COMPONENT32F, width, height);
            org.lwjgl.opengl.GL45.glTextureParameteri(seedTexture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            org.lwjgl.opengl.GL45.glTextureParameteri(seedTexture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }
        org.lwjgl.opengl.GL43.glCopyImageSubData(depthTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                seedTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    /** Clears prediction depth; the terrain shader tests main depth separately. */
    void beginOpaque(RenderTarget main) {
        if (!available() || main == null) {
            return;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(0, 0, width, height);
        PredictionGlState.depthMask(true);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        // Kept for callers that only need the clear/restore contract.  The
        // renderer uses the projection-aware overload below.
        GlStateManager._clearDepth(0.0D);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        PredictionGlState.enableDepthTest();
        PredictionGlState.depthFunc(GL11.GL_GEQUAL);
    }

    /** Same conversion as above with the current vanilla projection. */
    void beginOpaque(RenderTarget main, VssLodProjection.MatrixData projection) {
        beginOpaque(main, projection, null, null);
    }

    /** Seeds prediction depth from the vanilla target and, when available, Voxy's unclamped depth. */
    void beginOpaque(RenderTarget main, VssLodProjection.MatrixData projection,
                     PredictionVoxyDepth.Frame voxy, Matrix4f mainMvp) {
        if (!available() || main == null) {
            return;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(0, 0, width, height);
        PredictionGlState.depthMask(true);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        ensurePrograms();
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        PredictionGlState.bindTexture(main.getDepthTextureId());
        copyDepth.use();
        GL20.glUniform1i(copyDepth.uniform("MainDepth"), 0);
        GL20.glUniform1i(copyDepth.uniform("VoxyDepth"), 5);
        boolean voxyAvailable = voxy != null && voxy.texture() > 0
                && voxy.width() == width && voxy.height() == height && mainMvp != null;
        GL20.glUniform1i(copyDepth.uniform("VoxyDepthAvailable"), voxyAvailable ? 1 : 0);
        GL20.glUniform2f(copyDepth.uniform("VanillaPlanes"),
                projection.vanillaA(), projection.vanillaB());
        if (voxyAvailable) {
            org.joml.Vector4f numerator = new Matrix4f(mainMvp).mul(voxy.inverseMvp())
                    .getRow(3, new org.joml.Vector4f());
            org.joml.Vector4f denominator = voxy.inverseMvp().getRow(3, new org.joml.Vector4f());
            GL20.glUniform3f(copyDepth.uniform("VoxyDepthTransform"), voxy.zeroToOne() ? 1 : 2,
                    voxy.zeroToOne() ? 0 : -1, voxy.reverseZ() ? 1 : -1);
            GL20.glUniform4f(copyDepth.uniform("VoxyDistanceNumerator"),
                    numerator.x, numerator.y, numerator.z, numerator.w);
            GL20.glUniform4f(copyDepth.uniform("VoxyDistanceDenominator"),
                    denominator.x, denominator.y, denominator.z, denominator.w);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE5);
            PredictionGlState.bindTexture(voxy.texture());
            PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        }
        PredictionGlState.colorMask(false, false, false, false);
        // The fullscreen pass must write every pixel into the depth
        // attachment.  Disabling depth testing here is harmless for colour,
        // but on some drivers it also prevents a depth-only fragment from
        // updating the attachment when the target was just cleared.
        PredictionGlState.enableDepthTest();
        PredictionGlState.depthFunc(GL11.GL_ALWAYS);
        PredictionGlState.depthMask(true);
        drawFullscreen();
        PredictionGlState.colorMask(true, true, true, true);
        if (voxyAvailable) {
            PredictionGlState.activeTexture(GL13.GL_TEXTURE5);
            PredictionGlState.bindTexture(0);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        }
        PredictionGlState.bindTexture(0);
        GlProgram.unuse();
        PredictionGlState.depthFunc(GL11.GL_GEQUAL);
    }

    /** Retains both the opaque colour and reversed depth for fluids. */
    void beginWater() {
        if (!available()) {
            return;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(0, 0, width, height);
    }

    /**
     * Restore the normal Minecraft draw target after the isolated pass.  The
     * vanilla level renderer and Voxy both expect this target to remain bound
     * between render stages; leaving the default framebuffer active changes
     * the meaning of their depth and colour operations.
     */
    void restore(RenderTarget main) {
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        if (main != null) {
            main.bindWrite(false);
        } else {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }

    /** Converts the closest reversed prediction depth back to main depth. */
    void writeDepth(RenderTarget main, VssLodProjection.MatrixData projection) {
        if (!available() || main == null) {
            return;
        }
        main.bindWrite(false);
        RenderSystem.viewport(0, 0, main.width, main.height);
        PredictionGlState.enableDepthTest();
        PredictionGlState.depthFunc(GL11.GL_LESS);
        PredictionGlState.depthMask(true);
        PredictionGlState.colorMask(false, false, false, false);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        writeDepth.use();
        bindTexture(depthTexture);
        GL20.glUniform1i(writeDepth.uniform("LodDepth"), 0);
        GL20.glUniform1f(writeDepth.uniform("NearPlane"), NEAR_PLANE);
        GL20.glUniform2f(writeDepth.uniform("VanillaPlanes"),
                projection.vanillaA(), projection.vanillaB());
        GL20.glUniform1f(writeDepth.uniform("DepthBias"), DEPTH_BIAS_BLOCKS);
        drawFullscreen();
        PredictionGlState.colorMask(true, true, true, true);
        unbindTexture();
        GlProgram.unuse();
    }

    private void ensurePrograms() {
        if (writeDepth != null && copyDepth != null) {
            return;
        }
        if (writeDepth == null) {
            writeDepth = GlProgram.link("vss_prediction_write_depth",
                    FULLSCREEN_VERTEX, WRITE_DEPTH_FRAGMENT);
        }
        if (copyDepth == null) {
            copyDepth = GlProgram.link("vss_prediction_copy_main_depth",
                    FULLSCREEN_VERTEX, COPY_MAIN_DEPTH_FRAGMENT);
        }
        if (fullscreenVertexArray == -1) {
            fullscreenVertexArray = GlStateManager._glGenVertexArrays();
        }
    }

    private void drawFullscreen() {
        GlStateManager._glBindVertexArray(fullscreenVertexArray);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GlStateManager._glBindVertexArray(0);
    }

    private static void bindTexture(int texture) {
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        PredictionGlState.bindTexture(texture);
    }

    private static void unbindTexture() {
        PredictionGlState.bindTexture(0);
    }

    @Override
    public void close() {
        if (writeDepth != null) {
            writeDepth.close();
            writeDepth = null;
        }
        if (copyDepth != null) {
            copyDepth.close();
            copyDepth = null;
        }
        if (fullscreenVertexArray != -1) {
            GlStateManager._glDeleteVertexArrays(fullscreenVertexArray);
            fullscreenVertexArray = -1;
        }
        if (depthTexture != -1) {
            TextureUtil.releaseTextureId(depthTexture);
            depthTexture = -1;
        }
        if (framebuffer != -1) {
            GlStateManager._glDeleteFramebuffers(framebuffer);
            framebuffer = -1;
        }
        colorTexture = -1;
        if (seedTexture != -1) { GL11.glDeleteTextures(seedTexture); seedTexture = -1; }
        width = 0;
        height = 0;
    }

    /** Constants absent from GL11 but kept local to avoid another API layer. */
    private static final class GL12Compat {
        private static final int CLAMP_TO_EDGE = 0x812F;
    }

    private static final class GL14Compat {
        private static final int TEXTURE_COMPARE_MODE = 0x884C;
    }
}
