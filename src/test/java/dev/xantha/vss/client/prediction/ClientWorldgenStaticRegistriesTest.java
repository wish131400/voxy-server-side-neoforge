package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.networking.server.session.WorldgenRegistrySnapshotTestBridge;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientWorldgenStaticRegistriesTest {
    @BeforeAll
    static void bootstrapMinecraft() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void completeVanillaSnapshotRestoresDecorationWithOnlyTheActualNetworkRegistryLayer() throws Exception {
        var statics = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource()))) {
            // Load actual vanilla data and tags. Datagen fixture values are not
            // a runtime registry: their embedded holders have a different owner.
            var dynamic = RegistryDataLoader.load(resources, statics, RegistryDataLoader.WORLDGEN_REGISTRIES);
            dynamic.registries().forEach(entry -> bindTags(entry.value(), resources));
            var serverValues = new LinkedHashMap<ResourceKey<? extends Registry<?>>, Registry<?>>();
            statics.registries().forEach(entry -> serverValues.put(entry.key(), entry.value()));
            dynamic.registries().forEach(entry -> serverValues.put(entry.key(), entry.value()));
            var server = new RegistryAccess.ImmutableRegistryAccess(serverValues);
            var snapshot = WorldgenRegistrySnapshotTestBridge.encode(server);
            var strongholdsId = ResourceLocation.withDefaultNamespace("strongholds");
            var sourceRings = (ConcentricRingsStructurePlacement) server.registryOrThrow(Registries.STRUCTURE_SET)
                    .getOptional(strongholdsId).orElseThrow().placement();
            assertTrue(sourceRings.preferredBiomes().size() > 0, "Load the actual stronghold biome tag");
            assertTrue(snapshot.getAsJsonObject("structure_sets").getAsJsonObject(strongholdsId.toString())
                    .getAsJsonObject("placement").get("preferred_biomes").isJsonArray(),
                    "Stronghold preferred biome tags must travel as explicit server biome IDs");

            var packed = new LinkedHashMap<ResourceKey<? extends Registry<?>>,
                    List<RegistrySynchronization.PackedRegistryEntry>>();
            RegistrySynchronization.packRegistries(server.createSerializationContext(NbtOps.INSTANCE),
                    server, Set.of(), packed::put);
            var network = RegistryDataLoader.load(packed, ResourceProvider.EMPTY, statics,
                    RegistryDataLoader.SYNCHRONIZED_REGISTRIES);
            assertEquals(RegistrySynchronization.NETWORKABLE_REGISTRIES,
                    network.listRegistries().collect(Collectors.toSet()));
            for (var absent : List.of(Registries.BLOCK, Registries.FLUID, Registries.ENTITY_TYPE,
                    Registries.CONFIGURED_FEATURE, Registries.PLACED_FEATURE, Registries.STRUCTURE,
                    Registries.STRUCTURE_SET, Registries.NOISE, Registries.DENSITY_FUNCTION)) {
                assertFalse(network.listRegistries().anyMatch(absent::equals),
                        "The client fallback must contain only its actual network layer: " + absent);
            }
            for (var biome : network.registryOrThrow(Registries.BIOME)) {
                assertTrue(biome.getGenerationSettings().features().isEmpty(),
                        "Network biome codecs must not smuggle server decoration into the fallback");
                for (var step : GenerationStep.Carving.values())
                    assertFalse(biome.getGenerationSettings().getCarvers(step).iterator().hasNext());
            }

            var decoded = ClientWorldgenRegistries.decode(snapshot, network, List.of());
            var restored = decoded.access();
            for (var key : List.of(Registries.NOISE, Registries.DENSITY_FUNCTION, Registries.CONFIGURED_CARVER,
                    Registries.CONFIGURED_FEATURE, Registries.PLACED_FEATURE, Registries.BIOME,
                    Registries.PROCESSOR_LIST, Registries.TEMPLATE_POOL, Registries.STRUCTURE, Registries.STRUCTURE_SET))
                assertRegistryKeys(server, restored, key);
            assertSame(BuiltInRegistries.BLOCK, restored.registryOrThrow(Registries.BLOCK));
            assertSame(BuiltInRegistries.BLOCK.holderOwner(), decoded.ops().owner(Registries.BLOCK).orElseThrow());
            var configured = restored.registryOrThrow(Registries.CONFIGURED_FEATURE);
            assertEquals(Feature.SIMPLE_RANDOM_SELECTOR,
                    configured.getOptional(ResourceLocation.withDefaultNamespace("pointed_dripstone")).orElseThrow().feature());
            assertEquals(Feature.TREE, configured.getOptional(ResourceLocation.withDefaultNamespace("oak")).orElseThrow().feature());

            var placed = restored.registryOrThrow(Registries.PLACED_FEATURE);
            placed.forEach(feature -> {
                assertTrue(feature.feature().isBound());
                assertSame(configured.getHolderOrThrow(feature.feature().unwrapKey().orElseThrow()), feature.feature());
            });
            var biomes = restored.registryOrThrow(Registries.BIOME);
            var plains = biomes.getHolderOrThrow(Biomes.PLAINS).value();
            assertNotSame(network.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS).value(), plains);
            assertTrue(plains.getGenerationSettings().features().stream().anyMatch(set -> set.size() > 0));
            for (var biome : biomes) {
                biome.getGenerationSettings().features().forEach(set -> set.forEach(holder -> {
                    assertTrue(holder.isBound());
                    assertSame(placed.getHolderOrThrow(holder.unwrapKey().orElseThrow()), holder);
                }));
                for (var step : GenerationStep.Carving.values())
                    biome.getGenerationSettings().getCarvers(step).forEach(holder -> assertTrue(holder.isBound()));
            }
            var structures = restored.registryOrThrow(Registries.STRUCTURE);
            structures.forEach(structure -> {
                assertTrue(structure.biomes().size() > 0, "Real vanilla structure biome tags must be resolved");
                structure.biomes().forEach(holder -> {
                    assertTrue(holder.isBound());
                    assertSame(biomes.getHolderOrThrow(holder.unwrapKey().orElseThrow()), holder);
                });
            });
            restored.registryOrThrow(Registries.STRUCTURE_SET).forEach(set -> set.structures().forEach(entry -> {
                assertTrue(entry.structure().isBound());
                assertSame(structures.getHolderOrThrow(entry.structure().unwrapKey().orElseThrow()), entry.structure());
            }));
            var restoredRings = (ConcentricRingsStructurePlacement) restored.registryOrThrow(Registries.STRUCTURE_SET)
                    .getOptional(strongholdsId).orElseThrow().placement();
            assertTrue(restoredRings.preferredBiomes().unwrapKey().isEmpty(),
                    "The snapshot must not retain an unbound server biome tag");
            assertEquals(sourceRings.preferredBiomes().stream().map(holder -> holder.unwrapKey().orElseThrow())
                            .collect(Collectors.toSet()),
                    restoredRings.preferredBiomes().stream().map(holder -> holder.unwrapKey().orElseThrow())
                            .collect(Collectors.toSet()));
            restoredRings.preferredBiomes().forEach(holder ->
                    assertSame(biomes.getHolderOrThrow(holder.unwrapKey().orElseThrow()), holder));
            restored.registryOrThrow(Registries.TEMPLATE_POOL).forEach(pool -> assertTrue(pool.getFallback().isBound()));
        }
    }

    private static <T> void bindTags(Registry<T> registry, ResourceManager resources) {
        var tags = new TagLoader<Holder<T>>(id -> registry.getHolder(id).map(holder -> holder),
                Registries.tagsDirPath(registry.key())).loadAndBuild(resources);
        var resolved = new LinkedHashMap<TagKey<T>, List<Holder<T>>>();
        tags.forEach((id, values) -> resolved.put(TagKey.create(registry.key(), id), List.copyOf(values)));
        registry.bindTags(resolved);
    }

    private static void assertRegistryKeys(RegistryAccess server, RegistryAccess client,
            ResourceKey<? extends Registry<?>> key) {
        var expected = server.registries().filter(entry -> entry.key().equals(key))
                .findFirst().orElseThrow().value().keySet();
        assertFalse(expected.isEmpty(), "Exercise the real vanilla registry: " + key);
        var actual = client.registries().filter(entry -> entry.key().equals(key))
                .findFirst().orElseThrow().value().keySet();
        assertEquals(expected, actual,
                "No optional vanilla registry may silently fall back or disappear: " + key);
    }
}
