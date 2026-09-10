package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.api.VoxelColumnData;

/** Background tile cache for predicted terrain. No predicted data is sent to Voxy. */
public final class PredictionTileManager implements AutoCloseable {
    public static final int TILE_CHUNKS = 16;
    public static final int GRID_SIZE = TILE_CHUNKS + 1;
    public static final int MAX_LOD = 8;
    /** the supports up to twenty levels; the legacy constants remain for old tests/API callers. */
    public static final int MAX_LOD_LEVEL = 19;
    /**
     * the allows 512 concurrent build tasks.  A tight pending window
     * throttles the fill rate directly: keys beyond the cap are dropped and
     * re-derived only on the next 5-tick replan.
     */
    private static final int MAX_PENDING = 512;
    /**
     * Hysteresis margin beyond the planner horizon: tiles at the horizon
     * edge must not retire on one camera step and re-plan on the next.
     */
    private static final double RETIREMENT_MARGIN_BLOCKS = 1024.0D;
    private final ResourceKey<Level> dimension;
    private final ClientTerrainSampler sampler;
    private volatile PredictionVegetation vegetation;
    private int surfaceSettings;
    private int surfaceContextSettings;
    private final Object surfaceContextLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger activeSurfaceBuilds = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger activeDetailBuilds = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean previewWorkPending;
    private volatile PredictionMediumCoverage mediumCoverage = PredictionMediumCoverage.EMPTY;
    private volatile boolean mediumCoveragePending;
    private int mediumCoverageLevelBias;
    private static final int MEDIUM_BUILDS_PER_SURFACE_TURN = 16;
    private final java.util.concurrent.atomic.AtomicInteger mediumSinceSurface = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.LongAdder samplingNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder decorationNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder meshingNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder packingNanos = new java.util.concurrent.atomic.LongAdder();
    private final ThreadPoolExecutor executor;
    private final PredictionMemoryBudget memoryBudget;
    private final Map<PredictionTileKey, PredictionMemoryBudget.Reservation> residentMemory =
            new java.util.HashMap<>();
    private final Set<Long> pendingCaptures = ConcurrentHashMap.newKeySet();
    private final Map<Long, VoxelColumnData> latestCaptures = new java.util.HashMap<>();
    private final Map<Long, Long> captureVersions = new java.util.HashMap<>();
    private static final int MAX_PENDING_CAPTURES = 32;
    private final Map<PredictionTileKey, PredictionTile> ready = new ConcurrentHashMap<>();
    private final Set<PredictionTileKey> surfaceDesired = ConcurrentHashMap.newKeySet();
    private final Set<PredictionTileKey> surfaceReady = ConcurrentHashMap.newKeySet();
    private volatile double cameraBlockX, cameraBlockZ;
    private volatile VssLodFocus buildFocus;
    private volatile PredictionWorkView workView;
    private final Set<PredictionTileKey> backgroundPending = ConcurrentHashMap.newKeySet();
    private final Set<PredictionTileKey> refinementPending = ConcurrentHashMap.newKeySet();
    private RenderSnapshot renderSnapshot;
    private volatile int surfaceRadius = 768;
    private final Set<PredictionTileKey> pending = ConcurrentHashMap.newKeySet();
    /**
     * Tiles whose build exhausted the memory budget, with the failure time.
     * A permanent blacklist leaves a permanent hole: an OOM during a
     * teleport burst (hundreds of tiles allocated at once) retired tiles
     * that were never retried for the whole session.  A backoff window
     * still prevents the OOM/logging spiral while letting a later, calmer
     * plan rebuild the region.
     */
    private final Map<PredictionTileKey, Long> failedAt = new ConcurrentHashMap<>();
    private final Map<PredictionTileKey, Long> deferredUntil = new ConcurrentHashMap<>();
    private static final long CONTENTION_RETRY_NANOS = 100_000_000L;
    private static final long FAILED_RETRY_NANOS = 30_000_000_000L;
    /** Latest planner output; drives enqueue and the OOM-guard eviction order. */
    private final Set<PredictionTileKey> desiredKeys = ConcurrentHashMap.newKeySet();
    /** Final leaves upgrade temporary coverage; ancestors can hand off to children. */
    private final Set<PredictionTileKey> terrainLeaves = ConcurrentHashMap.newKeySet();
    private volatile Map<PredictionTileKey, Integer> terrainTargets = Map.of();
    private volatile Map<PredictionTileKey, Integer> transitionTargets = Map.of();
    /**
     * Last plan cycle each tile was desired.  Within the grace window a tile
     * is still treated as desired by the OOM-guard eviction, so plan-budget
     * and focus flips while moving cannot drop a tile that is about to be
     * re-planned.
     */
    private final Map<PredictionTileKey, Long> desiredGrace = new ConcurrentHashMap<>();
    private static final long DESIRED_GRACE_NANOS = 5_000_000_000L;

    /** True when the tile is desired now or was desired within the grace window. */
    private boolean effectivelyDesired(PredictionTileKey key) {
        return effectivelyDesired(desiredKeys, desiredGrace, key,
                System.nanoTime(), DESIRED_GRACE_NANOS);
    }

    static boolean effectivelyDesired(Set<PredictionTileKey> desired,
                                      Map<PredictionTileKey, Long> grace,
                                      PredictionTileKey key, long now, long graceNanos) {
        if (desired.contains(key)) {
            return true;
        }
        Long last = grace.get(key);
        return last != null && now - last <= graceNanos;
    }
    private final Set<Long> authoritativeCells = ConcurrentHashMap.newKeySet();
    private final VssLodSampleCache sampleCache = new VssLodSampleCache();
    private final PredictionSampleStore sampleStore;
    private final PredictionDiskCache diskCache;
    private final Set<PredictionTileKey> storedTiles = ConcurrentHashMap.newKeySet();
    private static final long COLD_TILE_NANOS = 30_000_000_000L;
    private final AtomicLong meshRevision = new AtomicLong();
    private final AtomicLong meshIds = new AtomicLong();
    private final Map<PredictionTileKey, Long> captureEpochs = new ConcurrentHashMap<>();
    private final Set<PredictionTileKey> dirtyTiles = ConcurrentHashMap.newKeySet();
    /**
     * Per-tile coverage epoch.  A tile's cell masks depend only on the
     * residency of tiles in its own quadtree column (a new child takes
     * cells from whichever ancestor covered them; a retired tile hands
     * them back).  Bumping just the affected column replaces the old
     * global revision, where every ready/retire invalidated all ~900
     * cached masks and the 32-per-frame re-resolve budget replayed the
     * handovers staggered across thirty frames — visible as crawling
     * "reloading" flicker whenever tiles filled while moving.
     */
    private final java.util.concurrent.ConcurrentHashMap<PredictionTileKey, Long> coverageEpochs =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Tiles whose masks changed this frame; resolved with priority. */
    private final Set<PredictionTileKey> coverageHot =
            ConcurrentHashMap.newKeySet();
    private final AtomicLong builtTiles = new AtomicLong();
    private final AtomicLong failedTiles = new AtomicLong();
    private final AtomicLong contentionDeferrals = new AtomicLong();
    private final AtomicLong captureCancelledBuilds = new AtomicLong();
    private final AtomicLong ignoredCaptureInvalidations = new AtomicLong();
    private final AtomicBoolean firstTileLogged = new AtomicBoolean();
    private volatile VssLodLayout layout;
    private volatile boolean closed;
    private volatile boolean paused;
    private final Set<Thread> activeBuildThreads = ConcurrentHashMap.newKeySet();
    private int selectionTicks;

    public PredictionTileManager(ResourceKey<Level> dimension, ClientTerrainSampler sampler) {
        this(dimension, sampler, PredictionMemoryBudget.SHARED);
    }

    PredictionTileManager(ResourceKey<Level> dimension, ClientTerrainSampler sampler,
                          PredictionMemoryBudget memoryBudget) {
        this(dimension, sampler, memoryBudget, VSSClientConfig.CONFIG.rememberTerrain ? openDiskCache(sampler) : null);
    }

