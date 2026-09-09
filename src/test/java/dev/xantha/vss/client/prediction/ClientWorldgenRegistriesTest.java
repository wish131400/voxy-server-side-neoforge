package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientWorldgenRegistriesTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void configuredSelectorCanReferForwardToAPlacedFeature() {
        var root = JsonParser.parseString("""
                {
                  "noises": {}, "density_functions": {},
                  "configured_features": {
                    "vss:selector": {
                      "type": "minecraft:random_selector",
                      "config": {"features": [], "default": "vss:placed"}
                    },
                    "vss:ground": {
                      "type": "minecraft:simple_block",
                      "config": {"to_place": {"type": "minecraft:simple_state_provider",
                                                "state": {"Name": "minecraft:grass_block"}}}
                    }
                  },
                  "placed_features": {
                    "vss:placed": {"feature": "vss:ground", "placement": []}
                  }
                }
                """).getAsJsonObject();
        var registries = ClientWorldgenRegistries.decode(root, RegistryAccess.EMPTY);
        var selector = registries.configuredFeature(ResourceLocation.parse("vss:selector"));
        assertTrue(selector.isPresent(), "a forward placed-feature reference must not disable all configured models");
        assertEquals(Feature.RANDOM_SELECTOR, selector.get().feature());
        var config = (RandomFeatureConfiguration) selector.get().config();
        assertTrue(config.defaultFeature.isBound());
        assertEquals(Feature.SIMPLE_BLOCK, config.defaultFeature.value().feature().value().feature());
        assertTrue(registries.configuredFeature(ResourceLocation.parse("vss:ground")).isPresent());
    }
}
