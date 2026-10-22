package dev.xantha.vss.api.column;

import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Read-only, server-side provenance of committed VSS column results. Queries are
 * safe on workers: they neither load chunks nor inspect a world or wait for I/O.
 * This is not a client Voxy write acknowledgement, a last-writer tracker, or a
 * write lease. Unknown and untouched sections grant no permission to write.
 */
public interface ColumnCoverage {
    enum State {
        /** An explicit UPDATE/CLEAR, or part of an unfiltered complete column. */
        VSS_OWNED,
        /** Omitted by the current filter; existing client data may still exist. */
        VSS_UNTOUCHED,
        /** Reserved for an explicit no-management contract; never inferred from missing data or disable. */
        UNMANAGED,
        /** No current result: pending, invalidated, evicted, stopped, or unsupported. */
        UNKNOWN
    }

    /** Atomic immutable view. Null arguments return UNKNOWN, never a writable answer. */
    Snapshot snapshot(ResourceKey<Level> dimension, ChunkPos chunkPos);

    /** sectionY is a signed section coordinate (floorDiv(blockY, 16)). */
    default State section(ResourceKey<Level> dimension, ChunkPos chunkPos, int sectionY) {
        return snapshot(dimension, chunkPos).section(sectionY);
    }

    /** A complete unfiltered real column; independent of predictive worldgen support. */
    default boolean authoritative(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        return snapshot(dimension, chunkPos).authoritative();
    }

    /**
     * Positive process-local cache token, not a timestamp. Compare for equality;
     * zero means no known result. Never persist across JVMs. Eviction/invalidation
     * returns zero; republishing allocates a larger token, including after restart.
     */
    default long revision(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        return snapshot(dimension, chunkPos).revision();
    }

    /**
     * The revision and all states belong to one publication. The explicit set is
     * copied on construction and immutable; a cached snapshot must be revalidated
     * before use. A positive revision does not grant exclusive write ownership.
     */
    record Snapshot(long revision, boolean authoritative, State defaultState, Set<Integer> ownedSections) {
        public static final Snapshot UNKNOWN = new Snapshot(0, false, State.UNKNOWN, Set.of());
        public static final Snapshot UNMANAGED = new Snapshot(0, false, State.UNMANAGED, Set.of());

        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("Negative coverage revision");
            Objects.requireNonNull(defaultState, "defaultState");
            ownedSections = Set.copyOf(ownedSections);
            if (revision == 0 && (authoritative || !ownedSections.isEmpty()
                    || defaultState == State.VSS_OWNED || defaultState == State.VSS_UNTOUCHED))
                throw new IllegalArgumentException("Known coverage requires a positive revision");
            if (authoritative && defaultState != State.VSS_OWNED)
                throw new IllegalArgumentException("Authoritative coverage must own the full column");
        }

        public State section(int sectionY) {
            return ownedSections.contains(sectionY) ? State.VSS_OWNED : defaultState;
        }
    }
}
