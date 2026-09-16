package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;

/** Rebuilds Blueprint's live wrapper; its codec alone returns only the original source. */
final class BlueprintBiomeCompat {
    private BlueprintBiomeCompat() { }

    static BiomeSource decode(JsonObject section, long seed, ClientWorldgenRegistries registries) {
        BiomeSource original = BiomeSource.CODEC.parse(registries.ops(), section.get("original_biome_source"))
                .getOrThrow();
        if (section.has("original_terrablender")) {
            original = TerraBlenderBackend.buildRoutingSource(section.getAsJsonObject("original_terrablender"),
                    original, seed, registries);
            if (original == null) throw new IllegalArgumentException("Missing wrapped TerraBlender routing");
        }
        try {
            Class<?> sourceType = Class.forName("com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSource");
            Class<?> sliceType = Class.forName("com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSlice");
            Codec<?> codec = (Codec<?>) sliceType.getField("CODEC").get(null);
            var slices = new ArrayList<Pair<ResourceLocation, Object>>();
            for (var element : section.getAsJsonArray("slices")) {
                JsonObject entry = element.getAsJsonObject();
                Object slice = codec.parse(registries.ops(), entry.get("slice")).getOrThrow();
                slices.add(Pair.of(ResourceLocation.parse(entry.get("name").getAsString()), slice));
            }
            if (slices.isEmpty() || slices.size() > 256) throw new IllegalArgumentException("Blueprint slice count");
            // Blueprint derives both routing seeds from the world seed and the
            // dimension modifier. Preserve both; don't mutate final fields.
            long modifier = dimensionModifier(seed, section.get("slices_seed").getAsLong(),
                    section.get("slices_zoom_seed").getAsLong());
            return (BiomeSource) sourceType.getConstructor(Registry.class, BiomeSource.class, ArrayList.class,
                    int.class, long.class, long.class).newInstance(registries.access().registryOrThrow(Registries.BIOME),
                    original, slices, section.get("size").getAsInt(), seed, modifier);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot restore Blueprint biome slices", failure);
        }
    }

    static long dimensionModifier(long seed, long slicesSeed, long zoomSeed) {
        long modifier = slicesSeed - seed - 1791510900L;
        if (seed - 771160217L + modifier != zoomSeed)
            throw new IllegalArgumentException("Unsupported Blueprint positional seeds");
        return modifier;
    }
}
