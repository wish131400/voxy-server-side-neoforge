package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionLeafStatesTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }

    @Test void settlesConnectedLeavesAndPreservesUnsupportedLeavesAndOtherProperties() {
        var source = new HashMap<BlockPos, BlockState>();
        source.put(new BlockPos(-1, 80, 0), Blocks.BIRCH_LOG.defaultBlockState());
        var wet = Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.WATERLOGGED, true)
                .setValue(LeavesBlock.PERSISTENT, true);
        for (int x = 0; x < 8; x++) source.put(new BlockPos(x, 80, 0), wet);
        source.put(new BlockPos(0, 82, 0), wet);
        var result = PredictionLeafStates.settle(Map.copyOf(source));
        for (int x = 0; x < 8; x++) {
            var state = result.get(new BlockPos(x, 80, 0));
            assertEquals(Math.min(7, x + 1), state.getValue(LeavesBlock.DISTANCE));
            assertTrue(state.getValue(LeavesBlock.PERSISTENT));
            assertTrue(state.getValue(LeavesBlock.WATERLOGGED));
            assertTrue(state.is(Blocks.BIRCH_LEAVES));
        }
        assertEquals(wet, result.get(new BlockPos(0, 82, 0)), "air gap must not acquire support");
        assertEquals(source.keySet(), result.keySet());
        assertEquals(wet, source.get(new BlockPos(0, 80, 0)), "cached input is immutable to repair");
        assertSame(result, PredictionLeafStates.settle(result), "settled disk results need no rewrite");
    }

    @Test void knownBoundarySupportSurvivesMissingChunks() {
        var leaf = Blocks.OAK_LEAVES.defaultBlockState();
        var p = new BlockPos(15, 80, 0);
        var source = Map.of(p, leaf.setValue(LeavesBlock.DISTANCE, 2), p.east(), leaf);
        var result = PredictionLeafStates.settle(source);
        assertEquals(2, result.get(p).getValue(LeavesBlock.DISTANCE));
        assertEquals(3, result.get(p.east()).getValue(LeavesBlock.DISTANCE));
    }

    @Test void fineMeshesKeepResourcePackStateVariantsEvenUnderPressure() {
        var source = new HashMap<BlockPos, BlockState>();
        for (int d = 1; d <= 7; d++) for (boolean persistent : new boolean[]{false, true}) {
            source.put(new BlockPos(d, 80, persistent ? 1 : 0), Blocks.BIRCH_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.DISTANCE, d).setValue(LeavesBlock.PERSISTENT, persistent));
        }
        for (int thinning : new int[]{1, 2, 8}) {
            var tile = PredictionVegetation.boundedTile(source, 0, 0, 16, 1, thinning);
            assertEquals(source, tile.blocks(), "distance=7/persistent=false can select a dying-leaf model");
        }
    }
}
