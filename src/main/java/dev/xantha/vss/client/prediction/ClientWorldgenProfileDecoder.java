package dev.xantha.vss.client.prediction;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

/** Reconstructs Minecraft noise generators from the VSS Codec snapshot. */
final class ClientWorldgenProfileDecoder {
    private static final Gson GSON = new Gson();

    private ClientWorldgenProfileDecoder() {
    }

    static Map<ResourceKey<Level>, ClientTerrainSampler> decode(
            WorldgenProfileS2CPayload payload,
            RegistryAccess clientRegistries) throws IOException {
        byte[] registryJson = LodByteCompression.decompress(
                payload.registries(),
                payload.registriesCompression(),
                payload.registriesRawSize(),
                WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES);
        JsonObject registryRoot = parse(registryJson);
        Map<ResourceKey<Level>, ClientTerrainSampler> samplers = new LinkedHashMap<>();
        ClientWorldgenRegistries registries;
        try {
            registries = ClientWorldgenRegistries.decode(registryRoot, clientRegistries);
            // Lithostitched binds shared configs to the server world seed, not
            // individual dimension seeds. RandomState does not perform this step.
            registries.bindWorldSeed(payload.seed());
        } catch (RuntimeException exception) {
            VSSLogger.warn("VSS worldgen registry snapshot could not be reconstructed; "
                    + "prediction disabled; real Voxy LOD remains available", exception);
            return samplers;
        }
        // Templates belong to the Java surface stage. Avoid parsing/copying
        // several MiB of Base64 building data into every native terrain graph.
        registryRoot.remove("structure_templates");
        for (DimensionProfile profile : payload.dimensions()) {
            java.util.Optional<ClientTerrainSampler> custom = PredictionTerrainBackends.open(
                    profile, profile.seed(), clientRegistries);
            if (custom.isPresent()) {
                samplers.put(profile.levelKey(), custom.get());
                continue;
            }
            if (profile.generatorData().length == 0) {
                VSSLogger.warn("VSS prediction unavailable for " + profile.dimension()
                        + ": generator supplied no reproducible snapshot (" + profile.generatorType() + ")");
                continue;
            }
            try {
                byte[] generatorJson = LodByteCompression.decompress(
                        profile.generatorData(),
                        profile.generatorCompression(),
                        profile.generatorRawSize(),
                        WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES);
                JsonObject generatorRoot = parse(generatorJson);
                String unsupported = PredictionWorldgenCapabilities.rejection(generatorRoot);
                if (unsupported != null) {
                    VSSLogger.warn("VSS prediction unavailable for " + profile.dimension() + ": " + unsupported);
                    continue;
                }
                ClientTerrainSampler javaSampler = null;
                try {
                    javaSampler = decodeJavaSampler(profile, generatorJson, registries, clientRegistries);
                } catch (RuntimeException exception) {
                    VSSLogger.warn("VSS Java biome/feature context unavailable for "
                            + profile.dimension(), exception);
                }
                // BetterEnd-New-Dawn keeps the vanilla NoiseBasedChunkGenerator
                // codec but replaces NoiseChunk.fillSlice with its PAULEVS
                // island SDF.  The native density graph cannot see that mixin,
                // so install the optional adapter before considering native
                // sampling.  The adapter preserves the decoded biome/feature
                // context and only replaces surfaceY.
                ClientTerrainSampler betterEndSampler = javaSampler == null ? null
                        : BetterEndCompat.wrap(profile, javaSampler);
                if (betterEndSampler != null) {
                    samplers.put(profile.levelKey(), betterEndSampler);
                    continue;
                }
                // Keep Java registry/structure context for codecs which a
                // terrain or feature extension implements outside vanilla.
                String nativeRejection = PredictionWorldgenCapabilities.nativeTerrainRejection(generatorRoot, registryRoot);
                ClientTerrainSampler rustSampler = javaSampler != null && nativeRejection == null
                        ? RustTerrainSampler.open(profile, generatorRoot, registryRoot, javaSampler) : null;
                if (rustSampler != null) {
                    samplers.put(profile.levelKey(), rustSampler);
                    continue;
                }
                if (javaSampler != null) {
                    samplers.put(profile.levelKey(), javaSampler);
                    if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging) {
                        VSSLogger.debug("VSS prediction backend: dimension=" + profile.dimension()
                                + ", terrain=java, decoration=java, reason="
                                + (nativeRejection != null ? nativeRejection : "native library/graph unavailable"));
                    }
                }
                else VSSLogger.warn("VSS prediction unavailable for " + profile.dimension() + ": missing client codecs");
            } catch (RuntimeException exception) {
                VSSLogger.warn("VSS could not reconstruct worldgen for "
                        + profile.dimension() + "; prediction disabled for this dimension", exception);
            }
        }
        return samplers;
    }

    static ClientTerrainSampler decodeJavaSampler(DimensionProfile profile,
            byte[] generatorJson, ClientWorldgenRegistries registries, RegistryAccess clientRegistries) {
        JsonObject generatorRoot = parse(generatorJson);
        NoiseGeneratorSettings settings = NoiseGeneratorSettings.DIRECT_CODEC
                .parse(registries.ops(), generatorRoot.get("settings"))
                .getOrThrow();
        BiomeSource biomeSource = BiomeSource.CODEC
                .parse(registries.ops(), generatorRoot.get("biome_source"))
                .getOrThrow();
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(
                biomeSource, Holder.direct(settings));
        boolean freeTerraForged = generatorRoot.has("vss_freeterraforged")
                && generatorRoot.get("vss_freeterraforged").getAsBoolean();
        RandomState randomState = FreeTerraForgedCompat.create(freeTerraForged,
                profile.levelKey().equals(Level.OVERWORLD), registries.access(),
                () -> RandomState.create(settings, registries.noiseLookup(), profile.seed()));
        LevelHeightAccessor profileHeights = LevelHeightAccessor.create(profile.minY(), profile.height());
        NoiseSettings noiseSettings = settings.noiseSettings().clampToHeightAccessor(profileHeights);
        LevelHeightAccessor heights = LevelHeightAccessor.create(
                noiseSettings.minY(), noiseSettings.height());
        if (freeTerraForged) return new FreeTerraForgedTerrainSampler(
                profile, generator, randomState, heights, registries, clientRegistries);
        ClientTerrainSampler sampler = new ClientTerrainSampler(
                profile.seed(), profile, generator, randomState, heights, settings.seaLevel(),
                registries.structureIds(), registries, clientRegistries);
        // Epic Terrain uses height-dependent cache_2d nodes. Its surface needs
        // NoiseChunk's evaluation order, including when no native library loads.
        return registries.hasDensityNamespace("etn") ? new MinecraftColumnTerrainSampler(sampler) : sampler;
    }

    private static JsonObject parse(byte[] bytes) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Empty VSS worldgen snapshot");
        }
        return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
    }
}
