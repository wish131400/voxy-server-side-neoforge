package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Actual MRT pixels and hostile pixel-pack state; no complete modpack reproduction is claimed. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionFrameCaptureGpuTest {
    @TempDir Path directory;

    @Test void recordsEntityReplacementWithoutChangingRenderingState() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(64, 64, "VSS MRT capture", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            int fb = glCreateFramebuffers(), other = glCreateFramebuffers(), vao = glGenVertexArrays();
            int[] textures = {texture(GL_RGBA16F), texture(GL_RGBA8), texture(GL_DEPTH_COMPONENT32F)};
            int pbo = glGenBuffers();
            glNamedFramebufferTexture(fb, GL_COLOR_ATTACHMENT0, textures[0], 0);
            glNamedFramebufferTexture(fb, GL_COLOR_ATTACHMENT3, textures[1], 0);
            glNamedFramebufferTexture(fb, GL_DEPTH_ATTACHMENT, textures[2], 0);
            glNamedFramebufferDrawBuffers(fb, new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT3});
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckNamedFramebufferStatus(fb, GL_FRAMEBUFFER));
            try (GlProgram entity = GlProgram.link("capture_entity", """
                    #version 450 core
                    void main() {
                        gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, -0.8, 1.0);
                    }
                    """, """
                    #version 450 core
                    layout(location=0) out vec4 skin;
                    layout(location=1) out vec4 transparentLayer;
                    uniform vec4 SkinColor;
                    void main() {
                        if (gl_FragCoord.x >= 32) discard;
                        skin = SkinColor;
                        transparentLayer = vec4(0);
                    }
                    """)) {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fb); glViewport(0, 0, 64, 64);
                glClearNamedFramebufferfv(fb, GL_COLOR, 0, new float[]{0, 1, 0, 1});
                glClearNamedFramebufferfv(fb, GL_COLOR, 1, new float[]{0, 0, 1, 1});
                glClearNamedFramebufferfv(fb, GL_DEPTH, 0, new float[]{.8f});
                glBindFramebuffer(GL_READ_FRAMEBUFFER, fb); glReadBuffer(GL_COLOR_ATTACHMENT3);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, other);
                glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo); glBufferData(GL_PIXEL_PACK_BUFFER, 65536, GL_STREAM_READ);
                glPixelStorei(GL_PACK_ROW_LENGTH, 256); glPixelStorei(GL_PACK_SKIP_ROWS, 3);
                glPixelStorei(GL_PACK_SKIP_PIXELS, 5); glPixelStorei(GL_PACK_ALIGNMENT, 8);
                glPixelStorei(GL_PACK_SWAP_BYTES, 1);
                glActiveTexture(GL_TEXTURE0 + 17);
                glBindTexture(GL_TEXTURE_2D, textures[0]);
                PredictionFrameCapture capture = new PredictionFrameCapture(.5, .5);
                capture.capture("prediction", fb);
                verifyState(fb, other, pbo, textures[0]);
                assertEquals(GL_NO_ERROR, glGetError(), "diagnostic readback must not generate GL errors");

                glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS); glDepthMask(true); glDisable(GL_BLEND);
                glDisable(GL_CULL_FACE); glBindVertexArray(vao); entity.use();
                glUniform4f(entity.uniform("SkinColor"), 1, 0, 0, 1);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                capture.capture("entity", fb);
                verifyState(fb, other, pbo, textures[0]);
                assertEquals(GL_NO_ERROR, glGetError());
                Path archive = capture.write(directory);
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    var report = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("report.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    assertEquals(2, report.getAsJsonArray("snapshots").size());
                    assertEquals(0, pixel(zip, "01-prediction-color0.f32", 8, 8, 0));
                    assertEquals(1, pixel(zip, "01-prediction-color0.f32", 8, 8, 1));
                    assertEquals(1, pixel(zip, "02-entity-color0.f32", 8, 8, 0), "skin replaces background");
                    assertEquals(0, pixel(zip, "02-entity-color3.f32", 8, 8, 3), "entity clears transparent layer");
                    assertEquals(1, pixel(zip, "02-entity-color0.f32", 48, 8, 1), "cutout retains background");
                    assertEquals(1, pixel(zip, "02-entity-color3.f32", 48, 8, 3));
                    byte[] depth = zip.getInputStream(zip.getEntry("02-entity-depth.f32")).readAllBytes();
                    assertEquals(.1f, ByteBuffer.wrap(depth).order(ByteOrder.nativeOrder()).getFloat((8 * 64 + 8) * 4), 1e-6);
                    assertFalse(report.getAsJsonArray("snapshots").get(1).getAsJsonObject().getAsJsonObject("state").getAsJsonArray("uniforms").isEmpty());
                }
                Path evidence = Path.of("build/reports/render-capture/test-frame.zip");
                java.nio.file.Files.createDirectories(evidence.getParent());
                java.nio.file.Files.copy(archive, evidence, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // Invalid targets and repeated hooks are bounded instead of allocating indefinitely.
                for (int i = 0; i < 50; i++) capture.capture("missing", 0);
                assertEquals(PredictionFrameCapture.MAX_SNAPSHOTS, capture.snapshots.size());
                assertEquals(0, PredictionFrameCapture.cropOrigin(0, 64, 32));
                assertEquals(32, PredictionFrameCapture.cropOrigin(1, 64, 32));
            } finally {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0); glDeleteBuffers(pbo);
                glDeleteVertexArrays(vao); glDeleteFramebuffers(fb); glDeleteFramebuffers(other);
                for (int texture : textures) glDeleteTextures(texture);
            }
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static int texture(int format) {
        int texture = glCreateTextures(GL_TEXTURE_2D); glTextureStorage2D(texture, 1, format, 64, 64); return texture;
    }

    private static void verifyState(int fb, int read, int pbo, int texture) {
        assertEquals(fb, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        assertEquals(read, glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fb);
        int selection = glGetInteger(GL_READ_BUFFER);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
        assertEquals(GL_COLOR_ATTACHMENT3, selection);
        assertEquals(pbo, glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING));
        assertEquals(256, glGetInteger(GL_PACK_ROW_LENGTH)); assertEquals(3, glGetInteger(GL_PACK_SKIP_ROWS));
        assertEquals(5, glGetInteger(GL_PACK_SKIP_PIXELS)); assertEquals(8, glGetInteger(GL_PACK_ALIGNMENT));
        assertEquals(1, glGetInteger(GL_PACK_SWAP_BYTES));
        assertEquals(GL_TEXTURE0 + 17, glGetInteger(GL_ACTIVE_TEXTURE));
        assertEquals(texture, glGetInteger(GL_TEXTURE_BINDING_2D));
    }

    private static float pixel(ZipFile zip, String name, int x, int y, int component) throws Exception {
        byte[] bytes = zip.getInputStream(zip.getEntry(name)).readAllBytes();
        return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).getFloat(((y * 64 + x) * 4 + component) * 4);
    }
}
