package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.HashCommon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded history of horizontal regions whose prediction handoff actually changed. */
public final class StrictVoxyCoverageChanges {
    private static final int MAX_AREAS = 4096, STAMP_BUCKETS = 65_536;
    private final int capacity;
    private final LinkedHashMap<Area, Long> areas = new LinkedHashMap<>(16, 0.75F, true);
    private final java.util.ArrayDeque<WindowChange> windows = new java.util.ArrayDeque<>();
    // Monotonic bucket stamps survive history eviction. Collisions only cause extra rechecks.
    private final long[][] columnVersions = new long[6][STAMP_BUCKETS];
    private final long[][] regionVersions = new long[6][STAMP_BUCKETS];
    private volatile long revision, resetRevision;
    private long discardedThrough;
    private long discardedWindowThrough;

    public StrictVoxyCoverageChanges() { this(MAX_AREAS); }

    StrictVoxyCoverageChanges(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Coverage history must have a positive capacity");
        this.capacity = capacity;
    }

    public long revision() { return revision; }
    public long resetRevision() { return resetRevision; }

    /** Six aligned ancestor lookups keep unrelated work out of the shared column cache. */
    public synchronized long columnRevision(int chunkX, int chunkZ) {
        long version = resetRevision;
        for (int lod = 0; lod < columnVersions.length; lod++)
            version = Math.max(version, columnVersions[lod][stampBucket(chunkX >> lod, chunkZ >> lod)]);
        return version;
    }

    /** Descendant stamps validate an aligned region without enumerating its columns. */
    public synchronized long regionRevision(int minX, int minZ, int maxX, int maxZ) {
        int lod = 32 - Integer.numberOfLeadingZeros((minX ^ maxX) | (minZ ^ maxZ));
        if (lod >= regionVersions.length) return revision;
        long version = resetRevision;
        for (int level = lod; level < regionVersions.length; level++)
            version = Math.max(version, rangeStamp(regionVersions[level], minX, minZ, maxX, maxZ, level));
        return version;
    }

    public void columnChanged(int chunkX, int chunkZ) {
        changed(new Area(chunkX, chunkZ, (long) chunkX + 1, (long) chunkZ + 1));
    }

    public void nodeChanged(long position) {
        int span = 2 << StrictVoxyNodeIndex.level(position);
        long x = (long) StrictVoxyNodeIndex.x(position) * span;
        long z = (long) StrictVoxyNodeIndex.z(position) * span;
        changed(new Area(x, z, x + span, z + span));
    }

    /** Moving a view changes its edge masks, not the uploaded column answers. */
    public synchronized void windowMoved(int oldX, int oldZ, int x, int z, int radius) {
        long next = revision + 1;
        windows.addLast(new WindowChange(next, new WindowMovement(oldX, oldZ, x, z, radius)));
        if (windows.size() > capacity) discardedWindowThrough = windows.removeFirst().revision();
        revision = next;
    }

    private synchronized void changed(Area area) {
        long next = revision + 1;
        areas.put(area, next);
        int lod = Long.numberOfTrailingZeros(area.maxX() - area.minX());
        columnVersions[lod][stampBucket(area.minX() >> lod, area.minZ() >> lod)] = next;
        for (int parent = lod; parent < regionVersions.length; parent++)
            stampRange(regionVersions[parent], area, parent, next);
        if (areas.size() > capacity) {
            var oldest = areas.entrySet().iterator();
            var entry = oldest.next();
            discardedThrough = Math.max(discardedThrough, entry.getValue());
            oldest.remove();
        }
        // Publish only after the history entry is complete.
        revision = next;
    }

    public synchronized void invalidateAll() {
        long next = revision + 1;
        areas.clear();
        windows.clear();
        for (var versions : columnVersions) Arrays.fill(versions, 0);
        for (var versions : regionVersions) Arrays.fill(versions, 0);
        discardedThrough = next;
        discardedWindowThrough = next;
        resetRevision = next;
        revision = next;
    }

    public synchronized Delta since(long previous) {
        if (previous == revision) return new Delta(revision, false, List.of());
        if (previous < discardedThrough || previous < discardedWindowThrough
                || previous < resetRevision || previous > revision)
            return new Delta(revision, true, List.of());
        var changed = new ArrayList<Area>();
        for (var entry : areas.entrySet()) if (entry.getValue() > previous) changed.add(entry.getKey());
        var moved = new ArrayList<WindowMovement>();
        for (var change : windows) if (change.revision() > previous) moved.add(change.movement());
        return new Delta(revision, false, List.copyOf(changed), List.copyOf(moved));
    }

    /** Chunk bounds, with exclusive maxima; a coarser Voxy node includes all its children. */
    public record Area(long minX, long minZ, long maxX, long maxZ) { }
    public record Delta(long revision, boolean reset, List<Area> areas, List<WindowMovement> windows) {
        public Delta(long revision, boolean reset, List<Area> areas) { this(revision, reset, areas, List.of()); }
    }
    private record WindowChange(long revision, WindowMovement movement) { }

    /** Conservative circular edge test in chunks; exclusive rectangle maxima. */
    public record WindowMovement(int oldX, int oldZ, int x, int z, int radius) {
        public boolean affects(long minX, long minZ, long maxX, long maxZ) {
            if (minX >= maxX || minZ >= maxZ) return false;
            int old = relation(oldX, oldZ, minX, minZ, maxX, maxZ);
            int next = relation(x, z, minX, minZ, maxX, maxZ);
            return old != next || old == 0;
        }
        // -1 outside, 1 wholly inside, 0 straddles the boundary.
        private int relation(int cx, int cz, long minX, long minZ, long maxX, long maxZ) {
            double nearX = distance(cx, minX, maxX - 1) + 1D;
            double nearZ = distance(cz, minZ, maxZ - 1) + 1D;
            double squared = (double) radius * radius;
            if (nearX * nearX + nearZ * nearZ >= squared) return -1;
            double farX = Math.max(Math.abs(minX - cx), Math.abs(maxX - 1 - cx)) + 1D;
            double farZ = Math.max(Math.abs(minZ - cz), Math.abs(maxZ - 1 - cz)) + 1D;
            return farX * farX + farZ * farZ < squared ? 1 : 0;
        }
        private static long distance(int center, long min, long max) {
            return center < min ? min - center : center > max ? center - max : 0;
        }
    }
    private static long pack(long x, long z) { return x << 32 | z & 0xFFFFFFFFL; }
    private static int stampBucket(long x, long z) { return (int) HashCommon.mix(pack(x, z)) & (STAMP_BUCKETS - 1); }

    private static void stampRange(long[] stamps, Area area, int level, long value) {
        long step = 1L << level;
        long minX = Math.floorDiv(area.minX(), step), maxX = Math.floorDiv(area.maxX() - 1L, step);
        long minZ = Math.floorDiv(area.minZ(), step), maxZ = Math.floorDiv(area.maxZ() - 1L, step);
        for (long x = minX; x <= maxX; x++) for (long z = minZ; z <= maxZ; z++)
            stamps[stampBucket(x, z)] = value;
    }

    private static long rangeStamp(long[] stamps, int minX, int minZ, int maxX, int maxZ, int level) {
        long step = 1L << level;
        long firstX = Math.floorDiv((long) minX, step), lastX = Math.floorDiv((long) maxX, step);
        long firstZ = Math.floorDiv((long) minZ, step), lastZ = Math.floorDiv((long) maxZ, step);
        long version = 0;
        for (long x = firstX; x <= lastX; x++) for (long z = firstZ; z <= lastZ; z++)
            version = Math.max(version, stamps[stampBucket(x, z)]);
        return version;
    }
}
