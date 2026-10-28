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



    @Test void fullVoxelPublicationKeepsAllModdedGrassStalksAtOriginalWidth() {
        var state = body(); var blocks = new HashMap<BlockPos, BlockState>();
        for (int z = -48; z < 48; z++) for (int x = -48; x < 48; x++) for (int y = 64; y < 68; y++)
            blocks.put(new BlockPos(x, y, z), state);
        var plants = PredictionVegetation.Tile.of(blocks, -48, -48, 96, 2, 1);
        assertEquals(blocks,plants.blocks());
        plants.blocks().forEach((p,s) -> {
            for (int y = 64; y < 68; y++) assertEquals(state, plants.blocks().get(new BlockPos(p.getX(), y, p.getZ())));
        });
    }
}
