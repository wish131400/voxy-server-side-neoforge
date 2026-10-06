package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StrictVoxyNodeIndexTest {
    @Test void boxProofsMatchEveryColumnAndSectionAcrossMixedAncestors() {
        var index = new StrictVoxyNodeIndex();
        var random = new java.util.Random(714509);
        for (int id = 0; id < 1600; id++) {
            int lod = random.nextInt(5), shift = lod + 1;
            index.update(id, StrictVoxyNodeIndex.key(lod, (random.nextInt(128) - 64) >> shift,
                    (random.nextInt(128) - 64) >> shift, (random.nextInt(128) - 64) >> shift), id % 3 == 0 ? 0xFFFFFE : 123);
        }
        for (int check = 0; check < 2000; check++) {
            int x = random.nextInt(140) - 70, z = random.nextInt(140) - 70, y = random.nextInt(160) - 80;
            int endX = x + random.nextInt(12), endZ = z + random.nextInt(12), endY = y + random.nextInt(44);
            boolean expected = true;
            outer: for (int cz = z; cz <= endZ; cz++) for (int cx = x; cx <= endX; cx++)
                for (int sy = y; sy <= endY; sy++) if (!index.covers(cx, sy, cz)) { expected = false; break outer; }
            assertEquals(expected, index.coversBox(x, y, z, endX, endY, endZ));
            assertEquals(expected, index.coversBox(x, endY, z, endX, y, endZ));
        }
    }

    @Test void largeReadyFootprintUsesOneProofPerAncestorRatherThanPerChunk() {
        var index = new StrictVoxyNodeIndex();
        int id = 0;
        for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++)
            index.update(id++, StrictVoxyNodeIndex.key(4, x, 0, z), 123);
        long before = index.boxLookups();
        assertTrue(index.coversBox(-256, 0, -256, 255, 31, 255));
        assertEquals(256, index.boxLookups() - before, "512 x 512 chunks require 256 uploaded ancestor proofs");
        index.update(127, -1, -1);
        assertFalse(index.coversBox(-256, 0, -256, 255, 31, 255), "a missing ancestor without ready children retains prediction");
    }

    @Test void longVerticalBoxIncludesThePrefixRepeatedAfterThePackedYPeriod() {
        var index = new StrictVoxyNodeIndex();
        for (int y = 0; y < 256; y++) index.update(y, StrictVoxyNodeIndex.key(4, 0, y, 0), 123);
        index.update(0, -1, -1);
        // All but the first half of this ancestor have finer replacement geometry.
        for (int y = 8; y < 16; y++) index.update(1000 + y, StrictVoxyNodeIndex.key(0, 0, y, 0), 123);
        assertTrue(index.coversBox(0, 16, 0, 1, 31, 1));
        assertFalse(index.coversBox(0, 16, 0, 1, 8192 + 31, 1));
        index.update(0, StrictVoxyNodeIndex.key(4, 0, 0, 0), 0xFFFFFE);
        assertTrue(index.coversBox(0, Integer.MIN_VALUE, 0, 1, Integer.MAX_VALUE, 1));
    }

    @Test void readinessChangesNotifyBothMovedRegionsButGeometryAndDuplicateIdsDoNotChurn() {
        var changed = new java.util.ArrayList<Long>();
        var index = new StrictVoxyNodeIndex(changed::add, () -> changed.add(Long.MIN_VALUE));
        long a = StrictVoxyNodeIndex.key(0, -1, -1, -1), b = StrictVoxyNodeIndex.key(4, 2, 0, 2);
        index.update(1, a, 123);
        assertEquals(java.util.List.of(a), changed);
        changed.clear();
        index.update(1, a, 456);
        index.update(2, a, 789);
        index.update(1, -1, -1);
        assertTrue(changed.isEmpty());
        index.update(2, b, 123);
        assertEquals(java.util.List.of(a, b), changed);
        index.clear();
        assertEquals(Long.MIN_VALUE, changed.get(changed.size() - 1));
    }

    @Test void rangeQueriesMatchSectionBySectionCoverageAcrossMixedNodesAndNegativeHeights() {
        var index = new StrictVoxyNodeIndex();
        var random = new java.util.Random(98123);
        for (int id = 0; id < 1200; id++) {
            int lod = random.nextInt(5), shift = lod + 1;
            index.update(id, StrictVoxyNodeIndex.key(lod, (random.nextInt(192) - 96) >> shift,
                    (random.nextInt(128) - 64) >> shift, (random.nextInt(192) - 96) >> shift), 123);
        }
        for (int check = 0; check < 4000; check++) {
            int x = random.nextInt(192) - 96, z = random.nextInt(192) - 96;
            int first = random.nextInt(96) - 48, last = first + random.nextInt(65);
            boolean expected = true;
            for (int y = first; y <= last; y++) if (!index.covers(x, y, z)) { expected = false; break; }
            assertEquals(expected, index.coversRange(x, first, last, z));
            assertEquals(expected, index.coversRange(x, last, first, z));
        }
    }

    @Test void atomicNodeIdReplacementDoesNotRetractButActualHoleStillDoes() {
        StrictVoxyNodeIndex index = new StrictVoxyNodeIndex();
        var frontier = new dev.xantha.vss.networking.client.StrictLodFrontier();
        frontier.center(0, 0, 128);
        for (int i=0; i<8; i++) frontier.advance((x,z)->true, (x,z)->true);
        long key = StrictVoxyNodeIndex.key(0, 1, 0, 0);
        index.update(1, key, 42);
        var removed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        removed.add(index.update(1, -1, -1));
        index.update(2, key, 43);
        index.retractUncovered(frontier, removed, (x,z)->index.covers(x,0,z));
        assertEquals(7, frontier.visibleRing(), "same GPU batch replaced the node");
        index.update(3, StrictVoxyNodeIndex.key(4, 0, 0, 0), 99);
        index.update(2, -1, -1);
        index.retractUncovered(frontier, removed, (x,z)->index.covers(x,0,z));
        assertEquals(7, frontier.visibleRing(), "resident ancestor still covers the column");
        index.update(3, -1, -1);
        index.retractUncovered(frontier, removed, (x,z)->index.covers(x,0,z));
        assertEquals(1, frontier.visibleRing(), "real loss must still close all outer rings");
    }
    @Test void missingNodesAndNullGeometryAreNotReadyButExplicitEmptyGeometryIs() {
        StrictVoxyNodeIndex index = new StrictVoxyNodeIndex();
        long position = StrictVoxyNodeIndex.key(0, -1, -2, 0);
        assertFalse(index.covers(-1, -3, 0));
        index.update(7, position, 0xFFFFFF);
        assertFalse(index.covers(-1, -3, 0));
        index.update(7, position, 0xFFFFFE);
        assertTrue(index.covers(-1, -3, 0));
        assertFalse(index.covers(0, -3, 0));
        assertEquals(position, index.update(7, -1L, -1));
        assertFalse(index.covers(-1, -3, 0));
    }
    @Test void parentMeshCoversChildrenAndEvictionOrIdReuseRevokesOldCoverage() {
        StrictVoxyNodeIndex index = new StrictVoxyNodeIndex();
        long parent = StrictVoxyNodeIndex.key(4, -1, 0, 0);
        index.update(1, parent, 123);
        assertTrue(index.covers(-32, 0, 0));
        assertFalse(index.coversFinest(-32, 0, 0), "an ancestor remains fallback geometry only");
        assertTrue(index.covers(-1, 31, 31));
        assertFalse(index.covers(0, 0, 0));
        index.update(1, StrictVoxyNodeIndex.key(0, 10, 2, 10), 456);
        assertFalse(index.covers(-1, 31, 31));
        assertTrue(index.covers(20, 4, 20));
        assertTrue(index.coversFinest(20, 4, 20));
        index.clear();
        assertFalse(index.covers(20, 4, 20));
        assertFalse(index.coversFinest(20, 4, 20));
    }

    @Test void rangeCoverageRequiresEverySectionAndAcceptsACompleteAncestor() {
        StrictVoxyNodeIndex index = new StrictVoxyNodeIndex();
        index.update(1, StrictVoxyNodeIndex.key(0, 2, 0, 3), 123);
        assertTrue(index.coversRange(4, 0, 1, 6));
        assertFalse(index.coversRange(4, 0, 2, 6), "one missing section keeps prediction active");

        index.update(2, StrictVoxyNodeIndex.key(4, 0, 0, 0), 456);
        assertTrue(index.coversRange(4, 0, 31, 6), "a complete ancestor covers its vertical span");
        assertFalse(index.coversRange(4, 0, 32, 6));

        index.update(2, -1L, -1);
        assertFalse(index.coversRange(4, 0, 31, 6), "removing the ancestor revokes the missing sections");
    }

    @Test void duplicateNodeIdsAndEmptyTransitionsPreserveReadinessUntilLastEviction() {
        var index = new StrictVoxyNodeIndex();
        long position = StrictVoxyNodeIndex.key(0, -1, 2, -1);
        index.update(1, position, 0xFFFFFE);
        assertTrue(index.covers(-1, 4, -1));
        assertEquals(Long.MIN_VALUE, index.update(1, position, 123));
        index.update(2, position, 789);
        assertEquals(Long.MIN_VALUE, index.update(1, -1, -1));
        assertTrue(index.covers(-1, 4, -1), "the duplicate is still ready");
        assertEquals(Long.MIN_VALUE, index.update(2, position, 0xFFFFFE));
        assertTrue(index.covers(-1, 4, -1), "explicit empty geometry still completes the ring");
        assertEquals(position, index.update(2, -1, -1));
        assertFalse(index.covers(-1, 4, -1));
    }
}
