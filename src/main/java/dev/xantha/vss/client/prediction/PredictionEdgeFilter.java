package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL33C.*;

/** Single-frame, directional FXAA for distant world edges; no history or depth writes. */
final class PredictionEdgeFilter implements AutoCloseable {
    private int texture, framebuffer, outputFramebuffer, vao, width, height;
    private GlProgram program;
    private boolean failed;
    private final java.nio.ByteBuffer colorMask = org.lwjgl.BufferUtils.createByteBuffer(4);

    void render(RenderTarget target, Matrix4f projection) {
        if (failed || target.width <= 0 || target.height <= 0 || target.getDepthTextureId() <= 0) return;
        boolean stencil = glIsEnabled(GL_STENCIL_TEST);
        int scissor = glIsEnabled(GL_SCISSOR_TEST) ? 1 : 0;
        glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
        try (var saved = new PredictionIrisBridge.State(2)) {
            ensure(target.width, target.height);
            glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, target.frameBufferId);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            // Sample main depth through a colour-only target: sampling an
            // attached depth image would be feedback even with writes disabled.
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, outputFramebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target.getColorTextureId(), 0);
            glViewport(0, 0, width, height);
            glDisable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glColorMask(true, true, true, true);
            program.use();
            glUniform1i(program.uniform("Color"), 0); glUniform1i(program.uniform("Depth"), 1);
            glUniform2f(program.uniform("Pixel"), 1F / width, 1F / height);
            glUniform3f(program.uniform("Projection"), projection.m22(), projection.m32(), Math.abs(projection.m11()) * height * .5F);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture); glBindSampler(0, 0);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, target.getDepthTextureId()); glBindSampler(1, 0);
            glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES, 0, 3);
        } catch (RuntimeException failure) {
            failed = true;
            dev.xantha.vss.common.VSSLogger.warn("VSS distant edge filter disabled after failure", failure);
        } finally {
            glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            if (stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
            if (scissor != 0) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        }
    }

    private void ensure(int w, int h) {
        if (program == null) {
            program = GlProgram.link("vss_distant_fxaa", VERTEX, FRAGMENT);
            vao = glGenVertexArrays(); framebuffer = glGenFramebuffers(); outputFramebuffer = glGenFramebuffers(); texture = glGenTextures();
        }
        if (width == w && height == h) return;
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture);
        try (var unpack = PredictionPixelUnpack.begin()) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("edge filter target incomplete");
        width = w; height = h;
    }

    @Override public void close() {
        if (program != null) program.close();
        if (texture != 0) glDeleteTextures(texture);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (outputFramebuffer != 0) glDeleteFramebuffers(outputFramebuffer);
        if (vao != 0) glDeleteVertexArrays(vao);
        program = null; texture = framebuffer = outputFramebuffer = vao = width = height = 0; failed = false;
    }

    static final String VERTEX = """
            #version 150
            void main() { gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0); }
            """;
    static final String FRAGMENT = """
            #version 150
            uniform sampler2D Color;
            uniform sampler2D Depth;
            uniform vec2 Pixel;
            uniform vec3 Projection;
            out vec4 fragColor;
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            void main() {
                vec2 uv = gl_FragCoord.xy * Pixel;
                vec4 center = texture(Color, uv);
                float depth = texelFetch(Depth, ivec2(gl_FragCoord.xy), 0).r;
                float distance = abs(Projection.y / (depth * 2.0 - 1.0 + Projection.x));
                // World units per pixel automatically preserve nearby/zoomed detail.
                float amount = depth >= 1.0 ? 0.0 : smoothstep(0.15, 0.65, distance / max(Projection.z, 1.0));
                fragColor = center;
                if (amount == 0.0) return;
                float nw = luma(texture(Color, uv + vec2(-1,-1) * Pixel).rgb);
                float ne = luma(texture(Color, uv + vec2(1,-1) * Pixel).rgb);
                float sw = luma(texture(Color, uv + vec2(-1,1) * Pixel).rgb);
                float se = luma(texture(Color, uv + vec2(1,1) * Pixel).rgb);
                vec3 n = texture(Color, uv + vec2(0,-1) * Pixel).rgb;
                vec3 s = texture(Color, uv + vec2(0,1) * Pixel).rgb;
                vec3 e = texture(Color, uv + vec2(1,0) * Pixel).rgb;
                vec3 w = texture(Color, uv + vec2(-1,0) * Pixel).rgb;
                float mid = luma(center.rgb);
                float low = min(min(mid, min(min(nw,ne),min(sw,se))), min(min(luma(n),luma(s)),min(luma(e),luma(w))));
                float high = max(max(mid, max(max(nw,ne),max(sw,se))), max(max(luma(n),luma(s)),max(luma(e),luma(w))));
                if (high - low < max(0.0312, high * 0.125)) return;
                // Directional FXAA: filter along the edge, bound the search,
                // and reject the wider estimate if it escapes local luminance.
                vec2 dir = vec2(-((nw+ne)-(sw+se)), (nw+sw)-(ne+se));
                float reduce = max((nw+ne+sw+se) * (0.25 * 0.125), 1.0/128.0);
                dir = clamp(dir / (min(abs(dir.x),abs(dir.y)) + reduce), vec2(-4), vec2(4)) * Pixel;
                vec3 a = 0.5 * (texture(Color,uv+dir*(1.0/3.0-0.5)).rgb + texture(Color,uv+dir*(2.0/3.0-0.5)).rgb);
                vec3 b = a*0.5 + 0.25*(texture(Color,uv-dir*0.5).rgb + texture(Color,uv+dir*0.5).rgb);
                float lb = luma(b);
                // Parallel snow ledges can have zero diagonal gradient. The
                // subpixel term catches isolated lines/checkers without a
                // random mask or history; broad straight edges get no term.
                vec3 crossAverage = (n+s+e+w)*0.25;
                float subpixel = 0.65 * smoothstep(0.25,0.75,abs(luma(crossAverage)-mid)/(high-low));
                vec3 filtered = mix(lb < low || lb > high ? a : b, crossAverage, subpixel);
                fragColor = vec4(mix(center.rgb, filtered, amount), center.a);
            }
            """;
}
