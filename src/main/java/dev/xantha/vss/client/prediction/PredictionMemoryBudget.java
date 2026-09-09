package dev.xantha.vss.client.prediction;

import java.util.function.LongSupplier;

/** JVM-pressure admission for production; fixed allowances remain available for deterministic tests. */
final class PredictionMemoryBudget {
    static final long MIB = 1024L * 1024L;
    static final long BUILD_BYTES = 128L * MIB;
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
    private int activeBuilders;
    private long used;
    private long pausedUntil;
    private boolean paused;

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
                () -> collectors.stream().mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum());
    }

    static PredictionMemoryBudget adaptive(long maxHeap, LongSupplier freeHeap, LongSupplier clock,
                                            int maxBuilders, LongSupplier collections) {
        // This is free space for Minecraft/other mods/GC, not a quota for VSS.
        long safety = Math.max(128L * MIB, Math.min(1024L * MIB, maxHeap / 10));
        return new PredictionMemoryBudget(maxHeap, safety, freeHeap, clock, maxBuilders, true, collections);
    }

    static int workerCount(int logicalProcessors) {
        return Math.max(1, logicalProcessors - 2);
    }

    synchronized Reservation tryReserveBuild() {
        if (activeBuilders >= maxBuilders) return null;
        Reservation reservation = tryReserve(BUILD_BYTES);
        if (reservation != null) {
            reservation.building = true;
            activeBuilders++;
        }
        return reservation;
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

    private long availableBytes() {
        long headroom = Math.max(0, freeHeap.getAsLong() - minimumFree - (adaptive ? workingBytes : 0));
        return adaptive ? headroom : Math.min(limit - used, headroom);
    }

    synchronized int availableBuildSlots() {
        return paused() ? 0 : (int) Math.min(maxBuilders - activeBuilders, availableBytes() / BUILD_BYTES);
    }

    synchronized long reclaimTargetBytes() {
        // Running jobs may release their workspaces shortly; do not evict
        // cached terrain just because all build slots are currently reserved.
        if (paused() || adaptive && activeBuilders != 0) return 0;
        long shortage = Math.max(0, BUILD_BYTES - availableBytes());
        if (!adaptive) return shortage;
        refreshCollections();
        return Math.max(0, shortage - awaitingCollectionBytes);
    }

    private void refreshCollections() {
        long count = collections.getAsLong();
        if (count != observedCollections) {
            observedCollections = count;
            awaitingCollectionBytes = 0;
        }
    }

    synchronized String diagnostics() {
        return "mode=" + (adaptive ? "adaptive" : "fixed") + ",accountedMiB=" + used / MIB
                + ",heapMaxMiB=" + limit / MIB + ",heapFreeMiB=" + freeHeap.getAsLong() / MIB
                + ",safetyMiB=" + minimumFree / MIB + ",workspaceMiB=" + workingBytes / MIB
                + ",workerLimit=" + maxBuilders + ",availableBuildSlots=" + availableBuildSlots();
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

        private Reservation(long bytes) {
            this.bytes = bytes;
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

        @Override
        public void close() {
            synchronized (PredictionMemoryBudget.this) {
                if (!closed) {
                    if (working) workingBytes -= bytes;
                    else if (adaptive) {
                        refreshCollections();
                        awaitingCollectionBytes += bytes;
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

    static final class MeshLimitException extends RuntimeException {
        MeshLimitException() {
            super("prediction tile exceeds the bounded mesh workspace", null, false, false);
        }
    }
}
