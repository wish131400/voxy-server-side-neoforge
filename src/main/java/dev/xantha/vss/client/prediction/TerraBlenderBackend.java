package dev.xantha.vss.client.prediction;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Replays TerraBlender's positional biome routing for a snapshot dimension.
 *
 * <p>The server serializes every registered region with its weight and its
 * {@code (ParameterPoint, biome)} pairs into {@code vss_terrablender}; this
 * backend rebuilds the per-region climate lists, replays the uniqueness zoom
 * stack (see {@link TerrablenderUniqueness}) and routes
 * {@code getNoiseBiome} exactly like TerraBlender's patched
 * {@code ParameterList.findValuePositional}: search the region the grid
 * selected, and fall back to the world's own biome source when the winner is
 * the deferred placeholder. Terrain and features keep the ordinary vanilla
 * snapshot path; only biome resolution changes.</p>
 */
public final class TerraBlenderBackend implements PredictionTerrainBackend {
    private static final Gson GSON = new Gson();
    private static final String PLACEHOLDER = "terrablender:deferred_placeholder";

    @Override
    public String id() {
        return "terrablender";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
                                               RegistryAccess registries) {
        // Without the decoded VSS registries the backend cannot rebuild the
        // region biome holders; the decoder always uses the four-argument hook.
        return Optional.empty();
    }

