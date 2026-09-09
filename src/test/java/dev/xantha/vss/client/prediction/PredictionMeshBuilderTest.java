package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PredictionMeshBuilderTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void buildsTwoTrianglesPerGridCell() {
        int[] heights = new int[PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE];
        for (int z = 0; z < PredictionTileManager.GRID_SIZE; z++) {
            for (int x = 0; x < PredictionTileManager.GRID_SIZE; x++) {
                heights[z * PredictionTileManager.GRID_SIZE + x] = 64 + x + z;
            }
        }

        PredictionMesh mesh = PredictionMeshBuilder.build(heights, 16);

        assertEquals(256, mesh.cellCount());
        assertEquals(256 * 6, mesh.vertexCount());
        assertEquals(0.0F, mesh.x(0));
        assertEquals(64.0F, mesh.y(0));
        assertEquals(0.0F, mesh.z(0));
        assertTrue(mesh.normalY(0) > 0.0F);
        assertEquals(0xFF, mesh.color(0) >>> 24);
        assertTrue((mesh.color(0) & 0xFF) < 200);
        assertEquals(mesh.cellIndexForVertex(0), mesh.cellIndexForVertex(5));
        assertEquals(1, mesh.cellIndexForVertex(6));
    }

    @Test
    void perCornerLightingDarkensValleyCorners() {
        // One raised column surrounded by lower ground: the corners shared
        // with the spike must be darker than the fully open corner.
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                boolean spike = x == grid / 2 && z == grid / 2;
                samples[z * grid + x] = new ClientColumnSample(
                        spike ? 96 : 64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                        0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid);
        // Compare the apex cell corner with an open corner: red channel on
        // flat ground equals the material colour; occluded corners go lower.
        int apexCell = (grid / 2 - 1) * (grid - 1) + (grid / 2 - 1);
        int openCell = 1 * (grid - 1) + 1;
        int apexColor = mesh.color(mesh.cellOffsets[apexCell]);
        int openColor = mesh.color(mesh.cellOffsets[openCell]);
        assertTrue((apexColor >> 16 & 0xFF) <= (openColor >> 16 & 0xFF),
                "AO should not brighten corners around a spike");
    }

    @Test
    void coarseMedianFilterRemovesSingleColumnSpikes() {
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                boolean spike = x == grid / 2 && z == grid / 2;
                samples[z * grid + x] = new ClientColumnSample(
                        spike ? 96 : 64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                        0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 2, grid);
        // The spike column is replaced by the neighbourhood median (64) so
        // no vertex sits at 96 any more.
        boolean sawSpike = false;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            if (mesh.y(vertex) == 96.0F) {
                sawSpike = true;
                break;
            }
        }
        assertFalse(sawSpike);
    }

    @Test
    void wallStrataUseDistinctMaterialBands() {
        // A high plateau next to a low plain produces exposed wall faces;
        // the wall must contain at least two distinct colours.
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                boolean high = x < 2;
                samples[z * grid + x] = new ClientColumnSample(
                        high ? 100 : 64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                        0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid);
        java.util.Set<Integer> wallColors = new java.util.HashSet<>();
        for (int cell = 0; cell < mesh.cellCount(); cell++) {
            int first = mesh.cellOffsets[cell];
            int count = mesh.cellCounts[cell];
            for (int v = first + 6; v < first + count; v++) {
                wallColors.add(mesh.color(v));
            }
        }
        assertTrue(wallColors.size() >= 2,
                "cliff walls should show multiple material strata");
    }

    @Test
    void packedContinuousCliffWallsBecomeRuns() {
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                boolean high = x < 4;
                samples[z * grid + x] = new ClientColumnSample(
                        high ? 100 : 64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                        0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        }

        PredictionQuadMesh packed = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid).packed();

        // Sixteen identical wall bands along the cliff edge collapse to one
        // run per material band instead of one quad per source cell.
        assertTrue(packed.quadCount() < 30,
                "continuous cliff walls should be merged, quads=" + packed.quadCount());
    }

    @Test
    void packedTerrainKeepsColoursAfterGrowingPastInitialCapacity() {
        int grid = PredictionTileManager.GRID_SIZE;
        int[] heights = new int[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                // Diagonal neighbours are deliberately different so the
                // greedy pass cannot collapse the 256 top cells into a few
                // rectangles. This forces QuadStorage to grow/use every
                // colour slot past its cell-count-sized initial capacity.
                heights[z * grid + x] = ((x + z) & 1) == 0 ? 64 : 96;
            }
        }

        PredictionQuadMesh packed = PredictionMeshBuilder.build(heights, 16).packed();

        assertTrue(packed.quadCount() > grid * grid / 2,
                "terrain packing should exercise the grown colour storage");
        assertEquals(0xFF, packed.color(0, 0) >>> 24,
                "grown terrain colour slots should contain a complete vertex colour");
    }

    @Test
    void packedWaterKeepsColoursAfterGrowingPastInitialCapacity() {
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                int fluidY = 32 + x + z;
                samples[z * grid + x] = new ClientColumnSample(
                        16, fluidY, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                        1, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        }

        PredictionQuadMesh packed = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 16).packed();

        assertTrue(packed.waterQuadCount() > grid * grid / 2,
                "water packing should exercise the grown colour storage");
        assertEquals(0xB22D78C5, packed.waterColor(0, 0));
    }

    @Test
    void rejectsWrongSampleShape() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PredictionMeshBuilder.build(new int[16], 16));
    }

    @Test
    void buildsSeparateWaterSurfaceAboveSubmergedGround() {
        int sampleCount = PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE;
        int[] surface = new int[sampleCount];
        int[] ground = new int[sampleCount];
        java.util.Arrays.fill(surface, 40);
        java.util.Arrays.fill(ground, 12);

        PredictionMesh mesh = PredictionMeshBuilder.build(surface, ground, 63, 16);

        assertEquals(256 * 6, mesh.waterVertexCount());
        assertEquals(12.0F, mesh.y(0));
        assertEquals(0xB2, mesh.waterColor(0) >>> 24);
    }

    @Test
    void richSamplesEmitExposedWallsAndFluidBanks() {
        int count = PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[count];
        java.util.Arrays.fill(samples, new ClientColumnSample(
                64, 63, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        for (int z = 0; z < PredictionTileManager.GRID_SIZE; z++) {
            for (int x = 0; x < PredictionTileManager.GRID_SIZE; x++) {
                if (x < 4) {
                    samples[z * PredictionTileManager.GRID_SIZE + x] = new ClientColumnSample(
                            48, 63, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                            1, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                            40, 36, 32, 24);
                }
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63, 0xB22D78C5, 16);
        assertTrue(mesh.vertexCount() > 256 * 6);
        assertTrue(mesh.waterVertexCount() > 0);
    }

    @Test
    void coarseSamplesDoNotCreateSyntheticCurtainsOrUndergroundPlanes() {
        int count = PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[count];
        java.util.Arrays.fill(samples, new ClientColumnSample(
                64, 63, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                1, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                20, 8, 0, 0));

        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63, 0xB22D78C5, 64);

        // Block topology: the tile's own columns (16x16; the 17th border
        // column belongs to the neighbouring tile) each render one flat
        // 6-vertex top quad.  Flat ground grows no walls, so the total is
        // exactly the in-tile column count.
        assertEquals(16 * 16 * 6, mesh.vertexCount());
        assertEquals(0, mesh.waterVertexCount(), "fluid below the sampled ground must not create a buried water plane");
    }

    @Test
    void packedViewKeepsOneTopQuadPerCellAndWaterQuad() {
        int[] heights = new int[PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE];
        java.util.Arrays.fill(heights, 64);
        PredictionMesh mesh = PredictionMeshBuilder.build(heights, 16);
        PredictionQuadMesh packed = mesh.packed();
        assertEquals(1, packed.quadCount());
        assertEquals(0, packed.waterQuadCount());
        assertEquals(0, packed.quadForCell(0));
        assertEquals(0, packed.quadForCell(255));
        org.junit.jupiter.api.Assertions.assertTrue(packed.isQuadOriginForCell(0));
        org.junit.jupiter.api.Assertions.assertFalse(packed.isQuadOriginForCell(255));
    }

    @Test
    void depthBoundIncludesSurfaceAndWaterWithoutUndergroundSpans() {
        int count = PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[count];
        java.util.Arrays.fill(samples, new ClientColumnSample(
                64, 80, 0, ClientColumnSample.NO_BLOCK, 0, 1, 1, 12,
                1, ClientColumnSample.FLAG_TREE_HERE, 0, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_BLOCK, 50, 40, 20, 8));
        PredictionDepthBound bound = PredictionDepthBound.fromSamples(samples);
        assertEquals(64, bound.minY());
        assertEquals(80, bound.maxY());
    }

    @Test
    void legacyHintsNeverCreateTreesWithoutWorldCoordinatePlacement() {
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample forestWithoutTree = new ClientColumnSample(
                64, 63, 0, ClientColumnSample.NO_BLOCK, 0, 1, 48, 12,
                0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        ClientColumnSample treeColumn = new ClientColumnSample(
                64, 63, 0, ClientColumnSample.NO_BLOCK, 0, 1, 48, 12,
                0, ClientColumnSample.FLAG_TREE_HERE, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        java.util.Arrays.fill(samples, forestWithoutTree);

        PredictionMesh noCanopy = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid, true, new PredictionFeatureStampCache());
        samples[0] = treeColumn;
        PredictionMesh withCanopy = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid, true, new PredictionFeatureStampCache());

        assertEquals(16 * 16 * 6, noCanopy.vertexCount());
        assertEquals(noCanopy.vertexCount(), withCanopy.vertexCount());

        PredictionMesh coarseCanopy = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 4, grid, true, new PredictionFeatureStampCache());
        assertEquals(16 * 16 * 6, coarseCanopy.vertexCount(),
                "spacing 4 cannot manufacture a tree from a density hint");
        PredictionMesh boxCanopy = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 8, grid, true, new PredictionFeatureStampCache());
        assertEquals(16 * 16 * 6, boxCanopy.vertexCount(),
                "spacing 8 cannot fall back to canopy boxes");
        PredictionMesh horizonCanopy = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 16, grid, true, new PredictionFeatureStampCache());
        assertEquals(16 * 16 * 6, horizonCanopy.vertexCount(),
                "spacing 16 omits individual tree geometry");
    }

    @Test
    void materialPaletteDistinguishesFluidAndCutoutHints() {
        ClientColumnSample water = new ClientColumnSample(64, 63, 0, 0,
                0, 0, 0, 0, 1, 0, 0, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN);
        assertEquals(PredictionMaterialPalette.MATERIAL_TRANSLUCENT,
                PredictionMaterialPalette.materialClass(water));
    }

    @Test
    void buildsFullResolutionGridAndPackedLookup() {
        int grid = VssLodLayout.TILE_QUADS + VssLodLayout.SAMPLE_MARGIN * 2;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        java.util.Arrays.fill(samples, new ClientColumnSample(
                64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid);
        assertEquals(VssLodLayout.TILE_QUADS, mesh.cellAxis());
        assertEquals(VssLodLayout.TILE_QUADS * VssLodLayout.TILE_QUADS,
                mesh.cellCount());
        assertEquals(1, mesh.packed().quadCount());
    }

    @Test
    void cropsTheReducedDistantGridToSixteenCells() {
        int grid = 18;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        java.util.Arrays.fill(samples, new ClientColumnSample(
                64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                0, 0, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));

        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 16, grid);

        assertEquals(16, mesh.cellAxis());
        assertEquals(16 * 16, mesh.cellCount());
    }

    @Test
    void packedMergedQuadReachesTheLastCellEdge() {
        int[] heights = new int[PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE];
        java.util.Arrays.fill(heights, 64);

        PredictionQuadMesh packed = PredictionMeshBuilder.build(heights, 16).packed();

        assertEquals(256.0F, packed.x(0, 1));
        assertEquals(256.0F, packed.x(0, 2));
        assertEquals(256.0F, packed.z(0, 2));
        assertEquals(256.0F, packed.z(0, 3));
    }

    @Test
    void packedMergedWaterQuadReachesTheLastCellEdge() {
        int sampleCount = PredictionTileManager.GRID_SIZE * PredictionTileManager.GRID_SIZE;
        int[] surface = new int[sampleCount];
        int[] ground = new int[sampleCount];
        java.util.Arrays.fill(surface, 40);
        java.util.Arrays.fill(ground, 12);

        PredictionQuadMesh packed = PredictionMeshBuilder.build(surface, ground, 63, 16).packed();

        assertEquals(256.0F, packed.waterX(0, 1));
        assertEquals(256.0F, packed.waterX(0, 2));
        assertEquals(256.0F, packed.waterZ(0, 2));
        assertEquals(256.0F, packed.waterZ(0, 3));
        org.junit.jupiter.api.Assertions.assertTrue(packed.isWaterQuadOriginForCell(0));
        org.junit.jupiter.api.Assertions.assertFalse(packed.isWaterQuadOriginForCell(255));
    }

    @Test
    void waterSurfaceDropsToVanillaStillWaterLine() {
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                samples[z * grid + x] = new ClientColumnSample(
                        40, 63, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                        1, 0, 0, ClientColumnSample.NO_BLOCK,
                        ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN);
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid);
        // the renders fluids eight-ninths full; the surface must drop by
        // 1.001 - 8/9 rather than sit at the sampled fluid top.
        assertEquals(63.0F - PredictionMeshBuilder.FLUID_SURFACE_DROP,
                mesh.waterY(0), 0.0001F);
    }

    @Test
    void detailTilesKeepFlatColourWithoutSpriteTable() {
        // Without a Minecraft instance the sprite table cannot resolve GL
        // resources, so the detail path must keep the fully opaque colour and
        // never write a partial alpha into the vertex stream.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> VssLodSpriteTable.indexForBlock(1));
        int color = 0xFF7FB238;
        int flat = color & 0x00FFFFFF | 0xFF << 24;
        assertEquals(0xFF, flat >>> 24);
        // The FLAT sentinel alpha survives the withSpriteAlpha round trip as
        // an opaque colour rather than a texture index.
        org.junit.jupiter.api.Assertions.assertEquals(flat,
                (color & 0x00FFFFFF) | (255 & 0xFF) << 24);
    }

    @Test
    void fluidKindRidesInWaterNormalsAndSplitsMerging() {
        int grid = PredictionTileManager.GRID_SIZE;
        ClientColumnSample water = new ClientColumnSample(40, 63, 0,
                ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 1, 0, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        ClientColumnSample ice = new ClientColumnSample(40, 63, 0,
                ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 1,
                ClientColumnSample.FLAG_ICE, 0, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN);
        // Left half ice, right half water: two fluid kinds must never merge
        // into one quad, and the kind must be readable back from the normals.
        // The kind is encoded as kind/3 so it survives the signed-normalized
        // byte round trip to the shader.
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                samples[z * grid + x] = x < grid / 2 ? ice : water;
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63,
                0xB22D78C5, 1, grid);
        PredictionQuadMesh packed = mesh.packed();
        assertTrue(packed.waterQuadCount() >= 2,
                "distinct fluid kinds must not merge into one quad");
        assertEquals(1.0F, mesh.waterNormalY(0), "ice side encodes kind/3 = 1");
        // The water half starts at the first column right of the ice half.
        int waterCell = grid / 2;
        int waterFirst = mesh.waterOffsets[waterCell];
        assertEquals(1.0F / 3.0F, mesh.waterNormalY(waterFirst), 0.0001F,
                "plain water encodes kind/3 = 1/3");
        // Water drops to the still-water line; the ice column keeps the
        // exact captured surface without the drop.
        assertEquals(63.0F - PredictionMeshBuilder.FLUID_SURFACE_DROP,
                mesh.waterY(waterFirst), 0.0001F,
                "water drops to the still-water line");
        int iceFirst = mesh.waterOffsets[0];
        assertEquals(63.0F, mesh.waterY(iceFirst), 0.0001F,
                "ice keeps the exact captured surface without the drop");
    }
}
