package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionBackendSelectionTest {
    @BeforeAll static void bootstrapMinecraft() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }

    private static ClientTerrainSampler sampler() {
        return ClientTerrainSampler.custom(42, new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42, -64, 384, "noise", "minecraft:overworld", 0), (x, z) -> 64);
    }
    @Test void correctedTagsCannotAdoptOldFinishedSamplesAndRemainStableAcrossReconnects() {
        var generator = JsonParser.parseString("{\"settings\":{},\"biome_source\":{\"type\":\"minecraft:fixed\"}}").getAsJsonObject();
        var old = sampler(); var current = sampler(); var same = sampler(); var changed = sampler();
        var registries = JsonParser.parseString("{\"registry_tags\":{\"example:climate\":{\"example:hot\":[\"example:desert\"]}}}").getAsJsonObject();
        PredictionBackendSelection.bind(old, generator, new JsonObject());
        PredictionBackendSelection.bind(current, generator, registries);
        PredictionBackendSelection.bind(same, generator, registries.deepCopy());
        assertNull(old.predictionCacheNamespace());
        assertNotNull(current.predictionCacheNamespace());
        assertEquals(current.predictionCacheNamespace(), same.predictionCacheNamespace());
        registries.getAsJsonObject("registry_tags").getAsJsonObject("example:climate")
                .getAsJsonArray("example:hot").add("example:savanna");
        PredictionBackendSelection.bind(changed, generator, registries);
        assertNotEquals(current.predictionCacheNamespace(), changed.predictionCacheNamespace());
    }
    @Test void endRoutingUsesItsOwnCacheEvenWithALegacyRegistrySnapshot() {
        var generator = new JsonObject(); var section = new JsonObject(); section.addProperty("revision", 1);
        generator.add("vss_terrablender_end", section);
        var first = sampler(); var second = sampler();
        PredictionBackendSelection.bind(first, generator, new JsonObject());
        section.addProperty("seed", 43);
        PredictionBackendSelection.bind(second, generator, new JsonObject());
        assertNotNull(first.predictionCacheNamespace());
        assertNotEquals(first.predictionCacheNamespace(), second.predictionCacheNamespace());
    }
}
