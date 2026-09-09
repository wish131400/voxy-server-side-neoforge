package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.common.worldgen.DensityFunctionSnapshot;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Codec snapshot equivalent to the worldgen dump, owned by VSS. */
final class WorldgenCodecSnapshot {
    private WorldgenCodecSnapshot() {
    }

    static Encoded encodeRegistries(RegistryAccess access) {
        return encodeRegistries(access, null);
    }

    static Encoded encodeRegistries(RegistryAccess access, net.minecraft.server.packs.resources.ResourceManager resources) {
        return encodeRegistries(access, resources, new DensityFunctionReferences(), new WorldgenRegistryDependencies(access));
    }

    static Encoded encodeRegistries(RegistryAccess access, net.minecraft.server.packs.resources.ResourceManager resources,
                                    DensityFunctionReferences references, WorldgenRegistryDependencies dependencies) {
        RegistryOps<JsonElement> ops = dependencies.ops();
        JsonObject root = new JsonObject();
        root.add("density_functions", encodeDensities(access, ops, references));
        for (var entry : references.definitions().entrySet()) {
            root.getAsJsonObject("density_functions").add(entry.getKey(), entry.getValue());
        }
        root.add("noises", encodeRegistry(
                access, ops, Registries.NOISE, NormalNoise.NoiseParameters.DIRECT_CODEC));
        // Keep the same worldgen inputs as the WorldgenDump.  The
        // client can then run feature/structure probes without asking the
        // server for another metadata round trip.
        root.add("biomes", encodeOptional(access, ops, Registries.BIOME, Biome.DIRECT_CODEC));
        root.add("configured_carvers", encodeOptional(
                access, ops, Registries.CONFIGURED_CARVER, ConfiguredWorldCarver.DIRECT_CODEC));
        root.add("placed_features", encodeOptional(
                access, ops, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC));
        root.add("configured_features", encodeOptional(
                access, ops, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC));
        root.add("structure_sets", encodeOptional(
                access, ops, Registries.STRUCTURE_SET, StructureSet.DIRECT_CODEC));
        root.add("structures", encodeOptionalStructures(access, ops));
        root.add("processor_lists", encodeOptional(access, ops, Registries.PROCESSOR_LIST,
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.DIRECT_CODEC));
        root.add("template_pools", encodeOptional(access, ops, Registries.TEMPLATE_POOL,
                net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.DIRECT_CODEC));
        if (resources != null) root.add("structure_templates", encodeTemplates(resources));
        root.add("custom_registries", dependencies.encode());
        return compress(root);
    }

    private static JsonObject encodeTemplates(net.minecraft.server.packs.resources.ResourceManager resources) {
        JsonObject templates = new JsonObject();
        int total = 0;
        // Stay below the existing 8 MiB compressed metadata limit. Vanilla's
        // complete template set is about 3.5 MiB. Missing mod templates fail
        // closed on the client, rather than inventing a replacement building.
        for (var entry : resources.listResources("structure", id -> id.getPath().endsWith(".nbt"))
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            try (var input = entry.getValue().open()) {
                byte[] bytes = input.readNBytes(524_289);
                if (bytes.length > 524_288 || total + bytes.length > 5 * 1024 * 1024) continue;
                total += bytes.length;
                String path = entry.getKey().getPath();
                ResourceLocation id = entry.getKey().withPath(path.substring("structure/".length(), path.length() - 4));
                templates.addProperty(id.toString(), java.util.Base64.getEncoder().encodeToString(bytes));
            } catch (java.io.IOException exception) {
                dev.xantha.vss.common.VSSLogger.debug("VSS structure template unavailable: " + entry.getKey());
            }
        }
        return templates;
    }

