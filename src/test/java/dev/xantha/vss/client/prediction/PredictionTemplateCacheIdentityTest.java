package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionTemplateCacheIdentityTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void oldEmptySurfaceIsRegeneratedAndChangedCatalogRejectsFinishedSurface() {
        var key = PredictionDiskCache.Key.surface(0, 0, 31);
        try (var legacy = new PredictionDiskCache(directory, 42); var lease = legacy.lease(key)) {
            assertTrue(legacy.writeSurface(lease, Map.of(), true));
        }
        try (var current = new PredictionDiskCache(directory, 42, null, "a".repeat(64)); var lease = current.lease(key)) {
            assertNull(current.readSurface(lease), "Old incomplete template coverage must not remain an empty building forever");
            assertTrue(current.writeSurface(lease, Map.of(new BlockPos(1, 64, 1), Blocks.OAK_PLANKS.defaultBlockState()), true));
        }
        try (var same = new PredictionDiskCache(directory, 42, null, "a".repeat(64)); var lease = same.lease(key)) {
            assertEquals(1, same.readSurface(lease).size());
        }
        try (var changed = new PredictionDiskCache(directory, 42, null, "b".repeat(64)); var lease = changed.lease(key)) {
            assertNull(changed.readSurface(lease));
        }
    }

    @Test void templateAwareSurfaceStillPreservesCanonicalAndWeatherFlags() {
        try (var cache = new PredictionDiskCache(directory, 42, null, "a".repeat(64))) {
            for (boolean canonical : new boolean[] {false, true}) {
                var key = PredictionDiskCache.Key.surface(0, 0, canonical ? 1 : 0);
                try (var lease = cache.lease(key)) {
                    assertTrue(cache.writeSurface(lease, Map.of(), canonical));
                    var restored = cache.readSurfaceData(lease);
                    assertNotNull(restored);
                    assertEquals(canonical, restored.canonical());
                    assertEquals(canonical, restored.weatherChecked());
                }
            }
        }
    }


}
