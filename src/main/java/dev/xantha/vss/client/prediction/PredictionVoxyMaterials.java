package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.world.level.block.state.BlockState;
import dev.xantha.vss.compat.VoxyPredictionBridge;

/** One section's material palette, without capturing a new mapping lambda for every run. */
final class PredictionVoxyMaterials implements VoxyPredictionBridge.Mapper {
    private final VoxyPredictionBridge.Mapper mapper;
    private final IdentityHashMap<BlockState, Map<String, Long>> values = new IdentityHashMap<>();
    private BlockState lastState;
    private String lastBiome;
    private long lastValue;
    private boolean hasLast;
    PredictionVoxyMaterials(VoxyPredictionBridge.Mapper mapper) { this.mapper = mapper; }
    public long map(BlockState state, String biome) {
        if (hasLast && state == lastState && Objects.equals(biome, lastBiome)) return lastValue;
        var biomes = values.get(state);
        if (biomes == null) { biomes = new HashMap<>(); values.put(state, biomes); }
        Long value = biomes.get(biome);
        if (value == null) { value = mapper.map(state, biome); biomes.put(biome, value); }
        lastState = state; lastBiome = biome; lastValue = value; hasLast = true;
        return value;
    }
}
