package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionRegistryContextCacheTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.parse("minecraft:overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void repairedRegistryContextRejectsOldFinishedVoxelsAndReusesRawTerrain() throws Exception {
        var key = new PredictionTileKey(PROFILE.levelKey(), 0, 0, 0);
        var tile = PredictionVoxelCacheTest.tile(key, 16, true);
        var sampler = new ClientTerrainSampler(PROFILE.seed(), PROFILE);
        byte[] identity = PredictionVoxelCache.identity(sampler);
        int current = 3 | PredictionVegetation.runtimeSettings(PROFILE.dimension());
        int legacy = current & ~(1 << 14);
        assertNotEquals(legacy, current);
        try (var cache = new PredictionDiskCache(directory, 77);
             var terrain = cache.lease(PredictionDiskCache.Key.terrain(0, 0, 0));
             var voxels = cache.lease(PredictionDiskCache.Key.voxels(0, 0, 0))) {
            assertTrue(cache.writeTerrain(terrain, tile.samples()));
            assertTrue(cache.writeVoxelsLater(voxels, tile, legacy, identity).get(10, TimeUnit.SECONDS));
            assertNotNull(cache.readVoxels(voxels, key, legacy, identity));
            assertNull(cache.readVoxels(voxels, key, current, identity), "old surfaceComplete must not restore as repaired decoration");
            assertArrayEquals(tile.samples(), cache.readTerrain(terrain, tile.samples().length));
        }
    }

    @Test void repairedSurfaceIdentityBypassesOldEmptyResultsAndStillInvalidatesOnRealCapture() {
        int current = 31 | PredictionVegetation.runtimeSettings(PROFILE.dimension());
        int legacy = current & ~(1 << 14);
        var oldKey = PredictionDiskCache.Key.surface(0, 0, legacy);
        var newKey = PredictionDiskCache.Key.surface(0, 0, current);
        try (var cache = new PredictionDiskCache(directory, 77)) {
            try (var old = cache.lease(oldKey); var fresh = cache.lease(newKey)) {
                assertTrue(cache.writeSurface(old, Map.of(), true));
                assertEquals(Map.of(), cache.readSurface(old));
                assertNull(cache.readSurface(fresh));
                assertTrue(cache.writeSurface(fresh, Map.of(new BlockPos(0, 64, 0), Blocks.OAK_LOG.defaultBlockState()), true));
                assertFalse(cache.readSurface(fresh).isEmpty());
            }
            cache.invalidateChunk(0, 0);
            try (var fresh = cache.lease(newKey)) { assertNull(cache.readSurface(fresh)); }
        }
    }
}
