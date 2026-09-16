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
    // Four ways per bucket retain overlapping decoration queries across workers.
    // Full coordinates avoid BlockPos's truncated Y/large-coordinate aliases.
    private static final int BUCKETS = 16_384, WAYS = 4;
    private final Entries shared = new Entries(BUCKETS * WAYS);
    private final byte[] next = new byte[BUCKETS];
    private final Object[] locks = new Object[64];
    private final java.util.concurrent.atomic.LongAdder localHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder sharedHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder loads = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder evictions = new java.util.concurrent.atomic.LongAdder();

    PredictionBiomeCache(BiomeSource source, Climate.Sampler climate) {
        this.source = source;
        this.climate = climate;
        java.util.Arrays.setAll(locks, i -> new Object());
    }

    Holder<Biome> get(int quartX, int quartY, int quartZ) {
        Entries entries = workers.get();
        int hash = quartX * 0x9E3779B9 ^ Integer.rotateLeft(quartZ * 0x85EBCA6B, 11) ^ quartY * 0xC2B2AE35;
        int slot = (hash ^ hash >>> 16) & (SIZE - 1);
        Holder<Biome> biome = entries.biomes[slot];
        if (biome != null && entries.x[slot] == quartX && entries.y[slot] == quartY && entries.z[slot] == quartZ) {
            localHits.increment();
            return biome;
        }
        int bucket = (hash ^ hash >>> 16) & (BUCKETS - 1);
        Object lock = locks[bucket & (locks.length - 1)];
        synchronized (lock) {
            biome = sharedBiome(bucket, quartX, quartY, quartZ);
        }
        if (biome != null) {
            sharedHits.increment();
        } else {
            // Modded climate evaluation stays outside cache locks. Concurrent
            // misses may duplicate one lookup, but never serialize noise work.
            loads.increment();
            biome = source.getNoiseBiome(quartX, quartY, quartZ, climate);
            synchronized (lock) {
                if (sharedBiome(bucket, quartX, quartY, quartZ) == null) {
                    int at = bucket * WAYS + next[bucket];
                    next[bucket] = (byte) ((next[bucket] + 1) & (WAYS - 1));
                    if (shared.biomes[at] != null) evictions.increment();
                    shared.x[at] = quartX; shared.y[at] = quartY; shared.z[at] = quartZ;
                    shared.biomes[at] = biome;
                }
            }
        }
        entries.x[slot] = quartX;
        entries.y[slot] = quartY;
        entries.z[slot] = quartZ;
        entries.biomes[slot] = biome;
        return biome;
    }

    private Holder<Biome> sharedBiome(int bucket, int x, int y, int z) {
        for (int at = bucket * WAYS, end = at + WAYS; at < end; at++) {
            if (shared.biomes[at] != null && shared.x[at] == x && shared.y[at] == y && shared.z[at] == z)
                return shared.biomes[at];
        }
        return null;
    }

    String diagnostics() {
        return "localHits=" + localHits.sum() + ",sharedHits=" + sharedHits.sum()
                + ",loads=" + loads.sum() + ",evictions=" + evictions.sum();
    }

    private static final class Entries {
        final int[] x, y, z;
        final Holder<Biome>[] biomes;
        Entries() { this(SIZE); }
        @SuppressWarnings("unchecked")
        Entries(int size) {
            x = new int[size]; y = new int[size]; z = new int[size];
            biomes = (Holder<Biome>[]) new Holder<?>[size];
        }
    }
}
