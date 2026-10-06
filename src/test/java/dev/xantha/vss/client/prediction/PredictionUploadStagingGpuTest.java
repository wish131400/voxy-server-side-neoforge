package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionUploadStagingGpuTest {
    @Test void workerBytesReachBothGpuBackendsEvenAfterCpuEvictionAndWithoutDecoderProgress() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(64,64,"worker upload staging regression",0,0);
        assertNotEquals(0, window);
        var workers = Executors.newSingleThreadExecutor();
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            var executorField = PredictionMeshRestore.class.getDeclaredField("WORKER"); executorField.setAccessible(true);
            var decoder = (ThreadPoolExecutor) executorField.get(null);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var gate = decoder.submit(() -> { entered.countDown(); release.await(); return true; });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (boolean compact : new boolean[]{false,true}) for (boolean fallback : new boolean[]{false,true})
                    for (boolean compressed : new boolean[]{false,true}) {
                    System.setProperty("vss.disableCompactGpu", Boolean.toString(!compact));
                    System.setProperty("vss.disableIndirect", Boolean.toString(fallback));
                    var mesh = PredictionMeshCodecTest.fixture(64); var payload = mesh.gpuPayload();
                    float[] morph = new float[(mesh.cellAxis()+1)*(mesh.cellAxis()+1)];
                    for (int i = 0; i < morph.length; i++) morph[i] = (i % 11 - 5) * .25F;
                    payload.morph(morph,60,80);
                    long epoch = PredictionUploadStaging.SHARED.epoch();
                    workers.submit(() -> {
                        assertThrows(IllegalStateException.class, GL::getCapabilities);
                        payload.prepareGpuStorage(); payload.prepareUpload(epoch);
                        return true;
                    }).get(10, TimeUnit.SECONDS);
                    assertTrue(PredictionUploadStaging.SHARED.contains(payload));
                    int[] opaque = payload.opaqueUploadWords().clone(), water = payload.waterUploadWords().clone();
                    int[] expected = Arrays.copyOf(opaque, opaque.length + (morph.length+3)/4*4);
                    for (int i = 0; i < morph.length; i++) expected[opaque.length+i] = Math.round(morph[i]*256);
                    if (compressed) {
                        payload.prepareStorage(); assertTrue(payload.compressed());
                        // Simulate normal CPU staging eviction without discarding
                        // independent native upload bytes.
                        PredictionMeshRestore.uploaded(payload); payload.releaseUploadStorage();
                        assertNull(PredictionMeshRestore.peek(payload));
                    }
                    assertTrue(payload.uploadReady());
                    var tile = tile(mesh,1);
                    try (var gpu = new PredictionGpuTile(tile.key())) {
                        assertTrue(gpu.ensureMesh(tile), "native-ready cache must not wait for decoder");
                        assertEquals(!fallback, gpu.arenaSlice() != null);
                        verifyGpu(gpu, expected, false); verifyGpu(gpu, water, true);
                        assertTrue(gpu.hasMesh(tile)); assertFalse(gpu.ensureMesh(tile));
                        assertEquals(0, PredictionMeshRestore.pendingBytes());
                    }
                    PredictionTerrainArena.SHARED.close(); PredictionQuadBufferPool.SHARED.close();
                    assertEquals(GL_NO_ERROR, glGetError());
                    System.out.printf("UPLOAD_STAGING_GPU compact=%s fallback=%s compressed=%s bytes=%d%n",
                            compact, fallback, compressed, (expected.length+(long)water.length)*4);
                }
                // Optional staging misses cannot add a frame of latency to a
                // ready raw mesh, including the immediate ownership handoff.
                var mesh = PredictionMeshCodecTest.fixture(8); mesh.gpuPayload().prepareGpuStorage();
                var tile = tile(mesh,2);
                try (var gpu = new PredictionGpuTile(tile.key())) {
                    assertFalse(PredictionUploadStaging.SHARED.contains(mesh.gpuPayload()));
                    assertTrue(mesh.gpuPayload().uploadReady()); assertTrue(gpu.ensureMesh(tile));
                    assertTrue(gpu.hasMesh(tile));
                }
            } finally { release.countDown(); gate.get(5, TimeUnit.SECONDS); }
            PredictionTerrainArena.SHARED.close(); PredictionQuadBufferPool.SHARED.close();
            PredictionMeshRestore.clear(); assertEquals(0, PredictionUploadStaging.SHARED.allocatedBytes());
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            workers.shutdownNow(); PredictionMeshRestore.clear();
            System.clearProperty("vss.disableCompactGpu"); System.clearProperty("vss.disableIndirect");
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static PredictionTileManager.PredictionTile tile(PredictionMesh mesh, long revision) {
        int n = (mesh.cellAxis()+1)*(mesh.cellAxis()+1);
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,0,0,0);
        return new PredictionTileManager.PredictionTile(key,new int[n],new int[n],new ClientColumnSample[n],mesh,
                new PredictionDepthBound(60,80),0,revision,mesh.cellAxis(),1);
    }

    private static void verifyGpu(PredictionGpuTile gpu, int[] expected, boolean water) throws Exception {
        if (expected.length == 0) return;
        var field = PredictionGpuTile.class.getDeclaredField(water ? "waterQuadAllocation" : "quadAllocation");
        field.setAccessible(true); var allocation = (PredictionQuadBufferPool.Allocation)field.get(gpu);
        int buffer; long offset;
        if (allocation != null) { buffer = allocation.buffer; offset = 0; }
        else { var slice = gpu.arenaSlice(water); assertNotNull(slice); buffer = slice.page.buffer; offset = slice.offset; }
        glBindBuffer(GL_COPY_READ_BUFFER,buffer);
        int[] actual = new int[expected.length]; glGetBufferSubData(GL_COPY_READ_BUFFER,offset,actual);
        assertArrayEquals(expected, actual); glBindBuffer(GL_COPY_READ_BUFFER,0);
    }
}