    @Override
    public Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
                                               RegistryAccess registries,
                                               ClientWorldgenRegistries worldgen) {
        try (var inputs = new RustWorldgenDocument.SharedInputs()) {
            return open(profile, seed, registries, worldgen, inputs);
        }
    }
    @Override
    public Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
            RegistryAccess registries, ClientWorldgenRegistries worldgen, RustWorldgenDocument.SharedInputs inputs) {
        if (inputs == null) return open(profile, seed, registries, worldgen);
        if (worldgen == null || profile.generatorData().length == 0) {
            return Optional.empty();
        }
        ClientTerrainSampler sampler = null;
        try {
            byte[] generatorJson = LodByteCompression.decompress(
                    profile.generatorData(),
                    profile.generatorCompression(),
                    profile.generatorRawSize(),
                    WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES);
            JsonObject generatorRoot = GSON.fromJson(
                    new String(generatorJson, StandardCharsets.UTF_8), JsonObject.class);
            if (generatorRoot == null || !generatorRoot.has("vss_terrablender")) {
                return Optional.empty();
            }
            String rejection = PredictionWorldgenCapabilities.rejection(generatorRoot);
            if (rejection != null) {
                return Optional.empty();
            }
            BiomeSource base = BiomeSource.CODEC
                    .parse(worldgen.ops(), generatorRoot.get("biome_source")).getOrThrow();
            BiomeSource routing = buildRoutingSource(
                    generatorRoot.getAsJsonObject("vss_terrablender"), base, seed, worldgen);
            if (routing == null) {
                return Optional.empty();
            }
            sampler = ClientWorldgenProfileDecoder.decodeJavaSampler(
                    profile, generatorRoot, worldgen, registries, routing);
            // TerraBlender only reroutes biomes, so the density graph stays
            // native-safe: try the Rust terrain core, but only trust its
            // surface materials when it reports the region routing itself.
            String terrain = "java";
            String terrainRejection = PredictionWorldgenCapabilities.nativeTerrainRejection(
                    generatorRoot, worldgen.snapshotRoot());
            if (terrainRejection == null) {
                ClientTerrainSampler nativeTerrain = RustTerrainSampler.open(
                        profile, generatorRoot, worldgen.snapshotRoot(), sampler,
                        inputs);
                if (nativeTerrain != null) {
                    if (nativeTerrain instanceof RustTerrainSampler rust
                            && rust.terrablenderRouting()) {
                        // The native sampler owns the Java context and closes
                        // it when released; publish the native handle only.
                        sampler = nativeTerrain;
                        terrain = "rust";
                    } else {
                        PredictionResources.releaseSampler(nativeTerrain);
                        terrain = "java (native lacks region routing)";
                    }
                }
            } else {
                terrain = "java (" + terrainRejection + ")";
            }
            VSSLogger.info("VSS TerraBlender positional-region backend active for "
                    + profile.dimension() + ": regions=" + ((RoutingSource) routing).regionCount()
                    + ", terrain=" + terrain + ", base=" + base.getClass().getSimpleName());
            return Optional.of(sampler);
        } catch (IOException | RuntimeException failure) {
            VSSLogger.warn("VSS TerraBlender region layout could not be reconstructed for "
                    + profile.dimension() + "; keeping the vanilla snapshot biomes", failure);
            if (sampler != null) PredictionResources.releaseSampler(sampler);
            return Optional.empty();
        }
    }

    /** Rebuilds the routing biome source, or null when the snapshot is unusable. */
    static BiomeSource buildRoutingSource(JsonObject section, BiomeSource base,
                                                  long seed, ClientWorldgenRegistries worldgen) {
        int regionSize = section.get("region_size").getAsInt();
        Registry<Biome> biomes = worldgen.access().registryOrThrow(Registries.BIOME);
        JsonArray regionArray = section.getAsJsonArray("regions");
        List<Climate.ParameterList<Holder<Biome>>> trees = new ArrayList<>();
        List<TerrablenderUniqueness.Region> weighted = new ArrayList<>();
        int unresolved = 0;
        for (int index = 0; index < regionArray.size(); index++) {
            JsonObject entry = regionArray.get(index).getAsJsonObject();
            Climate.ParameterList<Holder<Biome>> tree = null;
            if (!entry.get("base").getAsBoolean() && entry.has("groups")) {
                List<Pair<Climate.ParameterPoint, Holder<Biome>>> points = new ArrayList<>();
                for (JsonElement groupElement : entry.getAsJsonArray("groups")) {
                    JsonObject group = groupElement.getAsJsonObject();
                    Holder<Biome> holder = resolve(biomes, group.get("biome").getAsString());
                    if (holder == null) unresolved++;
                    for (JsonElement flatElement : group.getAsJsonArray("points")) {
                        points.add(Pair.of(parameterPoint(flatElement.getAsJsonArray()), holder));
                    }
                }
                if (!points.isEmpty()) {
                    tree = new Climate.ParameterList<>(points);
                }
            }
            trees.add(tree);
            if (entry.get("weighted").getAsBoolean()) {
                weighted.add(new TerrablenderUniqueness.Region(
                        index, entry.get("weight").getAsInt()));
            }
        }
        if (weighted.isEmpty()) {
            return null;
        }
        if (unresolved > 0) {
            VSSLogger.warn("VSS TerraBlender region snapshot has " + unresolved
                    + " biomes missing from the client registry; affected columns use the base region");
        }
        return new RoutingSource(base,
                TerrablenderUniqueness.build(seed, regionSize, weighted), trees);
    }

    /** Rebuilds a point from the 13 unquantized floats (six ranges plus offset). */
    private static Climate.ParameterPoint parameterPoint(JsonArray flat) {
        return new Climate.ParameterPoint(
                Climate.Parameter.span(flat.get(0).getAsFloat(), flat.get(1).getAsFloat()),
                Climate.Parameter.span(flat.get(2).getAsFloat(), flat.get(3).getAsFloat()),
                Climate.Parameter.span(flat.get(4).getAsFloat(), flat.get(5).getAsFloat()),
                Climate.Parameter.span(flat.get(6).getAsFloat(), flat.get(7).getAsFloat()),
                Climate.Parameter.span(flat.get(8).getAsFloat(), flat.get(9).getAsFloat()),
                Climate.Parameter.span(flat.get(10).getAsFloat(), flat.get(11).getAsFloat()),
                Climate.quantizeCoord(flat.get(12).getAsFloat()));
    }

    /** Placeholder or missing biomes become null winners, which route to the base source. */
    private static Holder<Biome> resolve(Registry<Biome> biomes, String id) {
        if (id == null || PLACEHOLDER.equals(id)) {
            return null;
        }
        try {
            return biomes.getHolder(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id)))
                    .orElse(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static final class RoutingSource extends BiomeSource {
        private final BiomeSource base;
        private final TerrablenderUniqueness.Tree uniqueness;
        private final Climate.ParameterList<Holder<Biome>>[] trees;
        private final Set<Holder<Biome>> possible;

        @SuppressWarnings("unchecked")
        RoutingSource(BiomeSource base, TerrablenderUniqueness.Tree uniqueness,
                      List<Climate.ParameterList<Holder<Biome>>> trees) {
            this.base = base;
            this.uniqueness = uniqueness;
            this.trees = trees.toArray(new Climate.ParameterList[0]);
            Set<Holder<Biome>> union = new LinkedHashSet<>(base.possibleBiomes());
            for (Climate.ParameterList<Holder<Biome>> tree : trees) {
                if (tree == null) continue;
                for (Pair<Climate.ParameterPoint, Holder<Biome>> pair : tree.values()) {
                    if (pair.getSecond() != null) union.add(pair.getSecond());
                }
            }
            this.possible = Set.copyOf(union);
        }

        int regionCount() {
            return trees.length;
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            // The routing source only exists in memory; VSS never serializes
            // a reconstructed generator.
            return Codec.unit(this).fieldOf("vss_terrablender");
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            return possible.stream();
        }

        @Override
        public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
            int index = uniqueness.get(x, z);
            if (index >= 0 && index < trees.length) {
                Climate.ParameterList<Holder<Biome>> tree = trees[index];
                if (tree != null) {
                    Holder<Biome> found = tree.findValue(sampler.sample(x, y, z));
                    if (found != null) {
                        return found;
                    }
                }
            }
            return base.getNoiseBiome(x, y, z, sampler);
        }
    }
}
