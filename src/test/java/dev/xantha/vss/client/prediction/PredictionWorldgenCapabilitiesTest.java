package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionWorldgenCapabilitiesTest {
    @Test void customDecorationDoesNotDisableNativeTerrainOrMutateJavaSnapshot() {
        var generator = JsonParser.parseString("""
                {"settings":{"noise_router":{"final_density":"example:height"}},"biome_source":{"type":"minecraft:multi_noise"}}
                """).getAsJsonObject();
        var registries = JsonParser.parseString("""
                {"configured_features":{"create:ore":{"type":"create:layered_ore"}},
                 "structures":{"example:building":{"type":"example:jigsaw"}},
                 "configured_carvers":{"example:cave":{"type":"example:carver"}},
                 "biomes":{"example:plain":{"features":[[{"type":"example:placement"}]],"carvers":{}}},
                 "density_functions":{"example:height":{"type":"minecraft:constant","argument":0}}}
                """).getAsJsonObject();
        String original = registries.toString();
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries));
        assertEquals(original, registries.toString(), "Java must retain the full feature snapshot");
        registries.getAsJsonObject("density_functions").getAsJsonObject("example:height")
                .addProperty("type", "tectonic:config_noise");
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries)
                .contains("tectonic:config_noise"));
    }

    @Test void biomeCodecsAndExplicitJavaRequestsStillRejectNative() {
        var generator = JsonParser.parseString("""
                {"settings":{},"biome_source":{"type":"example:biome_source"}}
                """).getAsJsonObject();
        var registries = new com.google.gson.JsonObject();
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries).contains("biome_source"));
        generator.getAsJsonObject("biome_source").addProperty("type", "minecraft:fixed");
        generator.addProperty("vss_force_java", true);
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries).contains("vss_force_java"));
        generator.remove("vss_force_java");
        registries.addProperty("vss_force_java", true);
        assertNotNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries));
    }

    @Test void unusedCustomDensityDoesNotDisableOtherDimensionsAndCyclesTerminate() {
        var generator = JsonParser.parseString("{\"settings\":{\"noise_router\":{\"final_density\":\"vss:a\"}},\"biome_source\":{\"type\":\"minecraft:fixed\"}}").getAsJsonObject();
        var registries = JsonParser.parseString("{\"density_functions\":{\"vss:a\":\"vss:b\",\"vss:b\":\"vss:a\",\"rtf:unused\":{\"type\":\"reterraforged:cell\"}}}").getAsJsonObject();
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries));
        var root = PredictionWorldgenCapabilities.terrainDocument(generator, registries);
        assertEquals(2, root.getAsJsonObject("density_functions").size());
        generator.getAsJsonObject("settings").getAsJsonObject("noise_router").addProperty("final_density", "rtf:unused");
        assertTrue(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries).contains("reterraforged:cell"));
    }

    @Test void knownLithostitchedCodecsAreNativeButUnknownExtensionsRemainExplicit() {
        for (String name : new String[]{"axis", "ceil", "floor", "sin", "cos", "sqrt", "mix", "select", "shift", "fast_noise"})
            assertTrue(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("{\"type\":\"lithostitched:" + name + "\"}")));
        assertFalse(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("{\"type\":\"lithostitched:future_noise\"}")));
    }

    @Test void dataPackIdentifiersDoNotDisableVanillaNoiseButCustomCodecsSelectJava() {
        assertTrue(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("""
                {"tectonic:mountains":{"type":"minecraft:add","argument1":"terralith:height","argument2":1}}
                """)));
        assertFalse(PredictionWorldgenCapabilities.nativeSafe(JsonParser.parseString("""
                {"density_functions":{"x":{"type":"tectonic:config_noise"}}}
                """)));
    }

    @Test void serverRuntimeDependencyAndMissingSnapshotCannotBecomeFallbackTerrain() {
        assertNotNull(PredictionWorldgenCapabilities.rejection(JsonParser.parseString("{}").getAsJsonObject()));
        assertEquals("region context", PredictionWorldgenCapabilities.rejection(JsonParser.parseString("""
                {"settings":{},"biome_source":{},"vss_unsupported_reason":"region context"}
                """).getAsJsonObject()));
    }
}
