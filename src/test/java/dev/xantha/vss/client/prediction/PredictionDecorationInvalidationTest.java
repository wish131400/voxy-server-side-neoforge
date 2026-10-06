package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionDecorationInvalidationTest {
    @BeforeAll static void bootstrap() { PredictionDecorationQueryTest.bootstrap(); }

    private static final class CountingTerrain extends ClientTerrainSampler {
        int reads;
        final ClientColumnSample sample = new PredictionDecorationQueryTest().terrain().sampleSurface(0, 0);
        CountingTerrain() { super(42, PredictionDecorationQueryTest.PROFILE); }
        @Override public ClientColumnSample sampleSurface(int x, int z) {
            return sample;
        }
        @Override boolean interiorTerrain() { reads++; return false; }
    }

    @Test void emptyFailedTransactionsRetainExistingPlacedGraphTopsAndPendingEdits() throws Exception {
        var terrain = new CountingTerrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        Map<BlockPos, BlockState> existing = new HashMap<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 104; y < 120; y++)
            existing.put(new BlockPos(x, y, z), (y & 1) == 0 ? Blocks.OAK_LOG.defaultBlockState() : Blocks.OAK_LEAVES.defaultBlockState());
        level.restoreSurface(existing);
        level.setBlock(new BlockPos(0, 130, 0), Blocks.AIR.defaultBlockState(), 0, 0);
        var expected = Map.copyOf(level.placed());
        var pending = Map.copyOf(level.pendingUploads());
        var field = PredictionDecorationLevel.class.getDeclaredField("changedTops"); field.setAccessible(true);
        int[] tops = (int[]) field.get(level), values = tops.clone();
        int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0);
        int reads = terrain.reads;
        for (int attempt = 0; attempt < 32; attempt++) {
            level.beginFeature();
            level.endFeature(false);
            assertSame(tops, field.get(level), "a failed feature without writes must not rebuild the existing graph");
            assertArrayEquals(values, (int[]) field.get(level));
            assertEquals(expected, level.placed());
            assertEquals(pending, level.pendingUploads());
            assertEquals(height, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        }
        assertEquals(reads, terrain.reads, "warm height answers must remain valid");
    }

    @Test void warmJavaColumnCannotBypassThreadInterruption() {
        var terrain = new CountingTerrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        var sample = level.column(0, 0);
        assertSame(sample, level.column(0, 0));
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> level.column(0, 0));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void unrelatedWritesRetainWarmBlockAndHeightAnswers() {
        var terrain = new CountingTerrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        var ground = new BlockPos(0, 99, 0);
        assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK));
        assertEquals(100, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        assertEquals(104, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        int reads = terrain.reads;
        var other = new BlockPos(1, 125, 1);
        level.beginFeature();
        level.setBlock(other, Blocks.OAK_LOG.defaultBlockState(), 0, 0);
        assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK));
        assertEquals(100, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        assertEquals(104, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        assertEquals(reads, terrain.reads, "another column must not force base terrain reads");
        level.endFeature(false);
        assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK));
        assertEquals(100, level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        assertEquals(reads, terrain.reads, "rollback in another column must preserve warm answers");
        level.useDisplayTerrain(true);
        assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK));
        assertTrue(terrain.reads > reads, "changing terrain view invalidates all block answers");
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> level.getBlockState(ground));
            assertThrows(CancellationException.class, () -> level.getHeight(Heightmap.Types.OCEAN_FLOOR, 0, 0));
        } finally { Thread.interrupted(); }
    }

    @Test void mixedTransactionsMatchFreshWorldForAllHeightmaps() {
        var terrain = new PredictionDecorationQueryTest().terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, -1, -1);
        Map<BlockPos, BlockState> expected = new HashMap<>();
        var random = new Random(8031);
        BlockState[] states = {Blocks.AIR.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.WATER.defaultBlockState()};
        for (int round = 0; round < 100; round++) {
            Map<BlockPos, BlockState> previous = new HashMap<>(expected);
            level.beginFeature();
            for (int write = 0; write < 5; write++) {
                var pos = new BlockPos(-16 + random.nextInt(4), 98 + random.nextInt(12), -16 + random.nextInt(4));
                var state = states[random.nextInt(states.length)];
                level.setBlock(pos, state, 0, 0);
                expected.put(pos, state);
                assertSame(state, level.getBlockState(pos));
            }
            boolean success = round % 3 != 0;
            level.endFeature(success);
            if (!success) expected = previous;
            var reference = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, -1, -1);
            reference.restoreSurface(expected);
            for (int x = -16; x < -12; x++) for (int z = -16; z < -12; z++) {
                for (var type : Heightmap.Types.values())
                    assertEquals(reference.getHeight(type, x, z), level.getHeight(type, x, z), "round=" + round + " type=" + type);
                for (int y = 98; y < 112; y++) {
                    var pos = new BlockPos(x, y, z);
                    assertSame(reference.getBlockState(pos), level.getBlockState(pos));
                }
            }
        }
    }

    @Test void columnVersionWrapCannotReuseAnOldHeightAnswer() throws Exception {
        var terrain = new PredictionDecorationQueryTest().terrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        assertEquals(104, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        var field = PredictionDecorationLevel.class.getDeclaredField("columnQueryVersions");
        field.setAccessible(true);
        int[] versions = (int[]) field.get(level);
        versions[32 * 80 + 32] = -1;
        level.beginFeature();
        level.setBlock(new BlockPos(0, 180, 0), Blocks.STONE.defaultBlockState(), 0, 0);
        assertEquals(0, versions[32 * 80 + 32]);
        assertEquals(181, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
        level.endFeature(false);
        assertEquals(104, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0));
    }

    @Test void aColumnRetainsEachDistinctHeightmapPredicateWithoutForcedCollisions() {
        var terrain = new CountingTerrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        for (var type : Heightmap.Types.values()) level.getHeight(type, -7, 3);
        int reads = terrain.reads;
        for (int repeat = 0; repeat < 100; repeat++)
            for (var type : Heightmap.Types.values()) level.getHeight(type, -7, 3);
        assertEquals(reads, terrain.reads, "all heightmap types at a column must coexist");
    }

    @Test void aWriteCollidingWithAnotherPositionCannotPoisonOrInvalidateItsAnswer() throws Exception {
        var terrain = new CountingTerrain();
        var level = new PredictionDecorationLevel(terrain, terrain, PredictionDecorationQueryTest.access, 0, 0);
        var slot = PredictionDecorationLevel.class.getDeclaredMethod("blockQuerySlot", int.class, int.class, int.class);
        slot.setAccessible(true);
        var ground = new BlockPos(0, 99, 0);
        int target = (int) slot.invoke(null, 0, 99, 0);
        BlockPos collision = null;
        search: for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++)
            for (int y = 98; y < 130; y++) {
                if ((x != 0 || z != 0) && (int) slot.invoke(null, x, y, z) == target) {
                    collision = new BlockPos(x, y, z);
                    break search;
                }
            }
        assertNotNull(collision);
        BlockState original = level.getBlockState(collision);
        assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK));
        int reads = terrain.reads;
        level.beginFeature();
        level.setBlock(collision, Blocks.DIAMOND_BLOCK.defaultBlockState(), 0, 0);
        assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK));
        assertEquals(reads, terrain.reads);
        assertTrue(level.getBlockState(collision).is(Blocks.DIAMOND_BLOCK));
        level.endFeature(false);
        assertSame(original, level.getBlockState(collision));
        assertTrue(level.getBlockState(ground).is(Blocks.GRASS_BLOCK));
    }
}
