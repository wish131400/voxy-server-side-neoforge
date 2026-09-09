package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Minimal dynamic registry set required to decode a noise generator snapshot. */
final class ClientWorldgenRegistries {
    private final MappedRegistry<NormalNoise.NoiseParameters> noises =
            new MappedRegistry<>(Registries.NOISE, Lifecycle.stable());
    private final MappedRegistry<DensityFunction> densityFunctions =
            new MappedRegistry<>(Registries.DENSITY_FUNCTION, Lifecycle.stable());
    private final MappedRegistry<ConfiguredWorldCarver<?>> configuredCarvers =
            new MappedRegistry<>(Registries.CONFIGURED_CARVER, Lifecycle.stable());
    private final MappedRegistry<ConfiguredFeature<?, ?>> configuredFeatures =
            new MappedRegistry<>(Registries.CONFIGURED_FEATURE, Lifecycle.stable());
    private final MappedRegistry<PlacedFeature> placedFeatures =
            new MappedRegistry<>(Registries.PLACED_FEATURE, Lifecycle.stable());
    private final MappedRegistry<Biome> biomes =
            new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
    private final MappedRegistry<Structure> structures =
            new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
    private final MappedRegistry<StructureSet> structureSets =
            new MappedRegistry<>(Registries.STRUCTURE_SET, Lifecycle.stable());
    private final MappedRegistry<net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool> templatePools =
            new MappedRegistry<>(Registries.TEMPLATE_POOL, Lifecycle.stable());
    private final MappedRegistry<net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList> processorLists =
            new MappedRegistry<>(Registries.PROCESSOR_LIST, Lifecycle.stable());
    private JsonObject templates = new JsonObject();
    private final Map<ResourceKey<? extends Registry<?>>, RegistryOps.RegistryInfo<?>> custom = new HashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, MappedRegistry<?>> extraRegistries = new java.util.LinkedHashMap<>();
    /**
     * Registry keys present in the server snapshot.  A registry must be
     * exposed through the snapshot lookup while it is being populated: a
     * density function can refer to an entry that has not been registered yet
     * and the old `hasEntries` gate incorrectly fell back to the client's
     * registry for that first decode.
     */
    private final Set<ResourceKey<? extends Registry<?>>> snapshotRegistries = new HashSet<>();
    private final Set<ResourceKey<? extends Registry<?>>> disabledCustom = new HashSet<>();
    private final Map<ResourceLocation, Integer> structureSetSalts = new HashMap<>();
    private final RegistryAccess fallback;
    private final RegistryOps<JsonElement> ops;

    private ClientWorldgenRegistries(RegistryAccess fallback) {
        this.fallback = fallback;
        registerInfo(noises);
        registerInfo(densityFunctions);
        registerInfo(configuredCarvers);
        registerInfo(configuredFeatures);
        registerInfo(placedFeatures);
        registerInfo(biomes);
        registerInfo(structures);
        registerInfo(structureSets);
        registerInfo(templatePools);
        registerInfo(processorLists);
        this.ops = RegistryOps.create(JsonOps.INSTANCE, this::lookup);
    }

    static ClientWorldgenRegistries decode(JsonObject root, RegistryAccess fallback) {
        return decode(root, fallback, net.neoforged.neoforge.registries.DataPackRegistriesHooks.getDataPackRegistries());
    }

