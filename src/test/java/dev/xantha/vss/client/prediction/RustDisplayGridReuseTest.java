package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Cross-language regression: exact Java reuse must preserve native grid admission. */
class RustDisplayGridReuseTest {
    @Test void partialExactCacheReusePreservesTheNativeGridRoute() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(java.nio.file.Path.of(library));
        assertTrue(RustTerrainSampler.available());
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L);
        long[] approximate = new long[2];
        long[] submitted = new long[2];
        long[] adaptive = new long[2];
        long[] density = new long[2];
        for (int cached = 0; cached < 2; cached++) {
            var document = LithostitchedNativeTest.document();
            document.add("possible_biomes", new com.google.gson.JsonArray());
            document.add("biome_source", com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
            var settings = document.getAsJsonObject("settings");
            settings.addProperty("aquifers_enabled", false);
            settings.add("surface_rule", com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:block\",\"result_state\":{\"Name\":\"minecraft:stone\"}}"));
            var flat = com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":64,\"to_y\":80,\"from_value\":1,\"to_value\":-1}");
            settings.getAsJsonObject("noise_router").add("final_density", flat);
            settings.getAsJsonObject("noise_router").add("initial_density_without_jaggedness", flat.deepCopy());
            long world = RustWorldgenBackend.create(1, 0, document.toString());
            try (var sampler = new RustTerrainSampler(world,
                    profile, new ClientTerrainSampler(1, profile))) {
                if (cached != 0) sampler.sample(-16, -16);
                long before = sampler.gridComputedPoints.sum();
                var cachedPoint = cached == 0 ? null : sampler.sample(-16, -16);
                var grid = sampler.sampleDisplayGrid(-16, -16, 1, 8, 8, new ClientColumnSample[64]);
                if (cachedPoint != null) { assertFalse(grid[0].approximate()); assertEquals(cachedPoint, grid[0]); }
                approximate[cached] = Arrays.stream(grid).filter(ClientColumnSample::approximate).count();
                submitted[cached] = sampler.gridComputedPoints.sum() - before;
                var stats = com.google.gson.JsonParser.parseString(RustWorldgenBackend.decorationQueryStats(world)).getAsJsonObject();
                adaptive[cached] = stats.get("adaptiveGrids").getAsLong();
                density[cached] = stats.get("displayDensityColumns").getAsLong();
                System.out.printf("DISPLAY_BATCH_AUDIT exactCacheHits=%d submitted=%d approximate=%d adaptiveGrids=%d displayDensityColumns=%d%n",
                        cached, submitted[cached], approximate[cached], adaptive[cached], density[cached]);
            }
        }
        assertEquals(64, submitted[0]);
        assertTrue(approximate[0] > 0, "fixture must exercise the adaptive display route");
        assertEquals(63, submitted[1]);
        assertEquals(1, adaptive[0]);
        assertEquals(1, adaptive[1], "partial reuse must preserve the full-grid shape");
        assertEquals(density[0], density[1], "one Java cache hit must not increase native density work");
    }
}
