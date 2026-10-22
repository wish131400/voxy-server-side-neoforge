package dev.xantha.vss.api.column;

import java.util.Set;

/**
 * Server-safe capability discovery. Queries never freeze filter registration or
 * load a client, world, cache or Voxy class. This version is independent of the
 * network protocol and the provider version used for cache identities.
 */
public final class ColumnFilterApi {
    public static final int VERSION = 2;
    public static final String SECTION_DECISIONS = "section-decisions";
    public static final String SECTION_LIGHT_MODES = "section-light-modes";
    public static final String SECTION_MAX_BLOCK_Y = "section-max-block-y";
    public static final String COLUMN_TOP_SOLID_Y = "column-top-solid-y";
    public static final String COLUMN_COVERAGE_QUERY = "column-coverage-query";

    private static final Set<String> CAPABILITIES = Set.of(
            SECTION_DECISIONS, SECTION_LIGHT_MODES, SECTION_MAX_BLOCK_Y, COLUMN_TOP_SOLID_Y, COLUMN_COVERAGE_QUERY);

    private ColumnFilterApi() { }

    /** Use this method rather than an inlined constant when probing at runtime. */
    public static int version() { return VERSION; }

    /** Immutable names; unknown or null capabilities are unsupported. */
    public static Set<String> capabilities() { return CAPABILITIES; }

    public static boolean supports(String capability) {
        return capability != null && CAPABILITIES.contains(capability);
    }
}
