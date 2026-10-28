package dev.xantha.vss.client.prediction;

import java.util.function.LongSupplier;

/** JVM-pressure admission for production; fixed allowances remain available for deterministic tests. */
final class PredictionMemoryBudget {
    static final long MIB = 1024L * 1024L;
    static final long BUILD_BYTES = 192L * MIB;
    static final long RETAINED_MESH_BYTES = 64L * MIB;
    static final PredictionMemoryBudget SHARED = runtimeBudget();
    private final long limit;
    private final long minimumFree;
    private final LongSupplier freeHeap;
    private final LongSupplier clock;
    private final int maxBuilders;
    private final boolean adaptive;
    private final LongSupplier collections;
    private long observedCollections;
    /** Promised workspaces are not necessarily visible in Runtime's heap usage yet. */
    private long workingBytes;
    /** Dropped cache references must reach GC before asking caches to drop more. */
    private long awaitingCollectionBytes;
    private long unobservedReleaseBytes;
    private int activeBuilders;
    private long used;
    private long pausedUntil;
    private boolean paused;
    private boolean detailPressure;
    private long detailPressureSince;
    private static final long DETAIL_PRESSURE_GRACE_NANOS = 5_000_000_000L;
    enum BuildKind { TERRAIN, SURFACE, RESTORE }
    enum BuildOutcome { COMPLETED, CANCELLED, FAILED }
    private final BuildCost[] costs = java.util.Arrays.stream(BuildKind.values()).map(k -> new BuildCost()).toArray(BuildCost[]::new);
    private static final class BuildCost {
        long completed, cancelled, failed, elapsedNanos, maxNanos, retainedHigh, uploadHigh;
    }
    record CostSnapshot(long completed, long cancelled, long failed, long elapsedNanos, long maxNanos,
                        long retainedHigh, long uploadHigh, long reservationBytes) { }

    PredictionMemoryBudget(long limit, long minimumFree, LongSupplier freeHeap, LongSupplier clock) {
        this(limit, minimumFree, freeHeap, clock, Integer.MAX_VALUE);
    }

    PredictionMemoryBudget(long limit, long minimumFree, LongSupplier freeHeap, LongSupplier clock,
                           int maxBuilders) {
        this(limit, minimumFree, freeHeap, clock, maxBuilders, false, () -> 0);
    }

    private PredictionMemoryBudget(long limit, long minimumFree, LongSupplier freeHeap, LongSupplier clock,
                                   int maxBuilders, boolean adaptive, LongSupplier collections) {
        this.limit = limit;
        this.minimumFree = minimumFree;
        this.freeHeap = freeHeap;
        this.clock = clock;
        this.maxBuilders = Math.max(1, maxBuilders);
        this.adaptive = adaptive;
        this.collections = collections;
        this.observedCollections = collections.getAsLong();
    }

    private static PredictionMemoryBudget runtimeBudget() {
        Runtime runtime = Runtime.getRuntime();
        var collectors = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        return adaptive(runtime.maxMemory(),
                () -> runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory(),
                System::nanoTime, workerCount(runtime.availableProcessors()),
                () -> {
                    long count = 0;
                    for (var collector : collectors) count += Math.max(0, collector.getCollectionCount());
                    return count;
                });
    }

    static PredictionMemoryBudget adaptive(long maxHeap, LongSupplier freeHeap, LongSupplier clock,
                                            int maxBuilders, LongSupplier collections) {
        // This is free space for Minecraft/other mods/GC, not a quota for VSS.
        long safety = Math.max(128L * MIB, Math.min(1024L * MIB, maxHeap / 10));
        return new PredictionMemoryBudget(maxHeap, safety, freeHeap, clock, maxBuilders, true, collections);
    }

    static int workerCount(int logicalProcessors) {
        return PredictionPerformanceProfile.current().poolSize(logicalProcessors);
    }

    synchronized Reservation tryReserveBuild() {
        return tryReserveBuild(BuildKind.TERRAIN);
    }

    synchronized Reservation tryReserveBuild(BuildKind kind) {
        if (activeBuilders >= maxBuilders) return null;
        Reservation reservation = tryReserve(buildBytes(kind));
        if (reservation != null) {
            reservation.building = true;
            reservation.kind = kind;
            reservation.started = clock.getAsLong();
            activeBuilders++;
        }
        return reservation;
    }

