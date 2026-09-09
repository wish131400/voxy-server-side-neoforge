package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

class ClientTerrainSamplerTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
            "noise", "minecraft:overworld", 1L);

    @BeforeAll
    static void bootstrapMinecraft() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(
                    java.nio.file.Path.of("build", "tmp", "prediction-tests"));
        }
        LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sameSeedAndProfileProduceStableSamples() {
        ClientTerrainSampler first = new ClientTerrainSampler(42L, PROFILE);
        ClientTerrainSampler second = new ClientTerrainSampler(42L, PROFILE);
        assertEquals(first.surfaceY(1234, -987), second.surfaceY(1234, -987));
        assertArrayEquals(first.sampleChunk(7, -3), second.sampleChunk(7, -3));
    }

    @Test
    void samplesStayInsideDimensionBounds() {
        ClientTerrainSampler sampler = new ClientTerrainSampler(Long.MIN_VALUE, PROFILE);
        for (int x = -256; x <= 256; x += 31) {
            for (int z = -256; z <= 256; z += 29) {
                int height = sampler.surfaceY(x, z);
                assertTrue(height > PROFILE.minY() && height < PROFILE.minY() + PROFILE.height());
            }
        }
    }

    @Test
    void dimensionFingerprintChangesTerrainStream() {
        DimensionProfile nether = new DimensionProfile(
                ResourceLocation.withDefaultNamespace("the_nether"), 0, 256,
                "noise", "minecraft:nether", 2L);
        ClientTerrainSampler overworld = new ClientTerrainSampler(42L, PROFILE);
        ClientTerrainSampler otherDimension = new ClientTerrainSampler(42L, nether);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                overworld.surfaceY(1234, -987), otherDimension.surfaceY(1234, -987));
    }

    @Test
    void distantSampleSkipsFineColumnMetadata() {
        ClientTerrainSampler sampler = new ClientTerrainSampler(42L, PROFILE);

        ClientColumnSample sample = sampler.sampleForLod(1234, -987, 16);

        assertTrue(sample.surfaceY() > PROFILE.minY());
        assertEquals(ClientColumnSample.NO_SPAN, sample.lowerTop());
        assertEquals(ClientColumnSample.NO_SPAN, sample.lowerBottom());
        assertEquals(0, sample.structureIndex());
        assertEquals(0, sample.treeKind());
    }

    @Test
    void surfaceOverridePreservesDecodedSamplerContext() {
        ClientTerrainSampler base = new ClientTerrainSampler(42L, PROFILE);
        ClientTerrainSampler overridden = ClientTerrainSampler.withSurfaceOverride(
                base, (x, z) -> 999);

        assertEquals(PROFILE.minY() + PROFILE.height() - 1,
                overridden.surfaceY(1234, -987));
        assertTrue(overridden.exactWorldgen());
        assertEquals(overridden.surfaceY(1234, -987), overridden.groundY(1234, -987));
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
