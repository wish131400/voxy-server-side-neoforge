package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import dev.xantha.vss.common.worldgen.BiolithWorldgenContext;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;

/** Uses the installed mod's noise and criteria against the server's frozen placement decisions. */
final class BiolithBiomeSource extends BiomeSource {
    private final BiolithWorldgenContext.Routing routing;
    private final List<Holder<Biome>> possible;
    BiolithBiomeSource(JsonObject snapshot,long seed,ClientWorldgenRegistries registries) {
        var entries=MultiNoiseBiomeSource.DIRECT_CODEC.codec().parse(registries.ops(),snapshot.get("entries")).getOrThrow();
        var biomes=registries.access().registryOrThrow(Registries.BIOME);
        var choices=new java.util.LinkedHashSet<>(entries.values().stream().map(com.mojang.datafixers.util.Pair::getSecond).toList());
        choices.addAll(BiolithWorldgenContext.additionalBiomes(snapshot,biomes));
        possible=List.copyOf(choices);
        routing=BiolithWorldgenContext.restore(snapshot,registries.ops(),biomes,seed);
    }
    @Override protected MapCodec<? extends BiomeSource> codec() { return MapCodec.unit(this); }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return possible.stream(); }
    @Override public Holder<Biome> getNoiseBiome(int x,int y,int z,Climate.Sampler sampler) {
        return routing.sample(x,y,z,sampler.sample(x,y,z));
    }
}
