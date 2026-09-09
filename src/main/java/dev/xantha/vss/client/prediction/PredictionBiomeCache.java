package dev.xantha.vss.client.prediction;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Exact quart-coordinate reuse for surface rules, tints and decoration reads. */
final class PredictionBiomeCache {
    private static final int SIZE = 2048;
    private final BiomeSource source;
    private final Climate.Sampler climate;
    private final ThreadLocal<Entries> workers = ThreadLocal.withInitial(Entries::new);

    PredictionBiomeCache(BiomeSource source, Climate.Sampler climate) {
        this.source = source;
        this.climate = climate;
    }

    Holder<Biome> get(int quartX, int quartY, int quartZ) {
        Entries entries = workers.get();
        int hash = quartX * 0x9E3779B9 ^ Integer.rotateLeft(quartZ * 0x85EBCA6B, 11) ^ quartY * 0xC2B2AE35;
        int slot = (hash ^ hash >>> 16) & (SIZE - 1);
        Holder<Biome> biome = entries.biomes[slot];
        if (biome != null && entries.x[slot] == quartX && entries.y[slot] == quartY && entries.z[slot] == quartZ) {
            return biome;
        }
        biome = source.getNoiseBiome(quartX, quartY, quartZ, climate);
        entries.x[slot] = quartX;
        entries.y[slot] = quartY;
        entries.z[slot] = quartZ;
        entries.biomes[slot] = biome;
        return biome;
    }

    private static final class Entries {
        final int[] x = new int[SIZE], y = new int[SIZE], z = new int[SIZE];
        @SuppressWarnings("unchecked")
        final Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder<?>[SIZE];
    }
}
