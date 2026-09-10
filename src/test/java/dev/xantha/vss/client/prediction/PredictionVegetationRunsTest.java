package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionVegetationRunsTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }

    @Test void mergingPreservesEveryExposedFaceAndAirGap() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int y = 70; y < 80; y++) {
            if (y != 74) blocks.put(new BlockPos(1, y, 1), Blocks.OAK_LEAVES.defaultBlockState());
            if (y % 3 == 0) blocks.put(new BlockPos(2, y, 1), Blocks.OAK_LOG.defaultBlockState());
        }
        blocks.put(new BlockPos(1, 78, 1), Blocks.BIRCH_LEAVES.defaultBlockState());
        var tile = PredictionVegetation.boundedTile(blocks, 0, 0, 4, 2, 1);
        Set<String> expected = new HashSet<>(), actual = new HashSet<>();
        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            if (pos.getY() < 72) continue;
            for (int face = 0; face <= 4; face++) {
                int dx = face == 3 ? -1 : face == 4 ? 1 : 0;
                int dz = face == 1 ? -1 : face == 2 ? 1 : 0;
                if (!blocks.containsKey(pos.offset(dx, face == 0 ? 1 : 0, dz)))
                    expected.add(key(pos.getX(), pos.getY(), pos.getZ(), face, entry.getValue()));
            }
        }
        for (int cell : tile.cells().keySet()) {
            for (var face : PredictionVegetationRuns.faces(tile, cell, (x,z) -> 72)) {
                for (int y = face.bottom(); y < face.top(); y++)
                    assertTrue(actual.add(key(face.x(), y, face.z(), face.direction(), face.state())),
                            "merged faces must not overlap");
            }
        }
        assertEquals(expected, actual);
    }

    @Test void denseFineCanopiesStayOneBlockAndFitExistingMeshBudget() {
        int span = 48, grid = span + 1;
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        // More than 800,000 unmerged vertices; old fallback expanded these into large cubes.
        for (int z = 0; z < span; z += 2) for (int x = 0; x < span; x += 2)
            for (int y = 80; y < 144; y++) blocks.put(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState());
        var tile = PredictionVegetation.boundedTile(blocks, 0, 0, span, 1, 1);
        assertEquals(1, tile.voxelSize());
        assertEquals(blocks, tile.blocks());
        assertTrue(tile.cells().values().stream().flatMap(java.util.List::stream).allMatch(v -> v.size() == 1));
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        int[] colors = new int[samples.length];
        java.util.Arrays.fill(samples, new ClientColumnSample(64, 64, 0, ClientColumnSample.NO_BLOCK,
                0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        java.util.Arrays.fill(colors, 0xff65934a);
        var mesh = PredictionMeshBuilder.build(samples, colors, 63, 0, 1, grid,
                true, null, colors, null, 0, 0, tile);
        assertTrue(mesh.vertexCount() < 100_000, "merge before triangle allocation, without increasing its hard cap");
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            if (mesh.y(vertex) <= 64) continue;
            assertTrue(mesh.y(vertex) >= 80 && mesh.y(vertex) <= 144);
        }
        var packed = mesh.packed();
        assertTrue(packed.quadCount() > 0);
        for (int quad = 0; quad < packed.quadCount(); quad++) {
            if (packed.y(quad, 0) <= 64) continue;
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                minX = Math.min(minX, packed.x(quad, corner)); maxX = Math.max(maxX, packed.x(quad, corner));
                minZ = Math.min(minZ, packed.z(quad, corner)); maxZ = Math.max(maxZ, packed.z(quad, corner));
            }
            assertTrue(maxX - minX <= 1 && maxZ - minZ <= 1,
                    "packed faces must not bridge the empty space between canopies");
        }
        System.out.println("DENSE_CANOPY blocks=" + blocks.size() + ", vertices=" + mesh.vertexCount());
    }

    @Test void adjacentStatesAreNotMergedAcrossDifferentMaterials() {
        var blocks = Map.of(new BlockPos(0, 80, 0), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(0, 81, 0), Blocks.OAK_LEAVES.defaultBlockState());
        var tile = PredictionVegetation.boundedTile(blocks, 0, 0, 2, 1, 1);
        for (var face : PredictionVegetationRuns.faces(tile, 0, (x,z) -> 64))
            assertEquals(1, face.top() - face.bottom());
    }

    private static String key(int x, int y, int z, int direction, BlockState state) {
        return x + "," + y + "," + z + "," + direction + ":" + state;
    }
}
