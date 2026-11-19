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
    @Test void correctedTerrainSurfaceRejectsOldRecordsAndEarlyDisplaySharesItsIdentity() {
        var generator = new JsonObject();
        var old = sampler(); PredictionBackendSelection.bind(old, generator, new JsonObject());
        generator.addProperty("vss_freeterraforged", true);
        var current = sampler(); var early = sampler();
        PredictionBackendSelection.bind(current, generator, new JsonObject());
        PredictionBackendSelection.bind(early, generator.deepCopy(), new JsonObject());
        assertNotEquals(old.predictionCacheNamespace(), current.predictionCacheNamespace());
        assertEquals(current.predictionCacheNamespace(), early.predictionCacheNamespace());
    }
    @Test void correctedTagsCannotAdoptOldFinishedSamplesAndRemainStableAcrossReconnects() {
        var generator = JsonParser.parseString("{\"settings\":{},\"biome_source\":{\"type\":\"minecraft:fixed\"}}").getAsJsonObject();
        var old = sampler(); var current = sampler(); var same = sampler(); var changed = sampler();
        var registries = JsonParser.parseString("{\"registry_tags\":{\"example:climate\":{\"example:hot\":[\"example:desert\"]}}}").getAsJsonObject();
        PredictionBackendSelection.bind(old, generator, new JsonObject());
        PredictionBackendSelection.bind(current, generator, registries);
        PredictionBackendSelection.bind(same, generator, registries.deepCopy());
        assertEquals("session-block-mappings-r1:density-preview-height-r1:legacy", old.predictionCacheNamespace());
        assertNotNull(current.predictionCacheNamespace());
        assertEquals(current.predictionCacheNamespace(), same.predictionCacheNamespace());
        registries.getAsJsonObject("registry_tags").getAsJsonObject("example:climate")
                .getAsJsonArray("example:hot").add("example:savanna");
        PredictionBackendSelection.bind(changed, generator, registries);
        assertNotEquals(current.predictionCacheNamespace(), changed.predictionCacheNamespace());
    }
    @Test void previewGeometryRevisionIsSharedByEarlyDisplayAndFinalSampler() {
        var generator = JsonParser.parseString("{\"settings\":{},\"biome_source\":{\"type\":\"minecraft:fixed\"}}").getAsJsonObject();
        var context = sampler();
        var early = new PredictionCacheSampler(context.profile(), dev.xantha.vss.testutil.TerrainPredictionFixtures.noise().getBiomeSource(),null);
        PredictionBackendSelection.bind(context,generator,new JsonObject());
        PredictionBackendSelection.bind(early,generator.deepCopy(),new JsonObject());
        assertTrue(context.predictionCacheNamespace().contains("density-preview-height-r1:"));
        assertEquals(context.predictionCacheNamespace(),early.predictionCacheNamespace());
    }

    @Test void ftfCacheIdentityIgnoresConfigurationLocationButKeepsContentAndResources() {
        var generator=JsonParser.parseString("{\"vss_original_stages\":{},\"vss_freeterraforged_runtime\":{\"schema\":1,\"tag_sequence\":1,\"tag_fingerprint\":\"tags\",\"resource_fingerprint\":\"resources\",\"biome_replacement\":{\"path\":\"C:/old/config.json\",\"revision\":1,\"fingerprint\":\"config\",\"content\":\"{}\"}}}").getAsJsonObject();
        var moved=generator.deepCopy();
        var runtime=moved.getAsJsonObject("vss_freeterraforged_runtime");
        runtime.addProperty("tag_sequence",5);
        runtime.getAsJsonObject("biome_replacement").addProperty("path","F:/new/config.json");
        runtime.getAsJsonObject("biome_replacement").addProperty("revision",8);
        assertEquals(dev.xantha.vss.common.worldgen.WorldgenJson.cacheSha256(generator),dev.xantha.vss.common.worldgen.WorldgenJson.cacheSha256(moved));
        assertArrayEquals(voxelIdentity(generator),voxelIdentity(moved));
        runtime.getAsJsonObject("biome_replacement").addProperty("content","{changed:true}");
        assertNotEquals(dev.xantha.vss.common.worldgen.WorldgenJson.cacheSha256(generator),dev.xantha.vss.common.worldgen.WorldgenJson.cacheSha256(moved));
        assertFalse(java.util.Arrays.equals(voxelIdentity(generator),voxelIdentity(moved)));
        runtime.getAsJsonObject("biome_replacement").addProperty("content","{}");runtime.addProperty("resource_fingerprint","changed-pack");
        assertNotEquals(dev.xantha.vss.common.worldgen.WorldgenJson.cacheSha256(generator),dev.xantha.vss.common.worldgen.WorldgenJson.cacheSha256(moved));
    }
    private static byte[] voxelIdentity(JsonObject generator) {
        byte[] data=generator.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var profile=new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",0,0,data.length,data);
        try(var sampler=ClientTerrainSampler.custom(42,profile,(x,z)->64)){return PredictionVoxelCache.identity(sampler);}
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
