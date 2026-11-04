package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.*;

class PredictionStructureTerrainTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void scopeIsBoundToGeneratorRandomAndThreadAndRestoresNestedWorld() throws Exception {
        Object generator = new Object(), random = new Object();
        var first = new PredictionStructureTerrain(generator, random);
        var second = new PredictionStructureTerrain(generator, new Object());
        assertNull(PredictionStructureTerrain.current(generator, random));
        try (var scope = first.enter()) {
            assertSame(first, PredictionStructureTerrain.current(generator, random));
            assertNull(PredictionStructureTerrain.current(new Object(), random));
            var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
            try { assertNull(workers.submit(() -> PredictionStructureTerrain.current(generator, random)).get()); }
            finally { workers.shutdownNow(); }
            try (var nested = second.enter()) { assertNull(PredictionStructureTerrain.current(generator, random)); }
            assertSame(first, PredictionStructureTerrain.current(generator, random));
        }
        assertNull(PredictionStructureTerrain.current(generator, random));
    }

    @Test void exactHeightsKeepHeightmapAndVerticalBoundsSeparate() {
        var cache = new PredictionStructureTerrain(new Object(), new Object());
        var surface = Heightmap.Types.WORLD_SURFACE_WG;
        cache.height(-10, 30, -64, 384, surface, 91);
        assertEquals(91, cache.height(-10, 30, -64, 384, surface));
        assertNull(cache.height(-10, 30, -64, 384, Heightmap.Types.OCEAN_FLOOR_WG));
        assertNull(cache.height(-10, 30, 0, 256, surface));
        assertNull(cache.height(-9, 30, -64, 384, surface));
    }

    @Test void columnMemoizationDoesNotExposeMutableGeneratorOrConsumerArrays() {
        var cache = new PredictionStructureTerrain(new Object(), new Object());
        var states = new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState()};
        var original = new NoiseColumn(-1, states);
        cache.column(2, -3, -1, 3, original);
        states[0] = Blocks.GOLD_BLOCK.defaultBlockState();
        var read = cache.column(2, -3, -1, 3);
        assertTrue(read.getBlock(-1).is(Blocks.STONE));
        read.setBlock(0, Blocks.GOLD_BLOCK.defaultBlockState());
        assertTrue(cache.column(2, -3, -1, 3).getBlock(0).is(Blocks.DIRT));
        assertNull(cache.column(2, -3, 0, 3));
    }
}
