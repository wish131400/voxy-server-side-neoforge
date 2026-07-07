package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import dev.xantha.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeferredCandidateOrderingTest {

    @Test
    void dirtyCandidatesAreOrderedBeforeNormalAndGenerationCandidates() {
        long normalNear = position(1, 0);
        long generationNear = position(2, 0);
        long dirtyFar = position(8, 0);
        LongOpenHashSet dirty = new LongOpenHashSet();
        LongOpenHashSet generation = new LongOpenHashSet();
        dirty.add(dirtyFar);
        generation.add(generationNear);

        LongList ordered = DeferredCandidateOrdering.order(
                candidates(generationNear, normalNear, dirtyFar),
                0,
                0,
                16,
                dirty::contains,
                generation::contains);

        assertIterableEquals(List.of(dirtyFar, normalNear, generationNear), ordered);
    }

    @Test
    void normalAndGenerationCandidatesStayRingOrdered() {
        long normalFar = position(8, 0);
        long generationNear = position(2, 0);
        long normalNear = position(1, 0);
        LongOpenHashSet generation = new LongOpenHashSet();
        generation.add(generationNear);

        LongList ordered = DeferredCandidateOrdering.order(
                candidates(normalFar, generationNear, normalNear),
                0,
                0,
                16,
                ignored -> false,
                generation::contains);

        assertIterableEquals(List.of(normalNear, generationNear, normalFar), ordered);
    }

    private static long position(int cx, int cz) {
        return PositionUtil.packPosition(cx, cz);
    }

    private static LongList candidates(long... values) {
        return new LongArrayList(values);
    }
}