    /** Cache restore uses the same byte allowance without occupying a generation slot. */
    synchronized Reservation tryReserveRestore() {
        var reservation = tryReserve(buildBytes(BuildKind.RESTORE));
        if (reservation != null) { reservation.kind = BuildKind.RESTORE; reservation.started = clock.getAsLong(); }
        return reservation;
    }

    private long buildBytes(BuildKind kind) {
        // Retained output is a measured lower bound, not an allocation/peak-heap
        // estimate. It may raise admission, but never lower the proven floor.
        long retained = costs[kind.ordinal()].retainedHigh;
        return Math.max(BUILD_BYTES, retained > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : retained * 2);
    }

    synchronized CostSnapshot cost(BuildKind kind) {
        var c = costs[kind.ordinal()];
        return new CostSnapshot(c.completed, c.cancelled, c.failed, c.elapsedNanos, c.maxNanos,
                c.retainedHigh, c.uploadHigh, buildBytes(kind));
    }

    synchronized Reservation tryReserve(long bytes) {
        if (bytes < 0 || paused() || bytes > availableBytes()) {
            return null;
        }
        used += bytes;
        workingBytes += bytes;
        return new Reservation(bytes);
    }

    synchronized boolean exhausted() {
        return paused() || BUILD_BYTES > availableBytes();
    }

    synchronized boolean exhausted(BuildKind kind) {
        return paused() || buildBytes(kind) > availableBytes();
    }

    synchronized boolean allowsIdleRefinement() {
        // Optional quality must leave room for normal loading, even with a
        // single-worker pool. This allowance is in addition to the GC reserve.
        return !paused() && availableBytes() >= BUILD_BYTES + 256L * MIB;
    }

    private long availableBytes() {
        long headroom = Math.max(0, freeHeap.getAsLong() - minimumFree - (adaptive ? workingBytes : 0));
        if (adaptive && headroom >= BUILD_BYTES) detailPressure = false;
        return adaptive ? headroom : Math.min(limit - used, headroom);
    }

    synchronized int availableBuildSlots() {
        return paused() ? 0 : (int) Math.min(maxBuilders - activeBuilders, availableBytes() / BUILD_BYTES);
    }

    synchronized long reclaimTargetBytes() {
        // Running jobs may release their workspaces shortly; do not evict
        // cached terrain just because all build slots are currently reserved.
        if (paused() || adaptive && activeBuilders != 0) return 0;
        // Preserve the safety deficit too. Clamping availableBytes to zero
        // understated severe pressure as just one workspace, encouraging
        // repeated visible-detail eviction that could never admit a build.
        long shortage = adaptive ? Math.max(0, minimumFree + BUILD_BYTES - freeHeap.getAsLong())
                : Math.max(0, BUILD_BYTES - availableBytes());
        if (!adaptive) return shortage;
        if (shortage == 0) detailPressure = false;
        else if (!detailPressure) {
            detailPressure = true;
            detailPressureSince = clock.getAsLong();
        }
        refreshCollections();
        return Math.max(0, shortage - awaitingCollectionBytes);
    }

    /** A visible downgrade must be sustained and capable of funding its replacement. */
    synchronized boolean canReclaimDetail(long reclaimableBytes) {
        long target = reclaimTargetBytes();
        return target > 0 && (!adaptive || detailPressure
                && clock.getAsLong() - detailPressureSince >= DETAIL_PRESSURE_GRACE_NANOS
                && reclaimableBytes >= target);
    }

    boolean hasFixedAllowance() { return !adaptive; }

    private void refreshCollections() {
        long count = collections.getAsLong();
        if (count != observedCollections) {
            observedCollections = count;
            awaitingCollectionBytes = 0;
        }
        // Releases since our last observation may have happened after that GC.
        // Keep their credit until a later observation rather than evicting them twice.
        awaitingCollectionBytes += unobservedReleaseBytes;
        unobservedReleaseBytes = 0;
    }

    synchronized String diagnostics() {
        return "mode=" + (adaptive ? "adaptive" : "fixed") + ",accountedMiB=" + used / MIB
                + ",heapMaxMiB=" + limit / MIB + ",heapFreeMiB=" + freeHeap.getAsLong() / MIB
                + ",safetyMiB=" + minimumFree / MIB + ",workspaceMiB=" + workingBytes / MIB
                + ",workerLimit=" + maxBuilders + ",availableBuildSlots=" + availableBuildSlots();
    }