    static ClientWorldgenRegistries decode(JsonObject root, RegistryAccess fallback,
            List<net.minecraft.resources.RegistryDataLoader.RegistryData<?>> codecs) {
        ClientWorldgenRegistries registries = new ClientWorldgenRegistries(fallback);
        // Configured selectors reference placed features, which in turn reference
        // configured features. Publish all snapshot lookups before decoding either.
        registries.declareSnapshot(root, "noises", Registries.NOISE);
        registries.declareSnapshot(root, "density_functions", Registries.DENSITY_FUNCTION);
        registries.declareSnapshot(root, "configured_carvers", Registries.CONFIGURED_CARVER);
        registries.declareSnapshot(root, "configured_features", Registries.CONFIGURED_FEATURE);
        registries.declareSnapshot(root, "placed_features", Registries.PLACED_FEATURE);
        registries.declareSnapshot(root, "biomes", Registries.BIOME);
        registries.declareSnapshot(root, "structures", Registries.STRUCTURE);
        registries.declareSnapshot(root, "structure_sets", Registries.STRUCTURE_SET);
        registries.declareSnapshot(root, "template_pools", Registries.TEMPLATE_POOL);
        registries.declareSnapshot(root, "processor_lists", Registries.PROCESSOR_LIST);
        if (root.has("structure_templates")) registries.templates = root.getAsJsonObject("structure_templates");
        var extra = root.has("custom_registries") ? root.getAsJsonObject("custom_registries") : new JsonObject();
        Map<String, net.minecraft.resources.RegistryDataLoader.RegistryData<?>> extraCodecs = new HashMap<>();
        for (var data : codecs) extraCodecs.put(data.key().location().toString(), data);
        for (var entry : extra.entrySet()) {
            var data = extraCodecs.get(entry.getKey());
            if (data == null || data.key().location().getNamespace().equals("minecraft")) {
                throw new IllegalStateException("Missing client worldgen registry codec: " + entry.getKey());
            }
            registries.declareExtra(data);
        }
        registries.registerAll(
                registries.noises,
                root.getAsJsonObject("noises"),
                NormalNoise.NoiseParameters.DIRECT_CODEC);
        for (var entry : extra.entrySet()) registries.decodeExtra(extraCodecs.get(entry.getKey()), entry.getValue().getAsJsonObject());
        registries.registerAll(
                registries.densityFunctions,
                root.getAsJsonObject("density_functions"),
                DensityFunction.DIRECT_CODEC);
        registries.registerOptional(registries.configuredCarvers, root, "configured_carvers",
                ConfiguredWorldCarver.DIRECT_CODEC);
        registries.registerOptional(registries.configuredFeatures, root, "configured_features",
                ConfiguredFeature.DIRECT_CODEC);
        registries.registerOptional(registries.placedFeatures, root, "placed_features",
                PlacedFeature.DIRECT_CODEC);
        registries.registerOptional(registries.biomes, root, "biomes", Biome.DIRECT_CODEC);
        registries.registerOptional(registries.processorLists, root, "processor_lists",
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.DIRECT_CODEC);
        registries.registerOptional(registries.templatePools, root, "template_pools",
                net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.DIRECT_CODEC);
        registries.registerOptional(registries.structures, root, "structures", Structure.DIRECT_CODEC);
        registries.registerOptional(registries.structureSets, root, "structure_sets",
                StructureSet.DIRECT_CODEC);
        registries.captureStructureSetSalts(root.getAsJsonObject("structure_sets"));
        registries.noises.freeze();
        registries.densityFunctions.freeze();
        registries.extraRegistries.values().forEach(MappedRegistry::freeze);
        registries.freezeOptional(registries.configuredCarvers);
        registries.freezeOptional(registries.configuredFeatures);
        registries.freezeOptional(registries.placedFeatures);
        registries.freezeOptional(registries.biomes);
        registries.freezeOptional(registries.processorLists);
        registries.freezeOptional(registries.templatePools);
        registries.freezeOptional(registries.structures);
        registries.freezeOptional(registries.structureSets);
        return registries;
    }

    private <T> void declareExtra(net.minecraft.resources.RegistryDataLoader.RegistryData<T> data) {
        var registry = new MappedRegistry<T>(data.key(), Lifecycle.stable());
        extraRegistries.put(data.key(), registry);
        snapshotRegistries.add(data.key());
        registerInfo(registry);
    }

    @SuppressWarnings("unchecked")
    private <T> void decodeExtra(net.minecraft.resources.RegistryDataLoader.RegistryData<T> data, JsonObject values) {
        registerAll((MappedRegistry<T>) extraRegistries.get(data.key()), values, data.elementCodec());
    }

    private void declareSnapshot(JsonObject root, String field, ResourceKey<? extends Registry<?>> key) {
        JsonElement value = root.get(field);
        if (value != null && value.isJsonObject() && !value.getAsJsonObject().entrySet().isEmpty()) {
            snapshotRegistries.add(key);
        }
    }

    JsonObject templates() { return templates; }

    /** Mirrors Lithostitched's server-start seed binding on isolated snapshot values only. */
    void bindWorldSeed(long seed) {
        var key = ResourceKey.createRegistryKey(ResourceLocation.parse("lithostitched:fast_noise_config"));
        Registry<?> configs = extraRegistries.get(key);
        if (configs == null || configs.size() == 0) return;
        try {
            Object first = configs.iterator().next();
            Class<?> configType = Class.forName(
                    "dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FastNoiseConfig",
                    false, first.getClass().getClassLoader());
            var bind = configType.getMethod("bind", long.class);
            for (Object config : configs) bind.invoke(configType.cast(config), seed);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new IllegalStateException("Cannot seed Lithostitched FastNoise snapshot", failure);
        }
    }

