package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionCompressedUploadGpuTest {
    @Test void bothGpuStoragePathsRestoreExactWordsAndKeepOldMeshWhileDecoderWaits() throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(64, 64, "compressed upload regression", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            for (boolean compact : new boolean[]{false, true}) for (boolean fallback : new boolean[]{false, true}) {
            System.setProperty("vss.disableIndirect", Boolean.toString(fallback));
            var mesh = PredictionMeshCodecTest.fixture(64);var payload = mesh.gpuPayload();
            assertTrue(payload.quadBytes() >= PredictionMeshCompression.MIN_BYTES);
            int[] canonical = payload.quads().clone();
            if (compact) { payload.prepareGpuStorage(); assertTrue(payload.paletteBaseTexel() > 0); }
            int[] original = payload.uploadWords().clone();
            assertArrayEquals(canonical, payload.quads());payload.prepareStorage();assertTrue(payload.compressed());
            int n = (mesh.cellAxis()+1)*(mesh.cellAxis()+1);
            var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,0,0,0);
            var tile = new PredictionTileManager.PredictionTile(key,new int[n],new int[n],new ClientColumnSample[n],mesh,
                    new PredictionDepthBound(60,80),0,1,mesh.cellAxis(),1);
            try (var gpu = new PredictionGpuTile(key)) {
                assertTrue(gpu.ensureMesh(tile));assertEquals(!fallback, gpu.arenaSlice() != null);assertTrue(gpu.hasMesh(tile));assertEquals(0,PredictionMeshRestore.readyBytes());
                verifyGpu(gpu,original);
                // An unchanged draw never requests decompression.
                assertFalse(gpu.ensureMesh(tile));assertEquals(0,PredictionMeshRestore.pendingBytes());
                PredictionMeshRestore.clear();
                var next = new PredictionTileManager.PredictionTile(key,new int[n],new int[n],new ClientColumnSample[n],mesh,
                        new PredictionDepthBound(60,80),0,2,mesh.cellAxis(),1);
                var executorField=PredictionMeshRestore.class.getDeclaredField("WORKER");executorField.setAccessible(true);
                var executor=(java.util.concurrent.ThreadPoolExecutor)executorField.get(null);
                var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
                var gate=executor.submit(()->{entered.countDown();release.await();return true;});
                try {
                    assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                    assertFalse(gpu.ensureMesh(next));assertTrue(gpu.hasMesh(tile));assertFalse(gpu.hasMesh(next));
                    verifyGpu(gpu,original);
                } finally {release.countDown();gate.get();}
                PredictionMeshCompressionTest.awaitUpload(payload);
                assertTrue(gpu.ensureMesh(next));assertTrue(gpu.hasMesh(next));verifyGpu(gpu,original);
            }
            PredictionTerrainArena.SHARED.close();PredictionQuadBufferPool.SHARED.close();
            assertEquals(GL_NO_ERROR,glGetError());
            }
        } finally {System.clearProperty("vss.disableIndirect");PredictionMeshRestore.clear();glfwDestroyWindow(window);glfwTerminate();}
    }
    private static void verifyGpu(PredictionGpuTile gpu,int[] expected)throws Exception {
        var allocation=PredictionGpuTile.class.getDeclaredField("quadAllocation");allocation.setAccessible(true);
        var value=allocation.get(gpu);
        int buffer;long offset;
        if(value!=null) {
            buffer=((PredictionQuadBufferPool.Allocation)value).buffer;offset=0;
        } else {
            var slice=gpu.arenaSlice();assertNotNull(slice);
            buffer=slice.page.buffer;offset=slice.offset;
        }
        glBindBuffer(GL_COPY_READ_BUFFER,buffer);int[] actual=new int[expected.length];
        glGetBufferSubData(GL_COPY_READ_BUFFER,offset,actual);assertArrayEquals(expected,actual);
        glBindBuffer(GL_COPY_READ_BUFFER,0);
    }
}
