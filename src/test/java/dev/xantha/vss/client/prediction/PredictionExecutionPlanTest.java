package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PredictionExecutionPlanTest {
    @Test void unknownSurfaceUsesJavaWhileNativeTerrainInputIsDetached() {
        var input = JsonParser.parseString("""
            {"settings":{"noise_router":{"final_density":0},"default_block":{"Name":"minecraft:stone"},
              "surface_rule":{"type":"unregistered_mod:surface"}},"biome_source":{"type":"minecraft:fixed"}}
            """).getAsJsonObject();
        var registries = new com.google.gson.JsonObject();
        String original = input.toString();
        var plan = PredictionExecutionPlan.resolve(input,registries);
        assertTrue(plan.nativeTerrain()); assertTrue(plan.javaSurface());
        assertEquals("minecraft:block", plan.nativeGenerator(input).getAsJsonObject("settings").getAsJsonObject("surface_rule").get("type").getAsString());
        assertEquals(original,input.toString());
        input.getAsJsonObject("settings").getAsJsonObject("noise_router").add("final_density",JsonParser.parseString("{\"type\":\"unregistered_mod:density\"}"));
        assertFalse(PredictionExecutionPlan.resolve(input,registries).nativeTerrain());
    }
}