    /** Use the same holder instances for decoding, biome predicates and feature placement. */
    RegistryAccess access() {
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> merged = new java.util.LinkedHashMap<>();
        net.minecraft.core.registries.BuiltInRegistries.REGISTRY.forEach(registry -> merged.put(registry.key(), registry));
        fallback.registries().forEach(entry -> merged.put(entry.key(), entry.value()));
        merged.putAll(extraRegistries);
        for (Registry<?> registry : List.of(noises, densityFunctions, configuredCarvers, configuredFeatures,
                placedFeatures, biomes, processorLists, templatePools, structures, structureSets)) {
            if (!disabledCustom.contains(registry.key()) && snapshotRegistries.contains(registry.key()))
                merged.put(registry.key(), registry);
        }
        return new RegistryAccess.ImmutableRegistryAccess(List.copyOf(merged.values()));
    }

    RegistryOps<JsonElement> ops() {
        return ops;
    }

    HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noiseLookup() {
        return noises.asLookup();
    }

    boolean hasDensityNamespace(String namespace) {
        return densityFunctions.keySet().stream().anyMatch(id -> id.getNamespace().equals(namespace));
    }

    List<ResourceLocation> structureIds() {
        if (disabledCustom.contains(Registries.STRUCTURE)) return List.of();
        return new ArrayList<>(structures.keySet());
    }

