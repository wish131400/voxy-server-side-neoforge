package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Short-lived scheduling hints, never proof for removing render coverage. */
final class PredictionVisibilityFeedback {
    static final long MAX_AGE_NANOS = 1_000_000_000L;
    record Stamp(PredictionTileKey key, long revision, long epoch) { }
    record Proof(long revision, long epoch, long observedAt, PredictionWorkView view, boolean hidden) { }
    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(Map.of());
        private record IndexedProof(PredictionTileKey key, Proof proof) { }
        private final Map<PredictionTileKey, Proof> proofs;
        private final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                PredictionTileTable<IndexedProof>> dimensions;

        Snapshot(Map<PredictionTileKey, Proof> proofs) {
            this.proofs = Map.copyOf(proofs);
            var grouped = new HashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                    Map<PredictionTileKey, IndexedProof>>();
            this.proofs.forEach((key, proof) -> grouped.computeIfAbsent(key.dimension(), ignored -> new HashMap<>())
                    .put(key, new IndexedProof(key, proof)));
            var indices = new HashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
                    PredictionTileTable<IndexedProof>>();
            grouped.forEach((dimension, values) -> indices.put(dimension,
                    new PredictionTileTable<IndexedProof>(dimension).changed(values, List.of())));
            dimensions = Map.copyOf(indices);
        }

        Map<PredictionTileKey, Proof> proofs() { return proofs; }

        /** Time can only change a fixed snapshot at proof age boundaries. */
        boolean sameAgeWindow(long before, long after) {
            for (var proof : proofs.values()) {
                long a = before - proof.observedAt(), b = after - proof.observedAt();
                if ((a >= 0 && a <= MAX_AGE_NANOS) != (b >= 0 && b <= MAX_AGE_NANOS)) return false;
            }
            return true;
        }

        /** Refreshing observation timestamps alone does not change hidden().
         * Preserve the exact key/proof/view checks and compare validity at both
         * request times, including future observations and expiration. */
        boolean sameHints(Snapshot other, long before, long after) {
            if (this == other) return sameAgeWindow(before, after);
            if (other == null || proofs.size() != other.proofs.size()) return false;
            for (var entry : proofs.entrySet()) {
                var a = entry.getValue(); var b = other.proofs.get(entry.getKey());
                if (b == null || a.revision() != b.revision() || a.epoch() != b.epoch() || a.hidden() != b.hidden()
                        || (a.view() == null ? b.view() != null : !a.view().sameGeometry(b.view()))) return false;
                long ageA = before - a.observedAt(), ageB = after - b.observedAt();
                if ((ageA >= 0 && ageA <= MAX_AGE_NANOS) != (ageB >= 0 && ageB <= MAX_AGE_NANOS)) return false;
            }
            return true;
        }

        boolean hidden(PredictionTileKey key, VssLodLayout layout, PredictionWorkView view, long now,
                       Map<PredictionTileKey, PredictionTile> ready, Map<PredictionTileKey, Long> epochs,
                       Set<PredictionTileKey> dirty) {
            if (view == null || dirty.contains(key)) return false;
            var index = dimensions.get(key.dimension());
            if (index == null) return false;
            int x = key.tileX(), z = key.tileZ();
            for (int lod = key.lod(); lod < layout.levelCount(); lod++, x >>= 1, z >>= 1) {
                // Missing ancestors are primitive lookups; a hit reuses the original
                // stamp key for mesh/epoch validation rather than allocating a key.
                var observed = index.at(x, z, lod);
                if (observed == null) continue;
                var ancestor = observed.key();
                var proof = observed.proof();
                var tile = ready.get(ancestor);
                long age = now - proof.observedAt();
                // A newer visible child overrides an older hidden parent. Invalid proof is unknown.
                if (tile == null || dirty.contains(ancestor) || tile.revision() != proof.revision()
                        || epochs.getOrDefault(ancestor, 0L) != proof.epoch() || age < 0 || age > MAX_AGE_NANOS
                        || view.changedFrom(proof.view())) return false;
                return proof.hidden();
            }
            return false;
        }
    }

    static final class History {
        private final Map<PredictionTileKey, Proof> proofs = new HashMap<>();
        Snapshot observe(List<Stamp> stamps, boolean[] hidden, PredictionWorkView view, long observedAt, long now) {
            proofs.entrySet().removeIf(e -> now - e.getValue().observedAt() > MAX_AGE_NANOS
                    || now - e.getValue().observedAt() < 0);
            if (now - observedAt >= 0 && now - observedAt <= MAX_AGE_NANOS) {
                for (int i = 0; i < stamps.size(); i++) {
                    var stamp = stamps.get(i);
                    var previous = proofs.get(stamp.key());
                    if (previous == null || observedAt >= previous.observedAt())
                        proofs.put(stamp.key(), new Proof(stamp.revision(), stamp.epoch(), observedAt, view, hidden[i]));
                }
            }
            // At most three bounded probes plus their one-second history can be retained.
            if (proofs.size() > 8192) proofs.clear();
            return new Snapshot(proofs);
        }
    }

    private PredictionVisibilityFeedback() { }
}
