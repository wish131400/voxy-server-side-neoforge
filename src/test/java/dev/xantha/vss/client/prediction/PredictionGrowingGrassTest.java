package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import org.junit.jupiter.api.*;

class PredictionGrowingGrassTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }

    // BOP high grass uses these same GrowingPlantHead/Body base classes,
    // rather than BushBlock. Cave vines also bypass the old identity whitelist.
    private static BlockState body() {
        return Blocks.CAVE_VINES_PLANT.defaultBlockState();
    }

    @Test void unlistedGrowingPlantIsCrossedGeometryWithoutSolidTopOrOcclusion() {
        for (var state : List.of(body(), Blocks.CAVE_VINES.defaultBlockState())) {
            assertTrue(PredictionVegetationTraits.of(state).vegetation());
            assertFalse(PredictionVegetation.solid(state));
            assertFalse(PredictionVegetation.mergeable(state, 1));
            assertTrue(PredictionVegetation.thinGroundCover(state));
            assertEquals(1, PredictionVegetation.voxelSize(state, 8));
            var plants = PredictionVegetation.Tile.of(Map.of(new BlockPos(0, 64, 0), state), 0, 0, 1, 1, 1);
            assertFalse(plants.occupied(0, 64, 0));
            var sample = new ClientColumnSample(64, ClientColumnSample.NO_SPAN, 0, ClientColumnSample.NO_BLOCK,
                    0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                    ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            var mesh = PredictionMeshBuilder.build(new ClientColumnSample[]{sample,sample,sample,sample}, null,
                    63, 0, 1, 2, null, null, 0, 0, plants);
            assertEquals(18, mesh.vertexCount(), "one ground quad plus two plant planes, no box roof/walls");
            assertEquals(0, mesh.waterVertexCount());
        }
        assertTrue(PredictionVegetation.solid(Blocks.STONE.defaultBlockState()));
        assertTrue(PredictionVegetation.solid(Blocks.CACTUS.defaultBlockState()));
    }

    @Test void pressureThinningKeepsCompleteModdedGrassStalksAtOriginalWidth() {
        var state = body(); var blocks = new HashMap<BlockPos, BlockState>();
        for (int z = -48; z < 48; z++) for (int x = -48; x < 48; x++) for (int y = 64; y < 68; y++)
            blocks.put(new BlockPos(x, y, z), state);
        var plants = PredictionVegetation.boundedTile(blocks, -48, -48, 96, 2, 1);
        assertTrue(plants.blocks().size() < blocks.size());
        plants.blocks().forEach((p,s) -> {
            for (int y = 64; y < 68; y++) assertEquals(state, plants.blocks().get(new BlockPos(p.getX(), y, p.getZ())));
            assertFalse(plants.occupied(p.getX() + 48, p.getY(), p.getZ() + 48));
        });
    }
}
