package dev.xantha.vss.networking.server.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.LongSupplier;

public final class PreloadRegionWindow {
    private static final long PRELOAD_REGION_RETAIN_NANOS = 3_000_000_000L;

    private final Queue<PlayerRequestState.PreloadRegion> regions = new ArrayDeque<>();
    private final Set<Long> queuedRegionKeys = new HashSet<>();
    private final Set<Long> coveredRegionKeys = new HashSet<>();
    private final Map<Long, Long> retainedRegionDeadlines = new HashMap<>();
    private final LongSupplier nanoClock;
    private Object dimension;
    private boolean initialized;
    private int centerRegionX;
    private int centerRegionZ;
    private int maxRegionRing;
    private int minRegionX;
    private int maxRegionX;
    private int minRegionZ;
    private int maxRegionZ;

    public PreloadRegionWindow() {
        this(System::nanoTime);
    }

    public PreloadRegionWindow(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public synchronized void reset(Object dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        maxRegionRing = Math.max(0, maxRegionRing);
        regions.clear();
        queuedRegionKeys.clear();
        coveredRegionKeys.clear();
        retainedRegionDeadlines.clear();
        setWindow(dimension, centerRegionX, centerRegionZ, maxRegionRing);
        for (int ring = 0; ring <= maxRegionRing; ring++) {
            addRegionRing(centerRegionX, centerRegionZ, ring);
        }
    }

    public synchronized void update(Object dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        maxRegionRing = Math.max(0, maxRegionRing);
        if (!initialized
                || this.dimension == null
                || !this.dimension.equals(dimension)
                || this.maxRegionRing != maxRegionRing) {
            reset(dimension, centerRegionX, centerRegionZ, maxRegionRing);
            return;
        }

        int oldMinRegionX = minRegionX;
        int oldMaxRegionX = maxRegionX;
        int oldMinRegionZ = minRegionZ;
        int oldMaxRegionZ = maxRegionZ;
        int newMinRegionX = centerRegionX - maxRegionRing;
        int newMaxRegionX = centerRegionX + maxRegionRing;
        int newMinRegionZ = centerRegionZ - maxRegionRing;
        int newMaxRegionZ = centerRegionZ + maxRegionRing;
        if (newMinRegionX == oldMinRegionX
                && newMaxRegionX == oldMaxRegionX
                && newMinRegionZ == oldMinRegionZ
                && newMaxRegionZ == oldMaxRegionZ) {
            expireCoveredRegions();
            return;
        }

        int overlapMinRegionX = Math.max(oldMinRegionX, newMinRegionX);
        int overlapMaxRegionX = Math.min(oldMaxRegionX, newMaxRegionX);
        int overlapMinRegionZ = Math.max(oldMinRegionZ, newMinRegionZ);
        int overlapMaxRegionZ = Math.min(oldMaxRegionZ, newMaxRegionZ);
        if (overlapMinRegionX > overlapMaxRegionX || overlapMinRegionZ > overlapMaxRegionZ) {
            reset(dimension, centerRegionX, centerRegionZ, maxRegionRing);
            return;
        }

        this.centerRegionX = centerRegionX;
        this.centerRegionZ = centerRegionZ;
        minRegionX = newMinRegionX;
        maxRegionX = newMaxRegionX;
        minRegionZ = newMinRegionZ;
        maxRegionZ = newMaxRegionZ;
        pruneQueuedRegionsToWindow();

        ArrayList<PlayerRequestState.PreloadRegion> enteredRegions = new ArrayList<>();
        collectRegionRectangle(enteredRegions, newMinRegionX, oldMinRegionX - 1, newMinRegionZ, newMaxRegionZ);
        collectRegionRectangle(enteredRegions, oldMaxRegionX + 1, newMaxRegionX, newMinRegionZ, newMaxRegionZ);
        collectRegionRectangle(enteredRegions, overlapMinRegionX, overlapMaxRegionX, newMinRegionZ, oldMinRegionZ - 1);
        collectRegionRectangle(enteredRegions, overlapMinRegionX, overlapMaxRegionX, oldMaxRegionZ + 1, newMaxRegionZ);
        addRegionsOrdered(enteredRegions, centerRegionX, centerRegionZ);
        reorderRegions(centerRegionX, centerRegionZ);
    }

    public synchronized PlayerRequestState.PreloadRegion poll() {
        PlayerRequestState.PreloadRegion region = regions.poll();
        if (region != null) {
            long key = regionKey(region.regionX(), region.regionZ());
            queuedRegionKeys.remove(key);
            coveredRegionKeys.add(key);
            retainedRegionDeadlines.remove(key);
        }
        return region;
    }

    public synchronized int count() {
        return regions.size();
    }

    public synchronized void clear() {
        regions.clear();
        queuedRegionKeys.clear();
        coveredRegionKeys.clear();
        retainedRegionDeadlines.clear();
        dimension = null;
        initialized = false;
    }

    private void addRegionRing(int centerRegionX, int centerRegionZ, int ring) {
        if (ring == 0) {
            addRegion(centerRegionX, centerRegionZ);
            return;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            addRegion(centerRegionX + dx, centerRegionZ - ring);
        }
        for (int dz = -ring + 1; dz <= ring; dz++) {
            addRegion(centerRegionX + ring, centerRegionZ + dz);
        }
        for (int dx = ring - 1; dx >= -ring; dx--) {
            addRegion(centerRegionX + dx, centerRegionZ + ring);
        }
        for (int dz = ring - 1; dz >= -ring + 1; dz--) {
            addRegion(centerRegionX - ring, centerRegionZ + dz);
        }
    }

    private void addRegion(int regionX, int regionZ) {
        long key = regionKey(regionX, regionZ);
        if (!queuedRegionKeys.contains(key) && !coveredRegionKeys.contains(key)) {
            regions.add(new PlayerRequestState.PreloadRegion(regionX, regionZ));
            queuedRegionKeys.add(key);
        }
    }

    private void addRegionsOrdered(
            ArrayList<PlayerRequestState.PreloadRegion> regions,
            int centerRegionX,
            int centerRegionZ) {
        if (regions.isEmpty()) {
            return;
        }
        regions.sort(regionComparator(centerRegionX, centerRegionZ));
        for (PlayerRequestState.PreloadRegion region : regions) {
            addRegion(region.regionX(), region.regionZ());
        }
    }

    private void collectRegionRectangle(
            ArrayList<PlayerRequestState.PreloadRegion> output,
            int minRegionX,
            int maxRegionX,
            int minRegionZ,
            int maxRegionZ) {
        if (minRegionX > maxRegionX || minRegionZ > maxRegionZ) {
            return;
        }
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                long key = regionKey(regionX, regionZ);
                if (!queuedRegionKeys.contains(key) && !coveredRegionKeys.contains(key)) {
                    output.add(new PlayerRequestState.PreloadRegion(regionX, regionZ));
                }
            }
        }
    }

    private void pruneQueuedRegionsToWindow() {
        expireCoveredRegions();
        if (regions.isEmpty()) {
            queuedRegionKeys.clear();
            return;
        }

        ArrayDeque<PlayerRequestState.PreloadRegion> retained = new ArrayDeque<>(regions.size());
        queuedRegionKeys.clear();
        while (!regions.isEmpty()) {
            PlayerRequestState.PreloadRegion region = regions.poll();
            if (isRegionInWindow(region.regionX(), region.regionZ())) {
                retained.add(region);
                queuedRegionKeys.add(regionKey(region.regionX(), region.regionZ()));
            }
        }
        regions.addAll(retained);
    }

    private void expireCoveredRegions() {
        long now = nanoClock.getAsLong();
        coveredRegionKeys.removeIf(key -> shouldDropCoveredRegion(key, now));
    }

    private boolean shouldDropCoveredRegion(long key, long now) {
        int regionX = regionKeyX(key);
        int regionZ = regionKeyZ(key);
        if (isRegionInWindow(regionX, regionZ)) {
            retainedRegionDeadlines.remove(key);
            return false;
        }

        long deadline = retainedRegionDeadlines.computeIfAbsent(key, ignored -> now + PRELOAD_REGION_RETAIN_NANOS);
        if (now < deadline) {
            return false;
        }
        retainedRegionDeadlines.remove(key);
        return true;
    }

    private void reorderRegions(int centerRegionX, int centerRegionZ) {
        if (regions.size() <= 1) {
            return;
        }
        ArrayList<PlayerRequestState.PreloadRegion> ordered = new ArrayList<>(regions);
        ordered.sort(regionComparator(centerRegionX, centerRegionZ));
        regions.clear();
        regions.addAll(ordered);
    }

    private void setWindow(Object dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        this.dimension = dimension;
        initialized = true;
        this.centerRegionX = centerRegionX;
        this.centerRegionZ = centerRegionZ;
        this.maxRegionRing = maxRegionRing;
        minRegionX = centerRegionX - maxRegionRing;
        maxRegionX = centerRegionX + maxRegionRing;
        minRegionZ = centerRegionZ - maxRegionRing;
        maxRegionZ = centerRegionZ + maxRegionRing;
    }

    private boolean isRegionInWindow(int regionX, int regionZ) {
        return regionX >= minRegionX
                && regionX <= maxRegionX
                && regionZ >= minRegionZ
                && regionZ <= maxRegionZ;
    }

    private static Comparator<PlayerRequestState.PreloadRegion> regionComparator(int centerRegionX, int centerRegionZ) {
        return Comparator
                .comparingInt((PlayerRequestState.PreloadRegion region) -> Math.max(
                        Math.abs(region.regionX() - centerRegionX),
                        Math.abs(region.regionZ() - centerRegionZ)))
                .thenComparingLong(region -> {
                    long dx = (long) region.regionX() - centerRegionX;
                    long dz = (long) region.regionZ() - centerRegionZ;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(PlayerRequestState.PreloadRegion::regionX)
                .thenComparingInt(PlayerRequestState.PreloadRegion::regionZ);
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int regionKeyX(long key) {
        return (int) (key >> 32);
    }

    private static int regionKeyZ(long key) {
        return (int) key;
    }
}