    static Encoded encodeGenerator(ChunkGenerator generator, RegistryAccess access, DensityFunctionReferences references,
                                   WorldgenRegistryDependencies dependencies) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            return Encoded.empty();
        }
        RegistryOps<JsonElement> ops = dependencies.ops();
        JsonObject root = new JsonObject();
        NoiseGeneratorSettings settings = noise.generatorSettings().value();
        if (requiresAppliedDensityGraph()) settings = DensityFunctionSnapshot.applied(settings);
        JsonObject encodedSettings = NoiseGeneratorSettings.DIRECT_CODEC
                .encodeStart(ops, settings)
                .getOrThrow().getAsJsonObject();
        root.add("settings", requiresAppliedDensityGraph() ? references.settings(encodedSettings) : encodedSettings);
        if (generator.getClass() != NoiseBasedChunkGenerator.class) {
            root.addProperty("vss_unsupported_reason", "Custom noise generator requires a registered backend: "
                    + generator.getClass().getName());
        }
        root.add("biome_source", encodeBiomeSource(noise.getBiomeSource(), access, ops));
        // TerraBlender's positional region tree/surface-rule context is not
        // serialized by the vanilla codecs. A plain reconstructed generator
        // would draw the wrong biomes even with matching client mod jars.
        var mods = net.neoforged.fml.ModList.get();
        JsonObject noiseSeeds = noiseSeedAliases(access.registryOrThrow(Registries.NOISE).keySet(),
                mods != null && mods.isLoaded("tectonic"));
        if (!noiseSeeds.isEmpty()) root.add("noise_seed_aliases", noiseSeeds);
        var rtfPreset = ResourceKey.createRegistryKey(ResourceLocation.parse("reterraforged:worldgen/preset"));
        if (mods != null && mods.isLoaded("reterraforged")
                && access.registry(rtfPreset).map(registry -> registry.containsKey(
                        ResourceLocation.parse("reterraforged:preset"))).orElse(false)) {
            // FreeTerraForged's RandomState initializer reads these directly,
            // even though its CellSampler markers do not encode preset holders.
            dependencies.require(ResourceLocation.parse("reterraforged:worldgen/preset"));
            dependencies.require(ResourceLocation.parse("reterraforged:worldgen/noise"));
            root.addProperty("vss_freeterraforged", true);
        }
        if (mods != null && mods.isLoaded("terrablender")
                && noise.getBiomeSource() instanceof MultiNoiseBiomeSource) {
            root.addProperty("vss_unsupported_reason",
                    "TerraBlender positional regions require a registered prediction backend");
        }
        return compress(root);
    }

    // Tectonic's NoisesMixin changes only the positional RNG name. The
    // parameters still belong to the original noise registry entry. A data
    // pack with the same namespace, without this mixin, must keep its own seed.
    static JsonObject noiseSeedAliases(java.util.Set<ResourceLocation> noiseIds, boolean tectonicLoaded) {
        JsonObject aliases = new JsonObject();
        if (tectonicLoaded) noiseIds.stream().sorted().forEach(id -> {
            if (id.getNamespace().equals("tectonic") && id.getPath().startsWith("parameter/")) {
                aliases.addProperty(id.toString(), ResourceLocation.withDefaultNamespace(
                        id.getPath().substring("parameter/".length())).toString());
            }
        });
        return aliases;
    }

    private static boolean requiresAppliedDensityGraph() {
        var mods = net.neoforged.fml.ModList.get();
        return mods != null && (mods.isLoaded("tectonic") || mods.isLoaded("lithostitched"));
    }

    private static JsonObject encodeDensities(RegistryAccess access, RegistryOps<JsonElement> ops,
                                              DensityFunctionReferences references) {
        if (!requiresAppliedDensityGraph()) {
            return encodeRegistry(access, ops, Registries.DENSITY_FUNCTION, DensityFunction.DIRECT_CODEC);
        }
        JsonObject result = new JsonObject();
        for (var entry : access.registryOrThrow(Registries.DENSITY_FUNCTION).entrySet()) {
            try {
                var encoded = DensityFunction.DIRECT_CODEC.encodeStart(ops,
                        DensityFunctionSnapshot.applied(entry.getValue())).getOrThrow();
                result.add(entry.getKey().location().toString(), references.compact(encoded));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Cannot snapshot density " + entry.getKey().location(), failure);
            }
        }
        return result;
    }

    /**
     * Resolve vanilla's compact multi-noise preset form before sending the
     * generator to a client. A client connection does not expose the
     * multi_noise_biome_source_parameter_list registry, so leaving
     * {"preset":"minecraft:overworld"} in the payload makes BiomeSource's
     * codec fail and silently selects VSS's deterministic fallback sampler.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonElement encodeBiomeSource(BiomeSource source, RegistryAccess access,
                                                 RegistryOps<JsonElement> ops) {
        JsonObject encoded = BiomeSource.CODEC.encodeStart(ops, source)
                .getOrThrow().getAsJsonObject();
        if (encoded.has("preset")) {
            ResourceLocation presetId = ResourceLocation.parse(encoded.get("preset").getAsString());
            MultiNoiseBiomeSourceParameterList preset = access
                    .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .get(presetId);
            if (preset == null) {
                throw new IllegalStateException("Unknown multi-noise preset " + presetId);
            }
            JsonObject resolved = MultiNoiseBiomeSource.DIRECT_CODEC.codec()
                    .encodeStart(ops, preset.parameters()).getOrThrow().getAsJsonObject();
            resolved.addProperty("type", "minecraft:multi_noise");
            return resolved;
        }
        // Convert HolderSet encodings to stable biome ids. This keeps the
        // payload independent of client-side holder internals and mirrors the
        // handling used for inline multi-noise biome lists.
        if (encoded.has("biomes")
                && (!encoded.get("biomes").isJsonArray()
                || containsInlineBiome(encoded.getAsJsonArray("biomes")))) {
            HolderSet biomes = Biome.LIST_CODEC.parse(ops, encoded.get("biomes")).getOrThrow();
            JsonArray ids = new JsonArray();
            biomes.stream().forEach(value -> {
                Holder<?> holder = (Holder<?>) value;
                String id = holder.unwrapKey()
                        .map(key -> key.location().toString())
                        .orElseThrow(() -> new IllegalStateException(
                                "Inline biomes cannot be sampled"));
                ids.add(id);
            });
            encoded.add("biomes", ids);
        }
        return encoded;
    }

    private static boolean containsInlineBiome(JsonArray biomes) {
        for (JsonElement element : biomes) {
            if (element.isJsonPrimitive()
                    || element.isJsonObject() && element.getAsJsonObject().has("parameters")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static <T> JsonObject encodeRegistry(
            RegistryAccess access,
            RegistryOps<JsonElement> ops,
            ResourceKey<? extends Registry<T>> registryKey,
            Codec<T> codec) {
        JsonObject object = new JsonObject();
        for (Map.Entry<ResourceKey<T>, T> entry : access.registryOrThrow(registryKey).entrySet()) {
            JsonElement encoded;
            try {
                encoded = codec.encodeStart(ops, entry.getValue()).getOrThrow();
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Cannot snapshot " + registryKey.location()
                        + " entry " + entry.getKey().location(), exception);
            }
            if (entry.getValue() instanceof ConfiguredFeature<?, ?> feature) {
                encoded = dev.xantha.vss.common.worldgen.VanillaFeatureSnapshot.correct(feature, encoded);
            }
            object.add(entry.getKey().location().toString(), encoded);
        }
        return object;
    }

    private static JsonObject encodeStructures(RegistryAccess access, RegistryOps<JsonElement> ops) {
        JsonObject object = new JsonObject();
        Registry<Structure> registry = access.registryOrThrow(Registries.STRUCTURE);
        for (Structure structureValue : registry) {
            Map.Entry<ResourceKey<Structure>, Structure> entry = Map.entry(registry.getResourceKey(structureValue).orElseThrow(), structureValue);
            JsonObject encoded = Structure.DIRECT_CODEC.encodeStart(ops, entry.getValue())
                    .getOrThrow().getAsJsonObject();
            JsonArray biomes = new JsonArray();
            entry.getValue().biomes().stream()
                    .map(holder -> holder.unwrapKey()
                            .map(key -> key.location().toString()).orElse(""))
                    .filter(value -> !value.isEmpty())
                    .forEach(biomes::add);
            encoded.add("biomes", biomes);
            object.add(entry.getKey().location().toString(), encoded);
        }
        return object;
    }

    private static <T> JsonObject encodeOptional(RegistryAccess access, RegistryOps<JsonElement> ops,
                                                 ResourceKey<? extends Registry<T>> key,
                                                 Codec<T> codec) {
        try {
            return encodeRegistry(access, ops, key, codec);
        } catch (RuntimeException exception) {
            return new JsonObject();
        }
    }

    private static JsonObject encodeOptionalStructures(RegistryAccess access,
                                                       RegistryOps<JsonElement> ops) {
        try {
            return encodeStructures(access, ops);
        } catch (RuntimeException exception) {
            return new JsonObject();
        }
    }

    private static Encoded compress(JsonObject object) {
        byte[] raw = dev.xantha.vss.common.worldgen.WorldgenJson.bytes(object);
        LodByteCompression.Result compressed = LodByteCompression.compressForNetwork(raw, false);
        return new Encoded(compressed.method(), compressed.originalLength(), compressed.bytes());
    }

    record Encoded(int compression, int rawSize, byte[] bytes) {
        static Encoded empty() {
            return new Encoded(LodByteCompression.METHOD_NONE, 0, new byte[0]);
        }
    }
}
