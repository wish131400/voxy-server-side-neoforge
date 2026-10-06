package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionRenderer.Draw;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Render-thread page grouping within bounded near-to-far distance buckets. */
final class PredictionOpaqueBatches {
    private static final int BUCKET_TARGET = 384;
    private static final int BUCKET_LIMIT = 8;
    private final ArrayList<Bucket> buckets = new ArrayList<>();
    /**
     * The visible plan is immutable between scene changes, but the renderer
     * used to rebuild every page map on every frame anyway. Keep the last
     * grouping and use the render-residency revision as its invalidation token.
     * This is deliberately render-thread-only: no synchronization is needed
     * and a residency change simply causes a miss on the next frame.
     */
    private List<Draw> preparedInput;
    private IdentityHashMap<Draw, PredictionDrawRanges> preparedRanges;
    private long preparedResidencyRevision = Long.MIN_VALUE;
    private int preparedBucketCount;
    private long cacheHits;
    private long cacheMisses;

    int prepare(List<Draw> draws) {
        return prepare(draws, Long.MIN_VALUE);
    }

    int prepare(List<Draw> draws, long residencyRevision) {
        return prepare(draws, residencyRevision, null);
    }

    /**
     * Prepares page groups using the ranges already built by the owning
     * renderer pass. A render frame must not ask every packed mesh for the
     * same opaque range a second time just to decide whether it belongs in a
     * page group.
     */
    int prepare(List<Draw> draws, long residencyRevision,
                IdentityHashMap<Draw, PredictionDrawRanges> drawableRanges) {
        // The legacy overload has no residency token. Keep its historical
        // rebuild semantics; only the production caller may reuse a grouping.
        if (residencyRevision != Long.MIN_VALUE
                && draws == preparedInput && residencyRevision == preparedResidencyRevision
                && drawableRanges == preparedRanges) {
            cacheHits++;
            return preparedBucketCount;
        }
        cacheMisses++;
        int bucketCount = draws.isEmpty() ? 0
                : Math.min(BUCKET_LIMIT, Math.max(1, (draws.size() + BUCKET_TARGET - 1) / BUCKET_TARGET));
        while (buckets.size() < bucketCount) buckets.add(new Bucket());
        clear();
        for (int index = 0; index < draws.size(); index++) {
            Draw draw = draws.get(index);
            PredictionPackedMesh packed = draw.gpu().packed();
            if (packed == null) continue;
            if (drawableRanges != null) {
                if (!drawableRanges.containsKey(draw)) continue;
            } else if (packed.drawRanges(false, draw.faces()).quads == 0) {
                continue;
            }
            int bucketIndex = Math.min(bucketCount - 1, (int) ((long) index * bucketCount / draws.size()));
            PredictionTerrainArena.Slice slice = draw.gpu().arenaSlice();
            if (slice == null) buckets.get(bucketIndex).fallback.add(draw);
            else buckets.get(bucketIndex).add(slice.page, draw);
        }
        preparedInput = draws;
        preparedRanges = drawableRanges;
        preparedResidencyRevision = residencyRevision;
        preparedBucketCount = bucketCount;
        return bucketCount;
    }

    Bucket bucket(int index) { return buckets.get(index); }

    void clear() {
        // Also clear inactive buckets so a smaller scene releases its old Draw/GPU references.
        for (Bucket bucket : buckets) bucket.clear();
        preparedInput = null;
        preparedRanges = null;
        preparedResidencyRevision = Long.MIN_VALUE;
        preparedBucketCount = 0;
    }

    long cacheHits() { return cacheHits; }
    long cacheMisses() { return cacheMisses; }

    static final class Bucket {
        final ArrayList<PageGroup> pages = new ArrayList<>();
        final ArrayList<Draw> fallback = new ArrayList<>();
        private final IdentityHashMap<PredictionTerrainArena.Page, PageGroup> byPage = new IdentityHashMap<>();
        private final ArrayList<PageGroup> reusable = new ArrayList<>();

        private void clear() {
            for (PageGroup group : pages) group.draws.clear();
            pages.clear();
            byPage.clear();
            fallback.clear();
        }

        private void add(PredictionTerrainArena.Page page, Draw draw) {
            PageGroup group = byPage.get(page);
            if (group == null) {
                int index = pages.size();
                if (index == reusable.size()) reusable.add(new PageGroup());
                group = reusable.get(index);
                byPage.put(page, group);
                pages.add(group);
            }
            group.draws.add(draw);
        }
    }

    static final class PageGroup {
        final ArrayList<Draw> draws = new ArrayList<>();
    }
}
