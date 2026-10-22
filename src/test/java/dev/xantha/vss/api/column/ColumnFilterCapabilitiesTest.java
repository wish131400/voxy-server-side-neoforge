package dev.xantha.vss.api.column;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ColumnFilterCapabilitiesTest {
    @Test void reflectiveDiscoveryDoesNotFreezeRegistrationOrClaimSourcePriority() throws Exception {
        assertFalse(ColumnFilters.isRegistrationFrozen());
        // Optional integrations may probe by name before linking to a newer API.
        Class<?> api = Class.forName("dev.xantha.vss.api.column.ColumnFilterApi", false, getClass().getClassLoader());
        assertEquals(2, api.getMethod("version").invoke(null));
        assertEquals(Boolean.TRUE, api.getMethod("supports", String.class).invoke(null, "column-top-solid-y"));
        assertTrue(ColumnFilterApi.supports(ColumnFilterApi.SECTION_MAX_BLOCK_Y));
        assertTrue(ColumnFilterApi.supports(ColumnFilterApi.SECTION_DECISIONS));
        assertTrue(ColumnFilterApi.supports(ColumnFilterApi.SECTION_LIGHT_MODES));
        assertFalse(ColumnFilterApi.supports("source-priority"));
        assertFalse(ColumnFilterApi.supports("future-feature"));
        assertFalse(ColumnFilterApi.supports(null));
        assertThrows(UnsupportedOperationException.class, () -> ColumnFilterApi.capabilities().clear());
        assertFalse(ColumnFilters.isRegistrationFrozen());
        ColumnFilters.register("test:discovery", 1, column -> ColumnDecision.updateAll());
        assertFalse(ColumnFilters.isRegistrationFrozen());
        assertFalse(ColumnFilters.snapshot().isEmpty());
        assertTrue(ColumnFilters.isRegistrationFrozen());
        assertThrows(IllegalStateException.class, () -> ColumnFilters.register("test:late", 1, column -> null));
    }
}
