package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Rasterize a zero-to-one projection with both actual GL conventions, as in the installed port. */
@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
@EnabledIfSystemProperty(named="vss.voxyJar", matches=".+")
class PredictionVoxyDepthConventionGpuTest {
    @Test void installedFinalBlitPreservesPhysicalWaterDepthDespitePropertyMismatch() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(16, 16, "VSS actual Voxy depth convention", 0, 0);
        assertNotEquals(0, window);
        try (var jar = new ZipFile(System.getProperty("vss.voxyJar"))) {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            System.out.println("Depth convention GPU: " + glGetString(GL_RENDERER));
            String source = load(jar, "voxy:post/blit_texture_depth_cutout.frag");
            var main = new TextureTarget(16, 16, true, false);
            var voxy = new TextureTarget(16, 16, true, false);
            int vao = glGenVertexArrays(); glBindVertexArray(vao);
            try (var prediction = new PredictionRenderTarget()) {
                prediction.ensure(main);
                for (boolean declaredZero : new boolean[]{true, false}) {
                    String fragment = source.replaceFirst("\n", declaredZero ? "\n#define USE_ZERO_ONE_DEPTH\n#define EMIT_COLOUR\n" : "\n#define EMIT_COLOUR\n");
                    fragment = PredictionFogBridge.patch("voxy:post/blit_texture_depth_cutout.frag", fragment);
                    try (var blit = GlProgram.link("actual_voxy_final_depth", """
                            #version 450 core
                            out vec2 UV;
                            void main() {
                                vec2 p = vec2(gl_VertexID == 1 ? 3 : -1, gl_VertexID == 2 ? 3 : -1);
                                UV = p * .5 + .5; gl_Position = vec4(p, 0, 1);
                            }
                            """, fragment);
                         var water = GlProgram.link("actual_voxy_water_depth", """
                            #version 450 core
                            uniform mat4 Projection;
                            uniform float Distance;
                            void main() {
                                vec2 p = vec2(gl_VertexID == 1 ? 3 : -1, gl_VertexID == 2 ? 3 : -1);
                                vec4 center = Projection * vec4(0, 0, -Distance, 1);
                                gl_Position = vec4(p * center.w, center.zw);
                            }
                            """, """
                            #version 450 core
                            uniform vec4 SurfaceColor;
                            out vec4 colour;
                            void main() { colour = SurfaceColor; }
                            """)) {
                        var vanilla = new Matrix4f().perspective(1.2F, 1, .05F, 2048);
                        var projection = new Matrix4f().perspective(1.2F, 1, 16, 48000, declaredZero);
                        for (boolean actualZero : new boolean[]{false, true}) for (float distance : new float[]{540, 1080, 4096}) {
                            glBindVertexArray(vao);
                            glClipControl(GL_LOWER_LEFT, actualZero ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
                            assertEquals(actualZero, PredictionNormalDepthBridge.sourceZeroToOne());
                            voxy.bindWrite(true); glDepthMask(true); glClearDepth(1); glClearColor(0, 0, 0, 0);
                            glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
                            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_ALWAYS); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
                            water.use(); matrix(water.uniform("Projection"), projection);
                            glUniform4f(water.uniform("SurfaceColor"), .1f, .3f, .6f, 1);
                            glUniform1f(water.uniform("Distance"), distance); glDrawArrays(GL_TRIANGLES, 0, 3);
                            main.bindWrite(true); glClearDepth(1); glClear(GL_DEPTH_BUFFER_BIT);
                            blit.use(); glBindTextureUnit(0, voxy.getDepthTextureId()); glBindTextureUnit(3, voxy.getColorTextureId());
                            matrix(1, new Matrix4f(projection).invert()); matrix(2, vanilla);
                            PredictionNormalDepthBridge.bind(actualZero);
                            glDrawArrays(GL_TRIANGLES, 0, 3);
                            var depth = BufferUtils.createFloatBuffer(1); glReadPixels(8, 8, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
                            float expected = Math.min(1F - 1F / 16777215F,
                                    (-vanilla.m22() + vanilla.m32() / distance) * .5F + .5F);
                            assertEquals(expected, depth.get(0), 2F / 16777215F,
                                    "water depth must remain physical: declared=" + declaredZero + " actual=" + actualZero + " distance=" + distance);
                            // Normal prediction runs after Voxy has returned to
                            // Minecraft's [-1,1] convention. The borrowed texture
                            // must keep the convention with which it was rasterized.
                            glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
                            var borrowed = new PredictionVoxyDepth.Frame(voxy.getDepthTextureId(), main.frameBufferId,
                                    16, 16, net.minecraft.world.phys.Vec3.ZERO, new Matrix4f(projection).invert(), actualZero, false);
                            prediction.beginOpaque(main, VssLodProjection.of(vanilla), borrowed, vanilla);
                            glReadPixels(8, 8, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
                            // The seed also retains vanilla's quantized depth;
                            // two D24 bins map to this reciprocal-distance error.
                            float seedTolerance = 4F / (16777215F * Math.abs(vanilla.m32()));
                            assertEquals(1F / distance, depth.get(0), seedTolerance,
                                    "prediction must receive the same physical occluder after the final blit");
                            assertEquals(GL_NO_ERROR, glGetError());
                            verifyWaterBackground(main, voxy, prediction, water, blit, vao,
                                    vanilla, projection, actualZero, distance);
                        }
                    }
                }
            } finally { glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE); main.destroyBuffers(); voxy.destroyBuffers(); glDeleteVertexArrays(vao); }
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static void verifyWaterBackground(TextureTarget main, TextureTarget voxy, PredictionRenderTarget prediction,
                                              GlProgram surface, GlProgram blit, int vao, Matrix4f vanilla,
                                              Matrix4f voxyProjection, boolean actualZero, float distance) {
        glBindVertexArray(vao);
        // A water-only Voxy pixel: it has finite depth and partial alpha, exactly the live seam case.
        glClipControl(GL_LOWER_LEFT, actualZero ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
        voxy.bindWrite(true); glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_ALWAYS); glDepthMask(true);
        surface.use(); matrix(surface.uniform("Projection"), voxyProjection);
        glUniform1f(surface.uniform("Distance"), distance);
        glUniform4f(surface.uniform("SurfaceColor"), .1f,.3f,.6f,.7f); glDrawArrays(GL_TRIANGLES,0,3);
        var lod=VssLodProjection.of(vanilla);
        for (int mode : new int[]{0,1,2}) {
            boolean foreground = mode == 1;
            glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE);
            main.bindWrite(true); glClearColor(.8f,.9f,1,1); glClearDepth(1);
            glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
            // New order: the opaque prediction sees only opaque real depth, so the seabed survives.
            prediction.beginOpaque(main,lod);
            prediction.captureSeed();
            glBindVertexArray(vao); surface.use(); matrix(surface.uniform("Projection"),lod.matrix());
            glUniform1f(surface.uniform("Distance"),foreground?distance*.5f:distance+80);
            glUniform4f(surface.uniform("SurfaceColor"),.2f,.1f,.05f,1);
            glDisable(GL_BLEND); glDrawArrays(GL_TRIANGLES,0,3);
            prediction.writeDepth(main,lod);
            if (mode == 2) {
                // A seeded real occluder may reconstruct nearer than Voxy's source depth.
                // It must not be mistaken for a foreground pixel newly written by prediction.
                glClearTexImage(prediction.depthTextureId(),0,GL_DEPTH_COMPONENT,GL_FLOAT,new float[]{2f/distance});
                prediction.captureSeed();
            }
            glBindVertexArray(vao); blit.use(); matrix(1,new Matrix4f(voxyProjection).invert()); matrix(2,vanilla);
            PredictionNormalDepthBridge.bind(actualZero);
            glUniform1i(blit.uniform("VssPredictionBeforeWater"),1);
            glUniform1i(blit.uniform("VssPredictionOpaqueDepth"),4);
            glUniform1i(blit.uniform("VssPredictionSeedDepth"),5);
            glBindTextureUnit(0,voxy.getDepthTextureId()); glBindTextureUnit(3,voxy.getColorTextureId());
            glBindTextureUnit(4,prediction.depthTextureId()); glBindSampler(4,0);
            glBindTextureUnit(5,prediction.seedTextureId()); glBindSampler(5,0);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDrawArrays(GL_TRIANGLES,0,3);
            var pixel=BufferUtils.createByteBuffer(4); glReadPixels(8,8,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
            float[] ground={.2f,.1f,.05f},fluid={.1f,.3f,.6f};
            for(int c=0;c<3;c++) assertEquals(Math.round(255*(foreground?ground[c]:fluid[c]*.7f+ground[c]*.3f)),pixel.get(c)&255,2,
                    "water must use seabed, not sky; nearer prediction must survive even beyond vanilla far plane: distance="+distance+", foreground="+foreground);
            glUniform1i(blit.uniform("VssPredictionBeforeWater"),0);glDisable(GL_BLEND);
            assertEquals(GL_NO_ERROR,glGetError());
        }
    }

    private static void matrix(int location, Matrix4f matrix) {
        glUniformMatrix4fv(location, false, matrix.get(new float[16]));
    }
    private static String load(ZipFile jar, String id) throws Exception {
        if (id.equals("sodium:include/fog.glsl")) return "float getFragDistance(int shape, vec3 p) { return length(p); }\n";
        String[] parts = id.split(":", 2);
        String source = new String(jar.getInputStream(jar.getEntry("assets/" + parts[0] + "/shaders/" + parts[1])).readAllBytes(), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("#import <([^>]+)>").matcher(source);
        var result = new StringBuilder(); int last = 0;
        while (matcher.find()) { result.append(source, last, matcher.start()).append(load(jar, matcher.group(1))); last = matcher.end(); }
        return result.append(source.substring(last)).toString().replace("\r\n", "\n");
    }
}