    List<StructurePlacementInfo> structurePlacements() {
        if (disabledCustom.contains(Registries.STRUCTURE_SET)
                || disabledCustom.contains(Registries.STRUCTURE)) {
            return List.of();
        }
        List<StructurePlacementInfo> result = new ArrayList<>();
        for (Map.Entry<ResourceKey<StructureSet>, StructureSet> setEntry : structureSets.entrySet()) {
            StructureSet set = setEntry.getValue();
            net.minecraft.world.level.levelgen.structure.placement.StructurePlacement placement = set.placement();
            if (!(placement instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement spread)) {
                continue;
            }
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                ResourceLocation id = entry.structure().unwrapKey()
                        .map(key -> key.location()).orElse(null);
                if (id == null) continue;
                java.util.Set<ResourceLocation> biomes = new java.util.HashSet<>();
                Optional<Structure> structure = entry.structure().unwrapKey()
                        .flatMap(key -> structures.getHolder(key).map(holder -> holder.value()));
                structure.ifPresent(value -> value.biomes().stream()
                        .map(holder -> holder.unwrapKey().map(key -> key.location()).orElse(null))
                        .filter(java.util.Objects::nonNull).forEach(biomes::add));
                // StructurePlacement.salt is intentionally protected in
                // vanilla; the codec's stable structure id supplies an
                // equivalent deterministic salt for the client probe.
                int salt = structureSetSalts.getOrDefault(setEntry.getKey().location(), id.hashCode());
                result.add(new StructurePlacementInfo(id, spread.spacing(), spread.separation(),
                        salt, spread.spreadType(), java.util.Set.copyOf(biomes)));
            }
        }
        return List.copyOf(result);
    }

    record StructurePlacementInfo(ResourceLocation id, int spacing, int separation, int salt,
                                  net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType spreadType,
                                  java.util.Set<ResourceLocation> biomes) { }

    private void captureStructureSetSalts(JsonObject object) {
        if (object == null) return;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement placement = entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject().get("placement") : null;
            if (placement != null && placement.isJsonObject()
                    && placement.getAsJsonObject().has("salt")) {
                structureSetSalts.put(ResourceLocation.parse(entry.getKey()),
                        placement.getAsJsonObject().get("salt").getAsInt());
            }
        }
    }

    /** Returns a decoded configured feature from the server snapshot. */
    java.util.Optional<ConfiguredFeature<?, ?>> configuredFeature(ResourceLocation id) {
        if (id == null || disabledCustom.contains(Registries.CONFIGURED_FEATURE)) {
            return java.util.Optional.empty();
        }
        ResourceKey<ConfiguredFeature<?, ?>> key = ResourceKey.create(
                Registries.CONFIGURED_FEATURE, id);
        return configuredFeatures.getHolder(key).map(holder -> holder.value());
    }

    List<ResourceLocation> configuredFeatureIds() {
        if (disabledCustom.contains(Registries.CONFIGURED_FEATURE)) return List.of();
        return new ArrayList<>(configuredFeatures.keySet());
    }

    private <T> void registerInfo(MappedRegistry<T> registry) {
        custom.put(registry.key(), new RegistryOps.RegistryInfo<>(
                registry.holderOwner(),
                registry.createRegistrationLookup(),
                Lifecycle.stable()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> Optional<RegistryOps.RegistryInfo<T>> lookup(
            ResourceKey<? extends Registry<? extends T>> key) {
        RegistryOps.RegistryInfo<?> info = custom.get(key);
        if (info != null && !disabledCustom.contains(key)
                && (snapshotRegistries.contains(key) || hasEntries(key))) {
            return Optional.of((RegistryOps.RegistryInfo<T>) info);
        }
        return ((Optional) fallback.lookup((ResourceKey) key))
                .map(value -> RegistryOps.RegistryInfo.fromRegistryLookup((HolderLookup.RegistryLookup<T>) value));
    }

    private <T> void registerAll(MappedRegistry<T> registry, JsonObject object, Codec<T> codec) {
        if (object == null) {
            throw new IllegalArgumentException("Missing synced registry " + registry.key().location());
        }
        if (object.entrySet().isEmpty()) {
            // An empty optional registry should fall back to the client's
            // built-in lookup instead of publishing an empty custom lookup.
            return;
        }
        // Publish the lookup before decoding the first value.  This is
        // required for self-referential density functions and noise entries.
        snapshotRegistries.add(registry.key());
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            ResourceKey<T> key = ResourceKey.create(registry.key(), ResourceLocation.parse(entry.getKey()));
            try {
                T value = codec.parse(ops, entry.getValue()).getOrThrow();
                registry.register(key, value, RegistrationInfo.BUILT_IN);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Cannot reconstruct registry " + registry.key().location()
                        + " entry " + entry.getKey(), failure);
            }
        }
    }

    private <T> void registerOptional(MappedRegistry<T> registry, JsonObject root,
                                       String field, Codec<T> codec) {
        JsonElement element = root.get(field);
        if (element != null && element.isJsonObject()) {
            try {
                if (!element.getAsJsonObject().entrySet().isEmpty()) {
                    registerAll(registry, element.getAsJsonObject(), codec);
                }
            } catch (RuntimeException exception) {
                // A modded feature/structure codec may not exist on this
                // client. Keep the critical density path alive and let
                // RegistryOps fall back to the client's own registry.
                disabledCustom.add(registry.key());
                snapshotRegistries.remove(registry.key());
            }
        }
    }

    private <T> void freezeOptional(MappedRegistry<T> registry) {
        // A failed codec can leave forward-reference holders unbound. Freezing
        // that partial registry throws and used to discard otherwise valid
        // noise/density snapshots. Disabled optional registries are never
        // published by lookup(), so leaving them mutable is both safe and
        // keeps critical terrain reconstruction available.
        if (!disabledCustom.contains(registry.key())) {
            try {
                registry.freeze();
            } catch (RuntimeException exception) {
                // A missing optional dependency can leave cross-registry holders
                // unbound even when this registry's own values decoded successfully.
                disabledCustom.add(registry.key());
                snapshotRegistries.remove(registry.key());
            }
        }
    }

    private boolean hasEntries(ResourceKey<? extends Registry<?>> key) {
        if (key.equals(Registries.CONFIGURED_FEATURE)) return !configuredFeatures.entrySet().isEmpty();
        if (key.equals(Registries.PLACED_FEATURE)) return !placedFeatures.entrySet().isEmpty();
        if (key.equals(Registries.BIOME)) return !biomes.entrySet().isEmpty();
        if (key.equals(Registries.STRUCTURE)) return !structures.entrySet().isEmpty();
        if (key.equals(Registries.STRUCTURE_SET)) return !structureSets.entrySet().isEmpty();
        if (key.equals(Registries.TEMPLATE_POOL)) return !templatePools.entrySet().isEmpty();
        if (key.equals(Registries.PROCESSOR_LIST)) return !processorLists.entrySet().isEmpty();
        if (key.equals(Registries.NOISE)) return !noises.entrySet().isEmpty();
        if (key.equals(Registries.DENSITY_FUNCTION)) return !densityFunctions.entrySet().isEmpty();
        if (key.equals(Registries.CONFIGURED_CARVER)) return !configuredCarvers.entrySet().isEmpty();
        return false;
    }
}
