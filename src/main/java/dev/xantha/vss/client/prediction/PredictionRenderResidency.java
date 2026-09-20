package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;

/** Coverage follows completed GPU uploads, independently of worker publication. */
final class PredictionRenderResidency {
    private final Map<PredictionTileKey, PredictionTile> tiles = new HashMap<>();
    private final PredictionSpatialIndex<PredictionTileKey> index = new PredictionSpatialIndex<>();
    private final java.util.Set<PredictionTileKey> changes = new java.util.HashSet<>();
    private final java.util.Set<PredictionTileKey> retiring = new java.util.HashSet<>();
    private final Map<PredictionTileKey, Long> epochs = new HashMap<>();
    private final PredictionSpatialIndex<PredictionTileKey> scopeIndex=new PredictionSpatialIndex<>();
    private final java.util.Set<PredictionTileKey> snapshotChanges=new java.util.HashSet<>();
    private PredictionTileTable<PredictionTile> publishedTiles;
    private PredictionTileTable<Long> publishedEpochs;
    private PredictionTileTable<Boolean> publishedScopes;
    private RenderSnapshot snapshot;
    private VssLodLayout layout;
    private long revision;
    private RenderSnapshot retainedSource;
    private long retainedRevision = -1;
    private RenderSnapshot pendingSource;
    private final java.util.ArrayList<PredictionTile> pendingUploads = new java.util.ArrayList<>();

    void retain(RenderSnapshot source) {
        if (source == retainedSource && revision == retainedRevision) return;
        boolean distanceReduced = layout != null && source.layout().maxDistanceBlocks() < layout.maxDistanceBlocks();
        if (retainedSource != null && !retainedSource.dimension().equals(source.dimension())) {
            var oldKeys=java.util.Set.copyOf(tiles.keySet());
            clear();changes.addAll(oldKeys);
        }
        if (!source.layout().equals(layout)) {
            // Tile coordinates and uploaded payloads do not change with radius
            // or selection quality. Re-evaluate ownership, retaining useful GPU data.
            layout = source.layout();
            snapshot = null;
            long epoch = ++revision;
            tiles.keySet().forEach(key -> { epochs.put(key, epoch); changes.add(key);snapshotChanges.add(key); });
        }
        if (source != retainedSource) {
            for (var key:tiles.keySet()) if(key.lod()>=source.layout().levelCount() || !source.tiles().containsKey(key)) retiring.add(key);
            retiring.removeIf(key->key.lod()<source.layout().levelCount() && source.tiles().containsKey(key));
        }
        var iterator = retiring.iterator();
        while (iterator.hasNext()) {
            var key = iterator.next();
            if (key.lod() >= source.layout().levelCount()
                    || distanceReduced && !source.tiles().containsKey(key)) {
                iterator.remove();
                tiles.remove(key); index.remove(key);scopeIndex.remove(key);
                changed(key);
                epochs.remove(key);
                continue;
            }
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
            tiles.remove(key); index.remove(key);scopeIndex.remove(key);
            changed(key);
            epochs.remove(key);
        }
        retainedSource = source;
        retainedRevision = revision;
    }

    boolean contains(PredictionTile tile) { return tiles.get(tile.key()) == tile; }

    java.util.List<PredictionTile> pendingUploads(RenderSnapshot source) {
        if (source != pendingSource) {
            pendingUploads.clear();
            pendingUploads.addAll(source.tiles().values());
            pendingSource = source;
        }
        pendingUploads.removeIf(tile -> contains(tile) || tile.mesh().gpuPayload() == null);
        return pendingUploads;
    }

    void uploaded(PredictionTile tile) {
        if (tiles.put(tile.key(), tile) != tile) {
            index.put(tile.key(),tile.key());
            if(tile.scopeOnly()) scopeIndex.put(tile.key(),tile.key());else scopeIndex.remove(tile.key());
            changed(tile.key());
        }
    }

    private void changed(PredictionTileKey changed) {
        snapshot = null;
        long epoch = ++revision;
        changes.add(changed);snapshotChanges.add(changed);
        long span=64L<<changed.lod(), x=changed.tileX()*span,z=changed.tileZ()*span;
        index.intersect(x,z,x+span,z+span,key->{ epochs.put(key,epoch);changes.add(key);snapshotChanges.add(key); });
    }

    java.util.Set<PredictionTileKey> drainChanges() {
        if(changes.isEmpty()) return java.util.Set.of();
        var result=java.util.Set.copyOf(changes);changes.clear();return result;
    }

    RenderSnapshot snapshot(RenderSnapshot source) {
        if(snapshot==null) {
            if(publishedTiles==null) {
                publishedTiles=new PredictionTileTable<>(source.dimension());
                publishedEpochs=new PredictionTileTable<>(source.dimension());
                publishedScopes=new PredictionTileTable<>(source.dimension());
            }
            var tileUpdates=new HashMap<PredictionTileKey,PredictionTile>();
            var epochUpdates=new HashMap<PredictionTileKey,Long>();
            var scopeUpdates=new HashMap<PredictionTileKey,Boolean>();
            var removed=new java.util.HashSet<PredictionTileKey>();
            var unscoped=new java.util.HashSet<PredictionTileKey>();
            for(var key:snapshotChanges) {
                var tile=tiles.get(key);
                if(tile==null) { removed.add(key);unscoped.add(key);continue; }
                tileUpdates.put(key,tile);epochUpdates.put(key,epochs.getOrDefault(key,0L));
                long span=64L<<key.lod(),x=key.tileX()*span,z=key.tileZ()*span;
                if(scopeIndex.any(x,z,x+span,z+span)) scopeUpdates.put(key,Boolean.TRUE);else unscoped.add(key);
            }
            publishedTiles=publishedTiles.changed(tileUpdates,removed);
            publishedEpochs=publishedEpochs.changed(epochUpdates,removed);
            publishedScopes=publishedScopes.changed(scopeUpdates,unscoped);
            snapshotChanges.clear();
            snapshot=new RenderSnapshot(source.dimension(),source.layout(),publishedTiles,publishedEpochs,publishedScopes.keySet());
        }
        return snapshot;
    }

    void clear() {
        pendingSource = null;
        pendingUploads.clear();
        tiles.clear();
        index.clear();scopeIndex.clear();changes.clear();snapshotChanges.clear();retiring.clear();
        publishedTiles=null;publishedEpochs=null;publishedScopes=null;
        epochs.clear();
        snapshot = null;
        layout = null;
        retainedSource = null;
        retainedRevision = -1;
    }
}
