package dev.xantha.vss.api.column;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dedicated-server-safe registration entry point. Register during mod setup, before
 * any world/cache use. The first snapshot freezes registrations for this process.
 * Changing a rule/configuration requires a new version and a server restart; a rule
 * must be deterministic for its input and declared version. No client API is loaded.
 */
public final class ColumnFilters {
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static volatile Snapshot frozen;

    private ColumnFilters() { }

    /** Observes registration state without freezing it; register still checks atomically. */
    public static boolean isRegistrationFrozen() { return frozen != null; }

    public static synchronized void register(String id, int version, ColumnFilter filter) {
        if (frozen != null) throw new IllegalStateException("Column filters are frozen; register during mod setup");
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || version < 1) {
            throw new IllegalArgumentException("Expected a namespaced column filter ID and positive version");
        }
        Objects.requireNonNull(filter, "filter");
        if (ENTRIES.containsKey(id)) throw new IllegalArgumentException("Duplicate column filter: " + id);
        ENTRIES.put(id, new Entry(id, version, filter));
    }

    public static Snapshot snapshot() {
        Snapshot current = frozen;
        if (current != null) return current;
        synchronized (ColumnFilters.class) {
            if (frozen == null) {
                List<Entry> entries = ENTRIES.values().stream().sorted(Comparator.comparing(Entry::id)).toList();
                frozen = new Snapshot(entries, identity(entries));
            }
            return frozen;
        }
    }

    private static String identity(List<Entry> entries) {
        if (entries.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("vss-column-filter-api-1\n".getBytes(StandardCharsets.UTF_8));
            for (Entry entry : entries) {
                digest.update((entry.id() + "=" + entry.version() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Entry(String id, int version, ColumnFilter filter) { }

    public static final class Snapshot {
        private final List<Entry> entries;
        private final String cacheKey;
        private Snapshot(List<Entry> entries, String cacheKey) { this.entries = entries; this.cacheKey = cacheKey; }
        public String cacheKey() { return cacheKey; }
        public boolean isEmpty() { return entries.isEmpty(); }

        /** One invocation per registered provider, never one invocation per section. */
        public ColumnDecision decide(ColumnInput input) {
            ArrayList<ColumnDecision> decisions = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                try {
                    ColumnDecision decision = Objects.requireNonNull(entry.filter().filter(input), "column decision");
                    for (int y : decision.sections().keySet()) {
                        if (y < input.minSectionY() || y >= input.maxSectionY()) {
                            throw new IllegalArgumentException("Section Y outside dimension: " + y);
                        }
                    }
                    decisions.add(decision);
                } catch (RuntimeException | LinkageError failure) {
                    throw new IllegalStateException("Column filter " + entry.id() + " failed", failure);
                }
            }
            Map<Integer, SectionDecision> combined = new LinkedHashMap<>();
            for (int y = input.minSectionY(); y < input.maxSectionY(); y++) {
                SectionDecision decision = SectionDecision.UPDATE;
                for (ColumnDecision next : decisions) decision = decision.intersect(next.section(y));
                combined.put(y, decision);
            }
            return new ColumnDecision(SectionDecision.UNTOUCHED, combined);
        }
    }
}
