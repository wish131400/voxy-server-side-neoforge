package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import dev.xantha.vss.networking.server.session.WorldgenRegistrySnapshotTestBridge;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
class LithostitchedBiomeSourceSnapshotTest {
    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    @SuppressWarnings("unchecked")
    void releasedInjectorCodecRestoresNestedPresetWithoutAClientPresetRegistry() throws Exception {
        var statics = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource()));
             var loader = new URLClassLoader(new java.net.URL[]{
                     Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL()}, getClass().getClassLoader())) {
            var dynamic = RegistryDataLoader.load(resources, statics, RegistryDataLoader.WORLDGEN_REGISTRIES);
            dynamic.registries().forEach(entry -> ClientWorldgenStaticRegistriesTest.bindTags(entry.value(), resources));
            var values = new LinkedHashMap<ResourceKey<? extends Registry<?>>, Registry<?>>();
            statics.registries().forEach(entry -> values.put(entry.key(), entry.value()));
            dynamic.registries().forEach(entry -> values.put(entry.key(), entry.value()));
            var server = new RegistryAccess.ImmutableRegistryAccess(values);
            var snapshot = WorldgenRegistrySnapshotTestBridge.encode(server);
            var client = ClientWorldgenRegistries.decode(snapshot, RegistryAccess.EMPTY, List.of());
            assertTrue(client.access().registry(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).isEmpty());
            var key = ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                    ResourceLocation.withDefaultNamespace("overworld"));
            var source = MultiNoiseBiomeSource.createFromPreset(server.registryOrThrow(
                    Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getHolderOrThrow(key));
            var type = loader.loadClass(
                    "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource");
            var wrapper = (BiomeSource) type.getConstructor(BiomeSource.class).newInstance(source);
            var codec = ((MapCodec<BiomeSource>) type.getField("CODEC").get(null)).codec();
            // Use the release's actual wrapper codec without registering mod types
            // into the JVM's frozen production registry or applying game Mixins.
            var raw = codec.encodeStart(server.createSerializationContext(JsonOps.INSTANCE), wrapper).getOrThrow();
            assertTrue(raw.getAsJsonObject().getAsJsonObject("delegate").has("preset"));
            var failure = codec.parse(client.ops(), raw).error().orElseThrow();
            assertTrue(failure.message().contains("multi_noise_biome_source_parameter_list"));
            var encoded = raw.deepCopy();
            WorldgenRegistrySnapshotTestBridge.resolveBiomePresets(encoded, server);
            assertTrue(encoded.getAsJsonObject().has("delegate"));
            assertFalse(encoded.getAsJsonObject().getAsJsonObject("delegate").has("preset"));
            var restored = assertInstanceOf(MultiNoiseBiomeSource.class, codec.parse(client.ops(), encoded).getOrThrow());
            ClientWorldgenStaticRegistriesTest.assertClimateBiomes(source, restored, client);
        }
    }
}
