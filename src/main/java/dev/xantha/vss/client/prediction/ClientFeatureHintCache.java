package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Reads tree/vegetation hints from the reconstructed biome feature lists. */
final class ClientFeatureHintCache {
    private final Map<Holder<Biome>, Hint> hints = new HashMap<>();

    ClientFeatureHintCache(Iterable<Holder<Biome>> biomes) {
        for (Holder<Biome> holder : biomes) {
            hints.put(holder, inspect(holder.value().getGenerationSettings()));
        }
    }

    Hint hint(Holder<Biome> biome) {
        return hints.getOrDefault(biome, Hint.NONE);
    }

    private static Hint inspect(BiomeGenerationSettings settings) {
        int treeKind = 0;
        int density = 0;
        int groundKind = 0;
        for (HolderSet<PlacedFeature> stage : settings.features()) {
            for (Holder<PlacedFeature> holder : stage) {
                String id = holder.unwrapKey().map(key -> key.location().getPath()).orElse("");
                if (id.contains("tree") || id.contains("forest") || id.contains("vegetation")) {
                    int kind = id.contains("spruce") || id.contains("pine") ? 2
                            : id.contains("birch") ? 3
                            : id.contains("jungle") || id.contains("bamboo") ? 4 : 1;
                    treeKind = Math.max(treeKind, kind);
                    density = Math.max(density, id.contains("dense") || id.contains("jungle") ? 85 : 48);
                }
                if (id.contains("flower") || id.contains("grass") || id.contains("fern")
                        || id.contains("reed") || id.contains("patch")) {
                    groundKind = Math.max(groundKind, 1);
                }
            }
        }
        return new Hint(treeKind, density, groundKind);
    }

    record Hint(int treeKind, int density, int groundKind) {
        static final Hint NONE = new Hint(0, 0, 0);
    }
}
