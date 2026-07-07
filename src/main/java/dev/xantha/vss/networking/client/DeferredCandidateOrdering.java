package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.function.LongPredicate;

final class DeferredCandidateOrdering {
    private DeferredCandidateOrdering() {
    }

    static LongList order(
            LongList candidates,
            int playerCx,
            int playerCz,
            int lodDistance,
            LongPredicate dirtyRefreshPredicate,
            LongPredicate generationPredicate) {
        if (candidates.size() <= 1) {
            return candidates;
        }

        int bucketCount = Math.max(1, lodDistance + 2);
        LongArrayFIFOQueue[] dirtyBuckets = newBuckets(bucketCount);
        LongArrayFIFOQueue[] normalBuckets = newBuckets(bucketCount);
        LongArrayFIFOQueue[] generationBuckets = newBuckets(bucketCount);

        LongIterator iterator = candidates.longIterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int ring = PositionUtil.chebyshevDistance(
                    PositionUtil.unpackX(packed),
                    PositionUtil.unpackZ(packed),
                    playerCx,
                    playerCz);
            int bucket = Math.min(Math.max(0, ring), bucketCount - 1);
            LongArrayFIFOQueue[] buckets = bucketsFor(
                    packed,
                    dirtyBuckets,
                    normalBuckets,
                    generationBuckets,
                    dirtyRefreshPredicate,
                    generationPredicate);
            LongArrayFIFOQueue ringBucket = buckets[bucket];
            if (ringBucket == null) {
                ringBucket = new LongArrayFIFOQueue();
                buckets[bucket] = ringBucket;
            }
            ringBucket.enqueue(packed);
        }

        LongArrayList ordered = new LongArrayList(candidates.size());
        appendBuckets(ordered, dirtyBuckets);
        for (int i = 0; i < bucketCount; i++) {
            appendBucket(ordered, normalBuckets[i]);
            appendBucket(ordered, generationBuckets[i]);
        }
        return ordered;
    }

    private static LongArrayFIFOQueue[] bucketsFor(
            long packed,
            LongArrayFIFOQueue[] dirtyBuckets,
            LongArrayFIFOQueue[] normalBuckets,
            LongArrayFIFOQueue[] generationBuckets,
            LongPredicate dirtyRefreshPredicate,
            LongPredicate generationPredicate) {
        if (dirtyRefreshPredicate.test(packed)) {
            return dirtyBuckets;
        }
        return generationPredicate.test(packed) ? generationBuckets : normalBuckets;
    }

    private static LongArrayFIFOQueue[] newBuckets(int bucketCount) {
        return new LongArrayFIFOQueue[bucketCount];
    }

    private static void appendBuckets(LongArrayList ordered, LongArrayFIFOQueue[] buckets) {
        for (LongArrayFIFOQueue bucket : buckets) {
            appendBucket(ordered, bucket);
        }
    }

    private static void appendBucket(LongArrayList ordered, LongArrayFIFOQueue bucket) {
        if (bucket == null) {
            return;
        }
        while (!bucket.isEmpty()) {
            ordered.add(bucket.dequeueLong());
        }
    }
}
