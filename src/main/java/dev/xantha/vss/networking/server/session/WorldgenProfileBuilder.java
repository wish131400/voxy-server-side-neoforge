package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot.Encoded;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Builds a complete VSS-owned worldgen snapshot for client-side LOD sampling. */
public final class WorldgenProfileBuilder {
    private WorldgenProfileBuilder() {
    }

    public static WorldgenProfileS2CPayload build(MinecraftServer server, long revision) {
        if (server == null || server.getWorldData() == null) {
            return empty(revision);
        }
        long seed = server.getWorldData().worldGenOptions().seed();
        var references = new dev.xantha.vss.common.worldgen.DensityFunctionReferences();
        var dependencies = new dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies(server.registryAccess());
        var generators = new java.util.LinkedHashMap<ServerLevel, Encoded>();
        for (ServerLevel level : server.getAllLevels()) {
            if (generators.size() >= WorldgenProfileS2CPayload.MAX_DIMENSIONS) break;
            generators.put(level, WorldgenCodecSnapshot.encodeGenerator(
                    level.getChunkSource().getGenerator(), server.registryAccess(), references, dependencies));
        }
        Encoded registries = WorldgenCodecSnapshot.encodeRegistries(server.registryAccess(), server.getResourceManager(), references, dependencies);
        long registryFingerprint = fingerprintBytes(registries.bytes());
        List<DimensionProfile> profiles = new ArrayList<>();
        for (ServerLevel level : generators.keySet()) {
            if (profiles.size() >= WorldgenProfileS2CPayload.MAX_DIMENSIONS) {
                break;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            String generatorType = generator.getClass().getName();
            String settings = generator instanceof NoiseBasedChunkGenerator noise
                    ? noise.generatorSettings().unwrapKey()
                    .map(key -> key.location().toString()).orElse("noise:inline")
                    : "custom:" + generatorType;
            int minY = level.getMinBuildHeight();
            int height = level.getHeight();
            long dimensionSeed = level.getSeed();
            long fingerprint = fingerprint(dimensionSeed, level.dimension().location().toString(),
                    minY, height, generatorType, settings, registryFingerprint);
            Encoded generatorData = generators.get(level);
            profiles.add(new DimensionProfile(
                    level.dimension().location(),
                    dimensionSeed,
                    minY,
                    height,
                    generatorType,
                    settings,
                    fingerprint,
                    generatorData.compression(),
                    generatorData.rawSize(),
                    generatorData.bytes()));
        }
        return new WorldgenProfileS2CPayload(
                WorldgenProfileS2CPayload.FORMAT_VERSION,
                seed,
                revision,
                registries.compression(),
                registries.rawSize(),
                registries.bytes(),
                profiles);
    }

    private static WorldgenProfileS2CPayload empty(long revision) {
        return new WorldgenProfileS2CPayload(
                WorldgenProfileS2CPayload.FORMAT_VERSION,
                0L,
                revision,
                0,
                0,
                new byte[0],
                List.of());
    }

    private static long fingerprint(long seed, String dimension, int minY, int height,
                                    String generatorType, String settings, long registryFingerprint) {
        long value = seed ^ 0x9E3779B97F4A7C15L;
        value = mix(value ^ dimension.hashCode());
        value = mix(value ^ minY);
        value = mix(value ^ ((long) height << 32));
        value = mix(value ^ generatorType.hashCode());
        return mix(value ^ settings.hashCode() ^ registryFingerprint);
    }

    private static long fingerprintBytes(byte[] bytes) {
        long value = 0xCBF29CE484222325L;
        for (byte b : bytes) {
            value ^= b & 0xFFL;
            value *= 0x100000001B3L;
        }
        return mix(value ^ bytes.length);
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
