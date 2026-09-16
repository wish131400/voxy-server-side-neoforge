package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VssLodSampleCacheTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static ClientTerrainSampler sampler() {
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 1L);
        return ClientTerrainSampler.withSurfaceOverride(new ClientTerrainSampler(42, profile), (x,z) -> 64);
    }

    @Test void overflowingCacheMustStillReturnTheComputedColumn() {
        var cache = new VssLodSampleCache(256);
        var sample = sampler().sampleSurface(0, 0);
        for (long key = 1; key <= 256; key++) cache.put(key, sample);
        assertSame(sample, cache.getOrCompute(0, key -> sample));
        assertTrue(cache.size() <= 256);
    }

    @Test void evictionDuringSurfaceExtractionMustNotDiscardCompletedVegetation() {
        var terrain = sampler();
        var cache = new VssLodSampleCache(256);
        var sample = terrain.sampleSurface(0, 0);
        for (long key = 1; key <= 256; key++) cache.put(key, sample);
        var level = new PredictionDecorationLevel(terrain, terrain, RegistryAccess.EMPTY, 0, 0, cache);
        var trunk = new BlockPos(0, 64, 0);
        var leaves = new BlockPos(0, 65, 0);
        level.setBlock(trunk, Blocks.OAK_LOG.defaultBlockState(), 0, 0);
        level.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState(), 0, 0);
        var exterior = PredictionVegetation.surfaceBlocks(level);
        assertEquals(Blocks.OAK_LOG.defaultBlockState(), exterior.get(trunk));
        assertEquals(Blocks.OAK_LEAVES.defaultBlockState(), exterior.get(leaves));
        assertEquals(64, level.column(0, 0).surfaceY());
    }

    @Test void authoritativeColumnArrivingDuringSamplingWins() {
        var cache = new VssLodSampleCache(256);
        var predicted = sampler().sampleSurface(0, 0);
        var s = predicted;
        var captured = new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(),
                s.flags() | ClientColumnSample.FLAG_CAPTURED, s.groundFeatureKind(), s.underBlockIndex(),
                s.deepBlockIndex(), s.surfaceBottom(), s.lowerTop(), s.lowerBottom(), s.spanFloor());
        var result = cache.getOrCompute(7, key -> {
            cache.put(key, captured);
            return predicted;
        });
        assertSame(captured, result);
        assertSame(captured, cache.get(7));
    }
}
