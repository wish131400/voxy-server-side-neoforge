package dev.xantha.vss.client.prediction;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** A bounded spatial coverage pass, before descending into local detail. */
record PredictionMediumCoverage(Set<PredictionTileKey> frontier, Set<PredictionTileKey> paths) {
    static final PredictionMediumCoverage EMPTY = new PredictionMediumCoverage(Set.of(), Set.of());

    static int level(VssLodLayout layout, int bias) {
        return Math.min(layout.levelCount()-1, Math.max(0, layout.levelCount()-4) + Math.max(0,bias));
    }

    static PredictionMediumCoverage plan(List<PredictionTileKey> leaves, VssLodLayout layout) {
        return plan(leaves, layout, 0);
    }

    static PredictionMediumCoverage plan(List<PredictionTileKey> leaves, VssLodLayout layout, int levelBias) {
        // Eight tiles per horizon radius. Unlike the final quadtree, this
        // wave does not grow thousands of small tiles around the player.
        int level = level(layout, levelBias);
        Set<PredictionTileKey> frontier = new LinkedHashSet<>();
        for (var leaf : leaves) {
            int shift = Math.max(0, level - leaf.lod());
            frontier.add(new PredictionTileKey(leaf.dimension(), leaf.tileX() >> shift,
                    leaf.tileZ() >> shift, leaf.lod() + shift));
        }
        return new PredictionMediumCoverage(Set.copyOf(frontier),
                Set.copyOf(PredictionLodPlanner.withCoarseCoverage(List.copyOf(frontier), layout)));
    }
}