    PredictionTileManager(ResourceKey<Level> dimension, ClientTerrainSampler sampler,
                          PredictionMemoryBudget memoryBudget, PredictionDiskCache diskCache) {
        this.dimension = dimension;
        this.sampler = sampler;
        this.diskCache = diskCache;
        this.vegetation = new PredictionVegetation(sampler, diskCache);
        this.surfaceSettings = surfaceSettings();
        this.surfaceContextSettings = surfaceSettings;
        this.memoryBudget = memoryBudget;
        this.layout = createLayout();
        this.sampleStore = diskCache == null ? null
                : new PredictionSampleStore(diskCache.root().resolve("captures.smp"), sampler.profile().fingerprint());
        // The shared budget also limits simultaneous builders across dimensions.
        int workers = Math.min(memoryBudget.buildLimit(),
                PredictionMemoryBudget.workerCount(Runtime.getRuntime().availableProcessors()));
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "vss-prediction-" + dimension.location().getPath());
            thread.setDaemon(true);
            return thread;
        });
    }

    void setWorkView(PredictionWorkView view) { this.workView = view; }

    private boolean backgroundWork(PredictionTileKey key) {
        if (mediumCoverageWork(key)) return false;
        if (key.lod() < 0 || key.lod() >= layout.levelCount()) return false;
        PredictionWorkView view = workView;
        return view != null && !view.foreground(key, layout, buildFocus,
                sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height());
    }

    private int backgroundLimit() {
        return Math.min(executor.getCorePoolSize(), Math.max(1, Math.min(4,
                VSSClientConfig.CONFIG.predictionBackgroundWorkers)));
    }

    private int refinementLimit() {
        return Math.min(executor.getCorePoolSize(), PredictionWorkOrder.refinementWorkers(
                Runtime.getRuntime().availableProcessors(), VSSClientConfig.CONFIG.predictionRefinementWorkers));
    }

    private boolean ordinaryRefinement(PredictionTileKey key) {
        return !mediumCoverageWork(key)
                && !PredictionWorkOrder.scoped(key, layout, buildFocus) && !dirtyTiles.contains(key);
    }

    private boolean mediumCoverageWork(PredictionTileKey key) {
        if (!mediumCoveragePending || !mediumCoverage.paths().contains(key)) return false;
        PredictionTile tile = ready.get(key);
        return tile == null || tile.cellAxis() < (mediumCoverage.frontier().contains(key)
                ? PredictionWorkOrder.PREVIEW_CELL_AXIS : sampler.initialTerrainCellAxis(key.lod()));
    }

    public void tick(int centerChunkX, int centerChunkZ) {
        tick(centerChunkX * 16.0D + 8.0D, 64.0D, centerChunkZ * 16.0D + 8.0D,
                1.0D, null);
    }

    /** Updates desired tiles from the real camera projection and the layout. */
    public void tick(double cameraX, double cameraY, double cameraZ,
                     double pixelsPerBlock, VssLodFocus focus) {
        tick(cameraX, cameraY, cameraZ, pixelsPerBlock, focus, 0);
    }

    public void tick(double cameraX, double cameraY, double cameraZ,
                     double pixelsPerBlock, VssLodFocus focus, int vanillaRadius) {
        if (closed || paused) {
            return;
        }
        // the refreshes the projected-size quadtree every five client
        // ticks. Replanning it every tick burns a full CPU core while the
        // camera is stationary and repeatedly queues the same leaves.
        if (selectionTicks++ % 5 != 0) {
            return;
        }
        int settings = surfaceSettings();
        if (settings != surfaceSettings) {
            surfaceSettings = settings;
            meshRevision.incrementAndGet();
            dirtyTiles.addAll(surfaceReady);
            surfaceReady.clear();
        }
        this.layout = createLayout();
        this.cameraBlockX = cameraX;
        this.cameraBlockZ = cameraZ;
        this.buildFocus = focus;
        this.surfaceRadius = PredictionWorkOrder.surfaceRadius(cameraX, cameraZ, vanillaRadius,
                VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks, layout.maxDistanceBlocks(), this::isAuthoritative);
        Map<PredictionTileKey, Boolean> surfaceCandidates = new java.util.HashMap<>();
        java.util.function.Predicate<PredictionTileKey> needsSurface = key -> surfaceCandidates.computeIfAbsent(key,
                candidate -> !fullyAuthoritative(candidate));
        int centerChunkX = Math.floorDiv((int) Math.floor(cameraX), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(cameraZ), 16);
        java.util.List<PredictionTileKey> leaves = PredictionLodPlanner.plan(dimension, cameraX, cameraY,
                cameraZ, layout, focus, Math.max(0.01D, pixelsPerBlock),
                sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height(), vegetation.available() ? surfaceRadius : 0, needsSurface);
        List<PredictionTileKey> plan = new ArrayList<>(withCoarseCoverage(leaves, layout));
        surfaceDesired.clear();
        if (vegetation.available()) {
            leaves.stream().filter(needsSurface).filter(key -> PredictionWorkOrder.surfaceEligible(key, layout,
                    cameraX, cameraZ, surfaceRadius, focus)).forEach(surfaceDesired::add);
        }
        // The desired set drives enqueue and the OOM-guard eviction order.
        // Retain recent detail during movement. With persistence enabled,
        // cold covered detail can later be restored from disk.
        long now = System.nanoTime();
        desiredKeys.clear();
        desiredKeys.addAll(plan);
        terrainLeaves.clear();
        terrainLeaves.addAll(leaves);
        Map<PredictionTileKey, Integer> bandTargets = new java.util.HashMap<>();
        for (var key : leaves) bandTargets.put(key, surfaceDesired.contains(key) ? layout.cellAxis(key.lod())
                : PredictionDetailBands.cellAxis(key, layout, cameraX, cameraY, cameraZ, focus,
                        pixelsPerBlock, sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height()));
        terrainTargets = Map.copyOf(bandTargets);
        Map<PredictionTileKey, Integer> residentAxes = new java.util.HashMap<>();
        ready.forEach((key, tile) -> residentAxes.put(key, tile.cellAxis()));
        transitionTargets = PredictionTransitionPlan.exposedTargets(desiredKeys, terrainLeaves,
                residentAxes, layout.levelCount(), terrainTargets);
        refreshMediumCoverage(leaves);
        previewWorkPending = plan.stream().anyMatch(this::previewBuildNeeded);
        for (PredictionTileKey key : plan) {
            desiredGrace.put(key, now);
        }
        desiredGrace.values().removeIf(deadline -> now - deadline > (diskCache == null ? DESIRED_GRACE_NANOS : COLD_TILE_NANOS));
        refreshQueuedWork();
        deferredUntil.values().removeIf(deadline -> now - deadline >= 0);
        pruneReady(centerChunkX, centerChunkZ);
        pruneCaptureEpochs();
        dirtyTiles.stream().filter(this::effectivelyDesired)
                .sorted(Comparator.comparingInt(PredictionTileKey::lod)
                        .thenComparingDouble(key -> PredictionWorkOrder.distanceSquared(key, layout, cameraX, cameraZ)))
                .forEach(key -> enqueue(key, centerChunkX, centerChunkZ));
        if (makeRoomForSurface(centerChunkX, centerChunkZ)) return;
        // Snapshot priorities once for sorting; workers can publish new grids
        // while this list is built, changing a tile's refinement stage.
        List<BuildRequest> work = new ArrayList<>();
        for (PredictionTileKey key : plan) {
            work.add(buildRequest(key, false));
        }
        // Completed regions can refine while other coverage jobs are still running.
        surfaceDesired.stream().filter(this::surfaceBuildReady)
                .forEach(key -> work.add(buildRequest(key, true)));
        work.sort(Comparator.comparingInt(BuildRequest::priority)
                .thenComparingDouble(BuildRequest::distance)
                .thenComparingInt(request -> -request.key().lod()));
        for (BuildRequest request : work) enqueue(request.key(), centerChunkX, centerChunkZ, request.surface());
    }

    private record BuildRequest(PredictionTileKey key, boolean surface, int priority, double distance) { }

    private BuildRequest buildRequest(PredictionTileKey key, boolean surface) {
        return new BuildRequest(key, surface, workPriority(key, surface),
                PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus));
    }

    private int workPriority(PredictionTileKey key, boolean surface) {
        PredictionTile tile = ready.get(key);
        if (!surface && mediumCoverageWork(key)
                && !PredictionWorkOrder.scoped(key, layout, buildFocus)) {
            // Breadth first across the horizon, before descending locally.
            return 10_000 + (tile == null ? 0 : 10_000)
                    + (layout.levelCount() - 1 - key.lod()) * 100;
        }
        double nearby = PredictionWorkOrder.distanceSquared(key, layout, cameraBlockX, cameraBlockZ);
        if (!PredictionWorkOrder.scoped(key, layout, buildFocus)) {
            // Within admitted regions, finish nearby grids and surfaces first.
            return (backgroundWork(key) ? PredictionWorkOrder.BACKGROUND_PRIORITY : 0)
                    + PredictionWorkOrder.localRefinementPriority(nearby, tile == null ? 0 : tile.cellAxis(), surface);
        }
        if (surface && surfaceTurnReady(key)) {
            return (backgroundWork(key) ? PredictionWorkOrder.BACKGROUND_PRIORITY : 0) + 90_000;
        }
        return (backgroundWork(key) ? PredictionWorkOrder.BACKGROUND_PRIORITY : 0) + PredictionWorkOrder.priority(key, layout,
                PredictionWorkOrder.distanceSquared(key, layout, cameraBlockX, cameraBlockZ),
                tile == null ? 0 : tile.cellAxis(), surface, buildFocus);
    }

    private synchronized void refreshQueuedWork() {
        if (closed || paused) return;
        refreshQueuedWork(executor.getQueue(), pending,
                key -> key.lod() >= 0 && key.lod() < layout.levelCount() && effectivelyDesired(key)
                        && mediumWorkAllowed(key, false)
                        && (!ordinaryRefinement(key) || refinementPending.contains(key))
                        && (!backgroundWork(key) || backgroundPending.contains(key)),
                (key, surface) -> dirtyTiles.contains(key) ? Integer.MIN_VALUE + 1 + key.lod()
                        : workPriority(key, surface),
                key -> PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus));
    }

    /** Reinsert only unstarted work: changing a priority in place breaks the heap. */
    static void refreshQueuedWork(java.util.concurrent.BlockingQueue<Runnable> queue,
                                  Set<PredictionTileKey> pending,
                                  java.util.function.Predicate<PredictionTileKey> retain,
                                  java.util.function.ToIntBiFunction<PredictionTileKey, Boolean> priorityOf,
                                  java.util.function.ToDoubleFunction<PredictionTileKey> distanceOf) {
        for (Runnable runnable : queue.toArray(Runnable[]::new)) {
            if (!(runnable instanceof PredictionTask task) || task.key == null) continue;
            // Release only tasks we actually removed: a worker may have
            // taken ownership since the queue snapshot was made.
            if (!retain.test(task.key)) {
                if (queue.remove(task)) pending.remove(task.key);
                continue;
            }
            double distance = distanceOf.applyAsDouble(task.key);
            int priority = priorityOf.applyAsInt(task.key, task.surface);
            if ((priority != task.priority || distance != task.distanceSquared) && queue.remove(task)) {
                // The pending reservation and delegate stay unchanged. If a
                // worker already took the task, leave that running task alone.
                queue.offer(new PredictionTask(task.key, priority, distance, task.surface, task.delegate));
            }
        }
    }

    private boolean surfaceBuildReady(PredictionTileKey key) {
        return surfaceDesired.contains(key) && terrainComplete(key)
                && !dirtyTiles.contains(key) && !surfaceReady.contains(key);
    }

    private boolean makeRoomForSurface(int centerChunkX, int centerChunkZ) {
        // A stationary view can pin the entire byte budget in terrain. Only
        // when builders have stopped, replace less urgent detail with its
        // resident parent so a terrain or surface upgrade can proceed.
        if (memoryBudget.reclaimTargetBytes() == 0 || memoryBudget.activeBuildCount() != 0) return false;
        // Terrain upgrades and telescope targets need headroom before their
        // surfaces are ready too. Retire only less urgent, covered detail.
        PredictionTileKey target = desiredKeys.stream().filter(key -> !pending.contains(key))
                .filter(this::retryReady)
                .filter(this::terrainParentReady)
                .filter(key -> mediumWorkAllowed(key, surfaceBuildReady(key)))
                .filter(key -> terrainBuildNeeded(key) || surfaceBuildReady(key))
                .min(Comparator.<PredictionTileKey>comparingInt(key -> workPriority(key, surfaceBuildReady(key)))
                        .thenComparingDouble(key -> PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus))
                        .thenComparingInt(key -> -key.lod())).orElse(null);
        if (target == null) return true;
        List<PredictionTile> candidates = ready.values().stream().filter(tile ->
                        !mediumCoverage.paths().contains(tile.key())
                                && (mediumCoveragePending || compareBuildKeys(tile.key(), target) > 0))
                .sorted((a, b) -> compareBuildKeys(b.key(), a.key())).toList();
        for (PredictionTile tile : candidates) {
            if (memoryBudget.reclaimTargetBytes() == 0) break;
            if (!pending.contains(tile.key()) && coveredByAncestor(ready.keySet(), Set.of(), dimension, layout, tile.key()))
                removeTile(tile.key());
        }
        if (mediumCoveragePending && memoryBudget.reclaimTargetBytes() > 0
                && mediumCoverageLevelBias < layout.levelCount()-1) {
            // If even the protected coverage cannot fit alongside one build,
            // use the next wider spatial layer. Never deadlock by pinning a
            // mandatory medium wave larger than the available heap.
            mediumCoverageLevelBias++;
            refreshMediumCoverage(List.copyOf(terrainLeaves));
            refreshQueuedWork();
            return false;
        }
        // Use the reclaimed headroom for its intended nearby upgrade. A
        // newly missing preview must not immediately refill that space.
        enqueue(target, centerChunkX, centerChunkZ, surfaceBuildReady(target));
        return true;
    }

    private int compareBuildKeys(PredictionTileKey a, PredictionTileKey b) {
        // Residency order must stay stable when a tile becomes more detailed;
        // otherwise publishing it makes it the next eviction candidate.
        return compareWork(PredictionWorkOrder.priority(a, layout,
                        PredictionWorkOrder.distanceSquared(a, layout, cameraBlockX, cameraBlockZ), 0, false, buildFocus),
                PredictionWorkOrder.orderingDistance(a, layout,
                        cameraBlockX, cameraBlockZ, buildFocus), a,
                PredictionWorkOrder.priority(b, layout,
                        PredictionWorkOrder.distanceSquared(b, layout, cameraBlockX, cameraBlockZ), 0, false, buildFocus),
                PredictionWorkOrder.orderingDistance(b, layout,
                        cameraBlockX, cameraBlockZ, buildFocus), b);
    }

    static List<PredictionTileKey> withCoarseCoverage(List<PredictionTileKey> leaves, VssLodLayout layout) {
        Set<PredictionTileKey> plan = new LinkedHashSet<>();
        int top = layout.levelCount() - 1;
        for (PredictionTileKey key : leaves) {
            int shift = top - key.lod();
            plan.add(new PredictionTileKey(key.dimension(), key.tileX() >> shift, key.tileZ() >> shift, top));
        }
        // Include every intermediate ancestor before its descendants. A root no
        // longer stays visible until a distant final-resolution tile finishes.
        for (int level = top - 1; level >= 0; level--) {
            for (PredictionTileKey key : leaves) {
                if (key.lod() > level) continue;
                int shift = level - key.lod();
                plan.add(new PredictionTileKey(key.dimension(), key.tileX() >> shift, key.tileZ() >> shift, level));
            }
        }
        return List.copyOf(plan);
    }

    private synchronized boolean publishTile(PredictionTile tile,
                                              PredictionMemoryBudget.Reservation reservation,
                                              long revision, long captureEpoch, boolean surface, boolean stored) {
        if (captureEpoch != captureEpochs.getOrDefault(tile.key(), 0L)) captureCancelledBuilds.incrementAndGet();
        if (closed || revision != meshRevision.get()
                || captureEpoch != captureEpochs.getOrDefault(tile.key(), 0L)
                || !effectivelyDesired(tile.key())
                || !reservation.retain(tile.retainedHeapBytes())) {
            return false;
        }
        PredictionMemoryBudget.Reservation previous = residentMemory.put(tile.key(), reservation);
        if (previous != null) previous.close();
        ready.put(tile.key(), tile);
        if (stored) storedTiles.add(tile.key()); else storedTiles.remove(tile.key());
        if (surface) surfaceReady.add(tile.key()); else surfaceReady.remove(tile.key());
        dirtyTiles.remove(tile.key());
        markCoverageDirty(tile.key());
        return true;
    }

    private synchronized void removeTile(PredictionTileKey key) {
        if (ready.remove(key) != null) markCoverageDirty(key);
        storedTiles.remove(key);
        surfaceReady.remove(key);
        PredictionMemoryBudget.Reservation reservation = residentMemory.remove(key);
        if (reservation != null) reservation.close();
    }

    /** True when every chunk in the tile's span has an ingested exact column. */
    boolean fullyAuthoritative(PredictionTileKey key) {
        if (key.lod() >= layout.levelCount()) {
            // Stale key from a previous layout; not authoritative under
            // the new plan but also harmless — retirement handles it.
            return false;
        }
        int chunksPerSide = Math.max(1, layout.tileBlocks(key.lod()) / 16);
        int baseChunkX = key.tileX() * chunksPerSide;
        int baseChunkZ = key.tileZ() * chunksPerSide;
        for (int cz = 0; cz < chunksPerSide; cz++) {
            for (int cx = 0; cx < chunksPerSide; cx++) {
                if (!authoritativeCells.contains(pack(baseChunkX + cx, baseChunkZ + cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    public VssLodLayout layout() {
        return layout;
    }

    /** The terrain sampler backing this manager; stable for the session. */
    public ClientTerrainSampler sampler() {
        return sampler;
    }

    private PredictionVegetation vegetationForBuild(int settings) {
        synchronized (surfaceContextLock) {
        if (surfaceContextSettings != settings) {
            vegetation = new PredictionVegetation(sampler, diskCache);
            surfaceContextSettings = settings;
        }
        return vegetation;
        }
    }

    private static int surfaceSettings() {
        return (VSSClientConfig.CONFIG.predictionTrees ? 1 : 0)
                | (VSSClientConfig.CONFIG.predictionStructures ? 2 : 0);
    }

    private VssLodLayout createLayout() {
        int distance = Math.max(VSSClientConfig.MIN_PREDICTION_DISTANCE_BLOCKS,
                Math.min(VSSClientConfig.MAX_PREDICTION_DISTANCE_BLOCKS,
                        VSSClientConfig.CONFIG.predictionDistanceBlocks));
        double pixelsPerQuad = switch (VSSClientConfig.CONFIG.predictionDetail) {
            case "low" -> 4.0D;
            case "high" -> 1.5D;
            case "extreme" -> 1.0D;
            default -> 2.0D;
        };
        return VssLodLayout.of(distance, pixelsPerQuad,
                VSSClientConfig.CONFIG.predictionTrees,
                VSSClientConfig.CONFIG.rememberTerrain);
    }

    private static PredictionDiskCache openDiskCache(ClientTerrainSampler sampler) {
        var storage = PredictionCacheStorage.current();
        return storage == null ? null : storage.open(sampler);
    }

    public boolean hasCoverage(int chunkX, int chunkZ) {
        if (authoritativeCells.contains(pack(chunkX, chunkZ))) {
            return false;
        }
        for (int lod = 0; lod < layout.levelCount(); lod++) {
            if (ready.get(keyFor(chunkX, chunkZ, lod)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the ready tile that should own this chunk for the requested LOD.
     *
     * <p>The planner and the worker queue are asynchronous, so a finer child
     * can become ready before the requested LOD, and a coarser parent can be
     * the only available fallback.  Resolving the complete LOD chain keeps
     * those transient states drawable instead of leaving a hole between two
     * ready tiles.</p>
     */
    public PredictionTile coveringTile(int chunkX, int chunkZ, int desiredLod) {
        if (desiredLod < 0) {
            return null;
        }
        return renderSnapshot().coveringTile(chunkX, chunkZ, desiredLod);
    }

    /**
     * Selects the finest nominal tile key for a chunk. The set-based form keeps
     * the selection contract independent of worker-thread tile contents and
     * makes it possible to test without constructing GPU mesh data.
     */
    static PredictionTileKey findCoveringKey(Set<PredictionTileKey> readyKeys,
                                              ResourceKey<Level> dimension,
                                              VssLodLayout layout,
                                              int chunkX, int chunkZ,
                                              int desiredLod) {
        if (readyKeys == null || dimension == null || layout == null || desiredLod < 0) {
            return null;
        }
        // Distance controls work requests, not whether existing detail is visible.
        for (int lod = 0; lod < layout.levelCount(); lod++) {
            PredictionTileKey key = keyFor(dimension, layout, chunkX, chunkZ, lod);
            if (readyKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private static PredictionTileKey keyFor(ResourceKey<Level> dimension,
                                             VssLodLayout layout,
                                             int chunkX, int chunkZ, int lod) {
        int span = layout.tileBlocks(lod) / 16;
        return new PredictionTileKey(dimension, Math.floorDiv(chunkX, span),
                Math.floorDiv(chunkZ, span), lod);
    }

    public PredictionTile parentTile(PredictionTile tile) {
        if (tile == null || tile.key().lod() >= layout.levelCount() - 1) {
            return null;
        }
        int parentLod = tile.key().lod() + 1;
        int span = layout.tileBlocks(parentLod) / 16;
        PredictionTileKey parentKey = new PredictionTileKey(dimension,
                Math.floorDiv(tile.baseBlockX() >> 4, span),
                Math.floorDiv(tile.baseBlockZ() >> 4, span), parentLod);
        return ready.get(parentKey);
    }

    public boolean isAuthoritative(int chunkX, int chunkZ) {
        return authoritativeCells.contains(pack(chunkX, chunkZ));
    }

    /**
     * Withdraws the ingest claim for a chunk when Voxy reports the data is
     * genuinely gone.  Returns true when the claim actually flipped, so the
     * caller bumps the exact-coverage revision exactly once.
     */
    public boolean revokeAuthoritative(int chunkX, int chunkZ) {
        return authoritativeCells.remove(pack(chunkX, chunkZ));
    }

    public synchronized void invalidate(int chunkX, int chunkZ) {
        if (closed) return;
        long columnKey = pack(chunkX, chunkZ);
        captureVersions.computeIfPresent(columnKey, (key, version) -> version + 1);
        latestCaptures.remove(columnKey);
        // This marks data ownership for request scheduling, but it must not
        // invalidate every GPU coverage buffer. Voxy raw ingestion is queued
        // asynchronously and the renderer resolves ownership from the shared
        // main depth at draw time.
        authoritativeCells.add(pack(chunkX, chunkZ));
        // A chunk contains 256 columns; invalidating only its center left
        // stale exact samples in memory and on disk after dirty notifications.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            long sampleKey = pack(chunkX * 16 + x, chunkZ * 16 + z);
            sampleCache.remove(sampleKey);
            if (sampleStore != null) sampleStore.remove(sampleKey);
        }
        capturedTerrainChanged(chunkX, chunkZ);
    }

    public int readyCount() {
        return ready.size();
    }

    /** Rebuild appearances after atlas reload while retaining sampled terrain. */
    synchronized void invalidateAppearance() {
        meshRevision.incrementAndGet();
        for (PredictionTileKey key : List.copyOf(ready.keySet())) removeTile(key);
        failedAt.clear();
        deferredUntil.clear();
        selectionTicks = 0;
        // Existing tasks retain their pending claims until finally runs, and
        // their old revision prevents publishing a mesh with stale sprite rows.
    }

    public int pendingCount() {
        return pending.size();
    }

    public int queuedHeightSamples() {
        int count = 0;
        for (PredictionTile tile : ready.values()) {
            count += tile.heights().length;
        }
        return count;
    }

    public int readyVertexCount() {
        int count = 0;
        for (PredictionTile tile : ready.values()) {
            count += tile.mesh().vertexCount();
        }
        return count;
    }

    public long builtTileCount() {
        return builtTiles.get();
    }

    public String surfaceDiagnostics() {
        return "radius=" + surfaceRadius + ",eligible=" + surfaceDesired.size() + ",ready=" + surfaceReady.size()
                + ",groundReady=" + surfaceDesired.stream().filter(this::surfaceBuildReady).count()
                + ",terrainFull=" + ready.values().stream().filter(tile -> tile.cellAxis() == VssLodLayout.TILE_QUADS).count()
                + ",terrainPreview=" + ready.values().stream().filter(tile -> tile.cellAxis() < VssLodLayout.TILE_QUADS).count()
                + ",transitionPending=" + transitionTargets.size()
                + ",terrainRemaining=" + desiredKeys.stream().filter(this::terrainBuildNeeded).count()
                + ",foregroundPending=" + pending.stream().filter(key -> !backgroundWork(key)).count()
                + ",backgroundSlots=" + backgroundPending.stream().filter(pending::contains).count()
                + ",refinementSlots=" + refinementPending.stream().filter(pending::contains).count()
                + ",refinementLimit=" + refinementLimit()
                + ",backgroundLimit=" + backgroundLimit()
                + ",mediumSinceSurface=" + mediumSinceSurface.get()
                + ",mediumCoveragePending=" + mediumCoveragePending
                + ",mediumCoverageLevelBias=" + mediumCoverageLevelBias
                + ",mediumCoverageRemaining=" + mediumCoverage.frontier().stream().filter(key -> {
                    PredictionTile tile = ready.get(key);
                    return tile == null || tile.cellAxis() < 32;
                }).count()
                + "," + memoryBudget.diagnostics()
                + ",captureCancelledBuilds=" + captureCancelledBuilds.get()
                + ",ignoredCaptureInvalidations=" + ignoredCaptureInvalidations.get()
                + ",contentionDeferrals=" + contentionDeferrals.get()
                + ",active=" + activeSurfaceBuilds.get() + "," + vegetation.diagnostics()
                + ",detailActive=" + activeDetailBuilds.get() + ",previewWorkPending=" + previewWorkPending
                + ",stageTotalMs={sample=" + samplingNanos.sum() / 1_000_000
                + ",surface=" + decorationNanos.sum() / 1_000_000
                + ",mesh=" + meshingNanos.sum() / 1_000_000
                + ",pack=" + packingNanos.sum() / 1_000_000 + "}"
                + "," + (diskCache == null ? "disk=disabled" : diskCache.diagnostics())
                + (sampler instanceof RustTerrainSampler rust ? ","+rust.diagnostics() : "");
    }

    public long failedTileCount() {
        return failedTiles.get();
    }

    /** Current coverage epoch of a tile key (0 = never invalidated). */
    public long coverageFamilyEpoch(PredictionTileKey key) {
        Long epoch = coverageEpochs.get(key);
        return epoch == null ? 0L : epoch;
    }

    /**
     * Marks a residency change: bumps the epochs of every quadtree
     * ancestor (their masks lose or regain the tile's cells) and queues
     * the tile itself for same-frame priority resolution so the handover
     * swaps atomically instead of leaving holes or overlap for several
     * frames.
     */
    public synchronized void markCoverageDirty(PredictionTileKey key) {
        renderSnapshot = null;
        VssLodLayout tileLayout = layout;
        if (tileLayout != null && key.lod() + 1 < tileLayout.levelCount()) {
            int span = tileLayout.tileBlocks(key.lod()) / 16;
            for (int lod = key.lod() + 1; lod < tileLayout.levelCount(); lod++) {
                int ancestorSpan = tileLayout.tileBlocks(lod) / 16;
                PredictionTileKey ancestor = new PredictionTileKey(key.dimension(),
                        Math.floorDiv(key.tileX() * span, ancestorSpan),
                        Math.floorDiv(key.tileZ() * span, ancestorSpan), lod);
                coverageEpochs.merge(ancestor, 1L, Long::sum);
                coverageHot.add(ancestor);
            }
        }
        coverageEpochs.merge(key, 1L, Long::sum);
        coverageHot.add(key);
        // A late parent can replace a finer fallback too, so its resident
        // descendants must release the same region on the next resolve.
        for (PredictionTileKey descendant : ready.keySet()) {
            if (descendant.lod() >= key.lod()) continue;
            int levels = key.lod() - descendant.lod();
            if ((descendant.tileX() >> levels) == key.tileX()
                    && (descendant.tileZ() >> levels) == key.tileZ()) {
                coverageEpochs.merge(descendant, 1L, Long::sum);
                coverageHot.add(descendant);
            }
        }
    }

    /** Drains the hot set for priority resolution; renderer thread only. */
    public Set<PredictionTileKey> drainCoverageHot() {
        if (coverageHot.isEmpty()) {
            return Set.of();
        }
        Set<PredictionTileKey> drained = new HashSet<>(coverageHot);
        coverageHot.clear();
        return drained;
    }

    public int sampleCacheSize() {
        // Disk entries are mirrored into the bounded memory cache on first
        // use; counting both would report every sample twice.
        return sampleCache.size();
    }

    public int persistentSampleCount() {
        return sampleStore == null ? 0 : sampleStore.size();
    }

    /** Captures one authoritative column into the persistent sample store. */
    public synchronized void captureExactColumn(int chunkX, int chunkZ, VoxelColumnData data) {
        if (closed || data == null || !data.completesRequest()) return;
        long captureKey = pack(chunkX, chunkZ);
        if (pendingCaptures.contains(captureKey)) {
            latestCaptures.put(captureKey, data);
            captureVersions.merge(captureKey, 1L, Long::sum);
            return;
        }
        if (!tryReservePending(pendingCaptures, captureKey, MAX_PENDING_CAPTURES)) return;
        latestCaptures.put(captureKey, data);
        captureVersions.put(captureKey, 0L);
        try {
            // The worker queue is a PriorityBlockingQueue, so every task put
            // into it must implement Comparable.  A raw lambda is not
            // comparable and causes the queue to throw ClassCastException as
            // soon as a tile task is already waiting in the queue.
            executor.execute(new PredictionTask(Integer.MIN_VALUE, Long.MAX_VALUE, () -> {
                PredictionMemoryBudget.Reservation reservation = memoryBudget.tryReserve(2L * PredictionMemoryBudget.MIB);
                boolean released = false;
                try {
                if (closed || reservation == null) return;
                while (true) {
                VoxelColumnData current;
                long version;
                synchronized (this) {
                    current = latestCaptures.remove(captureKey);
                    version = captureVersions.getOrDefault(captureKey, 0L);
                    if (current == null || closed) {
                        pendingCaptures.remove(captureKey);
                        captureVersions.remove(captureKey);
                        released = true;
                        return;
                    }
                }
                ClientColumnSample[] samples = ClientCaptureExtractor.extractAll(
                        chunkX, chunkZ, current, sampler);
                synchronized (this) {
                if (closed || version != captureVersions.getOrDefault(captureKey, -1L)) continue;
                boolean changed = false;
                for (int index = 0; index < samples.length; index++) {
                    int localX = index & 15;
                    int localZ = index >>> 4;
                    long key = pack(chunkX * 16 + localX, chunkZ * 16 + localZ);
                    ClientColumnSample sample = samples[index];
                    ClientColumnSample previous = sampleCache.get(key);
                    if (previous == null && sampleStore != null) previous = sampleStore.get(key);
                    sampleCache.put(key, sample);
                    if (!sample.equals(previous)) {
                        changed = true;
                        if (sampleStore != null) sampleStore.put(key, sample);
                    }
                }
                // Re-sent authoritative columns on reconnect are not edits.
                // Explicit dirty notifications remove these samples first,
                // so an actual invalidation still refreshes every cache layer.
                if (changed) capturedTerrainChanged(chunkX, chunkZ);
                }
                }
                } finally {
                    if (reservation != null) reservation.close();
                    if (!released) synchronized (this) {
                        pendingCaptures.remove(captureKey);
                        latestCaptures.remove(captureKey);
                        captureVersions.remove(captureKey);
                    }
                }
            }));
        } catch (RejectedExecutionException ignored) {
            pendingCaptures.remove(captureKey);
            latestCaptures.remove(captureKey);
            captureVersions.remove(captureKey);
            // Closing the manager races with the final network callback.
        }
    }

    synchronized void capturedTerrainChanged(int chunkX, int chunkZ) {
        if (diskCache != null) diskCache.invalidateChunk(chunkX, chunkZ);
        vegetation.invalidate(chunkX, chunkZ);
        // Include neighboring columns used by seam walls. Retain old meshes
        // until their replacements are ready, and reject in-flight stale data.
        for (int lod = 0; lod < layout.levelCount(); lod++) {
            Set<PredictionTileKey> affected = new HashSet<>();
            int margin = Math.max(64, layout.tileBlocks(lod) / sampler.initialTerrainCellAxis(lod) * VssLodLayout.SAMPLE_MARGIN);
            int span = layout.tileBlocks(lod);
            for (int tz = Math.floorDiv(chunkZ * 16 - margin, span);
                 tz <= Math.floorDiv(chunkZ * 16 + 15 + margin, span); tz++) {
                for (int tx = Math.floorDiv(chunkX * 16 - margin, span);
                     tx <= Math.floorDiv(chunkX * 16 + 15 + margin, span); tx++) {
                    affected.add(new PredictionTileKey(dimension, tx, tz, lod));
                }
            }
            for (PredictionTileKey key : affected) {
                if (!ready.containsKey(key) && !pending.contains(key)) continue;
                // Coarse tiles span kilometres but read isolated grid points.
                // Exact traffic between those points must not continually cancel
                // their builds and prevent descendants from acquiring a parent.
                if (!captureIntersectsGrid(key, layout, chunkX, chunkZ)
                        && !surfaceReady.contains(key) && !surfaceDesired.contains(key)) {
                    ignoredCaptureInvalidations.incrementAndGet();
                    continue;
                }
                captureEpochs.merge(key, 1L, Long::sum);
                storedTiles.remove(key);
                dirtyTiles.add(key);
                surfaceReady.remove(key);
                failedAt.remove(key);
                deferredUntil.remove(key);
            }
        }
    }

    static boolean captureIntersectsGrid(PredictionTileKey key, VssLodLayout layout, int chunkX, int chunkZ) {
        int spacing = layout.sampleSpacing(key.lod());
        int span = layout.tileBlocks(key.lod());
        return captureIntersectsAxis((long) chunkX * 16, (long) key.tileX() * span, span, spacing)
                && captureIntersectsAxis((long) chunkZ * 16, (long) key.tileZ() * span, span, spacing);
    }

    private static boolean captureIntersectsAxis(long chunkMin, long base, int span, int spacing) {
        // Final-grid points include every preview/refinement grid. Include the
        // largest preview margin too, which can extend before the final border.
        long minimum = Math.max(chunkMin, base - span / 8);
        long first = -Math.floorDiv(-(minimum - base), spacing) * spacing + base;
        return first <= chunkMin + 15 && first >= base - span / 8 && first <= base + span;
    }

    private synchronized void pruneCaptureEpochs() {
        captureEpochs.keySet().removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
        dirtyTiles.removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
    }

    public boolean exactWorldgen() {
        return sampler.exactWorldgen();
    }

    public synchronized Collection<PredictionTile> readyTiles() {
        List<PredictionTile> snapshot = new ArrayList<>(ready.values());
        snapshot.sort(Comparator.comparingInt(tile -> tile.key().lod()));
        return List.copyOf(snapshot);
    }

    /** Mesh contents and ownership epochs must describe the same rendered frame. */
    synchronized RenderSnapshot renderSnapshot() {
        if (renderSnapshot == null || !renderSnapshot.layout().equals(layout)) renderSnapshot = new RenderSnapshot(dimension, layout,
                Map.copyOf(ready), Map.copyOf(coverageEpochs));
        return renderSnapshot;
    }

    static final class RenderSnapshot {
        private final ResourceKey<Level> dimension;
        private final VssLodLayout layout;
        private final Map<PredictionTileKey, PredictionTile> tiles;
        private final Map<PredictionTileKey, Long> epochs;
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PredictionTile>[] levels;

        @SuppressWarnings("unchecked")
        RenderSnapshot(ResourceKey<Level> dimension, VssLodLayout layout,
                       Map<PredictionTileKey, PredictionTile> tiles, Map<PredictionTileKey, Long> epochs) {
            this.dimension = dimension; this.layout = layout; this.tiles = tiles; this.epochs = epochs;
            levels = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap[layout.levelCount()];
            for (var tile : tiles.values()) {
                int lod = tile.key().lod();
                if (lod < 0 || lod >= levels.length || !dimension.equals(tile.key().dimension())) continue;
                if (levels[lod] == null) levels[lod] = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
                levels[lod].put(pack(tile.key().tileX(), tile.key().tileZ()), tile);
            }
        }
        ResourceKey<Level> dimension() { return dimension; }
        VssLodLayout layout() { return layout; }
        Map<PredictionTileKey, PredictionTile> tiles() { return tiles; }
        Map<PredictionTileKey, Long> epochs() { return epochs; }
        PredictionTile coveringTile(int x, int z, int desiredLod) {
            if (desiredLod < 0) return null;
            PredictionTile finest = null;
            for (int lod = 0; lod < levels.length; lod++) {
                if (finest != null && layout.sampleSpacing(lod) >= finest.spacingBlocks()) break;
                PredictionTile tile = at(x, z, lod);
                // A small-footprint preview can still be coarser than an
                // already refined ancestor. Compare actual block spacing.
                if (tile != null && (finest == null || tile.spacingBlocks() < finest.spacingBlocks())) finest = tile;
            }
            return finest;
        }
        private PredictionTile at(int chunkX, int chunkZ, int lod) {
            if (levels[lod] == null) return null;
            int span = layout.tileBlocks(lod) / 16;
            return levels[lod].get(pack(Math.floorDiv(chunkX, span), Math.floorDiv(chunkZ, span)));
        }
        long epoch(PredictionTileKey key) { return epochs.getOrDefault(key, 0L); }
    }

    /** Returns a stable snapshot of ready tile counts for all active LOD levels. */
    public int[] readyLodCounts() {
        int[] counts = new int[Math.max(MAX_LOD + 1, layout.levelCount())];
        for (PredictionTile tile : ready.values()) {
            int lod = tile.key().lod();
            if (lod >= 0 && lod < counts.length) {
                counts[lod]++;
            }
        }
        return counts;
    }

    private synchronized void enqueue(PredictionTileKey key, int centerChunkX, int centerChunkZ) {
        enqueue(key, centerChunkX, centerChunkZ, false);
    }

    private boolean terrainComplete(PredictionTileKey key) {
        PredictionTile tile = ready.get(key);
        return tile != null && tile.cellAxis() == layout.cellAxis(key.lod());
    }

    private boolean terrainBuildNeeded(PredictionTileKey key) {
        PredictionTile tile = ready.get(key);
        return tile == null || dirtyTiles.contains(key) || tile.cellAxis() < targetCellAxis(key);
    }

    private boolean previewBuildNeeded(PredictionTileKey key) {
        PredictionTile tile = ready.get(key);
        return retryReady(key) && (tile == null || tile.cellAxis() < Math.min(
                PredictionWorkOrder.PREVIEW_CELL_AXIS, targetCellAxis(key)));
    }

    private boolean tryReserveDetailBuild() {
        int limit = previewWorkPending ? Math.max(1, executor.getCorePoolSize() - 1) : executor.getCorePoolSize();
        while (true) {
            int active = activeDetailBuilds.get();
            if (active >= limit) return false;
            if (activeDetailBuilds.compareAndSet(active, active + 1)) return true;
        }
    }

    private boolean retryReady(PredictionTileKey key) {
        Long deadline = deferredUntil.get(key);
        if (deadline != null && System.nanoTime() - deadline < 0) return false;
        Long failed = failedAt.get(key);
        return failed == null || System.nanoTime() - failed > FAILED_RETRY_NANOS;
    }

    private boolean surfaceTurnReady(PredictionTileKey key) {
        return previewWorkPending && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && mediumSinceSurface.get() >= MEDIUM_BUILDS_PER_SURFACE_TURN;
    }

    private boolean tryReserveSurfaceBuild(PredictionTileKey key) {
        int limit = previewWorkPending && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                ? 1 : executor.getCorePoolSize();
        while (true) {
            int active = activeSurfaceBuilds.get();
            if (active >= limit) return false;
            if (activeSurfaceBuilds.compareAndSet(active, active + 1)) return true;
        }
    }

    private void refreshMediumCoverage(List<PredictionTileKey> leaves) {
        mediumCoverage = PredictionMediumCoverage.plan(leaves, layout, mediumCoverageLevelBias);
        mediumCoveragePending = mediumCoverage.frontier().stream().anyMatch(key -> {
            PredictionTile tile = ready.get(key);
            if (tile != null && tile.cellAxis() >= 32) return false;
            // A failed parent must not freeze all detail during its backoff.
            for (var ancestor = key; ancestor.lod() < layout.levelCount(); ancestor =
                    new PredictionTileKey(key.dimension(), ancestor.tileX() >> 1,
                            ancestor.tileZ() >> 1, ancestor.lod() + 1)) {
                if (!retryReady(ancestor)) return false;
            }
            return true;
        });
    }

    private boolean terrainParentReady(PredictionTileKey key) {
        return ready.containsKey(key) || key.lod() == layout.levelCount()-1
                || ready.containsKey(new PredictionTileKey(key.dimension(), key.tileX() >> 1,
                        key.tileZ() >> 1, key.lod() + 1));
    }

    private boolean mediumWorkAllowed(PredictionTileKey key, boolean surface) {
        if (!mediumCoveragePending || PredictionWorkOrder.scoped(key, layout, buildFocus)
                || dirtyTiles.contains(key) || !surface && mediumCoverage.paths().contains(key)) return true;
        // Wait only for this region's medium grid, never the slowest grid in
        // the entire horizon. Parent residency still protects initial coverage.
        PredictionMediumCoverage coverage = mediumCoverage;
        for (var ancestor = key; ancestor.lod() < layout.levelCount(); ancestor =
                new PredictionTileKey(key.dimension(), ancestor.tileX() >> 1,
                        ancestor.tileZ() >> 1, ancestor.lod() + 1)) {
            if (!coverage.frontier().contains(ancestor)) continue;
            PredictionTile tile = ready.get(ancestor);
            return tile != null && tile.cellAxis() >= PredictionWorkOrder.PREVIEW_CELL_AXIS;
        }
        return false;
    }

    private int targetCellAxis(PredictionTileKey key) {
        int target = terrainLeaves.contains(key) ? terrainTargets.getOrDefault(key, layout.cellAxis(key.lod()))
                : sampler.initialTerrainCellAxis(key.lod());
        if (mediumCoverage.frontier().contains(key)) target = Math.max(32, target);
        if (mediumCoveragePending && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && mediumCoverage.paths().contains(key) && (!mediumCoverage.frontier().contains(key)
                || mediumCoverageWork(key))) {
            return mediumCoverage.frontier().contains(key) ? 32 : sampler.initialTerrainCellAxis(key.lod());
        }
        return Math.max(target, transitionTargets.getOrDefault(key, target));
    }

    // Selected grids progress independently of adjacent in-flight previews.
    // The renderer retains parent coverage and stitches the resident surfaces;
    // waiting for neighbours here lets distant sampling block useful detail.
    private synchronized void enqueue(PredictionTileKey key, int centerChunkX, int centerChunkZ, boolean surface) {
        if (!mediumWorkAllowed(key, surface)) return;
        if (surface && !surfaceBuildReady(key)) return;
        if (surface && previewWorkPending && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && activeSurfaceBuilds.get() >= 1) return;
        if (!surface && !terrainBuildNeeded(key)
                || closed || paused || memoryBudget.exhausted()) {
            return;
        }
        if (!surface && !terrainParentReady(key)) return;
        // Ingest is not render residency. Keep the selected detail instead
        // of replacing captured surfaces with enormous coarse fallback slabs.
        if (!retryReady(key)) return;
        if (pending.contains(key)) return;
        backgroundPending.retainAll(pending);
        refinementPending.retainAll(pending);
        boolean ordinary = ordinaryRefinement(key);
        if (ordinary && refinementPending.size() >= refinementLimit()) return;
        boolean background = backgroundWork(key);
        if (background && backgroundPending.size() >= backgroundLimit()) return;
        boolean surfaceTurn = surface && surfaceTurnReady(key);
        failedAt.remove(key);
        deferredUntil.remove(key);
        int priority = dirtyTiles.contains(key) ? Integer.MIN_VALUE + 1 + key.lod()
                : workPriority(key, surface);
        double distance = PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus);
        if (!reserveQueuedBuild(pending, executor.getQueue(), key, priority, distance, MAX_PENDING)) {
            return;
        }
        long revision = meshRevision.get();
        long captureEpoch = captureEpochs.getOrDefault(key, 0L);
        if (background) backgroundPending.add(key);
        if (ordinary) refinementPending.add(key);
        VssLodLayout tileLayout = layout;
        int buildSurfaceSettings = surfaceSettings;
        try {
            executor.execute(new PredictionTask(key, priority, distance, surface, () -> {
            long buildStartedNanos = System.nanoTime();
            PredictionMemoryBudget.Reservation reservation = null;
            PredictionDiskCache.Lease diskLease = null;
            boolean surfaceSlot = false;
            boolean detailSlot = false;
            activeBuildThreads.add(Thread.currentThread());
            try {
                if (closed || paused || revision != meshRevision.get() || !effectivelyDesired(key)) return;
                // A queued coverage/scope task may have become ordinary work.
                // Release it for bounded admission instead of filling the CPU.
                if (ordinaryRefinement(key) && !refinementPending.contains(key)) return;
                if (!mediumWorkAllowed(key, surface)) return;
                if (!surface && !terrainBuildNeeded(key)) return;
                if (surface && !surfaceBuildReady(key)) return;
                // A promotion belongs to one bounded turn. Stale promoted
                // queue entries must not turn into an entire vegetation sweep.
                if (surfaceTurn && !surfaceTurnReady(key)) return;
                PredictionTile previousTerrain = surface ? ready.get(key) : null;
                if (surface && previousTerrain == null) return;
                reservation = memoryBudget.tryReserveBuild();
                if (reservation == null) return;
                PredictionTile resident = ready.get(key);
                diskLease = diskCache == null ? null : diskCache.lease(PredictionDiskCache.Key.terrain(key.tileX(), key.tileZ(), key.lod()));
                // Read a resident tile only for decoration. Re-reading the same
                // preview on every upgrade would prevent refinement forever.
                PredictionDiskCache.TerrainData cached = diskLease == null || resident != null && !surface ? null
                        : diskCache.readTerrainData(diskLease, 0);
                if (cached != null && (cached.cellAxis() < 1 || cached.cellAxis() > tileLayout.cellAxis(key.lod())
                        || (cached.cellAxis() & (cached.cellAxis()-1)) != 0
                        || (cached.cellAxis()+2)*(cached.cellAxis()+2) != cached.samples().length
                        || resident != null && cached.cellAxis() < resident.cellAxis())) cached=null;
                ClientColumnSample[] batch = cached == null ? null : cached.samples();
                boolean diskHit = batch != null;
                boolean coverage = !surface && !diskHit && resident == null;
                int cellAxis = diskHit ? cached.cellAxis() : surface ? tileLayout.cellAxis(key.lod())
                        : coverage ? Math.min(sampler.initialTerrainCellAxis(key.lod()), targetCellAxis(key)) : targetCellAxis(key);
                if (resident != null) cellAxis = Math.max(cellAxis, resident.cellAxis());
                if (!surface && !diskHit && !coverage && resident != null
                        && resident.cellAxis() < cellAxis) {
                    // Publish useful intermediate detail while native point caches
                    // retain the aligned samples for the next grid. A dirty tile
                    // first restores its existing detail before it can advance.
                    cellAxis = Math.min(cellAxis, resident.cellAxis() * (dirtyTiles.contains(key) ? 1 : 2));
                }
                // Full terrain and vegetation share the expensive lane. While
                // previews need work, keep one worker available for them. Deferred
                // tasks release pending/reservations normally and retry next tick;
                // workers never block waiting for a permit. Once previews finish,
                // all workers can contribute to final detail again.
                if (surface || !diskHit && !coverage && cellAxis > PredictionWorkOrder.PREVIEW_CELL_AXIS) {
                    detailSlot = tryReserveDetailBuild();
                    if (!detailSlot) return;
                }
                if (surface) {
                    surfaceSlot = tryReserveSurfaceBuild(key);
                    if (!surfaceSlot) return;
                    if (!PredictionWorkOrder.scoped(key, tileLayout, buildFocus)) mediumSinceSurface.set(0);
                }
                boolean preview = cellAxis < tileLayout.cellAxis(key.lod());
                int gridSize = cellAxis + VssLodLayout.SAMPLE_MARGIN * 2;
                int stepBlocks = tileLayout.tileBlocks(key.lod()) / cellAxis;
                int[] heights = new int[gridSize * gridSize];
                int[] groundHeights = new int[gridSize * gridSize];
                int[] materialColors = new int[gridSize * gridSize];
                int[] foliageColors = new int[gridSize * gridSize];
                int[] waterColors = new int[gridSize * gridSize];
                int[] surfaceTints = new int[gridSize * gridSize];
                int[] waterTints = new int[gridSize * gridSize];
                long colorFingerprint = sampler.colorCacheFingerprint();
                boolean cachedColors = cached != null && cached.colorsMatch(colorFingerprint);
                ClientColumnSample[] samples = new ClientColumnSample[gridSize * gridSize];
                int baseBlockX = key.tileX() * (key.lod() < tileLayout.levelCount()
                        ? tileLayout.tileBlocks(key.lod()) : PredictionTileManager.TILE_CHUNKS << key.lod());
                int baseBlockZ = key.tileZ() * tileLayout.tileBlocks(key.lod());
                // Bounded native batches evaluate the grid; the Java fallback
                // samples the exterior surface without underground spans.
                if (batch == null) {
                    PredictionTile reusable = dirtyTiles.contains(key) ? previousTerrain : resident;
                    for (int z = 0; z < gridSize; z++) for (int x = 0; x < gridSize; x++) {
                        int blockX = baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int blockZ = baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        ClientColumnSample captured = cachedSample(blockX, blockZ);
                        samples[z * gridSize + x] = captured != null ? captured
                                : retainedSample(reusable, x, z, stepBlocks, preview && !surface);
                    }
                    batch = sampleGridFast(baseBlockX, baseBlockZ, stepBlocks, gridSize, samples, preview && !surface);
                }
                for (int dz = 0; dz < gridSize; dz++) {
                    if (closed || paused || revision != meshRevision.get()
                            || Thread.currentThread().isInterrupted() || !effectivelyDesired(key)) return;
                    for (int dx = 0; dx < gridSize; dx++) {
                        int blockX = baseBlockX
                                + (dx - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int blockZ = baseBlockZ
                                + (dz - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int sampleIndex = dz * gridSize + dx;
                        ClientColumnSample sample = cachedSample(blockX, blockZ);
                        if (sample == null && previousTerrain != null && dx >= 1 && dz >= 1) {
                            sample = previousTerrain.samples()[(dz - 1) * (cellAxis + 1) + dx - 1];
                        }
                        if (sample == null) sample = batch != null ? batch[sampleIndex] : samples[sampleIndex];
                        // The native grid misses are rare; fill individual
                        // holes through the per-column path rather than
                        // dropping the tile.
                        if (sample == null) {
                            sample = sampleAt(blockX, blockZ, key.lod(), stepBlocks);
                        }
                        samples[sampleIndex] = sample;
                        heights[sampleIndex] = sample.surfaceY();
                        // sample.surfaceY is the solid boundary; fluidY is
                        // stored separately, so no second density traversal
                        // is needed for the terrain mesh.
                        groundHeights[sampleIndex] = sample.surfaceY();
                        boolean reuseColors = cachedColors && sample.equals(batch[sampleIndex]);
                        surfaceTints[sampleIndex] = reuseColors ? cached.surfaceTints()[sampleIndex]
                                : sampler.surfaceColorForLod(blockX, sample.surfaceY(), blockZ, sample.approximate());
                        int baseColor = PredictionMaterialPalette.colorFor(sample, surfaceTints[sampleIndex]);
                        materialColors[sampleIndex] = PredictionLighting.shade(
                                baseColor,
                                 sample.surfaceY(), sampler.seaLevel(), false,
                                sample.fluid() != 0);
                        // Feature stamps tint their leaves with the column's
                        // own biome instead of the registry's spawn tint.
                        foliageColors[sampleIndex] = reuseColors ? cached.foliageTints()[sampleIndex]
                                : sampler.foliageColorForLod(blockX, sample.surfaceY(), blockZ, sample.approximate());
                        if (sample.fluid() == 1 && !sample.ice()) {
                            waterTints[sampleIndex] = reuseColors ? cached.waterTints()[sampleIndex]
                                    : sampler.waterTintForLod(blockX, sample.fluidY(), blockZ, sample.approximate());
                            waterColors[sampleIndex] = PredictionMaterialPalette.waterColor(waterTints[sampleIndex]);
                        }
                    }
                }
                // Every published sampling stage survives a restart. In-memory
                // refinement is monotonic; a restored grid also prevents a
                // smaller preview from overwriting the best stored result.
                boolean stored = diskLease != null && (diskHit && (cachedColors || colorFingerprint == Long.MIN_VALUE)
                        || diskCache.writeTerrain(diskLease, new PredictionDiskCache.TerrainData(
                                samples, colorFingerprint, surfaceTints, foliageColors, waterTints)));
                long surfaceStarted = System.nanoTime();
                samplingNanos.add(surfaceStarted - buildStartedNanos);
                PredictionVegetation.Tile plants = !surface ? PredictionVegetation.Tile.EMPTY : vegetationForBuild(buildSurfaceSettings).tile(baseBlockX, baseBlockZ,
                        tileLayout.tileBlocks(key.lod()), stepBlocks, tileLayout.trees(),
                        (x, z) -> {
                            if (authoritativeCells.contains(pack(Math.floorDiv(x, 16), Math.floorDiv(z, 16)))) {
                                return true;
                            }
                            ClientColumnSample captured = cachedSample(x, z);
                            return captured != null && captured.captured();
                        });
                long meshStarted = System.nanoTime();
                decorationNanos.add(meshStarted - surfaceStarted);
                PredictionMesh mesh = PredictionMeshBuilder.build(samples, materialColors,
                        sampler.seaLevel(), sampler.fluidColor(), stepBlocks, gridSize,
                        tileLayout.trees(), sampler.featureStamps(), foliageColors, waterColors,
                        baseBlockX, baseBlockZ, plants);
                int meshGridSize = cellAxis + 1;
                ClientColumnSample[] meshSamples = cropMargin(samples, gridSize, meshGridSize);
                int[] meshHeights = cropMargin(heights, gridSize, meshGridSize);
                int[] meshGroundHeights = cropMargin(groundHeights, gridSize, meshGridSize);
                // Materialise the packed/greedy quad buffer on the worker so
                // the first render frame never pays the conversion cost.
                mesh = mesh.compactForRendering();
                PredictionDepthBound surfaceBounds = PredictionDepthBound.fromSamples(samples);
                PredictionTile completed = new PredictionTile(key, meshHeights, meshGroundHeights,
                        meshSamples, mesh, new PredictionDepthBound(surfaceBounds.minY(),
                        Math.max(surfaceBounds.maxY(), plants.maxY())),
                        System.nanoTime(), meshIds.incrementAndGet(), mesh.cellAxis(), stepBlocks);
                long packStarted = System.nanoTime();
                meshingNanos.add(packStarted - meshStarted);
                mesh.prepareGpuPayload(completed);
                packingNanos.add(System.nanoTime() - packStarted);
                if (publishTile(completed, reservation, revision, captureEpoch, surface, stored && diskLease.valid())) {
                    reservation = null;
                    if (!surface && terrainLeaves.contains(key)
                            && cellAxis >= Math.min(PredictionWorkOrder.PREVIEW_CELL_AXIS, targetCellAxis(key))
                            && (resident == null || resident.cellAxis() < Math.min(
                                    PredictionWorkOrder.PREVIEW_CELL_AXIS, targetCellAxis(key)))
                            && PredictionWorkOrder.distanceSquared(key, tileLayout, cameraBlockX, cameraBlockZ) >= 256D * 256) {
                        mediumSinceSurface.updateAndGet(count -> Math.min(MEDIUM_BUILDS_PER_SURFACE_TURN, count + 1));
                    }
                    long built = builtTiles.incrementAndGet();
                    if (built == 1L && firstTileLogged.compareAndSet(false, true) && VSSClientConfig.CONFIG.debugLogging) {
                        dev.xantha.vss.common.VSSLogger.info(
                                "VSS prediction first tile ready: key=" + key
                                        + ", samples=" + (gridSize * gridSize)
                                        + ", preview=" + preview
                                        + ", meshVertices=" + (mesh.vertexCount()
                                        + mesh.waterVertexCount())
                                        + ", buildMs=" + String.format(java.util.Locale.ROOT,
                                        "%.1f", (System.nanoTime() - buildStartedNanos)
                                                / 1_000_000.0D));
                    }
                }
            } catch (PredictionWorkDeferred deferred) {
                contentionDeferrals.incrementAndGet();
                // The owning chunk build will populate the shared cache. Give
                // this worker back to other terrain instead of parking it.
                if (!closed && !paused && revision == meshRevision.get() && effectivelyDesired(key))
                    deferredUntil.put(key, System.nanoTime() + CONTENTION_RETRY_NANOS);
            } catch (java.util.concurrent.CancellationException cancelled) {
                // Lifecycle cancellation is immediately retryable after resume.
                // A backend declining a still-current tile needs backoff so it
                // cannot monopolize the first execution slot every replan.
                if (!closed && !paused && revision == meshRevision.get()
                        && !Thread.currentThread().isInterrupted() && effectivelyDesired(key)) {
                    failedAt.put(key, System.nanoTime());
                }
            } catch (PredictionMemoryBudget.MeshLimitException limit) {
                failedAt.put(key, System.nanoTime());
                failedTiles.incrementAndGet();
                dev.xantha.vss.common.VSSLogger.debug("VSS prediction tile deferred at mesh limit: " + key);
            } catch (OutOfMemoryError error) {
                failedTiles.incrementAndGet();
                if (memoryBudget.pauseAfterOutOfMemory()) {
                    dev.xantha.vss.common.VSSLogger.warn(
                            "VSS prediction builds paused after JVM heap exhaustion; retaining coarse coverage");
                }
            } catch (Throwable throwable) {
                failedAt.put(key, System.nanoTime());
                if (closed || paused || Thread.currentThread().isInterrupted()) return;
                failedTiles.incrementAndGet();
                dev.xantha.vss.common.VSSLogger.error(
                        "VSS prediction tile build failed: " + key, throwable);
            } finally {
                if (diskLease != null) diskLease.close();
                activeBuildThreads.remove(Thread.currentThread());
                if (surfaceSlot) activeSurfaceBuilds.decrementAndGet();
                if (detailSlot) activeDetailBuilds.decrementAndGet();
                if (reservation != null) reservation.close();
                backgroundPending.remove(key);
                refinementPending.remove(key);
                pending.remove(key);
            }
            }));
        } catch (RejectedExecutionException rejected) {
            backgroundPending.remove(key);
            refinementPending.remove(key);
            pending.remove(key);
            dev.xantha.vss.common.VSSLogger.debug("VSS prediction executor rejected tile " + key);
        }
    }

    /** Replace only unstarted lower-priority work; captures and active builds retain ownership. */
    static boolean reserveQueuedBuild(Set<PredictionTileKey> pending,
                                      java.util.concurrent.BlockingQueue<Runnable> queue,
                                      PredictionTileKey key, int priority, double distance, int maxPending) {
        if (pending.contains(key)) return false;
        if (pending.size() >= maxPending) {
            PredictionTask worst = null;
            for (Runnable runnable : queue) {
                if (runnable instanceof PredictionTask task && task.key != null
                        && (worst == null || task.compareTo(worst) > 0)) worst = task;
            }
            if (worst == null || compareWork(priority, distance, key, worst.priority,
                    worst.distanceSquared, worst.key) >= 0 || !queue.remove(worst)) return false;
            pending.remove(worst.key);
        }
        return tryReservePending(pending, key, maxPending);
    }

    private static int compareWork(int priority, double distance, PredictionTileKey key,
                                   int otherPriority, double otherDistance, PredictionTileKey otherKey) {
        int order = Integer.compare(priority, otherPriority);
        if (order == 0) order = Double.compare(distance, otherDistance);
        if (order == 0 && key != null && otherKey != null) order = Integer.compare(otherKey.lod(), key.lod());
        return order;
    }

    static <T> boolean tryReservePending(Set<T> pending, T key, int maxPending) {
        if (pending == null || key == null || maxPending <= 0 || !pending.add(key)) {
            return false;
        }
        if (pending.size() <= maxPending) {
            return true;
        }
        pending.remove(key);
        return false;
    }

    static ClientColumnSample[] cropMargin(ClientColumnSample[] source,
                                            int sourceGridSize, int targetGridSize) {
        int margin = VssLodLayout.SAMPLE_MARGIN;
        validateCrop(sourceGridSize, targetGridSize, margin);
        ClientColumnSample[] result = new ClientColumnSample[targetGridSize * targetGridSize];
        for (int z = 0; z < targetGridSize; z++) {
            for (int x = 0; x < targetGridSize; x++) {
                result[z * targetGridSize + x] =
                        source[(z + margin) * sourceGridSize + x + margin];
            }
        }
        return result;
    }

    static int[] cropMargin(int[] source, int sourceGridSize, int targetGridSize) {
        int margin = VssLodLayout.SAMPLE_MARGIN;
        validateCrop(sourceGridSize, targetGridSize, margin);
        int[] result = new int[targetGridSize * targetGridSize];
        for (int z = 0; z < targetGridSize; z++) {
            System.arraycopy(source, (z + margin) * sourceGridSize + margin,
                    result, z * targetGridSize, targetGridSize);
        }
        return result;
    }

    private static void validateCrop(int sourceGridSize, int targetGridSize, int margin) {
        if (sourceGridSize <= 0 || targetGridSize <= 0 || margin < 0
                || margin + targetGridSize > sourceGridSize) {
            throw new IllegalArgumentException("invalid the sample crop");
        }
    }

    /**
     * Batched native grid sampling fast path.  The grid origin includes the
     * one-sample the margin, matching the per-column loop's
     * {@code (dx - SAMPLE_MARGIN) * step} coordinates.  Returns null when no
     * native sampler is active so the caller keeps the per-column path.
     */
    static ClientColumnSample retainedSample(PredictionTile resident, int x, int z, int step) {
        return retainedSample(resident,x,z,step,false);
    }

    static ClientColumnSample retainedSample(PredictionTile resident, int x, int z, int step, boolean preview) {
        if (resident == null || resident.spacingBlocks() <= 0 || resident.samples() == null) return null;
        int localX = (x - VssLodLayout.SAMPLE_MARGIN) * step;
        int localZ = (z - VssLodLayout.SAMPLE_MARGIN) * step;
        int oldStep = resident.spacingBlocks(), axis = resident.cellAxis() + 1;
        if (localX < 0 || localZ < 0 || localX % oldStep != 0 || localZ % oldStep != 0
                || localX / oldStep >= axis || localZ / oldStep >= axis
                || resident.samples().length != axis * axis) return null;
        ClientColumnSample sample = resident.samples()[localZ / oldStep * axis + localX / oldStep];
        return sample != null && sample.reusableFor(preview) ? sample : null;
    }

    private ClientColumnSample[] sampleGridFast(int baseBlockX, int baseBlockZ,
                                                int stepBlocks, int gridSize, ClientColumnSample[] samples, boolean preview) {
        if (!(sampler instanceof RustTerrainSampler rust)) {
            if (!preview) return samples;
            long revision = meshRevision.get();
            for (int z = 0; z < gridSize; z++) for (int x = 0; x < gridSize; x++) {
                if (closed || paused || revision != meshRevision.get() || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException();
                }
                if (samples[z * gridSize + x] == null) {
                    samples[z * gridSize + x] = sampler.samplePreview(
                            baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks,
                            baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks, stepBlocks);
                }
            }
            return samples;
        }
        long revision = meshRevision.get();
        for (int z = 0; z < gridSize; z += 8) {
            for (int x = 0; x < gridSize; x += 8) {
                if (closed || paused || revision != meshRevision.get() || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException();
                }
                int width = Math.min(8, gridSize - x), height = Math.min(8, gridSize - z);
                int originX = baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                int originZ = baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                ClientColumnSample[] retained = new ClientColumnSample[width * height];
                for (int row = 0; row < height; row++) {
                    System.arraycopy(samples, (z + row) * gridSize + x, retained, row * width, width);
                }
                ClientColumnSample[] batch = rust.sampleGrid(originX, originZ, stepBlocks, width, height, retained, preview);
                if (batch == null) return null;
                for (int row = 0; row < height; row++) {
                    System.arraycopy(batch, row * width, samples, (z + row) * gridSize + x, width);
                }
            }
        }
        return samples;
    }

    private ClientColumnSample sampleAt(int blockX, int blockZ, int lod, int stepBlocks) {
        ClientColumnSample captured = cachedSample(blockX, blockZ);
        if (captured != null) return captured;
        // Keep the LOD hook for custom backends. Both Java paths sample only
        // the exterior surface; near tiles also reuse the column cache.
        if (stepBlocks >= 16) {
            return sampler.sampleForLod(blockX, blockZ, stepBlocks);
        }
        // The full the sample grid resolves one column per grid node at
        // every level, so intermediate spacings keep their own sample instead
        // of blending four neighbours: a blended representative erases the
        // per-column steps that give distant terrain its block structure.
        if (stepBlocks >= 2) {
            return sampleSingle(blockX, blockZ);
        }
        if (VSSClientConfig.CONFIG.predictionSupersample && lod <= 1) {
            ClientColumnSample a = sampleSingle(blockX - 1, blockZ - 1);
            ClientColumnSample b = sampleSingle(blockX + 1, blockZ - 1);
            ClientColumnSample c = sampleSingle(blockX - 1, blockZ + 1);
            ClientColumnSample d = sampleSingle(blockX + 1, blockZ + 1);
            int surface = (a.surfaceY() + b.surfaceY() + c.surfaceY() + d.surfaceY()) / 4;
            int fluidY = (a.fluidY() + b.fluidY() + c.fluidY() + d.fluidY()) / 4;
            return new ClientColumnSample(surface, fluidY, a.biomeIndex(), a.topBlockIndex(),
                    a.structureIndex(), a.treeKind(), a.treeDensity(), a.treeHeight(), a.fluid(),
                    a.flags(), a.groundFeatureKind(), a.underBlockIndex(), a.deepBlockIndex(),
                    a.surfaceBottom(), a.lowerTop(), a.lowerBottom(), a.spanFloor());
        }
        return sampleSingle(blockX, blockZ);
    }

    private ClientColumnSample cachedSample(int blockX, int blockZ) {
        long sampleKey = pack(blockX, blockZ);
        ClientColumnSample sample = sampleCache.get(sampleKey);
        if (sample == null && sampleStore != null) {
            sample = sampleStore.get(sampleKey);
            if (sample != null) sampleCache.put(sampleKey, sample);
        }
        return sample;
    }

    private ClientColumnSample sampleSingle(int blockX, int blockZ) {
        long sampleKey = pack(blockX, blockZ);
        ClientColumnSample sample = cachedSample(blockX, blockZ);
        if (sample == null) {
            sample = sampler.sampleSurface(blockX, blockZ);
            sampleCache.put(sampleKey, sample);
            // Prediction grids persist in compressed tile files. The small
            // legacy sample store now contains authoritative captures only.
        }
        return sample;
    }

    private void pruneReady(int centerChunkX, int centerChunkZ) {
        // Voxy-style pinned residency: retirement is horizon-based, never
        // desired-based.  A fine tile built by a focus sweep or a past
        // position stays resident anywhere inside the horizon — the renderer
        // prefers the finest resident tile per cell, so looking back
        // re-renders it at full detail with zero rebuild cost.  Retiring on
        // focus loss destroyed built work and read as the far field
        // vanishing whenever the camera turned.
        retireCovered(centerChunkX, centerChunkZ);
        if (diskCache != null) {
            long now = System.nanoTime();
            for (PredictionTile tile : List.copyOf(ready.values())) {
                long lastUse = Math.max(tile.generatedAtNanos(), desiredGrace.getOrDefault(tile.key(), 0L));
                boolean outside = beyondHorizon(tile.baseBlockX(), tile.baseBlockZ(), tile.spanBlocks(),
                        centerChunkX * 16, centerChunkZ * 16, layout.maxDistanceBlocks() + RETIREMENT_MARGIN_BLOCKS);
                if (canRetireStored(tile.key(), now - lastUse, effectivelyDesired(tile.key()),
                        storedTiles.contains(tile.key()), ready.keySet(), dimension, layout, outside)) removeTile(tile.key());
            }
        }
        // Let JVM headroom govern residency instead of imposing a tile-count
        // ceiling. Evict inactive covered detail only as pressure requires.
        if (memoryBudget.reclaimTargetBytes() == 0) {
            return;
        }
        List<PredictionTile> evictable = new ArrayList<>();
        for (PredictionTile tile : ready.values()) {
            if (!effectivelyDesired(tile.key()) && coveredByAncestor(ready.keySet(), Set.of(),
                    dimension, layout, tile.key())) {
                evictable.add(tile);
            }
        }
        evictable.sort(Comparator.comparingLong((PredictionTile tile) ->
                distanceSquared(tile, centerChunkX, centerChunkZ)).reversed());
        for (PredictionTile tile : evictable) {
            if (memoryBudget.reclaimTargetBytes() == 0) break;
            if (coveredByAncestor(ready.keySet(), Set.of(), dimension, layout, tile.key())) {
                removeTile(tile.key());
            }
        }
    }

    /**
     * Retires tiles that left the prediction horizon and whose region stays
     * covered by a resident ancestor.  Tiles inside the horizon are pinned
     * regardless of the desired set; the ancestor test guarantees the far
     * field never exposes a hole when a tile is finally dropped.
     */
    private void retireCovered(int centerChunkX, int centerChunkZ) {
        int cameraBlockX = centerChunkX * 16;
        int cameraBlockZ = centerChunkZ * 16;
        List<PredictionTileKey> retired = null;
        for (PredictionTile tile : ready.values()) {
            PredictionTileKey key = tile.key();
            if (!shouldRetirePinned(key, ready.keySet(), desiredKeys, dimension, layout,
                    cameraBlockX, cameraBlockZ)) {
                continue;
            }
            if (retired == null) {
                retired = new ArrayList<>();
            }
            retired.add(key);
        }
        if (retired != null) {
            for (PredictionTileKey key : retired) {
                removeTile(key);
            }
        }
    }

    /**
     * Pinned-residency retirement decision.  Desired tiles are never
     * retired; inside the horizon nothing is retired (a resident tile costs
     * no rebuild work and re-renders at full detail when looked at again);
     * beyond the horizon a tile is dropped only when a resident or desired
     * ancestor keeps its region covered.
     */
    static boolean shouldRetirePinned(PredictionTileKey key,
                                      Set<PredictionTileKey> readyKeys,
                                      Set<PredictionTileKey> desiredKeys,
                                      ResourceKey<Level> dimension,
                                      VssLodLayout layout,
                                      int cameraBlockX, int cameraBlockZ) {
        if (desiredKeys.contains(key)) {
            return false;
        }
        // A profile reinstall can shrink the layout while tiles from the
        // old layout are still resident (pinned residency); VssLodLayout
        // validates its level argument, so stale keys must not reach it.
        // Out-of-range levels retire unconditionally — the tile's
        // coordinates are meaningless in the new layout and the planner
        // rebuilds the region at valid levels.
        if (key.lod() >= layout.levelCount()) {
            return true;
        }
        int span = layout.tileBlocks(key.lod());
        int minX = key.tileX() * span;
        int minZ = key.tileZ() * span;
        if (!beyondHorizon(minX, minZ, span, cameraBlockX, cameraBlockZ,
                layout.maxDistanceBlocks() + RETIREMENT_MARGIN_BLOCKS)) {
            return false;
        }
        return coveredByAncestor(readyKeys, desiredKeys, dimension, layout, key);
    }

    static boolean canRetireStored(PredictionTileKey key, long ageNanos, boolean desired, boolean stored,
                                   Set<PredictionTileKey> readyKeys, ResourceKey<Level> dimension, VssLodLayout layout) {
        return canRetireStored(key, ageNanos, desired, stored, readyKeys, dimension, layout, false);
    }

    static boolean canRetireStored(PredictionTileKey key, long ageNanos, boolean desired, boolean stored,
                                   Set<PredictionTileKey> readyKeys, ResourceKey<Level> dimension, VssLodLayout layout,
                                   boolean beyondHorizon) {
        return stored && !desired && ageNanos >= COLD_TILE_NANOS && key.lod() < layout.levelCount()
                && beyondHorizon;
    }

    /** True when no part of the tile block-range is within the given distance. */
    static boolean beyondHorizon(int minX, int minZ, int span,
                                 int cameraX, int cameraZ, double distanceBlocks) {
        double dx = cameraX < minX ? minX - cameraX
                : cameraX > minX + span ? cameraX - (minX + span) : 0.0D;
        double dz = cameraZ < minZ ? minZ - cameraZ
                : cameraZ > minZ + span ? cameraZ - (minZ + span) : 0.0D;
        return Math.sqrt(dx * dx + dz * dz) > distanceBlocks;
    }

    /** True when some ancestor of the key is resident or desired, keeping its region covered. */
    static boolean coveredByAncestor(Set<PredictionTileKey> readyKeys,
                                     Set<PredictionTileKey> desiredKeys,
                                     ResourceKey<Level> dimension,
                                     VssLodLayout layout, PredictionTileKey key) {
        for (int lod = key.lod() + 1; lod < layout.levelCount(); lod++) {
            int span = layout.tileBlocks(lod) / 16;
            PredictionTileKey ancestor = new PredictionTileKey(dimension,
                    Math.floorDiv(key.tileX() * (layout.tileBlocks(key.lod()) / 16), span),
                    Math.floorDiv(key.tileZ() * (layout.tileBlocks(key.lod()) / 16), span), lod);
            if (readyKeys.contains(ancestor) || desiredKeys.contains(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private void refineRelief(int centerChunkX, int centerChunkZ) {
        for (PredictionTile tile : ready.values()) {
            if (tile.key().lod() <= 0) continue;
            if (!highRelief(tile)) continue;
            int childLod = tile.key().lod() - 1;
            int childX = tile.key().tileX() << 1;
            int childZ = tile.key().tileZ() << 1;
            enqueue(new PredictionTileKey(dimension, childX, childZ, childLod), centerChunkX, centerChunkZ);
            enqueue(new PredictionTileKey(dimension, childX + 1, childZ, childLod), centerChunkX, centerChunkZ);
            enqueue(new PredictionTileKey(dimension, childX, childZ + 1, childLod), centerChunkX, centerChunkZ);
            enqueue(new PredictionTileKey(dimension, childX + 1, childZ + 1, childLod), centerChunkX, centerChunkZ);
        }
    }

    private static boolean highRelief(PredictionTile tile) {
        ClientColumnSample[] samples = tile.samples();
        int axis = tile.cellAxis();
        int threshold = Math.max(8, tile.spacingBlocks());
        for (int z = 1; z < axis; z++) {
            for (int x = 1; x < axis; x++) {
                int h = samples[z * (axis + 1) + x].surfaceY();
                int hx = samples[z * (axis + 1) + x - 1].surfaceY();
                int hz = samples[(z - 1) * (axis + 1) + x].surfaceY();
                if (samples[z * (axis + 1) + x].structureIndex() != 0
                        || Math.abs(h - hx) > threshold || Math.abs(h - hz) > threshold) return true;
            }
        }
        return false;
    }

    private long distanceSquared(PredictionTile tile, int centerChunkX, int centerChunkZ) {
        return distanceSquared(tile.key(), centerChunkX, centerChunkZ);
    }

    private long distanceSquared(PredictionTileKey key, int centerChunkX, int centerChunkZ) {
        int span = layout.tileBlocks(key.lod()) / 16;
        long dx = (long) key.tileX() * span + span / 2L - centerChunkX;
        long dz = (long) key.tileZ() * span + span / 2L - centerChunkZ;
        return dx * dx + dz * dz;
    }

    private PredictionTileKey keyFor(int chunkX, int chunkZ, int lod) {
        return keyFor(dimension, layout, chunkX, chunkZ, lod);
    }

    private void enqueueRing(int centerChunkX, int centerChunkZ, int lod, int radius) {
        int span = layout.tileBlocks(lod) / 16;
        int tileX = Math.floorDiv(centerChunkX, span);
        int tileZ = Math.floorDiv(centerChunkZ, span);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                enqueue(new PredictionTileKey(dimension, tileX + dx, tileZ + dz, lod),
                        centerChunkX, centerChunkZ);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        meshRevision.incrementAndGet();
        ready.clear();
        coverageEpochs.clear();
        renderSnapshot = null;
        coverageHot.clear();
        executor.shutdownNow();
        ready.clear();
        residentMemory.values().forEach(PredictionMemoryBudget.Reservation::close);
        residentMemory.clear();
        pending.clear();
        backgroundPending.clear();
        refinementPending.clear();
        pendingCaptures.clear();
        latestCaptures.clear();
        captureVersions.clear();
        desiredKeys.clear();
        desiredGrace.clear();
        terrainLeaves.clear();
        failedAt.clear();
        deferredUntil.clear();
        terrainTargets = Map.of();
        transitionTargets = Map.of();
        mediumCoverage = PredictionMediumCoverage.EMPTY;
        mediumCoveragePending = false;
        mediumCoverageLevelBias = 0;
        authoritativeCells.clear();
        captureEpochs.clear();
        dirtyTiles.clear();
        sampleCache.clear();
        storedTiles.clear();
        if (diskCache != null) diskCache.close();
        // Never wait for worker termination or disk I/O on the client thread.
        PredictionResources.retire(executor, sampleStore, sampler);
    }

    /** Invalidates in-flight publications without destroying reusable meshes. */
    synchronized void setPaused(boolean value) {
        if (closed || paused == value) return;
        paused = value;
        meshRevision.incrementAndGet();
        selectionTicks = 0;
        // Leave running keys reserved until their finally blocks execute.
        // Clearing those keys here lets an obsolete job remove a new job's key.
        if (value) {
            activeBuildThreads.forEach(Thread::interrupt);
            executor.getQueue().removeIf(task -> {
                if (task instanceof PredictionTask prediction && prediction.key != null) {
                    pending.remove(prediction.key);
                    return true;
                }
                return false;
            });
        }
    }

    public record PredictionTileKey(ResourceKey<Level> dimension, int tileX, int tileZ, int lod) {
        @Override public int hashCode() {
            // Adjacent x/z pairs collide in the record's linear 31*x+z hash.
            // These keys dominate render residency and planning lookups.
            long coordinates = (long) tileX << 32 | tileZ & 0xFFFFFFFFL;
            return it.unimi.dsi.fastutil.HashCommon.long2int(it.unimi.dsi.fastutil.HashCommon.mix(coordinates))
                    ^ it.unimi.dsi.fastutil.HashCommon.mix(lod) ^ dimension.hashCode();
        }
    }

    public record PredictionTile(PredictionTileKey key, int[] heights, int[] groundHeights,
                                 ClientColumnSample[] samples,
                                 PredictionMesh mesh,
                                 PredictionDepthBound depthBound,
                                 long generatedAtNanos, long revision,
                                 int cellAxis, int spacingBlocks) {
        long retainedHeapBytes() {
            PredictionQuadMesh quads = mesh.packed();
            // Include sample objects conservatively and the future CPU upload payload.
            return 1024L + 4L * (heights.length + groundHeights.length) + 96L * samples.length
                    + mesh.retainedHeapBytes() + 48L * (quads.quadCount() + quads.waterQuadCount())
                    + 16L * cellAxis * cellAxis;
        }

        public int heightAt(int localBlockX, int localBlockZ) {
            int sampleX = Math.max(0, Math.min(cellAxis, Math.floorDiv(localBlockX, spacingBlocks)));
            int sampleZ = Math.max(0, Math.min(cellAxis, Math.floorDiv(localBlockZ, spacingBlocks)));
            return heights[sampleZ * (cellAxis + 1) + sampleX];
        }

        public int groundHeightAt(int localBlockX, int localBlockZ) {
            int sampleX = Math.max(0, Math.min(cellAxis, Math.floorDiv(localBlockX, spacingBlocks)));
            int sampleZ = Math.max(0, Math.min(cellAxis, Math.floorDiv(localBlockZ, spacingBlocks)));
            return groundHeights[sampleZ * (cellAxis + 1) + sampleX];
        }

        public int baseBlockX() {
            return key.tileX() * cellAxis * spacingBlocks;
        }

        public int baseBlockZ() {
            return key.tileZ() * cellAxis * spacingBlocks;
        }

        public int spanBlocks() {
            return cellAxis * spacingBlocks;
        }
    }

    private static long pack(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (long) chunkZ & 0xFFFFFFFFL;
    }

    static final class PredictionTask implements Runnable, Comparable<PredictionTask> {
        private final PredictionTileKey key;
        private final int priority;
        private final double distanceSquared;
        private final boolean surface;
        private final Runnable delegate;

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, Runnable delegate) {
            this(key, priority, distanceSquared, false, delegate);
        }

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, boolean surface, Runnable delegate) {
            this.key = key;
            this.priority = priority;
            this.distanceSquared = distanceSquared;
            this.surface = surface;
            this.delegate = delegate;
        }

        private PredictionTask(int priority, long distanceSquared, Runnable delegate) {
            this(null, priority, distanceSquared, false, delegate);
        }

        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public int compareTo(PredictionTask other) {
            // Use the same distance-to-tile bounds and parent tie break as
            // admission. Large roots have distant centres even beside the player.
            return compareWork(priority, distanceSquared, key,
                    other.priority, other.distanceSquared, other.key);
        }
    }
}
