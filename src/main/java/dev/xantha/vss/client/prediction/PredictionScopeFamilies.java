package dev.xantha.vss.client.prediction;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Finds overlapping telescope families through quadtree ancestors, without a tile-pair scan. */
final class PredictionScopeFamilies {
    /** Resident topology is independent of tile contents. Only changed branches need inspection. */
    static final class Index {
        private static final class Node {
            final PredictionTileKey key;
            final Node[] children = new Node[4];
            Node parent;
            boolean resident, scoped;
            int scopes;
            Node(PredictionTileKey key) { this.key = key; }
        }
        private final Map<PredictionTileKey, Node> nodes = new java.util.HashMap<>();
        private final Set<PredictionTileKey> families = new HashSet<>();
        private Set<PredictionTileKey> snapshot = Set.of();
        private boolean changed;

        void put(PredictionTileKey key, boolean scoped) {
            Node node = node(key);
            node.resident = true;
            if (node.scoped != scoped) {
                node.scoped = scoped;
                for (Node at = node; at != null; at = at.parent) at.scopes += scoped ? 1 : -1;
                refreshBranch(node, scopedAbove(node));
                for (Node at = node.parent; at != null; at = at.parent) refresh(at, scopedAbove(at));
            } else refresh(node, scopedAbove(node));
        }
        void remove(PredictionTileKey key) {
            Node node = nodes.get(key);
            if (node == null || !node.resident) return;
            node.resident = false;
            if (families.remove(key)) changed = true;
            if (node.scoped) {
                node.scoped = false;
                for (Node at = node; at != null; at = at.parent) at.scopes--;
                refreshBranch(node, scopedAbove(node));
                for (Node at = node.parent; at != null; at = at.parent) refresh(at, scopedAbove(at));
            }
            while (node != null && !node.resident && leaf(node)) {
                nodes.remove(node.key);
                Node parent = node.parent;
                if (parent != null) parent.children[slot(node.key)] = null;
                node = parent;
            }
        }
        private static boolean leaf(Node node) {
            for (Node child : node.children) if (child != null) return false;
            return true;
        }
        private Node node(PredictionTileKey key) {
            Node existing = nodes.get(key);
            if (existing != null) return existing;
            Node next = new Node(key);
            nodes.put(key, next);
            if (key.lod() < PredictionTileManager.MAX_LOD_LEVEL) {
                next.parent = node(ancestor(key, key.lod() + 1));
                next.parent.children[slot(key)] = next;
            }
            return next;
        }
        private static int slot(PredictionTileKey key) { return (key.tileX() & 1) | (key.tileZ() & 1) << 1; }
        private static boolean scopedAbove(Node node) {
            for (Node at = node.parent; at != null; at = at.parent) if (at.scoped) return true;
            return false;
        }
        private void refresh(Node node, boolean above) {
            boolean present = node.resident && (above || node.scopes > 0);
            changed |= present ? families.add(node.key) : families.remove(node.key);
        }
        private void refreshBranch(Node node, boolean above) {
            refresh(node, above);
            for (Node child : node.children) if (child != null) refreshBranch(child, above || node.scoped);
        }
        Set<PredictionTileKey> snapshot() {
            if (changed) { snapshot = Set.copyOf(families); changed = false; }
            return snapshot;
        }
        void clear() { nodes.clear(); families.clear(); snapshot = Set.of(); changed = false; }
    }

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
