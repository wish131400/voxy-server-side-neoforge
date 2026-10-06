package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionVegetationOccupancyTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }

    @Test void tilesDefensivelyCopyBlocksAndKeepEveryViewImmutable() {
        var pos = new BlockPos(-17, 70, -33);
        var blocks = new HashMap<BlockPos, BlockState>();
        blocks.put(pos, Blocks.OAK_LOG.defaultBlockState());
        blocks.put(pos.above(), Blocks.OAK_LEAVES.defaultBlockState());
        var voxels = new ArrayList<PredictionVegetation.Voxel>();
        voxels.add(new PredictionVegetation.Voxel(15, 70, 15, 1, Blocks.OAK_LOG.defaultBlockState()));
        var cells = new HashMap<Integer, List<PredictionVegetation.Voxel>>();
        cells.put(255, voxels);
        var tile = new PredictionVegetation.Tile(cells, blocks, -32, -48, 1, 72,
                it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP,
                it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP);
        blocks.clear(); voxels.clear(); cells.clear();
        assertEquals(2, tile.blocks().size());
        assertEquals(1, tile.cell(255).size());
        assertEquals(Blocks.OAK_LOG.defaultBlockState(), tile.blocks().get(pos));
        assertThrows(UnsupportedOperationException.class,
                () -> tile.blocks().put(pos.below(), Blocks.STONE.defaultBlockState()));
        assertThrows(UnsupportedOperationException.class,
                () -> tile.blocks().entrySet().iterator().next().setValue(Blocks.STONE.defaultBlockState()));
        assertThrows(UnsupportedOperationException.class, () -> tile.cells().put(0, List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> tile.cell(255).add(new PredictionVegetation.Voxel(1, 1, 1, 1, Blocks.STONE.defaultBlockState())));
        assertTrue(PredictionVegetation.Tile.EMPTY.blocks().isEmpty());
    }

    @Test void negativeWorldCoordinatesKeepNeighborOccupancyAndAirGaps() {
        int baseX = -32, baseZ = -48;
        var blocks = Map.of(new BlockPos(-17, 70, -33), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(-16, 70, -33), Blocks.STONE.defaultBlockState(),
                new BlockPos(-17, 71, -33), Blocks.DANDELION.defaultBlockState(),
                new BlockPos(baseX - 1, 72, baseZ), Blocks.STONE.defaultBlockState());
        var tile = PredictionVegetation.Tile.of(blocks, baseX, baseZ, 32, 1, 1);
        assertTrue(tile.occupied(15, 70, 15));
        assertTrue(tile.occupied(16, 70, 15));
        assertTrue(tile.occupied(-1, 72, 0), "neighbour blocks remain available outside this tile's own cells");
        assertFalse(tile.occupied(15, 71, 15), "a non-occluding flower must not hide a face");
        for (int z = 14; z <= 16; z++) for (int x = 14; x <= 17; x++) for (int y = 69; y <= 72; y++) {
            var state = blocks.get(new BlockPos(baseX + x, y, baseZ + z));
            boolean expected = state != null && PredictionVegetation.solid(state) && PredictionSurfaceShapes.occludes(state);
            assertEquals(expected, tile.occupied(x, y, z));
        }
    }

    @Test void inputMapOrderKeepsSignaturesCompleteMeshAndPackedOutputIdentical() throws Exception {
        var blocks = mixedCanopy(-48, -64);
        var reverse = new LinkedHashMap<BlockPos, BlockState>();
        var entries = new ArrayList<>(blocks.entrySet());
        Collections.reverse(entries);
        entries.forEach(entry -> reverse.put(entry.getKey(), entry.getValue()));
        for (int spacing : new int[]{1, 2, 4}) {
            var expected = PredictionVegetation.Tile.of(Map.copyOf(blocks), -48, -64, 16, spacing, 1);
            var actual = PredictionVegetation.Tile.of(reverse, -48, -64, 16, spacing, 1);
            assertEquals(expected, actual);
            assertEquals(expected.hashCode(), actual.hashCode());
            assertArrayEquals(PredictionMeshCodec.decorationBytes(expected), actual.signatureCache().data(actual));
            var samples = PredictionRefinementOptimizationsTest.samples(16 / spacing + 1);
            var expectedMesh = build(samples, expected, spacing, false);
            var actualMesh = build(samples, actual, spacing, false);
            assertMeshEqual(expectedMesh, actualMesh, samples);
            assertMeshEqual(build(samples, expected, spacing, true), build(samples, actual, spacing, true), samples);
        }
    }

    static Map<BlockPos, BlockState> mixedCanopy(int baseX, int baseZ) {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (int center : new int[]{3, 11}) {
            for (int y = 64; y <= 73; y++) blocks.put(new BlockPos(baseX + center, y, baseZ + center), Blocks.OAK_LOG.defaultBlockState());
            for (int y : new int[]{69, 70, 72, 73}) for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dz == 0 || Math.abs(dx) + Math.abs(dz) == 4) continue;
                blocks.put(new BlockPos(baseX + center + dx, y, baseZ + center + dz),
                        center == 3 ? Blocks.OAK_LEAVES.defaultBlockState() : Blocks.BIRCH_LEAVES.defaultBlockState());
            }
        }
        blocks.put(new BlockPos(baseX + 2, 74, baseZ + 3), Blocks.SNOW.defaultBlockState());
        blocks.put(new BlockPos(baseX + 7, 64, baseZ + 7), Blocks.DANDELION.defaultBlockState());
        blocks.put(new BlockPos(baseX + 7, 63, baseZ + 7), Blocks.DIRT.defaultBlockState());
        blocks.put(new BlockPos(baseX + 8, 64, baseZ + 7), Blocks.WATER.defaultBlockState());
        return blocks;
    }

    static PredictionMesh build(ClientColumnSample[] samples, PredictionVegetation.Tile tile, int spacing, boolean rendering) {
        int grid = (int) Math.sqrt(samples.length);
        return rendering ? PredictionMeshBuilder.buildForRendering(samples, null, 63, 0xb22d78c5,
                spacing, grid, null, null, tile.baseX(), tile.baseZ(), tile,
                PredictionSimpleVegetation.Result.EMPTY, null, () -> true)
                : PredictionMeshBuilder.build(samples, null, 63, 0xb22d78c5, spacing, grid,
                null, null, tile.baseX(), tile.baseZ(), tile);
    }

    static void assertMeshEqual(PredictionMesh expected, PredictionMesh actual, ClientColumnSample[] samples) {
        assertEquals(expected.vertexCount(), actual.vertexCount());
        assertEquals(expected.waterVertexCount(), actual.waterVertexCount());
        assertEquals(expected.cellAxis(), actual.cellAxis());
        assertArrayEquals(expected.positions, actual.positions);
        assertArrayEquals(expected.normals, actual.normals);
        assertArrayEquals(expected.colors, actual.colors);
        assertArrayEquals(expected.waterPositions, actual.waterPositions);
        assertArrayEquals(expected.waterNormals, actual.waterNormals);
        assertArrayEquals(expected.waterColors, actual.waterColors);
        assertArrayEquals(expected.waterCells, actual.waterCells);
        assertArrayEquals(expected.cellOffsets, actual.cellOffsets);
        assertArrayEquals(expected.cellCounts, actual.cellCounts);
        assertArrayEquals(expected.waterOffsets, actual.waterOffsets);
        assertArrayEquals(expected.waterCounts, actual.waterCounts);
        assertArrayEquals(expected.terrainEnds, actual.terrainEnds);
        assertArrayEquals(pack(expected, samples).quads(), pack(actual, samples).quads());
    }

    static PredictionPackedMesh pack(PredictionMesh mesh, ClientColumnSample[] samples) {
        var heights = new int[samples.length]; Arrays.fill(heights, 64);
        var tile = new PredictionTileManager.PredictionTile(
                new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0), heights, heights,
                samples, mesh, new PredictionDepthBound(63, 80), 1, 1, mesh.cellAxis(), mesh.spacingBlocks);
        return PredictionPackedMesh.pack(tile);
    }
}
