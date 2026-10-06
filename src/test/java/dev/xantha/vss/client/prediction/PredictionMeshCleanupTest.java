package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.*;

class PredictionMeshCleanupTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() {
        PredictionSurfaceShapes.invalidate();
        PredictionVegetationTest.restoreTags();
    }

    @Test void waterIceAndLavaKeepEveryShorelineFaceAndEncodedNormal() {
        for (int kind = 1; kind <= 3; kind++) {
            var samples = new ClientColumnSample[9];
            Arrays.fill(samples, column(60, 0, false));
            samples[4] = column(58, kind == 3 ? 1 : kind, kind == 3);
            var mesh = PredictionMeshBuilder.build(samples, null, 63, 0xb22d78c5, 1, 3, false);
            float top = kind == 3 ? 63 : 63 - PredictionMeshBuilder.FLUID_SURFACE_DROP;
            float bottom = (float) Math.ceil(top) - 1;
            var positions = new ArrayList<Float>();
            var normals = new ArrayList<Float>();
            rectangle(positions, normals, 0, top, 1, 1, 2, 2, kind, 1);
            rectangle(positions, normals, 2, 1, 1, bottom, 2, top, kind, -1);
            rectangle(positions, normals, 1, 2, 1, bottom, 2, top, kind, 1);
            rectangle(positions, normals, 2, 2, 1, bottom, 2, top, kind, 1);
            rectangle(positions, normals, 1, 1, 1, bottom, 2, top, kind, -1);
            assertEquals(30, mesh.waterVertexCount());
            assertArrayEquals(floats(positions), mesh.waterPositions);
            assertArrayEquals(floats(normals), mesh.waterNormals);
            assertArrayEquals(new boolean[]{false, false, false, true}, mesh.waterCells);
            assertEquals(0, mesh.waterOffsets[3]);
            assertEquals(30, mesh.waterCounts[3]);
        }
    }

    @Test void boxesAndOcclusionReuseRegisteredShapesUntilReload() {
        var state = Blocks.STONE.defaultBlockState();
        PredictionSurfaceShapes.invalidate();
        var first = PredictionSurfaceShapes.boxes(state, 1);
        assertEquals(List.of(new AABB(0, 0, 0, 1, 1, 1)), first);
        assertTrue(PredictionSurfaceShapes.occludes(state));
        assertSame(first, PredictionSurfaceShapes.boxes(state, 1));
        PredictionSurfaceShapes.invalidate();
        assertTrue(PredictionSurfaceShapes.occludes(state));
        var reloaded = PredictionSurfaceShapes.boxes(state, 1);
        assertEquals(first, reloaded);
        assertNotSame(first, reloaded);
        assertEquals(List.of(new AABB(0, 0, 0, 4, 4, 4)), PredictionSurfaceShapes.boxes(state, 4));
        assertSame(reloaded, PredictionSurfaceShapes.boxes(state, 1), "coarse voxels retain the cached unit shape");
    }

    @Test void dryFineTileKeepsIdenticalGeometryThroughTheRenderingPath() {
        var samples = new ClientColumnSample[65 * 65];
        Arrays.fill(samples, column(65, 0, false));
        var triangles = PredictionMeshBuilder.build(samples, null, 63, 0, 1, 65);
        var rendered = PredictionMeshBuilder.buildForRendering(samples, null, 63, 0, 1, 65,
                null, null, 0, 0, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY,
                null, () -> true);
        assertEquals(24_576, triangles.vertexCount());
        assertEquals(triangles.vertexCount(), rendered.vertexCount());
        assertEquals(0, triangles.waterVertexCount());
        assertEquals(0, rendered.waterVertexCount());
        assertEquals(0, triangles.waterPositions.length);
        assertEquals(0, triangles.waterNormals.length);
        assertEquals(0, triangles.waterColors.length);
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, 0, 0, 0);
        for (var mesh : List.of(triangles, rendered)) mesh.prepareGpuPayload(new PredictionTileManager.PredictionTile(
                key, new int[0], new int[0], samples, mesh, new PredictionDepthBound(65, 65), 1, 1, 64, 1));
        assertArrayEquals(triangles.gpuPayload().restoreWords(), rendered.gpuPayload().restoreWords());
    }

    @Test void partialAndEmptyShapesKeepExistingOcclusionRules() {
        PredictionSurfaceShapes.invalidate();
        for (var block : List.of(Blocks.STONE, Blocks.OAK_LOG, Blocks.FARMLAND,
                Blocks.DIRT_PATH, Blocks.SNOW, Blocks.WHITE_CARPET, Blocks.AIR)) {
            var state = block.defaultBlockState();
            var boxes = PredictionSurfaceShapes.boxes(state, 1);
            assertEquals(boxes.size() == 1 && boxes.get(0).equals(new AABB(0, 0, 0, 1, 1, 1)),
                    PredictionSurfaceShapes.occludes(state), block.toString());
        }
    }

    static ClientColumnSample column(int height, int fluid, boolean ice) {
        return new ClientColumnSample(height, fluid == 0 ? height : 63, 0,
                PredictionMaterialPalette.stoneIndex(), 0, 0, 0, 0, fluid,
                ClientColumnSample.FLAG_SURFACE_ONLY | (ice ? ClientColumnSample.FLAG_ICE : 0), 0,
                PredictionMaterialPalette.stoneIndex(), PredictionMaterialPalette.stoneIndex(),
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    private static void rectangle(List<Float> positions, List<Float> normals, int axis, float plane,
                                  float u0, float v0, float u1, float v1, int kind, int direction) {
        float[] points = axis == 0 ? new float[]{u0, plane, v0, u1, plane, v0, u1, plane, v1, u0, plane, v1}
                : axis == 1 ? new float[]{plane, v1, u0, plane, v1, u1, plane, v0, u1, plane, v0, u0}
                : new float[]{u0, v1, plane, u1, v1, plane, u1, v0, plane, u0, v0, plane};
        for (int corner : new int[]{0, 1, 2, 0, 2, 3}) {
            for (int component = 0; component < 3; component++) positions.add(points[corner * 3 + component]);
            normals.add(axis == 1 ? (float) direction : 0);
            normals.add(kind / 3F);
            normals.add(axis == 2 ? (float) direction : 0);
        }
    }

    private static float[] floats(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

}
