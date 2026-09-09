package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;

/** Coverage follows completed GPU uploads, independently of worker publication. */
final class PredictionRenderResidency {
    private final Map<PredictionTileKey, PredictionTile> tiles = new HashMap<>();
    private final Map<PredictionTileKey, Long> epochs = new HashMap<>();
    private RenderSnapshot snapshot;
    private VssLodLayout layout;
    private long revision;
    private RenderSnapshot retainedSource;
    private long retainedRevision = -1;

    void retain(RenderSnapshot source) {
        if (source == retainedSource && revision == retainedRevision) return;
        if (!source.layout().equals(layout) || snapshot != null && !snapshot.dimension().equals(source.dimension())) {
            clear();
            layout = source.layout();
        }
        var iterator = tiles.keySet().iterator();
        while (iterator.hasNext()) {
            var key = iterator.next();
            if (source.tiles().containsKey(key)) continue;
            // CPU eviction can precede the fallback parent's GPU upload.
            // Keep the old detail until that replacement can cover it.
            boolean pendingAncestor = false;
            for (int lod = key.lod() + 1; lod < source.layout().levelCount(); lod++) {
                int shift = lod - key.lod();
                var parent = new PredictionTileKey(key.dimension(), key.tileX() >> shift, key.tileZ() >> shift, lod);
                if (tiles.containsKey(parent)) { pendingAncestor = false; break; }
                if (source.tiles().containsKey(parent)) pendingAncestor = true;
            }
            if (pendingAncestor) continue;
            iterator.remove();
            changed(key);
            epochs.remove(key);
        }
        retainedSource = source;
        retainedRevision = revision;
    }

    boolean contains(PredictionTile tile) { return tiles.get(tile.key()) == tile; }

    void uploaded(PredictionTile tile) {
        if (tiles.put(tile.key(), tile) != tile) changed(tile.key());
    }

    private void changed(PredictionTileKey changed) {
        snapshot = null;
        long epoch = ++revision;
        for (var key : tiles.keySet()) {
            var parent = key.lod() >= changed.lod() ? key : changed;
            var child = key.lod() >= changed.lod() ? changed : key;
            int shift = parent.lod() - child.lod();
            if (parent.dimension().equals(child.dimension()) && (child.tileX() >> shift) == parent.tileX()
                    && (child.tileZ() >> shift) == parent.tileZ()) epochs.put(key, epoch);
        }
    }

    RenderSnapshot snapshot(RenderSnapshot source) {
        if (snapshot == null) snapshot = new RenderSnapshot(source.dimension(), source.layout(),
                Map.copyOf(tiles), Map.copyOf(epochs));
        return snapshot;
    }

    void clear() {
        tiles.clear();
        epochs.clear();
        snapshot = null;
        layout = null;
        retainedSource = null;
        retainedRevision = -1;
    }
}
