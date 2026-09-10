package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionRealBoundarySeamsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void connectsRealGroundOnEverySideAndBothHeightOrders() {
        for (int step : new int[]{1, 4, 16}) for (int real : new int[]{61, 67})
            for (int d = 0; d < 4; d++) {
                var surface = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0, 0, step, 64));
                int nx = d == 0 ? -1 : d == 1 ? 1 : 0, nz = d == 2 ? -1 : d == 3 ? 1 : 0;
                int x = nx < 0 ? 64 * step : nx > 0 ? -1 : 10;
                int z = nz < 0 ? 64 * step : nz > 0 ? -1 : 10;
                var edge = new PredictionRealBoundarySeams.Edge(x, z, nx, nz, ground(real));
                var cache = new PredictionRealBoundarySeams();
                var patches = cache.update(List.of(surface), List.of(edge));
                assertEquals(1, patches.size());
                var words = patches.getFirst().mesh().quads();
                double area = 0;
                for (int i = 0; i < words.length; i += 12) {
                    int top = ((words[i + 4] & 65535) - 32768) / 4;
                    int bottom = ((words[i + 5] & 65535) - 32768) / 4;
                    assertTrue(top <= Math.max(64, real) && bottom >= Math.min(64, real));
                    area += top - bottom;
                    assertEquals(PredictionRealBoundarySeams.REAL_BOUNDARY,
                            words[i + 9] & PredictionRealBoundarySeams.REAL_BOUNDARY);
                }
                assertEquals(3, area);
                assertSame(patches, cache.update(List.of(surface), List.of(edge)));
                assertTrue(cache.update(List.of(surface), List.of()).isEmpty());
            }
    }

    @Test void equalHeightsAndMissingPredictionNeedNoCurtain() {
        var surface = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0, 0, 1, 64));
        var edge = new PredictionRealBoundarySeams.Edge(-1, 10, 1, 0, ground(64));
        assertTrue(new PredictionRealBoundarySeams().update(List.of(surface), List.of(edge)).isEmpty());
        assertTrue(new PredictionRealBoundarySeams().update(List.of(), List.of(edge)).isEmpty());
    }

    @Test void largeMismatchNeverBecomesWallAndSmallReplacementCanStillStitch() {
        for (int step : new int[]{1, 4, 16, 64}) {
            var surface = PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0, 0, step, 64));
            var cache = new PredictionRealBoundarySeams();
            for (int height : new int[]{-64, 0, 59, 69, 128, 320}) {
                var edge = new PredictionRealBoundarySeams.Edge(-1, 10, 1, 0, ground(height));
                assertTrue(cache.update(List.of(surface), List.of(edge)).isEmpty(),
                        "unsupported mismatch cannot become a curtain, spacing=" + step + ", y=" + height);
            }
            var small = new PredictionRealBoundarySeams.Edge(-1, 10, 1, 0, ground(68));
            assertFalse(cache.update(List.of(surface), List.of(small)).isEmpty());
            var large = new PredictionRealBoundarySeams.Edge(-1, 10, 1, 0, ground(128));
            assertTrue(cache.update(List.of(surface), List.of(large)).isEmpty(), "remove previously cached connector");
        }
    }

    @Test void missingGrassStrataDoNotRepeatGrassAndExplicitMaterialsArePreserved() {
        int grass = PredictionMaterialPalette.grassBlockIndex();
        var sample = new ClientColumnSample(64, 64, 0, grass, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_SURFACE_ONLY, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        assertEquals(PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.wallUnderBlock(sample));
        assertEquals(PredictionMaterialPalette.stoneIndex(), PredictionMaterialPalette.wallDeepBlock(sample));
        int sand = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(net.minecraft.world.level.block.Blocks.SAND);
        var explicit = new ClientColumnSample(64, 64, 0, grass, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_CAPTURED, 0, sand, sand,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        assertEquals(sand, PredictionMaterialPalette.wallUnderBlock(explicit));
        assertEquals(sand, PredictionMaterialPalette.wallDeepBlock(explicit));
    }

    static ClientColumnSample ground(int height) {
        return new ClientColumnSample(height, height, 0, ClientColumnSample.NO_BLOCK,
                0, 0, 0, 0, 0, ClientColumnSample.FLAG_CAPTURED, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    @Test void caveAirAndBuriedSurfaceMaterialsCannotPaintTheWholeCliff() {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        for (var block : new net.minecraft.world.level.block.Block[]{
                net.minecraft.world.level.block.Blocks.AIR, net.minecraft.world.level.block.Blocks.CAVE_AIR,
                net.minecraft.world.level.block.Blocks.WATER, net.minecraft.world.level.block.Blocks.SHORT_GRASS,
                net.minecraft.world.level.block.Blocks.GRASS_BLOCK, net.minecraft.world.level.block.Blocks.PODZOL}) {
            int id = registry.getId(block);
            var sample = new ClientColumnSample(100, 100, 0, PredictionMaterialPalette.grassBlockIndex(),
                    0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0, id, id,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            assertEquals(PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.wallUnderBlock(sample), block.toString());
            assertEquals(PredictionMaterialPalette.stoneIndex(), PredictionMaterialPalette.wallDeepBlock(sample), block.toString());
        }
        for (var block : new net.minecraft.world.level.block.Block[]{
                net.minecraft.world.level.block.Blocks.SANDSTONE, net.minecraft.world.level.block.Blocks.RED_TERRACOTTA,
                net.minecraft.world.level.block.Blocks.NETHERRACK, net.minecraft.world.level.block.Blocks.END_STONE}) {
            int id = registry.getId(block);
            var sample = new ClientColumnSample(100, 100, 0, id, 0, 0, 0, 0, 0,
                    ClientColumnSample.FLAG_SURFACE_ONLY, 0, id, id,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            assertEquals(id, PredictionMaterialPalette.wallUnderBlock(sample));
            assertEquals(id, PredictionMaterialPalette.wallDeepBlock(sample));
        }
    }

    @Test void generatedLongWallMatchesSolidStrataWhenCaveSamplesContainAirOrGrass() {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        for (int invalid : new int[]{registry.getId(net.minecraft.world.level.block.Blocks.AIR),
                PredictionMaterialPalette.grassBlockIndex()}) {
            var bad = new ClientColumnSample[9];
            var reference = new ClientColumnSample[9];
            for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
                int y = x == 0 ? 100 : 64;
                for (boolean repaired : new boolean[]{false, true}) {
                    var target = repaired ? reference : bad;
                    target[z * 3 + x] = new ClientColumnSample(y, y, 0, PredictionMaterialPalette.grassBlockIndex(),
                            0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                            repaired ? PredictionMaterialPalette.dirtIndex() : invalid,
                            repaired ? PredictionMaterialPalette.stoneIndex() : invalid,
                            ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
                }
            }
            var actual = PredictionMeshBuilder.build(bad, null, 63, 0, 1, 3);
            var expected = PredictionMeshBuilder.build(reference, null, 63, 0, 1, 3);
            assertEquals(expected.vertexCount(), actual.vertexCount());
            for (int i = 0; i < actual.vertexCount(); i++) {
                assertEquals(expected.y(i), actual.y(i));
                assertEquals(expected.color(i), actual.color(i), "wall vertex=" + i);
            }
        }
    }
}
