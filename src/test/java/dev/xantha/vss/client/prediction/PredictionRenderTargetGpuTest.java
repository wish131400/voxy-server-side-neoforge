package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Opt-in hidden-context test of the production target, including its GL state transitions. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionRenderTargetGpuTest {
    @Test
    void depthOrderSurvivesFluidsFrameResetAndResize() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(64, 64, "VSS pixel handoff regression", 0, 0);
        assertNotEquals(0L, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("Handoff GPU: " + glGetString(GL_RENDERER));
            int vao = glGenVertexArrays();
            try (PredictionRenderTarget target = new PredictionRenderTarget();
                 GlProgram fill = GlProgram.link("handoff_probe", """
                         #version 150
                         void main() {
                             gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0,
                                                gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0);
                         }
                         """, """
                         #version 150
                         uniform float Depth;
                         uniform vec4 Color;
                         uniform sampler2D MainDepth;
                         uniform float MainSpaceDepth;
                         uniform bool CompareMainDepth;
                         out vec4 fragColor;
                         void main() {
                             if (CompareMainDepth) {
                                 float mainDepth = texelFetch(MainDepth, ivec2(gl_FragCoord.xy), 0).r;
                                 if (mainDepth < 1.0 && MainSpaceDepth >= mainDepth) discard;
                             }
                             gl_FragDepth = Depth;
                             fragColor = Color;
                         }
                         """); GlProgram following = subsequentProgram()) {
                for (int size : new int[]{16, 32, 16, 16}) {
                    TextureTarget main = new TextureTarget(size, size, true, false);
                    try {
                        target.ensure(main);
                        assertTrue(target.available());
                        if (size == 16) {
                            // Keep the old object attached to prediction, then
                            // recreate the main texture with exactly the same name.
                            // OpenGL allows reuse even while an FBO retains it.
                            int colorName = main.getColorTextureId();
                            main.bindWrite(true);
                            com.mojang.blaze3d.platform.GlStateManager._deleteTexture(colorName);
                            java.util.List<Integer> reservedNames = new java.util.ArrayList<>();
                            int replacement;
                            do {
                                replacement = glGenTextures();
                                if (replacement != colorName) reservedNames.add(replacement);
                            } while (replacement != colorName && reservedNames.size() < 256);
                            for (int reserved : reservedNames) glDeleteTextures(reserved);
                            assertEquals(colorName, replacement, "fixture requires the released texture name to be reused");
                            com.mojang.blaze3d.platform.GlStateManager._bindTexture(colorName);
                            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorName, 0);
                            com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);
                            target.ensure(main);
                            assertEquals(GL_NO_ERROR, glGetError(), "recycled texture setup");
                        }
                        for (int frame = 0; frame < 4; frame++) {
                            Matrix4f vanilla = new Matrix4f().setPerspective(
                                    (float) Math.toRadians(70), 1, .05F, 65536);
                            // Include the pitch-coupled walking projection used in Minecraft.
                            vanilla.translate(.03F * frame, -.02F * frame, 0).rotateX(.01F * frame);
                            var projection = VssLodProjection.of(vanilla);
                            float exactDepth = (float) VssLodProjection.distanceToVanillaDepth(128, projection);
                            int left = frame % 2 == 0 ? 0 : size / 2;
                            main.bindWrite(true);
                            RenderSystem.disableBlend();
                            RenderSystem.disableCull();
                            RenderSystem.colorMask(true, true, true, true);
                            RenderSystem.depthMask(true);
                            glClearColor(0, 0, 0, 1);
                            glClearDepth(1);
                            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                            glEnable(GL_SCISSOR_TEST);
                            glScissor(left, 0, size / 2, size);
                            glClearColor(1, 0, 0, 1);
                            glClearDepth(exactDepth);
                            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                            glDisable(GL_SCISSOR_TEST);
                            glClearDepth(1);
                            assertColors(size, left, new int[]{0, 0, 0});
                            assertEquals(GL_NO_ERROR, glGetError(), "main target setup");
                            float[] originalDepth = depths(size);

                            target.beginOpaque(main);
                            assertFalse(glIsEnabled(GL_STENCIL_TEST));
                            float distance = new float[]{64, 128, 256, 512}[frame];
                            boolean predictionInFront = distance < 128;
                            RenderSystem.activeTexture(GL_TEXTURE7);
                            RenderSystem.bindTexture(main.getDepthTextureId());
                            fill.use();
                            glUniform1i(fill.uniform("MainDepth"), 7);
                            glUniform1i(fill.uniform("CompareMainDepth"), 1);
                            float expectedGap = (float) VssLodProjection.distanceToVanillaDepth(
                                    distance + .02, projection);
                            glUniform1f(fill.uniform("MainSpaceDepth"), expectedGap);
                            draw(fill, vao, 1 / distance, 0, 1, 0, 1);
                            assertColors(size, predictionInFront ? -size : left, new int[]{0, 255, 0});
                            target.beginWater();
                            RenderSystem.enableBlend();
                            RenderSystem.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                            RenderSystem.depthMask(false);
                            draw(fill, vao, 1 / distance, 0, 0, 1, .5F);
                            assertColors(size, predictionInFront ? -size : left, new int[]{0, 128, 128});

                            target.restore(main);
                            target.writeDepth(main, projection);
                            assertColors(size, predictionInFront ? -size : left, new int[]{0, 128, 128});
                            assertFalse(glIsEnabled(GL_STENCIL_TEST));
                            assertEquals(0xFF, glGetInteger(GL_STENCIL_WRITEMASK));
                            float[] result = depths(size);
                            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                                boolean owned = !predictionInFront && x >= left && x < left + size / 2;
                                int pixel = y * size + x;
                                if (owned) assertEquals(originalDepth[pixel], result[pixel],
                                        "exact depth must be preserved without round-trip changes");
                                else assertEquals(expectedGap, result[pixel], 2e-7F,
                                        "prediction must contribute depth in uncovered pixels");
                            }
                            RenderSystem.disableBlend();
                            following.use();
                            // Do not leave the current draw depth attachment
                            // bound as a sampler, even when its branch is off.
                            RenderSystem.activeTexture(GL_TEXTURE7);
                            RenderSystem.bindTexture(0);
                            assertTrue(glIsEnabled(GL_DEPTH_TEST), "depth enabled before subsequent world geometry");
                            assertEquals(GL_LESS, glGetInteger(GL_DEPTH_FUNC));
                            assertEquals(main.frameBufferId, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                            draw(following, vao, (float) VssLodProjection.distanceToVanillaDepth(
                                    1024, projection), 1, 1, 0, 1);
                            try {
                                assertColors(size, predictionInFront ? -size : left, new int[]{0, 128, 128});
                            } catch (AssertionError failure) {
                                fail("subsequent geometry: size=" + size + ",frame=" + frame
                                        + ",predictionDistance=" + distance + ",beforeDepth=" + result[0]
                                        + ",afterDepth=" + depths(size)[0] + ",drawDepth="
                                        + glGetUniformf(glGetInteger(GL_CURRENT_PROGRAM), following.uniform("Depth"))
                                        + ",depthMask=" + glGetBoolean(GL_DEPTH_WRITEMASK)
                                        + ",depthAttachment=" + glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
                                        + ",mainDepthTexture=" + main.getDepthTextureId()
                                        + ",readFramebuffer=" + glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
                                        + ",drawFramebuffer=" + glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
                                        + ",glError=" + glGetError(), failure);
                            }
                            draw(following, vao, (float) VssLodProjection.distanceToVanillaDepth(
                                    16, projection), 0, 0, 1, 1);
                            assertColors(size, -size, new int[]{0, 0, 255});
                            assertEquals(GL_NO_ERROR, glGetError());
                        }
                    } finally {
                        target.restore(main);
                        main.destroyBuffers();
                    }
                }
                System.out.println("PASS: 16 production target cases; foreground prediction covers distant real depth, real depth wins ties, gap fill, fluids, subsequent geometry occlusion, walking projection, frame reset and resize");
                verifySurfaceRendering(vao, false);
                verifySurfaceRendering(vao, true);
                verifyIrisPrograms();
                verifyIrisState();
                verifySharedVoxyFog(vao);
            } finally {
                glDeleteVertexArrays(vao);
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static GlProgram subsequentProgram() {
        // This program has no depth sampler: testing the downstream geometry
        // must never create feedback with the framebuffer it is drawing into.
        return GlProgram.link("subsequent_geometry", """
                #version 150
                void main() {
                    gl_Position=vec4(gl_VertexID==1?3.0:-1.0,gl_VertexID==2?3.0:-1.0,0.0,1.0);
                }
                """, """
                #version 150
                uniform float Depth;
                uniform vec4 Color;
                out vec4 fragColor;
                void main(){gl_FragDepth=Depth;fragColor=Color;}
                """);
    }

    private static void draw(GlProgram fill, int vao, float depth, float r, float g, float b, float a) {
        fill.use();
        glUniform1f(fill.uniform("Depth"), depth);
        glUniform4f(fill.uniform("Color"), r, g, b, a);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void assertColors(int size, int left, int[] gap) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
        glReadPixels(0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            boolean owned = x >= left && x < left + size / 2;
            for (int channel = 0; channel < 3; channel++) {
                int expected = owned ? (channel == 0 ? 255 : 0) : gap[channel];
                assertEquals(expected, pixels.get((y * size + x) * 4 + channel) & 255, 1,
                        "pixel " + x + "," + y + " channel " + channel + ",rgba="
                                + (pixels.get((y * size + x) * 4) & 255) + ","
                                + (pixels.get((y * size + x) * 4 + 1) & 255) + ","
                                + (pixels.get((y * size + x) * 4 + 2) & 255) + ","
                                + (pixels.get((y * size + x) * 4 + 3) & 255));
            }
        }
    }

    private static float[] depths(int size) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(size * size);
        glReadPixels(0, 0, size, size, GL_DEPTH_COMPONENT, GL_FLOAT, pixels);
        float[] values = new float[size * size];
        pixels.get(values);
        return values;
    }

    private static void verifySurfaceRendering(int vao, boolean iris) {
        TextureTarget target = new TextureTarget(64, 64, true, false);
        TextureTarget main = new TextureTarget(64, 64, true, false);
        int[] textures = new int[7];
        int[] buffers = new int[3];
        try (PredictionTerrainProgram terrain = iris ? PredictionTerrainProgram.createIris(
                "vec2 vssTaaShift(){return vec2(0.0);}", source -> source + """
                    layout(location=0) out vec4 color;
                    uniform bool VssTestLightmap;
                    void voxy_emitFragment(VoxyFragmentParameters p) {
                        color = p.sampledColour * p.tinting;
                        if (VssTestLightmap) color.rgb *= (p.lightMap.y * 256.0 - 8.0) / 240.0;
                    }
                    """) : PredictionTerrainProgram.create()) {
            target.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.colorMask(true, true, true, true);
            terrain.use();
            terrain.setFrame(new float[]{0, 0, 0, 1}, 1e7F, 2e7F, 0, 1e7F, false, 1);
            terrain.setOpaqueAlpha(1);
            terrain.setSamplers(0, 1, 2, 3, 4);
            // Adversarial legacy transition input: a shader that still applies
            // independent per-face heights must fail the closed-cliff check.
            glUniform1i(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "MorphDeltas"), 5);
            int program = glGetInteger(GL_CURRENT_PROGRAM);
            glUniform1f(glGetUniformLocation(program, "VanillaRenderDistance"), 128);
            glUniform1fv(glGetUniformLocation(program, "DirectionalTint[0]"),
                    new float[]{1, 1, 1, 1, 1, 1, 1});
            textures[0] = texture(0, GL_RGBA8, 2, 2, GL_RGBA, new float[]{
                    .6F,.2F,.05F,1, .9F,.4F,.1F,1, .9F,.4F,.1F,1, .6F,.2F,.05F,1});
            textures[1] = texture(1, GL_RGBA8, 1, 1, GL_RGBA, new float[]{.4F,.4F,.8F,1});
            textures[2] = texture(2, GL_RGBA32F, 2, 2, GL_RGBA, new float[]{
                    0,0,0,0, 0,0,1,1, 0,0,0,0, .75F,.3F,.075F,1});
            textures[3] = texture(3, GL_R8, 1, 1, GL_RED, new float[]{1});
            for (int unit = 4; unit <= 5; unit++) {
                RenderSystem.activeTexture(GL_TEXTURE0 + unit);
                textures[unit] = glGenTextures();
                glBindTexture(GL_TEXTURE_BUFFER, textures[unit]);
                buffers[unit - 4] = glGenBuffers();
                glBindBuffer(GL_TEXTURE_BUFFER, buffers[unit - 4]);
                glBufferData(GL_TEXTURE_BUFFER, new int[unit == 4 ? 12 : 1], GL_STATIC_DRAW);
                glTexBuffer(GL_TEXTURE_BUFFER, unit == 4 ? GL_RGBA32UI : GL_R32I, buffers[unit - 4]);
            }
            RenderSystem.activeTexture(GL_TEXTURE6);
            textures[6] = glGenTextures();
            glBindTexture(GL_TEXTURE_3D, textures[6]);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, 2, 2, 2, 0, GL_RED, GL_FLOAT, new float[8]);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glUniform3i(glGetUniformLocation(program, "VanillaMaskSize"), 2, 2, 2);
            glBindVertexArray(vao);
            buffers[2] = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[2]);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2, 0, 2, 3}, GL_STATIC_DRAW);
            int y = 32768 + 96 * 4;
            int[] roof = {32 << 16, 32, 0, 32 | (32 << 16), y | (y << 16), y | (y << 16),
                    1, 0xBF4D13, 0, 0x1BF4D13, 0xBF4D13, 0xBF4D13};
            glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]);
            glBufferData(GL_TEXTURE_BUFFER, roof, GL_STATIC_DRAW);

            for (float fov : new float[]{70, 7}) {
                for (float eyeX : new float[]{4, 16, 28}) {
                    for (float eyeY : new float[]{64, 128}) {
                        float eyeZ = 32 - eyeX;
                        var projection = VssLodProjection.of(new Matrix4f().perspective(
                                (float) Math.toRadians(fov), 1, .05F, 65536));
                        terrain.setCamera(new Matrix4f().lookAlong(16 - eyeX, 96 - eyeY,
                                16 - eyeZ, 0, 0, -1), projection.matrix());
                        if (iris) terrain.setIrisFrame(new Matrix4f(projection.matrix()).invert(),
                                64, 64, false, new int[256]);
                        terrain.setTile(-eyeX, -eyeY, -eyeZ, 32, 1, false);
                        glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"),
                                -eyeX, 80 - eyeY, -eyeZ);
                        // Vanilla turnOnLightLayer's bindForSetup overwrites
                        // the active slot. Reproduce that before the real binder.
                        RenderSystem.activeTexture(GL_TEXTURE0);
                        com.mojang.blaze3d.platform.GlStateManager._bindTexture(textures[1]);
                        PredictionRenderer.bindMaterialTextures(textures[0], textures[1], textures[2]);
                        assertEquals(textures[0], glGetInteger(GL_TEXTURE_BINDING_2D));
                        main.bindWrite(true);
                        RenderSystem.depthMask(true);
                        glClearDepth(iris ? 0 : 1);
                        glClear(GL_DEPTH_BUFFER_BIT);
                        target.bindWrite(true);
                        terrain.bindMainDepth(main.getDepthTextureId(), projection);
                        ByteBuffer pixels = terrainPixels();
                        int pixel = (32 * 64 + 32) * 4;
                        int red = pixels.get(pixel) & 255;
                        int green = pixels.get(pixel + 1) & 255;
                        int blue = pixels.get(pixel + 2) & 255;
                        if (eyeY < 96) {
                            assertEquals(0, red + green + blue, "no prediction roof from below, FOV=" + fov);
                        } else {
                            assertTrue(red > 100 && red > green * 1.8 && green > blue * 2,
                                    "block texture must remain orange after lightmap setup, FOV=" + fov);
                            float surfaceDistance = (float) Math.sqrt((16 - eyeX) * (16 - eyeX)
                                    + (96 - eyeY) * (96 - eyeY) + (16 - eyeZ) * (16 - eyeZ));
                            for (float realDistance : new float[]{16, surfaceDistance + 4, 256}) {
                                main.bindWrite(true);
                                glClearDepth(iris ? 1.0 / realDistance : VssLodProjection.distanceToVanillaDepth(realDistance, projection));
                                glClear(GL_DEPTH_BUFFER_BIT);
                                target.bindWrite(true);
                                int visibleRed = terrainPixels().get(pixel) & 255;
                                if (realDistance < 200) assertEquals(0, visibleRed,
                                        "real terrain wins close height disagreement too, FOV=" + fov);
                                else assertTrue(visibleRed > 100,
                                        "foreground prediction must occlude distant Voxy cut faces, FOV=" + fov);
                            }
                        }
                    }
                }
            }
            // The last camera is above the surface. Mark its real compiled
            // space, including empty sky, and verify prediction yields there.
            RenderSystem.activeTexture(GL_TEXTURE6);
            glBindTexture(GL_TEXTURE_3D, textures[6]);
            glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, 2, 2, 2, GL_RED, GL_FLOAT,
                    new float[]{1,1,1,1,1,1,1,1});
            ByteBuffer masked = terrainPixels();
            int center = (32 * 64 + 32) * 4;
            assertEquals(0, masked.get(center) & 255, "compiled air must reject prediction too");
            glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), 1024, 1024, 1024);
            assertTrue((terrainPixels().get(center) & 255) > 100,
                    "outside compiled space prediction must still fill the horizon");
            // A retained compiled column must not create a sky hole when the
            // camera leaves real render distance vertically or horizontally.
            for (float eyeY : new float[]{512, 1024}) {
                for (float fov : new float[]{70, 7}) {
                    var projection = VssLodProjection.of(new Matrix4f().perspective(
                            (float) Math.toRadians(fov), 1, .05F, 65536));
                    terrain.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), projection.matrix());
                    if (iris) terrain.setIrisFrame(new Matrix4f(projection.matrix()).invert(), 64, 64, false, new int[256]);
                    terrain.setTile(-16, -eyeY, -16, 32, 1, false);
                    glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), -16, 80 - eyeY, -16);
                    main.bindWrite(true);
                    glClearDepth(iris ? 0 : 1);
                    glClear(GL_DEPTH_BUFFER_BIT);
                    target.bindWrite(true);
                    terrain.bindMainDepth(main.getDepthTextureId(), projection);
                    assertTrue((terrainPixels().get(center) & 255) > 100,
                            "compiled-but-distant column must fall back to prediction; Iris=" + iris + " FOV=" + fov);
                    main.bindWrite(true);
                    glClearDepth(iris ? 1.0 / 64 : VssLodProjection.distanceToVanillaDepth(64, projection));
                    glClear(GL_DEPTH_BUFFER_BIT);
                    target.bindWrite(true);
                    assertEquals(0, terrainPixels().get(center) & 255, "real Voxy depth still wins at altitude");
                }
            }
            glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), 1024, 1024, 1024);
            if (!iris) verifyFarVoxyDepth(terrain, target, main, buffers);
            if (!iris) {
                verifyPlanarHandoff(terrain, target, main, buffers, true);
                verifyPlanarHandoff(terrain, target, main, buffers, false);
            }
            verifyClosedCliffDuringTransition(terrain, target, main, buffers, textures, iris);
            verifyMixedLodSeams(terrain, target, main, buffers, textures, iris);
            verifyWideTerrainEdge(terrain, target, main, buffers, iris);
            verifyWaterAndBakedUvs(terrain, target, main, buffers, textures, iris);
            if (iris) verifyIrisDepthConventions(terrain, target, main, buffers);
            if (!iris) verifyWaterMaskBoundary(terrain, target, main, buffers, textures);
            assertEquals(GL_NO_ERROR, glGetError());
            System.out.println("PASS: production surface shader (Iris=" + iris + "): real/prediction occlusion, above/below terrain, moving cameras, 70/7 degree FOV, atlas binding, compiled-air rejection, closed cliffs and exact 65536-block tile edges");
        } finally {
            for (int buffer : buffers) if (buffer != 0) glDeleteBuffers(buffer);
            for (int texture : textures) if (texture != 0)
                com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(texture);
            target.destroyBuffers();
            main.destroyBuffers();
            RenderSystem.activeTexture(GL_TEXTURE0);
        }
    }

    private static void verifySharedVoxyFog(int vao) {
        String fragment = """
                #version 150
                uniform sampler2D colourTex;
                uniform vec3 SurfacePoint;
                in vec2 UV;
                out vec4 colour;
                vec3 rev3d(vec3 point) { return SurfacePoint; }
                void main() {
                    vec3 point = rev3d(vec3(UV, 0));
                    colour = texture(colourTex, UV.xy);
                    float dist = max(length(point.xz), abs(point.y));
                    colour.rgb = mix(colour.rgb, vec3(.65, .75, .9), smoothstep(1200, 1536, dist));
                }
                """;
        assertEquals(fragment, PredictionFogBridge.patch("voxy:other.frag", fragment));
        String patched = PredictionFogBridge.patch("voxy:post/blit_texture_depth_cutout.frag", fragment);
        var installedFixture = java.nio.file.Path.of("build/reports/water-handoff/voxy-installed-final-blit.frag");
        if (java.nio.file.Files.exists(installedFixture)) {
            try {
                String installed = java.nio.file.Files.readString(installedFixture);
                String changed = PredictionFogBridge.patch("voxy:post/blit_texture_depth_cutout.frag", installed);
                assertNotEquals(installed, changed, "the installed Voxy shader must match the bridge contract");
                try (var linked = GlProgram.link("installed_voxy_handoff", """
                        #version 450 core
                        out vec2 UV;
                        void main() { UV = vec2(0); gl_Position = vec4(0,0,0,1); }
                        """, changed)) {
                    assertTrue(linked.uniform("VssPredictionFogEnabled") >= 0);
                }
                System.out.println("PASS: patched installed Voxy final-blit GLSL compiles and links (Sodium fog helper stub)");
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        try (var program = GlProgram.link("voxy_fog_handoff", """
                #version 150
                out vec2 UV;
                void main() {
                    vec2 corner = vec2(gl_VertexID == 1 ? 3 : -1, gl_VertexID == 2 ? 3 : -1);
                    UV = corner * .5 + .5; gl_Position = vec4(corner, 0, 1);
                }
                """, patched)) {
            var target = new TextureTarget(16, 16, false, false);
            int texture = texture(0, GL_RGBA8, 1, 1, GL_RGBA, new float[]{.2F,.4F,.1F,1});
            try {
                target.bindWrite(true); program.use(); glBindVertexArray(vao);
                RenderSystem.disableBlend(); RenderSystem.disableDepthTest(); RenderSystem.disableCull();
                glUniform1i(program.uniform("colourTex"), 0);
                PredictionFogBridge.bind(glGetInteger(GL_CURRENT_PROGRAM), 36044.8F, 65536, 512,
                        (float) (Math.log(2) / 65536), new float[]{.65F,.75F,.9F,1});
                for (boolean enabled : new boolean[]{false, true}) for (float height : new float[]{0, 4700, 20000}) {
                    glUniform1i(program.uniform("VssPredictionFogEnabled"), enabled ? 1 : 0);
                    glUniform3f(program.uniform("SurfacePoint"), 500, -height, 0);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    ByteBuffer color = BufferUtils.createByteBuffer(4); glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, color);
                    int red = color.get(0) & 255;
                    if (enabled || height == 0) assertEquals(51, red, 1, "nearby terrain keeps its color at altitude with shared horizontal fog");
                    else assertEquals(166, red, 1, "disabling prediction restores the Voxy fog rule");
                }
                glUniform1i(program.uniform("VssPredictionFogEnabled"), 1);
                glUniform3f(program.uniform("SurfacePoint"), 65536, -4700, 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                ByteBuffer color = BufferUtils.createByteBuffer(4); glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, color);
                assertEquals(166, color.get(0) & 255, 1, "the configured prediction horizon still fades into fog");
                System.out.println("PASS: shared normal Voxy/prediction fog at heights 0/4700/20000, horizon fade and original Voxy fallback");
            } finally {
                target.destroyBuffers(); com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(texture);
                glBindVertexArray(0);
            }
        }
    }

    private static void verifyPlanarHandoff(PredictionTerrainProgram terrain, TextureTarget target,
                                             TextureTarget main, int[] buffers, boolean fluid) {
        int y = 32768 + 64 * 4;
        int[] water = {16384 << 16, 16384, 0, 16384 | (16384 << 16), y | (y << 16), y | (y << 16),
                1 | (fluid ? 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT : 0), 0xBF4D13, 0, 0x1BF4D13, 0xBF4D13, 0xBF4D13};
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]); glBufferData(GL_TEXTURE_BUFFER, water, GL_STATIC_DRAW);
        int originalDepth = texture(5, GL_R32F, 64, 64, GL_RED, new float[4096]);
        try {
            for (float fov : new float[]{70, 7, 1}) for (float height : new float[]{65.6F, 68.1F, 128, 181.07F, 4700, 4701.37F, 8191.3F})
                    for (double pitch : height < 200 ? new double[]{Math.PI / 2 - .013, fov < 10 ? .1 : .7}
                            : new double[]{Math.PI / 2 - .013}) {
                var vanilla = new Matrix4f().perspective((float) Math.toRadians(fov), 1, .05F, 320);
                var projection = VssLodProjection.of(vanilla);
                var view = new Matrix4f().lookAlong(0, (float) -Math.sin(pitch), (float) -Math.cos(pitch), 0, 1, 0);
                var voxyMvp = new Matrix4f().perspective((float) Math.toRadians(fov), 1, 16, 131072).mul(view);
                var mainMvp = new org.joml.Matrix4d(vanilla).mul(new org.joml.Matrix4d(view));
                var inverse = new org.joml.Matrix4d(voxyMvp).invert();
                terrain.setCamera(view, projection.matrix()); terrain.setTile(-8192, -height, -8192, 16384, 1, false);
                terrain.setOpaqueAlpha(fluid ? 0 : 1);
                for (float below : (fluid ? new float[]{0, 4, 4000} : new float[]{0, 32, 4000})) {
                    float[] depths = new float[4096];
                    float[] mainDepths = new float[4096];
                    for (int py = 0; py < 64; py++) for (int px = 0; px < 64; px++) {
                        var ray = inverse.transformProject(new org.joml.Vector3d((px + .5) / 32 - 1, (py + .5) / 32 - 1, 0));
                        ray.mul((64 - height - below) / ray.y);
                        var clip = new org.joml.Matrix4d(voxyMvp).transformProject(new org.joml.Vector3d(ray));
                        depths[py * 64 + px] = (float) (Math.rint((clip.z * .5 + .5) * 16777215) / 16777215);
                        var mainClip = mainMvp.transformProject(new org.joml.Vector3d(ray));
                        // Match Voxy's final far clamp, but retain actual depth
                        // inside vanilla's range instead of forcing every case
                        // into the original-depth recovery branch.
                        mainDepths[py * 64 + px] = (float) (Math.rint(Math.min(mainClip.z * .5 + .5,
                                1.0 - 1.0 / 16777215) * 16777215) / 16777215);
                    }
                    RenderSystem.activeTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, originalDepth);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 64, 64, GL_RED, GL_FLOAT, depths);
                    main.bindWrite(true); glClearDepth(1.0 - 1.0 / 16777215); glClear(GL_DEPTH_BUFFER_BIT);
                    RenderSystem.activeTexture(GL_TEXTURE7); RenderSystem.bindTexture(main.getDepthTextureId());
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 64, 64, GL_DEPTH_COMPONENT, GL_FLOAT, mainDepths);
                    target.bindWrite(true); terrain.bindMainDepth(main.getDepthTextureId(), projection);
                    terrain.bindVoxyDepth(new PredictionVoxyDepth.Frame(originalDepth, 0, 64, 64,
                            net.minecraft.world.phys.Vec3.ZERO, new Matrix4f(voxyMvp).invert()), new Matrix4f(vanilla).mul(view));
                    ByteBuffer pixels = terrainPixels();
                    if (height == 4700 && fov == 70 && below == 0) saveHandoffPixels(fluid ? "high-water" : "seabed", pixels);
                    int visible = 0;
                    for (int py = 8; py < 56; py++) for (int px = 8; px < 56; px++)
                        if ((pixels.get((py * 64 + px) * 4) & 255) > 50) visible++;
                    System.out.println("PLANAR_HANDOFF fluid=" + fluid + " fov=" + fov + " height=" + height + " pitch=" + pitch + " realBelow=" + below + " visible=" + visible + "/2304");
                    if (below == 0) assertEquals(0, visible, "coincident Voxy surface must win every pixel; fluid=" + fluid);
                    else assertEquals(2304, visible, "a distinct background surface must not erase foreground prediction; fluid=" + fluid);
                }
            }
        } finally {
            terrain.setOpaqueAlpha(1); terrain.bindVoxyDepth(null, new Matrix4f());
            com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(originalDepth);
        }
    }

    private static void verifyWaterMaskBoundary(PredictionTerrainProgram terrain, TextureTarget target,
                                                TextureTarget main, int[] buffers, int[] textures) {
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        int y = 32768 + 64 * 4;
        int[] water = {512 << 16, 512, 0, 512 | (512 << 16), y | (y << 16), y | (y << 16),
                1 | (1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT), 0xFFFFFF, 0, 0x1FFFFFF, 0xFFFFFF, 0xFFFFFF};
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]); glBufferData(GL_TEXTURE_BUFFER, water, GL_STATIC_DRAW);
        float alpha = 180F / 255;
        RenderSystem.activeTexture(GL_TEXTURE0); RenderSystem.bindTexture(textures[0]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_FLOAT, new float[]{1,1,1,alpha});
        RenderSystem.activeTexture(GL_TEXTURE2); RenderSystem.bindTexture(textures[2]);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 1, 1, 1, 1, GL_RGBA, GL_FLOAT, new float[]{1,1,1,alpha});
        RenderSystem.activeTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_3D, textures[6]);
        float[] compiled = new float[32 * 2 * 32]; java.util.Arrays.fill(compiled, 1);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, 32, 2, 32, 0, GL_RED, GL_FLOAT, compiled);
        glUniform3i(glGetUniformLocation(program, "VanillaMaskSize"), 32, 2, 32);
        glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), -256, -86, -256);
        glUniform1f(glGetUniformLocation(program, "VanillaRenderDistance"), 80);
        var projection = VssLodProjection.of(new Matrix4f().perspective((float) Math.toRadians(90), 1, .05F, 320));
        terrain.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), projection.matrix());
        terrain.setTile(-256, -134, -256, 512, 1, false); terrain.setOpaqueAlpha(0);
        terrain.setFrame(new float[]{0,0,0,1}, 1e7F, 2e7F, 0, 1e7F, false, 1);
        try {
            for (boolean vanillaWaterDrawn : new boolean[]{false, true}) {
                main.bindWrite(true);
                // At cutout stage only the bed exists in main depth. At the
                // translucent stage actual vanilla water has supplied its depth.
                glClearDepth(VssLodProjection.distanceToVanillaDepth(vanillaWaterDrawn ? 70 : 74, projection));
                glClear(GL_DEPTH_BUFFER_BIT);
                target.bindWrite(true); terrain.bindMainDepth(main.getDepthTextureId(), projection);
                ByteBuffer pixels = terrainPixels();
                saveHandoffPixels(vanillaWaterDrawn ? "water-after-vanilla" : "water-before-vanilla", pixels);
                int visible = 0;
                for (int i = 0; i < 4096; i++) if ((pixels.get(i * 4) & 255) > 50) visible++;
                if (vanillaWaterDrawn) assertEquals(0, visible, "finished vanilla water must own both sides of the mask radius");
                else assertTrue(visible > 100 && visible < 4000, "early water pass exposes a circular mask edge");
                System.out.println("WATER_ORDER vanillaDrawn=" + vanillaWaterDrawn + " duplicatePixels=" + visible);
            }
        } finally {
            terrain.setOpaqueAlpha(1);
            glUniform3i(glGetUniformLocation(program, "VanillaMaskSize"), 0, 0, 0);
        }
    }

    private static void saveHandoffPixels(String name, ByteBuffer pixels) {
        try {
            var directory = java.nio.file.Path.of("build/reports/water-handoff"); java.nio.file.Files.createDirectories(directory);
            var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                int p = (y * 64 + x) * 4;
                image.setRGB(x, 63 - y, (pixels.get(p) & 255) << 16 | (pixels.get(p + 1) & 255) << 8 | pixels.get(p + 2) & 255);
            }
            javax.imageio.ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static void verifyFarVoxyDepth(PredictionTerrainProgram terrain, TextureTarget target,
                                          TextureTarget main, int[] buffers) {
        // Voxy's normal final blit clamps NDC to 1 - 2/(2^24-1) BEFORE
        // converting to window depth. All geometry beyond vanilla's far plane
        // consequently has the same main depth, regardless of its distance.
        int y = 32768 + 96 * 4;
        int[] roof = {512 << 16, 512, 0, 512 | (512 << 16), y | (y << 16), y | (y << 16),
                1, 0xBF4D13, 0, 0x1BF4D13, 0xBF4D13, 0xBF4D13};
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]);
        glBufferData(GL_TEXTURE_BUFFER, roof, GL_STATIC_DRAW);
        int originalDepth = texture(5, GL_R32F, 64, 64, GL_RED, new float[64 * 64]);
        try {
        for (float fov : new float[]{70, 7}) {
            for (float farPlane : new float[]{256, 1024}) {
                var vanilla = new Matrix4f().perspective((float) Math.toRadians(fov), 1, .05F, farPlane);
                var projection = VssLodProjection.of(vanilla);
                var view = new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1);
                var voxyProjection = new Matrix4f().perspective((float) Math.toRadians(fov), 1, 16, 131072);
                terrain.setCamera(view, projection.matrix());
                terrain.setTile(-256, -(96 + 2048), -256, 512, 1, false);
                main.bindWrite(true);
                glClearDepth(1.0 - 1.0 / 16777215.0);
                glClear(GL_DEPTH_BUFFER_BIT);
                target.bindWrite(true);
                terrain.bindMainDepth(main.getDepthTextureId(), projection);
                int center = (32 * 64 + 32) * 4;
                for (int realDistance : new int[]{8192, 2048, 1536}) {
                    float[] depths = new float[64 * 64];
                    java.util.Arrays.fill(depths, (float) VssLodProjection.distanceToVanillaDepth(
                            realDistance, VssLodProjection.of(voxyProjection)));
                    RenderSystem.activeTexture(GL_TEXTURE5);
                    glBindTexture(GL_TEXTURE_2D, originalDepth);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 64, 64, GL_RED, GL_FLOAT, depths);
                    terrain.bindVoxyDepth(new PredictionVoxyDepth.Frame(originalDepth, 0, 64, 64,
                                    net.minecraft.world.phys.Vec3.ZERO, new Matrix4f(voxyProjection).mul(view).invert()),
                            new Matrix4f(vanilla).mul(view));
                    int red = terrainPixels().get(center) & 255;
                    if (realDistance > 2048) assertTrue(red > 100,
                        "prediction at 2048 blocks must cover Voxy at 8192 despite clamped main depth; far="
                                + farPlane + ", FOV=" + fov);
                    else assertEquals(0, red, "closer or coincident real Voxy must retain ownership beyond vanilla far plane");
                }
            }
        }
        } finally {
            terrain.bindVoxyDepth(null, new Matrix4f());
            com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(originalDepth);
        }
        System.out.println("PASS: original Voxy depth orders 1536/2048/8192-block surfaces beyond vanilla far=256/1024, FOV=70/7");
    }

    private static void verifyClosedCliffDuringTransition(PredictionTerrainProgram terrain,
            TextureTarget target, TextureTarget main, int[] buffers, int[] textures, boolean iris) {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        ClientColumnSample[] samples = new ClientColumnSample[9];
        int[] colors = new int[9];
        java.util.Arrays.fill(colors, 0xFF00FF00);
        for (int i = 0; i < samples.length; i++) {
            int h = i % 3 == 0 ? 64 : 66;
            samples[i] = new ClientColumnSample(h, h, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 0,
                    ClientColumnSample.FLAG_SURFACE_ONLY, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        }
        var mesh = PredictionMeshBuilder.build(samples, colors, 63, 0, 16, 3, false);
        var tile = new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(
                net.minecraft.world.level.Level.OVERWORLD, 0, 0, 4), new int[0], new int[0], samples, mesh,
                new PredictionDepthBound(64, 66), 0, 1, 2, 16);
        var packed = PredictionPackedMesh.pack(tile);
        try (var gpu = new PredictionGpuTile(tile.key())) {
            assertFalse(gpu.ensureMesh(tile), "render upload cannot pack an unprepared mesh");
            java.util.concurrent.CompletableFuture.runAsync(() -> mesh.prepareGpuPayload(tile)).join();
            assertArrayEquals(packed.quads(), mesh.gpuPayload().quads());
            assertTrue(gpu.ensureMesh(tile));
            assertSame(mesh.gpuPayload(), gpu.packed());
            assertFalse(gpu.ensureMesh(tile), "unchanged tiles cannot upload repeatedly");
            gpu.bindQuad(4);
            int texture = glGetInteger(GL_TEXTURE_BINDING_BUFFER);
            int buffer = glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_BUFFER_DATA_STORE_BINDING);
            assertTrue(texture > 0 && buffer > 0);
            glBindBuffer(GL_TEXTURE_BUFFER, buffer);
            int[] actual = new int[packed.quads().length];
            glGetBufferSubData(GL_TEXTURE_BUFFER, 0, actual);
            assertArrayEquals(packed.quads(), actual, "worker payload survives actual GPU upload in native byte order");
            glBindTexture(GL_TEXTURE_BUFFER, textures[4]);
        }
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]);
        glBufferData(GL_TEXTURE_BUFFER, packed.quads(), GL_STATIC_DRAW);
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[1]);
        glBufferData(GL_TEXTURE_BUFFER, new int[]{-1, 1, -1, 1}, GL_STATIC_DRAW);
        int[] indices = new int[packed.quadCount() * 6];
        for (int q = 0; q < packed.quadCount(); q++) {
            int[] corners = {0, 1, 2, 0, 2, 3};
            for (int i = 0; i < 6; i++) indices[q * 6 + i] = q * 4 + corners[i];
        }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffers[2]);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        RenderSystem.activeTexture(GL_TEXTURE3);
        RenderSystem.bindTexture(textures[3]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 2, 2, 0, GL_RED, GL_FLOAT, new float[]{1,1,1,1});
        var projection = VssLodProjection.of(new Matrix4f().perspective((float) Math.toRadians(55), 1, .05F, 65536));
        terrain.setCamera(new Matrix4f().lookAlong(40, -13, 0, 0, 1, 0), projection.matrix());
        if (iris) terrain.setIrisFrame(new Matrix4f(projection.matrix()).invert(), 64, 64, false, new int[256]);
        main.bindWrite(true);
        glClearDepth(iris ? 0 : 1);
        glClear(GL_DEPTH_BUFFER_BIT);
        target.bindWrite(true);
        terrain.bindMainDepth(main.getDepthTextureId(), projection);
        terrain.setTile(24, -78, -16, 16, 2, true);
        ByteBuffer stable = terrainPixels(packed.quadCount());
        for (float morph : new float[]{.5F, 1}) {
            glUniform1f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "MorphAmount"), morph);
            ByteBuffer transition = terrainPixels(packed.quadCount());
            int exposed = 0, solid = 0;
            for (int y = 12; y < 52; y++) for (int x = 12; x < 52; x++) {
                int p = (y * 64 + x) * 4;
                if ((stable.get(p + 1) & 255) > 30) {
                    solid++;
                    if ((transition.get(p + 1) & 255) == 0) exposed++;
                }
            }
            assertTrue(solid > 300, "fixture must cover the interior of the stepped terrain");
            assertEquals(0, exposed, "neighbor columns and their connecting cliff must not separate during LOD transition; Iris=" + iris + ", morph=" + morph);
        }
    }

    private static void verifyMixedLodSeams(PredictionTerrainProgram terrain, TextureTarget target,
            TextureTarget main, int[] buffers, int[] textures, boolean iris) {
        var fine = PredictionLodSeamsTest.tile(-1, -1, 2, 64);
        var coarse = PredictionLodSeamsTest.tile(0, -1, 4, 96);
        var surfaces = java.util.List.of(PredictionLodSeamsTest.surface(fine), PredictionLodSeamsTest.surface(coarse));
        var patches = new PredictionLodSeams().update(surfaces);
        assertEquals(1, patches.size());
        for (float fov : new float[]{70, 7}) {
            var projection = VssLodProjection.of(new Matrix4f().perspective((float) Math.toRadians(fov), 1, .05F, 65536));
            terrain.setCamera(new Matrix4f().lookAlong(40, -24, 0, 0, 1, 0), projection.matrix());
            if (iris) terrain.setIrisFrame(new Matrix4f(projection.matrix()).invert(), 64, 64, false, new int[256]);
            main.bindWrite(true);
            glClearDepth(iris ? 0 : 1); glClear(GL_DEPTH_BUFFER_BIT);
            target.bindWrite(true); terrain.bindMainDepth(main.getDepthTextureId(), projection);
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_GEQUAL); RenderSystem.depthMask(true);
            glClearColor(1, 0, 0, 1); glClearDepth(0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            for (var surface : surfaces) drawSeamFixture(terrain, surface, surface.tile().mesh().gpuPayload(), buffers[2]);
            ByteBuffer pixel = BufferUtils.createByteBuffer(4);
            glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            assertEquals(255, pixel.get(0) & 255, "before stitching the two resident meshes expose the sky");
            assertEquals(0, pixel.get(1) & 255);
            saveSeamPixels("before", iris, fov);
            for (var patch : patches) drawSeamFixture(terrain, patch.surface(), patch.mesh(), buffers[2]);
            glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            assertTrue((pixel.get(1) & 255) > 20, "mixed LOD gap must be closed; Iris=" + iris + ", FOV=" + fov);
            saveSeamPixels("after", iris, fov);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.activeTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_BUFFER, textures[4]);
        RenderSystem.activeTexture(GL_TEXTURE3); RenderSystem.bindTexture(textures[3]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 1, 1, 0, GL_RED, GL_FLOAT, new float[]{1});
        System.out.println("PASS: mixed 2/4-block LOD seam reproduces sky leak before stitching and closes it at FOV=70/7 (Iris=" + iris + ")");
    }

    private static void drawSeamFixture(PredictionTerrainProgram terrain, PredictionLodSeams.Surface surface,
            PredictionPackedMesh mesh, int elementBuffer) {
        var tile = surface.tile();
        try (var gpu = new PredictionGpuTile(tile.key())) {
            RenderSystem.activeTexture(GL_TEXTURE3);
            gpu.ensureSeams(mesh); gpu.updateCoverage(surface.allowed()); gpu.bindQuad(4); gpu.bindYield(3);
            terrain.setTile(tile.baseBlockX() + 40, -104, tile.baseBlockZ() + 64, tile.spacingBlocks(), 64, true);
            int[] elements = new int[mesh.quadCount() * 6];
            int[] corners = {0, 1, 2, 0, 2, 3};
            for (int q = 0; q < mesh.quadCount(); q++) for (int c = 0; c < 6; c++) elements[q * 6 + c] = q * 4 + corners[c];
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer); glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_STATIC_DRAW);
            glDrawElements(GL_TRIANGLES, elements.length, GL_UNSIGNED_INT, 0L);
        }
    }

    private static void saveSeamPixels(String stage, boolean iris, float fov) {
        var pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
        glReadPixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            int offset = (y * 64 + x) * 4;
            image.setRGB(x, 63 - y, (pixels.get(offset) & 255) << 16
                    | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255);
        }
        try {
            var directory = java.nio.file.Path.of("build/reports/lod-seams");
            java.nio.file.Files.createDirectories(directory);
            javax.imageio.ImageIO.write(image, "png", directory.resolve(stage + "-iris-" + iris + "-fov-" + (int) fov + ".png").toFile());
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static void verifyWideTerrainEdge(PredictionTerrainProgram terrain, TextureTarget target,
            TextureTarget main, int[] buffers, boolean iris) {
        var sample = new ClientColumnSample(64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_SURFACE_ONLY, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        var samples = new ClientColumnSample[]{sample, sample, sample, sample};
        var mesh = PredictionMeshBuilder.build(samples, new int[]{0xFF00FF00, 0xFF00FF00, 0xFF00FF00, 0xFF00FF00},
                63, 0, 65536, 2, false);
        var tile = new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(
                net.minecraft.world.level.Level.OVERWORLD, 0, 0, 10), new int[0], new int[0], samples, mesh,
                new PredictionDepthBound(64, 64), 0, 1, 1, 65536);
        var packed = PredictionPackedMesh.pack(tile);
        assertEquals(1, packed.quadCount());
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]);
        glBufferData(GL_TEXTURE_BUFFER, packed.quads(), GL_STATIC_DRAW);
        var projection = VssLodProjection.of(new Matrix4f().perspective((float) Math.toRadians(55), 1, .05F, 65536));
        terrain.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), projection.matrix());
        if (iris) terrain.setIrisFrame(new Matrix4f(projection.matrix()).invert(), 64, 64, false, new int[256]);
        main.bindWrite(true);
        glClearDepth(iris ? 0 : 1);
        glClear(GL_DEPTH_BUFFER_BIT);
        target.bindWrite(true);
        terrain.bindMainDepth(main.getDepthTextureId(), projection);
        terrain.setTile(-65536, -70, -32768, 65536, 1, true);
        terrainPixels();
        terrain.setTile(0, -70, -32768, 65536, 1, true);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
        ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
        glReadPixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        for (int y = 12; y < 52; y++) for (int x = 12; x < 52; x++) {
            assertTrue((pixels.get((y * 64 + x) * 4 + 1) & 255) > 30,
                    "neighboring 65536-block tiles must meet without a shortened edge; Iris=" + iris + ",pixel=" + x + "," + y);
        }
    }

    private static void verifyWaterAndBakedUvs(PredictionTerrainProgram terrain, TextureTarget target,
            TextureTarget main, int[] buffers, int[] textures, boolean iris) {
        int y = 32768 + 64 * 4;
        int[] quad = {32 << 16, 32, 0, 32 | (32 << 16), y | (y << 16), y | (y << 16),
                1 | (1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT), 0xFFFFFF, 0, 0x1FFFFFF, 0xFFFFFF, 0xFFFFFF};
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]); glBufferData(GL_TEXTURE_BUFFER, quad, GL_STATIC_DRAW);
        var projection = VssLodProjection.of(new Matrix4f().perspective((float) Math.toRadians(55), 1, .05F, 65536));
        terrain.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), projection.matrix());
        if (iris) terrain.setIrisFrame(new Matrix4f(projection.matrix()).invert(), 64, 64, false, new int[256]);
        terrain.setTile(-16, -80, -16, 32, 1, false);
        terrain.setOpaqueAlpha(0);
        float alpha = 180F / 255;
        RenderSystem.activeTexture(GL_TEXTURE0); RenderSystem.bindTexture(textures[0]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_FLOAT,
                new float[]{1,1,1,alpha, 1,1,1,alpha, 1,1,1,alpha, 1,1,1,alpha});
        RenderSystem.activeTexture(GL_TEXTURE2); RenderSystem.bindTexture(textures[2]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 2, 4, 0, GL_RGBA, GL_FLOAT,
                new float[]{0,0,0,0, 0,0,1,1, 0,0,0,0, 1,1,1,alpha,
                        0,0,0,0, .1F,.1F,.1F,.9F, 0,0,0,0, .1F,.9F,.1F,.1F});
        main.bindWrite(true);
        // A real riverbed four blocks behind the surface must not erase water.
        glClearDepth(iris ? 1.0 / 20 : VssLodProjection.distanceToVanillaDepth(20, projection));
        glClear(GL_DEPTH_BUFFER_BIT);
        target.bindWrite(true); terrain.bindMainDepth(main.getDepthTextureId(), projection);
        RenderSystem.enableBlend(); RenderSystem.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        int center = (32 * 64 + 32) * 4;
        ByteBuffer water = terrainPixels();
        assertEquals(180, water.get(center) & 255, 2, "vanilla water alpha must blend over real riverbed; Iris=" + iris);
        for (int kind : new int[]{1, 2}) {
            float resourceAlpha = kind == 1 ? .35F : 1F;
            quad[6] = 1 | (kind << PredictionPackedMesh.FLAGS_FLUID_SHIFT);
            glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]); glBufferData(GL_TEXTURE_BUFFER, quad, GL_STATIC_DRAW);
            RenderSystem.activeTexture(GL_TEXTURE0); RenderSystem.bindTexture(textures[0]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_FLOAT,
                    new float[]{1,1,1,resourceAlpha, 1,1,1,resourceAlpha, 1,1,1,resourceAlpha, 1,1,1,resourceAlpha});
            RenderSystem.activeTexture(GL_TEXTURE2); RenderSystem.bindTexture(textures[2]);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 1, 1, 1, 1, GL_RGBA, GL_FLOAT, new float[]{1,1,1,resourceAlpha});
            assertEquals(Math.round(resourceAlpha * 255), terrainPixels().get(center) & 255, 2,
                    "resource water alpha and opaque lava must survive without a hard cap; Iris=" + iris);
        }
        RenderSystem.disableBlend();
        terrain.setOpaqueAlpha(1);
        main.bindWrite(true); glClearDepth(iris ? 0 : 1); glClear(GL_DEPTH_BUFFER_BIT); target.bindWrite(true);
        quad[6] = 1 | PredictionPackedMesh.FLAG_MODEL_UV;
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]); glBufferData(GL_TEXTURE_BUFFER, quad, GL_STATIC_DRAW);
        RenderSystem.activeTexture(GL_TEXTURE0); RenderSystem.bindTexture(textures[0]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_FLOAT,
                new float[]{1,0,0,1, 0,1,0,1, 1,0,0,1, 0,1,0,1});
        ByteBuffer baked = terrainPixels();
        assertTrue((baked.get(center) & 255) > 240 && (baked.get(center + 1) & 255) < 5,
                "baked UVs must sample their narrow red texture strip, independently of world coordinates; Iris=" + iris);
        quad[6] = 0;
        for (int word : new int[]{7, 9, 10, 11}) quad[word] |= 10 << 28;
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]); glBufferData(GL_TEXTURE_BUFFER, quad, GL_STATIC_DRAW);
        if (iris) glUniform1i(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "VssTestLightmap"), 1);
        else {
            float[] light = new float[16 * 4];
            for (int i = 0; i < 16; i++) { for (int c = 0; c < 3; c++) light[i * 4 + c] = i / 15F; light[i * 4 + 3] = 1; }
            RenderSystem.activeTexture(GL_TEXTURE1); RenderSystem.bindTexture(textures[1]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 16, 0, GL_RGBA, GL_FLOAT, light);
            terrain.setFrame(new float[]{0,0,0,1}, 1e7F, 2e7F, 0, 1e7F, true, 1);
        }
        assertEquals(85, terrainPixels().get(center) & 255, 2,
                "ten water blocks reduce skylight from 15 to 5; Iris=" + iris);
        System.out.println("PASS: vanilla/resource water alpha, opaque lava, underwater light and baked model UVs (Iris=" + iris + ")");
    }

    private static int texture(int unit, int internal, int width, int height, int format, float[] values) {
        RenderSystem.activeTexture(GL_TEXTURE0 + unit);
        int texture = glGenTextures();
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(texture);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL_FLOAT, values);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    private static void verifyIrisState() {
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        PredictionIrisBridge.restoreBlend(1, GL_ONE, GL_ZERO, GL_ZERO, GL_ONE);
        glEnablei(GL_BLEND, 1);
        try (PredictionIrisBridge.State saved = new PredictionIrisBridge.State(16)) {
            assertEquals(active, glGetInteger(GL_ACTIVE_TEXTURE));
            RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(GL_GEQUAL);
            RenderSystem.disableBlend();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glActiveTexture(GL_TEXTURE0 + 15);
        }
        assertEquals(active, glGetInteger(GL_ACTIVE_TEXTURE));
        assertEquals(draw, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        assertEquals(GL_LEQUAL, glGetInteger(GL_DEPTH_FUNC));
        assertTrue(glIsEnabled(GL_DEPTH_TEST));
        assertTrue(glIsEnabledi(GL_BLEND, 1));
        assertEquals(GL_ONE, glGetIntegeri(GL_BLEND_SRC_RGB, 1));
        assertEquals(GL_NO_ERROR, glGetError(), "Iris state restore beyond Minecraft's 12 cached units");
        glDisablei(GL_BLEND, 1);
        System.out.println("PASS: Iris texture/sampler, framebuffer, depth and indexed blend state restoration");
    }

    private static void verifyIrisPrograms() {
        for (String taa : new String[]{"vec2 vssTaaShift(){return vec2(0.0);}", """
                layout(binding=5,std140) uniform ShaderUniformBindings { vec2 offset; };
                vec2 vssTaaShift(){return offset;}
                """}) {
            try (PredictionTerrainProgram program = PredictionTerrainProgram.createIris(taa, source -> source + """
                    layout(location=0) out vec4 opaqueColor;
                    layout(location=1) out vec4 materialBuffer;
                    void voxy_emitFragment(VoxyFragmentParameters p) {
                        opaqueColor=p.sampledColour*p.tinting;
                        materialBuffer=vec4(p.lightMap, float(p.face), float(p.customId));
                    }
                    """)) {
                program.use();
                program.setIrisFrame(new Matrix4f(), 64, 64, true, new int[256]);
                assertEquals(GL_NO_ERROR, glGetError(), "Voxy shader adapter contract and TAA UBO");
            }
        }
        System.out.println("PASS: Voxy MRT fragment contract and TAA shader linking");
    }

    private static void verifyIrisDepthConventions(PredictionTerrainProgram terrain,
            TextureTarget target, TextureTarget main, int[] buffers) {
        int y = 32768;
        int[] roof = {128 << 16, 128, 0, 128 | (128 << 16), y | (y << 16), y | (y << 16),
                1, 0xBF4D13, 0, 0x1BF4D13, 0xBF4D13, 0xBF4D13};
        glBindBuffer(GL_TEXTURE_BUFFER, buffers[0]);
        glBufferData(GL_TEXTURE_BUFFER, roof, GL_STATIC_DRAW);
        terrain.use();
        terrain.setOpaqueAlpha(1);
        terrain.setTile(-64, -60, -64, 128, 1, true);
        glUniform1f(glGetUniformLocation(glGetInteger(GL_CURRENT_PROGRAM), "VanillaRenderDistance"), 0);
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        int oldMode = glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_DEPTH_MODE);
        try {
            for (boolean zeroToOne : new boolean[]{false, true}) {
                org.lwjgl.opengl.GL45.glClipControl(org.lwjgl.opengl.GL45.GL_LOWER_LEFT,
                        zeroToOne ? org.lwjgl.opengl.GL45.GL_ZERO_TO_ONE : org.lwjgl.opengl.GL45.GL_NEGATIVE_ONE_TO_ONE);
                for (boolean reversed : new boolean[]{false, true}) {
                    Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 1, .05F, 64, zeroToOne);
                    if (reversed) {
                        projection.m22((zeroToOne ? -1 : 0) - projection.m22());
                        projection.m32(-projection.m32());
                    }
                    terrain.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), projection);
                    terrain.setIrisFrame(new Matrix4f(projection).invert(), 64, 64,
                            zeroToOne, new int[256], reversed ? 0 : 1);
                    main.bindWrite(true);
                    RenderSystem.depthMask(true);
                    glClearDepth(reversed ? 0 : 1);
                    glClear(GL_DEPTH_BUFFER_BIT);
                    target.bindWrite(true);
                    terrain.bindMainDepth(main.getDepthTextureId(), VssLodProjection.of(projection));
                    assertTrue((terrainPixels().get((32 * 64 + 32) * 4) & 255) > 100,
                            "sky at finite far plane must remain fillable: zeroToOne=" + zeroToOne + ", reversed=" + reversed);
                    var clip = projection.transform(new org.joml.Vector4f(0, 0, -16, 1));
                    main.bindWrite(true);
                    glClearDepth(zeroToOne ? clip.z / clip.w : clip.z / clip.w * .5 + .5);
                    glClear(GL_DEPTH_BUFFER_BIT);
                    target.bindWrite(true);
                    assertEquals(0, terrainPixels().get((32 * 64 + 32) * 4) & 255,
                            "near real terrain must still occlude prediction");
                }
            }
        } finally {
            org.lwjgl.opengl.GL45.glClipControl(org.lwjgl.opengl.GL45.GL_LOWER_LEFT, oldMode);
        }
        System.out.println("PASS: Iris standard/reverse Z and both clip ranges, sky fill and foreground occlusion");
    }

    private static ByteBuffer terrainPixels() {
        return terrainPixels(1);
    }

    private static ByteBuffer terrainPixels(int quads) {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawElements(GL_TRIANGLES, quads * 6, GL_UNSIGNED_INT, 0L);
        ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
        glReadPixels(0, 0, 64, 64, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }
}
