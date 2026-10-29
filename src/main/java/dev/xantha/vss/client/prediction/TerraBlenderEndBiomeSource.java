package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import dev.xantha.vss.common.worldgen.TerraBlenderEndRouting;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Biome-only adapter; terrain and decoration keep the decoded Java/Rust context. */
final class TerraBlenderEndBiomeSource extends BiomeSource {
    private final TerraBlenderEndRouting routing;
    private final Map<String, Holder<Biome>> biomes = new LinkedHashMap<>();
    TerraBlenderEndBiomeSource(JsonObject section, long seed, ClientWorldgenRegistries registries) {
        routing = new TerraBlenderEndRouting(section, seed);
        var registry = registries.access().registryOrThrow(Registries.BIOME);
        for (String id : routing.possibleBiomes()) biomes.put(id,
                registry.getHolder(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id)))
                        .orElseThrow(() -> new IllegalArgumentException("Missing TerraBlender End biome " + id)));
    }
    @Override protected MapCodec<? extends BiomeSource> codec() { return MapCodec.unit(this); }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return biomes.values().stream(); }
    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        double erosion = TerraBlenderEndRouting.central(x, z) ? 0
                : sampler.erosion().compute(new DensityFunction.SinglePointContext(x << 2, y << 2, z << 2));
        return biomes.get(routing.sample(x, z, erosion));
    }
}
