package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.function.LongSupplier;

/** Render-thread soft VRAM cache. Visibility means the CPU frustum, never a
 * transient Hi-Z result. A drawable coarse replacement is mandatory. */
final class PredictionGpuResidency<K> {
    static final long MIB = 1024L * 1024L;
    static final long SWEEP_NANOS = 500_000_000L;
    static final long OUTSIDE_GRACE_NANOS = 10_000_000_000L;
    static final long OWNED_GRACE_NANOS = 2_000_000_000L;
    private static final long RETIRE_BYTES = 32L * MIB;
    private static final int RETIRE_TILES = 8;
    enum Reason { BUDGET, OWNERSHIP }
    private static final class Entry {
        long revision, bytes, lastUsed, ownedSince;
        boolean visible, protectedTile, fallback, owned, ownershipStarted;
    }
    private record Cold(long revision, Reason reason) { }
    record Retirement<K>(K key, Reason reason) { }
    private final Map<K, Entry> resident = new HashMap<>();
    private final Map<K, Cold> cold = new HashMap<>();
    private final LongSupplier clock;
    private final long budget, target;
    private long bytes, nextSweep, budgetEvictions, ownedEvictions, releasedBytes, restores;
    private long opaqueBytes, waterBytes, previousBytes, quads, rectangles, waterRectangles;
    private long committed;

    PredictionGpuResidency(long budget, LongSupplier clock) {
        this.budget = Math.max(1, budget); this.target = this.budget - this.budget / 10; this.clock = clock;
    }
    static <K> PredictionGpuResidency<K> runtime() {
        int mib = Math.max(128, Math.min(4096, Integer.getInteger("vss.predictionGpuBudgetMiB", 1024)));
        return new PredictionGpuResidency<>(mib * MIB, System::nanoTime);
    }
    boolean sweepDue() {
        long now = clock.getAsLong();
        if (now < nextSweep) return false;
        nextSweep = now + SWEEP_NANOS; return true;
    }
    void uploaded(K key, long revision, long size) {
        Entry old = resident.get(key);
        if (old != null) bytes -= old.bytes;
        Entry entry = new Entry(); entry.revision = revision; entry.bytes = Math.max(0, size);
        entry.lastUsed = clock.getAsLong(); resident.put(key, entry); bytes += entry.bytes;
    }
    void observe(K key, long size, boolean visible, boolean protectedTile, boolean fallback, boolean owned) {
        Entry entry = resident.get(key); if (entry == null) return;
        bytes += size - entry.bytes; entry.bytes = size;
        entry.visible = visible; entry.protectedTile = protectedTile; entry.fallback = fallback; entry.owned = owned;
        long now = clock.getAsLong();
        if (visible) entry.lastUsed = now;
        if (owned && !entry.ownershipStarted) { entry.ownershipStarted = true; entry.ownedSince = now; }
        if (!owned) entry.ownershipStarted = false;
    }
    void used(K key) { Entry entry = resident.get(key); if (entry != null) entry.lastUsed = clock.getAsLong(); }

    List<Retirement<K>> retirements(long committedBytes) {
        committed = committedBytes;
        long now = clock.getAsLong();
        boolean pressure = Math.max(bytes, committed) > budget && bytes > target;
        List<Map.Entry<K, Entry>> candidates = new ArrayList<>();
        for (var entry : resident.entrySet()) {
            Entry e = entry.getValue();
            if (e.protectedTile || !e.fallback) continue;
            boolean owned = e.owned && now - e.ownedSince >= OWNED_GRACE_NANOS;
            if (owned || pressure && !e.visible && now - e.lastUsed >= OUTSIDE_GRACE_NANOS) candidates.add(entry);
        }
        candidates.sort(Comparator.<Map.Entry<K, Entry>>comparingInt(e -> e.getValue().owned ? 0 : 1)
                .thenComparingLong(e -> e.getValue().lastUsed).thenComparingLong(e -> -e.getValue().bytes));
        List<Retirement<K>> result = new ArrayList<>(); long released = 0;
        for (var entry : candidates) {
            Entry e = entry.getValue();
            if (!e.owned && bytes - released <= target) continue;
            if (!result.isEmpty() && (result.size() >= RETIRE_TILES || released + e.bytes > RETIRE_BYTES)) break;
            result.add(new Retirement<>(entry.getKey(), e.owned ? Reason.OWNERSHIP : Reason.BUDGET));
            released += e.bytes;
        }
        return result;
    }

    void evicted(Retirement<K> retirement) {
        Entry entry = resident.remove(retirement.key()); if (entry == null) return;
        bytes -= entry.bytes; releasedBytes += entry.bytes;
        cold.put(retirement.key(), new Cold(entry.revision, retirement.reason()));
        if (retirement.reason() == Reason.OWNERSHIP) ownedEvictions++; else budgetEvictions++;
    }
    boolean allowsUpload(K key, long revision, boolean visible, boolean owned) {
        Cold entry = cold.get(key); if (entry == null) return true;
        boolean wanted = entry.revision() != revision || (entry.reason() == Reason.OWNERSHIP ? !owned : visible);
        if (wanted) { cold.remove(key); restores++; }
        return wanted;
    }
    void removed(K key) { Entry entry = resident.remove(key); if (entry != null) bytes -= entry.bytes; cold.remove(key); }
    void retainCold(java.util.function.Predicate<K> exists) { cold.keySet().removeIf(key -> !exists.test(key)); }
    void census(long opaque, long water, long previous, long quads, long rectangles, long waterRectangles) {
        opaqueBytes = opaque; waterBytes = water; previousBytes = previous;
        this.quads = quads; this.rectangles = rectangles; this.waterRectangles = waterRectangles;
    }
    long residentBytes() { return bytes; }
    int coldCount() { return cold.size(); }
    boolean cold(K key) { return cold.containsKey(key); }
    String diagnostics() {
        return "budgetBytes=" + budget + ",targetBytes=" + target + ",liveBytes=" + bytes + ",committedGeometryBytes=" + committed
                + ",budgetEvictions=" + budgetEvictions + ",ownedEvictions=" + ownedEvictions
                + ",releasedBytes=" + releasedBytes + ",coldTiles=" + cold.size() + ",restoreRequests=" + restores
                + ",opaquePayloadBytes=" + opaqueBytes + ",waterPayloadBytes=" + waterBytes
                + ",waterDuplicationSavedBytes=" + (previousBytes - opaqueBytes - waterBytes)
                + ",quads=" + quads + ",rectangles=" + rectangles + ",waterRectangles=" + waterRectangles
                + ",coordinateSavingUpperBoundBytes=" + rectangles * 12;
    }
    void clear() {
        resident.clear(); cold.clear(); bytes = nextSweep = committed = 0;
        budgetEvictions = ownedEvictions = releasedBytes = restores = 0;
        opaqueBytes = waterBytes = previousBytes = quads = rectangles = waterRectangles = 0;
    }
}
