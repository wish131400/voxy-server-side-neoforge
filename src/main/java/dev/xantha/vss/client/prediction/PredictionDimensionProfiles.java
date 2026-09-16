package dev.xantha.vss.client.prediction;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import java.util.LinkedHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Bounded compressed snapshots for revisiting dimensions within one connection/revision. */
final class PredictionDimensionProfiles {
    private final LinkedHashMap<ResourceKey<Level>, WorldgenProfileS2CPayload> profiles = new LinkedHashMap<>(4, .75F, true);
    private long seed, revision;
    void remember(WorldgenProfileS2CPayload payload) {
        if (!profiles.isEmpty() && (seed != payload.seed() || revision != payload.revision())) clear();
        seed = payload.seed(); revision = payload.revision();
        for (var dimension : payload.dimensions()) {
            profiles.put(dimension.levelKey(), payload);
            while (profiles.size() > 4) profiles.remove(profiles.keySet().iterator().next());
        }
    }
    WorldgenProfileS2CPayload restore(ResourceKey<Level> dimension, WorldgenProfileS2CPayload active) {
        return contains(active, dimension) ? null : profiles.get(dimension);
    }
    static boolean contains(WorldgenProfileS2CPayload payload, ResourceKey<Level> dimension) {
        return payload != null && payload.dimensions().stream().anyMatch(p -> p.levelKey().equals(dimension));
    }
    int size() { return profiles.size(); }
    void clear() { profiles.clear(); }
}
