package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;
import dev.xantha.vss.compat.VoxyPredictionBridge;

/** Worker-owned palette. Entries live for one conversion; its primitive storage is reused. */
final class PredictionVoxyMaterials implements VoxyPredictionBridge.Mapper {
    private static final int INITIAL = 32, RETAINED = 256, MAX_ENTRIES = 4096;
    private VoxyPredictionBridge.Mapper mapper;
    private BlockState[] states = new BlockState[INITIAL];
    private String[] biomes = new String[INITIAL];
    private long[] values = new long[INITIAL];
    private int[] table = new int[INITIAL * 2];
    private int size;
    private BlockState lastState;
    private String lastBiome;
    private long lastValue;
    private boolean hasLast;
    PredictionVoxyMaterials() { }
    PredictionVoxyMaterials(VoxyPredictionBridge.Mapper mapper) { reset(mapper); }

    void reset(VoxyPredictionBridge.Mapper mapper) {
        clear(); this.mapper = mapper;
    }

    void clear() {
        if (states.length > RETAINED) {
            states = new BlockState[RETAINED]; biomes = new String[RETAINED];
            values = new long[RETAINED]; table = new int[RETAINED * 2];
        } else {
            Arrays.fill(states, 0, size, null); Arrays.fill(biomes, 0, size, null);
            Arrays.fill(table, 0);
        }
        size = 0; mapper = null; hasLast = false; lastState = null; lastBiome = null;
    }

    public long map(BlockState state, String biome) {
        if (hasLast && state == lastState && Objects.equals(biome, lastBiome)) return lastValue;
        int slot = hash(state, biome) & (table.length - 1);
        int entry;
        while ((entry = table[slot]) != 0) {
            if (states[entry-1] == state && Objects.equals(biomes[entry-1], biome))
                return remember(state, biome, values[entry-1]);
            slot = (slot + 1) & (table.length - 1);
        }
        long value = mapper.map(state, biome);
        if (size < MAX_ENTRIES) {
            if (size == states.length) {
                grow(); slot = hash(state, biome) & (table.length - 1);
                while (table[slot] != 0) slot = (slot + 1) & (table.length - 1);
            }
            states[size] = state; biomes[size] = biome; values[size] = value;
            table[slot] = ++size;
        }
        return remember(state, biome, value);
    }

    private long remember(BlockState state, String biome, long value) {
        lastState = state; lastBiome = biome; lastValue = value; hasLast = true;
        return value;
    }

    private void grow() {
        int capacity = states.length * 2;
        states = Arrays.copyOf(states, capacity); biomes = Arrays.copyOf(biomes, capacity);
        values = Arrays.copyOf(values, capacity); table = new int[capacity * 2];
        for (int i = 0; i < size; i++) {
            int slot = hash(states[i], biomes[i]) & (table.length - 1);
            while (table[slot] != 0) slot = (slot + 1) & (table.length - 1);
            table[slot] = i + 1;
        }
    }

    private static int hash(BlockState state, String biome) {
        int hash = System.identityHashCode(state) * 31 + Objects.hashCode(biome);
        hash ^= hash >>> 16; hash *= 0x7feb352d; return hash ^ (hash >>> 15);
    }
}
