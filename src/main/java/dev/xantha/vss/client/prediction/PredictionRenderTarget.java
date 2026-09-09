package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

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

    private int framebuffer = -1;
    private int depthTexture = -1;
    private int colorTexture = -1;
    private int fullscreenVertexArray = -1;
    private GlProgram writeDepth;
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
        GlStateManager._bindTexture(depthTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12Compat.CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12Compat.CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14Compat.TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);
        GlStateManager._bindTexture(0);

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

    /** Clears prediction depth; the terrain shader tests main depth separately. */
    void beginOpaque(RenderTarget main) {
        if (!available() || main == null) {
            return;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RenderSystem.viewport(0, 0, width, height);
        RenderSystem.depthMask(true);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GlStateManager._clearDepth(0.0D);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        GlStateManager._clearDepth(1.0D);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_GEQUAL);
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
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LESS);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        writeDepth.use();
        bindTexture(depthTexture);
        GL20.glUniform1i(writeDepth.uniform("LodDepth"), 0);
        GL20.glUniform1f(writeDepth.uniform("NearPlane"), NEAR_PLANE);
        GL20.glUniform2f(writeDepth.uniform("VanillaPlanes"),
                projection.vanillaA(), projection.vanillaB());
        GL20.glUniform1f(writeDepth.uniform("DepthBias"), DEPTH_BIAS_BLOCKS);
        drawFullscreen();
        RenderSystem.colorMask(true, true, true, true);
        unbindTexture();
        GlProgram.unuse();
    }

    private void ensurePrograms() {
        if (writeDepth != null) {
            return;
        }
        writeDepth = GlProgram.link("vss_prediction_write_depth",
                FULLSCREEN_VERTEX, WRITE_DEPTH_FRAGMENT);
        fullscreenVertexArray = GlStateManager._glGenVertexArrays();
    }

    private void drawFullscreen() {
        GlStateManager._glBindVertexArray(fullscreenVertexArray);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GlStateManager._glBindVertexArray(0);
    }

    private static void bindTexture(int texture) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(texture);
    }

    private static void unbindTexture() {
        RenderSystem.bindTexture(0);
    }

    @Override
    public void close() {
        if (writeDepth != null) {
            writeDepth.close();
            writeDepth = null;
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
