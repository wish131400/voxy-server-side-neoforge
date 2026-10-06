package dev.xantha.vss.client.prediction;

import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.COLD_TILE_NANOS;
import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.RETIREMENT_MARGIN_BLOCKS;
import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.beyondHorizon;
import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.canRetireStored;
import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.coveredByAncestor;
import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.firstMissingAncestor;
import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.outOfRetirementRange;
import static dev.xantha.vss.client.prediction.PredictionTileResidencyPolicy.shouldRetirePinned;

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
    private final ResourceKey<Level> dimension;
    private final ClientTerrainSampler sampler;
    private volatile PredictionVegetation vegetation;
    private final LostCityHints cityHints;
    private final PredictionSimpleVegetation simpleVegetation;
    private int surfaceSettings;
    private int surfaceContextSettings;
    private final Object surfaceContextLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger activeSurfaceBuilds = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger activeDetailBuilds = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean previewWorkPending;
    private volatile PredictionLoadingProgress loadingProgress = PredictionLoadingProgress.INITIALIZING;
    private volatile boolean surfacePreviewWorkPending = true;
    private volatile PredictionMediumCoverage mediumCoverage = PredictionMediumCoverage.EMPTY;
    private volatile boolean mediumCoveragePending;
    private int mediumCoverageLevelBias;
    private static final int MEDIUM_BUILDS_PER_SURFACE_TURN = 16;
    private final java.util.concurrent.atomic.AtomicInteger mediumSinceSurface = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.LongAdder samplingNanos = new java.util.concurrent.atomic.LongAdder();
    // `samplingNanos` spans everything from thread start to the first vegetation
    // call, so it cannot answer "is this slow or is it waiting". These split the
    // same window into the phases that differ in nature: disk read/decode,
    // native terrain sampling, colour resolution and the synchronous commit.
    private final java.util.concurrent.atomic.LongAdder diskReadNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder queueWaitNanos = new java.util.concurrent.atomic.LongAdder();
    private final AtomicLong maxQueueWaitNanos = new AtomicLong();
    // P2-13: why a queued build did not run. The single `contentionDeferrals`
    // counter could not separate "the scheduler released this entry" from "a
    // resource permit was missing", which are different problems with different
    // fixes; this is what "near detail is not advancing" has to be read from.
    // Indexed by the ADMIT_* constants.
    private static final int ADMIT_NOT_DESIRED = 0, ADMIT_RELEASED = 1, ADMIT_MEDIUM = 2,
            ADMIT_TERRAIN_NEEDED = 3, ADMIT_SURFACE_READY = 4,
            ADMIT_NO_PREVIOUS = 5, ADMIT_NO_RESERVATION = 6, ADMIT_NO_DETAIL_SLOT = 7,
            ADMIT_NO_SURFACE_SLOT = 8, ADMIT_CPU_BUDGET = 9, ADMIT_REASONS = 10;
    private final java.util.concurrent.atomic.LongAdder[] admissionReasons =
            java.util.stream.IntStream.range(0, ADMIT_REASONS)
                    .mapToObj(i -> new java.util.concurrent.atomic.LongAdder())
                    .toArray(java.util.concurrent.atomic.LongAdder[]::new);
    private final java.util.concurrent.atomic.LongAdder nativeSampleNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder colorNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder exteriorNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder colorResolveNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder colorTintNanos = new java.util.concurrent.atomic.LongAdder();
    private final PredictionTerrainColors terrainColors = new PredictionTerrainColors();
    private final java.util.concurrent.atomic.LongAdder reusedColorPoints = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder colorFallbackPoints = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder captureEarlyExits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder captureDiscardNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder commitNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder diskHitBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder diskMissBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder finishedMeshRestores = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder cacheResourceDeferrals = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder reusedPointBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder adaptiveDisplayAccepted = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder adaptiveDisplayRejected = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder adaptiveDisplayFilled = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder decorationNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder meshingNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder packingNanos = new java.util.concurrent.atomic.LongAdder();
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor cacheExecutor;
    private final Map<PredictionTileKey, Long> cacheMissUntil = new ConcurrentHashMap<>();
    private final Map<PredictionTileKey, PredictionCityMeshCache.Proof> restoredCityProofs = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder cacheFastMisses = new java.util.concurrent.atomic.LongAdder();
    private final PredictionMemoryBudget memoryBudget;
    private final PredictionCpuBudget cpuBudget;
    private final Map<PredictionTileKey, PredictionMemoryBudget.Reservation> residentMemory =
            new java.util.HashMap<>();
    private final Set<Long> pendingCaptures = ConcurrentHashMap.newKeySet();
    private final Map<Long, VoxelColumnData> latestCaptures = new java.util.HashMap<>();
    private final Map<Long, Long> captureVersions = new java.util.HashMap<>();
    private static final int MAX_PENDING_CAPTURES = 32;
    private final Map<PredictionTileKey, PredictionTile> ready = new ConcurrentHashMap<>();
    private final Set<PredictionTileKey> surfaceDesired = ConcurrentHashMap.newKeySet();
    private final Set<PredictionTileKey> surfaceReady = ConcurrentHashMap.newKeySet();
    private volatile double cameraBlockX, cameraBlockY, cameraBlockZ;
    private volatile Set<PredictionTileKey> voxyOwnedTiles = Set.of();
    private volatile VssLodFocus buildFocus;
    private volatile PredictionWorkView workView;
    private final Set<PredictionTileKey> backgroundPending = ConcurrentHashMap.newKeySet();
    private final Set<PredictionTileKey> refinementPending = ConcurrentHashMap.newKeySet();
    private RenderSnapshot renderSnapshot;
    private volatile int surfaceRadius = 768;
    private final Set<PredictionTileKey> pending = ConcurrentHashMap.newKeySet();
    private final Set<PredictionTileKey> cachePending = ConcurrentHashMap.newKeySet();
    private final java.util.ArrayDeque<PredictionTileKey> cacheCandidates = new java.util.ArrayDeque<>();
    /** Keep several independent disk decodes in flight without opening an unbounded IO lane. */
    private static final int CACHE_RESTORE_IN_FLIGHT = 4;
    private final Set<PredictionTileKey> scopePending = ConcurrentHashMap.newKeySet();
    private final java.util.ArrayDeque<BuildRequest> scopeCandidates = new java.util.ArrayDeque<>();
    /**
     * Tiles whose build exhausted the memory budget, with the failure time.
     * A permanent blacklist leaves a permanent hole: an OOM during a
     * teleport burst (hundreds of tiles allocated at once) retired tiles
     * that were never retried for the whole session.  A backoff window
     * still prevents the OOM/logging spiral while letting a later, calmer
     * plan rebuild the region.
     */
    private final Map<PredictionTileKey, Long> failedAt = new ConcurrentHashMap<>();
    private final Map<PredictionTileKey, PredictionMeshFailure> meshLimits = new ConcurrentHashMap<>();
    private final Map<PredictionTileKey, Long> deferredUntil = new ConcurrentHashMap<>();
    private static final long CONTENTION_RETRY_NANOS = 100_000_000L;
    /** Avoid repeatedly starting workers that cannot acquire the shared CPU budget. */
    // The bounded admission queue already limits repeated worker starts. Keep
    // the retry delay to one planner tick so initial coverage does not lose
    // throughput at the end of a single-worker load window.
    private static final long CPU_BUDGET_RETRY_NANOS = 10_000_000L;
    private static final long FAILED_RETRY_NANOS = 30_000_000_000L;
    /** Latest planner output; drives enqueue and the OOM-guard eviction order. */
    private final Set<PredictionTileKey> desiredKeys = ConcurrentHashMap.newKeySet();
    /**
     * Ancestors that are being prepared as a safe hand-off for detail tiles
     * which have just left the moving camera horizon.  Keeping this separate
     * from the planner output lets the normal plan move on while a single
     * coarse parent is built for several retiring children.
     */
    private final Set<PredictionTileKey> fallbackKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong retiredOutOfRange = new AtomicLong();
    private final AtomicLong retainedForFallback = new AtomicLong();
    private final AtomicLong fallbackParentsQueued = new AtomicLong();
    /** Final leaves upgrade temporary coverage; ancestors can hand off to children. */
    private final Set<PredictionTileKey> terrainLeaves = ConcurrentHashMap.newKeySet();
    private final Map<PredictionTileKey, PredictionRelief> relief = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Map<PredictionTileKey, Integer> terrainTargets = Map.of();
    private final PredictionIdleRefinement idleRefinement = new PredictionIdleRefinement();
    private volatile Map<PredictionTileKey, Integer> ordinaryTargets = Map.of();
    private volatile Map<PredictionTileKey, Integer> idleTargets = Map.of();
    private final Set<PredictionTileKey> idlePending = ConcurrentHashMap.newKeySet();
    private final Map<PredictionTileKey, Long> idleResidents = new ConcurrentHashMap<>();
    private volatile boolean idleAllowed;
    private volatile String idleState = "ordinary-work";
    private volatile long idleRejectedBytes = Long.MAX_VALUE;
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
        if (key.lod() < 0 || key.lod() >= layout.levelCount()) return false;
        if (voxyOwnedTiles.contains(key)) return false;
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
    private final Set<Long> editedCells = ConcurrentHashMap.newKeySet();
    private final VssLodSampleCache sampleCache = new VssLodSampleCache();
    private final PredictionSampleStore sampleStore;
    private final PredictionDiskCache diskCache;
    private final Set<PredictionTileKey> storedTiles = ConcurrentHashMap.newKeySet();
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
    private final PredictionCaptureRefresh captureRefresh = new PredictionCaptureRefresh();
    private final java.util.concurrent.atomic.LongAdder captureRefreshDeferrals = new java.util.concurrent.atomic.LongAdder();
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
        this(dimension, sampler, memoryBudget, diskCache, memoryBudget.hasFixedAllowance()
                ? PredictionCpuBudget.fixed(memoryBudget.buildLimit()) : PredictionCpuBudget.SHARED);
    }

    PredictionTileManager(ResourceKey<Level> dimension, ClientTerrainSampler sampler,
                          PredictionMemoryBudget memoryBudget, PredictionDiskCache diskCache,
                          PredictionCpuBudget cpuBudget) {
        this.dimension = dimension;
        this.sampler = sampler;
        this.diskCache = diskCache;
        this.cityHints = new LostCityHints(dimension, sampler.profile().fingerprint());
        this.vegetation = new PredictionVegetation(sampler, diskCache, true);
        this.simpleVegetation = new PredictionSimpleVegetation(sampler);
        this.surfaceSettings = surfaceSettings();
        this.surfaceContextSettings = surfaceSettings & ~dev.xantha.vss.config.PredictionVegetationDensity.SETTINGS_MASK;
        this.memoryBudget = memoryBudget;
        this.cpuBudget = cpuBudget;
        this.layout = createLayout();
        this.sampleStore = diskCache == null ? null
                : new PredictionSampleStore(diskCache.root().resolve("captures.smp"), sampler.cacheFingerprint(), diskCache.mappings());
        // The shared budget also limits simultaneous builders across dimensions.
        int workers = Math.min(memoryBudget.buildLimit(),
                PredictionMemoryBudget.workerCount(Runtime.getRuntime().availableProcessors()));
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "vss-prediction-" + dimension.location().getPath());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        // Cache hits never enter world generation. Two independent workers keep
        // recovery moving even while generation is throttled by frame pacing.
        this.cacheExecutor = new ThreadPoolExecutor(2, 2, 15L, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(CACHE_RESTORE_IN_FLIGHT), runnable -> {
            Thread thread = new Thread(runnable, "vss-cache-restore-" + dimension.location().getPath());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        this.cacheExecutor.allowCoreThreadTimeOut(true);
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

    private int throttledRefinementLimit(PredictionFramePace.ThrottleLevel throttle) {
        int cap = refinementLimit();
        return switch (throttle) {
            case PAUSE -> 0;
            case REDUCE -> Math.max(1, cap / 2);
            case NONE -> cap;
        };
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
        if (!VSSClientConfig.CONFIG.predictionSpyglassLoading && focus != null) {
            tick(cameraX, cameraY, cameraZ, pixelsPerBlock, null, vanillaRadius);
            return;
        }
        if (closed || paused) {
            return;
        }
        // the refreshes the projected-size quadtree every five client
        // ticks. Replanning it every tick burns a full CPU core while the
        // camera is stationary and repeatedly queues the same leaves.
        VssLodLayout nextLayout = createLayout();
        boolean distanceReduced = nextLayout.maxDistanceBlocks() < layout.maxDistanceBlocks();
        int settings = surfaceSettings();
        if (selectionTicks++ % 5 != 0 && nextLayout.equals(layout) && settings == surfaceSettings
                && java.util.Objects.equals(buildFocus, focus)) {
            return;
        }
        if (settings != surfaceSettings) {
            boolean densityChanged = ((settings ^ surfaceSettings)
                    & dev.xantha.vss.config.PredictionVegetationDensity.SETTINGS_MASK) != 0;
            surfaceSettings = settings;
            meshRevision.incrementAndGet();
            if (densityChanged) dirtyTiles.addAll(ready.keySet());
            dirtyTiles.addAll(surfaceReady);
            surfaceReady.clear();
        }
        this.layout = nextLayout;
        this.cameraBlockX = cameraX;
        this.cameraBlockY = cameraY;
        this.cameraBlockZ = cameraZ;
        this.buildFocus = focus;
        this.surfaceRadius = PredictionWorkOrder.surfaceRadius(cameraX, cameraZ, vanillaRadius,
                VSSClientConfig.CONFIG.predictionSurfaceDistanceBlocks, layout.maxDistanceBlocks(), this::isRenderAuthoritative);
        Map<PredictionTileKey, Boolean> surfaceCandidates = new java.util.HashMap<>();
        java.util.function.Predicate<PredictionTileKey> needsSurface = key -> surfaceCandidates.computeIfAbsent(key,
                candidate -> !fullyAuthoritative(candidate));
        int centerChunkX = Math.floorDiv((int) Math.floor(cameraX), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(cameraZ), 16);
        java.util.List<PredictionTileKey> leaves = PredictionLodPlanner.plan(dimension, cameraX, cameraY,
                cameraZ, layout, focus, Math.max(0.01D, pixelsPerBlock),
                sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height(), vegetation.available() ? surfaceRadius : 0, needsSurface,
                key -> { var error = relief.get(key); return error != null && error.needsRefinement(); });
        List<PredictionTileKey> plan = new ArrayList<>(withCoarseCoverage(leaves, layout));
        // The same settled column index feeds the GPU region mask. A mixed
        // tile stays in the plan; only a completely owned tile can be omitted.
        refreshVoxyOwnership(plan, dev.xantha.vss.compat.ModCompat.getVoxyViewDistanceChunks().orElse(0));
        leaves = leaves.stream().filter(key -> !voxyOwnedTiles.contains(key)).toList();
        plan.removeIf(voxyOwnedTiles::contains);
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
        // A moving view may need one or two coarse ancestors to become
        // drawable before old detail can be released.  They are not part of
        // today's view plan, but stay desired long enough for their upload to
        // complete and replace the old detail atomically.
        desiredKeys.addAll(fallbackKeys);
        if (distanceReduced) {
            // A radius reduction invalidates the old hand-off chain as well as
            // the planner leaves; let the new plan establish fresh parents.
            fallbackKeys.clear();
            desiredKeys.retainAll(plan);
            retireAfterDistanceReduction(cameraX, cameraZ);
        }
        terrainLeaves.clear();
        terrainLeaves.addAll(leaves);
        Map<PredictionTileKey, Integer> bandTargets = new java.util.HashMap<>();
        for (var key : leaves) bandTargets.put(key, surfaceDesired.contains(key) ? layout.cellAxis(key.lod())
                : PredictionDetailBands.cellAxis(key, layout, cameraX, cameraY, cameraZ, focus,
                        pixelsPerBlock, sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height()));
        Map<PredictionTileKey, Integer> residentAxes = new java.util.HashMap<>();
        ready.forEach((key, tile) -> residentAxes.put(key, tile.cellAxis()));
        Map<PredictionTileKey, Integer> ordinary = new java.util.HashMap<>();
        for (var key : plan) ordinary.put(key, bandTargets.getOrDefault(key, sampler.initialTerrainCellAxis(key.lod())));
        ordinaryTargets = Map.copyOf(ordinary);
        boolean ordinaryBusy = plan.stream().filter(this::retryReady).anyMatch(key -> dirtyTiles.contains(key)
                || residentAxes.getOrDefault(key, 0) < ordinary.get(key))
                || surfaceDesired.stream().filter(this::retryReady).anyMatch(key -> !surfaceReady.contains(key)) || !pendingCaptures.isEmpty()
                || pending.stream().anyMatch(key -> !idlePending.contains(key));
        boolean framesReady = PredictionFramePace.allowsExtraWork()
                && PredictionFramePace.currentThrottle() == PredictionFramePace.ThrottleLevel.NONE
                && cpuBudget.allowsDetail();
        boolean memoryReady = memoryBudget.allowsIdleRefinement()
                && idleResidentBytes() < Math.min(PredictionIdleRefinement.MAX_RESIDENT_BYTES, idleRejectedBytes);
        idleState = ordinaryBusy ? "ordinary-work" : !framesReady ? "frame-budget"
                : !memoryReady ? "memory-budget" : "settling";
        idleAllowed = idleRefinement.settled(now, !ordinaryBusy && framesReady && memoryReady, cameraX, cameraZ);
        if (idleAllowed) idleState = "ready";
        idleTargets = idleRefinement.update(Set.copyOf(leaves), bandTargets, residentAxes, layout,
                key -> !surfaceDesired.contains(key) && retryReady(key)
                        && PredictionWorkOrder.distanceSquared(key, layout, cameraX, cameraZ)
                            > Math.pow(PredictionDetailBands.fineRadius(layout.maxDistanceBlocks(), dimension), 2),
                key -> idleScore(key, residentAxes.getOrDefault(key, 64), cameraX, cameraY, cameraZ, pixelsPerBlock),
                idleAllowed && idlePending.isEmpty(), now);
        if (idleAllowed) idleState = idleRefinement.state();
        for (var entry : idleTargets.entrySet()) {
            if (!desiredKeys.contains(entry.getKey())) plan.add(entry.getKey());
            desiredKeys.add(entry.getKey());
            terrainLeaves.add(entry.getKey());
            bandTargets.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        terrainTargets = Map.copyOf(bandTargets);
        transitionTargets = PredictionTransitionPlan.exposedTargets(desiredKeys, terrainLeaves,
                residentAxes, layout.levelCount(), terrainTargets);
        refreshMediumCoverage(leaves);
        refreshLoadingProgress(leaves, cameraX, cameraZ);
        previewWorkPending = plan.stream().anyMatch(this::previewBuildNeeded);
        // Remote horizon previews retain their reserved worker, but must not
        // serialize all nearby vegetation after the local ground is ready.
        surfacePreviewWorkPending = plan.stream().anyMatch(key -> previewBuildNeeded(key)
                && PredictionWorkOrder.distanceSquared(key, layout, cameraX, cameraZ)
                    <= (double) surfaceRadius * surfaceRadius);
        for (PredictionTileKey key : plan) {
            desiredGrace.put(key, now);
        }
        desiredGrace.values().removeIf(deadline -> now - deadline > (diskCache == null ? DESIRED_GRACE_NANOS : COLD_TILE_NANOS));
        refreshQueuedWork();
        deferredUntil.values().removeIf(deadline -> now - deadline >= 0);
        cacheMissUntil.entrySet().removeIf(entry -> now - entry.getValue() >= 0 || !effectivelyDesired(entry.getKey()));
        pruneReady(centerChunkX, centerChunkZ);
        serviceFallbacks(centerChunkX, centerChunkZ, now);
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
        if (diskCache != null) {
            diskCache.probeTerrain(work.stream().filter(request -> !request.surface())
                    .map(request -> diskKey(request.key())).toList());
            synchronized (this) {
                cacheCandidates.clear();
                // Snapshot readiness before sorting: workers may publish concurrently.
                var residentKeys = Set.copyOf(ready.keySet());
                work.stream().filter(request -> !request.surface() && cachedUpgrade(request.key()))
                        .sorted(Comparator.comparingInt((BuildRequest request) -> residentKeys.contains(request.key()) ? 1 : 0)
                                .thenComparingInt(request -> -request.key().lod())
                                .thenComparingDouble(BuildRequest::distance))
                        .forEach(request -> cacheCandidates.add(request.key()));
                restoreCached(centerChunkX, centerChunkZ);
            }
        }
        synchronized (this) {
            scopeCandidates.clear();
            for (BuildRequest request : work) {
                if (PredictionWorkOrder.scoped(request.key(), layout, buildFocus)) scopeCandidates.add(request);
            }
            refillScope(centerChunkX, centerChunkZ);
            for (BuildRequest request : work) if (!PredictionWorkOrder.scoped(request.key(), layout, buildFocus))
                enqueue(request.key(), centerChunkX, centerChunkZ, request.surface());
        }
    }

    private record BuildRequest(PredictionTileKey key, boolean surface, int priority, double distance) { }

    private double idleScore(PredictionTileKey key, int axis, double x, double y, double z, double pixels) {
        double vertical = Math.max(0, Math.max(sampler.profile().minY() - y,
                y - sampler.profile().minY() - sampler.profile().height()));
        double distance = Math.sqrt(PredictionWorkOrder.distanceSquared(key, layout, x, z) + vertical * vertical);
        double projectedCell = VssLodProjection.projectedSize(layout.tileBlocks(key.lod()), Math.max(1, distance), pixels) / axis;
        if (projectedCell <= Math.max(.5, layout.pixelThreshold() / 128)) return 0;
        var error = relief.get(key);
        return projectedCell * (backgroundWork(key) ? 1 : 4) * (error != null && error.needsRefinement() ? 1.5 : 1);
    }

    private long idleResidentBytes() {
        return idleResidents.values().stream().mapToLong(Long::longValue).sum();
    }

    private boolean idleWork(PredictionTileKey key) {
        if (!idleTargets.containsKey(key) || dirtyTiles.contains(key)
                || PredictionWorkOrder.scoped(key, layout, buildFocus)) return false;
        PredictionTile tile = ready.get(key);
        return !ordinaryTargets.containsKey(key) || tile != null && tile.cellAxis() >= ordinaryTargets.get(key);
    }

    private boolean idleAdmissionReady() {
        return idleAllowed && PredictionFramePace.allowsExtraWork()
                && PredictionFramePace.currentThrottle() == PredictionFramePace.ThrottleLevel.NONE
                && cpuBudget.allowsDetail()
                && memoryBudget.allowsIdleRefinement() && pendingCaptures.isEmpty()
                && pending.stream().allMatch(idlePending::contains);
    }

    private BuildRequest buildRequest(PredictionTileKey key, boolean surface) {
        return new BuildRequest(key, surface, workPriority(key, surface),
                PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus));
    }

    private int workPriority(PredictionTileKey key, boolean surface) {
        if (!surface && idleWork(key)) return 2_000_000;
        PredictionTile tile = ready.get(key);
        if (localCompletionTurnReady(key) && !mediumCoverageWork(key)) {
            // Local completion can interrupt a long medium coverage wave.
            // Its workers share the configured detail budget.
            int band = Math.min(1023, (int) (Math.sqrt(PredictionWorkOrder.distanceSquared(
                    key, layout, cameraBlockX, cameraBlockZ)) / 64));
            return (backgroundWork(key) ? PredictionWorkOrder.BACKGROUND_PRIORITY : 0)
                    + 5000 + band * 3 + (surface ? 0 : 1);
        }
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
        return (backgroundWork(key) ? PredictionWorkOrder.BACKGROUND_PRIORITY : 0) + PredictionWorkOrder.priority(key, layout,
                PredictionWorkOrder.distanceSquared(key, layout, cameraBlockX, cameraBlockZ),
                tile == null ? 0 : tile.cellAxis(), surface, buildFocus);
    }

    private synchronized void refreshQueuedWork() {
        if (closed || paused) return;
        refreshQueuedWork(executor.getQueue(), pending,
                key -> key.lod() >= 0 && key.lod() < layout.levelCount() && effectivelyDesired(key)
                        && (cachePending.contains(key) || !idleWork(key) || idleAdmissionReady())
                        && (cachePending.contains(key) || fallbackKeys.contains(key) || mediumWorkAllowed(key, false)
                        && (!ordinaryRefinement(key) || refinementPending.contains(key))
                        && (!backgroundWork(key) || backgroundPending.contains(key))),
                (key, surface) -> dirtyTiles.contains(key) ? Integer.MIN_VALUE + 1 + key.lod()
                        : cachePending.contains(key) ? -100 : workPriority(key, surface),
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
                if (queue.remove(task)) {
                    task.releaseAdmission();
                    pending.remove(task.key);
                }
                continue;
            }
            double distance = distanceOf.applyAsDouble(task.key);
            int priority = priorityOf.applyAsInt(task.key, task.surface);
            if ((priority != task.priority || distance != task.distanceSquared) && queue.remove(task)) {
                // The pending reservation and delegate stay unchanged. If a
                // worker already took the task, leave that running task alone.
                queue.offer(new PredictionTask(task.key, priority, distance, task.surface,
                        task.delegate, task.cpuReservation));
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
        PredictionTileKey target = desiredKeys.stream().filter(key -> !pending.contains(key) && !idleWork(key))
                .filter(this::retryReady)
                .filter(this::terrainParentReady)
                .filter(key -> mediumWorkAllowed(key, surfaceBuildReady(key)))
                .filter(key -> terrainBuildNeeded(key) || surfaceBuildReady(key))
                .min(Comparator.<PredictionTileKey>comparingInt(key -> workPriority(key, surfaceBuildReady(key)))
                        .thenComparingDouble(key -> PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus))
                        .thenComparingInt(key -> -key.lod())).orElse(null);
        if (target == null) return true;
        // Keep the active target's old detail while its replacement waits for headroom.
        List<PredictionTile> candidates = ready.values().stream().filter(tile ->
                        !PredictionWorkOrder.scoped(tile.key(), layout, buildFocus)
                                && !mediumCoverage.paths().contains(tile.key())
                                && (mediumCoveragePending || compareBuildKeys(tile.key(), target) > 0))
                .filter(tile -> !pending.contains(tile.key())
                        && coveredByAncestor(ready.keySet(), layout, tile.key()))
                .sorted((a, b) -> compareBuildKeys(b.key(), a.key())).toList();
        if (!memoryBudget.canReclaimDetail(candidates.stream().mapToLong(PredictionTile::retainedHeapBytes).sum()))
            return true;
        for (PredictionTile tile : candidates) {
            if (memoryBudget.reclaimTargetBytes() == 0) break;
            if (!pending.contains(tile.key()) && coveredByAncestor(ready.keySet(), layout, tile.key()))
                removeTile(tile.key());
        }
        if (memoryBudget.hasFixedAllowance() && mediumCoveragePending && memoryBudget.reclaimTargetBytes() > 0
                && mediumCoverageLevelBias < layout.levelCount()-1) {
            // If even the protected coverage cannot fit alongside one build,
            // use the next wider spatial layer. Never deadlock by pinning a
            // mandatory medium wave larger than the fixed allowance. Runtime
            // heap troughs pause work; they must not permanently raise the
            // coverage bias to the coarsest layer while other mods allocate.
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
                                              long revision, long captureEpoch, boolean surface, boolean refreshAncestors,
                                              boolean stored, PredictionRelief error) {
        boolean optional = idlePending.contains(tile.key());
        if (optional && idleResidentBytes() - idleResidents.getOrDefault(tile.key(), 0L)
                + tile.retainedHeapBytes() > PredictionIdleRefinement.MAX_RESIDENT_BYTES) {
            idleRejectedBytes = idleResidentBytes();
            failedAt.put(tile.key(), System.nanoTime());
            return false;
        }
        if (captureEpoch != captureEpochs.getOrDefault(tile.key(), 0L)) captureCancelledBuilds.incrementAndGet();
        if (closed || revision != meshRevision.get()
                || captureEpoch != captureEpochs.getOrDefault(tile.key(), 0L)
                || !effectivelyDesired(tile.key())
                || !reservation.retain(tile.retainedHeapBytes())) {
            return false;
        }
        PredictionTile current = ready.get(tile.key());
        // A late disk/build completion cannot undo already published detail.
        if (current != null && current.spacingBlocks() < tile.spacingBlocks()) return false;
        if (current != null && !surface && surfaceReady.contains(tile.key()) && !dirtyTiles.contains(tile.key())
                && current.spacingBlocks() == tile.spacingBlocks()) return false;
        PredictionMemoryBudget.Reservation previous = residentMemory.put(tile.key(), reservation);
        if (previous != null) previous.close();
        ready.put(tile.key(), tile);
        meshLimits.remove(tile.key());
        if (optional || idleResidents.containsKey(tile.key())) idleResidents.put(tile.key(), tile.retainedHeapBytes());
        relief.put(tile.key(), error);
        if (stored) storedTiles.add(tile.key()); else storedTiles.remove(tile.key());
        if (surface) {
            surfaceReady.add(tile.key());
            // Refresh only the closest cached tree representation, not every
            // horizon ancestor on every completed vegetation chunk.
            for (int lod=tile.key().lod()+1;refreshAncestors && lod<layout.levelCount();lod++) {
                int shift=lod-tile.key().lod();
                var ancestor=new PredictionTileKey(dimension,tile.key().tileX()>>shift,tile.key().tileZ()>>shift,lod);
                var cached=ready.get(ancestor);
                if (cached != null && cached.spacingBlocks()>2 && cached.spacingBlocks()<=8
                        && desiredKeys.contains(ancestor)) { dirtyTiles.add(ancestor); break; }
            }
        } else surfaceReady.remove(tile.key());
        dirtyTiles.remove(tile.key());
        markCoverageDirty(tile.key());
        return true;
    }

    private synchronized void removeTile(PredictionTileKey key) {
        terrainColors.remove(key);
        if (ready.remove(key) != null) markCoverageDirty(key);
        releaseTileResources(key);
    }

    private synchronized void terrainStored(PredictionTileKey key, long tileRevision, long revision, long captureEpoch) {
        PredictionTile tile = ready.get(key);
        if (!closed && revision == meshRevision.get() && captureEpoch == captureEpochs.getOrDefault(key, 0L)
                && tile != null && tile.revision() == tileRevision && !dirtyTiles.contains(key)) storedTiles.add(key);
    }

    private void releaseTileResources(PredictionTileKey key) {
        idleResidents.remove(key);
        relief.remove(key);
        storedTiles.remove(key);
        surfaceReady.remove(key);
        PredictionMemoryBudget.Reservation reservation = residentMemory.remove(key);
        if (reservation != null) reservation.close();
    }

    /** Explicit radius reductions must not wait for movement grace or a disk copy. */
    private synchronized void retireAfterDistanceReduction(double cameraX, double cameraZ) {
        java.util.function.Predicate<PredictionTileKey> retire = key -> PredictionTileResidencyPolicy.retireAfterDistanceReduction(
                key, desiredKeys, layout, cameraX, cameraZ);
        // Also cancels running builders at their next desired/publication check.
        // Keep disk entries: increasing the radius can restore them later.
        desiredGrace.keySet().removeIf(retire);
        fallbackKeys.removeIf(retire);
        boolean changed = false;
        for (var key : List.copyOf(ready.keySet())) if (retire.test(key)) {
            ready.remove(key);
            releaseTileResources(key);
            changed = true;
        }
        if (changed) {
            // One ownership invalidation for the batch, avoiding a descendant
            // scan per removed tile when a very large horizon is reduced.
            renderSnapshot = null;
            coverageEpochs.replaceAll((key, epoch) -> epoch + 1);
            for (var key : ready.keySet()) coverageEpochs.putIfAbsent(key, 1L);
            coverageHot.addAll(ready.keySet());
        }
        dirtyTiles.removeIf(retire);
        deferredUntil.keySet().removeIf(retire);
        failedAt.keySet().removeIf(retire);
        meshLimits.keySet().removeIf(retire);
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
        if (editedCells.isEmpty()) return ClientPredictionState.hasSettledExactCoverageBox(dimension,
                baseChunkX, baseChunkZ, baseChunkX + chunksPerSide - 1, baseChunkZ + chunksPerSide - 1);
        for (int cz = 0; cz < chunksPerSide; cz++) {
            for (int cx = 0; cx < chunksPerSide; cx++) {
                if (!isRenderAuthoritative(baseChunkX + cx, baseChunkZ + cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean fullyVoxyOwned(PredictionTileKey key, int radiusBlocks) {
        if (!dimension.equals(key.dimension()) || key.lod() < 0 || key.lod() >= layout.levelCount()) return false;
        int span = layout.tileBlocks(key.lod());
        double minX = (double) key.tileX() * span, minZ = (double) key.tileZ() * span;
        var profile = sampler.profile();
        if (!PredictionVoxyOwnership.containsBox(cameraBlockX, cameraBlockY, cameraBlockZ, radiusBlocks,
                minX, profile.minY(), minZ, minX + span, profile.minY() + profile.height(), minZ + span)) return false;
        int minChunkX = key.tileX() * (span / 16), minChunkZ = key.tileZ() * (span / 16);
        // Match the GPU mask's one-chunk transition. Stored sections can end
        // before a complete visible edge; keep geometry for depth-tested fallback.
        return ClientPredictionState.hasSettledExactCoverageBox(dimension, minChunkX - 1, minChunkZ - 1,
                minChunkX + span / 16, minChunkZ + span / 16);
    }

    synchronized void refreshVoxyOwnership(Collection<PredictionTileKey> plan, int radiusChunks) {
        var candidates = new HashSet<>(plan);
        candidates.addAll(ready.keySet()); candidates.addAll(pending); candidates.addAll(fallbackKeys);
        candidates.addAll(desiredGrace.keySet());
        int radius = PredictionVoxyOwnership.radiusBlocks(radiusChunks);
        var owned = new HashSet<PredictionTileKey>();
        if (radius > 0) for (var key : candidates) if (fullyVoxyOwned(key, radius)) owned.add(key);
        if (!voxyOwnedTiles.equals(owned)) renderSnapshot = null;
        voxyOwnedTiles = Set.copyOf(owned);
        // Publish the cancellation set before retiring residents so workers
        // cannot immediately restore a tile from disk or finish an old build.
        desiredGrace.keySet().removeAll(owned);
        fallbackKeys.removeAll(owned);
        for (var key : owned) if (ready.containsKey(key)) removeTile(key);
    }

    public VssLodLayout layout() {
        return layout;
    }

    /** The terrain sampler backing this manager; stable for the session. */
    public ClientTerrainSampler sampler() {
        return sampler;
    }

    private PredictionVegetation vegetationForBuild(int settings) {
        settings &= ~dev.xantha.vss.config.PredictionVegetationDensity.SETTINGS_MASK;
        synchronized (surfaceContextLock) {
        if (surfaceContextSettings != settings) {
            vegetation = new PredictionVegetation(sampler, diskCache, true);
            surfaceContextSettings = settings;
        }
        return vegetation;
        }
    }

    void tickCityHints(int chunkX, int chunkZ) { cityHints.tick(chunkX, chunkZ); }

    synchronized void acceptCityHints(dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload response) {
        boolean known = cityHints.regionKnown(response.regionX(), response.regionZ());
        boolean changed = cityHints.accept(response);
        // The first confirmed empty region can remove buildings restored from disk.
        if (!changed && (known || !response.active()
                || !cityHints.regionKnown(response.regionX(), response.regionZ()))) return;
        int minX = response.regionX() * 128 - 16, minZ = response.regionZ() * 128 - 16;
        int maxX = minX + 159, maxZ = minZ + 159;
        Set<PredictionTileKey> affected = new java.util.HashSet<>(ready.keySet());
        affected.addAll(pending);
        for (PredictionTileKey key : affected) {
            int span = layout.tileBlocks(key.lod());
            PredictionTile tile = ready.get(key);
            if (span / Math.max(1, tile == null ? layout.cellAxis(key.lod()) : tile.cellAxis()) > 16) continue;
            long x = (long) key.tileX() * span, z = (long) key.tileZ() * span;
            if (x > maxX || z > maxZ || x + span - 1 < minX || z + span - 1 < minZ) continue;
            cacheMissUntil.remove(key);
            var proof = restoredCityProofs.get(key);
            if (proof != null && proof.agrees(response)) continue;
            restoredCityProofs.remove(key);
            captureEpochs.merge(key, 1L, Long::sum);
            dirtyTiles.add(key);
            surfaceReady.remove(key);
            failedAt.remove(key);
        }
    }

    private static int surfaceSettings() {
        return (VSSClientConfig.CONFIG.predictionTrees ? 1 : 0)
                | (VSSClientConfig.CONFIG.predictionStructures ? 2 : 0) | PredictionVegetation.predicateSettings()
                | dev.xantha.vss.config.PredictionVegetationDensity.current().settingsBits();
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
        if (isAuthoritative(chunkX, chunkZ)) {
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
        // Finest-data lookup for CPU consumers; rendering uses coveringTileAtDetail.
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
     * Planning ownership is stricter than network ownership. Keep a
     * prediction tile until the exact column has settled. Local storage
     * discoveries count even without a network delivery in this session.
     */
    boolean isRenderAuthoritative(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        return editedCells.contains(key)
                || ClientPredictionState.hasSettledExactCoverage(dimension, chunkX, chunkZ);
    }

    void acceptExactColumn(int chunkX, int chunkZ) {
        authoritativeCells.add(pack(chunkX, chunkZ));
    }

    /**
     * Withdraws the ingest claim for a chunk when Voxy reports the data is
     * genuinely gone.  Returns true when the claim actually flipped, so the
     * caller bumps the exact-coverage revision exactly once.
     */
    public boolean revokeAuthoritative(int chunkX, int chunkZ) {
        long packed = pack(chunkX, chunkZ);
        Long key = null;
        // A coverage sweep mostly sees missing columns. Each empty-set read
        // can linearize its no-op removal without allocating a boxed key.
        if (!editedCells.isEmpty()) {
            key = packed;
            editedCells.remove(key);
        }
        if (authoritativeCells.isEmpty()) return false;
        return authoritativeCells.remove(key == null ? Long.valueOf(packed) : key);
    }

    public synchronized void invalidate(int chunkX, int chunkZ) {
        invalidateColumns(it.unimi.dsi.fastutil.longs.LongSets.singleton(pack(chunkX, chunkZ)));
    }

    /** A network dirty packet is one snapshot; overlapping tiles advance once. */
    public synchronized void invalidate(long[] packedPositions) {
        if (packedPositions == null || packedPositions.length == 0) return;
        invalidateColumns(new it.unimi.dsi.fastutil.longs.LongOpenHashSet(packedPositions));
    }

    private void invalidateColumns(it.unimi.dsi.fastutil.longs.LongSet columns) {
        if (closed) return;
        for (long columnKey : columns) {
            int chunkX = (int) (columnKey >> 32), chunkZ = (int) columnKey;
            captureVersions.computeIfPresent(columnKey, (key, version) -> version + 1);
            latestCaptures.remove(columnKey);
            // This marks data ownership for request scheduling, but it must not
            // invalidate every GPU coverage buffer. Voxy raw ingestion is queued
            // asynchronously and the renderer resolves ownership from the shared
            // main depth at draw time.
            authoritativeCells.add(columnKey);
            editedCells.add(columnKey);
            // A chunk contains 256 columns; invalidating only its center left
            // stale exact samples in memory and on disk after dirty notifications.
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                long sampleKey = pack(chunkX * 16 + x, chunkZ * 16 + z);
                sampleCache.remove(sampleKey);
                if (sampleStore != null) sampleStore.remove(sampleKey);
            }
        }
        capturedTerrainChanged(columns, true);
    }

    public int readyCount() {
        return ready.size();
    }

    /** Rebuild appearances after atlas reload while retaining sampled terrain. */
    synchronized void invalidateAppearance() {
        meshRevision.incrementAndGet();
        for (PredictionTileKey key : List.copyOf(ready.keySet())) removeTile(key);
        failedAt.clear();
        meshLimits.clear();
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
        long ordinaryRemaining = ordinaryTargets.entrySet().stream().filter(entry -> {
            PredictionTile tile = ready.get(entry.getKey());
            return tile == null || tile.cellAxis() < entry.getValue();
        }).count();
        return "radius=" + surfaceRadius + ",eligible=" + surfaceDesired.size() + ",ready=" + surfaceReady.size()
                + ",voxyOwnedTiles=" + voxyOwnedTiles.size()
                + ",groundReady=" + surfaceDesired.stream().filter(this::surfaceBuildReady).count()
                + ",terrainFull=" + ready.values().stream().filter(tile -> tile.cellAxis() == VssLodLayout.TILE_QUADS).count()
                + ",terrainPreview=" + ready.values().stream().filter(tile -> tile.cellAxis() < VssLodLayout.TILE_QUADS).count()
                + ",transitionPending=" + transitionTargets.size()
                + ",terrainRemaining=" + desiredKeys.stream().filter(this::terrainBuildNeeded).count()
                + ",ordinaryTargets=" + ordinaryTargets.size() + ",ordinaryRemaining=" + ordinaryRemaining
                + ",meshLimitBlocked=" + meshLimits.size()
                + ",sampleStorage={references=" + ready.values().stream().mapToLong(t -> t.samples().length).sum()
                + ",objects=" + ready.values().stream().mapToLong(t -> t.mesh().retainedSampleObjects < 0
                        ? t.samples().length : t.mesh().retainedSampleObjects).sum() + "}"
                + ",foregroundPending=" + pending.stream().filter(key -> !backgroundWork(key)).count()
                + ",cacheRestorePending=" + cachePending.stream().filter(pending::contains).count()
                + ",backgroundSlots=" + backgroundPending.stream().filter(pending::contains).count()
                + ",refinementSlots=" + refinementPending.stream().filter(pending::contains).count()
                + ",refinementLimit=" + refinementLimit()
                + ",backgroundLimit=" + backgroundLimit()
                + ",idle={state=" + idleState + ",targets=" + idleTargets.size()
                + ",remaining=" + idleTargets.entrySet().stream().filter(e -> {
                    var tile = ready.get(e.getKey()); return tile == null || tile.cellAxis() < e.getValue();
                }).count()
                + ",pending=" + idlePending.stream().filter(pending::contains).count()
                + ",residentMiB=" + idleResidentBytes() / PredictionMemoryBudget.MIB + ",limitMiB=64}"
                + ",mediumSinceSurface=" + mediumSinceSurface.get()
                + ",mediumCoveragePending=" + mediumCoveragePending
                + ",mediumCoverageLevelBias=" + mediumCoverageLevelBias
                + ",mediumCoverageRemaining=" + mediumCoverage.frontier().stream().filter(key -> {
                    PredictionTile tile = ready.get(key);
                    return tile == null || tile.cellAxis() < 32;
                }).count()
                + "," + memoryBudget.diagnostics()
                + ",residency={ready=" + ready.size()
                + ",desired=" + desiredKeys.size()
                + ",fine=" + ready.values().stream().filter(tile -> tile.key().lod() <= 1).count()
                + ",coarse=" + ready.values().stream().filter(tile -> tile.key().lod() >= Math.max(0, layout.levelCount() - 2)).count()
                + ",fallbackPending=" + fallbackKeys.size()
                + ",retiredOutOfRange=" + retiredOutOfRange.get()
                + ",retainedForFallback=" + retainedForFallback.get()
                + ",fallbackParentsQueued=" + fallbackParentsQueued.get() + "}"
                + ",captureCancelledBuilds=" + captureCancelledBuilds.get()
                + ",captureRefreshDeferrals=" + captureRefreshDeferrals.sum()
                + ",ignoredCaptureInvalidations=" + ignoredCaptureInvalidations.get()
                + ",contentionDeferrals=" + contentionDeferrals.get()
                + ",deferred=" + deferredUntil.size()
                + ",active=" + activeSurfaceBuilds.get() + "," + vegetation.diagnostics()
                + ",detailActive=" + activeDetailBuilds.get() + ",previewWorkPending=" + previewWorkPending
                + ",surfacePreviewWorkPending=" + surfacePreviewWorkPending
                + ",framePace={" + PredictionFramePace.diagnostics() + "}"
                + ",cpuBudget={" + cpuBudget.diagnostics() + "}"
                + ",biomeCache={" + sampler.decorationContext().biomeCacheDiagnostics() + "}"
                + ",densityCompiler={" + sampler.decorationContext().densityCompilerDiagnostics() + "}"
                + ",climateCompiler={" + sampler.decorationContext().climateCompilerDiagnostics() + "}"
                + ",simpleVegetation={" + simpleVegetation.diagnostics() + "}"
                + ",wallEvidence={mode=capturedOnly}"
                + ",stageTotalMs={sample=" + samplingNanos.sum() / 1_000_000
                + ",surface=" + decorationNanos.sum() / 1_000_000
                + ",mesh=" + meshingNanos.sum() / 1_000_000
                + ",pack=" + packingNanos.sum() / 1_000_000 + "}"
                // Sub-phases of the `sample` window above. `native` is the JNI
                // terrain call, `disk` the read/decode, `commit` the synchronous
                // persist enqueue and `color` the resolution/tint loop (which
                // may include missing-column sampling). Exterior is separate.
                // these against `sample` is what separates computing from
                // waiting.
                + ",stageDetailMs={disk=" + diskReadNanos.sum() / 1_000_000
                + ",native=" + nativeSampleNanos.sum() / 1_000_000
                + ",color=" + colorNanos.sum() / 1_000_000
                + ",exterior=" + exteriorNanos.sum() / 1_000_000
                + ",commit=" + commitNanos.sum() / 1_000_000 + "}"
                + ",colorDetail={resolveMs=" + colorResolveNanos.sum()/1_000_000
                + ",tintMs=" + colorTintNanos.sum()/1_000_000 + ",fallbackPoints=" + colorFallbackPoints.sum()
                + ",reusedPoints=" + reusedColorPoints.sum() + ",stageCache={" + terrainColors.diagnostics() + "}"
                + ",adaptiveDisplay={accepted=" + adaptiveDisplayAccepted.sum()
                + ",rejected=" + adaptiveDisplayRejected.sum()
                + ",filled=" + adaptiveDisplayFilled.sum() + "}"
                + ",earlyCaptureExits=" + captureEarlyExits.sum() + ",captureDiscardMs=" + captureDiscardNanos.sum()/1_000_000 + "}"
                + ",buildSources={diskHit=" + diskHitBuilds.sum()
                + ",diskMiss=" + diskMissBuilds.sum()
                + ",reusedPoints=" + reusedPointBuilds.sum()
                + ",finishedMeshRestores=" + finishedMeshRestores.sum()
                + ",resourceDeferrals=" + cacheResourceDeferrals.sum()
                + ",cacheFastMisses=" + cacheFastMisses.sum()
                + ",cacheWorkers=" + cacheExecutor.getActiveCount() + "}"
                // Queue wait is outside the `sample` window on purpose: a tile
                // that waited 200 ms and computed for 5 ms is a scheduling
                // problem, and averaging the two together hides that.
                + ",scheduling={queuedMs=" + queueWaitNanos.sum() / 1_000_000
                + ",maxQueuedMs=" + maxQueueWaitNanos.get() / 1_000_000
                + ",tilesBuilt=" + builtTiles.get()
                + ",avgQueuedMs=" + (builtTiles.get() == 0 ? 0
                        : queueWaitNanos.sum() / builtTiles.get() / 1_000_000) + "}"
                // Why a queued build did not run, by reason. Read together with
                // `scheduling`: a large `noDetailSlot`/`noSurfaceSlot` means the
                // bounded lanes are saturated, while a large `released`/`medium`
                // means admission policy itself is holding work back.
                + ",admission={notDesired=" + admissionReasons[ADMIT_NOT_DESIRED].sum()
                + ",released=" + admissionReasons[ADMIT_RELEASED].sum()
                + ",medium=" + admissionReasons[ADMIT_MEDIUM].sum()
                + ",terrainNeeded=" + admissionReasons[ADMIT_TERRAIN_NEEDED].sum()
                + ",surfaceReady=" + admissionReasons[ADMIT_SURFACE_READY].sum()
                + ",noPrevious=" + admissionReasons[ADMIT_NO_PREVIOUS].sum()
                + ",noReservation=" + admissionReasons[ADMIT_NO_RESERVATION].sum()
                + ",noDetailSlot=" + admissionReasons[ADMIT_NO_DETAIL_SLOT].sum()
                + ",noSurfaceSlot=" + admissionReasons[ADMIT_NO_SURFACE_SLOT].sum()
                + ",cpuBudget=" + admissionReasons[ADMIT_CPU_BUDGET].sum() + "}"
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
        capturedTerrainChanged(it.unimi.dsi.fastutil.longs.LongSets.singleton(pack(chunkX, chunkZ)), false);
    }

    private void capturedTerrainChanged(it.unimi.dsi.fastutil.longs.LongSet columns, boolean worldEdit) {
        if (diskCache != null) {
            if (worldEdit) diskCache.invalidateChunks(columns);
            else diskCache.invalidateCaptures(columns);
        }
        if (worldEdit) for (long column : columns)
            vegetation.invalidate((int) (column >> 32), (int) column);
        // Include neighboring columns used by seam walls. Retain old meshes
        // until their replacements are ready, and reject in-flight stale data.
        Set<PredictionTileKey> affected = new HashSet<>();
        for (int lod = 0; lod < layout.levelCount(); lod++) {
            int margin = Math.max(64, layout.tileBlocks(lod) / sampler.initialTerrainCellAxis(lod) * VssLodLayout.SAMPLE_MARGIN);
            int span = layout.tileBlocks(lod);
            for (long column : columns) {
                int chunkX = (int) (column >> 32), chunkZ = (int) column;
                for (int tz = Math.floorDiv(chunkZ * 16 - margin, span);
                     tz <= Math.floorDiv(chunkZ * 16 + 15 + margin, span); tz++) {
                    for (int tx = Math.floorDiv(chunkX * 16 - margin, span);
                         tx <= Math.floorDiv(chunkX * 16 + 15 + margin, span); tx++) {
                        PredictionTileKey key = new PredictionTileKey(dimension, tx, tz, lod);
                        if (!ready.containsKey(key) && !pending.contains(key)) continue;
                        // Sparse ancestors depend only on their actual sample grid.
                        if (!captureIntersectsGrid(key, layout, chunkX, chunkZ)
                                && !surfaceReady.contains(key) && !surfaceDesired.contains(key)) {
                            ignoredCaptureInvalidations.incrementAndGet();
                            continue;
                        }
                        affected.add(key);
                    }
                }
            }
        }
        long now = System.nanoTime();
        for (PredictionTileKey key : affected) {
            captureEpochs.merge(key, 1L, Long::sum);
            terrainColors.remove(key);
            captureRefresh.changed(key, now);
            storedTiles.remove(key);
            dirtyTiles.add(key);
            surfaceReady.remove(key);
            failedAt.remove(key);
            meshLimits.remove(key);
            deferredUntil.remove(key);
        }
    }

    static boolean captureIntersectsGrid(PredictionTileKey key, VssLodLayout layout, int chunkX, int chunkZ) {
        int spacing = layout.sampleSpacing(key.lod());
        int span = layout.tileBlocks(key.lod());
        return captureIntersectsAxis((long) chunkX * 16, (long) key.tileX() * span, span, spacing)
                && captureIntersectsAxis((long) chunkZ * 16, (long) key.tileZ() * span, span, spacing);
    }

    static boolean captureIntersectsAxis(long chunkMin, long base, int span, int spacing) {
        // Final-grid points include every preview/refinement grid. Include the
        // largest preview margin too, which can extend before the final border.
        long minimum = Math.max(chunkMin, base - span / 8);
        long first = -Math.floorDiv(-(minimum - base), spacing) * spacing + base;
        return first <= chunkMin + 15 && first >= base - span / 8 && first <= base + span;
    }

    /** Cheap stage boundary used by every expensive build phase. */
    private boolean captureCurrent(PredictionTileKey key, long epoch, long revision) {
        return !closed && !paused && !Thread.currentThread().isInterrupted()
                && (VSSClientConfig.CONFIG.predictionSpyglassLoading || !scopePending.contains(key))
                && revision == meshRevision.get()
                && epoch == captureEpochs.getOrDefault(key, 0L)
                && effectivelyDesired(key);
    }

    private void requireCaptureCurrent(PredictionTileKey key, long epoch, long revision) {
        if (!captureCurrent(key, epoch, revision)) {
            captureEarlyExits.increment();
            throw new java.util.concurrent.CancellationException("prediction capture changed");
        }
    }

    private synchronized void pruneCaptureEpochs() {
        meshLimits.keySet().removeIf(key -> !effectivelyDesired(key) && !ready.containsKey(key) && !pending.contains(key));
        captureRefresh.retain(dirtyTiles);
        captureEpochs.keySet().removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
        restoredCityProofs.keySet().removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
        dirtyTiles.removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
    }

    public boolean exactWorldgen() {
        return sampler.exactWorldgen();
    }

    /** A render-hook presence check must not copy/sort the full ready collection. */
    public boolean hasReadyTiles() { return !ready.isEmpty(); }

    public synchronized Collection<PredictionTile> readyTiles() {
        List<PredictionTile> snapshot = new ArrayList<>(ready.values());
        snapshot.sort(Comparator.comparingInt(tile -> tile.key().lod()));
        return List.copyOf(snapshot);
    }

    /** Mesh contents and ownership epochs must describe the same rendered frame. */
    synchronized RenderSnapshot renderSnapshot() {
        if (renderSnapshot == null || !renderSnapshot.layout().equals(layout)) renderSnapshot = new RenderSnapshot(dimension, layout,
                Map.copyOf(ready), Map.copyOf(coverageEpochs), null, voxyOwnedTiles);
        return renderSnapshot;
    }

    static final class RenderSnapshot {
        private final ResourceKey<Level> dimension;
        private final VssLodLayout layout;
        private final Map<PredictionTileKey, PredictionTile> tiles;
        private final Map<PredictionTileKey, Long> epochs;
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<PredictionTile>[] levels;
        private final PredictionTileTable<PredictionTile> paged;
        private final boolean scopedTiles;
        private final Set<PredictionTileKey> scopedFamilies;
        private final Set<PredictionTileKey> voxyOwned;

        @SuppressWarnings("unchecked")
        RenderSnapshot(ResourceKey<Level> dimension, VssLodLayout layout,
                       Map<PredictionTileKey, PredictionTile> tiles, Map<PredictionTileKey, Long> epochs) {
            this(dimension,layout,tiles,epochs,null);
        }
        @SuppressWarnings("unchecked")
        RenderSnapshot(ResourceKey<Level> dimension,VssLodLayout layout,Map<PredictionTileKey,PredictionTile> tiles,
                       Map<PredictionTileKey,Long> epochs,Set<PredictionTileKey> families) {
            this(dimension, layout, tiles, epochs, families, Set.of());
        }
        @SuppressWarnings("unchecked")
        RenderSnapshot(ResourceKey<Level> dimension,VssLodLayout layout,Map<PredictionTileKey,PredictionTile> tiles,
                       Map<PredictionTileKey,Long> epochs,Set<PredictionTileKey> families,Set<PredictionTileKey> voxyOwned) {
            this.voxyOwned = Set.copyOf(voxyOwned);
            if (!(tiles instanceof PredictionTileTable<?>))
                tiles = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(tiles));
            if (!(epochs instanceof PredictionTileTable<?>))
                epochs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(epochs));
            this.dimension = dimension; this.layout = layout; this.tiles = tiles; this.epochs = epochs;
            paged=tiles instanceof PredictionTileTable<?> table?(PredictionTileTable<PredictionTile>)table:null;
            scopedFamilies = families==null?PredictionScopeFamilies.find(tiles):families;
            this.scopedTiles = !scopedFamilies.isEmpty();
            if(paged!=null) { levels=null;return; }
            levels = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap[layout.levelCount()];
            int[] counts = new int[levels.length];
            for (var tile : tiles.values()) {
                int lod = tile.key().lod();
                if (lod >= 0 && lod < counts.length && dimension.equals(tile.key().dimension())) counts[lod]++;
            }
            for (int lod = 0; lod < counts.length; lod++) if (counts[lod] != 0)
                levels[lod] = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(counts[lod]);
            for (var tile : tiles.values()) {
                int lod = tile.key().lod();
                if (lod < 0 || lod >= levels.length || !dimension.equals(tile.key().dimension())) continue;
                levels[lod].put(pack(tile.key().tileX(), tile.key().tileZ()), tile);
            }
        }
        ResourceKey<Level> dimension() { return dimension; }
        VssLodLayout layout() { return layout; }
        Map<PredictionTileKey, PredictionTile> tiles() { return tiles; }
        Map<PredictionTileKey, Long> epochs() { return epochs; }
        boolean hasScopedTiles() { return scopedTiles; }
        Set<PredictionTileKey> scopedFamilies() { return scopedFamilies; }
        Set<PredictionTileKey> voxyOwned() { return voxyOwned; }
        boolean scopeAffects(PredictionTileKey key) { return scopedFamilies.contains(key); }
        void forEachChangedTile(RenderSnapshot previous, java.util.function.Consumer<PredictionTileKey> changed) {
            if (tiles == previous.tiles) return;
            if (paged != null && previous.paged != null) { paged.forEachDifference(previous.paged, changed); return; }
            for (var entry : previous.tiles.entrySet())
                if (tiles.get(entry.getKey()) != entry.getValue()) changed.accept(entry.getKey());
            for (var key : tiles.keySet()) if (!previous.tiles.containsKey(key)) changed.accept(key);
        }
        PredictionTile coveringTile(int x, int z, int desiredLod) {
            if (desiredLod < 0) return null;
            PredictionTile finest = null;
            for (int lod = 0; lod < layout.levelCount(); lod++) {
                if (finest != null && layout.sampleSpacing(lod) >= finest.spacingBlocks()) break;
                PredictionTile tile = at(x, z, lod);
                // A small-footprint preview can still be coarser than an
                // already refined ancestor. Compare actual block spacing.
                if (tile != null && (finest == null || tile.spacingBlocks() < finest.spacingBlocks())) finest = tile;
            }
            return finest;
        }
        /** Select cached geometry for today's view without discarding finer cached work. */
        PredictionTile coveringTileAtDetail(int x, int z, int desiredLod) {
            if (desiredLod <= 0 || !scopedTiles) return coveringTile(x, z, desiredLod);
            int target = 1 << Math.min(20, desiredLod);
            PredictionTile ordinary = null, finest = null;
            for (int lod = 0; lod < layout.levelCount(); lod++) {
                PredictionTile tile = at(x, z, lod);
                if (tile == null) continue;
                if (finest == null || tile.spacingBlocks() < finest.spacingBlocks()) finest = tile;
                if (!tile.scopeOnly() && (ordinary == null || tile.spacingBlocks() < ordinary.spacingBlocks())) ordinary = tile;
            }
            // Ordinary loading is monotonic. Only work explicitly promoted for
            // the telescope may return to its resident ordinary representation.
            // Newly arriving coarse ancestors must never steal ordinary detail.
            return ordinary != null && (finest == ordinary || ordinary.spacingBlocks() <= target * 2)
                    ? ordinary : finest;
        }
        private PredictionTile at(int chunkX, int chunkZ, int lod) {
            int span = layout.tileBlocks(lod) / 16;
            if(paged!=null) return paged.at(Math.floorDiv(chunkX,span),Math.floorDiv(chunkZ,span),lod);
            if (levels[lod] == null) return null;
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
        int limit = PredictionWorkOrder.detailBuildLimit(executor.getCorePoolSize(), previewWorkPending, buildFocus != null);
        while (true) {
            int active = activeDetailBuilds.get();
            if (active >= limit) return false;
            if (activeDetailBuilds.compareAndSet(active, active + 1)) return true;
        }
    }

    private boolean retryReady(PredictionTileKey key) {
        PredictionMeshFailure limit = meshLimits.get(key);
        if (limit != null && limit.matches(meshRevision.get(), captureEpochs.getOrDefault(key, 0L),
                targetCellAxis(key), surfaceSettings)) return false;
        Long deadline = deferredUntil.get(key);
        if (deadline != null && System.nanoTime() - deadline < 0) return false;
        Long failed = failedAt.get(key);
        return failed == null || System.nanoTime() - failed > FAILED_RETRY_NANOS;
    }

    private boolean localCompletionTurnReady(PredictionTileKey key) {
        return previewWorkPending && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && surfaceDesired.contains(key)
                && mediumSinceSurface.get() >= MEDIUM_BUILDS_PER_SURFACE_TURN;
    }

    private boolean tryReserveSurfaceBuild() {
        int limit = PredictionWorkOrder.surfaceBuildLimit(executor.getCorePoolSize(), refinementLimit());
        while (true) {
            int active = activeSurfaceBuilds.get();
            if (active >= limit) return false;
            if (activeSurfaceBuilds.compareAndSet(active, active + 1)) {
                return true;
            }
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

    public PredictionLoadingProgress loadingProgress() { return loadingProgress; }

    private void refreshLoadingProgress(List<PredictionTileKey> leaves, double x, double z) {
        int covered = 0, near = 0, nearReady = 0, targetReady = 0, importantSurfaces = 0, surfacesReady = 0;
        for (var key : mediumCoverage.frontier()) {
            var tile = ready.get(key);
            if (tile != null && !tile.scopeOnly() && tile.cellAxis() >= 32) covered++;
        }
        double radius = PredictionDetailBands.fineRadius(layout.maxDistanceBlocks(), dimension);
        for (var key : leaves) {
            if (PredictionWorkOrder.distanceSquared(key, layout, x, z) > radius * radius) continue;
            near++;
            var tile = ready.get(key);
            int target = ordinaryTargets.getOrDefault(key, layout.cellAxis(key.lod()));
            boolean current = tile != null && !tile.scopeOnly() && !dirtyTiles.contains(key);
            if (current && tile.cellAxis() >= Math.min(64, target)) nearReady++;
            if (current && tile.cellAxis() >= target) targetReady++;
        }
        for (var key : surfaceDesired) {
            if (voxyOwnedTiles.contains(key) || !terrainLeaves.contains(key)) continue;
            importantSurfaces++;
            var tile = ready.get(key);
            if (tile != null && !tile.scopeOnly() && !dirtyTiles.contains(key)
                    && tile.cellAxis() >= ordinaryTargets.getOrDefault(key, layout.cellAxis(key.lod()))
                    && surfaceReady.contains(key)) surfacesReady++;
        }
        loadingProgress = new PredictionLoadingProgress(mediumCoverage.frontier().size(), covered,
                near, nearReady, near, targetReady, importantSurfaces, surfacesReady);
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
        enqueue(key, centerChunkX, centerChunkZ, surface, false);
    }

    private static PredictionDiskCache.Key diskKey(PredictionTileKey key) {
        return PredictionDiskCache.Key.terrain(key.tileX(), key.tileZ(), key.lod());
    }

    private synchronized void restoreCached(int centerChunkX, int centerChunkZ) {
        cachePending.retainAll(pending);
        while (!closed && !paused && cachePending.size() < CACHE_RESTORE_IN_FLIGHT && !cacheCandidates.isEmpty()) {
            PredictionTileKey key = cacheCandidates.removeFirst();
            if (effectivelyDesired(key)) enqueue(key, centerChunkX, centerChunkZ, false, true);
        }
    }

    private boolean cachedUpgrade(PredictionTileKey key) {
        PredictionTile tile = ready.get(key);
        Long retryAt = cacheMissUntil.get(key);
        return diskCache != null && (retryAt == null || System.nanoTime() - retryAt >= 0)
                && diskCache.cachedTerrainAxis(diskKey(key)) > (tile == null ? 0 : tile.cellAxis());
    }

    private synchronized void refillScope(int centerChunkX, int centerChunkZ) {
        scopePending.retainAll(pending);
        int limit = PredictionWorkOrder.detailBuildLimit(executor.getCorePoolSize(), false, true);
        while (!closed && !paused && buildFocus != null && scopePending.size() < limit && !scopeCandidates.isEmpty()) {
            BuildRequest request = scopeCandidates.removeFirst();
            if (desiredKeys.contains(request.key()) && PredictionWorkOrder.scoped(request.key(), layout, buildFocus))
                enqueue(request.key(), centerChunkX, centerChunkZ, request.surface());
        }
    }

    private synchronized void enqueue(PredictionTileKey key, int centerChunkX, int centerChunkZ,
                                      boolean surface, boolean cacheOnly) {
        if (voxyOwnedTiles.contains(key)) return;
        cachePending.retainAll(pending);
        if (cacheOnly && (cachePending.size() >= CACHE_RESTORE_IN_FLIGHT || !cachedUpgrade(key))) return;
        idlePending.retainAll(pending);
        boolean idle = !cacheOnly && !surface && idleWork(key);
        if (idle && (!idleAdmissionReady() || !idlePending.isEmpty())) return;
        if (dirtyTiles.contains(key) && ready.containsKey(key) && captureRefresh.defer(key, System.nanoTime())) {
            captureRefreshDeferrals.increment();
            return;
        }
        if (!cacheOnly && !fallbackKeys.contains(key) && !mediumWorkAllowed(key, surface)) return;
        if (surface && !surfaceBuildReady(key)) return;
        if (!cacheOnly && !surface && !terrainBuildNeeded(key)
                || closed || paused || memoryBudget.exhausted()) {
            return;
        }
        if (!cacheOnly && !surface && !terrainParentReady(key)) return;
        // Ingest is not render residency. Keep the selected detail instead
        // of replacing captured surfaces with enormous coarse fallback slabs.
        if (!retryReady(key)) return;
        if (pending.contains(key)) return;
        scopePending.retainAll(pending);
        boolean scoped = !cacheOnly && PredictionWorkOrder.scoped(key, layout, buildFocus);
        if (scoped && scopePending.size() >= PredictionWorkOrder.detailBuildLimit(executor.getCorePoolSize(), false, true)) return;
        backgroundPending.retainAll(pending);
        refinementPending.retainAll(pending);
        // The frame fuse sheds only ordinary refinement and background work;
        // scoped, dirty and medium-coverage builds always stay admitted.
        PredictionFramePace.ThrottleLevel throttle = PredictionFramePace.currentThrottle();
        boolean ordinary = !cacheOnly && ordinaryRefinement(key);
        PredictionTile current = ready.get(key);
        Integer ordinaryTarget = ordinaryTargets.get(key);
        // Finish current terrain/surfaces slowly under sustained pressure.
        // Optional idle upgrades still require healthy frames.
        boolean detailTrickle = ordinary && !idle && throttle == PredictionFramePace.ThrottleLevel.PAUSE
                && (surface || terrainLeaves.contains(key) && current != null && ordinaryTarget != null
                    && current.cellAxis() < ordinaryTarget)
                && cpuBudget.allowsDetailTrickle();
        long ordinaryPending = refinementPending.stream().filter(k -> !idlePending.contains(k)).count();
        if (ordinary && ordinaryPending >= (detailTrickle ? 1 : throttledRefinementLimit(throttle))) return;
        boolean background = !cacheOnly && backgroundWork(key);
        if (background && backgroundPending.size() >= (throttle == PredictionFramePace.ThrottleLevel.PAUSE
                ? (detailTrickle ? 1 : 0) : backgroundLimit())) return;
        failedAt.remove(key);
        deferredUntil.remove(key);
        int priority = dirtyTiles.contains(key) ? Integer.MIN_VALUE + 1 + key.lod()
                : cacheOnly ? -100 : workPriority(key, surface);
        double distance = PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus);
        VssLodLayout tileLayout = layout;
        // Reserve the shared CPU slot before publishing work to the executor.
        // The worker still performs the final lease check after it starts, since
        // generation pressure and frame pacing may change while this task waits
        // in the priority queue. A failed reservation gets a short backoff so
        // replanning cannot create a stream of immediately rejected workers.
        boolean urgent = !idle && (dirtyTiles.contains(key)
                || !surface && (ready.get(key) == null || mediumCoverageWork(key))
                || PredictionWorkOrder.scoped(key, tileLayout, buildFocus));
        boolean pausedDetail = detailTrickle || !cacheOnly && !idle
                && ordinaryRefinement(key)
                && PredictionFramePace.currentThrottle() == PredictionFramePace.ThrottleLevel.PAUSE;
        // A healthy frame can tolerate a small executor backlog. Reserve that
        // backlog separately from active CPU capacity so a one-worker pool can
        // stay fed while each worker still performs the final lease check. When
        // frame pacing or detail trickle is active, retain strict admission so
        // optional work cannot bypass the existing fuse.
        boolean allowQueuedAdmission = !pausedDetail
                && throttle == PredictionFramePace.ThrottleLevel.NONE;
        int queueLimit = Math.max(8, Math.min(32, executor.getCorePoolSize() * 4));
        ThreadPoolExecutor selectedExecutor = cacheOnly ? cacheExecutor : executor;
        PredictionCpuBudget.Reservation cpuReservation = cacheOnly ? null : allowQueuedAdmission
                ? cpuBudget.tryReserveQueued(urgent, pausedDetail, queueLimit)
                : cpuBudget.tryReserve(urgent, pausedDetail);
        if (!cacheOnly && cpuReservation == null) {
            admissionReasons[ADMIT_CPU_BUDGET].increment();
            // The budget admits one urgent handoff while a worker is active.
            // Further urgent candidates must back off too, or every planner
            // pass will retry the whole missing-coverage frontier.
            deferredUntil.put(key, System.nanoTime() + CPU_BUDGET_RETRY_NANOS);
            return;
        }
        if (!reserveQueuedBuild(pending, selectedExecutor.getQueue(), key, priority, distance, MAX_PENDING)) {
            if (cpuReservation != null) cpuReservation.close();
            return;
        }
        long revision = meshRevision.get();
        captureRefresh.started(key);
        if (background) backgroundPending.add(key);
        if (ordinary) refinementPending.add(key);
        if (idle) idlePending.add(key);
        if (cacheOnly) cachePending.add(key);
        if (scoped) scopePending.add(key);
        boolean scopePromoted = PredictionWorkOrder.scoped(key, tileLayout, buildFocus)
                && PredictionWorkOrder.distanceSquared(key, tileLayout, cameraBlockX, cameraBlockZ)
                    >= Math.pow(PredictionDetailBands.fineRadius(tileLayout.maxDistanceBlocks(), dimension), 2);
        int buildSurfaceSettings = surfaceSettings;
        var buildDensity = dev.xantha.vss.config.PredictionVegetationDensity.fromSettings(buildSurfaceSettings);
        int buildTargetAxis = targetCellAxis(key);
        try {
            // The lambda body runs when a worker picks the task up, so the gap
            // to `buildStartedNanos` is the time this tile spent queued. That is
            // the number that tells scheduling starvation apart from slow work.
            long enqueueNanos = System.nanoTime();
            long uploadGeneration = PredictionUploadStaging.SHARED.epoch();
            selectedExecutor.execute(new PredictionTask(key, priority, distance, surface, () -> {
            long buildStartedNanos = System.nanoTime();
            long waitNanos = buildStartedNanos - enqueueNanos;
            queueWaitNanos.add(waitNanos);
            maxQueueWaitNanos.accumulateAndGet(waitNanos, Math::max);
            PredictionMemoryBudget.Reservation reservation = null;
            PredictionCpuBudget.Lease cpuLease = null;
            PredictionDiskCache.Lease diskLease = null;
            PredictionPackedMesh stagedUpload = null;
            boolean surfaceSlot = false;
            boolean detailSlot = false;
            boolean continueSurface = false;
            boolean buildAdmitted = false;
            boolean captureDeferred = false;
            if (dirtyTiles.contains(key) && ready.containsKey(key)) {
                synchronized (this) {
                    captureDeferred = captureRefresh.defer(key, System.nanoTime());
                }
            }
            // New coverage avoids the planner lock. Unstarted jobs use the latest
            // capture epoch; every later stage still rejects in-flight changes.
            final long captureEpoch = captureEpochs.getOrDefault(key, 0L);
            activeBuildThreads.add(Thread.currentThread());
            try {
                // Convert the queued reservation into the worker's final
                // non-blocking lease check. close() is idempotent and also
                // covers cancellation paths which never reach this point.
                if (cpuReservation != null) cpuReservation.consume();
                if (captureDeferred) { captureRefreshDeferrals.increment(); return; }
                if (closed || paused || revision != meshRevision.get() || !effectivelyDesired(key)) {
                    admissionReasons[ADMIT_NOT_DESIRED].increment();
                    return;
                }
                if (idle && (!idleAdmissionReady() || !idleTargets.containsKey(key))) return;
                // A queued coverage/scope task may have become ordinary work.
                // Release it for bounded admission instead of filling the CPU.
                if (!cacheOnly && ordinaryRefinement(key) && !refinementPending.contains(key)) {
                    admissionReasons[ADMIT_RELEASED].increment();
                    return;
                }
                if (!cacheOnly && !fallbackKeys.contains(key) && !mediumWorkAllowed(key, surface)) {
                    admissionReasons[ADMIT_MEDIUM].increment();
                    return;
                }
                if (!cacheOnly && !surface && !terrainBuildNeeded(key)) {
                    admissionReasons[ADMIT_TERRAIN_NEEDED].increment();
                    return;
                }
                if (surface && !surfaceBuildReady(key)) {
                    admissionReasons[ADMIT_SURFACE_READY].increment();
                    return;
                }
                PredictionTile previousTerrain = surface ? ready.get(key) : null;
                if (surface && previousTerrain == null) {
                    admissionReasons[ADMIT_NO_PREVIOUS].increment();
                    return;
                }
                boolean workerUrgent = !idle && (dirtyTiles.contains(key)
                        || !surface && (ready.get(key) == null || mediumCoverageWork(key))
                        || PredictionWorkOrder.scoped(key, tileLayout, buildFocus));
                // Recheck a tier's frame fuse after queueing, and retain a trickle
                // reservation even if frames recovered before the worker started.
                boolean workerPausedDetail = detailTrickle || !cacheOnly && !idle
                        && ordinaryRefinement(key)
                        && PredictionFramePace.currentThrottle() == PredictionFramePace.ThrottleLevel.PAUSE;
                cpuLease = cacheOnly ? null : cpuBudget.tryAcquire(workerUrgent, workerPausedDetail);
                if (!cacheOnly && cpuLease == null) {
                    admissionReasons[ADMIT_CPU_BUDGET].increment();
                    // The task already waited in the executor queue. Let the
                    // next planner tick decide when to retry; adding another
                    // wall-clock backoff here would restart a detail delay
                    // that may have elapsed while this worker was waiting.
                    return;
                }
                if (surface) {
                    int span = tileLayout.tileBlocks(key.lod());
                    vegetationForBuild(buildSurfaceSettings).deferIfRenderingBusy(key.tileX() * span,
                            key.tileZ() * span, span, span / tileLayout.cellAxis(key.lod()),
                            () -> captureCurrent(key, captureEpoch, revision));
                }
                reservation = cacheOnly ? memoryBudget.tryReserve(PredictionMemoryBudget.BUILD_BYTES)
                        : memoryBudget.tryReserveBuild();
                if (reservation == null) {
                    admissionReasons[ADMIT_NO_RESERVATION].increment();
                    return;
                }
                PredictionTile resident = ready.get(key);
                // Do not reread/decompress the same warm terrain every retry
                // while resource identity still prevents finished-mesh restore.
                if (cacheOnly && PredictionMeshResources.pending()) {
                    cacheResourceDeferrals.increment();
                    deferredUntil.put(key, System.nanoTime() + 100_000_000L);
                    return;
                }
                long diskStartedNanos = System.nanoTime();
                diskLease = diskCache == null ? null : diskCache.lease(PredictionDiskCache.Key.terrain(key.tileX(), key.tileZ(), key.lod()));
                // Read a resident tile only for decoration. Re-reading the same
                // preview on every upgrade would prevent refinement forever.
                PredictionDiskCache.TerrainData cached = diskLease == null || resident != null && !surface && !cacheOnly ? null
                        : diskCache.readTerrainData(diskLease, 0);
                diskReadNanos.add(System.nanoTime() - diskStartedNanos);
                requireCaptureCurrent(key, captureEpoch, revision);
                if (cached != null && (cached.cellAxis() < 1 || cached.cellAxis() > tileLayout.cellAxis(key.lod())
                        || (cached.cellAxis() & (cached.cellAxis()-1)) != 0
                        || (cached.cellAxis()+2)*(cached.cellAxis()+2) != cached.samples().length
                        || resident != null && cached.cellAxis() < resident.cellAxis())) cached=null;
                if (cached != null && surface && java.util.Arrays.stream(cached.samples())
                        .anyMatch(sample -> !sample.reusableFor(false))) cached=null;
                if (cacheOnly && (cached == null || resident != null && cached.cellAxis() <= resident.cellAxis())) {
                    diskCache.forgetTerrain(diskKey(key));
                    return;
                }
                // A warm terrain record may have a finished mesh, but its
                // resource identity is computed asynchronously after login.
                // Do not spend a full colour/vegetation/mesh rebuild merely
                // because the scan has not finished; the next planner tick
                // retries this bounded cache slot. A missing/failed scan still
                // falls through to the normal build path.
                if (cacheOnly && cached != null && PredictionMeshResources.pending()) {
                    cacheResourceDeferrals.increment();
                    deferredUntil.put(key, System.nanoTime() + 100_000_000L);
                    return;
                }
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
                    if (!detailSlot) {
                        admissionReasons[ADMIT_NO_DETAIL_SLOT].increment();
                        return;
                    }
                }
                if (surface) {
                    surfaceSlot = tryReserveSurfaceBuild();
                    if (!surfaceSlot) {
                        admissionReasons[ADMIT_NO_SURFACE_SLOT].increment();
                        return;
                    }
                }
                if (surface && !PredictionWorkOrder.scoped(key, tileLayout, buildFocus)) mediumSinceSurface.set(0);
                buildAdmitted = true;
                boolean preview = cellAxis < tileLayout.cellAxis(key.lod());
                int gridSize = cellAxis + VssLodLayout.SAMPLE_MARGIN * 2;
                int stepBlocks = tileLayout.tileBlocks(key.lod()) / cellAxis;
                int baseBlockX = key.tileX() * (key.lod() < tileLayout.levelCount()
                        ? tileLayout.tileBlocks(key.lod()) : PredictionTileManager.TILE_CHUNKS << key.lod());
                int baseBlockZ = key.tileZ() * tileLayout.tileBlocks(key.lod());
                long colorFingerprint = sampler.colorCacheFingerprint();
                boolean cachedColors = cached != null && cached.colorsMatch(colorFingerprint);
                byte[] resources = PredictionMeshResources.ready();
                if (stepBlocks <= 16 && VSSClientConfig.CONFIG.predictionStructures)
                    cityHints.observeArea(baseBlockX - 16, baseBlockZ - 16,
                        tileLayout.tileBlocks(key.lod()) + 32);
                LostCityHints.Snapshot citySnapshot = stepBlocks <= 16 && VSSClientConfig.CONFIG.predictionStructures
                        ? cityHints.cacheSnapshot(baseBlockX, baseBlockZ, tileLayout.tileBlocks(key.lod()))
                        : new LostCityHints.Snapshot(null, true);
                dev.xantha.vss.common.worldgen.LostCityPreview.Tile cityBuildings = citySnapshot.buildings();
                int meshSettings = buildSurfaceSettings;
                byte[] baseMeshIdentity = !surface && diskLease != null && cachedColors && resources != null
                        ? PredictionMeshCodec.baseSignature(resources, cached.samples(), cached.surfaceTints(),
                                cached.foliageTints(), cached.waterTints(), sampler.seaLevel(), sampler.fluidColor(),
                                stepBlocks, tileLayout.trees(), meshSettings) : null;
                byte[] rawBaseIdentity = baseMeshIdentity;
                baseMeshIdentity = PredictionMeshCodec.withCityBuildings(baseMeshIdentity, cityBuildings);
                // A warm terrain record may already have its complete packed
                // mesh. Restore it before allocating colour arrays or replaying
                // any vegetation; this is the fast cache path.
                if (!surface && cached != null && cachedColors && baseMeshIdentity != null) {
                    var meshRecord = diskCache.readMeshBaseRecord(diskLease, baseMeshIdentity, cellAxis,
                            citySnapshot.tile() == null ? null : rawBaseIdentity,
                            stored -> cityHints.agrees(stored, baseBlockX, baseBlockZ, tileLayout.tileBlocks(key.lod())));
                    if (meshRecord != null) {
                        PredictionMesh restoredMesh = meshRecord.mesh();
                        requireCaptureCurrent(key, captureEpoch, revision);
                        PredictionTile restored = restoreFinishedMesh(key, tileLayout, stepBlocks,
                                cached.samples(), restoredMesh, scopePromoted, resident,
                                meshRecord.cities() == null ? cityBuildings : PredictionCityMeshCache.buildings(meshRecord.cities()));
                        restoredMesh.gpuPayload().prepareGpuStorage();
                        stagedUpload = restoredMesh.gpuPayload();
                        stagedUpload.prepareUpload(uploadGeneration);
                        if (VSSClientConfig.CONFIG.predictionCompressMeshes) restoredMesh.gpuPayload().prepareStorage();
                        requireCaptureCurrent(key, captureEpoch, revision);
                        boolean restoredSurface = meshRecord.surfaceCompleted()
                                && cellAxis == tileLayout.cellAxis(key.lod()) && !restored.scopeOnly();
                        var proof = meshRecord.cities() == null ? null : PredictionCityMeshCache.Proof.of(meshRecord.cities());
                        synchronized (PredictionTileManager.this) {
                            if (publishTile(restored, reservation, revision, captureEpoch, restoredSurface, false,
                                    diskLease.valid(), PredictionRelief.of(restored))) {
                                if (proof == null) restoredCityProofs.remove(key); else restoredCityProofs.put(key, proof);
                                reservation = null;
                                finishedMeshRestores.increment();
                                diskHitBuilds.increment();
                                builtTiles.incrementAndGet();
                                continueSurface = surfaceBuildReady(key);
                            }
                        }
                        // Upgrade a successfully validated legacy city record once. The next
                        // entry can restore it before network hints, without rebuilding terrain.
                        if (reservation == null && meshRecord.cities() == null && citySnapshot.complete()
                                && citySnapshot.tile() != null && diskLease.valid())
                            diskCache.writeMeshLater(diskLease, meshRecord.fullIdentity(), restoredMesh,
                                    meshRecord.baseIdentity(), true, meshRecord.surfaceCompleted(),
                                    rawBaseIdentity, citySnapshot.tile());
                        return;
                    }
                }
                if (cacheOnly) {
                    // A cache miss must never turn this independent lane into a
                    // density/feature generator. Normal work handles the rebuild.
                    cacheFastMisses.increment();
                    cacheMissUntil.put(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                    return;
                }
                restoredCityProofs.remove(key);
                int[] heights = new int[gridSize * gridSize];
                int[] materialColors = new int[gridSize * gridSize];
                int[] foliageColors = new int[gridSize * gridSize];
                int[] waterColors = new int[gridSize * gridSize];
                int[] surfaceTints = new int[gridSize * gridSize];
                int[] waterTints = new int[gridSize * gridSize];
                ClientColumnSample[] samples = new ClientColumnSample[gridSize * gridSize];
                ClientColumnSample[] capturedOverrides = null;
                // Bounded native batches evaluate the grid; the Java fallback
                // samples the exterior surface without underground spans.
                long sampleStartedNanos = System.nanoTime();
                if (batch == null) {
                    PredictionTile reusable = dirtyTiles.contains(key) ? previousTerrain : resident;
                    for (int z = 0; z < gridSize; z++) for (int x = 0; x < gridSize; x++) {
                        int blockX = baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int blockZ = baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        ClientColumnSample captured = cachedSample(blockX, blockZ);
                        if (captured != null && captured.captured()) {
                            if (capturedOverrides == null) capturedOverrides = new ClientColumnSample[samples.length];
                            capturedOverrides[z * gridSize + x] = captured;
                        }
                        ClientColumnSample retained = captured != null ? null
                                : retainedSample(reusable, x, z, stepBlocks, preview && !surface, !preview && !surface);
                        if (captured != null || retained != null) reusedPointBuilds.increment();
                        samples[z * gridSize + x] = captured != null ? captured : retained;
                    }
                    batch = sampleGridFast(baseBlockX, baseBlockZ, stepBlocks, gridSize, samples,
                            preview && !surface, !preview && !surface,
                            () -> captureCurrent(key, captureEpoch, revision));
                }
                requireCaptureCurrent(key, captureEpoch, revision);
                nativeSampleNanos.add(System.nanoTime() - sampleStartedNanos);
                if (diskHit) diskHitBuilds.increment(); else diskMissBuilds.increment();
                long colorStartedNanos = System.nanoTime();
                var retainedColors = terrainColors.get(key, revision, captureEpoch, colorFingerprint);
                for (int dz = 0; dz < gridSize; dz++) {
                    if (captureEpoch != captureEpochs.getOrDefault(key, 0L)) { captureEarlyExits.increment(); return; }
                    if (closed || paused || revision != meshRevision.get()
                            || Thread.currentThread().isInterrupted() || !effectivelyDesired(key)) return;
                    for (int dx = 0; dx < gridSize; dx++) {
                        int blockX = baseBlockX
                                + (dx - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int blockZ = baseBlockZ
                                + (dz - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int sampleIndex = dz * gridSize + dx;
                        long resolveStarted = System.nanoTime();
                        // Captures are protected by captureEpoch; predicted columns can improve concurrently.
                        ClientColumnSample sample = sampler.interiorTerrain() ? null
                                : !diskHit && capturedOverrides != null && capturedOverrides[sampleIndex] != null
                                        ? capturedOverrides[sampleIndex] : cachedSample(blockX, blockZ);
                        if (sample == null && previousTerrain != null && dx >= 1 && dz >= 1) {
                            sample = previousTerrain.samples()[(dz - 1) * (cellAxis + 1) + dx - 1];
                            if (!sample.reusableFor(false) || sampler.interiorTerrain() && sample.volume() == null) sample = null;
                        }
                        if (sample == null) sample = batch != null ? batch[sampleIndex] : samples[sampleIndex];
                        // The native grid misses are rare; fill individual
                        // holes through the per-column path rather than
                        // dropping the tile.
                        if (sample == null) {
                            colorFallbackPoints.increment();
                            sample = sampleAt(blockX, blockZ, key.lod(), stepBlocks);
                        }
                        samples[sampleIndex] = sample;
                        heights[sampleIndex] = sample.surfaceY();
                        // sample.surfaceY is the solid boundary; fluidY is
                        // stored separately, so no second density traversal
                        // is needed for the terrain mesh.
                        if (PredictionExteriorColumns.interiorVolume(sample)) {
                            materialColors[sampleIndex] = PredictionMaterialPalette.colorFor(sample, 0);
                            continue;
                        }
                        long tintStarted = System.nanoTime();
                        colorResolveNanos.add(tintStarted - resolveStarted);
                        boolean reuseColors = cachedColors && sample.equals(batch[sampleIndex]);
                        int retainedColor = retainedColors == null ? -1 : retainedColors.match(dx, dz, stepBlocks, sample);
                        if (!reuseColors && retainedColor >= 0) reusedColorPoints.increment();
                        surfaceTints[sampleIndex] = reuseColors ? cached.surfaceTints()[sampleIndex]
                                : retainedColor >= 0 ? retainedColors.surface[retainedColor]
                                : sampler.surfaceColorForLod(blockX, sample.surfaceY(), blockZ, sample.approximate());
                        int baseColor = PredictionMaterialPalette.colorFor(sample, surfaceTints[sampleIndex]);
                        materialColors[sampleIndex] = PredictionLighting.shade(
                                baseColor,
                                 sample.surfaceY(), sampler.seaLevel(), false,
                                sample.fluid() != 0);
                        // Feature stamps tint their leaves with the column's
                        // own biome instead of the registry's spawn tint.
                        foliageColors[sampleIndex] = reuseColors ? cached.foliageTints()[sampleIndex]
                                : retainedColor >= 0 ? retainedColors.foliage[retainedColor]
                                : sampler.foliageColorForLod(blockX, sample.surfaceY(), blockZ, sample.approximate());
                        int forestTint = vegetation.forestTint(sample, blockX, blockZ, stepBlocks,
                                surfaceTints[sampleIndex], foliageColors[sampleIndex]);
                        if (forestTint != surfaceTints[sampleIndex]) materialColors[sampleIndex] = PredictionLighting.shade(
                                PredictionMaterialPalette.colorFor(sample, forestTint), sample.surfaceY(), sampler.seaLevel(), false, false);
                        if (sample.fluid() == 1 && !sample.ice()) {
                            waterTints[sampleIndex] = reuseColors ? cached.waterTints()[sampleIndex]
                                    : retainedColors != null && retainedColors.waterMatches(retainedColor, sample)
                                            ? retainedColors.water[retainedColor]
                                    : sampler.waterTintForLod(blockX, sample.fluidY(), blockZ, sample.approximate());
                            waterColors[sampleIndex] = PredictionMaterialPalette.waterColor(waterTints[sampleIndex]);
                        }
                        colorTintNanos.add(System.nanoTime() - tintStarted);
                    }
                }
                requireCaptureCurrent(key, captureEpoch, revision);
                if (!sampler.interiorTerrain()) terrainColors.put(key, revision, captureEpoch, colorFingerprint, gridSize, stepBlocks,
                        samples, surfaceTints, foliageColors, waterTints);
                colorNanos.add(System.nanoTime() - colorStartedNanos);
                long exteriorStartedNanos = System.nanoTime();
                int exteriorUpdates = PredictionExteriorColumns.enrich(samples, gridSize, stepBlocks,
                        baseBlockX, baseBlockZ, sampler, () -> !closed && !paused
                                && revision == meshRevision.get() && effectivelyDesired(key)
                                && captureEpoch == captureEpochs.getOrDefault(key, 0L));
                exteriorNanos.add(System.nanoTime() - exteriorStartedNanos);
                requireCaptureCurrent(key, captureEpoch, revision);
                // Every published sampling stage survives a restart. In-memory
                // refinement is monotonic; a restored grid also prevents a
                // smaller preview from overwriting the best stored result.
                if (captureEpoch != captureEpochs.getOrDefault(key, 0L)) { captureEarlyExits.increment(); return; }
                requireCaptureCurrent(key, captureEpoch, revision);
                long commitStartedNanos = System.nanoTime();
                java.util.concurrent.CompletableFuture<Boolean> persisted = diskLease == null
                        ? java.util.concurrent.CompletableFuture.completedFuture(false)
                        : diskHit && exteriorUpdates == 0 && (cachedColors || colorFingerprint == Long.MIN_VALUE)
                                ? java.util.concurrent.CompletableFuture.completedFuture(true)
                                : diskCache.writeTerrainLater(diskLease, new PredictionDiskCache.TerrainData(
                                        samples, colorFingerprint, surfaceTints, foliageColors, waterTints));
                boolean cacheAccepted = !persisted.isDone() || persisted.getNow(false);
                commitNanos.add(System.nanoTime() - commitStartedNanos);
                long surfaceStarted = System.nanoTime();
                samplingNanos.add(surfaceStarted - buildStartedNanos);
                // Persist the raw terrain/tint identity alongside the full geometry identity.
                // Warm restores use it before gathering decoration sources; the full
                // identity remains needed when a build could not take that early path.
                baseMeshIdentity = diskLease != null && resources != null
                        ? PredictionMeshCodec.baseSignature(resources, samples, surfaceTints, foliageColors, waterTints,
                                sampler.seaLevel(), sampler.fluidColor(), stepBlocks, tileLayout.trees(),
                                meshSettings) : null;
                rawBaseIdentity = baseMeshIdentity;
                baseMeshIdentity = PredictionMeshCodec.withCityBuildings(baseMeshIdentity, cityBuildings);
                requireCaptureCurrent(key, captureEpoch, revision);
                PredictionVegetation.Tile plants = !surface ? vegetation.cachedDisplayForRendering(baseBlockX, baseBlockZ,
                        tileLayout.tileBlocks(key.lod()), stepBlocks,
                        editedCells::contains, buildDensity) : vegetationForBuild(buildSurfaceSettings).tileForRendering(baseBlockX, baseBlockZ,
                        tileLayout.tileBlocks(key.lod()), stepBlocks, tileLayout.trees(),
                        editedCells::contains, () -> captureCurrent(key, captureEpoch, revision), buildDensity);
                if (captureEpoch != captureEpochs.getOrDefault(key, 0L)) { captureEarlyExits.increment(); return; }
                requireCaptureCurrent(key, captureEpoch, revision);
                long meshStarted = System.nanoTime();
                decorationNanos.add(meshStarted - surfaceStarted);
                PredictionSimpleVegetation.Result simple = !surface && tileLayout.trees()
                        ? PredictionVegetationSelection.select(simpleVegetation.build(samples, gridSize, stepBlocks, baseBlockX, baseBlockZ,
                                surfaceTints, foliageColors, plants, (x,z) -> vegetation.hasCachedChunk(x,z)
                                        || editedCells.contains(pack(Math.floorDiv(x,16),Math.floorDiv(z,16)))),
                                baseBlockX, baseBlockZ, buildDensity)
                        : PredictionSimpleVegetation.Result.EMPTY;
                requireCaptureCurrent(key, captureEpoch, revision);
                if (simple.forestTints() != null) for (int i = 0; i < samples.length; i++) {
                    if (simple.forestTints()[i] != surfaceTints[i]) materialColors[i] = PredictionLighting.shade(
                            PredictionMaterialPalette.colorFor(samples[i], simple.forestTints()[i]),
                            samples[i].surfaceY(), sampler.seaLevel(), false, false);
                }
                // Key the finished geometry by the actual immutable inputs, including captured edits,
                // decoration, current colours and resources. View masks and morphs are deliberately excluded.
                byte[] meshIdentity = diskLease == null ? null : PredictionMeshCodec.signature(
                        resources, samples, materialColors, foliageColors, waterColors,
                        sampler.seaLevel(), sampler.fluidColor(), stepBlocks, tileLayout.trees(),
                        meshSettings, plants, simple);
                meshIdentity = PredictionMeshCodec.withCityBuildings(meshIdentity, cityBuildings);
                PredictionMesh mesh = diskLease == null ? null : diskCache.readMesh(diskLease, meshIdentity, cellAxis);
                boolean finishedMeshHit = mesh != null;
                if (mesh == null) mesh = PredictionVegetation.meshWithinBudget(plants,
                        tileLayout.tileBlocks(key.lod()), stepBlocks, meshPlants -> PredictionMeshBuilder.buildForRenderingWithCityPreview(samples, materialColors,
                        sampler.seaLevel(), sampler.fluidColor(), stepBlocks, gridSize,
                        foliageColors, waterColors,
                        baseBlockX, baseBlockZ, meshPlants, simple, cityBuildings,
                        () -> captureCurrent(key, captureEpoch, revision)));
                requireCaptureCurrent(key, captureEpoch, revision);
                int meshGridSize = cellAxis + 1;
                ClientColumnSample[] meshSamples = cropMargin(samples, gridSize, meshGridSize);
                int[] meshHeights = cropMargin(heights, gridSize, meshGridSize);
                if (cityBuildings != null) {
                    meshSamples = PredictionCityGeometry.ground(meshSamples, cityBuildings, baseBlockX, baseBlockZ,
                            stepBlocks, meshGridSize, null).samples();
                    for (int i = 0; i < meshHeights.length; i++) meshHeights[i] = meshSamples[i].surfaceY();
                }
                int[] meshGroundHeights = meshHeights;
                // Materialise the packed/greedy quad buffer on the worker so
                // the first render frame never pays the conversion cost.
                mesh = mesh.compactForRendering();
                mesh.retainedSampleObjects = PredictionSampleCompaction.compact(meshSamples);
                mesh.retainedVolumeBytes = PredictionSampleCompaction.volumeBytes(meshSamples);
                requireCaptureCurrent(key, captureEpoch, revision);
                PredictionDepthBound surfaceBounds = finishedMeshHit
                        ? new PredictionDepthBound(mesh.gpuPayload().morphMinY(), mesh.gpuPayload().morphMaxY())
                        : PredictionDepthBound.fromSamples(samples);
                PredictionTile completed = new PredictionTile(key, meshHeights, meshGroundHeights,
                        meshSamples, mesh, LostCityHints.includeBuildings(new PredictionDepthBound(surfaceBounds.minY(),
                        Math.max(Math.max(surfaceBounds.maxY(), plants.maxY()), simple.maxY())), cityBuildings),
                        System.nanoTime(), meshIds.incrementAndGet(), mesh.cellAxis(), stepBlocks,
                        // Refining an ordinary resident must not revoke its ordinary coverage.
                        scopePromoted && (resident == null || resident.scopeOnly()));
                long packStarted = System.nanoTime();
                meshingNanos.add(packStarted - meshStarted);
                if (plants.blocks().isEmpty() && simple.forms().isEmpty()
                        && cityBuildings == null && !scopePromoted) {
                    PredictionTile parent = resident;
                    if (parent == null && key.lod()+1 < tileLayout.levelCount())
                        parent = ready.get(new PredictionTileKey(key.dimension(), key.tileX()>>1, key.tileZ()>>1, key.lod()+1));
                    mesh.morph(PredictionMorph.field(completed, parent));
                }
                mesh.prepareGpuPayload(completed);
                mesh.gpuPayload().prepareGpuStorage();
                stagedUpload = mesh.gpuPayload();
                stagedUpload.prepareUpload(uploadGeneration);
                if (VSSClientConfig.CONFIG.predictionCompressMeshes) mesh.gpuPayload().prepareStorage();
                requireCaptureCurrent(key, captureEpoch, revision);
                packingNanos.add(System.nanoTime() - packStarted);
                if (publishTile(completed, reservation, revision, captureEpoch, surface, surface,
                        persisted.getNow(false) && diskLease.valid(), PredictionRelief.of(completed))) {
                    reservation = null;
                    long completedRevision = completed.revision();
                    persisted.thenAccept(written -> {
                        if (written) terrainStored(key, completedRevision, revision, captureEpoch);
                    });
                    boolean completedSurface = surface && cellAxis == tileLayout.cellAxis(key.lod()) && !completed.scopeOnly();
                    if ((!finishedMeshHit || completedSurface) && cacheAccepted && diskLease.valid() && meshIdentity != null
                            && baseMeshIdentity != null && citySnapshot.complete())
                        diskCache.writeMeshLater(diskLease, meshIdentity, mesh, baseMeshIdentity, true,
                                completedSurface, rawBaseIdentity, citySnapshot.tile());
                    if (!surface && cellAxis >= PredictionWorkOrder.PREVIEW_CELL_AXIS
                            && (terrainLeaves.contains(key) || mediumCoverage.frontier().contains(key))
                            && (resident == null || resident.cellAxis() < PredictionWorkOrder.PREVIEW_CELL_AXIS)) {
                        mediumSinceSurface.updateAndGet(count -> Math.min(MEDIUM_BUILDS_PER_SURFACE_TURN, count + 1));
                    }
                    continueSurface = !surface && surfaceBuildReady(key);
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
                        && !Thread.currentThread().isInterrupted() && effectivelyDesired(key)
                        && captureEpoch == captureEpochs.getOrDefault(key, 0L)) {
                    failedAt.put(key, System.nanoTime());
                }
            } catch (PredictionMemoryBudget.MeshLimitException limit) {
                // New data/settings/targets retry; unchanged geometry does not replay worldgen.
                if (!closed && revision == meshRevision.get()
                        && captureEpoch == captureEpochs.getOrDefault(key, 0L))
                    meshLimits.put(key, new PredictionMeshFailure(revision, captureEpoch, buildTargetAxis, buildSurfaceSettings));
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
                // The reservation is consumed before the final lease check;
                // close is therefore a no-op for normal execution and still
                // releases a queued slot when a task is cancelled early.
                if (cpuReservation != null) cpuReservation.close();
                if (captureEpoch != captureEpochs.getOrDefault(key, 0L))
                    captureDiscardNanos.add(System.nanoTime() - buildStartedNanos);
                if (diskLease != null) diskLease.close();
                activeBuildThreads.remove(Thread.currentThread());
                if (surfaceSlot) activeSurfaceBuilds.decrementAndGet();
                if (detailSlot) activeDetailBuilds.decrementAndGet();
                if (reservation != null) reservation.close();
                if (reservation != null && stagedUpload != null) PredictionUploadStaging.SHARED.discard(stagedUpload);
                if (cpuLease != null) cpuLease.close();
                backgroundPending.remove(key);
                idlePending.remove(key);
                refinementPending.remove(key);
                cachePending.remove(key);
                scopePending.remove(key);
                pending.remove(key);
                if (cacheOnly) restoreCached(centerChunkX, centerChunkZ);
                // Refill after actual work, not after a failed permit: draining
                // rejected requests would recreate the telescope CPU burst.
                if (buildAdmitted) refillScope(centerChunkX, centerChunkZ);
                // A finished fine tile need not wait for the next five-tick
                // planner pass to request its surface. All normal admission,
                // priority, memory and lifecycle checks still apply.
                if (continueSurface && !closed && !paused && revision == meshRevision.get()
                        && captureEpoch == captureEpochs.getOrDefault(key, 0L))
                    enqueue(key, centerChunkX, centerChunkZ, true);
            }
            }, cpuReservation));
        } catch (RejectedExecutionException rejected) {
            if (cpuReservation != null) cpuReservation.close();
            idlePending.remove(key);
            backgroundPending.remove(key);
            refinementPending.remove(key);
            cachePending.remove(key);
            scopePending.remove(key);
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
            worst.releaseAdmission();
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

    /**
     * Rebuilds the small residency wrapper around a decoded finished mesh.
     * The mesh payload already contains its exact height bounds and seam
     * summary; only the sampled columns needed by coverage and morphing have
     * to be retained by the tile manager.
     */
    private PredictionTile restoreFinishedMesh(PredictionTileKey key, VssLodLayout tileLayout,
                                               int stepBlocks, ClientColumnSample[] cachedSamples,
                                               PredictionMesh mesh, boolean scopePromoted,
                                               PredictionTile resident, dev.xantha.vss.common.worldgen.LostCityPreview.Tile cityBuildings) {
        int sourceGridSize = (int) Math.sqrt(cachedSamples.length);
        int cellAxis = sourceGridSize - VssLodLayout.SAMPLE_MARGIN * 2;
        if (sourceGridSize * sourceGridSize != cachedSamples.length
                || cellAxis < 1 || mesh.cellAxis() != cellAxis
                || stepBlocks <= 0) {
            throw new IllegalArgumentException("invalid finished mesh cache grid");
        }
        ClientColumnSample[] meshSamples = cropMargin(cachedSamples, sourceGridSize, cellAxis + 1);
        meshSamples = PredictionCityGeometry.ground(meshSamples, cityBuildings,
                key.tileX() * tileLayout.tileBlocks(key.lod()), key.tileZ() * tileLayout.tileBlocks(key.lod()),
                stepBlocks, cellAxis + 1, null).samples();
        int[] heights = new int[cachedSamples.length];
        for (int i = 0; i < cachedSamples.length; i++) {
            int height = cachedSamples[i].surfaceY();
            heights[i] = height;
        }
        int[] meshHeights = cropMargin(heights, sourceGridSize, cellAxis + 1);
        if (cityBuildings != null)
            for (int i = 0; i < meshHeights.length; i++) meshHeights[i] = meshSamples[i].surfaceY();
        int[] meshGroundHeights = meshHeights;
        mesh.retainedSampleObjects = PredictionSampleCompaction.compact(meshSamples);
        mesh.retainedVolumeBytes = PredictionSampleCompaction.volumeBytes(meshSamples);
        int minY = mesh.gpuPayload().morphMinY();
        int maxY = mesh.gpuPayload().morphMaxY();
        if (minY > maxY) {
            PredictionDepthBound bounds = PredictionDepthBound.fromSamples(cachedSamples);
            minY = bounds.minY();
            maxY = bounds.maxY();
        }
        boolean promoted = scopePromoted && (resident == null || resident.scopeOnly());
        return new PredictionTile(key, meshHeights, meshGroundHeights, meshSamples, mesh,
                new PredictionDepthBound(minY, maxY), System.nanoTime(), meshIds.incrementAndGet(),
                cellAxis, stepBlocks, promoted);
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
        return retainedSample(resident,x,z,step,preview,false);
    }

    static ClientColumnSample retainedSample(PredictionTile resident, int x, int z, int step, boolean preview, boolean display) {
        if (resident == null || resident.spacingBlocks() <= 0 || resident.samples() == null) return null;
        int localX = (x - VssLodLayout.SAMPLE_MARGIN) * step;
        int localZ = (z - VssLodLayout.SAMPLE_MARGIN) * step;
        int oldStep = resident.spacingBlocks(), axis = resident.cellAxis() + 1;
        if (localX < 0 || localZ < 0 || localX % oldStep != 0 || localZ % oldStep != 0
                || localX / oldStep >= axis || localZ / oldStep >= axis
                || resident.samples().length != axis * axis) return null;
        ClientColumnSample sample = resident.samples()[localZ / oldStep * axis + localX / oldStep];
        return sample != null && (display ? sample.reusableForDisplay() : sample.reusableFor(preview)) ? sample : null;
    }

    /**
     * Compatibility entry point for the colour-aware build path.  Sampling is
     * deliberately kept independent from tint resolution: the arrays are
     * populated by the following colour stage, while this method preserves
     * the existing Rust/Java sampling and cancellation behavior.
     */
    private ClientColumnSample[] sampleGridFast(int baseBlockX, int baseBlockZ,
                                                int stepBlocks, int gridSize, ClientColumnSample[] samples,
                                                boolean preview, boolean display,
                                                int[] surfaceTints, int[] foliageColors, int[] waterTints,
                                                boolean[] adaptiveColorHints,
                                                java.util.function.BooleanSupplier captureCurrent) {
        return sampleGridFast(baseBlockX, baseBlockZ, stepBlocks, gridSize, samples,
                preview, display, captureCurrent);
    }

    private ClientColumnSample[] sampleGridFast(int baseBlockX, int baseBlockZ,
                                                int stepBlocks, int gridSize, ClientColumnSample[] samples, boolean preview, boolean display,
                                                java.util.function.BooleanSupplier captureCurrent) {
        if (sampler.interiorTerrain()) {
            long revision = meshRevision.get();
            for (int z = 0; z < gridSize; z++) for (int x = 0; x < gridSize; x++) {
                if (!captureCurrent.getAsBoolean()) return samples;
                if (closed || paused || revision != meshRevision.get() || Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                int i = z * gridSize + x;
                // A captured height alone cannot replace the entire underground column.
                if (samples[i] == null || samples[i].volume() == null)
                    samples[i] = sampler.sampleInterior(baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks,
                            baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks);
            }
            return samples;
        }
        if (!(sampler instanceof RustTerrainSampler rust)) {
            if (display && stepBlocks >= 4 && !strictDisplaySampling()) {
                return sampleJavaDisplayGrid(baseBlockX, baseBlockZ, stepBlocks, gridSize, samples,
                        captureCurrent);
            }
            if (!preview) return samples;
            long revision = meshRevision.get();
            for (int z = 0; z < gridSize; z++) for (int x = 0; x < gridSize; x++) {
                if (!captureCurrent.getAsBoolean()) return samples;
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
                if (!captureCurrent.getAsBoolean()) return samples;
                if (closed || paused || revision != meshRevision.get() || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException();
                }
                int width = Math.min(8, gridSize - x), height = Math.min(8, gridSize - z);
                int originX = baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                int originZ = baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                if (display) rust.sampleDisplayGridInPlace(originX, originZ, stepBlocks,
                        width, height, samples, z * gridSize + x, gridSize);
                else rust.sampleGridInPlace(originX, originZ, stepBlocks,
                        width, height, samples, z * gridSize + x, gridSize, preview);
            }
        }
        return samples;
    }

    private static boolean strictDisplaySampling() {
        return Boolean.getBoolean("vss.strictDisplay")
                || System.getenv("VSS_STRICT_DISPLAY") != null;
    }

    /**
     * Java backends do not have the native display envelope.  Apply the same
     * conservative spatial reduction to their distant exterior grids while
     * keeping preview, near and interior paths unchanged.
     */
    private ClientColumnSample[] sampleJavaDisplayGrid(int baseBlockX, int baseBlockZ,
                                                        int stepBlocks, int gridSize,
                                                        ClientColumnSample[] samples,
                                                        java.util.function.BooleanSupplier captureCurrent) {
        long revision = meshRevision.get();
        for (int z = 0; z < gridSize; z += 8) {
            for (int x = 0; x < gridSize; x += 8) {
                if (!captureCurrent.getAsBoolean()) return samples;
                if (closed || paused || revision != meshRevision.get()
                        || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException();
                }
                int width = Math.min(8, gridSize - x);
                int height = Math.min(8, gridSize - z);
                ClientColumnSample[] retained = new ClientColumnSample[width * height];
                for (int row = 0; row < height; row++) {
                    System.arraycopy(samples, (z + row) * gridSize + x, retained, row * width, width);
                }
                final int patchX = x;
                final int patchZ = z;
                ClientColumnSample[] batch = PredictionAdaptiveDisplayGrid.sample(stepBlocks, retained,
                        index -> {
                            if (!captureCurrent.getAsBoolean()
                                    || closed || paused || revision != meshRevision.get()
                                    || Thread.currentThread().isInterrupted()) {
                                throw new java.util.concurrent.CancellationException();
                            }
                            int localX = index % width;
                            int localZ = index / width;
                            ClientColumnSample existing = retained[index];
                            if (existing != null && !existing.approximate()
                                    && (existing.flags() & ClientColumnSample.FLAG_DISPLAY) == 0
                                    && !existing.captured()) return existing;
                            return sampler.sampleForLod(
                                    baseBlockX + (patchX + localX - VssLodLayout.SAMPLE_MARGIN) * stepBlocks,
                                    baseBlockZ + (patchZ + localZ - VssLodLayout.SAMPLE_MARGIN) * stepBlocks,
                                    stepBlocks);
                        });
                if (width == 8 && height == 8) {
                    boolean accepted = java.util.Arrays.stream(batch)
                            .allMatch(value -> value != null
                                    && (value.flags() & ClientColumnSample.FLAG_DISPLAY) != 0);
                    if (accepted) {
                        adaptiveDisplayAccepted.increment();
                        adaptiveDisplayFilled.add(28);
                    } else {
                        adaptiveDisplayRejected.increment();
                    }
                }
                for (int row = 0; row < height; row++) {
                    System.arraycopy(batch, row * width, samples, (z + row) * gridSize + x, width);
                }
            }
        }
        return samples;
    }

    private ClientColumnSample sampleAt(int blockX, int blockZ, int lod, int stepBlocks) {
        if (sampler.interiorTerrain()) return sampler.sampleInterior(blockX, blockZ);
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
        ClientColumnSample sample = sampler.interiorTerrain() ? null : cachedSample(blockX, blockZ);
        if (sample == null) {
            sample = sampler.sampleSurface(blockX, blockZ);
            sampleCache.put(sampleKey, sample);
            // Prediction grids persist in compressed tile files. The small
            // legacy sample store now contains authoritative captures only.
        }
        return sample;
    }

    private void pruneReady(int centerChunkX, int centerChunkZ) {
        // Keep cached fine work across focus changes. Render selection can
        // return to a medium ancestor without deleting these samples/meshes;
        // memory pressure and persisted cold retirement still reclaim them.
        retireCovered(centerChunkX, centerChunkZ);
        if (diskCache != null) {
            long now = System.nanoTime();
            for (PredictionTile tile : List.copyOf(ready.values())) {
                long lastUse = Math.max(tile.generatedAtNanos(), desiredGrace.getOrDefault(tile.key(), 0L));
                boolean outside = beyondHorizon(tile.baseBlockX(), tile.baseBlockZ(), tile.spanBlocks(),
                        centerChunkX * 16, centerChunkZ * 16, layout.maxDistanceBlocks() + RETIREMENT_MARGIN_BLOCKS);
                if (canRetireStored(tile.key(), now - lastUse, effectivelyDesired(tile.key()),
                        storedTiles.contains(tile.key()), layout, outside)) removeTile(tile.key());
            }
        }
        // Let JVM headroom govern residency instead of imposing a tile-count
        // ceiling. Evict inactive covered detail only as pressure requires.
        if (memoryBudget.reclaimTargetBytes() == 0) {
            return;
        }
        List<PredictionTile> evictable = new ArrayList<>();
        for (PredictionTile tile : ready.values()) {
            if (!effectivelyDesired(tile.key()) && !PredictionWorkOrder.scoped(tile.key(), layout, buildFocus)
                    && coveredByAncestor(ready.keySet(), layout, tile.key())) {
                evictable.add(tile);
            }
        }
        evictable.sort(Comparator.comparingLong((PredictionTile tile) ->
                distanceSquared(tile, centerChunkX, centerChunkZ)).reversed());
        for (PredictionTile tile : evictable) {
            if (memoryBudget.reclaimTargetBytes() == 0) break;
            if (coveredByAncestor(ready.keySet(), layout, tile.key())) {
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
        // Prepare missing parents in a separate pass.  This keeps the moving
        // view O(n) instead of sorting the entire resident set every five
        // ticks, while still marking parents desired before the removal pass.
        Set<PredictionTileKey> fallbackRequests = new HashSet<>();
        for (PredictionTile tile : ready.values()) {
            PredictionTileKey key = tile.key();
            if (!shouldRetirePinned(key, ready.keySet(), desiredKeys, layout,
                    cameraBlockX, cameraBlockZ)) {
                if (!desiredKeys.contains(key) && outOfRetirementRange(key, layout, cameraBlockX, cameraBlockZ)
                        && key.lod() < layout.levelCount() - 1) {
                    PredictionTileKey fallback = firstMissingAncestor(key, ready.keySet(), layout);
                    if (fallback != null) {
                        fallbackRequests.add(fallback);
                        retainedForFallback.incrementAndGet();
                    }
                }
            }
        }
        for (PredictionTileKey fallback : fallbackRequests) {
            queueFallbackAncestors(fallback, centerChunkX, centerChunkZ);
        }
        for (PredictionTile tile : ready.values()) {
            PredictionTileKey key = tile.key();
            if (!shouldRetirePinned(key, ready.keySet(), desiredKeys, layout,
                    cameraBlockX, cameraBlockZ)) continue;
            if (retired == null) {
                retired = new ArrayList<>();
            }
            retired.add(key);
        }
        if (retired != null) {
            for (PredictionTileKey key : retired) {
                removeTile(key);
                fallbackKeys.remove(key);
                retiredOutOfRange.incrementAndGet();
            }
        }
    }

    /** Queue a coarse chain from the root down; each child waits for its parent. */
    private synchronized void queueFallbackAncestors(PredictionTileKey nearest,
                                                       int centerChunkX, int centerChunkZ) {
        if (nearest == null || nearest.lod() < 0 || nearest.lod() >= layout.levelCount()) return;
        List<PredictionTileKey> chain = new ArrayList<>();
        for (int lod = nearest.lod(); lod < layout.levelCount(); lod++) {
            int shift = lod - nearest.lod();
            chain.add(new PredictionTileKey(nearest.dimension(), nearest.tileX() >> shift,
                    nearest.tileZ() >> shift, lod));
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            PredictionTileKey key = chain.get(i);
            if (voxyOwnedTiles.contains(key)) continue;
            if (ready.containsKey(key)) continue;
            if (fallbackKeys.add(key)) fallbackParentsQueued.incrementAndGet();
            desiredKeys.add(key);
            desiredGrace.put(key, System.nanoTime());
            if (!pending.contains(key)) enqueue(key, centerChunkX, centerChunkZ, false);
        }
    }

    /** Re-admits fallback parents on later planner ticks until they upload. */
    private synchronized void serviceFallbacks(int centerChunkX, int centerChunkZ, long now) {
        for (PredictionTileKey key : List.copyOf(fallbackKeys)) {
            if (key.lod() < 0 || key.lod() >= layout.levelCount()
                    || ready.containsKey(key)) {
                fallbackKeys.remove(key);
                continue;
            }
            if (!outOfRetirementRange(key, layout, (int) Math.floor(cameraBlockX), (int) Math.floor(cameraBlockZ))
                    && !pending.contains(key)) {
                // The current view no longer needs this hand-off.  A pending
                // task will self-cancel when its grace expires.
                fallbackKeys.remove(key);
                continue;
            }
            desiredKeys.add(key);
            desiredGrace.put(key, now);
            if (!pending.contains(key)) enqueue(key, centerChunkX, centerChunkZ, false);
        }
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

    @Override
    public synchronized void close() {
        terrainColors.close();
        if (closed) return;
        closed = true;
        meshRevision.incrementAndGet();
        ready.clear();
        coverageEpochs.clear();
        renderSnapshot = null;
        coverageHot.clear();
        // Release CPU reservations for tasks that never reached a worker.
        for (Runnable task : executor.getQueue()) {
            if (task instanceof PredictionTask prediction) prediction.releaseAdmission();
        }
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof PredictionTask prediction) prediction.releaseAdmission();
        }
        cacheExecutor.shutdownNow();
        cacheMissUntil.clear();
        restoredCityProofs.clear();
        ready.clear();
        residentMemory.values().forEach(PredictionMemoryBudget.Reservation::close);
        residentMemory.clear();
        pending.clear();
        cachePending.clear();
        cacheCandidates.clear();
        scopePending.clear();
        scopeCandidates.clear();
        backgroundPending.clear();
        refinementPending.clear();
        idlePending.clear();
        idleResidents.clear();
        idleTargets = Map.of();
        ordinaryTargets = Map.of();
        idleAllowed = false;
        pendingCaptures.clear();
        latestCaptures.clear();
        captureVersions.clear();
        desiredKeys.clear();
        desiredGrace.clear();
        voxyOwnedTiles = Set.of();
        fallbackKeys.clear();
        terrainLeaves.clear();
        failedAt.clear();
        meshLimits.clear();
        deferredUntil.clear();
        relief.clear();
        terrainTargets = Map.of();
        transitionTargets = Map.of();
        mediumCoverage = PredictionMediumCoverage.EMPTY;
        mediumCoveragePending = false;
        mediumCoverageLevelBias = 0;
        authoritativeCells.clear();
        editedCells.clear();
        captureEpochs.clear();
        captureRefresh.clear();
        dirtyTiles.clear();
        sampleCache.clear();
        storedTiles.clear();
        if (diskCache != null) diskCache.close();
        // Never wait for worker termination or disk I/O on the client thread.
        PredictionResources.retire(executor, sampleStore, sampler, cacheExecutor);
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
            cacheExecutor.getQueue().removeIf(task -> {
                if (task instanceof PredictionTask prediction && prediction.key != null) {
                    pending.remove(prediction.key);
                    cachePending.remove(prediction.key);
                    return true;
                }
                return false;
            });
            executor.getQueue().removeIf(task -> {
                if (task instanceof PredictionTask prediction && prediction.key != null) {
                    prediction.releaseAdmission();
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
                                 int cellAxis, int spacingBlocks, boolean scopeOnly) {
        public PredictionTile(PredictionTileKey key, int[] heights, int[] groundHeights,
                              ClientColumnSample[] samples, PredictionMesh mesh, PredictionDepthBound depthBound,
                              long generatedAtNanos, long revision, int cellAxis, int spacingBlocks) {
            this(key, heights, groundHeights, samples, mesh, depthBound, generatedAtNanos, revision, cellAxis, spacingBlocks, false);
        }
        long retainedHeapBytes() {
            // Published meshes account for their single visual payload and seam
            // summary. Unprepared legacy fixtures still reserve the future payload.
            long futurePayload = mesh.gpuPayload() == null
                    ? 48L * (mesh.packed().quadCount() + mesh.packed().waterQuadCount()) : 0;
            int sampleObjects = mesh.retainedSampleObjects < 0 ? samples.length : mesh.retainedSampleObjects;
            return 1024L + 4L * (heights.length + (groundHeights == heights ? 0 : groundHeights.length))
                    + 4L * samples.length + 104L * sampleObjects + mesh.retainedVolumeBytes
                    + mesh.retainedHeapBytes() + futurePayload
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
        private final PredictionCpuBudget.Reservation cpuReservation;

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, Runnable delegate) {
            this(key, priority, distanceSquared, false, delegate, null);
        }

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, boolean surface, Runnable delegate) {
            this(key, priority, distanceSquared, surface, delegate, null);
        }

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, boolean surface,
                       Runnable delegate, PredictionCpuBudget.Reservation cpuReservation) {
            this.key = key;
            this.priority = priority;
            this.distanceSquared = distanceSquared;
            this.surface = surface;
            this.delegate = delegate;
            this.cpuReservation = cpuReservation;
        }

        private PredictionTask(int priority, long distanceSquared, Runnable delegate) {
            this(null, priority, distanceSquared, false, delegate, null);
        }

        void releaseAdmission() {
            if (cpuReservation != null) cpuReservation.close();
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
