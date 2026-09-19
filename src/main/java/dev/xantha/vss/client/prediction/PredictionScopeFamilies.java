package dev.xantha.vss.client.prediction;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Finds overlapping telescope families through quadtree ancestors, without a tile-pair scan. */
final class PredictionScopeFamilies {
    static Set<PredictionTileKey> find(Map<PredictionTileKey, PredictionTile> tiles) {
        var scoped = new HashSet<PredictionTileKey>();
        int maxLod = 0;
        for (var tile : tiles.values()) {
            maxLod = Math.max(maxLod, tile.key().lod());
            if (tile.scopeOnly()) scoped.add(tile.key());
        }
        if (scoped.isEmpty()) return Set.of();

        var ancestors = new HashSet<PredictionTileKey>();
        for (var key : scoped) {
            for (int lod = key.lod(); lod <= maxLod; lod++) ancestors.add(ancestor(key, lod));
        }
        var families = new HashSet<PredictionTileKey>();
        for (var key : tiles.keySet()) {
            if (ancestors.contains(key)) {
                families.add(key);
                continue;
            }
            // A candidate below a scoped tile must also use view-dependent ownership.
            // Shifts retain floor division for negative quadtree coordinates.
            for (int lod = key.lod() + 1; lod <= maxLod; lod++) {
                if (scoped.contains(ancestor(key, lod))) {
                    families.add(key);
                    break;
                }
            }
        }
        return Set.copyOf(families);
    }

    private static PredictionTileKey ancestor(PredictionTileKey key, int lod) {
        if (lod == key.lod()) return key;
        int shift = lod - key.lod();
        return new PredictionTileKey(key.dimension(), key.tileX() >> shift, key.tileZ() >> shift, lod);
    }

    private PredictionScopeFamilies() { }
}
