package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionDisplayLodGpuTest {
    @Test void nearFarMdiAndOwnershipHolesMatchWithProductionShader() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(128, 128, "display LOD regression", 0, 0); assertNotEquals(0, window);
        var textures = new ArrayList<Integer>();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            PredictionMeshCodecTest.bootstrap();
            int[] words = PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_CUTOUT);
            for (int q = 0; q < 1024; q++) words[q * 12 + 9] |= 1 << 24;
            // Put canonical water between original and appended opaque records.
            // Every far-layer submission now exercises the pass-local index shift.
            words = Arrays.copyOf(words, (1024 + 128) * 12);
            for (int q = 1024; q < 1152; q++) {
                System.arraycopy(words, (q - 1024) * 12, words, q * 12, 12);
                words[q * 12 + 6] = 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT;
            }
            var payload = new PredictionPackedMesh(words, 64, 1024, new int[5],new int[]{1024,0,0,0,0},
                    new int[]{1024,1152,1152,1152,1152},new int[]{128,0,0,0,0},false,0);
            payload.prepareGpuStorage();
            var source = PredictionMeshCodecTest.fixture(64);
            var mesh = PredictionMesh.restored(1024 * 6, 0, payload, source.seamMesh());
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            var tile = new PredictionTileManager.PredictionTile(key, new int[4225], new int[4225],
                    new ClientColumnSample[4225], mesh, new PredictionDepthBound(80, 80), 0, 1, 64, 1);
            try (var gpu = new PredictionGpuTile(key); var shader = PredictionTerrainProgram.createBatch();
                    var batch = new PredictionIndirectBatch()) {
                assertTrue(gpu.ensureMesh(tile));
                int vao = glGenVertexArrays(), indices = glGenBuffers(); glBindVertexArray(vao);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
                int[] elements = new int[payload.quadCount() * 6], corners = {0, 1, 2, 0, 2, 3};
                for (int q = 0; q < payload.quadCount(); q++) for (int i = 0; i < 6; i++) elements[q * 6 + i] = q * 4 + corners[i];
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements, GL_STATIC_DRAW);
                texture(textures, 0, GL_RGBA8, GL_RGBA, 1, 1, new float[]{1, 1, 1, 1});
                texture(textures, 1, GL_RGBA8, GL_RGBA, 1, 1, new float[]{1, 1, 1, 1});
                texture(textures, 2, GL_RGBA32F, GL_RGBA, 2, 2, new float[16]);
                float[] depth = new float[128 * 128]; Arrays.fill(depth, 1);
                int depthTexture = texture(textures, 7, GL_R32F, GL_RED, 128, 128, depth);
                texture(textures, 8, GL_R8, GL_RED, 1, 1, new float[]{0});
                PredictionGlState.activeTexture(GL_TEXTURE6); int volume = glGenTextures(); textures.add(volume);
                glBindTexture(GL_TEXTURE_3D, volume); glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, 1, 1, 1, 0, GL_RED, GL_FLOAT, new float[]{0});
                shader.use(); shader.setSamplers(0, 1, 2, 3, 4);
                shader.setFrame(new float[]{0, 0, 0, 1}, 1e7f, 2e7f, 0, 1e7f, false, 1);
                var projection = VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70), 1, .05f, 65536));
                shader.setCamera(new Matrix4f().lookAlong(0, -1, 0, 0, 0, -1), projection.matrix());
                shader.bindMainDepth(depthTexture, projection); shader.setOpaqueAlpha(1);
                int program = glGetInteger(GL_CURRENT_PROGRAM);
                glUniform1i(glGetUniformLocation(program, "ExactCoverage"), 8);
                glUniform3f(glGetUniformLocation(program, "ExactCoverageGrid"), 0, 0, 1);
                glUniform1i(glGetUniformLocation(program, "VanillaMask"), 6);
                glUniform3i(glGetUniformLocation(program, "VanillaMaskSize"), 1, 1, 1);
                glUniform3f(glGetUniformLocation(program, "VanillaMaskOrigin"), 1e6f, 1e6f, 1e6f);
                glUniform1fv(glGetUniformLocation(program, "DirectionalTint[0]"), new float[]{1, 1, 1, 1, 1, 1, 1});
                var camera = new Vec3(16, 115, 16);
                boolean[] allowed = new boolean[4096];
                var draw = new PredictionRenderer.Draw(tile, gpu, allowed, false, VssLodFaceGroup.ALL, 0, new AABB(0, 80, 0, 64, 80, 64));
                var target = new com.mojang.blaze3d.pipeline.TextureTarget(128, 128, true, false);
                try {
                    for (int mask = 0; mask < 3; mask++) {
                        for (int cell = 0; cell < allowed.length; cell++) allowed[cell] = mask != 1 && (mask == 0 || (cell % 64 / 8 + cell / 64 / 8) % 2 == 0);
                        gpu.updateCoverage(allowed);
                        shader.use(); shader.bindMainDepth(depthTexture, projection);
                        byte[] reference = null;
                        for (int tier = 0; tier <= 3; tier++) for (boolean mdi : new boolean[]{false, true}) {
                            target.bindWrite(true); shader.use(); glViewport(0, 0, 128, 128);
                            glDisable(GL_CULL_FACE); glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_GEQUAL); glDepthMask(true);
                            glClearColor(0, 0, 0, 1); glClearDepth(0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                            var ranges = payload.drawRanges(false, VssLodFaceGroup.ALL, tier);
                            if (mdi) { batch.begin(shader); assertTrue(batch.add(draw, ranges, camera, false, true, Long.MAX_VALUE)); batch.end(); }
                            else {
                                shader.batch(false); gpu.bindTerrain(shader); gpu.bindYield(3);
                                shader.setTile(-16, -115, -16, 1, 64, true); shader.setBoundaryReplacement(true);
                                PredictionRenderer.submitRanges(ranges, payload, false);
                            }
                            var buffer = BufferUtils.createByteBuffer(128 * 128 * 4); glReadPixels(0, 0, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                            byte[] pixels = new byte[buffer.remaining()]; buffer.get(pixels);
                            if (reference == null) reference = pixels;
                            else assertArrayEquals(reference, pixels, "mask=" + mask + " tier=" + tier + " mdi=" + mdi);
                        }
                        assertTrue(reference.length == 128 * 128 * 4, "readback must cover the complete target");
                        if (mask != 1) { int colored = 0; for (int i = 0; i < reference.length; i += 4) if (reference[i] != 0) colored++; assertTrue(colored > 100, "nonblank mask=" + mask + " colored=" + colored); }
                    }
                    assertEquals(GL_NO_ERROR, glGetError());
                    System.out.println("DISPLAY_GPU near/far tiers 0-3, direct/MDI and missing/mixed ownership are pixel-exact");
                } finally { target.destroyBuffers(); glDeleteBuffers(indices); glDeleteVertexArrays(vao); }
            }
        } finally { for (int texture : textures) glDeleteTextures(texture); glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static int texture(ArrayList<Integer> textures, int unit, int format, int type, int width, int height, float[] data) {
        PredictionGlState.activeTexture(GL_TEXTURE0 + unit); int id = glGenTextures(); textures.add(id); glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, type, GL_FLOAT, data); return id;
    }
}
