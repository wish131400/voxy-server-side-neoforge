package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RustSurfaceRegistriesTest {
    @Test void referencesKeepTheirRegistryAndOrderedEmptyTagsWithoutUnrelatedTagPayloads() {
        var input = JsonParser.parseString("""
                {"custom_registries":{"lithostitched:surface_rule":{"example:a":{"type":"minecraft:block"}}},
                 "registry_tags":{"lithostitched:surface_rule":{"example:ordered":["example:b","example:a"],"example:empty":[]},
                                  "minecraft:worldgen/biome":{"example:unrelated":["example:biome"]}}}
                """).getAsJsonObject();
        String before = input.toString();
        var result = new JsonObject();
        RustSurfaceRegistries.copy(input, result);
        assertSame(input.get("custom_registries"), result.get("custom_registries"));
        assertEquals(1, result.getAsJsonObject("registry_tags").size());
        assertEquals(input.getAsJsonObject("registry_tags").get("lithostitched:surface_rule"),
                result.getAsJsonObject("registry_tags").get("lithostitched:surface_rule"));
        assertEquals(before, input.toString());
        result = new JsonObject();
        RustSurfaceRegistries.copy(new JsonObject(), result);
        assertEquals(0, result.size());
    }

    @Test void implementedSurfaceTypesEnterNativeButUnknownNestedRulesStillReject() {
        for (String type : new String[]{"biomeswevegone:state_provider", "biomeswevegone:between_repeating_noise_range",
                "biomeswevegone:bands", "lithostitched:reference"}) {
            var rule = new JsonObject();rule.addProperty("type", type);
            assertTrue(PredictionWorldgenCapabilities.nativeSafe(rule), type);
            rule.add("nested", JsonParser.parseString("{\"type\":\"example:unknown_rule\"}"));
            assertFalse(PredictionWorldgenCapabilities.nativeSafe(rule), type);
        }
    }
}
