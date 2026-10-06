package dev.xantha.vss.client.prediction;

import java.io.IOException;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Resource names survive registry reordering; legacy numbers are accepted only with a saved mapping. */
final class PredictionCacheMappings {
    private final List<String> biomes;
    private final Map<String, Integer> biomeIds;
    private final List<String> legacyBiomes, legacyBlocks;

    PredictionCacheMappings(List<String> biomes, List<String> legacyBiomes, List<String> legacyBlocks) {
        this.biomes = List.copyOf(biomes);
        var ids = new HashMap<String, Integer>();
        for (int i = 0; i < biomes.size(); i++) if (!biomes.get(i).isEmpty()) ids.put(biomes.get(i), i);
        biomeIds = Map.copyOf(ids);
        this.legacyBiomes = List.copyOf(legacyBiomes);
        this.legacyBlocks = List.copyOf(legacyBlocks);
    }

    String biomeName(int id) throws IOException {
        if (id == -1 || id == ClientColumnSample.NO_BLOCK || biomes.isEmpty()) return "#" + id;
        if (id < 0 || id >= biomes.size() || biomes.get(id).isEmpty()) throw new IOException("Unregistered biome: " + id);
        return biomes.get(id);
    }

    int biomeId(String name) throws IOException {
        if (name.equals("#-1")) return -1;
        if (name.equals("#" + ClientColumnSample.NO_BLOCK)) return ClientColumnSample.NO_BLOCK;
        // Custom height-only backends have no biome registry or biome-dependent output.
        if (biomes.isEmpty() && name.startsWith("#")) {
            try { return Integer.parseInt(name.substring(1)); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid biome index", invalid); }
        }
        Integer id = biomeIds.get(name);
        if (id == null) throw new IOException("Biome unavailable: " + name);
        return id;
    }

    int legacyBiome(int id) throws IOException {
        if (id == -1 || id == ClientColumnSample.NO_BLOCK || biomes.isEmpty() && legacyBiomes.isEmpty()) return id;
        if (id < 0 || id >= legacyBiomes.size()) throw new IOException("Missing legacy biome mapping: " + id);
        return biomeId(legacyBiomes.get(id));
    }

    int legacyBlock(int id) throws IOException {
        if (id == ClientColumnSample.NO_BLOCK) return id;
        if (id < 0 || id >= legacyBlocks.size()) throw new IOException("Missing legacy block mapping: " + id);
        return blockId(legacyBlocks.get(id));
    }

    boolean hasLegacyBlocks() { return !legacyBlocks.isEmpty(); }

    static String blockName(int id) throws IOException {
        if (id == ClientColumnSample.NO_BLOCK) return "";
        var block = BuiltInRegistries.BLOCK.byId(id);
        if (block == null) throw new IOException("Invalid block index: " + id);
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    static int blockId(String name) throws IOException {
        if (name.isEmpty()) return ClientColumnSample.NO_BLOCK;
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IOException("Block unavailable: " + name);
        return BuiltInRegistries.BLOCK.getId(BuiltInRegistries.BLOCK.get(id));
    }
}
