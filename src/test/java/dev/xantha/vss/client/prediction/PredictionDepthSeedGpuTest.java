package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Depth from an offscreen Voxy surface must not create an invisible occluder. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionDepthSeedGpuTest {
    @Test void onlyCompositedVoxyPixelsSeedPredictionAcrossConsecutiveFrames() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(16, 16, "VSS composited depth", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("Composited depth GPU: " + glGetString(GL_RENDERER));
            TextureTarget main = new TextureTarget(16, 16, true, false);
            TextureTarget voxy = new TextureTarget(16, 16, true, false);
            var vanilla = new Matrix4f().perspective((float) Math.toRadians(70), 1, .05F, 256);
            var projection = VssLodProjection.of(vanilla);
            var voxyProjection = new Matrix4f().perspective((float) Math.toRadians(70), 1, 16, 65536);
            try (var target = new PredictionRenderTarget()) {
                target.ensure(main);
                RenderSystem.disableCull(); glDisable(GL_CULL_FACE);
                RenderSystem.disableBlend(); glDisable(GL_BLEND);
                glDisable(GL_SCISSOR_TEST);
                // Offscreen depth exists on every frame, but the final Voxy
                // blit can discard a zero-alpha pixel or skip a fogged view.
                voxy.bindWrite(true);
                glDepthMask(true);
                glClearDepth((-voxyProjection.m22() + voxyProjection.m32() / 2048) * .5 + .5);
                glClear(GL_DEPTH_BUFFER_BIT);
                var borrowed = new PredictionVoxyDepth.Frame(voxy.getDepthTextureId(), main.frameBufferId,
                        16, 16, Vec3.ZERO, new Matrix4f(voxyProjection).invert());
                for (int frame = 0; frame < 120; frame++) {
                    main.bindWrite(true);
                    glDepthMask(true);
                    glClearDepth(1);
                    glClear(GL_DEPTH_BUFFER_BIT);
                    int left = (frame & 1) == 0 ? 0 : 8;
                    glEnable(GL_SCISSOR_TEST);
                    glScissor(left, 0, 8, 16);
                    // Voxy's far clamp leaves the last non-clear main bin.
                    glClearDepth(1.0 - 1.0 / 16777215.0);
                    glClear(GL_DEPTH_BUFFER_BIT);
                    glDisable(GL_SCISSOR_TEST);
                    target.beginOpaque(main, projection, borrowed, vanilla);
                    var depth = BufferUtils.createFloatBuffer(256);
                    glReadPixels(0, 0, 16, 16, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
                    for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                        boolean composited = x >= left && x < left + 8;
                        assertEquals(composited ? 1F / 2048 : 0, depth.get(y * 16 + x), 1e-6F,
                                "only current composited pixels may occlude fallback, frame=" + frame + ",x=" + x);
                    }
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            } finally {
                glClearDepth(1);
                main.destroyBuffers(); voxy.destroyBuffers();
            }
        } finally {
            glfwDestroyWindow(window); glfwTerminate();
        }
    }
}
