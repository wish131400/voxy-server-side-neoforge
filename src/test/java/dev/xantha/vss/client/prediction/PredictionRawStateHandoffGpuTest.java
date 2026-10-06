package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Verify that an external raw GL pass cannot leave old prediction occluders behind. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionRawStateHandoffGpuTest {
    @Test
    void everyOpaqueSeedReplacesOldDepthAfterRawStateChanges() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(16, 16, "VSS raw state handoff", 0, 0);
        assertNotEquals(0L, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("Raw state handoff GPU: " + glGetString(GL_RENDERER));
            TextureTarget main = new TextureTarget(16, 16, true, false);
            try (PredictionRenderTarget target = new PredictionRenderTarget()) {
                target.ensure(main);
                var projection = VssLodProjection.of(new Matrix4f().perspective(
                        (float) Math.toRadians(70), 1, .05F, 512));
                var failures = new ArrayList<org.junit.jupiter.api.function.Executable>();
                for (String mode : new String[]{"depthMask", "depthTest", "depthFunction", "colorMask"}) {
                    normalize();
                    clearMain(main, projection, 8);
                    target.beginOpaque(main, projection);
                    float old = readDepth();
                    assertEquals(1F / 8, old, 2e-5F, "clean initial seed");
                    normalize();
                    clearMain(main, projection, 64);
                    // Keep Minecraft's cache at the desired state, then simulate a
                    // Voxy/Iris raw call. The production copy must establish its
                    // own state even when RenderSystem believes it is installed.
                    if (mode.equals("depthMask")) glDepthMask(false);
                    if (mode.equals("depthTest")) glDisable(GL_DEPTH_TEST);
                    if (mode.equals("depthFunction")) glDepthFunc(GL_NEVER);
                    if (mode.equals("colorMask")) glColorMask(false, false, false, false);
                    target.beginOpaque(main, projection);
                    float seeded = readDepth();
                    boolean depth = glIsEnabled(GL_DEPTH_TEST);
                    boolean writes = glGetBoolean(GL_DEPTH_WRITEMASK);
                    int function = glGetInteger(GL_DEPTH_FUNC);
                    System.out.println("raw=" + mode + ", before=" + old + ", seeded=" + seeded
                            + ", depth=" + depth + ", writes=" + writes + ", function=" + function);
                    failures.add(() -> assertEquals(1F / 64, seeded, 2e-5F,
                            mode + " must not retain the previous frame's nearer occluder"));
                    failures.add(() -> assertTrue(depth, mode + " must enable depth testing"));
                    failures.add(() -> assertTrue(writes, mode + " must enable depth writes"));
                    failures.add(() -> assertEquals(GL_GEQUAL, function, mode + " must restore reversed depth compare"));
                    assertEquals(GL_NO_ERROR, glGetError());
                }
                normalize();
                clearMain(main, projection, 64);
                target.beginOpaque(main, projection);
                main.bindWrite(true);
                glClearColor(.25F, .5F, .75F, 1);
                glClearDepth(1);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                // Voxy uses raw colour writes. If a cached depth-only mask
                // survives from a surrounding pass, the cached API can skip
                // the mask which must isolate WRITE_DEPTH_FRAGMENT's black output.
                RenderSystem.colorMask(false, false, false, false);
                glColorMask(true, true, true, true);
                target.writeDepth(main, projection);
                ByteBuffer pixel = BufferUtils.createByteBuffer(4);
                glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                int red = pixel.get(0) & 255, green = pixel.get(1) & 255, blue = pixel.get(2) & 255;
                System.out.println("raw=depthExportColorMask, color=" + red + "," + green + "," + blue);
                failures.add(() -> assertEquals(64, red, 1,
                        "depth export must preserve main red after an external raw color-mask change"));
                failures.add(() -> assertEquals(128, green, 1,
                        "depth export must preserve main green after an external raw color-mask change"));
                failures.add(() -> assertEquals(191, blue, 1,
                        "depth export must preserve main blue after an external raw color-mask change"));
                assertTrue(readDepth() < 1, "the tested export must really write its prediction depth");
                assertEquals(GL_NO_ERROR, glGetError());
                assertAll(failures);
            } finally {
                normalize();
                main.destroyBuffers();
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static void normalize() {
        // Cache and native state must agree before each independent scenario.
        RenderSystem.enableDepthTest(); glEnable(GL_DEPTH_TEST);
        RenderSystem.depthMask(true); glDepthMask(true);
        RenderSystem.depthFunc(GL_ALWAYS); glDepthFunc(GL_ALWAYS);
        RenderSystem.colorMask(true, true, true, true); glColorMask(true, true, true, true);
        RenderSystem.disableCull(); glDisable(GL_CULL_FACE);
        RenderSystem.disableBlend(); glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST);
    }

    private static void clearMain(TextureTarget main, VssLodProjection.MatrixData projection, float distance) {
        main.bindWrite(true);
        glClearDepth(VssLodProjection.distanceToVanillaDepth(distance, projection));
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    private static float readDepth() {
        FloatBuffer value = BufferUtils.createFloatBuffer(1);
        glReadPixels(8, 8, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, value);
        return value.get(0);
    }
}
