package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

@EnabledIfSystemProperty(named = "vss.gpuTests", matches = "true")
class PredictionSegmentedMeshGpuTest {
    @Test void denseForestUploadsEveryQuadThroughBothStoragePaths() throws Exception {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        long window = glfwCreateWindow(64, 64, "segmented forest upload", 0, 0);
        assertNotEquals(0, window);
        String previous = System.getProperty("vss.disableIndirect");
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            PredictionVegetationTest.bootstrap();
            int span = 64;
            var blocks = new HashMap<BlockPos, BlockState>();
            for (int z = 0; z < span; z += 2) for (int x = 0; x < span; x += 2)
                for (int y = 80; y < 112; y += 2)
                    blocks.put(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState());
            var plants = PredictionVegetation.Tile.of(blocks, 0, 0, span, 1, 1);
            var samples = PredictionRefinementOptimizationsTest.samples(span + 1);
            var mesh = PredictionRefinementOptimizationsTest.build(samples, span + 1, plants);
            assertTrue(mesh.vertexCount() > 262_144);
            var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, 0, 0, 0);
            var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], samples, mesh,
                    new PredictionDepthBound(64, 112), 1, 1, span, 1);
            mesh.prepareGpuPayload(tile);
            var payload = mesh.gpuPayload();
            assertTrue(payload.terrainQuadCount() >= blocks.size() * 5);
            int[] canonical = payload.restoreWords();
            payload.prepareGpuStorage();
            assertArrayEquals(canonical, payload.restoreWords());
            int[] expected = payload.restoreUploadWords();
            for (boolean fallback : new boolean[]{false, true}) {
                System.setProperty("vss.disableIndirect", Boolean.toString(fallback));
                try (var gpu = new PredictionGpuTile(key)) {
                    assertTrue(gpu.ensureMesh(tile));
                    assertEquals(!fallback, gpu.arenaSlice() != null);
                    assertEquals(payload.quadCount(), gpu.quadCount());
                    var allocation = PredictionGpuTile.class.getDeclaredField("quadAllocation");
                    allocation.setAccessible(true);
                    var pooled = (PredictionQuadBufferPool.Allocation) allocation.get(gpu);
                    var slice = gpu.arenaSlice();
                    glBindBuffer(GL_COPY_READ_BUFFER, pooled == null ? slice.page.buffer : pooled.buffer);
                    int[] actual = new int[expected.length];
                    glGetBufferSubData(GL_COPY_READ_BUFFER, pooled == null ? slice.offset : 0, actual);
                    assertArrayEquals(expected, actual);
                    glBindBuffer(GL_COPY_READ_BUFFER, 0);
                    assertFalse(gpu.ensureMesh(tile), "unchanged geometry is not uploaded again");
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        } finally {
            if (previous == null) System.clearProperty("vss.disableIndirect");
            else System.setProperty("vss.disableIndirect", previous);
            PredictionVegetationTest.restoreTags();
            PredictionTerrainArena.SHARED.close();
            PredictionQuadBufferPool.SHARED.close();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
}
