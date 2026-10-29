package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerraBlenderRequiredRoutingTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }
    @Test void requiredInvalidRegionRoutingCannotBecomePlainVanillaBiomes() {
        var source = new JsonObject(); source.addProperty("type", "minecraft:fixed"); source.addProperty("biome", "minecraft:plains");
        var generator = new JsonObject(); generator.add("biome_source", source); generator.add("vss_terrablender", new JsonObject());
        var profile = new DimensionProfile(Level.OVERWORLD.location(), 42, -64, 384, "noise", "minecraft:overworld", 0);
        var registries = ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), TerrainPredictionFixtures.access());
        assertThrows(RuntimeException.class, () -> ClientWorldgenProfileDecoder.decodeJavaSampler(
                profile, generator, registries, TerrainPredictionFixtures.access()));
    }
    @Test void endRoutingCannotAttachToAnUnrelatedBiomeCodec() {
        var source = new JsonObject(); source.addProperty("type", "minecraft:fixed"); source.addProperty("biome", "minecraft:plains");
        var generator = new JsonObject(); generator.add("biome_source", source); generator.add("vss_terrablender_end", new JsonObject());
        var profile = new DimensionProfile(Level.OVERWORLD.location(), 42, -64, 384, "noise", "minecraft:overworld", 0);
        var registries = ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), TerrainPredictionFixtures.access());
        var failure = assertThrows(IllegalArgumentException.class, () -> ClientWorldgenProfileDecoder.decodeJavaSampler(
                profile, generator, registries, TerrainPredictionFixtures.access()));
        assertTrue(failure.getMessage().contains("requires the End biome source"));
    }
}
