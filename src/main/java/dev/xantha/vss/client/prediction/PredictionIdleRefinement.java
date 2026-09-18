package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Bounded, optional quality improvements layered over the ordinary plan. Render-thread owned. */
final class PredictionIdleRefinement {
    static final int MAX_TARGETS = 128;
    static final long MAX_RESIDENT_BYTES = 64L * PredictionMemoryBudget.MIB;
    static final long SETTLE_NANOS = 2_000_000_000L;
    private final Map<PredictionTileKey, Integer> targets = new LinkedHashMap<>();
    private long settledAt = Long.MIN_VALUE, nextPromotion;
    private double anchorX, anchorZ;
    private String state = "settling";

    String state() { return state; }

    boolean settled(long now, boolean available, double x, double z) {
        if (!available) { settledAt = Long.MIN_VALUE; return false; }
        if (settledAt == Long.MIN_VALUE || Math.hypot(x - anchorX, z - anchorZ) > 16) {
            settledAt = now;
            anchorX = x;
            anchorZ = z;
        }
        return now - settledAt >= SETTLE_NANOS;
    }

    Map<PredictionTileKey, Integer> update(Set<PredictionTileKey> leaves,
            Map<PredictionTileKey, Integer> baseTargets, Map<PredictionTileKey, Integer> residentAxes,
            VssLodLayout layout, Predicate<PredictionTileKey> eligible,
            ToDoubleFunction<PredictionTileKey> score, boolean advance, long now) {
        // Ordinary planning takes ownership as the player approaches. Expired
        // promotions do not delete resident meshes or invalidate disk entries.
        targets.keySet().removeIf(key -> owner(key, leaves, layout.levelCount()) == null
                || baseTargets.getOrDefault(key, 0) >= targets.get(key));
        if (!advance) return Map.copyOf(targets);
        if (targets.entrySet().stream().anyMatch(e -> residentAxes.getOrDefault(e.getKey(), 0) < e.getValue())) {
            state = "refining";
            return Map.copyOf(targets);
        }
        if (now < nextPromotion) {
            state = "cooldown";
            return Map.copyOf(targets);
        }
        Map<PredictionTileKey, Integer> candidates = new HashMap<>(baseTargets);
        candidates.putAll(targets);
        Set<PredictionTileKey> frontier = new java.util.HashSet<>(candidates.keySet());
        frontier.removeIf(key -> key.lod() > 0
                && candidates.keySet().containsAll(PredictionTransitionPlan.children(key)));
        PredictionTileKey best = null;
        double bestScore = 0;
        for (var entry : candidates.entrySet()) {
            var key = entry.getKey();
            int axis = residentAxes.getOrDefault(key, 0);
            if (!frontier.contains(key) || !eligible.test(key) || axis < entry.getValue()
                    || layout.tileBlocks(key.lod()) / Math.max(1, axis) <= 2) continue;
            if (axis >= 64 && (key.lod() == 0 || !canSplit(key, frontier, layout.levelCount()))) continue;
            int extra = axis < 64 ? (targets.containsKey(key) ? 0 : 1) : 4;
            if (targets.size() + extra > MAX_TARGETS) continue;
            double value = score.applyAsDouble(key);
            if (value > bestScore || value == bestScore && value > 0 && before(key, best)) {
                best = key;
                bestScore = value;
            }
        }
        if (best != null) {
            int axis = residentAxes.get(best);
            if (axis < 64) targets.put(best, Math.min(64, axis * 2));
            else for (var child : PredictionTransitionPlan.children(best)) targets.put(child, 64);
            nextPromotion = now + 1_000_000_000L;
            state = "refining";
        } else {
            state = targets.size() >= MAX_TARGETS - 3 ? "target-limit" : "quality-complete";
        }
        return Map.copyOf(targets);
    }

    private static boolean before(PredictionTileKey a, PredictionTileKey b) {
        return b == null || a.lod() < b.lod() || a.lod() == b.lod()
                && (a.tileZ() < b.tileZ() || a.tileZ() == b.tileZ() && a.tileX() < b.tileX());
    }

    private static boolean canSplit(PredictionTileKey key, Set<PredictionTileKey> frontier, int levels) {
        // A new child may meet a sibling-sized neighbour, but must not create
        // a two-level boundary. Let that neighbour improve first instead.
        for (int[] direction : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}})
            for (int lod = key.lod() + 1; lod < levels; lod++) {
                int shift = lod - key.lod();
                if (frontier.contains(new PredictionTileKey(key.dimension(),
                        (key.tileX() + direction[0]) >> shift,
                        (key.tileZ() + direction[1]) >> shift, lod))) return false;
            }
        return true;
    }

    private static PredictionTileKey owner(PredictionTileKey key, Set<PredictionTileKey> leaves, int levels) {
        for (var parent = key; parent.lod() < levels; parent = new PredictionTileKey(
                key.dimension(), parent.tileX() >> 1, parent.tileZ() >> 1, parent.lod() + 1))
            if (leaves.contains(parent)) return parent;
        return null;
    }
}
