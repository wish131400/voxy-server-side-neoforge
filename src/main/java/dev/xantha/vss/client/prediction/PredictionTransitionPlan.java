package dev.xantha.vss.client.prediction;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Spatial transition bands, independent of textures and render coverage masks. */
final class PredictionTransitionPlan {
    static final int MAX_EXTRA_LEAVES = 512;
    private PredictionTransitionPlan() { }

    static List<PredictionTileKey> balance(List<PredictionTileKey> input, int levels) {
        Set<PredictionTileKey> leaves = new LinkedHashSet<>(input);
        var queue = new ArrayDeque<>(input);
        // Reserve bounded extra planning space for the transition perimeter.
        int limit = input.size() + MAX_EXTRA_LEAVES;
        int[][] directions = {{-1,0},{1,0},{0,-1},{0,1}};
        while (!queue.isEmpty()) {
            var key = queue.removeFirst();
            if (!leaves.contains(key)) continue;
            for (int[] direction : directions) {
                for (int lod = key.lod() + 2; lod < levels; lod++) {
                    int shift = lod - key.lod();
                    var neighbor = new PredictionTileKey(key.dimension(),
                            (key.tileX() + direction[0]) >> shift,
                            (key.tileZ() + direction[1]) >> shift, lod);
                    if (!leaves.contains(neighbor)) continue;
                    if (leaves.size() + 3 > limit) break;
                    leaves.remove(neighbor);
                    for (var child : children(neighbor)) {
                        leaves.add(child);
                        queue.addLast(child);
                    }
                    queue.addLast(key);
                    break;
                }
            }
        }
        return List.copyOf(leaves);
    }

    static List<PredictionTileKey> children(PredictionTileKey key) {
        return List.of(new PredictionTileKey(key.dimension(), key.tileX()*2, key.tileZ()*2, key.lod()-1),
                new PredictionTileKey(key.dimension(), key.tileX()*2+1, key.tileZ()*2, key.lod()-1),
                new PredictionTileKey(key.dimension(), key.tileX()*2, key.tileZ()*2+1, key.lod()-1),
                new PredictionTileKey(key.dimension(), key.tileX()*2+1, key.tileZ()*2+1, key.lod()-1));
    }

    static Map<PredictionTileKey, Integer> exposedTargets(Set<PredictionTileKey> planned,
                                                         Set<PredictionTileKey> leaves,
                                                         Map<PredictionTileKey, Integer> residentAxes,
                                                         int levels) {
        return exposedTargets(planned, leaves, residentAxes, levels, Map.of());
    }

    static Map<PredictionTileKey, Integer> exposedTargets(Set<PredictionTileKey> planned,
                                                         Set<PredictionTileKey> leaves,
                                                         Map<PredictionTileKey, Integer> residentAxes,
                                                         int levels, Map<PredictionTileKey, Integer> leafTargets) {
        Map<PredictionTileKey, Integer> targets = new HashMap<>();
        for (var entry : residentAxes.entrySet()) {
            var child = entry.getKey();
            if (!planned.contains(child) || child.lod() + 1 >= levels) continue;
            var parent = new PredictionTileKey(child.dimension(), child.tileX() >> 1, child.tileZ() >> 1, child.lod()+1);
            if (!planned.contains(parent) || !residentAxes.containsKey(parent)) continue;
            // A requested child is not coverage until its mesh is ready.
            // Only skip a parent once all four child regions have replacements.
            if (residentAxes.keySet().containsAll(children(parent))) continue;
            int axis = leaves.contains(child) ? Math.max(entry.getValue(),
                    Math.min(leafTargets.getOrDefault(child,64), entry.getValue()*2)) : entry.getValue();
            if (axis > residentAxes.get(parent)) targets.merge(parent, axis, Math::max);
        }
        return Map.copyOf(targets);
    }
}
