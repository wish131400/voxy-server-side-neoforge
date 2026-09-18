package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.*;

class PredictionSimpleVegetationTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    static ClientColumnSample sample(int y) {
        return new ClientColumnSample(y, y, 0, PredictionMaterialPalette.grassBlockIndex(), 0, 0, 0, 0,
                0, ClientColumnSample.FLAG_SURFACE_ONLY, 0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    private static PredictionSimpleVegetation.Hint forest() {
        return new PredictionSimpleVegetation.Hint(List.of(new PredictionSimpleVegetation.Species(
                Blocks.CHERRY_LOG.defaultBlockState(), Blocks.CHERRY_LEAVES.defaultBlockState())), true);
    }
    private static ClientColumnSample[] samples() {
        var result = new ClientColumnSample[66 * 66]; Arrays.fill(result, sample(96)); return result;
    }
    private static int[] colors() { int[] c = new int[66 * 66]; Arrays.fill(c, 0xff669944); return c; }
    private static PredictionSimpleVegetation.Result build(ClientColumnSample[] samples, int step, PredictionSimpleVegetation.Hint hint) {
        return PredictionSimpleVegetation.build(samples, 66, step, -512, -512, 42, colors(), colors(),
                PredictionVegetation.Tile.EMPTY, (x,y,z) -> hint);
    }

    @Test void registryTreesHaveActualSpeciesAndDesertIsNotAForest() {
        var registry = net.minecraft.data.registries.VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        var cherry = PredictionSimpleVegetation.inspect(registry.getOrThrow(Biomes.CHERRY_GROVE));
        assertTrue(cherry.trees().stream().anyMatch(t -> t.leaves().is(Blocks.CHERRY_LEAVES)));
        assertTrue(PredictionSimpleVegetation.inspect(registry.getOrThrow(Biomes.DESERT)).trees().isEmpty());
        assertTrue(PredictionSimpleVegetation.inspect(registry.getOrThrow(Biomes.OCEAN)).trees().isEmpty());
    }

    @Test void representativesAreBoundedDeterministicAndSurviveMarginCropAndPacking() {
        var samples = samples();
        for (int step : new int[]{4, 8}) {
            var forms = build(samples, step, forest());
            assertEquals(forms, build(samples, step, forest()));
            assertFalse(forms.forms().isEmpty());
            assertTrue(forms.forms().size() <= PredictionSimpleVegetation.MAX_FORMS);
            assertTrue(forms.forms().stream().allMatch(f -> f.height() >= 1 && f.height() <= 8));
            var plain = PredictionMeshBuilder.build(samples, colors(), 63, 0, step, 66, true);
            var mesh = PredictionMeshBuilder.build(samples, colors(), 63, 0, step, 66, true,
                    null, colors(), colors(), -512, -512, PredictionVegetation.Tile.EMPTY, forms);
            assertTrue(mesh.vertexCount() > plain.vertexCount());
            assertTrue(mesh.packed().quadCount() > plain.packed().quadCount());
            assertTrue(mesh.vertexCount() - plain.vertexCount() <= 60 * forms.forms().size());
            for (int v = 0; v < mesh.vertexCount(); v++) {
                assertTrue(mesh.x(v) >= 0 && mesh.x(v) <= step * 64);
                assertTrue(mesh.z(v) >= 0 && mesh.z(v) <= step * 64);
                assertTrue(mesh.y(v) <= forms.maxY());
            }
        }
    }

    @Test void distantForestIsTintOnlyAndGrassIsOneBlockCrossesAtFourBlockSpacing() {
        var grass = new PredictionSimpleVegetation.Hint(List.of(), true);
        assertTrue(build(samples(), 8, grass).forms().isEmpty());
        var near = build(samples(), 4, grass);
        assertFalse(near.forms().isEmpty());
        assertTrue(near.forms().stream().allMatch(f -> f.tree() == null && f.height() == 1));
        var far = build(samples(), 16, forest());
        assertTrue(far.forms().isEmpty()); assertNotNull(far.forestTints());
        assertEquals(PredictionSimpleVegetation.Result.EMPTY, build(samples(), 2, forest()));
    }

    @Test void fluidsSnowAndStoneNeverGrowRepresentatives() {
        for (int mode = 0; mode < 3; mode++) {
            var s = sample(96);
            var invalid = new ClientColumnSample(96, mode == 0 ? 100 : 96, 0,
                    mode == 2 ? PredictionMaterialPalette.stoneIndex() : s.topBlockIndex(), 0, 0, 0, 0,
                    mode == 0 ? 1 : 0, s.flags() | (mode == 1 ? ClientColumnSample.FLAG_SNOW : 0),
                    0, s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
            var all = samples(); Arrays.fill(all, invalid);
            assertTrue(build(all, 8, forest()).forms().isEmpty());
            assertNull(build(all, 16, forest()).forestTints());
        }
    }

    @Test void ordinaryCaptureKeepsRepresentativesUntilRenderTimeHandoff() {
        var original = samples();
        var s = sample(96);
        var captured = new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                0, 0, 0, 0, 0, s.flags() | ClientColumnSample.FLAG_CAPTURED, 0,
                s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
        var updated = samples(); Arrays.fill(updated, captured);
        for (int step : new int[]{4, 8, 16}) {
            var before = build(original, step, forest());
            var after = build(updated, step, forest());
            assertEquals(before.forms(), after.forms());
            assertArrayEquals(before.forestTints(), after.forestTints());
        }
    }

    @Test void grassCarriesGrassTintWhenFoliageTintDiffers() {
        var grass=colors(); var foliage=colors(); Arrays.fill(foliage,0xff223344);
        var simple=PredictionSimpleVegetation.build(samples(),66,4,0,0,42,grass,foliage,
                PredictionVegetation.Tile.EMPTY,(x,y,z)->new PredictionSimpleVegetation.Hint(List.of(),true));
        assertFalse(simple.forms().isEmpty());
        assertTrue(simple.forms().stream().allMatch(f->f.grassTint()==0xff669944));
    }

    @Test void benchmarkMeshOnlyBaselineVersusRepresentatives() {
        var samples = samples(); var colors = colors();
        for (int step : new int[]{4,8,16}) {
            long[] times = new long[2]; long[] quads = new long[2];
            for (int round = 0; round < 28; round++) for (int order = 0; order < 2; order++) {
                int mode = (round + order) % 2;
                long start = System.nanoTime();
                var simple = mode == 0 ? PredictionSimpleVegetation.Result.EMPTY : build(samples, step, forest());
                var mesh = PredictionMeshBuilder.build(samples, colors, 63, 0, step, 66, true,
                        null, colors, colors, 0, 0, PredictionVegetation.Tile.EMPTY, simple).compactForRendering();
                long elapsed = System.nanoTime() - start;
                if (round >= 8) { times[mode] += elapsed; quads[mode] += mesh.packed().quadCount(); }
            }
            System.out.printf(java.util.Locale.ROOT,"SIMPLE_VEGETATION step=%d baselineMs=%.3f enabledMs=%.3f baselineQuads=%d enabledQuads=%d%n",
                    step, times[0]/20e6, times[1]/20e6, quads[0]/20, quads[1]/20);
        }
    }
}
