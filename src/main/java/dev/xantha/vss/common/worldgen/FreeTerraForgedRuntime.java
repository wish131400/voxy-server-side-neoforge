package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Public-API bridge for the epoch-based 1.21.1 FreeTerraForged releases. */
public final class FreeTerraForgedRuntime {
    public static final String FIELD = "vss_freeterraforged_runtime";
    private static final String PREFIX = FreeTerraForgedVariant.CURRENT.worldgenClass("runtime.");
    private static final String GENERATOR = PREFIX + "TerraForgedChunkGenerator";

    private FreeTerraForgedRuntime() { }

    public static boolean supports(ChunkGenerator generator) {
        return generator.getClass().getName().equals(GENERATOR);
    }

    public static BiomeSource acquisitionSource(ChunkGenerator generator) {
        try { return (BiomeSource) call(generator, "acquisitionBiomeSource"); }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged acquisition biome source unavailable", failure);
        }
    }

    public static JsonObject snapshot(ChunkGenerator generator) {
        try {
            Object epoch = ((Optional<?>) call(generator, "epoch")).orElseThrow(
                    () -> new IllegalStateException("FreeTerraForged generator has no live worldgen epoch"));
            // Explicit in-memory source plans cannot be recovered from a biome-source codec.
            for (String method : new String[]{"acquisitionBiomePlanInput", "acquisitionBiomeCandidateRoot"}) {
                if (((Optional<?>) call(generator, method)).isPresent())
                    throw new IllegalStateException("FreeTerraForged custom biome plan needs a serializable source");
            }
            var result = new JsonObject();
            result.addProperty("schema", 1);
            result.addProperty("settings_identity", (String) call(epoch, "settingsIdentity"));
            result.addProperty("resource_revision", (Long) call(epoch, "resourceRevision"));
            result.addProperty("resource_fingerprint", (String) call(epoch, "resourceLayerFingerprint"));
            Object tags = call(epoch, "tagEpoch");
            result.addProperty("tag_sequence", (Long) call(tags, "sequence"));
            result.addProperty("tag_fingerprint", (String) call(tags, "fingerprint"));
            java.lang.reflect.Method configMethod;
            try { configMethod = epoch.getClass().getMethod("biomeReplacementConfig"); }
            catch (NoSuchMethodException earlierEpoch) { configMethod = null; }
            if (configMethod != null) {
                Object config = configMethod.invoke(epoch);
                var replacement = new JsonObject();
                replacement.addProperty("revision", (Long) call(config, "revision"));
                replacement.addProperty("fingerprint", (String) call(config, "fingerprint"));
                replacement.addProperty("path", (String) call(config, "path"));
                replacement.addProperty("origin", ((Enum<?>) call(config, "origin")).name());
                replacement.addProperty("content", (String) call(config, "content"));
                replacement.addProperty("legacy", (Boolean) call(config, "legacyFilePresent"));
                result.add("biome_replacement", replacement);
            }
            result.add("contributions", contributions(call(epoch, "contributionRevision")));
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged worldgen epoch snapshot unavailable", failure);
        }
    }

    public static NoiseBasedChunkGenerator generator(ClassLoader loader, BiomeSource source,
            Holder<NoiseGeneratorSettings> settings) throws ReflectiveOperationException {
        return (NoiseBasedChunkGenerator) loader.loadClass(GENERATOR)
                .getConstructor(BiomeSource.class, Holder.class).newInstance(source, settings);
    }

    /** The caller owns the new generator and must close it even when initialization fails. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object epoch(NoiseBasedChunkGenerator generator, JsonObject snapshot,
            ResourceLocation dimension, long seed, RegistryAccess registries, DimensionType dimensionType)
            throws ReflectiveOperationException {
        if (snapshot.get("schema").getAsInt() != 1)
            throw new IllegalArgumentException("Unsupported FreeTerraForged runtime snapshot schema");
        ClassLoader loader = generator.getClass().getClassLoader();
        if (registries.registry(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).isEmpty()) {
            // The snapshot expands the selected multi-noise preset into its
            // complete candidate list. Upstream still requests this registry
            // before checking the inline graph; no named preset is needed here.
            var parameters = new net.minecraft.core.MappedRegistry<>(
                    Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                    com.mojang.serialization.Lifecycle.stable()).freeze();
            var values = new java.util.ArrayList<net.minecraft.core.Registry<?>>();
            registries.registries().forEach(entry -> values.add(entry.value()));
            values.add(parameters);
            registries = new RegistryAccess.ImmutableRegistryAccess(values);
        }
        var key = ResourceKey.create(Registries.LEVEL_STEM, dimension);
        Object providers = call(generator, "acquireProviderCatalog");
        var catalogType = loader.loadClass(PREFIX + "WorldgenProviderCatalog");
        Object revision = loader.loadClass(PREFIX + "WorldgenContributionRevision")
                .getMethod("snapshot", ResourceKey.class, catalogType).invoke(null, key, providers);
        if (!contributions(revision).equals(snapshot.get("contributions")))
            throw new IllegalStateException("FreeTerraForged client/server worldgen providers differ");
        var tagType = loader.loadClass(PREFIX + "TagEpoch");
        Object tags = tagType.getConstructor(long.class, String.class).newInstance(
                snapshot.get("tag_sequence").getAsLong(), snapshot.get("tag_fingerprint").getAsString());
        var epochType = loader.loadClass(PREFIX + "WorldgenEpoch");
        if (!snapshot.has("biome_replacement")) {
            return epochType.getMethod("create", ResourceKey.class, long.class, RegistryAccess.class,
                    LevelStem.class, String.class, long.class, String.class, tagType, revision.getClass())
                    .invoke(null, key, seed, registries, new LevelStem(Holder.direct(dimensionType), generator),
                    snapshot.get("settings_identity").getAsString(), snapshot.get("resource_revision").getAsLong(),
                    snapshot.get("resource_fingerprint").getAsString(), tags, revision);
        }
        var config = snapshot.getAsJsonObject("biome_replacement");
        var configType = loader.loadClass(PREFIX + "BiomeReplacementConfig");
        var originType = loader.loadClass(PREFIX + "BiomeReplacementConfigSnapshot$Origin");
        var snapshotType = loader.loadClass(PREFIX + "BiomeReplacementConfigSnapshot");
        Object replacement = snapshotType.getConstructor(long.class, String.class, String.class, originType,
                String.class, configType, boolean.class).newInstance(config.get("revision").getAsLong(),
                config.get("fingerprint").getAsString(), config.get("path").getAsString(),
                Enum.valueOf((Class) originType, config.get("origin").getAsString()),
                config.get("content").getAsString(),
                configType.getMethod("parse", String.class).invoke(null, config.get("content").getAsString()),
                config.get("legacy").getAsBoolean());
        return epochType.getMethod("create", ResourceKey.class,
                long.class, RegistryAccess.class, LevelStem.class, String.class, long.class,
                String.class, tagType, snapshotType, revision.getClass()).invoke(null, key, seed, registries,
                new LevelStem(Holder.direct(dimensionType), generator), snapshot.get("settings_identity").getAsString(),
                snapshot.get("resource_revision").getAsLong(), snapshot.get("resource_fingerprint").getAsString(),
                tags, replacement, revision);
    }

    public static void initialize(NoiseBasedChunkGenerator generator, Object epoch, Object state)
            throws ReflectiveOperationException {
        var loader = generator.getClass().getClassLoader();
        generator.getClass().getMethod("initializeEpoch", epoch.getClass(),
                loader.loadClass(FreeTerraForgedVariant.CURRENT.stateClass()),
                loader.loadClass(PREFIX + "WorldgenProviderCatalog"))
                .invoke(generator, epoch, state, call(generator, "acquireProviderCatalog"));
    }

    private static JsonObject contributions(Object revision) throws ReflectiveOperationException {
        var result = new JsonObject();
        var values = new JsonObject();
        for (var entry : ((Map<?, ?>) call(revision, "revisions")).entrySet()) {
            Object key = entry.getKey();
            values.addProperty(call(key, "mechanism") + "/" + call(key, "scope"), (Long) entry.getValue());
        }
        result.add("revisions", values);
        var failures = new JsonArray();
        ((Map<?, ?>) call(revision, "failures")).keySet().stream().map(Object::toString)
                .sorted().forEach(failures::add);
        result.add("failures", failures);
        return result;
    }

    private static Object call(Object owner, String method) throws ReflectiveOperationException {
        return owner.getClass().getMethod(method).invoke(owner);
    }
}
