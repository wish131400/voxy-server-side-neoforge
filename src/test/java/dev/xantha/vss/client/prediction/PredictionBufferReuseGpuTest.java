package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Raw upload-policy experiment. Production packing, masks and terrain shaders are unchanged. */
@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionBufferReuseGpuTest {
    private static final int TILES = 32, W = 128, H = 64;
    private static final String VERTEX = """
            #version 150
            void main() { gl_Position=vec4(gl_VertexID==1?3:-1,gl_VertexID==2?3:-1,0,1); }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform usamplerBuffer Payload;
            uniform int Words;
            uniform int Offset;
            out vec4 fragColor;
            void main() {
                int pixel=int(gl_FragCoord.x)+int(gl_FragCoord.y)*128;
                uvec4 data=texelFetch(Payload,(pixel*97+Offset)%Words)+texelFetch(Payload,0);
                fragColor=vec4(vec3(data.xyz & uvec3(255))/255.0,1);
            }
            """;

    @Test void compareRecreationAndCapacityReuseWithIdenticalPayloads() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W, H, "VSS upload policy experiment", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            System.out.println("BUFFER_REUSE_GPU " + glGetString(GL_RENDERER) + " " + glGetString(GL_VERSION));
            int vao = glGenVertexArrays(); glBindVertexArray(vao);
            glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_DITHER);
            try (var program = GlProgram.link("buffer_reuse_experiment", VERTEX, FRAGMENT)) {
                program.use(); glUniform1i(program.uniform("Payload"), 0);
                int words = program.uniform("Words"), offset = program.uniform("Offset");
                for (int bytes : new int[]{48 * 1024, 192 * 1024, 768 * 1024}) {
                    // Alternating 75%/100% mesh sizes keep the reused capacity bounded at the maximum.
                    ByteBuffer data = BufferUtils.createByteBuffer(bytes).order(ByteOrder.nativeOrder());
                    for (int i = 0; i < bytes / 4; i++) data.putInt(i * 1664525 + 1013904223);
                    data.flip();
                    try (var recreate = new Store(0); var reuse = new Store(1); var replace = new Store(2); var fenced = new Store(3)) {
                        Store[] stores = {recreate, reuse, replace, fenced};
                        long[][] cpu = new long[4][30], complete = new long[4][30];
                        byte[][] pixels = new byte[4][];
                        for (int frame = -12; frame < 30; frame++) {
                            for (int order = 0; order < 4; order++) {
                                int mode = Math.floorMod(frame + order, 4);
                                Store store = stores[mode];
                                glViewport(0, 0, W, H); glClear(GL_COLOR_BUFFER_BIT); glFinish();
                                long start = System.nanoTime();
                                for (int tile = 0; tile < TILES; tile++) {
                                    int length = ((frame + tile) & 1) == 0 ? bytes : bytes * 3 / 4;
                                    data.limit(length);
                                    // Two updates of each tile without a fence between them exercise
                                    // the driver synchronization required by in-flight buffer reuse.
                                    for (int update = 0; update < 2; update++) {
                                        data.putInt(0, frame * 31 + tile * 7 + update);
                                        store.upload(tile, data);
                                        glBindTexture(GL_TEXTURE_BUFFER, store.textures[tile]);
                                        glUniform1i(words, length / 16); glUniform1i(offset, tile + update * 113);
                                        glViewport(tile % 8 * 16 + update * 8, tile / 8 * 16, 8, 16);
                                        glDrawArrays(GL_TRIANGLES, 0, 3);
                                    }
                                }
                                long submitted = System.nanoTime() - start;
                                glFinish(); long finished = System.nanoTime() - start;
                                if (frame >= 0) { cpu[mode][frame] = submitted; complete[mode][frame] = finished; }
                                if (frame == 0 || frame == 29) {
                                    ByteBuffer image = BufferUtils.createByteBuffer(W * H * 4);
                                    glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, image);
                                    pixels[mode] = new byte[image.remaining()]; image.get(pixels[mode]);
                                    // Verify every byte of every live buffer outside the timed interval.
                                    ByteBuffer actual = BufferUtils.createByteBuffer(bytes);
                                    for (int tile = 0; tile < TILES; tile++) {
                                        int length = ((frame + tile) & 1) == 0 ? bytes : bytes * 3 / 4;
                                        data.limit(length); data.putInt(0, frame * 31 + tile * 7 + 1);
                                        actual.clear().limit(length);
                                        glBindBuffer(GL_TEXTURE_BUFFER, store.buffers[tile]);
                                        glGetBufferSubData(GL_TEXTURE_BUFFER, 0, actual);
                                        assertEquals(data, actual, "payload bytes must remain identical");
                                    }
                                }
                            }
                            if (frame == 0 || frame == 29) {
                                assertArrayEquals(pixels[0], pixels[1], "in-flight capacity-reused draws must match");
                                assertArrayEquals(pixels[0], pixels[2], "in-flight replaced-storage draws must match");
                                assertArrayEquals(pixels[0], pixels[3], "production fenced pool must preserve in-flight draws");
                            }
                        }
                        for (long[] times : cpu) Arrays.sort(times);
                        for (long[] times : complete) Arrays.sort(times);
                        for (int mode = 0; mode < 4; mode++) System.out.printf(Locale.ROOT,
                                "UPLOAD_POLICY maxKiB=%d updates=64 mode=%s submitMs=%.3f completeMs=%.3f p90CompleteMs=%.3f capacityRatio=%.3f bytesAndPixels=equal%n",
                                bytes / 1024, new String[]{"recreate", "capacity", "replaceStorage", "fencedPool"}[mode],
                                cpu[mode][15] / 1e6, complete[mode][15] / 1e6, complete[mode][27] / 1e6,
                                mode == 1 ? 1.0 / .875 : 1.0);
                        assertEquals(GL_NO_ERROR, glGetError());
                    }
                }
            } finally { glDeleteVertexArrays(vao); }
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static final class Store implements AutoCloseable {
        final int[] buffers = new int[TILES], textures = new int[TILES], capacity = new int[TILES];
        final PredictionQuadBufferPool.Allocation[] allocations = new PredictionQuadBufferPool.Allocation[TILES];
        final int mode;
        Store(int mode) { this.mode = mode; }
        void upload(int tile, ByteBuffer data) {
            if (mode == 3) {
                var next = PredictionQuadBufferPool.SHARED.upload(data);
                PredictionQuadBufferPool.SHARED.retire(allocations[tile]); allocations[tile] = next;
                buffers[tile] = next.buffer; textures[tile] = next.texture;
                return;
            }
            if (mode == 0 && buffers[tile] != 0) {
                glDeleteTextures(textures[tile]); glDeleteBuffers(buffers[tile]);
                textures[tile] = buffers[tile] = 0;
            }
            boolean fresh = buffers[tile] == 0;
            if (fresh) { buffers[tile] = glGenBuffers(); textures[tile] = glGenTextures(); }
            glBindBuffer(GL_TEXTURE_BUFFER, buffers[tile]);
            if (mode != 1) glBufferData(GL_TEXTURE_BUFFER, data, GL_STATIC_DRAW);
            else {
                if (data.remaining() > capacity[tile]) {
                    capacity[tile] = data.remaining();
                    glBufferData(GL_TEXTURE_BUFFER, (long) capacity[tile], GL_STATIC_DRAW);
                }
                glBufferSubData(GL_TEXTURE_BUFFER, 0, data);
            }
            if (fresh) { glBindTexture(GL_TEXTURE_BUFFER, textures[tile]); glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, buffers[tile]); }
        }
        @Override public void close() {
            if (mode == 3) {
                for (var allocation : allocations) PredictionQuadBufferPool.SHARED.discard(allocation);
                PredictionQuadBufferPool.SHARED.close();
                return;
            }
            for (int texture : textures) if (texture != 0) glDeleteTextures(texture);
            for (int buffer : buffers) if (buffer != 0) glDeleteBuffers(buffer);
        }
    }
}
