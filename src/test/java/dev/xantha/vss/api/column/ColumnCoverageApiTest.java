package dev.xantha.vss.api.column;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ColumnCoverageApiTest {
    @Test void discoveryIsServerSafeAndDoesNotFreezeFilters() throws Exception {
        assertFalse(ColumnFilters.isRegistrationFrozen());
        Class<?> api = Class.forName("dev.xantha.vss.api.column.ColumnCoverageApi");
        assertEquals(1, api.getMethod("version").invoke(null));
        assertTrue(ColumnCoverageApi.get().isEmpty());
        assertFalse(ColumnFilters.isRegistrationFrozen());
    }

    @Test void snapshotsAreDetachedAndUnknownNeverMeansWritable() {
        Set<Integer> selected = new HashSet<>(Set.of(-4, 2));
        var snapshot = new ColumnCoverage.Snapshot(7, false, ColumnCoverage.State.VSS_UNTOUCHED, selected);
        selected.clear();
        assertEquals(ColumnCoverage.State.VSS_OWNED, snapshot.section(-4));
        assertEquals(ColumnCoverage.State.VSS_UNTOUCHED, snapshot.section(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ownedSections().clear());
        assertEquals(ColumnCoverage.State.UNKNOWN, ColumnCoverage.Snapshot.UNKNOWN.section(0));
        assertFalse(ColumnCoverage.Snapshot.UNKNOWN.authoritative());
        assertEquals(0, ColumnCoverage.Snapshot.UNKNOWN.revision());
        assertThrows(IllegalArgumentException.class,
                () -> new ColumnCoverage.Snapshot(0, true, ColumnCoverage.State.VSS_OWNED, Set.of()));
    }

    @Test void anOldRuntimeCannotUnbindItsReplacement() {
        ColumnCoverage first = (dimension, pos) -> ColumnCoverage.Snapshot.UNKNOWN;
        ColumnCoverage second = (dimension, pos) -> ColumnCoverage.Snapshot.UNMANAGED;
        try {
            ColumnCoverageApi.bind(first);
            ColumnCoverageApi.bind(second);
            ColumnCoverageApi.unbind(first);
            assertSame(second, ColumnCoverageApi.get().orElseThrow());
        } finally { ColumnCoverageApi.unbind(second); }
        assertTrue(ColumnCoverageApi.get().isEmpty());
    }
}
