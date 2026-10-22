package dev.xantha.vss.api.column;

import java.util.Objects;
import java.util.Optional;

/**
 * Server-safe discovery, independent of filter registration and protocol version.
 * Empty before server startup, after shutdown, and on a remote multiplayer client.
 * Empty means unavailable, not permission to overwrite another source's data.
 */
public final class ColumnCoverageApi {
    public static final int VERSION = 1;
    private static volatile ColumnCoverage coverage;

    private ColumnCoverageApi() { }

    public static int version() { return VERSION; }
    public static Optional<ColumnCoverage> get() { return Optional.ofNullable(coverage); }

    /** Runtime binding only. Integrations must query get(), not install a provider. */
    public static synchronized void bind(ColumnCoverage view) { coverage = Objects.requireNonNull(view, "view"); }

    /** Only the runtime which installed this view may remove it. */
    public static synchronized void unbind(ColumnCoverage view) {
        if (coverage == view) coverage = null;
    }
}
