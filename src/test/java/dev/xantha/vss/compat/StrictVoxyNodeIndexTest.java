package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StrictVoxyNodeIndexTest {
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
        assertTrue(index.covers(-1, 31, 31));
        assertFalse(index.covers(0, 0, 0));
        index.update(1, StrictVoxyNodeIndex.key(0, 10, 2, 10), 456);
        assertFalse(index.covers(-1, 31, 31));
        assertTrue(index.covers(20, 4, 20));
        index.clear();
        assertFalse(index.covers(20, 4, 20));
    }
}
