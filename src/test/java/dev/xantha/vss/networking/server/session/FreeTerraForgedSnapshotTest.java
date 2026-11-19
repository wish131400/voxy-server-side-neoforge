package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FreeTerraForgedSnapshotTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ultraRuntimeStructureRulesAreCopiedWithoutSerializedHolderReferences(boolean empty) {
        var variant = FreeTerraForgedVariant.ULTRA;
        var registries = new java.util.ArrayList<net.minecraft.core.Registry<?>>();
        var codecs = new java.util.ArrayList<RegistryDataLoader.RegistryData<?>>();
        for (String path : List.of("worldgen/preset", "worldgen/noise", "worldgen/structure_rule")) {
            var key = ResourceKey.<String>createRegistryKey(ResourceLocation.parse(variant.namespace + ":" + path));
            var registry = new MappedRegistry<String>(key, Lifecycle.stable());
            if (!empty || !path.equals("worldgen/structure_rule"))
                registry.register(ResourceKey.create(key, ResourceLocation.parse(variant.namespace + ":preset")), path, RegistrationInfo.BUILT_IN);
            registries.add(registry.freeze());
            codecs.add(new RegistryDataLoader.RegistryData<>(key, Codec.STRING, false));
        }
        var access = new RegistryAccess.ImmutableRegistryAccess(registries);
        var dependencies = new WorldgenRegistryDependencies(access, codecs);
        WorldgenCodecSnapshot.snapshotFreeTerraForged(new JsonObject(), access, dependencies, variant.namespace::equals);
        var encoded = dependencies.encode().getAsJsonObject("ultraterraforged:worldgen/structure_rule");
        assertNotNull(encoded, "registryOrThrow also needs empty runtime registries");
        if (empty) assertTrue(encoded.entrySet().isEmpty());
        else assertEquals("worldgen/structure_rule", encoded.get("ultraterraforged:preset").getAsString());
    }
    @BeforeAll static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void allFamiliesCarryTheirOwnRuntimeRegistries() {
        for (var variant : FreeTerraForgedVariant.values()) {
            var presetKey = ResourceKey.<String>createRegistryKey(ResourceLocation.parse(variant.namespace + ":worldgen/preset"));
            var noiseKey = ResourceKey.<String>createRegistryKey(ResourceLocation.parse(variant.namespace + ":worldgen/noise"));
            var presets = new MappedRegistry<String>(presetKey, Lifecycle.stable());
            presets.register(ResourceKey.create(presetKey, ResourceLocation.parse(variant.namespace + ":preset")),
                    "active", RegistrationInfo.BUILT_IN);
            presets.freeze();
            var noises = new MappedRegistry<String>(noiseKey, Lifecycle.stable());
            noises.register(ResourceKey.create(noiseKey, ResourceLocation.parse(variant.namespace + ":terrain")),
                    "noise", RegistrationInfo.BUILT_IN);
            noises.freeze();
            var access = new RegistryAccess.ImmutableRegistryAccess(List.of(presets, noises));
            List<RegistryDataLoader.RegistryData<?>> codecs = List.of(
                    new RegistryDataLoader.RegistryData<>(presetKey, Codec.STRING, false),
                    new RegistryDataLoader.RegistryData<>(noiseKey, Codec.STRING, false));
            var dependencies = new WorldgenRegistryDependencies(access, codecs);
            var root = new JsonObject();
            WorldgenCodecSnapshot.snapshotFreeTerraForged(root, access, dependencies, variant.namespace::equals);
            assertTrue(root.get("vss_freeterraforged").getAsBoolean());
            assertEquals(variant.namespace, root.get("vss_freeterraforged_variant").getAsString());
            var encoded = dependencies.encode();
            assertEquals(2, encoded.size());
            assertEquals("active", encoded.getAsJsonObject(presetKey.location().toString())
                    .get(variant.namespace + ":preset").getAsString());
            assertEquals("noise", encoded.getAsJsonObject(noiseKey.location().toString())
                    .get(variant.namespace + ":terrain").getAsString());

            var inactive = new JsonObject();
            WorldgenCodecSnapshot.snapshotFreeTerraForged(inactive, access, dependencies, id -> false);
            assertFalse(inactive.has("vss_freeterraforged"));
            WorldgenCodecSnapshot.snapshotFreeTerraForged(inactive, RegistryAccess.EMPTY, dependencies, id -> true);
            assertFalse(inactive.has("vss_freeterraforged"));

            var missingNoise = new RegistryAccess.ImmutableRegistryAccess(List.of(presets));
            assertThrows(IllegalStateException.class, () -> WorldgenCodecSnapshot.snapshotFreeTerraForged(
                    new JsonObject(), missingNoise, new WorldgenRegistryDependencies(missingNoise, codecs), id -> true));
        }
    }
}