    synchronized String costDiagnostics() {
        var out = new StringBuilder("buildCosts={");
        for (var kind : BuildKind.values()) {
            if (kind.ordinal() != 0) out.append(';');
            var c = costs[kind.ordinal()];
            out.append(kind).append(":ok=").append(c.completed).append(",cancel=").append(c.cancelled)
                    .append(",fail=").append(c.failed).append(",meanUs=")
                    .append(c.completed == 0 ? 0 : c.elapsedNanos / c.completed / 1000)
                    .append(",maxUs=").append(c.maxNanos / 1000).append(",retainedHigh=").append(c.retainedHigh)
                    .append(",uploadHigh=").append(c.uploadHigh).append(",reserve=").append(buildBytes(kind));
        }
        return out.append('}').toString();
    }

    synchronized int activeBuildCount() {
        return activeBuilders;
    }

    int buildLimit() {
        return maxBuilders;
    }

    synchronized long usedBytes() {
        return used;
    }

    long limitBytes() {
        return limit;
    }

    synchronized boolean pauseAfterOutOfMemory() {
        boolean first = !paused();
        long now = clock.getAsLong();
        pausedUntil = now + 30_000_000_000L;
        paused = true;
        return first;
    }

    private boolean paused() {
        if (paused && clock.getAsLong() - pausedUntil >= 0L) paused = false;
        return paused;
    }

    final class Reservation implements AutoCloseable {
        private long bytes;
        private boolean closed;
        private boolean building;
        private boolean working = true;
        private BuildKind kind;
        private long started;
        private boolean costFinished;

        private Reservation(long bytes) {
            this.bytes = bytes;
        }

        /** No world/system queries, only counters; publication may then transfer this lease. */
        void completeBuild(long retainedBytes, long uploadBytes, boolean restored) {
            synchronized (PredictionMemoryBudget.this) {
                if (closed || costFinished || kind == null) return;
                if (restored) kind = BuildKind.RESTORE;
                var c = costs[kind.ordinal()];
                long elapsed = Math.max(0, clock.getAsLong() - started);
                c.completed++; c.elapsedNanos += elapsed; c.maxNanos = Math.max(c.maxNanos, elapsed);
                c.retainedHigh = Math.max(c.retainedHigh, Math.max(0, retainedBytes));
                c.uploadHigh = Math.max(c.uploadHigh, Math.max(0, uploadBytes));
                costFinished = true;
            }
        }

        void finishBuild(BuildOutcome outcome) {
            synchronized (PredictionMemoryBudget.this) {
                if (costFinished || kind == null) return;
                if (outcome == BuildOutcome.COMPLETED) throw new IllegalArgumentException("completed build needs byte counts");
                var c = costs[kind.ordinal()];
                if (outcome == BuildOutcome.FAILED) c.failed++; else c.cancelled++;
                costFinished = true;
            }
        }

        boolean retain(long residentBytes) {
            synchronized (PredictionMemoryBudget.this) {
                if (closed || residentBytes < 0 || (adaptive
                        ? residentBytes - bytes > availableBytes()
                        : residentBytes > limit - used + bytes)) {
                    return false;
                }
                if (working) {
                    workingBytes -= bytes;
                    working = false;
                }
                used += residentBytes - bytes;
                bytes = residentBytes;
                releaseBuildSlot();
                return true;
            }
        }

        /** Published metadata can enter/leave cold storage without creating a new tile. */
        void accountResident(long residentBytes) {
            synchronized (PredictionMemoryBudget.this) {
                if (closed || working || residentBytes < 0) return;
                used += residentBytes - bytes;
                bytes = residentBytes;
            }
        }

        @Override
        public void close() {
            synchronized (PredictionMemoryBudget.this) {
                if (!closed) {
                    finishBuild(BuildOutcome.CANCELLED);
                    if (working) workingBytes -= bytes;
                    else if (adaptive) {
                        // The reclaim decision refreshes GC state once for the batch.
                        // Closing each cached column/section only updates accounting.
                        unobservedReleaseBytes += bytes;
                    }
                    used -= bytes;
                    releaseBuildSlot();
                    closed = true;
                }
            }
        }

        private void releaseBuildSlot() {
            if (building) {
                activeBuilders--;
                building = false;
            }
        }
    }

}
