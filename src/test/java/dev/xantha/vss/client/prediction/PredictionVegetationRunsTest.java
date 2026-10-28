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

















    private static String key(int x, int y, int z, int direction, BlockState state) {
        return x + "," + y + "," + z + "," + direction + ":" + state;
    }
}
