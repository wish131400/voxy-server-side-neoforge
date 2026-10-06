package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import dev.xantha.vss.common.worldgen.WorldgenJson;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One-time reader for old hash directories. Never participates after the world cache is initialized. */
final class PredictionCacheMigration {
    record Source(Path directory, long fingerprint) { }

    static Source find(Path base, Path legacyBase, ClientTerrainSampler sampler) throws IOException {
        Path dimension = dimension(base, sampler);
        var contentIdentity = sampler.legacyCacheIdentity();
        boolean hasContent;
        if (Files.isDirectory(dimension)) {
            try (var paths = Files.list(dimension)) {
                hasContent = paths.anyMatch(p -> p.getFileName().toString().startsWith("content-"));
            }
        } else hasContent = false;
        if (hasContent && contentIdentity != null) {
            String hash = contentIdentity.get();
            Path content = contentDirectory(base, sampler, hash);
            if (Files.isDirectory(content, LinkOption.NOFOLLOW_LINKS)) {
                Path marker = content.resolve("worldgen.identity");
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 128)
                    throw new IOException("Invalid legacy content identity");
                var lines = Files.readAllLines(marker, StandardCharsets.US_ASCII);
                if (lines.size() != 3 || !lines.get(0).equals("1") || !lines.get(1).equals(hash))
                    throw new IOException("Legacy content identity mismatch");
                try { return new Source(content, Long.parseLong(lines.get(2))); }
                catch (NumberFormatException invalid) { throw new IOException("Invalid legacy fingerprint", invalid); }
            }
        }
        Path original = wireDirectory(base, sampler);
        if (Files.isDirectory(original, LinkOption.NOFOLLOW_LINKS)) return new Source(original, sampler.profile().fingerprint());
        Path legacy = legacyBase == null ? null : wireDirectory(legacyBase, sampler);
        return legacy != null && Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)
                ? new Source(legacy, sampler.profile().fingerprint()) : null;
    }

    static Path contentDirectory(Path base, ClientTerrainSampler sampler, String hash) {
        return dimension(base, sampler).resolve("content-" + digest(algorithm(sampler)) + "-" + hash);
    }

    static Path wireDirectory(Path base, ClientTerrainSampler sampler) {
        var p = sampler.profile();
        String input = p.seed() + ":" + p.fingerprint() + ":" + p.minY() + ":" + p.height()
                + ":" + p.dimension() + ":" + p.generatorType() + ":" + p.generatorSettings() + ":" + algorithm(sampler);
        return dimension(base, sampler).resolve(digest(input) + "-" + PredictionCacheStorage.digest(p.generatorData()));
    }

    private static Path dimension(Path base, ClientTerrainSampler sampler) {
        return base.resolve(sampler.profile().dimension().getNamespace()).resolve(sampler.profile().dimension().getPath());
    }

    private static String algorithm(ClientTerrainSampler sampler) {
        return sampler.getClass().getName() + ":" + VSSClientConfig.CONFIG.predictionSupersample
                + (sampler instanceof RustTerrainSampler rust ? ":vanilla-rust-abi2-r3" + (rust.signedSqrt() ? ":signed-sqrt-r1" : "") : "")
                + (sampler instanceof FreeTerraForgedTerrainSampler ? ":java-stage-exact-r2" : "")
                + (sampler.interiorTerrain() ? ":interior-columns-r3-structures" : "");
    }

    static String contentHash(int format, long worldSeed, DimensionProfile profile, String registriesHash, JsonElement generator) {
        var inputs = new JsonObject();
        inputs.addProperty("identity_version", 1);
        inputs.addProperty("profile_format", format);
        inputs.addProperty("world_seed", worldSeed);
        inputs.addProperty("dimension_seed", profile.seed());
        inputs.addProperty("dimension", profile.dimension().toString());
        inputs.addProperty("min_y", profile.minY()); inputs.addProperty("height", profile.height());
        inputs.addProperty("generator_type", profile.generatorType());
        inputs.addProperty("generator_settings", profile.generatorSettings());
        inputs.addProperty("registries", registriesHash);
        inputs.addProperty("generator", WorldgenJson.sha256(generator == null ? JsonNull.INSTANCE : generator));
        return WorldgenJson.sha256(inputs);
    }

    private static String digest(String text) { return PredictionCacheStorage.digest(text.getBytes(StandardCharsets.UTF_8)); }
}
