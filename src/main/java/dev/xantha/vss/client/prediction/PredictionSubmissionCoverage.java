package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.WeakHashMap;
import net.minecraft.world.phys.Vec3;

/** Cached CPU ownership plans shared by MDI, legacy draws and shader-pack passes. */
final class PredictionSubmissionCoverage {
    private PredictionExactCoverageMask.Snapshot index;
    private double x, y, z;
    private int radius;
    private long revision;
    private final WeakHashMap<PredictionPackedMesh, Entry> cache = new WeakHashMap<>();

    /**
     * The camera-dependent result is invalidated by a cell crossing, while the static
     * Snapshot/index proof stays attached to the mesh entry. This keeps movement cheap
     * without retaining ownership after a coverage revision.
     */
    private static final class Entry {
        final double baseX, baseZ;
        final PredictionDrawRanges[] source = new PredictionDrawRanges[64];
        final PredictionDrawRanges[] filtered = new PredictionDrawRanges[64];
        final long[] filteredRevision = new long[64];
        final PredictionOwnershipRuns.Scratch scratch = new PredictionOwnershipRuns.Scratch();
        byte[] staticCoverage;
        boolean allStaticCoverage;

        Entry(double baseX, double baseZ) {
            this.baseX = baseX;
            this.baseZ = baseZ;
            Arrays.fill(filteredRevision, -1L);
        }

    }

    void update(PredictionExactCoverageMask.Snapshot next, Vec3 camera, int radius) {
        double x = Math.floor(camera.x / 8) * 8 + 4, y = Math.floor(camera.y / 8) * 8 + 4;
        double z = Math.floor(camera.z / 8) * 8 + 4;
        boolean cameraChanged = this.x != x || this.y != y || this.z != z;
        boolean ownershipChanged = index != next || this.radius != radius;
        if (!cameraChanged && !ownershipChanged) return;
        index = next;
        this.x = x; this.y = y; this.z = z; this.radius = radius;
        if (ownershipChanged) {
            // Static coverage is only valid for the exact immutable snapshot and base.
            cache.clear();
        }
        revision++;
    }

    long revision() { return revision; }

    boolean fullyOwned(PredictionPackedMesh packed, double baseX, double baseZ) {
        if (index == null || radius <= 0 || packed.quadCount() == 0) return false;
        if (!packed.ownershipRuns.fullyInside(baseX, baseZ, x, y, z, radius,
                packed.morph() == null ? Float.POSITIVE_INFINITY : packed.morphMinY(),
                packed.morph() == null ? Float.NEGATIVE_INFINITY : packed.morphMaxY())) return false;
        // Most VRAM lives far outside Voxy. Reject those bounds before any run
        // scan, and use the prefix index for a solid covered tile in O(1).
        if (packed.ownershipRuns.wholeStaticCoverage(index, baseX, baseZ)) return true;
        Entry entry = cache.get(packed);
        if (entry == null || entry.baseX != baseX || entry.baseZ != baseZ) {
            entry = new Entry(baseX, baseZ); cache.put(packed, entry);
        }
        if (entry.staticCoverage == null) {
            entry.staticCoverage = packed.ownershipRuns.staticCoverage(index, baseX, baseZ);
            entry.allStaticCoverage = PredictionOwnershipRuns.allStaticCoverage(entry.staticCoverage);
        }
        return entry.allStaticCoverage;
    }

    PredictionDrawRanges filter(PredictionPackedMesh packed, PredictionDrawRanges ranges,
                                double baseX, double baseZ, boolean water, int faces) {
        if (index == null || radius <= 0 || ranges.quads == 0) return ranges;
        Entry entry = cache.get(packed);
        if (entry == null || entry.baseX != baseX || entry.baseZ != baseZ) {
            entry = new Entry(baseX, baseZ);
            cache.put(packed, entry);
        }
        int slot = (water ? 32 : 0) + faces;
        if (entry.source[slot] != ranges) {
            entry.source[slot] = ranges;
            entry.filtered[slot] = null;
            entry.filteredRevision[slot] = -1L;
        }
        if (entry.staticCoverage == null) {
            entry.staticCoverage = packed.ownershipRuns.staticCoverage(index, baseX, baseZ);
            entry.allStaticCoverage = PredictionOwnershipRuns.allStaticCoverage(entry.staticCoverage);
        }
        if (entry.filteredRevision[slot] != revision) {
            float morphMin = !water && packed.morph() != null ? packed.morphMinY() : Float.POSITIVE_INFINITY;
            float morphMax = !water && packed.morph() != null ? packed.morphMaxY() : Float.NEGATIVE_INFINITY;
            entry.filtered[slot] = packed.ownershipRuns.filter(ranges, index, baseX, baseZ, x, y, z, radius,
                    morphMin, morphMax, entry.staticCoverage, entry.allStaticCoverage,
                    entry.scratch, entry.filtered[slot]);
            entry.filteredRevision[slot] = revision;
        }
        return entry.filtered[slot];
    }

    void clear() {
        index = null;
        cache.clear();
        revision++;
    }
}
