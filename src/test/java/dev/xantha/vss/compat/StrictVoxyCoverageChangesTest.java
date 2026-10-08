package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StrictVoxyCoverageChangesTest {
    @Test void regionStampsIncludeEveryDescendantAndAncestorWithoutFlushingDistantRegions() {
        var changes = new StrictVoxyCoverageChanges(2);
        long near = changes.regionRevision(-8, 16, -1, 23);
        changes.columnChanged(-3, 21);
        assertTrue(changes.regionRevision(-8, 16, -1, 23) > near);
        near = changes.regionRevision(-8, 16, -1, 23);
        for (int i = 0; i < 100; i++) changes.columnChanged(1000 + i, 1000);
        assertEquals(near, changes.regionRevision(-8, 16, -1, 23), "history eviction retains local descendant stamps");
        changes.nodeChanged(StrictVoxyNodeIndex.key(4, -1, 0, 0));
        assertTrue(changes.regionRevision(-8, 16, -1, 23) > near, "coarser uploaded ancestors invalidate the region");
        near = changes.regionRevision(-8, 16, -1, 23);
        changes.windowMoved(0, 0, 1, 0, 64);
        assertEquals(near, changes.regionRevision(-8, 16, -1, 23));
        changes.invalidateAll();
        assertTrue(changes.regionRevision(-8, 16, -1, 23) > near);
    }

    @Test void arbitraryRegionBoundsConservativelyIncludeLocalEditsAcrossZero() {
        var changes = new StrictVoxyCoverageChanges(); var random = new java.util.Random(145);
        for (int test = 0; test < 500; test++) {
            int minX = random.nextInt(100) - 50, minZ = random.nextInt(100) - 50;
            int maxX = minX + random.nextInt(64), maxZ = minZ + random.nextInt(64);
            long before = changes.regionRevision(minX, minZ, maxX, maxZ);
            changes.columnChanged(minX + random.nextInt(maxX - minX + 1), minZ + random.nextInt(maxZ - minZ + 1));
            assertTrue(changes.regionRevision(minX, minZ, maxX, maxZ) > before);
        }
    }

    @Test void distantChangesKeepLocalColumnVersionsAndIncludeEveryAncestorChild() {
        var changes = new StrictVoxyCoverageChanges();
        changes.columnChanged(3, 4);
        long local = changes.columnRevision(3, 4), before = changes.revision();
        changes.nodeChanged(StrictVoxyNodeIndex.key(4, -1, 0, 2));
        assertEquals(local, changes.columnRevision(3, 4));
        for (int x = -32; x < 0; x++) for (int z = 64; z < 96; z++)
            assertTrue(changes.columnRevision(x, z) > before);
        assertEquals(0, changes.columnRevision(0, 64));
        assertEquals(0, changes.columnRevision(-33, 64));
        assertEquals(0, changes.columnRevision(-1, 96));
        var delta = changes.since(before);
        assertFalse(delta.reset());
        assertEquals(java.util.List.of(new StrictVoxyCoverageChanges.Area(-32, 64, 0, 96)), delta.areas());
    }

    @Test void repeatedEditsAreCoalescedButAReaderMissingEvictedHistoryMustReset() {
        var changes = new StrictVoxyCoverageChanges(2);
        changes.columnChanged(-1, -1);
        long first = changes.revision();
        for (int edit = 0; edit < 10_000; edit++) changes.columnChanged(-1, -1);
        assertFalse(changes.since(first).reset());
        assertEquals(1, changes.since(first).areas().size());
        changes.columnChanged(10, 10);
        long beforeEviction = changes.revision();
        changes.columnChanged(20, 20);
        assertTrue(changes.since(first).reset());
        assertFalse(changes.since(beforeEviction).reset());
        assertEquals(1, changes.since(beforeEviction).areas().size());
        assertTrue(changes.columnRevision(-1, -1) > first, "evicted local stamps cannot leave a stale cached answer");
    }

    @Test void resetInvalidatesEveryColumnAndStartsASeparateHistory() {
        var changes = new StrictVoxyCoverageChanges();
        changes.columnChanged(0, 0);
        long before = changes.revision();
        changes.invalidateAll();
        assertTrue(changes.since(before).reset());
        assertTrue(changes.columnRevision(100, -100) > before);
        long reset = changes.revision();
        changes.columnChanged(-7, 3);
        assertFalse(changes.since(reset).reset());
        assertEquals(1, changes.since(reset).areas().size());
    }

    @Test void resetBaselineDominatesOldStampsAndRetainsNewLocalInvalidations() {
        var changes = new StrictVoxyCoverageChanges(2);
        for (int edit = 0; edit < 1000; edit++) changes.columnChanged(3, 4);
        changes.nodeChanged(StrictVoxyNodeIndex.key(4, -1, 0, 2));
        long oldColumn = changes.columnRevision(3, 4);
        long oldRegion = changes.regionRevision(-32, 64, -1, 95);
        changes.windowMoved(0, 0, 1, 0, 64);
        changes.invalidateAll();
        long reset = changes.resetRevision();
        assertTrue(reset > Math.max(oldColumn, oldRegion));
        assertEquals(reset, changes.columnRevision(3, 4));
        assertEquals(reset, changes.regionRevision(-32, 64, -1, 95));
        var delta = changes.since(reset);
        assertFalse(delta.reset());
        assertTrue(delta.areas().isEmpty());
        assertTrue(delta.windows().isEmpty());
        changes.columnChanged(-3, 70);
        assertTrue(changes.columnRevision(-3, 70) > reset);
        assertTrue(changes.regionRevision(-32, 64, -1, 95) > reset);
        assertEquals(reset, changes.columnRevision(3, 4),
                "new local edits must not revive unrelated stamps from before the reset");
        changes.invalidateAll();
        long nextReset = changes.resetRevision();
        assertTrue(nextReset > reset);
        assertEquals(nextReset, changes.columnRevision(-3, 70));
        assertEquals(nextReset, changes.regionRevision(-32, 64, -1, 95));
    }

    @Test void evictingAreaHistoryPreservesLocalStampsWithoutGloballyInvalidatingColumns() {
        var changes = new StrictVoxyCoverageChanges(2);
        changes.columnChanged(0, 0);
        long original = changes.columnRevision(0, 0);
        for (int x = 1; x <= 100; x++) changes.columnChanged(x, 10);
        assertTrue(changes.since(original).reset(), "scene readers still detect missing area history");
        assertEquals(original, changes.columnRevision(0, 0), "eviction must retain the original local stamp");
        assertEquals(0, changes.columnRevision(-1000, -1000), "unrelated columns keep their warm answers");
        changes.columnChanged(0, 0);
        assertTrue(changes.columnRevision(0, 0) > original);
    }
    @Test void movingWindowKeepsColumnVersionsAndSignalsOnlyTheCircularEdge() {
        var changes = new StrictVoxyCoverageChanges(2);
        changes.columnChanged(0, 0);
        long local = changes.columnRevision(0, 0), before = changes.revision();
        changes.windowMoved(0, 0, 1, 0, 62);
        var delta = changes.since(before);
        assertFalse(delta.reset());
        assertTrue(delta.areas().isEmpty());
        assertEquals(local, changes.columnRevision(0, 0));
        assertEquals(1, delta.windows().size());
        var moved = delta.windows().get(0);
        assertFalse(moved.affects(-2, -2, 3, 3));
        assertFalse(moved.affects(100, 100, 104, 104));
        assertTrue(moved.affects(60, 0, 62, 1));
        assertTrue(moved.affects(-60, 0, -59, 1));
        changes.windowMoved(1, 0, 2, 1, 62);
        changes.windowMoved(2, 1, 3, 1, 62);
        assertTrue(changes.since(before).reset(), "a missed movement must conservatively invalidate old masks");
        assertEquals(local, changes.columnRevision(0, 0), "lost mask history must not flush underlying node queries");
    }

    @Test void edgeClassifierIncludesEveryChangedCellAcrossNegativeMovesAndTeleports() {
        var random = new java.util.Random(591);
        for (int sample = 0; sample < 300; sample++) {
            int ox = random.nextInt(80) - 40, oz = random.nextInt(80) - 40;
            int nx = random.nextInt(80) - 40, nz = random.nextInt(80) - 40, radius = 3 + random.nextInt(30);
            var window = new StrictVoxyCoverageChanges.WindowMovement(ox, oz, nx, nz, radius);
            for (int rect = 0; rect < 30; rect++) {
                int x = random.nextInt(120) - 60, z = random.nextInt(120) - 60;
                int width = 1 + random.nextInt(8), depth = 1 + random.nextInt(8);
                boolean changed = false;
                for (int cz = z; cz < z + depth; cz++) for (int cx = x; cx < x + width; cx++) {
                    long odx = Math.abs(cx - ox) + 1L, odz = Math.abs(cz - oz) + 1L;
                    long ndx = Math.abs(cx - nx) + 1L, ndz = Math.abs(cz - nz) + 1L;
                    changed |= (odx * odx + odz * odz < (long) radius * radius)
                            != (ndx * ndx + ndz * ndz < (long) radius * radius);
                }
                if (changed) assertTrue(window.affects(x, z, x + width, z + depth));
            }
        }
    }
}
