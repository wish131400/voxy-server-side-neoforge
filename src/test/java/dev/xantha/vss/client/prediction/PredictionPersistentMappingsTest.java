package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionPersistentMappingsTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void awaitClose() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    private static PredictionCacheMappings mappings(String... names) {
        return new PredictionCacheMappings(List.of(names), List.of(), List.of());
    }

    private static ClientColumnSample sample(int biome) {
        int stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE), dirt = BuiltInRegistries.BLOCK.getId(Blocks.DIRT);
        return new ClientColumnSample(80, 63, biome, dirt, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_CAPTURED, 0, dirt, stone, 72, 40, 20, 8,
                new PredictionColumnVolume(new int[]{0, 50, stone, 0, 70, 80, dirt, 0}));
    }

    @Test void terrainAndColorsSurviveBiomeReorderingAndUnknownBiomeOnlyRejectsItsTile() throws Exception {
        var forest = PredictionDiskCache.Key.terrain(0, 0, 0);
        var plains = PredictionDiskCache.Key.terrain(1, 0, 0);
        var first = mappings("minecraft:forest", "minecraft:plains");
        try (var cache = new PredictionDiskCache(directory, 12L, first);
             var a = cache.lease(forest); var b = cache.lease(plains)) {
            assertTrue(cache.writeTerrain(a, new PredictionDiskCache.TerrainData(new ClientColumnSample[]{sample(0)}, 99L,
                    new int[]{1}, new int[]{2}, new int[]{3})));
            assertTrue(cache.writeTerrain(b, new ClientColumnSample[]{sample(1)}));
            cache.flush();
        }
        awaitClose();
        try (var cache = new PredictionDiskCache(directory, 12L, mappings("minecraft:desert", "minecraft:plains", "minecraft:forest"));
             var a = cache.lease(forest); var b = cache.lease(plains)) {
            var restored = cache.readTerrainData(a, 1);
            assertEquals(sample(2), restored.samples()[0]);
            assertTrue(restored.colorsMatch(99L));
            assertArrayEquals(new int[]{3}, restored.waterTints());
            assertEquals(sample(1), cache.readTerrain(b, 1)[0]);
        }
        awaitClose();
        try (var cache = new PredictionDiskCache(directory, 12L, mappings("minecraft:plains"));
             var a = cache.lease(forest); var b = cache.lease(plains)) {
            assertNull(cache.readTerrain(a, 1));
            assertEquals(sample(0), cache.readTerrain(b, 1)[0]);
            assertTrue(cache.writeTerrain(a, new ClientColumnSample[]{sample(0)}));
            assertEquals(sample(0), cache.readTerrain(a, 1)[0]);
        }
    }

    @Test void capturesPersistBlockAndBiomeNamesAndSkipOnlyUnavailableSamples() {
        Path file = directory.resolve("captures.smp");
        try (var store = new PredictionSampleStore(file, 12L, mappings("minecraft:forest", "minecraft:plains"))) {
            store.put(1, sample(0)); store.put(2, sample(1));
        }
        try (var store = new PredictionSampleStore(file, 12L, mappings("minecraft:plains", "minecraft:forest"))) {
            assertEquals(sample(1), store.get(1)); assertEquals(sample(0), store.get(2));
            store.put(3, sample(0));
        }
        try (var store = new PredictionSampleStore(file, 12L, mappings("minecraft:plains"))) {
            assertNull(store.get(1));
            assertEquals(sample(0), store.get(2)); assertEquals(sample(0), store.get(3));
            assertEquals(2, store.size());
        }
        try (var store = new PredictionSampleStore(file, 12L, mappings("minecraft:forest", "minecraft:plains"))) {
            assertNull(store.get(1)); assertEquals(sample(1), store.get(2));
        }
    }

    @Test void legacyTerrainAndCapturesUseSavedMappingsRatherThanCurrentNumbers() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        int stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE), dirt = BuiltInRegistries.BLOCK.getId(Blocks.DIRT);
        var names = new java.util.ArrayList<String>(java.util.Collections.nCopies(Math.max(stone, dirt) + 1, "minecraft:air"));
        names.set(stone, "minecraft:stone"); names.set(dirt, "minecraft:dirt");
        var mapped = new PredictionCacheMappings(List.of("minecraft:plains", "minecraft:forest"),
                List.of("minecraft:forest", "minecraft:plains"), names);
        try (var cache = new PredictionDiskCache(directory, 12L); var lease = cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease, new ClientColumnSample[]{sample(0)})); cache.flush();
        }
        awaitClose();
        Path file = directory.resolve("captures.smp");
        try (var store = new PredictionSampleStore(file, 12L)) { store.put(1, sample(0)); }
        try (var cache = new PredictionDiskCache(directory, 12L, mapped); var lease = cache.lease(key)) {
            assertEquals(sample(1), cache.readTerrain(lease, 1)[0]);
        }
        try (var store = new PredictionSampleStore(file, 12L, mapped)) { assertEquals(sample(1), store.get(1)); }
    }
}
