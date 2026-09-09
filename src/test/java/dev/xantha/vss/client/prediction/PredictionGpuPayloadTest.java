package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PredictionGpuPayloadTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void packedQuadRetainsAllFourColoursBesideTheSourceCoverageFlag() {
        int[] corners = {0xFF123456, 0xFF79ABCD, 0xFFEF2345, 0xFF6789AB};
        PredictionMesh mesh = topGrid(1, 1, corners);
        int[] words = pack(mesh, 1).quads();
        assertEquals(12, words.length);
        assertEquals(corners[0] & 0xFFFFFF, words[7]);
        for (int corner = 1; corner < 4; corner++) {
            assertEquals(corners[corner] & 0xFFFFFF, words[8 + corner] & 0xFFFFFF);
        }
        assertEquals(1, words[9] >>> 24, "unmerged quads retain source-cell coverage");
        assertEquals(0, words[8]);
    }

    @Test
    void repeatedCornerGradientIsNotStretchedByGreedyMerging() {
        PredictionMesh mesh = topGrid(2, 1,
                new int[]{0xFF335533, 0xFF668866, 0xFFAACCAA, 0xFFDDEEDD});
        assertEquals(4, mesh.packed().quadCount());
        for (int quad = 0; quad < 4; quad++) {
            assertEquals(1.0F, mesh.packed().x(quad, 1) - mesh.packed().x(quad, 0));
        }
    }

    @Test
    void uniformMergedQuadKeepsPositionBasedCoverageAndAllCornerColours() {
        int color = 0xFFABCDEF;
        PredictionMesh mesh = topGrid(2, 1, new int[]{color, color, color, color});
        int[] words = pack(mesh, 1).quads();
        assertEquals(12, words.length);
        assertEquals(0, words[9] >>> 24, "a merged quad samples each covered cell by position");
        for (int index : new int[]{7, 9, 10, 11}) assertEquals(color & 0xFFFFFF, words[index]);
    }

    @Test
    void mergedAndUnmergedTerrainKeepTheirLodTextureScaleAndSpriteCount() {
        for (int spacing : new int[]{1, 4, 8, 32, 128}) {
            for (int[] colors : new int[][]{
                    {0x01777777, 0x01777777, 0x01777777, 0x01777777},
                    {0x01555555, 0x01777777, 0x01999999, 0x01AAAAAA}}) {
                PredictionPackedMesh packed = pack(topGrid(2, spacing, colors), spacing);
                assertEquals(packed.quadCount(), packed.spriteQuadCount());
                for (int offset = 6; offset < packed.quads().length; offset += 12) {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            (packed.quads()[offset] & PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE) != 0,
                            "terrain scale must survive greedy merging at spacing " + spacing);
                }
            }
        }
        assertEquals(0, pack(topGrid(1, 1,
                new int[]{0xFF777777, 0xFF777777, 0xFF777777, 0xFF777777}), 1).spriteQuadCount());
    }

    @Test
    void coarseUniformTerrainDoesNotBecomeATileSizedMaterialSlab() {
        int axis = 64;
        int[] colors = new int[axis * axis];
        java.util.Arrays.fill(colors, 0x01777777);
        PredictionQuadMesh packed = topGrid(axis, 128, colors).packed();
        for (int quad = 0; quad < packed.quadCount(); quad++) {
            assertTrue(packed.x(quad, 1) - packed.x(quad, 0) <= 256.0F);
            assertTrue(packed.z(quad, 3) - packed.z(quad, 0) <= 256.0F);
        }
        assertTrue(packed.quadCount() >= 1024,
                "coarse material must retain spatial variation instead of one slab");
    }

    @Test
    void farEndpointsRemainExactInsteadOfWrappingOrOpeningGaps() {
        for (int span : new int[]{32768, 65536, 131072, VssLodLayout.WORLD_DISTANCE_BLOCKS}) {
            PredictionMesh mesh = topGrid(1, span,
                    new int[]{0xFF557733, 0xFF557733, 0xFF557733, 0xFF557733});
            int[] words = pack(mesh, span).quads();
            int scale = 1 << ((words[6] >>> PredictionPackedMesh.XZ_SHIFT_BITS) & 15);
            assertEquals(span, (words[0] >>> 16) * scale);
            assertEquals(span, (words[1] & 0xFFFF) * scale);
            assertEquals(span, (words[3] & 0xFFFF) * scale);
            assertEquals(span, (words[3] >>> 16) * scale);
        }
    }

    @Test
    void featureFacesKeepBlockUvsBesideCoarseTerrain() {
        int[] colors = {0x01777777, 0x01777777, 0x01777777, 0x01777777};
        PredictionMesh ground = topGrid(1, 8, colors);
        PredictionMesh feature = topGrid(1, 1, colors);
        float[] positions = java.util.Arrays.copyOf(ground.positions, 54);
        float[] normals = java.util.Arrays.copyOf(ground.normals, 54);
        int[] combinedColors = new int[18];
        java.util.Arrays.fill(combinedColors, colors[0]);
        System.arraycopy(feature.positions, 0, positions, 18, 18);
        System.arraycopy(feature.normals, 0, normals, 18, 18);
        float[] side = {1, 79, 0, 1, 79, 1, 1, 80, 1, 1, 79, 0, 1, 80, 1, 1, 80, 0};
        System.arraycopy(side, 0, positions, 36, 18);
        for (int vertex = 6; vertex < 12; vertex++) positions[vertex * 3 + 1] = 80;
        for (int vertex = 12; vertex < 18; vertex++) normals[vertex * 3] = 1;
        PredictionMesh mesh = new PredictionMesh(positions, normals, combinedColors,
                new float[0], new float[0], new int[0], new boolean[1], 18, 0, 1,
                new int[]{0}, new int[]{18}, new int[]{0}, new int[]{0});
        int[] words = pack(mesh, 8).quads();
        assertEquals(36, words.length);
        int terrain = 0, features = 0, positiveSides = 0;
        for (int offset = 0; offset < words.length; offset += 12) {
            int y = (words[offset + 4] & 0xFFFF) - 32768;
            boolean scaled = (words[offset + 6] & PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE) != 0;
            if (y == 64 * 4) {
                assertTrue(scaled);
                terrain++;
            } else {
                assertFalse(scaled);
                features++;
            }
            if ((words[offset + 6] & PredictionPackedMesh.FLAG_FACE_POSITIVE) != 0) positiveSides++;
        }
        assertEquals(1, terrain);
        assertEquals(2, features);
        assertEquals(1, positiveSides);
    }

    private static PredictionPackedMesh pack(PredictionMesh mesh, int spacing) {
        var dimension = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"));
        var key = new PredictionTileManager.PredictionTileKey(dimension, 0, 0, 0);
        var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0],
                new ClientColumnSample[0], mesh, new PredictionDepthBound(64, 64),
                0L, 1L, mesh.cellAxis(), spacing);
        return PredictionPackedMesh.pack(tile);
    }

    private static PredictionMesh topGrid(int axis, int spacing, int[] cornerColors) {
        int count = axis * axis;
        float[] positions = new float[count * 18];
        float[] normals = new float[positions.length];
        int[] colors = new int[count * 6];
        int[] vertexCorners = {0, 1, 2, 0, 2, 3};
        int[] dx = {0, 1, 1, 0};
        int[] dz = {0, 0, 1, 1};
        for (int cell = 0; cell < count; cell++) {
            for (int v = 0; v < 6; v++) {
                int corner = vertexCorners[v];
                int vertex = cell * 6 + v;
                positions[vertex * 3] = (cell % axis + dx[corner]) * (float) spacing;
                positions[vertex * 3 + 1] = 64;
                positions[vertex * 3 + 2] = (cell / axis + dz[corner]) * (float) spacing;
                normals[vertex * 3 + 1] = 1;
                colors[vertex] = cornerColors[corner];
            }
        }
        return new PredictionMesh(positions, normals, colors, new float[0], new float[0],
                new int[0], new boolean[count], count * 6, 0, count);
    }

    @Test
    void packedWordsUseNativeOrderExpectedByTextureBuffer() {
        int[] words = {0x7FB23800, 0x00100020, 0x80400000};
        ByteBuffer payload = ByteBuffer.allocate(words.length * Integer.BYTES);
        PredictionGpuTile.writeQuadPayload(payload, words);
        payload.flip();

        for (int word : words) {
            assertEquals(word, payload.getInt());
        }
    }
}
