package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reproduces a terrain roof skipped by the former 16-block preview march. */
class PredictionPreviewHeightTest {
    @BeforeAll static void bootstrap() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }

    private static ClientTerrainSampler sampler(DensityFunction density, DensityFunction initial) {
        var zero = DensityFunctions.zero();
        var router = new NoiseRouter(zero,zero,zero,zero,zero,zero,zero,zero,zero,zero,initial,density,zero,zero,zero);
        var settings = new NoiseGeneratorSettings(NoiseSettings.create(-64,384,1,2),
                Blocks.STONE.defaultBlockState(),Blocks.AIR.defaultBlockState(),router,
                SurfaceRules.state(Blocks.STONE.defaultBlockState()),java.util.List.of(),-64,false,false,false,false);
        var context = PredictionJavaExteriorTest.context(settings);
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                5052304137288917019L,-64,384,"noise","minecraft:overworld",0L);
        return new ClientTerrainSampler(profile.seed(),profile,context.generator(),context.random(),context.heights(),-64);
    }

    @Test void previewKeepsUpperTerrainInsteadOfTheLowerCaveFloor() {
        var y = DensityFunctions.yClampedGradient(-64,320,-64,320);
        var density = DensityFunctions.rangeChoice(y,216,219,DensityFunctions.constant(1),
                DensityFunctions.rangeChoice(y,-64,156,DensityFunctions.constant(1),DensityFunctions.constant(-1)));
        try (var sampler = sampler(density,DensityFunctions.yClampedGradient(200,234,1,-1))) {
            for (int[] pos : new int[][]{{703,1727},{704,1728},{-1,-1}}) {
                assertEquals(219,sampler.surfaceY(pos[0],pos[1]));
                assertEquals(219,sampler.previewSurfaceY(pos[0],pos[1]));
                var preview = sampler.samplePreview(pos[0],pos[1]);
                assertEquals(219,preview.surfaceY());
                assertTrue(preview.approximate(),"cheap preview materials still require their later surface stage");
                assertNull(preview.volume(),"height correctness does not require building underground columns");
            }
        }
    }

    @Test void previewRetainsOneBlockHeightPrecision() {
        try (var sampler = sampler(DensityFunctions.yClampedGradient(201,217,1,-1),
                DensityFunctions.yClampedGradient(201,217,1,-1))) {
            assertEquals(209,sampler.surfaceY(0,0));
            assertEquals(209,sampler.previewSurfaceY(0,0));
            assertEquals(209,sampler.previewSurfaceY(0,0),"cached heights retain the same boundary");
        }
    }
}
