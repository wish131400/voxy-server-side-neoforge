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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/**
 * 一个维度的预测 CPU 场景：协调需求、缓存恢复、生成任务和带版本的瓦片发布。
 * <p>客户端线程提交需求并消费渲染快照；工作线程只计算安全采样上下文。
 * 本类拥有生成/缓存执行器与 CPU 结果，不拥有 OpenGL 缓冲，也不向 Voxy 写入预测列。
 * 关闭或版本失效后不能发布旧结果；取消路径仍须归还预算租约与暂存资源。
 */
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
    private volatile ClientTerrainSampler sampler;
    private volatile boolean generationReady;
    private volatile PredictionVegetation vegetation;
    private final LostCityHints cityHints;
    private int surfaceSettings;
    private int surfaceContextSettings;
    private final Object surfaceContextLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger activeSurfaceBuilds = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger activeDetailBuilds = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean previewWorkPending;
    private volatile PredictionLoadingProgress loadingProgress = PredictionLoadingProgress.INITIALIZING;
    private volatile PredictionLoadingProgress loadingDisplayProgress = PredictionLoadingProgress.INITIALIZING;
    private long loadingDisplayAt, loadingDisplayCommits, loadingReadinessCommits, loadingReadinessDiscards;
    private volatile boolean loadingDisplayCurrent;
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
    private final java.util.concurrent.atomic.LongAdder colorFallbackPoints = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder captureEarlyExits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder captureDiscardNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder commitNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder diskHitBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder diskMissBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder reusedPointBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder adaptiveDisplayAccepted = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder adaptiveDisplayRejected = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder adaptiveDisplayFilled = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder decorationNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder meshingNanos = new java.util.concurrent.atomic.LongAdder();
    private final ThreadPoolExecutor executor;
    private final ThreadPoolExecutor cacheExecutor;
    private final PredictionTileStates<PredictionTileKey> tileStates = new PredictionTileStates<>(11,5);
    private final Map<PredictionTileKey, Long> cacheMissUntil = tileStates.values(0);
    private record SurfaceCacheAttempt(long epoch,long revision,int settings) { }
    private final Map<PredictionTileKey,SurfaceCacheAttempt> surfaceCacheAttempts=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder cacheFastMisses = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder voxelRestores = new java.util.concurrent.atomic.LongAdder();
    private record VoxelIdentity(long tags, byte[] bytes) { }
    private volatile VoxelIdentity voxelIdentity;

    private byte[] voxelContentIdentity() {
        long tags = PredictionVegetationTraits.generation();
        var current = voxelIdentity;
        if (current == null || current.tags() != tags) {
            current = new VoxelIdentity(tags,PredictionVoxelCache.identity(sampler));
            voxelIdentity = current;
        }
        return current.bytes();
    }
    private final PredictionMemoryBudget memoryBudget;
    private final PredictionCpuBudget cpuBudget;
    private final Map<PredictionTileKey, PredictionMemoryBudget.Reservation> residentMemory =
            new java.util.HashMap<>();
    private final Set<Long> pendingCaptures = ConcurrentHashMap.newKeySet();
    private final Map<Long, VoxelColumnData> latestCaptures = new java.util.LinkedHashMap<>();
    private final Map<Long, Long> captureRetryAfter = new java.util.HashMap<>();
    private final PredictionCaptureHandoff captureHandoff = new PredictionCaptureHandoff();
    private final Map<Long, Long> captureVersions = new java.util.HashMap<>();
    private static final int MAX_PENDING_CAPTURES = 32;
    private static final long MAX_PENDING_CAPTURE_BYTES = 16L * PredictionMemoryBudget.MIB;
    private long pendingCaptureBytes;
    private boolean captureDrainQueued;
    private Long activeCapture;
    private final Set<Long> captureEdits = new HashSet<>();
    private final PredictionCapturedTerrain capturedTerrain;
    private final Map<PredictionTileKey, PredictionTile> ready = new ConcurrentHashMap<>();
    private final PredictionVoxySource voxySource = new PredictionVoxySource();
    private final PredictionClientPreparation<PredictionVoxySource.DetailInput,PredictionVoxyDetailQueue.Changes>
            voxyDemand=new PredictionClientPreparation<>(voxySource::refreshDetails);
    private boolean voxyDemandRefill;
    private volatile Set<PredictionTileKey> voxyDetailTargets = PredictionInputSnapshots.concurrentSet();
    private final Map<PredictionTileKey, PredictionDetailTrace> detailTraces = new ConcurrentHashMap<>();
    private void detailEvent(PredictionTileKey key,String event) {
        var trace=detailTraces.get(key);if(trace!=null)trace.event(event,System.nanoTime());
    }
    private void markDirty(PredictionTileKey key) {
        dirtyTiles.add(key);
        var trace=detailTraces.get(key);
        if(trace!=null)trace.dirty(System.nanoTime(),captureEpochs.getOrDefault(key,0L));
    }
    private final java.util.ArrayDeque<PredictionTileKey> voxyDetailWork = new java.util.ArrayDeque<>();

    private boolean voxyDetail(PredictionTileKey key) { return unifiedVoxy && voxyDetailTargets.contains(key); }

    /** Source demand crosses the worker boundary as a snapshot; admission stays on the client thread. */
    private synchronized void refreshVoxyDetails(boolean refill) {
        if(closed)return;
        voxyDemandRefill|=refill;
        var changes=voxyDemand.poll();
        if(!voxyDemand.pending())voxyDemand.request(new PredictionVoxySource.DetailInput(cameraBlockX,cameraBlockZ,
                layout,workView,dimension,buildFocus,sampler.profile().minY(),sampler.profile().minY()+sampler.profile().height()));
        boolean changed = changes!=null && !changes.empty();
        voxyDemandRefill|=changed;
        if (changed) {
            var planMembers = changes.removed().isEmpty() ? Set.<PredictionTileKey>of() : new HashSet<>(scheduledPlan);
            for (var detail : changes.removed()) {
                var key = new PredictionTileKey(dimension,detail.x(),detail.z(),detail.lod());
                voxyDetailTargets.remove(key);
                detailTraces.remove(key);
                if (!planMembers.contains(key) && !fallbackKeys.contains(key)) desiredKeys.remove(key);
            }
            var added = new ArrayList<PredictionTileKey>();
            for (var detail : changes.added()) if (detail.lod()<layout.levelCount()) {
                var key = new PredictionTileKey(dimension,detail.x(),detail.z(),detail.lod());
                voxyDetailTargets.add(key); added.add(key);
                detailTraces.computeIfAbsent(key,k->new PredictionDetailTrace(System.nanoTime(),
                        dirtyTiles.contains(k),captureEpochs.getOrDefault(k,0L)));
                desiredKeys.add(key);
            }
            if (diskCache != null && !added.isEmpty()) probeNearbyCache(added);
            scheduleAgain = true;
        }
        publishVoxyWork();
        if ((changed || voxyDemandRefill) && !planning.details.pending()) {
            voxyDemandRefill=false;
            planning.details.request(new PredictionVoxyWorkPreparation.Input(layout,buildFocus,workView,
                    cameraBlockX,cameraBlockY,cameraBlockZ,schedulingPixels,sampler.profile().minY(),
                    sampler.profile().minY()+sampler.profile().height()+64,surfaceRadius,meshRevision.get(),navigationGeneration,
                    inputSnapshots.set(voxyDetailTargets),surfaceDesired,inputSnapshots.set(surfaceReady),inputSnapshots.set(dirtyTiles),
                    renderSnapshot(),viewBands));
        }
    }

    /** Both demand refresh and task servicing can receive completed priority hints without waiting. */
    private synchronized void publishVoxyWork() {
        var prepared = planning.details.poll(result -> !closed && !paused
                && result.input().layout().equals(layout) && Objects.equals(result.input().focus(),buildFocus)
                && result.input().revision()==meshRevision.get() && result.input().navigation()==navigationGeneration);
        if (prepared != null) {
            // These are hints, not readiness proofs. Admission rechecks each key against live state.
            // Terrain can finish after this sort captured its residents. Keep those
            // completion handoffs: the older result could not have ranked them yet.
            var completions = visibleSurfaceWork.stream()
                    .filter(key -> ready.get(key) != prepared.input().residents().tiles().get(key)
                            && visibleSurface(key) && surfaceBuildReady(key)).toList();
            voxyDetailWork.clear(); voxyDetailWork.addAll(prepared.terrain());
            visibleSurfaceWork.clear(); visibleSurfaceWork.addAll(prepared.surfaces());
            for (var key : completions) queueSurfaceCompletion(key);
        }
    }

    private boolean serviceVisibleSurfaces(int cx, int cz, long until) {
        var deferred=new ArrayList<PredictionTileKey>();
        try {
            for (int checked=0; checked<8 && !visibleSurfaceWork.isEmpty() && System.nanoTime()<until; checked++) {
                // Lane fairness guarantees a turn for plants. Spend that turn on
                // the nearest unfinished surface; terrain lanes still serve their far end.
                var key=visibleSurfaceWork.removeFirst();
                if (pending.contains(key) || !visibleSurface(key) || !surfaceBuildReady(key)) continue;
                enqueue(key,cx,cz,true);
                if (pending.contains(key)) return true;
                deferred.add(key);
            }
            return false;
        } finally { restoreDeferred(visibleSurfaceWork,deferred,false); }
    }
    private void queueSurfaceCompletion(PredictionTileKey key) {
        visibleSurfaceWork.remove(key);
        int remaining=visibleSurfaceWork.size(); boolean inserted=false;
        for (int index=0; index<remaining; index++) {
            var queued=visibleSurfaceWork.removeFirst();
            int order=Integer.compare(viewBand(key),viewBand(queued));
            if (order==0) order=Double.compare(PredictionWorkOrder.distanceSquared(key,layout,cameraBlockX,cameraBlockZ),
                    PredictionWorkOrder.distanceSquared(queued,layout,cameraBlockX,cameraBlockZ));
            if (!inserted && order<0) { visibleSurfaceWork.addLast(key); inserted=true; }
            visibleSurfaceWork.addLast(queued);
        }
        if (!inserted) visibleSurfaceWork.addLast(key);
    }

    private boolean visibleSurface(PredictionTileKey key) {
        return unifiedVoxy && viewWork(key,true) && (surfaceDesired.contains(key) || voxyDetailSurface(key));
    }

    private boolean serviceVoxyDetails(int cx, int cz, long until) {
        var deferred=new ArrayList<PredictionTileKey>();
        try {
            for (int checked=0; checked<8 && !voxyDetailWork.isEmpty() && System.nanoTime()<until; checked++) {
                var key=fairGenerationAdmission ? voxyDetailWork.removeLast() : voxyDetailWork.removeFirst();
                if (!voxyDetail(key) || pending.contains(key)) continue;
                boolean surface=!terrainBuildNeeded(key) && surfaceBuildReady(key);
                if (!surface && !terrainBuildNeeded(key)) continue;
                enqueue(key,cx,cz,surface);
                if (pending.contains(key)) return true;
                deferred.add(key);
            }
            return false;
        } finally { restoreDeferred(voxyDetailWork,deferred,fairGenerationAdmission); }
    }

    private static <T> void restoreDeferred(ArrayDeque<T> queue,List<T> deferred,boolean fromTail) {
        for (int index=deferred.size()-1; index>=0; index--) {
            if (fromTail) queue.addLast(deferred.get(index)); else queue.addFirst(deferred.get(index));
        }
    }

    /** Prefer nearby work across all three lanes. Every fourth admission preserves a fair lane turn. */
    private void serviceGenerationWork(int cx, int cz, long until) {
        for (int admitted=0; admitted<Math.max(3, Math.min(32,executor.getCorePoolSize()*4))
                && System.nanoTime()<until; admitted++) {
            if (!serviceGenerationTurn(cx,cz,until)) break;
        }
    }

    private boolean serviceGenerationTurn(int cx, int cz, long until) {
        boolean fair = nearGenerationAdmissions >= NEAR_WORK_BURST;
        int first = fair ? fairGenerationLane : generationAdmissionLane;
        if (!fair) {
            BuildRequest best = null;
            for (int offset=0; offset<3; offset++) {
                int lane=(generationAdmissionLane+offset)%3;
                BuildRequest candidate = generationHead(lane);
                if (candidate != null && (best == null || compareWork(candidate.priority(),candidate.distance(),candidate.key(),
                        best.priority(),best.distance(),best.key()) < 0)) { best=candidate; first=lane; }
            }
        }
        long before=generationEnqueues;
        fairGenerationAdmission=fair;
        try {
        for (int offset=0; offset<3 && System.nanoTime()<until; offset++) {
            int lane=(first+offset)%3;
            boolean admitted = switch(lane) {
                case 0 -> serviceVisibleSurfaces(cx,cz,until);
                case 1 -> serviceVoxyDetails(cx,cz,until);
                default -> servicePreparedCandidates(cx,cz,until);
            };
            if (admitted) {
                generationAdmissionLane=(lane+1)%3;
                // A surface request can progress through the independent cache lane.
                // Rotate its fair lane too, without spending a generation burst turn.
                if (fair) fairGenerationLane=(lane+1)%3;
                if (generationEnqueues != before)
                    nearGenerationAdmissions = fair ? 0 : nearGenerationAdmissions+1;
                return true;
            }
        }
        } finally { fairGenerationAdmission=false; }
        return false;
    }

    private BuildRequest generationHead(int lane) {
        if (lane == 2) {
            for (int checked=0; checked<64 && !preparedWork.isEmpty(); checked++) {
                var request=preparedWork.peekFirst(); var key=request.key();
                if (desiredKeys.contains(key) && !pending.contains(key)
                        && (request.surface() ? surfaceBuildReady(key) : terrainBuildNeeded(key)))
                    return new BuildRequest(key,request.surface(),workPriority(key,request.surface()),
                            PredictionWorkOrder.orderingDistance(key,layout,cameraBlockX,cameraBlockZ,buildFocus));
                preparedWork.removeFirst();
            }
        } else {
            var queue=lane == 0 ? visibleSurfaceWork : voxyDetailWork;
            for (int checked=0; checked<64 && !queue.isEmpty(); checked++) {
                var key=queue.peekFirst();
                boolean surface=lane == 0 || !terrainBuildNeeded(key) && surfaceBuildReady(key);
                if (!pending.contains(key) && (lane == 0 ? visibleSurface(key) && surfaceBuildReady(key)
                        : voxyDetail(key) && (terrainBuildNeeded(key) || surfaceBuildReady(key))))
                    return new BuildRequest(key,surface,workPriority(key,surface),
                            PredictionWorkOrder.orderingDistance(key,layout,cameraBlockX,cameraBlockZ,buildFocus));
                queue.removeFirst();
            }
        }
        return null;
    }

    private boolean servicePreparedCandidates(int cx,int cz,long until) {
        int count=Math.min(64,preparedWork.size());
        var deferred=new ArrayList<PredictionSchedulePreparation.Request>();
        try {
            for(int checked=0;checked<count && System.nanoTime()<until;checked++) {
                var request=fairGenerationAdmission ? preparedWork.removeLast() : preparedWork.removeFirst(); admissionChecks++;
                var key=request.key();
                if(!desiredKeys.contains(key) || pending.contains(key)
                        || !(request.surface()?surfaceBuildReady(key):terrainBuildNeeded(key)))continue;
                var target=request.surface() || cachedUpgrade(key) ? key : missingParent(key);
                if (pending.contains(target)) { deferred.add(request); continue; }
                enqueue(target,cx,cz,request.surface());
                if (!target.equals(key) || !pending.contains(key)) deferred.add(request);
                if(pending.contains(target) && unifiedVoxy)return true;
            }
            return false;
        } finally { restoreDeferred(preparedWork,deferred,fairGenerationAdmission); }
    }

    /** Resolve only the selected region's missing ancestor chain, never a horizon-wide quality pass. */
    private PredictionTileKey missingParent(PredictionTileKey key) {
        var target=key;
        while (!terrainParentReady(target)) {
            var parent=new PredictionTileKey(key.dimension(),target.tileX()>>1,target.tileZ()>>1,target.lod()+1);
            if (!effectivelyDesired(parent)) break;
            target=parent;
        }
        return target;
    }
    private volatile boolean unifiedVoxy;

    synchronized void connectVoxy() {
        // Prediction-to-Xaero projection is temporarily suspended; retain the Voxy source.
        boolean connected = dev.xantha.vss.compat.VoxyPredictionBridge.attach(dimension, voxySource);
        if (connected != unifiedVoxy) {
            unifiedVoxy = connected;
            if (!connected) dev.xantha.vss.compat.VoxyPredictionBridge.detach(voxySource);
            // Renderer availability does not change the sampled voxels. Keep resident
            // tiles and valid builds so a replacement renderer can read them directly.
        }
    }
    boolean usesVoxyRendering() { return unifiedVoxy; }
    private volatile Set<PredictionTileKey> surfaceDesired = ConcurrentHashMap.newKeySet();
    private final Set<PredictionTileKey> surfaceReady = tileStates.flag(0);
    private final ArrayDeque<PredictionTileKey> visibleSurfaceWork = new ArrayDeque<>();
    private int generationAdmissionLane;
    private int nearGenerationAdmissions;
    private int fairGenerationLane;
    private boolean fairGenerationAdmission;
    private long generationEnqueues;
    private static final int FAIR_WORK_PRIORITY = Integer.MIN_VALUE;
    private static final int NEAR_WORK_BURST = 3;
    private volatile double cameraBlockX, cameraBlockY, cameraBlockZ;
    private boolean cameraInitialized;
    private volatile long navigationGeneration;
    private volatile Set<PredictionTileKey> voxyOwnedTiles = Set.of();
    private PredictionPreviewHandoff.Uploads voxyUploads = PredictionPreviewHandoff.VOXY;
    private long voxyUploadRevision = -1;
    private volatile VssLodFocus buildFocus;
    private volatile PredictionWorkView workView;
    private volatile double schedulingPixels = 1;
    private PredictionWorkView lastScheduledView;
    private volatile PredictionVisibilityFeedback.Snapshot visibilityFeedback = PredictionVisibilityFeedback.Snapshot.EMPTY;
    private PredictionVisibilityFeedback.Snapshot lastScheduledVisibility = visibilityFeedback;
    private volatile Map<PredictionTileKey, Integer> viewBands = Map.of();
    private volatile Set<PredictionTileKey> viewDependencies = Set.of();
    private Set<PredictionTileKey> scheduledOccluded = Set.of();
    private final Map<PredictionTileKey, Long> recentlyVisible = tileStates.values(4);
    private final PredictionSpatialIndex<PredictionTileKey> coverageSpatial = new PredictionSpatialIndex<>();
    private int coverageSpatialSize;
    private final PredictionPlanningPipeline planning =
            new PredictionPlanningPipeline(ClientPredictionState::planningCoverage);
    private final PredictionInputSnapshots inputSnapshots = new PredictionInputSnapshots();
    private final PredictionInputSnapshots.Union<PredictionTileKey> planningCandidates = new PredictionInputSnapshots.Union<>();
    private long loadingStateVersion;
    private final java.util.ArrayDeque<PredictionSchedulePreparation.Request> preparedWork = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<PredictionTileKey> preparedReclaim = new java.util.ArrayDeque<>();
    private record ReclaimCandidate(PredictionTileKey key, long revision, long bytes) { }
    private final java.util.ArrayDeque<ReclaimCandidate> validatedReclaim = new java.util.ArrayDeque<>();
    private final Map<PredictionTileKey, ReclaimCandidate> validatedReclaimEntries = new java.util.HashMap<>();
    private long validatedReclaimBytes;
    private PredictionTileKey reclaimTarget;
    // An asynchronous eviction pays for this replacement, not another queued key.
    private PredictionTileKey reclaimAdmissionTarget;
    private Map<PredictionTileKey, PredictionSchedulePreparation.Hint> scheduledHints = Map.of();
    private Runnable[] queuedRefresh = new Runnable[0];
    private int queuedRefreshIndex;
    private long queuedRefreshChecks;
    private boolean admissionBatch;
    private volatile boolean scheduleAgain;
    private long admissionChecks;
    private volatile boolean foregroundWorkPending;
    private Map<PredictionTileKey, PredictionLoadingPreparation.DisplayGoal> displayGoals = Map.of();
    private List<PredictionTileKey> scheduledPlan = List.of(), scheduledLeaves = List.of();
    private Set<PredictionTileKey> desiredSnapshot() {
        return inputSnapshots.set(desiredKeys);
    }

    private static final java.lang.management.ThreadMXBean CPU_TIMES = java.lang.management.ManagementFactory.getThreadMXBean();
    private final java.util.concurrent.atomic.LongAdder viewRefreshes = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder viewDeferredAdmissions = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder visibleCpuNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder backgroundCpuNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder supersededBuilds = new java.util.concurrent.atomic.LongAdder();
    private final PredictionQueueTimings visibleQueueTimings = new PredictionQueueTimings();
    private final PredictionQueueTimings backgroundQueueTimings = new PredictionQueueTimings();
    private volatile long viewStartedNanos, lastViewReadyNanos;
    private volatile boolean viewCompletionRecorded;
    private final Set<PredictionTileKey> backgroundPending = tileStates.flag(1);
    private final Set<PredictionTileKey> surfacePending = tileStates.flag(2);
    private final Set<PredictionTileKey> refinementPending = tileStates.flag(3);
    private RenderSnapshot renderSnapshot;
    private PredictionTileTable<PredictionTile> publishedTiles;
    private PredictionTileTable<Long> publishedEpochs;
    private final Set<PredictionTileKey> snapshotChanges = new HashSet<>();
    private final PredictionScopeFamilies.Index scopeFamilies = new PredictionScopeFamilies.Index();
    private volatile int surfaceRadius = 768;
    private final Set<PredictionTileKey> pending = tileStates.flag(4);
    private final Set<PredictionTileKey> cachePending = tileStates.flag(5);
    private final java.util.ArrayDeque<PredictionTileKey> cacheCandidates = new java.util.ArrayDeque<>();
    private int nearCacheAdmissions;
    /** Keep several independent disk decodes in flight without opening an unbounded IO lane. */
    private static final int CACHE_RESTORE_IN_FLIGHT = 4;
    private final Set<PredictionTileKey> scopePending = tileStates.flag(6);
    private final java.util.ArrayDeque<BuildRequest> scopeCandidates = new java.util.ArrayDeque<>();
    /**
     * Tiles whose build exhausted the memory budget, with the failure time.
     * A permanent blacklist leaves a permanent hole: an OOM during a
     * teleport burst (hundreds of tiles allocated at once) retired tiles
     * that were never retried for the whole session.  A backoff window
     * still prevents the OOM/logging spiral while letting a later, calmer
     * plan rebuild the region.
     */
    private final Map<PredictionTileKey, Long> failedAt = tileStates.values(1);
    private final Map<PredictionTileKey, Long> deferredUntil = tileStates.values(2);
    private static final long CONTENTION_RETRY_NANOS = 100_000_000L;
    /** Avoid repeatedly starting workers that cannot acquire the shared CPU budget. */
    // The bounded admission queue already limits repeated worker starts. Keep
    // the retry delay to one planner tick so initial coverage does not lose
    // throughput at the end of a single-worker load window.
    private static final long FAILED_RETRY_NANOS = 30_000_000_000L;
    /** Latest planner output; drives enqueue and the OOM-guard eviction order. */
    private final Set<PredictionTileKey> desiredKeys = PredictionInputSnapshots.concurrentSet();
    /**
     * Ancestors that are being prepared as a safe hand-off for detail tiles
     * which have just left the moving camera horizon.  Keeping this separate
     * from the planner output lets the normal plan move on while a single
     * coarse parent is built for several retiring children.
     */
    private final Set<PredictionTileKey> fallbackKeys = PredictionInputSnapshots.concurrentSet();
    private final AtomicLong retiredOutOfRange = new AtomicLong();
    private final AtomicLong retainedForFallback = new AtomicLong();
    private final AtomicLong fallbackParentsQueued = new AtomicLong();
    /** Final leaves upgrade temporary coverage; ancestors can hand off to children. */
    private final Set<PredictionTileKey> terrainLeaves = ConcurrentHashMap.newKeySet();
    private final Map<PredictionTileKey, PredictionRelief> relief = PredictionInputSnapshots.concurrentMap();
    private volatile Map<PredictionTileKey, Integer> terrainTargets = Map.of();
    private PredictionIdleRefinement idleRefinement = new PredictionIdleRefinement();
    private volatile Map<PredictionTileKey, Integer> ordinaryTargets = Map.of();
    private volatile Map<PredictionTileKey, Integer> idleTargets = Map.of();
    private final Set<PredictionTileKey> idlePending = tileStates.flag(7);
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
    private final Map<PredictionTileKey, Long> desiredGrace = PredictionInputSnapshots.concurrentMap();
    private static final long DESIRED_GRACE_NANOS = 5_000_000_000L;

    /** True when the tile is desired now or was desired within the grace window. */
    private boolean effectivelyDesired(PredictionTileKey key) {
        if (key.lod() < 0 || key.lod() >= layout.levelCount()) return false;
        if (voxyOwnedTiles.contains(key)) return false;
        if (voxyDetail(key)) return true;
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
    private final Set<Long> editedCells = PredictionInputSnapshots.concurrentSet();
    private final VssLodSampleCache sampleCache = new VssLodSampleCache();
    private final PredictionSampleStore sampleStore;
    private final PredictionDiskCache diskCache;
    private final Set<PredictionTileKey> storedTiles = tileStates.flag(8);
    private final AtomicLong meshRevision = new AtomicLong();
    private final AtomicLong meshIds = new AtomicLong();
    private final Map<PredictionTileKey, Long> captureEpochs = tileStates.values(3);
    private final Set<PredictionTileKey> dirtyTiles = tileStates.flag(9);
    // Ground refreshes (captures/child mips) do not invalidate placement inputs.
    private final Set<PredictionTileKey> invalidatedSurfaces = tileStates.flag(10);
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
    private final java.util.concurrent.atomic.LongAdder publishedTileCount = new java.util.concurrent.atomic.LongAdder();
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
    private final PredictionCpuCachePolicy cpuCachePolicy = new PredictionCpuCachePolicy(Runtime.getRuntime().maxMemory());
    private long nextCacheMaintenance, coldCacheBytes, coldCacheEvictions, coldCacheReleasedBytes;
    private java.util.Iterator<PredictionTile> cacheScan, retirementScan;
    private long retirementChecks;
    private java.util.Iterator<Map.Entry<PredictionTileKey, Long>> deferredCleanup, cacheMissCleanup;
    private long cacheScanBytes, cacheScanSlices;
    private final java.util.PriorityQueue<PredictionTile> cacheScanCandidates = new java.util.PriorityQueue<>(
            Comparator.comparingLong((PredictionTile t) -> t.generatedAtNanos()).reversed());

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
        this.generationReady = !(sampler instanceof PredictionCacheSampler);
        this.diskCache = diskCache;
        this.cityHints = new LostCityHints(dimension, sampler.profile().fingerprint());
        this.vegetation = new PredictionVegetation(sampler, diskCache, true);
        this.surfaceSettings = surfaceSettings();
        this.surfaceContextSettings = surfaceSettings;
        this.memoryBudget = memoryBudget;
        voxySource.budget(memoryBudget);
        this.cpuBudget = cpuBudget;
        this.layout = createLayout();
        this.sampleStore = diskCache == null ? null
                : new PredictionSampleStore(diskCache.root().resolve("captures.smp"), sampler.cacheFingerprint(), diskCache.mappings());
        this.capturedTerrain = new PredictionCapturedTerrain(diskCache);
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
        // recovery moving even while generation is throttled by CPU pressure.
        this.cacheExecutor = new ThreadPoolExecutor(2, 2, 15L, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(CACHE_RESTORE_IN_FLIGHT), runnable -> {
            Thread thread = new Thread(runnable, "vss-cache-restore-" + dimension.location().getPath());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        this.cacheExecutor.allowCoreThreadTimeOut(true);
    }

    void serviceCaptures() { queueCaptureDrain(); }

    void setWorkView(PredictionWorkView view) {
        // Render projection includes zoom/bob/tilt; the tick camera is a startup fallback.
        if (workView == null || !workView.freshRender(System.nanoTime())) workView = view;
    }

    void setRenderWorkView(PredictionWorkView view) {
        if (!closed) {
            if (view != null && view.changedFrom(workView)) loadingDisplayCurrent = false;
            workView = view;
        }
    }

    void setNativeDetailLoading(boolean allowed) { voxySource.detailLoading(allowed); }

    void setVisibilityFeedback(PredictionVisibilityFeedback.Snapshot feedback) {
        if (!closed) visibilityFeedback = feedback;
    }

    private boolean occludedWork(PredictionTileKey key) {
        return occludedWork(key, System.nanoTime());
    }

    private boolean occludedWork(PredictionTileKey key, long now) {
        return !visibilityFeedback.proofs().isEmpty() && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && visibilityFeedback.hidden(key, layout, workView, now, ready, coverageEpochs, dirtyTiles);
    }

    private int classifyView(PredictionTileKey key) {
        PredictionWorkView view = workView;
        if (view == null || key.lod() < 0 || key.lod() >= layout.levelCount()) return PredictionWorkView.VISIBLE;
        double minY = sampler.profile().minY(), maxY = minY + sampler.profile().height() + 64;
        PredictionTile tile = ready.get(key);
        // Unknown vegetation/buildings and coarse samples retain full-height bounds.
        if (tile != null && tile.data() != null && tile.cellAxis() > 8
                && tile.cellAxis() >= Math.max(ordinaryTargets.getOrDefault(key, tile.cellAxis()),
                        terrainTargets.getOrDefault(key, tile.cellAxis()))
                && (!surfaceDesired.contains(key) || surfaceReady.contains(key))) {
            double margin = Math.max(32, tile.spacingBlocks() * 2D);
            minY = Math.min(tile.depthBound().minY(), Math.max(minY, tile.depthBound().minY() - margin));
            maxY = tile.depthBound().maxY() + margin + 64;
        }
        return view.band(key, layout, buildFocus, minY, maxY);
    }

    private int viewBand(PredictionTileKey key) {
        if (key.lod() < 0 || key.lod() >= layout.levelCount()) return PredictionWorkView.VISIBLE;
        if (PredictionWorkOrder.scoped(key, layout, buildFocus)) return PredictionWorkView.VISIBLE;
        if (voxyDetail(key)) return classifyView(key);
        Integer band = viewBands.get(key);
        return band == null ? classifyView(key) : band;
    }

    private boolean backgroundWork(PredictionTileKey key) {
        return viewBand(key) >= PredictionWorkView.RECENT;
    }

    private boolean viewRelevant(PredictionTileKey key) { return viewWork(key, false); }

    private boolean viewWork(PredictionTileKey key, boolean surface) {
        return viewBand(key) <= PredictionWorkView.BUFFER || !surface && viewDependencies.contains(key);
    }

    private boolean requiredCoverage(PredictionTileKey key, boolean surface) {
        if (!surface && voxyDetail(key)) return true;
        if (dirtyTiles.contains(key) || fallbackKeys.contains(key)
                || PredictionWorkOrder.scoped(key, layout, buildFocus)) return true;
        if (surface) return false;
        PredictionTile tile = ready.get(key);
        if (tile == null) return true; // Every missing region needs its first usable terrain.
        if ((mediumCoverage.paths().contains(key) || viewDependencies.contains(key)) && tile.scopeOnly()) return true;
        return viewDependencies.contains(key) && transitionTargets.containsKey(key)
                && (tile == null || tile.cellAxis() < transitionTargets.get(key));
    }

    private boolean deferBackground(PredictionTileKey key, boolean surface) {
        // Coarse first coverage remains omnidirectional. Completed invisible detail does not keep upgrading.
        return workView != null && viewBand(key) >= PredictionWorkView.RECENT
                && (surface || ready.containsKey(key))
                && !requiredCoverage(key, surface) && !(!surface && viewDependencies.contains(key));
    }

    /** Scalar validity is captured only when a completed result is polled; no extra world snapshot is copied. */
    private PredictionPlanningValidity.Current planningState() {
        return new PredictionPlanningValidity.Current(layout, workView, buildFocus,
                cameraBlockX, cameraBlockY, cameraBlockZ, surfaceSettings, meshRevision.get(),
                ClientPredictionState.exactCoverageRemovalRevision(), loadingStateVersion, closed, paused);
    }

    private boolean publishViewWork() {
        var result = planning.view.poll(candidate ->
                PredictionPlanningValidity.view(candidate.input(), planningState(), desiredKeys));
        if (result == null) return false;
        var input = result.input();
        if (input.view() != null && input.view().changedFrom(lastScheduledView)) {
            viewStartedNanos = input.now(); viewCompletionRecorded = false;
        }
        viewBands = result.bands(); viewDependencies = result.dependencies(); lastScheduledView = input.view();
        recentlyVisible.clear(); recentlyVisible.putAll(result.recent());
        foregroundWorkPending = true; loadingProgress = PredictionLoadingProgress.INITIALIZING;
        viewRefreshes.increment(); return true;
    }

    private void requestViewWork(long now) {
        if (workView != null && workView.cutFrom(lastScheduledView)) {
            viewBands = Map.of(); viewDependencies = Set.of();
        }
        if (planning.view.pending()) return;
        var recent = workView != null && workView.cutFrom(lastScheduledView) ? Map.<PredictionTileKey, Long>of()
                : inputSnapshots.map(recentlyVisible);
        planning.view.request(new PredictionDemandPreparation.Input(layout, workView, buildFocus,
                desiredSnapshot(), renderSnapshot().tiles(), ordinaryTargets, terrainTargets,
                surfaceDesired, inputSnapshots.set(surfaceReady), recent, sampler.profile().minY(),
                sampler.profile().minY() + sampler.profile().height() + 64, now));
    }

    private static long currentCpuNanos() {
        return CPU_TIMES.isCurrentThreadCpuTimeSupported() && CPU_TIMES.isThreadCpuTimeEnabled()
                ? Math.max(0, CPU_TIMES.getCurrentThreadCpuTime()) : 0;
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
        return !requiredCoverage(key, false) && (!mediumCoverageWork(key) || backgroundWork(key))
                && !PredictionWorkOrder.scoped(key, layout, buildFocus) && !dirtyTiles.contains(key);
    }

    private boolean mediumCoverageWork(PredictionTileKey key) {
        if (!mediumCoveragePending || !mediumCoverage.paths().contains(key)) return false;
        PredictionTile tile = ready.get(key);
        return tile == null || tile.cellAxis() < mediumAxis(key);
    }

    private int mediumAxis(PredictionTileKey key) {
        int axis = mediumCoverage.frontier().contains(key) ? PredictionWorkOrder.PREVIEW_CELL_AXIS
                : sampler.initialTerrainCellAxis(key.lod());
        // A leaf already meeting the screen error does not owe a redundant 32-cell bootstrap upgrade.
        return workView != null && terrainTargets.containsKey(key)
                ? Math.min(axis, ordinaryTargets.getOrDefault(key, axis)) : axis;
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
        long timingStart = PredictionRenderTimings.start();
        try { tickWork(cameraX, cameraY, cameraZ, pixelsPerBlock, focus, vanillaRadius); }
        finally { PredictionRenderTimings.end(PredictionRenderTimings.Stage.CLIENT_SCHEDULE, timingStart); }
    }

    private void tickWork(double cameraX, double cameraY, double cameraZ,
                          double pixelsPerBlock, VssLodFocus focus, int vanillaRadius) {
        if (!VSSClientConfig.CONFIG.predictionSpyglassLoading && focus != null) {
            tick(cameraX, cameraY, cameraZ, pixelsPerBlock, null, vanillaRadius);
            return;
        }
        if (closed || paused) {
            return;
        }
        schedulingPixels = pixelsPerBlock;
        VssLodLayout nextLayout = createLayout();
        boolean changed = !nextLayout.equals(layout) || surfaceSettings() != surfaceSettings
                || !java.util.Objects.equals(buildFocus, focus);
        boolean distanceReduced = nextLayout.maxDistanceBlocks() < layout.maxDistanceBlocks();
        int settings = surfaceSettings();
        if (settings != surfaceSettings) {
            surfaceSettings = settings; meshRevision.incrementAndGet();
            invalidatedSurfaces.addAll(ready.keySet());
            invalidatedSurfaces.addAll(pending);
            invalidatedSurfaces.forEach(this::markDirty); surfaceReady.clear();
        }
        // A different LOD count gives every outstanding plan a different key
        // domain. Retire that demand at its owner, before submitting snapshots
        // with the new layout; resident terrain remains reusable.
        if (layout.levelCount() != nextLayout.levelCount()) {
            voxyDetailTargets.removeIf(key -> key.lod() >= nextLayout.levelCount());
            voxyDetailWork.removeIf(key -> key.lod() >= nextLayout.levelCount());
            detailTraces.keySet().removeIf(key -> key.lod() >= nextLayout.levelCount());
            abandonOldViewWork();
        }
        boolean captureLayoutChanged = layout.levelCount() != nextLayout.levelCount();
        layout = nextLayout;
        if (captureLayoutChanged) captureHandoff.reindex(column -> captureDependencies(
                it.unimi.dsi.fastutil.longs.LongSets.singleton(column)));
        boolean relocated = cameraInitialized && (Math.hypot(cameraX - cameraBlockX, cameraZ - cameraBlockZ) > 128
                || Math.abs(cameraY - cameraBlockY) > 128);
        cameraInitialized = true;
        cameraBlockX = cameraX; cameraBlockY = cameraY; cameraBlockZ = cameraZ; buildFocus = focus;
        if (distanceReduced) {
            fallbackKeys.clear();
            retireAfterDistanceReduction(cameraX, cameraZ);
        }
        long now = System.nanoTime();
        boolean ownershipChanged = refreshUploadedOwnership();
        boolean planChanged = publishPlan(now);
        if (relocated) { abandonOldViewWork(); planChanged = false; }
        boolean demandChanged = publishViewWork();
        boolean priorityChanged = visibilityFeedback != lastScheduledVisibility;
        publishLoadingWork();
        boolean periodic = selectionTicks++ % (PredictionMotionPace.fastMoving() ? 10 : 5) == 0;
        refreshVoxyDetails(periodic || planChanged || relocated);
        if (periodic || changed || relocated || ownershipChanged || scheduledPlan.isEmpty()) requestPlan(vanillaRadius, now);
        if (scheduledPlan.isEmpty()) { servicePreparedWork(now); return; }
        if (workView != null && workView.changedFrom(lastScheduledView)) requestViewWork(now);
        if (planChanged || demandChanged || priorityChanged || periodic) {
            scheduleWork(scheduledPlan, cameraX, cameraZ, now, planChanged);
        }
        pruneReady(Math.floorDiv((int) Math.floor(cameraX), 16), Math.floorDiv((int) Math.floor(cameraZ), 16));
        servicePreparedWork(now);
    }

    /** Navigation invalidates demand, never resident geometry or its content revision. */
    private synchronized void abandonOldViewWork() {
        navigationGeneration++;
        if (diskCache != null) diskCache.resetTerrainProbes();
        voxyOwnedTiles = Set.of(); renderSnapshot = null;
        planning.invalidate();
        scheduledPlan = List.of(); scheduledLeaves = List.of();
        desiredKeys.clear(); desiredKeys.addAll(voxyDetailTargets); desiredGrace.clear(); fallbackKeys.clear();
        terrainLeaves.clear(); ordinaryTargets = terrainTargets = transitionTargets = idleTargets = Map.of();
        surfaceDesired = Set.of(); mediumCoverage = PredictionMediumCoverage.EMPTY; mediumCoveragePending = false;
        idleAllowed = false; idleState = "ordinary-work";
        viewBands = Map.of(); viewDependencies = Set.of(); recentlyVisible.clear(); lastScheduledView = null;
        scheduledOccluded = Set.of(); visibilityFeedback = lastScheduledVisibility = PredictionVisibilityFeedback.Snapshot.EMPTY;
        displayGoals = Map.of();
        loadingDisplayProgress = PredictionLoadingProgress.INITIALIZING; loadingDisplayAt = 0; loadingDisplayCurrent = false;
        preparedWork.clear(); scopeCandidates.clear(); cacheCandidates.clear(); preparedReclaim.clear();
        nearGenerationAdmissions=nearCacheAdmissions=fairGenerationLane=0;
        clearValidatedReclaim(); reclaimTarget = reclaimAdmissionTarget = null; scheduledHints = Map.of();
        queuedRefresh = new Runnable[0]; queuedRefreshIndex = 0;
        for (var pool : List.of(executor, cacheExecutor)) {
            for (Runnable runnable : pool.getQueue().toArray(Runnable[]::new)) {
                if (runnable instanceof PredictionTask task && task.key != null && pool.getQueue().remove(task)) {
                    task.releaseAdmission(); pending.remove(task.key);
                }
            }
        }
        cleanupPending();
        foregroundWorkPending = previewWorkPending = surfacePreviewWorkPending = true;
        loadingProgress = PredictionLoadingProgress.INITIALIZING;
        viewStartedNanos = System.nanoTime(); viewCompletionRecorded = false;
        scheduleAgain = true;
    }

    private void requestPlan(int vanillaRadius, long now) {
        if (planning.plan.pending()) return;
        var residents = renderSnapshot();
        var candidates = planningCandidates.snapshot(inputSnapshots.set(pending),
                inputSnapshots.set(fallbackKeys), inputSnapshots.keys(desiredGrace));
        int voxyRadius = dev.xantha.vss.compat.ModCompat.getVoxyViewDistanceChunks().orElse(0);
        Set<PredictionTileKey> uploaded = Set.of(); // Prediction uploads cannot retire their own voxel source.
        var axes = new ArrayList<Integer>();
        for (int lod = 0; lod < layout.levelCount(); lod++) axes.add(sampler.initialTerrainCellAxis(lod));
        boolean busy = foregroundWorkPending || !pendingCaptures.isEmpty()
                || pending.stream().anyMatch(key -> !idlePending.contains(key) && viewWork(key, surfacePending.contains(key)));
        planning.plan.request(new PredictionPlanPreparation.Input(dimension, layout, workView, buildFocus,
                cameraBlockX, cameraBlockY, cameraBlockZ, schedulingPixels, vanillaRadius,
                768, sampler.profile().minY(),
                sampler.profile().minY() + sampler.profile().height(), vegetation.available(), List.copyOf(axes),
                surfaceSettings, meshRevision.get(), ClientPredictionState.exactCoverageRemovalRevision(), residents,
                inputSnapshots.map(relief), inputSnapshots.set(editedCells), false, candidates, voxyOwnedTiles, uploaded,
                voxyRadius, mediumCoverageLevelBias,
                visibilityFeedback, inputSnapshots.set(dirtyTiles), viewBands, viewDependencies, inputSnapshots.map(failedAt),
                inputSnapshots.map(deferredUntil), idleRefinement.copy(), busy,
                memoryBudget.allowsIdleRefinement() && idleResidentBytes() < Math.min(
                        PredictionIdleRefinement.MAX_RESIDENT_BYTES, idleRejectedBytes), !idlePending.isEmpty(), now, unifiedVoxy));
    }

    private boolean uploadedOwnership(PredictionTileKey key) {
        // Prediction uploads cannot retire the source that feeds the same Voxy nodes.
        return false;
    }

    private boolean refreshUploadedOwnership() {
        long revision = voxyUploads.revision();
        if (voxyUploadRevision == revision) return false;
        voxyUploadRevision = revision;
        if (voxyOwnedTiles.isEmpty()) return false;
        var retained = new HashSet<>(voxyOwnedTiles);
        retained.removeIf(key -> key.lod() >= layout.levelCount() || !uploadedOwnership(key));
        if (retained.size() == voxyOwnedTiles.size()) return false;
        voxyOwnedTiles = Set.copyOf(retained); renderSnapshot = null;
        return true;
    }

    private boolean publishPlan(long now) {
        var result = planning.plan.poll(candidate -> PredictionPlanningValidity.plan(
                candidate, planningState(), this::uploadedOwnership));
        if (result == null) return false;
        boolean goalsChanged = !sameMembers(scheduledPlan, result.plan()) || !sameMembers(scheduledLeaves, result.leaves())
                || !voxyOwnedTiles.equals(result.owned()) || !surfaceDesired.equals(result.surfaces())
                || surfaceRadius != result.surfaceRadius() || !ordinaryTargets.equals(result.ordinary())
                || !terrainTargets.equals(result.terrain()) || !transitionTargets.equals(result.transitions())
                || !mediumCoverage.equals(result.medium()) || !idleTargets.equals(result.idleTargets());
        idleRefinement = result.idle(); idleState = result.idleState();
        idleAllowed = result.idleAllowed() && memoryBudget.allowsIdleRefinement();
        for (var key : result.plan()) desiredGrace.put(key, now);
        desiredGrace.values().removeIf(deadline -> now - deadline > (diskCache == null ? DESIRED_GRACE_NANOS : COLD_TILE_NANOS));
        // A polling result with the same goals is not a new demand generation.
        // Keep publication identities and completed readiness for in-flight work.
        if (!goalsChanged) { refreshMediumCoveragePending(); return false; }
        // The index snapshot may safely omit new positives, but never an already
        // revoked column. All published goal collections are immutable together.
        if (!voxyOwnedTiles.equals(result.owned())) renderSnapshot = null;
        voxyOwnedTiles = result.owned();
        desiredGrace.keySet().removeAll(voxyOwnedTiles); fallbackKeys.removeAll(voxyOwnedTiles);
        // Stop redundant generation, retaining completed fallback content under
        // the existing CPU/GPU cache budgets. Frame coverage owns visibility;
        // a renderer reset must not force regeneration of a just-retired tile.
        surfaceRadius = result.surfaceRadius(); surfaceDesired = result.surfaces();
        var nextDesired = new HashSet<>(result.plan());
        nextDesired.addAll(fallbackKeys); nextDesired.addAll(voxyDetailTargets);
        desiredKeys.removeIf(key -> !nextDesired.contains(key)); desiredKeys.addAll(nextDesired);
        var nextLeaves = new HashSet<>(result.leaves()); nextLeaves.addAll(result.idleTargets().keySet());
        terrainLeaves.removeIf(key -> !nextLeaves.contains(key)); terrainLeaves.addAll(nextLeaves);
        ordinaryTargets = result.ordinary(); terrainTargets = result.terrain(); transitionTargets = result.transitions();
        mediumCoverage = result.medium(); refreshMediumCoveragePending();
        idleTargets = result.idleTargets();
        scheduledPlan = result.plan(); scheduledLeaves = result.leaves();
        foregroundWorkPending = true; previewWorkPending = surfacePreviewWorkPending = true;
        loadingProgress = PredictionLoadingProgress.INITIALIZING;
        requestViewWork(now); return true;
    }

    private static boolean sameMembers(List<PredictionTileKey> a, List<PredictionTileKey> b) {
        return a.equals(b) || a.size() == b.size() && new HashSet<>(a).equals(new HashSet<>(b));
    }

    private void scheduleWork(List<PredictionTileKey> plan, double cameraX, double cameraZ, long now, boolean planChanged) {
        int cx = Math.floorDiv((int) Math.floor(cameraX), 16), cz = Math.floorDiv((int) Math.floor(cameraZ), 16);
        maintainCpuCache(cx, cz, now); maintainRetryMetadata(now);
        if (planChanged) { serviceFallbacks(cx, cz, now); pruneCaptureEpochs(); }
        if (planning.schedule.pending()) { scheduleAgain = true; return; }
        if (diskCache != null && planChanged) probeNearbyCache(plan);
        requestScheduledWork(now);
    }

    private void maintainRetryMetadata(long now) {
        if (deferredCleanup == null) deferredCleanup = deferredUntil.entrySet().iterator();
        if (cacheMissCleanup == null) cacheMissCleanup = cacheMissUntil.entrySet().iterator();
        for (int j = 0; j < 64 && deferredCleanup.hasNext(); j++) {
            var entry = deferredCleanup.next();
            if (now - entry.getValue() >= 0 || !effectivelyDesired(entry.getKey()))
                deferredUntil.remove(entry.getKey(), entry.getValue());
        }
        for (int j = 0; j < 64 && cacheMissCleanup.hasNext(); j++) {
            var entry = cacheMissCleanup.next();
            if (now - entry.getValue() >= 0 || !effectivelyDesired(entry.getKey()))
                cacheMissUntil.remove(entry.getKey(), entry.getValue());
        }
        if (!deferredCleanup.hasNext()) deferredCleanup = null;
        if (!cacheMissCleanup.hasNext()) cacheMissCleanup = null;
    }

    private synchronized void requestScheduledWork(long now) {
        if (planning.schedule.pending() || scheduledPlan.isEmpty()) return;
        var scheduling = schedulingInput(now);
        planning.schedule.request(scheduling);
        if (!planning.loading.pending()) {
            planning.loading.request(loadingInput(scheduling));
        }
        scheduleAgain = false;
    }

    private PredictionSchedulePreparation.Input schedulingInput(long now) {
        var axes = new ArrayList<Integer>();
        for (int lod = 0; lod < layout.levelCount(); lod++) axes.add(sampler.initialTerrainCellAxis(lod));
        return new PredictionSchedulePreparation.Input(layout, buildFocus, workView,
                cameraBlockX, cameraBlockY, cameraBlockZ, schedulingPixels, sampler.profile().minY(),
                sampler.profile().minY() + sampler.profile().height() + 64, meshRevision.get(), scheduledPlan,
                renderSnapshot(), desiredSnapshot(), surfaceDesired, inputSnapshots.set(surfaceReady),
                inputSnapshots.set(dirtyTiles), inputSnapshots.set(fallbackKeys), inputSnapshots.set(pending), ordinaryTargets, terrainTargets,
                transitionTargets, idleTargets, mediumCoverage, mediumCoveragePending, previewWorkPending,
                mediumSinceSurface.get() >= MEDIUM_BUILDS_PER_SURFACE_TURN, PredictionMotionPace.fastMoving(),
                viewBands, viewDependencies, scheduledOccluded, List.copyOf(axes), diskCache, inputSnapshots.map(cacheMissUntil),
                inputSnapshots.map(failedAt), inputSnapshots.map(deferredUntil), now, memoryBudget.reclaimTargetBytes() > 0);
    }

    private synchronized PredictionLoadingPreparation.Input loadingInput(PredictionSchedulePreparation.Input scheduling) {
        return new PredictionLoadingPreparation.Input(scheduling, scheduledLeaves, terrainTargets.keySet(),
                voxyOwnedTiles, inputSnapshots.map(captureEpochs), displayGoals,
                surfaceRadius, visibilityFeedback, surfaceSettings, loadingStateVersion);
    }

    private synchronized void publishLoadingWork() {
        long now = System.nanoTime();
        var current = planningState();
        var result = planning.loading.poll(candidate -> PredictionPlanningValidity.loading(candidate.input(), current,
                scheduledPlan, renderSnapshot(), viewBands) || PredictionPlanningValidity.loadingDisplay(
                candidate.input(), current, scheduledPlan, now));
        if (result == null) return;
        var request = result.input();
        loadingDisplayProgress = result.progress(); loadingDisplayAt = request.scheduling().now(); loadingDisplayCommits++;
        loadingDisplayCurrent = false;
        // Display is a dated observation. Only exact evidence may release the
        // foreground turn, replace display goals or change hidden scheduling.
        if (!PredictionPlanningValidity.loading(request, current, scheduledPlan,
                renderSnapshot(), viewBands)
                && !PredictionPlanningValidity.loading(result,current,loadingInput(schedulingInput(now)))) {
            loadingReadinessDiscards++;
            return;
        }
        loadingReadinessCommits++;
        loadingDisplayCurrent = true;
        loadingProgress = result.progress(); displayGoals = result.goals();
        foregroundWorkPending = result.foreground(); previewWorkPending = result.preview();
        surfacePreviewWorkPending = result.surfacePreview();
        if (!scheduledOccluded.equals(result.hidden())) { scheduledOccluded = result.hidden(); scheduleAgain = true; }
        lastScheduledVisibility = request.visibility();
        if (loadingProgress.view() != null && !viewCompletionRecorded && loadingProgress.view().ready(100)) {
            lastViewReadyNanos = Math.max(0, System.nanoTime() - viewStartedNanos); viewCompletionRecorded = true;
        }
    }

    private void servicePreparedWork(long now) {
        refreshVoxyDetails(false);
        publishVoxyWork();
        long discarded = planning.schedule.discards();
        var result = planning.schedule.poll(candidate ->
                PredictionPlanningValidity.schedule(candidate.input(), planningState(), scheduledPlan));
        if (result != null) {
            synchronized (this) {
                preparedWork.clear(); scopeCandidates.clear(); cacheCandidates.clear();
                preparedReclaim.clear(); preparedReclaim.addAll(result.reclaim());
                clearValidatedReclaim(); reclaimTarget = null;
                scheduledHints = result.hints(); queuedRefresh = executor.getQueue().toArray(Runnable[]::new); queuedRefreshIndex = 0;
                cacheCandidates.addAll(result.cached());
                for (var request : result.work()) {
                    if (PredictionWorkOrder.scoped(request.key(), layout, buildFocus))
                        scopeCandidates.add(new BuildRequest(request.key(), request.surface(), request.priority(), request.distance()));
                    else preparedWork.add(request);
                }
            }
        }
        if (planning.schedule.discards() != discarded) scheduleAgain = true;
        if (scheduleAgain && !planning.schedule.pending()) requestScheduledWork(now);
        int cx = Math.floorDiv((int) Math.floor(cameraBlockX), 16), cz = Math.floorDiv((int) Math.floor(cameraBlockZ), 16);
        long until = System.nanoTime() + 1_000_000L;
        synchronized (this) {
            refreshQueuedWork();
            admissionBatch = true;
            try {
            if (reclaimAdmissionTarget != null) {
                var target = reclaimAdmissionTarget;
                boolean surface = surfaceBuildReady(target);
                if (!effectivelyDesired(target) || !retryReady(target) || deferBackground(target, surface)
                        || !localWorkAllowed(target, surface) || voxyOwnedTiles.contains(target)
                        || captureHandoff.blocked(target)
                        || !generationReady && !cachedUpgrade(target)
                        || generationReady && !cachedUpgrade(target) && !surface && idleWork(target)
                                && !idleAdmissionReady()
                        || !terrainBuildNeeded(target) && !surface) {
                    reclaimAdmissionTarget = null;
                } else if (pending.contains(target)) {
                    return;
                } else {
                    if (voxySource.retiring()) return;
                    enqueue(target, cx, cz, surfaceBuildReady(target));
                    if (!pending.contains(target)) reclaimPreparedWork(cx, cz, until);
                    return;
                }
            }
            restoreCached(cx, cz); refillScope(cx, cz);
            if(unifiedVoxy) serviceGenerationWork(cx, cz, until);
            else servicePreparedCandidates(cx,cz,until);
            if (reclaimPreparedWork(cx, cz, until)) return;
            } finally { admissionBatch = false; }
        }
    }

    private boolean reclaimPreparedWork(int cx, int cz, long until) {
        if (memoryBudget.reclaimTargetBytes() == 0 || memoryBudget.activeBuildCount() != 0) return false;
        // Retired source data remains charged until disposal finishes. Do not
        // evict another working set to pay a shortage already being released.
        if (voxySource.retiring()) return true;
        PredictionTileKey target = reclaimAdmissionTarget;
        int inspected = 0;
        for (var key : voxyDetailWork) {
            if (target != null) break;
            if (inspected++ >= 64 || System.nanoTime() >= until) break;
            if (!pending.contains(key) && retryReady(key) && terrainBuildNeeded(key)) { target = key; break; }
        }
        for (var request : preparedWork) {
            if (target != null) break;
            if (inspected++ >= 64 || System.nanoTime() >= until) break;
            var key = request.key();
            if (desiredKeys.contains(key) && !pending.contains(key) && !idleWork(key) && retryReady(key)
                    && !deferBackground(key, request.surface())
                    && localWorkAllowed(key, request.surface()) && (terrainBuildNeeded(key) || surfaceBuildReady(key))) {
                target = key; break;
            }
        }
        if (target == null) return true;
        // Reclaim must preserve the evidence that selected this replacement.
        // Dropping one of its four children would make the parent fall back to
        // independent sampling, then become dirty again when that child returns.
        var parentSource = surfaceBuildReady(target) ? null
                : parentInput(target, terrainCellAxis(target, ready.get(target)), layout);
        if (!target.equals(reclaimTarget)) { reclaimTarget = target; clearValidatedReclaim(); }
        for (int checked = 0; checked < 64 && !preparedReclaim.isEmpty() && validatedReclaim.size() < 1024
                && System.nanoTime() < until; checked++) {
            var key = preparedReclaim.removeFirst();
            if (!reclaimEligible(key, target, parentSource)) continue;
            var tile = ready.get(key);
            var candidate = new ReclaimCandidate(key, tile.revision(), tile.retainedHeapBytes());
            if (validatedReclaimEntries.putIfAbsent(key, candidate) == null) {
                validatedReclaim.add(candidate); validatedReclaimBytes += candidate.bytes();
            }
        }
        // A visible downgrade must be capable of paying for its replacement.
        // Accumulate bounded slices instead of repeatedly scanning all meshes.
        if (memoryBudget.canReclaimDetail(validatedReclaimBytes)) {
            // Reservations remain charged until disposal completes; account the bytes
            // already assigned to this slice rather than evicting the entire batch.
            long reclaimRemaining = memoryBudget.reclaimTargetBytes();
            for (int removed = 0; removed < 8 && reclaimRemaining > 0 && !validatedReclaim.isEmpty()
                    && memoryBudget.reclaimTargetBytes() > 0 && System.nanoTime() < until; removed++) {
                var candidate = validatedReclaim.removeFirst();
                if (validatedReclaimEntries.get(candidate.key()) != candidate) continue;
                discardReclaim(candidate.key());
                var tile = ready.get(candidate.key());
                if (tile != null && tile.revision() == candidate.revision() && reclaimEligible(candidate.key(), target, parentSource)) {
                    removeTile(candidate.key());
                    reclaimAdmissionTarget = target;
                    reclaimRemaining -= candidate.bytes();
                }
            }
        }
        if (memoryBudget.hasFixedAllowance() && mediumCoveragePending && memoryBudget.reclaimTargetBytes() > 0
                && preparedReclaim.isEmpty() && mediumCoverageLevelBias < layout.levelCount() - 1) {
            mediumCoverageLevelBias++; refreshMediumCoverage(scheduledLeaves); scheduleAgain = true;
        }
        enqueue(target, cx, cz, surfaceBuildReady(target));
        return true;
    }

    private boolean reclaimEligible(PredictionTileKey key, PredictionTileKey target,
                                    PredictionParentLod.Input parentSource) {
        return !key.equals(target) && (parentSource == null || !parentSource.uses(key)) && !pending.contains(key) && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && !mediumCoverage.paths().contains(key) && ready.containsKey(key)
                && coveredByAncestor(ready.keySet(), layout, key)
                && surfaceFallbackReady(key)
                && compareBuildKeys(key, target) > 0;
    }

    private void clearValidatedReclaim() {
        validatedReclaim.clear(); validatedReclaimEntries.clear(); validatedReclaimBytes = 0;
    }
    private void discardReclaim(PredictionTileKey key) {
        var candidate = validatedReclaimEntries.remove(key);
        if (candidate != null) validatedReclaimBytes -= candidate.bytes();
    }

    private record BuildRequest(PredictionTileKey key, boolean surface, int priority, double distance) { }

    private long idleResidentBytes() {
        return idleResidents.values().stream().mapToLong(Long::longValue).sum();
    }

    private boolean idleWork(PredictionTileKey key) {
        if (voxyDetail(key)) return false;
        if (!idleTargets.containsKey(key) || dirtyTiles.contains(key)
                || PredictionWorkOrder.scoped(key, layout, buildFocus)) return false;
        PredictionTile tile = ready.get(key);
        return !ordinaryTargets.containsKey(key) || tile != null && tile.cellAxis() >= ordinaryTargets.get(key);
    }

    private boolean idleAdmissionReady() {
        return idleAllowed
                && memoryBudget.allowsIdleRefinement() && pendingCaptures.isEmpty()
                && !foregroundWorkPending
                && pending.stream().noneMatch(key -> !idlePending.contains(key) && viewWork(key, surfacePending.contains(key)));
    }

    private int workPriority(PredictionTileKey key, boolean surface) {
        if (dirtyTiles.contains(key)) return Integer.MIN_VALUE + 1 + key.lod();
        if (!surface && idleWork(key)) return workView == null ? 2_000_000 : 400_000 + viewPriorityOffset(key, surface);
        var tile = ready.get(key);
        int axis = tile == null ? 0 : tile.cellAxis();
        double distance = PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus);
        if (PredictionWorkOrder.scoped(key, layout, buildFocus))
            return PredictionWorkOrder.priority(key, layout, distance, axis, surface, buildFocus);
        int screen = workView != null && viewBand(key) <= PredictionWorkView.BUFFER && !mediumCoverageWork(key)
                ? screenWorkPriority(key, surface) : 0;
        return viewPriorityOffset(key, surface) + PredictionWorkOrder.coveragePriority(distance, axis, surface, screen);
    }

    private int viewPriorityOffset(PredictionTileKey key, boolean surface) {
        if (occludedWork(key) && !requiredCoverage(key, surface))
            return viewBand(key) == PredictionWorkView.VISIBLE ? 350_000 : 650_000;
        if (viewBand(key) == PredictionWorkView.VISIBLE || !surface && viewDependencies.contains(key)) return 0;
        return switch (viewBand(key)) {
            case PredictionWorkView.BUFFER -> 300_000;
            case PredictionWorkView.RECENT -> 600_000;
            default -> PredictionWorkOrder.BACKGROUND_PRIORITY;
        };
    }

    private int screenWorkPriority(PredictionTileKey key, boolean surface) {
        var tile = ready.get(key);
        double projected = PredictionScreenDetail.projected(workView, key, layout,
                cameraBlockX, cameraBlockY, cameraBlockZ, schedulingPixels,
                sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height() + 64);
        double pixelsPerCell = layout.pixelThreshold() / VssLodLayout.TILE_QUADS;
        double error = surface ? projected * 8 / layout.tileBlocks(key.lod()) / Math.max(1.5, Math.min(3, pixelsPerCell))
                : projected / Math.max(1, tile == null ? 8 : tile.cellAxis()) / pixelsPerCell;
        return (int) (6000 / (1 + Math.max(0, error)));
    }

    private synchronized void refreshQueuedWork() {
        if (closed || paused) return;
        if (queuedRefresh.length == 0 && !executor.getQueue().isEmpty()) {
            queuedRefresh = executor.getQueue().toArray(Runnable[]::new); queuedRefreshIndex = 0;
        }
        long until = System.nanoTime() + 250_000L;
        for (int checked = 0; queuedRefreshIndex < queuedRefresh.length && checked < 32
                && System.nanoTime() < until; checked++) {
            var runnable = queuedRefresh[queuedRefreshIndex]; queuedRefresh[queuedRefreshIndex++] = null;
            queuedRefreshChecks++;
            if (!(runnable instanceof PredictionTask task) || task.key == null) continue;
            var key = task.key;
            boolean retain = key.lod() >= 0 && key.lod() < layout.levelCount() && effectivelyDesired(key)
                    && (cachePending.contains(key) || !deferBackground(key, task.surface))
                    && (cachePending.contains(key) || !idleWork(key) || idleAdmissionReady())
                    && (cachePending.contains(key) || fallbackKeys.contains(key) || localWorkAllowed(key, task.surface)
                        && (task.surface && visibleSurface(key) || !ordinaryRefinement(key) || refinementPending.contains(key))
                        && (!backgroundWork(key) || requiredCoverage(key, false) || backgroundPending.contains(key)));
            if (!retain) {
                if (executor.getQueue().remove(task)) { task.releaseAdmission(); pending.remove(key); }
                continue;
            }
            var hint = (voxyDetail(key) && !task.surface || task.surface && visibleSurface(key))
                    ? new PredictionSchedulePreparation.Hint(workPriority(key, false), workPriority(key, true),
                        PredictionWorkOrder.distanceSquared(key, layout, cameraBlockX, cameraBlockZ)) : scheduledHints.get(key);
            if (hint == null) continue;
            int priority = task.priority == FAIR_WORK_PRIORITY ? FAIR_WORK_PRIORITY
                    : dirtyTiles.contains(key) ? Integer.MIN_VALUE + 1 + key.lod() : cachePending.contains(key) ? -100
                : task.surface ? hint.surface() : hint.terrain();
            if ((priority != task.priority || hint.distance() != task.distanceSquared) && executor.getQueue().remove(task))
                executor.getQueue().offer(new PredictionTask(key, priority, hint.distance(), task.surface, task.delegate, task.cpuReservation, task.buildSlots));
        }
        if (queuedRefreshIndex == queuedRefresh.length) { queuedRefresh = new Runnable[0]; queuedRefreshIndex = 0; }
        cleanupPending();
    }

    private void cleanupPending() {
        cachePending.retainAll(pending); surfacePending.retainAll(pending); idlePending.retainAll(pending);
        scopePending.retainAll(pending); backgroundPending.retainAll(pending); refinementPending.retainAll(pending);
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
                        task.delegate, task.cpuReservation, task.buildSlots));
            }
        }
    }

    private boolean voxyDetailSurface(PredictionTileKey key) {
        return voxyDetail(key) && PredictionWorkOrder.surfaceEligible(key, layout, workView, buildFocus,
                cameraBlockX, cameraBlockY, cameraBlockZ, surfaceRadius, schedulingPixels,
                sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height() + 64);
    }

    private boolean surfaceBuildReady(PredictionTileKey key) {
        PredictionTile tile = ready.get(key);
        boolean terrainReady = tile != null && tile.data().voxyTile != null;
        return (surfaceDesired.contains(key) || voxyDetailSurface(key)) && terrainReady
                && !dirtyTiles.contains(key) && !surfaceReady.contains(key);
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

    private boolean publishTile(PredictionTile tile,
                                PredictionMemoryBudget.Reservation reservation,
                                long revision, long captureEpoch, boolean surface, boolean refreshAncestors,
                                boolean stored, PredictionRelief error) {
        var original = tile.data().voxyTile;
        for (;;) {
            PredictionTile observed;
            synchronized (this) { observed = ready.get(tile.key()); }
            // Immutable comparisons must not occupy the planner/publication monitor.
            var canonical = original.reuseContent(observed == null ? null : observed.data().voxyTile);
            boolean sameTerrain = observed != null && tile.cellAxis() == observed.cellAxis()
                    && tile.spacingBlocks() == observed.spacingBlocks()
                    && canonical.columns() == observed.data().voxyTile.columns()
                    && java.util.Arrays.equals(tile.samples(), observed.samples())
                    && java.util.Arrays.equals(tile.heights(), observed.heights())
                    && java.util.Arrays.equals(tile.groundHeights(), observed.groundHeights());
            synchronized (this) {
                if (ready.get(tile.key()) != observed) continue;
                tile.data().voxyTile = canonical;
                if (sameTerrain) tile.data().terrainRevision = observed.terrainRevision();
                if (sameTerrain && canonical == observed.data().voxyTile
                        && tile.depthBound().equals(observed.depthBound()))
                    tile.data().cacheContentIdentity = observed.data().cacheContentIdentity;
                if (!publishTileLocked(tile,reservation,revision,captureEpoch,surface,refreshAncestors,stored,error)) return false;
                break;
            }
        }
        var canonical=voxySource.put(tile.data().voxyTile, () -> !closed && ready.get(tile.key()) == tile);
        if(canonical!=null) synchronized(this) {
            if(!closed && ready.get(tile.key())==tile) {
                tile.data().voxyTile=canonical;
                reservation.accountResident(tile.retainedHeapBytes());
            }
        }
        return true;
    }

    /** Caller owns the manager monitor; expensive immutable-content walks and notifications run after commit. */
    private boolean publishTileLocked(PredictionTile tile,
                                     PredictionMemoryBudget.Reservation reservation,
                                     long revision, long captureEpoch, boolean surface, boolean refreshAncestors,
                                     boolean stored, PredictionRelief error) {
        if (tile.data().parentLod != null
                && !tile.data().parentLod.current(ready, dirtyTiles, captureEpochs)) return false;
        boolean optional = idlePending.contains(tile.key());
        if (optional && idleResidentBytes() - idleResidents.getOrDefault(tile.key(), 0L)
                + tile.retainedHeapBytes() > PredictionIdleRefinement.MAX_RESIDENT_BYTES) {
            idleRejectedBytes = idleResidentBytes();
            failedAt.put(tile.key(), System.nanoTime());
            return false;
        }
        if (captureEpoch != captureEpochs.getOrDefault(tile.key(), 0L)) captureCancelledBuilds.incrementAndGet();
        if (closed || captureHandoff.blocked(tile.key()) || revision != meshRevision.get()
                || captureEpoch != captureEpochs.getOrDefault(tile.key(), 0L)
                || !effectivelyDesired(tile.key())
                || !reservation.retain(tile.retainedHeapBytes())) {
            return false;
        }
        PredictionTile current = ready.get(tile.key());
        boolean terrainChanged = current == null || current.terrainRevision() != tile.terrainRevision();
        boolean contentChanged = current == null || current.data().voxyTile != tile.data().voxyTile;
        boolean preservesOrdinaryReadiness = optional && current != null && !current.scopeOnly()
                && !dirtyTiles.contains(tile.key()) && current.cellAxis() >= ordinaryTargets.getOrDefault(
                        tile.key(), layout.cellAxis(tile.key().lod()));
        // A late disk/build completion cannot undo already published detail.
        if (current != null && current.spacingBlocks() < tile.spacingBlocks()) return false;
        if (current != null && !surface && surfaceReady.contains(tile.key()) && !dirtyTiles.contains(tile.key())
                && current.spacingBlocks() == tile.spacingBlocks()) return false;
        PredictionMemoryBudget.Reservation previous = residentMemory.put(tile.key(), reservation);
        if (previous != null) previous.close();
        ready.put(tile.key(), tile);
        discardReclaim(tile.key());
        coverageSpatial.put(tile.key(), tile.key());
        if (current == null) coverageSpatialSize++;
        if (optional || idleResidents.containsKey(tile.key())) idleResidents.put(tile.key(), tile.retainedHeapBytes());
        relief.put(tile.key(), error);
        if (stored) storedTiles.add(tile.key()); else storedTiles.remove(tile.key());
        if (surface) {
            surfaceReady.add(tile.key());
            // Refresh only the closest cached tree representation, not every
            // horizon ancestor on every completed vegetation chunk.
            for (int lod=tile.key().lod()+1;refreshAncestors && contentChanged && lod<layout.levelCount();lod++) {
                int shift=lod-tile.key().lod();
                var ancestor=new PredictionTileKey(dimension,tile.key().tileX()>>shift,tile.key().tileZ()>>shift,lod);
                var cached=ready.get(ancestor);
                if (cached != null && cached.spacingBlocks()>2 && cached.spacingBlocks()<=8
                        && desiredKeys.contains(ancestor)) { markDirty(ancestor); break; }
            }
        } else if (tile.data().voxyTile != null && tile.data().voxyTile.surfaceComplete()) surfaceReady.add(tile.key());
        else surfaceReady.remove(tile.key());
        dirtyTiles.remove(tile.key());
        invalidatedSurfaces.remove(tile.key());
        var trace=detailTraces.get(tile.key());
        if(trace!=null)trace.published(System.nanoTime(),captureEpoch);
        markCoverageDirty(tile.key(), !preservesOrdinaryReadiness);
        if (terrainChanged) refreshDerivedParent(tile.key());
        publishedTileCount.increment();
        return true;
    }

    /** Child publication invalidates only its direct source-derived parent. Old geometry stays drawable. */
    private void refreshDerivedParent(PredictionTileKey child) {
        if (child.lod() + 1 >= layout.levelCount()) return;
        var key = new PredictionTileKey(dimension, child.tileX() >> 1, child.tileZ() >> 1, child.lod() + 1);
        var parent = ready.get(key);
        if (parent == null || !desiredKeys.contains(key) || surfaceDesired.contains(key) || dirtyTiles.contains(key)) return;
        var input = PredictionParentLod.capture(key, parent.cellAxis(), layout, ready, dirtyTiles, captureEpochs);
        if (input == null || parent.data().parentLod != null
                && parent.data().parentLod.current(ready, dirtyTiles, captureEpochs)) return;
        captureEpochs.merge(key, 1L, Long::sum);
        markDirty(key);
        failedAt.remove(key);
        markCoverageDirty(key, false);
    }

    private synchronized PredictionParentLod.Input parentInput(PredictionTileKey key, int axis, VssLodLayout buildLayout) {
        return PredictionParentLod.capture(key, axis, buildLayout, ready, dirtyTiles, captureEpochs);
    }

    private synchronized void removeTile(PredictionTileKey key) {
        discardReclaim(key);
        if (ready.remove(key) != null) {
            coverageSpatial.remove(key); coverageSpatialSize--;
            markCoverageDirty(key);
        }
        releaseTileResources(key);
    }

    private synchronized void terrainStored(PredictionTileKey key, long tileRevision, long revision, long captureEpoch) {
        PredictionTile tile = ready.get(key);
        if (!closed && revision == meshRevision.get() && captureEpoch == captureEpochs.getOrDefault(key, 0L)
                && tile != null && tile.revision() == tileRevision && !dirtyTiles.contains(key)) storedTiles.add(key);
    }

    private void persistVoxels(PredictionTile tile, long revision, long captureEpoch,
                                            int settings, byte[] identity) {
        PredictionDiskCache.Lease lease;
        synchronized(this) {
            if (diskCache == null || closed || revision != meshRevision.get()
                    || captureEpoch != captureEpochs.getOrDefault(tile.key(),0L)
                    || ready.get(tile.key()) != tile || dirtyTiles.contains(tile.key())) return;
            lease=diskCache.lease(PredictionDiskCache.Key.voxels(tile.key().tileX(),tile.key().tileZ(),tile.key().lod()));
        }
        try (lease) {
            diskCache.writeVoxelsLater(lease,tile,settings,identity,()->!closed && revision==meshRevision.get()
                    && captureEpoch==captureEpochs.getOrDefault(tile.key(),0L)
                    && ready.get(tile.key())==tile && !dirtyTiles.contains(tile.key())).thenAccept(written -> {
                if (written) terrainStored(tile.key(),tile.revision(),revision,captureEpoch);
            });
        }
    }

    private void releaseTileResources(PredictionTileKey key) {
        surfaceCacheAttempts.remove(key);
        invalidatedSurfaces.remove(key);
        idleResidents.remove(key);
        relief.remove(key);
        storedTiles.remove(key);
        surfaceReady.remove(key);
        PredictionMemoryBudget.Reservation reservation = residentMemory.remove(key);
        voxySource.removeLater(key, () -> !closed && !ready.containsKey(key),
                () -> { if (reservation != null) reservation.close(); });
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
            snapshotChanges.add(key);
            releaseTileResources(key);
            changed = true;
        }
        if (changed) {
            // One ownership invalidation for the batch, avoiding a descendant
            // scan per removed tile when a very large horizon is reduced.
            renderSnapshot = null;
            coverageEpochs.replaceAll((key, epoch) -> epoch + 1);
            for (var key : ready.keySet()) coverageEpochs.putIfAbsent(key, 1L);
            snapshotChanges.addAll(coverageEpochs.keySet());
            coverageHot.addAll(ready.keySet());
        }
        dirtyTiles.removeIf(retire);
        deferredUntil.keySet().removeIf(retire);
        failedAt.keySet().removeIf(retire);
    }

    public VssLodLayout layout() {
        return layout;
    }

    /** Cache context is replaced once, in place, when generation is ready. */
    public ClientTerrainSampler sampler() {
        return sampler;
    }

    boolean generationReady() { return generationReady; }
    PredictionDiskCache diskCache() { return diskCache; }

    /** Profile worker: validate identity before preparing the full decoration context. */
    boolean acceptsGeneration(ClientTerrainSampler replacement) {
        var current = sampler;
        return !closed && !generationReady && current.profile().equals(replacement.profile())
                && java.util.Objects.equals(current.predictionCacheNamespace(), replacement.predictionCacheNamespace())
                && current.cacheBiomes().equals(replacement.cacheBiomes())
                && java.util.Arrays.equals(PredictionVoxelCache.identity(current), PredictionVoxelCache.identity(replacement));
    }

    /** Client thread: resident voxels, source, cache leases and coverage stay owned by this manager. */
    synchronized boolean enableGeneration(ClientTerrainSampler replacement, PredictionVegetation preparedVegetation) {
        if (closed || generationReady) return false;
        var previous = sampler;
        synchronized (surfaceContextLock) {
            sampler = replacement;
            vegetation = preparedVegetation;
            surfaceContextSettings = surfaceSettings;
            generationReady = true;
        }
        previous.close();
        planning.invalidate();
        cacheMissUntil.clear();
        surfaceCacheAttempts.clear();
        selectionTicks = 0;
        scheduleAgain = true;
        queueCaptureDrain();
        return true;
    }

    private PredictionVegetation vegetationForBuild(int settings) {
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
            captureEpochs.merge(key, 1L, Long::sum);
            markDirty(key);
            invalidatedSurfaces.add(key);
            surfaceReady.remove(key);
            failedAt.remove(key);
        }
        if (!affected.isEmpty()) invalidateLoadingWork();
    }

    private int surfaceSettings() {
        return 3 | PredictionVegetation.runtimeSettings(dimension.location());
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
                true,
                VSSClientConfig.CONFIG.rememberTerrain);
    }

    private static PredictionDiskCache openDiskCache(ClientTerrainSampler sampler) {
        var storage = PredictionCacheStorage.current();
        return storage == null ? null : storage.open(sampler);
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

    /** A dirty notice withdraws obsolete inputs; rebuilding waits for their replacement. */
    public synchronized void awaitExactColumns(long[] positions, long[] timestamps) {
        if (closed || positions == null) return;
        var changed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int i = 0; i < positions.length; i++) {
            long column = positions[i];
            long timestamp = timestamps != null && i < timestamps.length ? timestamps[i] : 0L;
            if (captureHandoff.await(column, timestamp, captureDependencies(
                    it.unimi.dsi.fastutil.longs.LongSets.singleton(column)))) changed.add(column);
        }
        withdrawCaptures(changed);
        // Revoke disk leases and in-flight builders, while keeping resident terrain and plants drawable.
        if (diskCache != null && !changed.isEmpty()) diskCache.invalidateChunks(changed);
        for (PredictionTileKey key : captureDependencies(changed)) {
            if (!ready.containsKey(key) && !pending.contains(key)) continue;
            captureEpochs.merge(key, 1L, Long::sum);
            storedTiles.remove(key);
            detailEvent(key, "capture-wait");
        }
    }

    /** The request owner retires out-of-range refreshes; do not leave orphan build barriers. */
    public synchronized void retireExactColumns(long[] columns) {
        boolean changed = false;
        for (long column : columns) changed |= captureHandoff.retired(column);
        if (changed) invalidateLoadingWork(false);
    }

    private void invalidateColumns(it.unimi.dsi.fastutil.longs.LongSet columns) {
        if (closed) return;
        withdrawCaptures(columns);
        capturedTerrainChanged(columns, true);
    }

    private void withdrawCaptures(it.unimi.dsi.fastutil.longs.LongSet columns) {
        for (long columnKey : columns) {
            captureVersions.computeIfPresent(columnKey, (key, version) -> version + 1);
            VoxelColumnData withdrawn = latestCaptures.remove(columnKey);
            if (withdrawn != null) pendingCaptureBytes -= captureBytes(withdrawn);
            captureRetryAfter.remove(columnKey);
            if (!Long.valueOf(columnKey).equals(activeCapture)) {
                pendingCaptures.remove(columnKey); captureVersions.remove(columnKey); captureEdits.remove(columnKey);
            }
            authoritativeCells.add(columnKey);
            editedCells.add(columnKey);
            // Keep the last real snapshot while the next version is in transit.
            sampleCache.removePredictedChunk((int) (columnKey >> 32), (int) columnKey);
        }
    }

    public int readyCount() {
        return ready.size();
    }

    /** Reject voxel content built with outdated generation inputs, such as block tags. */
    synchronized void invalidateContent() {
        meshRevision.incrementAndGet();
        for (PredictionTileKey key : List.copyOf(ready.keySet())) removeTile(key);
        failedAt.clear();
        deferredUntil.clear();
        selectionTicks = 0;
        // Existing tasks retain their pending claims until finally runs, and
        // their old revision prevents publishing voxels from outdated inputs.
    }

    public int pendingCount() {
        return pending.size();
    }

    public long readyVoxelBytes() {
        long bytes = 0;
        for (PredictionTile tile : ready.values()) bytes += tile.data().voxyTile.bytes();
        return bytes;
    }

    public long builtTileCount() {
        return builtTiles.get();
    }

    public String surfaceDiagnostics() {
        long ordinaryRemaining = ordinaryTargets.entrySet().stream().filter(entry -> {
            PredictionTile tile = ready.get(entry.getKey());
            return tile == null || tile.cellAxis() < entry.getValue();
        }).count();
        return "detailDriver=" + (unifiedVoxy ? "voxy" : "vss") + ",radius=" + surfaceRadius + ",eligible=" + surfaceDesired.size() + ",ready=" + surfaceReady.size()
                + ",voxyOwnedTiles=" + voxyOwnedTiles.size()
                + ",groundReady=" + surfaceDesired.stream().filter(this::surfaceBuildReady).count()
                + ",terrainFull=" + ready.values().stream().filter(tile -> tile.cellAxis() == VssLodLayout.TILE_QUADS).count()
                + ",terrainPreview=" + ready.values().stream().filter(tile -> tile.cellAxis() < VssLodLayout.TILE_QUADS).count()
                + ",transitionPending=" + transitionTargets.size()
                + ",terrainRemaining=" + desiredKeys.stream().filter(this::terrainBuildNeeded).count()
                + ",ordinaryTargets=" + ordinaryTargets.size() + ",ordinaryRemaining=" + ordinaryRemaining
                + ",sampleStorage={references=" + ready.values().stream().mapToLong(t -> t.samples().length).sum()
                + ",objects=" + ready.values().stream().mapToLong(t -> t.data().retainedSampleObjects < 0
                        ? t.samples().length : t.data().retainedSampleObjects).sum() + "}"
                + ",viewScheduling={refreshes=" + viewRefreshes.sum()
                + ",planJobs=" + planning.plan.jobs() + ",planCommits=" + planning.plan.commits() + ",planDiscards=" + planning.plan.discards()
                + ",scheduleJobs=" + planning.schedule.jobs() + ",scheduleCommits=" + planning.schedule.commits() + ",admissionChecks=" + admissionChecks
                + ",planPrepareMs=" + planning.plan.preparationNanos() / 1_000_000
                + ",schedulePrepareMs=" + planning.schedule.preparationNanos() / 1_000_000
                + ",queuedRefreshChecks=" + queuedRefreshChecks + ",retirementChecks=" + retirementChecks
                + ",demandJobs=" + planning.view.jobs() + ",demandCommits=" + planning.view.commits() + ",demandDiscards=" + planning.view.discards()
                + ",cacheScanSlices=" + cacheScanSlices
                + ",foregroundDebt=" + foregroundWorkPending + ",deferredAdmissions=" + viewDeferredAdmissions.sum()
                + ",supersededBuilds=" + supersededBuilds.sum()
                + ",visibilityRecords=" + visibilityFeedback.proofs().size()
                + ",visibleCpuMs=" + visibleCpuNanos.sum() / 1_000_000
                + ",backgroundCpuMs=" + backgroundCpuNanos.sum() / 1_000_000
                + ",visibleQueue={" + visibleQueueTimings.diagnostics() + "}"
                + ",backgroundQueue={" + backgroundQueueTimings.diagnostics() + "}"
                + ",viewReadyMs=" + (viewCompletionRecorded ? lastViewReadyNanos / 1_000_000 : -1)
                + ",viewWaitingMs=" + (!viewCompletionRecorded && viewStartedNanos != 0
                        ? Math.max(0, System.nanoTime() - viewStartedNanos) / 1_000_000 : 0)
                + ",loading=" + loadingProgress.view() + "}"
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
                    return tile == null || tile.cellAxis() < mediumAxis(key);
                }).count()
                + "," + memoryBudget.diagnostics()
                + "," + memoryBudget.costDiagnostics()
                + ",cpuCache={coldBytes=" + coldCacheBytes + ",highBytes=" + cpuCachePolicy.highBytes
                + ",lowBytes=" + cpuCachePolicy.lowBytes + ",evictions=" + coldCacheEvictions
                + ",releasedBytes=" + coldCacheReleasedBytes + "}"
                + ",residency={ready=" + ready.size()
                + ",desired=" + desiredKeys.size()
                + ",fine=" + ready.values().stream().filter(tile -> tile.key().lod() <= 1).count()
                + ",coarse=" + ready.values().stream().filter(tile -> tile.key().lod() >= Math.max(0, layout.levelCount() - 2)).count()
                + ",fallbackPending=" + fallbackKeys.size()
                + ",retiredOutOfRange=" + retiredOutOfRange.get()
                + ",retainedForFallback=" + retainedForFallback.get()
                + ",fallbackParentsQueued=" + fallbackParentsQueued.get() + "}"
                + "," + capturedTerrain.diagnostics()
                + "," + captureHandoff.diagnostics()
                + ",captureQueue=" + pendingCaptures.size() + ",captureQueueBytes=" + pendingCaptureBytes
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
                + ",terrainPlan={" + sampler.predictionBackendDiagnostics() + "}"
                + ",climateCompiler={" + sampler.decorationContext().climateCompilerDiagnostics() + "}"
                + ",wallEvidence={mode=capturedOnly}"
                + ",stageTotalMs={sample=" + samplingNanos.sum() / 1_000_000
                + ",surface=" + decorationNanos.sum() / 1_000_000
                + ",mesh=" + meshingNanos.sum() / 1_000_000
                + "}"
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
                + ",sampleFallbackPoints=" + colorFallbackPoints.sum()
                + ",adaptiveDisplay={accepted=" + adaptiveDisplayAccepted.sum()
                + ",rejected=" + adaptiveDisplayRejected.sum()
                + ",filled=" + adaptiveDisplayFilled.sum() + "}"
                + ",earlyCaptureExits=" + captureEarlyExits.sum() + ",captureDiscardMs=" + captureDiscardNanos.sum()/1_000_000 + "}"
                + ",buildSources={diskHit=" + diskHitBuilds.sum()
                + ",diskMiss=" + diskMissBuilds.sum()
                + ",reusedPoints=" + reusedPointBuilds.sum()
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
                + ",voxelRestores=" + voxelRestores.sum()
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
        markCoverageDirty(key, true);
    }

    private synchronized void markCoverageDirty(PredictionTileKey key, boolean resetReadiness) {
        renderSnapshot = null;
        snapshotChanges.add(key);
        invalidateLoadingWork(resetReadiness);
        VssLodLayout tileLayout = layout;
        if (tileLayout != null && key.lod() + 1 < tileLayout.levelCount()) {
            int span = tileLayout.tileBlocks(key.lod()) / 16;
            for (int lod = key.lod() + 1; lod < tileLayout.levelCount(); lod++) {
                int ancestorSpan = tileLayout.tileBlocks(lod) / 16;
                PredictionTileKey ancestor = new PredictionTileKey(key.dimension(),
                        Math.floorDiv(key.tileX() * span, ancestorSpan),
                        Math.floorDiv(key.tileZ() * span, ancestorSpan), lod);
                coverageEpochs.merge(ancestor, 1L, Long::sum);
                snapshotChanges.add(ancestor);
                coverageHot.add(ancestor);
            }
        }
        coverageEpochs.merge(key, 1L, Long::sum);
        coverageHot.add(key);
        // A late parent can replace a finer fallback too, so its resident
        // descendants must release the same region on the next resolve.
        // Restore the index if a headless fixture/legacy caller populated ready
        // directly. Normal publication/removal maintains it incrementally.
        if (coverageSpatialSize != ready.size()) {
            coverageSpatial.clear(); ready.keySet().forEach(k -> coverageSpatial.put(k, k));
            coverageSpatialSize = ready.size();
        }
        long span = 64L << key.lod(), x = key.tileX() * span, z = key.tileZ() * span;
        coverageSpatial.intersect(x, z, x + span, z + span, descendant -> {
            if (descendant.lod() >= key.lod()) return;
            int levels = key.lod() - descendant.lod();
            if ((descendant.tileX() >> levels) == key.tileX()
                    && (descendant.tileZ() >> levels) == key.tileZ()) {
                coverageEpochs.merge(descendant, 1L, Long::sum);
                snapshotChanges.add(descendant);
                coverageHot.add(descendant);
            }
        });
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

    /** Complete server/local snapshots share one bounded, serialized lane on the existing workers. */
    public synchronized boolean captureExactColumn(int chunkX, int chunkZ, VoxelColumnData data) {
        if (closed || data == null || !data.completesRequest() || !data.authoritative()) return false;
        long captureKey = pack(chunkX, chunkZ);
        if (!captureHandoff.accepts(captureKey, data.columnTimestamp())) return false;
        if (!pendingCaptures.contains(captureKey) && pendingCaptures.size() >= MAX_PENDING_CAPTURES) return false;
        long size = captureBytes(data);
        VoxelColumnData old = latestCaptures.get(captureKey);
        long replacementBytes = pendingCaptureBytes + size - (old == null ? 0 : captureBytes(old));
        if (replacementBytes > MAX_PENDING_CAPTURE_BYTES) return false;
        pendingCaptureBytes = replacementBytes;
        pendingCaptures.add(captureKey);
        latestCaptures.put(captureKey, data);
        if (captureHandoff.awaiting(captureKey)) captureEdits.add(captureKey);
        captureRetryAfter.remove(captureKey);
        captureVersions.merge(captureKey, 1L, Long::sum);
        queueCaptureDrain();
        return true;
    }

    synchronized boolean captureLocalColumn(int chunkX, int chunkZ, VoxelColumnData data, boolean edited) {
        // Local snapshots have a different clock and cannot settle a server version watermark.
        if (captureHandoff.awaiting(pack(chunkX, chunkZ))) return false;
        if (!captureExactColumn(chunkX, chunkZ, data)) return false;
        if (edited) captureEdits.add(pack(chunkX, chunkZ));
        return true;
    }

    public synchronized boolean requiresExactCapture(int chunkX, int chunkZ) {
        return captureHandoff.awaiting(pack(chunkX, chunkZ));
    }

    private static long captureBytes(VoxelColumnData data) {
        long size = 256;
        for (var section : data.sections()) if (section != null && section.section() != null)
            size += 8192L + section.section().getStates().getSerializedSize() + section.section().getBiomes().getSerializedSize();
        return size;
    }

    private synchronized void queueCaptureDrain() {
        if (closed || !generationReady || captureDrainQueued || latestCaptures.isEmpty()) return;
        long now = System.nanoTime();
        if (latestCaptures.keySet().stream().noneMatch(key -> now >= captureRetryAfter.getOrDefault(key, 0L))) return;
        captureDrainQueued = true;
        try { executor.execute(new PredictionTask(Integer.MIN_VALUE, Long.MAX_VALUE, this::drainCaptures)); }
        catch (RejectedExecutionException ignored) { captureDrainQueued = false; }
    }

    private void drainCaptures() {
        var cpu = cpuBudget.tryAcquire(true);
        var reservation = memoryBudget.tryReserve(2L * PredictionMemoryBudget.MIB);
        try {
            if (cpu == null || reservation == null) return;
            for (int batch = 0; batch < 4 && !closed; batch++) {
                long captureKey, version;
                VoxelColumnData current;
                synchronized (this) {
                    long now = System.nanoTime();
                    Long next = latestCaptures.keySet().stream()
                            .filter(key -> now >= captureRetryAfter.getOrDefault(key, 0L)).findFirst().orElse(null);
                    if (next == null) return;
                    captureKey = next;
                    activeCapture = captureKey;
                    // Retain the accepted snapshot and account for its bytes until it commits.
                    current = latestCaptures.get(captureKey);
                    version = captureVersions.getOrDefault(captureKey, 0L);
                }
                int chunkX = (int) (captureKey >> 32), chunkZ = (int) captureKey;
                try {
                    var samples = ClientCaptureExtractor.extractAll(chunkX, chunkZ, current, sampler);
                    synchronized (this) {
                        if (closed || version != captureVersions.getOrDefault(captureKey, -1L)) continue;
                    }
                    // Disk IO and mip generation never hold the monitor used by rendering/ticks.
                    boolean changed = capturedTerrain.put(chunkX, chunkZ, samples);
                    synchronized (this) {
                        // A newer notice can arrive during IO. It alone owns the input barrier now.
                        if (closed || version != captureVersions.getOrDefault(captureKey, -1L)) continue;
                        for (int index = 0; index < samples.length; index++) {
                            long sampleKey = pack(chunkX * 16 + (index & 15), chunkZ * 16 + (index >>> 4));
                            sampleCache.put(sampleKey, samples[index]);
                            if (sampleStore != null) sampleStore.remove(sampleKey);
                        }
                        boolean edited = captureEdits.contains(captureKey);
                        boolean refreshed = captureHandoff.committed(captureKey, current.columnTimestamp());
                        if (changed || refreshed) {
                            if (edited || refreshed) editedCells.add(captureKey);
                            // Even identical sampled ground may contain changed trees/buildings.
                            capturedTerrainChanged(it.unimi.dsi.fastutil.longs.LongSets.singleton(captureKey), edited || refreshed);
                        }
                        latestCaptures.remove(captureKey);
                        pendingCaptureBytes -= captureBytes(current);
                        captureRetryAfter.remove(captureKey);
                    }
                } catch (java.util.concurrent.CancellationException failure) {
                    retryCapture(captureKey, version);
                    if (Thread.currentThread().isInterrupted()) return;
                } catch (RuntimeException failure) {
                    retryCapture(captureKey, version);
                    dev.xantha.vss.common.VSSLogger.warn("VSS real-terrain capture failed; retrying retained snapshot", failure);
                } finally {
                    synchronized (this) {
                        if (!latestCaptures.containsKey(captureKey)) {
                            pendingCaptures.remove(captureKey); captureVersions.remove(captureKey); captureEdits.remove(captureKey);
                        }
                        activeCapture = null;
                    }
                }
            }
        } finally {
            if (reservation != null) reservation.close();
            if (cpu != null) cpu.close();
            synchronized (this) { captureDrainQueued = false; }
        }
    }

    private synchronized void retryCapture(long key, long version) {
        if (closed || version != captureVersions.getOrDefault(key, -1L)) return;
        captureRetryAfter.put(key, System.nanoTime() + 1_000_000_000L);
    }

    boolean acceptsLocalCapture() {
        synchronized (this) { return !closed && pendingCaptures.size() < MAX_PENDING_CAPTURES
                && pendingCaptureBytes < MAX_PENDING_CAPTURE_BYTES - 2L * PredictionMemoryBudget.MIB; }
    }

    final PredictionLocalCaptures localCaptures = new PredictionLocalCaptures();

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
        Set<PredictionTileKey> affected = captureDependencies(columns);
        affected.removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
        long now = System.nanoTime();
        for (PredictionTileKey key : affected) {
            captureEpochs.merge(key, 1L, Long::sum);
            captureRefresh.changed(key, now);
            storedTiles.remove(key);
            markDirty(key);
            if (worldEdit) {
                invalidatedSurfaces.add(key);
                surfaceReady.remove(key);
            }
            failedAt.remove(key);
            deferredUntil.remove(key);
        }
        if (!affected.isEmpty()) invalidateLoadingWork();
    }

    private Set<PredictionTileKey> captureDependencies(it.unimi.dsi.fastutil.longs.LongSet columns) {
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
                        // An area mip depends on every intersecting capture, not just grid nodes.
                        affected.add(key);
                    }
                }
            }
        }
        return affected;
    }

    /** Capture/structure dirtiness can change without publishing a new mesh snapshot. */
    private void invalidateLoadingWork() {
        invalidateLoadingWork(true);
    }

    private void invalidateLoadingWork(boolean resetReadiness) {
        loadingStateVersion++;
        loadingDisplayCurrent = false;
        // An optional upgrade keeps the completed ordinary mesh drawable.
        // Reject stale worker results without reacquiring the loading turn.
        if (resetReadiness) {
            foregroundWorkPending = true;
            loadingProgress = PredictionLoadingProgress.INITIALIZING;
        }
        scheduleAgain = true;
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
                && !captureHandoff.blocked(key)
                && epoch == captureEpochs.getOrDefault(key, 0L)
                && effectivelyDesired(key);
    }

    private void requireCaptureCurrent(PredictionTileKey key, long epoch, long revision) {
        if (!captureCurrent(key, epoch, revision)) {
            captureEarlyExits.increment();
            throw new java.util.concurrent.CancellationException("prediction capture changed");
        }
    }

    /** Current view demand is independent of content validity and cached detail.
     * Only an optional upgrade with existing coverage may be stopped. */
    private boolean buildDemandCurrent(PredictionTileKey key, int axis, boolean surface, boolean cacheOnly) {
        if (cacheOnly || workView == null) return true;
        PredictionTile resident = ready.get(key);
        if (resident == null || requiredCoverage(key, surface)) return true;
        if (voxyOwnedTiles.contains(key) || deferBackground(key, surface)) return false;
        if (surface) return surfaceBuildReady(key);
        // Admission and running checkpoints share the same target, including
        // the medium frontier needed before this region's local detail can run.
        return targetCellAxis(key) >= axis;
    }

    private static final class PredictionDemandChanged extends java.util.concurrent.CancellationException {
        PredictionDemandChanged() { super("prediction view demand changed"); }
    }

    private boolean buildCurrent(PredictionTileKey key, long epoch, long revision,
                                 int axis, boolean surface, boolean cacheOnly) {
        if (!captureCurrent(key, epoch, revision)) return false;
        if (!buildDemandCurrent(key, axis, surface, cacheOnly)) throw new PredictionDemandChanged();
        return true;
    }

    private void requireBuildCurrent(PredictionTileKey key, long epoch, long revision,
                                     int axis, boolean surface, boolean cacheOnly) {
        if (!buildCurrent(key, epoch, revision, axis, surface, cacheOnly)) {
            captureEarlyExits.increment();
            throw new java.util.concurrent.CancellationException("prediction capture changed");
        }
    }

    private synchronized void pruneCaptureEpochs() {
        captureRefresh.retain(dirtyTiles);
        captureEpochs.keySet().removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
        dirtyTiles.removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
        invalidatedSurfaces.removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
        surfaceCacheAttempts.keySet().removeIf(key -> !ready.containsKey(key) && !pending.contains(key));
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
        if (renderSnapshot == null || !renderSnapshot.layout().equals(layout)) {
            // Meshes and epochs are published under the same lock. Unchanged hash
            // pages remain shared with older, immutable frame snapshots.
            if (publishedTiles == null) {
                publishedTiles = new PredictionTileTable<PredictionTile>(dimension).changed(ready, List.of());
                publishedEpochs = new PredictionTileTable<Long>(dimension).changed(coverageEpochs, List.of());
                for (var tile : ready.values()) scopeFamilies.put(tile.key(),tile.scopeOnly());
            } else if (!snapshotChanges.isEmpty()) {
                Map<PredictionTileKey, PredictionTile> tiles = new java.util.HashMap<>();
                Map<PredictionTileKey, Long> epochs = new java.util.HashMap<>();
                List<PredictionTileKey> removedTiles = new ArrayList<>(), removedEpochs = new ArrayList<>();
                for (var key : snapshotChanges) {
                    var tile = ready.get(key);
                    if (tile == null) removedTiles.add(key); else tiles.put(key, tile);
                    if (tile == null) scopeFamilies.remove(key); else scopeFamilies.put(key,tile.scopeOnly());
                    var epoch = coverageEpochs.get(key);
                    if (epoch == null) removedEpochs.add(key); else epochs.put(key, epoch);
                }
                publishedTiles = publishedTiles.changed(tiles, removedTiles);
                publishedEpochs = publishedEpochs.changed(epochs, removedEpochs);
            }
            snapshotChanges.clear();
            renderSnapshot = new RenderSnapshot(dimension, layout, publishedTiles, publishedEpochs,
                    scopeFamilies.snapshot(), voxyOwnedTiles);
        }
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

        /** Decoration can retain an uploaded parent while a finer ground-only child loads. */
        PredictionTile coveringFeatureTileAtDetail(int x, int z, int desiredLod) {
            if (desiredLod < 0) return null;
            PredictionTile ordinary = null, finest = null;
            for (int lod = 0; lod < layout.levelCount(); lod++) {
                PredictionTile tile = at(x, z, lod);
                if (tile == null || !(tile.data().voxyTile.surfaceComplete() || tile.data().voxyTile.hasVegetation())) continue;
                if (betterFeatures(tile, finest)) finest = tile;
                if (!tile.scopeOnly() && betterFeatures(tile, ordinary)) ordinary = tile;
            }
            if (desiredLod <= 0 || !scopedTiles) return finest;
            int target = 1 << Math.min(20, desiredLod);
            return ordinary != null && (finest == ordinary || ordinary.spacingBlocks() <= target * 2)
                    ? ordinary : finest;
        }

        private static boolean betterFeatures(PredictionTile tile, PredictionTile previous) {
            if (previous == null) return true;
            boolean ready = tile.data().voxyTile.surfaceComplete(), oldReady = previous.data().voxyTile.surfaceComplete();
            return ready != oldReady ? ready : tile.spacingBlocks() < previous.spacingBlocks();
        }
        private PredictionTile at(int chunkX, int chunkZ, int lod) {
            int span = layout.tileBlocks(lod) / 16;
            if(paged!=null) return paged.at(Math.floorDiv(chunkX,span),Math.floorDiv(chunkZ,span),lod);
            if (levels[lod] == null) return null;
            return levels[lod].get(pack(Math.floorDiv(chunkX, span), Math.floorDiv(chunkZ, span)));
        }
        long epoch(PredictionTileKey key) { return epochs.getOrDefault(key, 0L); }

        /** Only sub-cell resident footprints can split this tile's ownership cells. */
        List<PredictionTile> subCellTiles(PredictionTile tile) {
            var result = new ArrayList<PredictionTile>();
            for (int lod = 0; lod < layout.levelCount() && layout.tileBlocks(lod) < tile.spacingBlocks(); lod++) {
                int span = layout.tileBlocks(lod), minX = Math.floorDiv(tile.baseBlockX()-1, span), minZ = Math.floorDiv(tile.baseBlockZ()-1, span);
                int maxX = Math.floorDiv(tile.baseBlockX() + tile.spanBlocks(), span);
                int maxZ = Math.floorDiv(tile.baseBlockZ() + tile.spanBlocks(), span);
                if (paged != null) paged.forEachInBounds(lod, minX, minZ, maxX, maxZ, result::add);
                else if (levels[lod] != null) for (var candidate : levels[lod].values())
                    if (candidate.key().tileX() >= minX && candidate.key().tileX() <= maxX
                            && candidate.key().tileZ() >= minZ && candidate.key().tileZ() <= maxZ) result.add(candidate);
            }
            result.sort(java.util.Comparator.comparingInt((PredictionTile t)->t.key().lod())
                    .thenComparingInt(t->t.key().tileX()).thenComparingInt(t->t.key().tileZ()));
            return result;
        }
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

    private boolean retryReady(PredictionTileKey key) {
        Long deadline = deferredUntil.get(key);
        if (deadline != null && System.nanoTime() - deadline < 0) return false;
        Long failed = failedAt.get(key);
        return failed == null || System.nanoTime() - failed > FAILED_RETRY_NANOS;
    }

    /** Held from admission through completion, including queue cancellation/reprioritization. */
    private final class BuildSlots implements AutoCloseable {
        final boolean surface;
        final AtomicBoolean held = new AtomicBoolean(true);
        BuildSlots(boolean surface) { this.surface = surface; }
        @Override public void close() {
            if (!held.compareAndSet(true, false)) return;
            if (surface) activeSurfaceBuilds.decrementAndGet();
            activeDetailBuilds.decrementAndGet();
        }
    }

    private BuildSlots tryReserveBuildSlots(boolean surface) {
        int detailLimit = PredictionWorkOrder.detailBuildLimit(executor.getCorePoolSize(), previewWorkPending, buildFocus != null);
        if (activeDetailBuilds.get() >= detailLimit) { admissionReasons[ADMIT_NO_DETAIL_SLOT].increment(); return null; }
        int surfaceLimit = PredictionWorkOrder.surfaceBuildLimit(executor.getCorePoolSize(), refinementLimit());
        if (surface && activeSurfaceBuilds.get() >= surfaceLimit) { admissionReasons[ADMIT_NO_SURFACE_SLOT].increment(); return null; }
        activeDetailBuilds.incrementAndGet();
        if (surface) activeSurfaceBuilds.incrementAndGet();
        return new BuildSlots(surface);
    }

    private void refreshMediumCoverage(List<PredictionTileKey> leaves) {
        mediumCoverage = PredictionMediumCoverage.plan(leaves, layout, mediumCoverageLevelBias);
        refreshMediumCoveragePending();
    }

    private void refreshMediumCoveragePending() {
        mediumCoveragePending = mediumCoverage.frontier().stream().anyMatch(key -> {
            PredictionTile tile = ready.get(key);
            if (tile != null && tile.cellAxis() >= mediumAxis(key)) return false;
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

    java.util.Map<String,Object> demandSnapshot() {
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("publishedTiles",publishedTileCount.sum());
        result.put("voxyRendering", unifiedVoxy);
        result.put("detailDriver", unifiedVoxy ? "voxy" : "vss");
        result.put("voxyOverlayBytes", voxySource.bytes());
        result.put("voxyDetailTargets", voxyDetailTargets.size());
        var details=detailPipelineSnapshot();
        result.put("voxyDetailRemaining", details.get("remaining"));
        result.put("detailPipeline",details);
        var admission=new java.util.LinkedHashMap<String,Long>();
        String[] reasons={"notDesired","released","medium","terrainNeeded","surfaceReady","noPrevious",
                "noReservation","noDetailSlot","noSurfaceSlot","cpuBudget"};
        for(int i=0;i<reasons.length;i++)admission.put(reasons[i],admissionReasons[i].sum());
        result.put("admission",admission);
        result.put("voxyOverlay", dev.xantha.vss.compat.VoxyPredictionBridge.diagnostics());
        result.put("voxySource", voxySource.diagnostics());
        result.put("generationActive",executor.getActiveCount()); result.put("cacheActive",cacheExecutor.getActiveCount());
        result.put("generationQueued",executor.getQueue().size()); result.put("cachePending",cachePending.size());
        result.put("sampleNanos",samplingNanos.sum()); result.put("surfaceNanos",decorationNanos.sum());
        result.put("meshNanos",meshingNanos.sum());
        result.put("diskNanos",diskReadNanos.sum()); result.put("nativeNanos",nativeSampleNanos.sum());
        result.put("colorNanos",colorNanos.sum()); result.put("exteriorNanos",exteriorNanos.sum());
        result.put("queueWaitNanos",queueWaitNanos.sum()); result.put("diskHits",diskHitBuilds.sum());
        result.put("diskMisses",diskMissBuilds.sum());
        result.put("contentVersion",meshRevision.get()); result.put("readyTiles",ready.size());
        result.put("pending",pending.size()); result.put("built",builtTiles.get()); result.put("failed",failedTiles.get());
        result.put("supersededBuilds",supersededBuilds.sum()); result.put("viewRefreshes",viewRefreshes.sum());
        result.put("visibleCpuNanos",visibleCpuNanos.sum()); result.put("backgroundCpuNanos",backgroundCpuNanos.sum());
        result.put("ordinaryTargets",ordinaryTargets.size()); result.put("idleTargets",idleTargets.size());
        result.put("surfaceDesired",surfaceDesired.size()); result.put("surfaceReady",surfaceReady.size());
        result.put("surfacePipeline",surfacePipelineSnapshot());
        var currentVegetation = vegetation;
        result.put("vegetation",currentVegetation == null ? Map.of("initialized",false) : currentVegetation.performanceSnapshot());
        result.put("generationReady", generationReady);
        result.put("foregroundDebt",foregroundWorkPending); result.put("voxyOwnedTiles",voxyOwnedTiles.size());
        result.put("planJobs",planning.plan.jobs()); result.put("planCommits",planning.plan.commits()); result.put("planDiscards",planning.plan.discards());
        result.put("planTopologyBuilds",planning.planCalculation.topologyBuilds());
        result.put("planTopologyHits",planning.planCalculation.topologyHits());
        result.put("planCoverageBuilds",planning.planCalculation.coverageBuilds());
        result.put("planCoverageHits",planning.planCalculation.coverageHits());
        result.put("scheduleJobs",planning.schedule.jobs()); result.put("scheduleCommits",planning.schedule.commits());
        result.put("admissionChecks",admissionChecks); result.put("queuedRefreshChecks",queuedRefreshChecks);
        result.put("retirementChecks",retirementChecks); result.put("planPrepareNanos",planning.plan.preparationNanos());
        result.put("schedulePrepareNanos",planning.schedule.preparationNanos());
        result.put("loadingJobs",planning.loading.jobs()); result.put("loadingCommits",planning.loading.commits()); result.put("loadingDiscards",planning.loading.discards());
        result.put("loadingPrepareNanos",planning.loading.preparationNanos());
        result.put("loadingDisplayCommits",loadingDisplayCommits); result.put("loadingReadinessCommits",loadingReadinessCommits);
        result.put("loadingReadinessDiscards",loadingReadinessDiscards);
        result.put("progressObservation", loadingObservation());
        return result;
    }

    /** Sampled diagnostics only; never used to admit, discard or lower the quality of work. */
    private synchronized Map<String,Object> detailPipelineSnapshot() {
        long now=System.nanoTime();int remaining=0;
        var levels=new java.util.TreeMap<Integer,Map<String,Object>>();
        var oldest=new java.util.PriorityQueue<Map<String,Object>>(java.util.Comparator.comparingDouble(v->(double)v.get("ageSeconds")));
        for(var key:voxyDetailTargets) {
            var row=levels.computeIfAbsent(key.lod(),ignored->{
                var counts=new java.util.LinkedHashMap<String,Object>();
                for(String name:List.of("targets","missing","dirty","upgrading","complete","pending","visibleRemaining"))counts.put(name,0);
                return counts;
            });
            row.put("targets",(int)row.get("targets")+1);
            var tile=ready.get(key);int axis=tile==null?0:tile.cellAxis(),target=targetCellAxis(key);
            String state=tile==null?"missing":dirtyTiles.contains(key)?"dirty":axis<target?"upgrading":"complete";
            row.put(state,(int)row.get(state)+1);
            boolean queued=pending.contains(key);
            if(queued)row.put("pending",(int)row.get("pending")+1);
            if(state.equals("complete"))continue;
            remaining++;boolean visible=viewWork(key,false);
            if(visible)row.put("visibleRemaining",(int)row.get("visibleRemaining")+1);
            var trace=detailTraces.get(key);
            double age=trace==null?0:Math.max(0,now-trace.demandSince)/1e9;
            if(oldest.size()<8 || age>(double)oldest.peek().get("ageSeconds")) {
                var detail=new java.util.LinkedHashMap<String,Object>();
                detail.putAll(Map.<String,Object>of("x",key.tileX(),"z",key.tileZ(),"lod",key.lod(),"state",state,
                        "axis",axis,"targetAxis",target,"pending",queued,"visible",visible,"ageSeconds",age,
                        "ageMeaning","demand"));
                if(trace!=null)detail.putAll(trace.snapshot(now,dirtyTiles.contains(key)));
                detail.put("contentRevision",captureEpochs.getOrDefault(key,0L));
                oldest.add(detail);if(oldest.size()>8)oldest.poll();
            }
        }
        var samples=new ArrayList<>(oldest);samples.sort(java.util.Comparator.comparingDouble(v->-(double)v.get("ageSeconds")));
        return Map.of("remaining",remaining,"byLod",levels,"oldestIncomplete",samples,"maxQueueWaitSeconds",maxQueueWaitNanos.get()/1e9);
    }

    /** Separate current plant demand from the global resident completion count. */
    private synchronized Map<String,Object> surfacePipelineSnapshot() {
        int terrain = 0, completed = 0;
        for (var key : surfaceDesired) {
            var tile = ready.get(key);
            if (tile != null && tile.data().voxyTile != null && !dirtyTiles.contains(key)) terrain++;
            if (surfaceReady.contains(key) && !dirtyTiles.contains(key)) completed++;
        }
        return Map.of("desired",surfaceDesired.size(),"terrainReady",terrain,"completed",completed,
                "pending",surfacePending.size(),"active",activeSurfaceBuilds.get(),
                "queuedHints",visibleSurfaceWork.size());
    }

    /** Diagnostics only. Retained counts carry age and cannot release loading admission. */
    Map<String, Object> loadingObservation() {
        var progress = loadingDisplayProgress;
        double age = loadingDisplayAt == 0 ? -1 : Math.max(0, System.nanoTime() - loadingDisplayAt) / 1e9;
        boolean fresh = loadingDisplayCurrent && progress == loadingProgress && age >= 0 && age <= 1 && !closed && !paused;
        return Map.ofEntries(Map.entry("coverageReady", progress.coverageReady()), Map.entry("coverageTotal", progress.coverageTotal()),
                Map.entry("targetReady", progress.targetReady()), Map.entry("targetTotal", progress.targetTotal()),
                Map.entry("surfaceReady", progress.surfaceReady()), Map.entry("surfaceTotal", progress.surfaceTotal()),
                Map.entry("waitingFor", fresh ? progress.waitingFor(95) : loadingDisplayAt == 0 ? "initializing" : "refreshing"),
                Map.entry("observedWaitingFor", progress.waitingFor(95)), Map.entry("readinessWaitingFor", loadingProgress.waitingFor(95)),
                Map.entry("fresh", fresh), Map.entry("ageSeconds", age));
    }

    private boolean terrainParentReady(PredictionTileKey key) {
        return voxyDetail(key) || ready.containsKey(key) || key.lod() == layout.levelCount()-1
                || ready.containsKey(new PredictionTileKey(key.dimension(), key.tileX() >> 1,
                        key.tileZ() >> 1, key.lod() + 1));
    }

    private boolean localWorkAllowed(PredictionTileKey key, boolean surface) {
        return surface || terrainParentReady(key);
    }

    private int targetCellAxis(PredictionTileKey key) {
        if (voxyDetail(key)) return layout.cellAxis(key.lod());
        // The immutable map remains valid while replanning rebuilds terrainLeaves.
        int target = terrainTargets.getOrDefault(key, sampler.initialTerrainCellAxis(key.lod()));
        if (mediumCoverage.frontier().contains(key)) target = Math.max(mediumAxis(key), target);
        if (mediumCoveragePending && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && mediumCoverage.paths().contains(key) && (!mediumCoverage.frontier().contains(key)
                || mediumCoverageWork(key))) {
            return mediumAxis(key);
        }
        return Math.max(target, transitionTargets.getOrDefault(key, target));
    }

    /** One generation target shared by admission/reclaim and the worker. */
    private int terrainCellAxis(PredictionTileKey key, PredictionTile resident) {
        int target = targetCellAxis(key);
        if (resident == null) return voxyDetail(key) ? target
                : Math.min(sampler.initialTerrainCellAxis(key.lod()), target);
        int axis = Math.max(target, resident.cellAxis());
        if (!voxyDetail(key) && resident.cellAxis() < axis)
            axis = Math.min(axis, resident.cellAxis() * (dirtyTiles.contains(key) ? 1 : 2));
        return axis;
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
        if (!admissionBatch) cachePending.retainAll(pending);
        int inspected = 0;
        int maxInspected = 32;
        long until = System.nanoTime() + 250_000L;
        while (!closed && !paused && cachePending.size() < CACHE_RESTORE_IN_FLIGHT && !cacheCandidates.isEmpty()
                && inspected++ < maxInspected && System.nanoTime() < until) {
            boolean fair = nearCacheAdmissions >= NEAR_WORK_BURST;
            PredictionTileKey key = fair ? cacheCandidates.removeLast() : cacheCandidates.removeFirst();
            if (effectivelyDesired(key) && !pending.contains(key)) {
                enqueue(key, centerChunkX, centerChunkZ, false, true);
                if (cachePending.contains(key)) nearCacheAdmissions = fair ? 0 : nearCacheAdmissions+1;
            }
        }
    }

    private record CacheProbe(PredictionTileKey key, int band, double distance) { }
    private void probeNearbyCache(Collection<PredictionTileKey> keys) {
        var ordered = new ArrayList<CacheProbe>(keys.size());
        for (var key : keys) ordered.add(new CacheProbe(key,viewBand(key),
                PredictionWorkOrder.orderingDistance(key,layout,cameraBlockX,cameraBlockZ,buildFocus)));
        ordered.sort(Comparator.comparingInt(CacheProbe::band).thenComparingDouble(CacheProbe::distance)
                .thenComparingInt(probe -> -probe.key().lod()));
        diskCache.probeTerrain(ordered.stream().map(probe -> diskKey(probe.key())).toList());
    }

    private boolean cachedUpgrade(PredictionTileKey key) {
        PredictionTile tile = ready.get(key);
        Long retryAt = cacheMissUntil.get(key);
        return diskCache != null && (retryAt == null || System.nanoTime() - retryAt >= 0)
                && diskCache.cachedTerrainAxis(diskKey(key)) > (tile == null ? 0 : tile.cellAxis());
    }

    private boolean movementUpgrade(PredictionTileKey key, boolean surface) {
        return PredictionMotionPace.fastMoving() && !dirtyTiles.contains(key)
                && !PredictionWorkOrder.scoped(key, layout, buildFocus)
                && (surface || ready.containsKey(key));
    }

    private synchronized void refillScope(int centerChunkX, int centerChunkZ) {
        if (!admissionBatch) scopePending.retainAll(pending);
        int limit = PredictionWorkOrder.detailBuildLimit(executor.getCorePoolSize(), false, true);
        int inspected = 0; long until = System.nanoTime() + 250_000L;
        while (!closed && !paused && buildFocus != null && scopePending.size() < limit && !scopeCandidates.isEmpty()
                && inspected++ < 32 && System.nanoTime() < until) {
            BuildRequest request = scopeCandidates.removeFirst();
            if (desiredKeys.contains(request.key()) && PredictionWorkOrder.scoped(request.key(), layout, buildFocus))
                enqueue(request.key(), centerChunkX, centerChunkZ, request.surface());
        }
    }

    private synchronized void enqueue(PredictionTileKey key, int centerChunkX, int centerChunkZ,
                                      boolean surface, boolean cacheOnly) {
        if (closed || paused) return;
        if (reclaimAdmissionTarget != null && !key.equals(reclaimAdmissionTarget)) return;
        if (!generationReady) {
            if (surface) return;
            if (!cacheOnly) { enqueue(key, centerChunkX, centerChunkZ, false, true); return; }
        }
        final boolean finishedCacheOnly = !generationReady;
        if (!admissionBatch) cleanupPending();
        if (!dimension.equals(key.dimension()) || key.lod() < 0 || key.lod() >= layout.levelCount()) return;
        if (voxyOwnedTiles.contains(key)) return;
        if (captureHandoff.blocked(key)) { detailEvent(key, "capture-wait"); return; }
        // Surface recovery shares the existing bounded cache lane. Only cache
        // misses proceed to CPU/generation admission, once per content epoch.
        if(surface && !cacheOnly && unifiedVoxy && surfaceBuildReady(key)
                && !new SurfaceCacheAttempt(captureEpochs.getOrDefault(key,0L),meshRevision.get(),surfaceSettings).equals(surfaceCacheAttempts.get(key))) {
            enqueue(key,centerChunkX,centerChunkZ,true,true);return;
        }
        if (!cacheOnly && deferBackground(key, surface)) { detailEvent(key,"view-deferred");viewDeferredAdmissions.increment(); return; }
        if (cacheOnly && (cachePending.size() >= CACHE_RESTORE_IN_FLIGHT || (surface ? !surfaceBuildReady(key) : !cachedUpgrade(key)))) return;
        boolean idle = !cacheOnly && !surface && idleWork(key);
        if (idle && (!idleAdmissionReady() || !idlePending.isEmpty())) return;
        if (dirtyTiles.contains(key) && ready.containsKey(key) && captureRefresh.defer(key, System.nanoTime())) {
            detailEvent(key,"capture-debounce");captureRefreshDeferrals.increment();
            return;
        }
        if (!cacheOnly && !surface && diskCache != null) {
            if (cachedUpgrade(key)) { enqueue(key,centerChunkX,centerChunkZ,false,true); return; }
            // Give warm-cache metadata a short head start. A busy write/probe queue
            // must not hold missing coverage indefinitely; the worker still validates
            // and restores any actual cached payload before sampling new terrain.
            long probeWait = ready.containsKey(key) ? Long.MAX_VALUE : 250_000_000L;
            if (diskCache.terrainProbePending(diskKey(key), probeWait)) {detailEvent(key,"cache-probe");return;}
        }
        if (!cacheOnly && !localWorkAllowed(key, surface)) return;
        if (surface && !surfaceBuildReady(key)) return;
        if (!cacheOnly && !surface && !terrainBuildNeeded(key)
                || memoryBudget.exhausted(cacheOnly ? PredictionMemoryBudget.BuildKind.RESTORE
                : surface ? PredictionMemoryBudget.BuildKind.SURFACE : PredictionMemoryBudget.BuildKind.TERRAIN)) {
            return;
        }
        // Ingest is not render residency. Keep the selected detail instead
        // of replacing captured surfaces with enormous coarse fallback slabs.
        if (!retryReady(key)) return;
        if (pending.contains(key)) return;
        boolean scoped = !cacheOnly && PredictionWorkOrder.scoped(key, layout, buildFocus);
        if (scoped && scopePending.size() >= PredictionWorkOrder.detailBuildLimit(executor.getCorePoolSize(), false, true)) return;
        boolean visibleCompletion = !cacheOnly && surface && visibleSurface(key);
        boolean ordinary = !cacheOnly && !visibleCompletion && ordinaryRefinement(key);
        PredictionTile current = ready.get(key);
        long ordinaryPending = refinementPending.stream().filter(k -> !idlePending.contains(k)).count();
        if (ordinary && ordinaryPending >= refinementLimit()) return;
        if (ordinary && movementUpgrade(key, surface)
                && refinementPending.stream().filter(k -> !idlePending.contains(k) && movementUpgrade(k, false)).count()
                    >= PredictionMotionPace.upgradeBuildLimit(executor.getCorePoolSize())) return;
        boolean background = !cacheOnly && !visibleCompletion && backgroundWork(key) && !requiredCoverage(key, surface);
        if (background && backgroundPending.size() >= backgroundLimit()) return;
        failedAt.remove(key);
        deferredUntil.remove(key);
        // A reserved fair turn must survive both the executor heap and later
        // reprioritization. Repeated dirty parents retain the other three turns.
        int priority = cacheOnly ? -100 : fairGenerationAdmission ? FAIR_WORK_PRIORITY : workPriority(key, surface);
        double distance = PredictionWorkOrder.orderingDistance(key, layout, cameraBlockX, cameraBlockZ, buildFocus);
        VssLodLayout tileLayout = layout;
        // Queue capacity and executing capacity are separate. The worker acquires
        // its shared lease only when it starts; local generation may have changed.
        // Native-demand managers reserve current capacity so rejected queue tails
        // cannot consume the fair turn that the next completion should choose.
        boolean urgent = !idle && (dirtyTiles.contains(key)
                || visibleCompletion || !surface && voxyDetail(key)
                || !surface && (ready.get(key) == null || mediumCoverageWork(key))
                || PredictionWorkOrder.scoped(key, tileLayout, buildFocus));
        int queueLimit = Math.max(8, Math.min(32, executor.getCorePoolSize() * 4));
        ThreadPoolExecutor selectedExecutor = cacheOnly ? cacheExecutor : executor;
        PredictionCpuBudget.Reservation cpuReservation = cacheOnly ? null
                : unifiedVoxy ? cpuBudget.tryReserve(urgent) : cpuBudget.tryReserveQueued(urgent, queueLimit);
        if (!cacheOnly && cpuReservation == null) {
            detailEvent(key,"cpu-budget");admissionReasons[ADMIT_CPU_BUDGET].increment();
            // CPU refusal is shared across managers/tiles by the budget's lane
            // backoff. Do not add a per-key deadline: a completed lease must
            // allow the next target immediately, even in another manager.
            return;
        }
        // Terrain upgrades of resident grids and decoration use the expensive
        // lane. Fresh coverage and finished-cache restores retain their own lane.
        boolean needsDetail = !cacheOnly && (surface || !surface && voxyDetail(key) || current != null
                && Math.max(current.cellAxis(), Math.min(targetCellAxis(key), current.cellAxis() * 2))
                    > PredictionWorkOrder.PREVIEW_CELL_AXIS);
        BuildSlots buildSlots = needsDetail ? tryReserveBuildSlots(surface) : null;
        if (needsDetail && buildSlots == null) {
            detailEvent(key,"detail-slot");
            if (cpuReservation != null) cpuReservation.close();
            return;
        }
        if (!reserveQueuedBuild(pending, selectedExecutor.getQueue(), key, priority, distance, MAX_PENDING)) {
            if (cpuReservation != null) cpuReservation.close();
            if (buildSlots != null) buildSlots.close();
            return;
        }
        long revision = meshRevision.get();
        long navigation = navigationGeneration;
        detailEvent(key,cacheOnly?"cache-queued":"generation-queued");
        captureRefresh.started(key);
        if (background) backgroundPending.add(key);
        if (surface) surfacePending.add(key);
        if (ordinary) refinementPending.add(key);
        if (idle) idlePending.add(key);
        if (cacheOnly) cachePending.add(key);
        if (scoped) scopePending.add(key);
        boolean scopePromoted = PredictionWorkOrder.scoped(key, tileLayout, buildFocus)
                && PredictionWorkOrder.distanceSquared(key, tileLayout, cameraBlockX, cameraBlockZ)
                    >= Math.pow(PredictionDetailBands.fineRadius(tileLayout.maxDistanceBlocks(), dimension), 2);
        int buildSurfaceSettings = surfaceSettings;
        int buildTargetAxis = targetCellAxis(key);
        try {
            // The lambda body runs when a worker picks the task up, so the gap
            // to `buildStartedNanos` is the time this tile spent queued. That is
            // the number that tells scheduling starvation apart from slow work.
            long enqueueNanos = System.nanoTime();
            selectedExecutor.execute(new PredictionTask(key, priority, distance, surface, () -> {
            long buildStartedNanos = System.nanoTime();
            long buildCpuStart = currentCpuNanos();
            boolean visibleBuild = viewWork(key, surface);
            long waitNanos = buildStartedNanos - enqueueNanos;
            queueWaitNanos.add(waitNanos);
            maxQueueWaitNanos.accumulateAndGet(waitNanos, Math::max);
            PredictionMemoryBudget.Reservation reservation = null;
            PredictionCpuBudget.Lease cpuLease = null;
            PredictionDiskCache.Lease diskLease = null;
            var buildOutcome = PredictionMemoryBudget.BuildOutcome.CANCELLED;
            boolean continueSurface = false;
            boolean buildAdmitted = false;
            boolean captureDeferred = false;
            if (dirtyTiles.contains(key) && ready.containsKey(key)) {
                synchronized (this) {
                    captureDeferred = captureRefresh.defer(key, System.nanoTime());
                }
            }
            // Read content validity and its epoch together; terrain generation stays outside the lock.
            final long captureEpoch;
            final boolean retainSurface;
            synchronized (this) {
                captureEpoch = captureEpochs.getOrDefault(key, 0L);
                retainSurface = !invalidatedSurfaces.contains(key);
            }
            detailEvent(key,cacheOnly?"cache-running":"generation-running");
            activeBuildThreads.add(Thread.currentThread());
            try {
                // Convert the queued reservation into the worker's final
                // non-blocking lease check. close() is idempotent and also
                // covers cancellation paths which never reach this point.
                if (cpuReservation != null) cpuReservation.consume();
                if (captureDeferred) { captureRefreshDeferrals.increment(); return; }
                if (!captureCurrent(key, captureEpoch, revision)) {
                    admissionReasons[ADMIT_NOT_DESIRED].increment();
                    return;
                }
                if (!cacheOnly && deferBackground(key, surface)) { detailEvent(key,"view-deferred");viewDeferredAdmissions.increment(); return; }
                if (idle && (!idleAdmissionReady() || !idleTargets.containsKey(key))) return;
                // A queued coverage/scope task may have become ordinary work.
                // Release it for bounded admission instead of filling the CPU.
                if (!cacheOnly && !(surface && visibleSurface(key)) && ordinaryRefinement(key) && !refinementPending.contains(key)) {
                    admissionReasons[ADMIT_RELEASED].increment();
                    return;
                }
                if (!cacheOnly && !localWorkAllowed(key, surface)) {
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
                        || surface && visibleSurface(key) || !surface && voxyDetail(key)
                        || !surface && (ready.get(key) == null || mediumCoverageWork(key))
                        || PredictionWorkOrder.scoped(key, tileLayout, buildFocus));
                cpuLease = cacheOnly ? null : cpuBudget.tryAcquire(workerUrgent);
                if (!cacheOnly && cpuLease == null) {
                    detailEvent(key,"cpu-budget");admissionReasons[ADMIT_CPU_BUDGET].increment();
                    // The task already waited in the executor queue. Let the
                    // next planner tick decide when to retry; adding another
                    // wall-clock backoff here would restart a detail delay
                    // that may have elapsed while this worker was waiting.
                    return;
                }
                if (surface && !cacheOnly) {
                    int span = tileLayout.tileBlocks(key.lod());
                    vegetationForBuild(buildSurfaceSettings).deferIfRenderingBusy(key.tileX() * span,
                            key.tileZ() * span, span, 1,
                            () -> captureCurrent(key, captureEpoch, revision));
                }
                synchronized (this) {
                    // Tasks already queued must honor the same workspace handoff.
                    if (reclaimAdmissionTarget != null && !key.equals(reclaimAdmissionTarget)) return;
                    reservation = cacheOnly ? memoryBudget.tryReserveRestore()
                            : memoryBudget.tryReserveBuild(surface ? PredictionMemoryBudget.BuildKind.SURFACE
                            : PredictionMemoryBudget.BuildKind.TERRAIN);
                    if (reservation != null && key.equals(reclaimAdmissionTarget)) reclaimAdmissionTarget = null;
                }
                if (reservation == null) {
                    detailEvent(key,"memory-budget");admissionReasons[ADMIT_NO_RESERVATION].increment();
                    return;
                }
                byte[] voxelIdentity = diskCache == null ? null : voxelContentIdentity();
                PredictionTile voxelResident = ready.get(key);
                if (diskCache != null && !dirtyTiles.contains(key) && (voxelResident == null || cacheOnly)) {
                    long readStarted = System.nanoTime();
                    try (var lease = diskCache.lease(PredictionDiskCache.Key.voxels(key.tileX(),key.tileZ(),key.lod()))) {
                        var saved = diskCache.readVoxels(lease,key,buildSurfaceSettings,voxelIdentity);
                        requireCaptureCurrent(key,captureEpoch,revision);
                        if (saved != null && (!lease.valid() || saved.voxels().axis() > tileLayout.cellAxis(key.lod()))) saved = null;
                        if (saved != null && (voxelResident == null || saved.voxels().axis() > voxelResident.cellAxis()
                                || saved.voxels().axis()==voxelResident.cellAxis() && saved.voxels().surfaceComplete()
                                    && !voxelResident.data().voxyTile.surfaceComplete())) {
                            buildAdmitted = true;
                            var completed = saved.restore(meshIds.incrementAndGet(),
                                    scopePromoted && (voxelResident == null || voxelResident.scopeOnly()),voxelResident);
                            if (publishTile(completed,reservation,revision,captureEpoch,false,false,true,PredictionRelief.of(completed))) {
                                reservation.completeBuild(completed.retainedHeapBytes(),0,false); reservation = null;
                                if (completed.data().voxyTile != saved.voxels())
                                    persistVoxels(completed,revision,captureEpoch,buildSurfaceSettings,voxelIdentity);
                                voxelRestores.increment(); builtTiles.incrementAndGet();
                                continueSurface = surfaceBuildReady(key);
                            }
                            return;
                        }
                    } finally { diskReadNanos.add(System.nanoTime()-readStarted); }
                }
                if (finishedCacheOnly) return;
                if (surface) {
                    // Decoration owns world-coordinate voxels, not the terrain sampling grid.
                    // Attach it to the current immutable terrain instead of waiting for final
                    // terrain resolution and replaying the complete density/colour build.
                    buildAdmitted = true;
                    if (!cacheOnly && !PredictionWorkOrder.scoped(key, tileLayout, buildFocus)) mediumSinceSurface.set(0);
                    long surfaceStarted = System.nanoTime();
                    var base = previousTerrain.data().voxyTile;
                    var vegetation=vegetationForBuild(buildSurfaceSettings);
                    java.util.function.BooleanSupplier currentSurface=() ->
                            buildCurrent(key,captureEpoch,revision,previousTerrain.cellAxis(),true,cacheOnly);
                    var cachedPlants=cacheOnly ? vegetation.restoreForRendering(base.baseX(),base.baseZ(),
                            base.span(),1,tileLayout.trees(),editedCells::contains,currentSurface) : null;
                    var plants=cacheOnly ? cachedPlants.tile() : vegetation.tileForRendering(base.baseX(),base.baseZ(),
                            base.span(),1,tileLayout.trees(),editedCells::contains,currentSurface);
                    boolean complete=!cacheOnly || cachedPlants.complete();
                    requireBuildCurrent(key, captureEpoch, revision, previousTerrain.cellAxis(), true, cacheOnly);
                    if(cacheOnly)surfaceCacheAttempts.put(key,new SurfaceCacheAttempt(captureEpoch,revision,buildSurfaceSettings));
                    var cities = cityHints.cacheSnapshot(base.baseX(), base.baseZ(), base.span()).buildings();
                    var surfaceVoxels=base.withVegetation(plants,cities,complete);
                    if(!complete && retainSurface)surfaceVoxels=surfaceVoxels.retainFeatures(base);
                    var mesh = PredictionVoxelData.forVoxy(surfaceVoxels);
                    mesh.terrainRevision = previousTerrain.terrainRevision();
                    mesh.parentLod = previousTerrain.data().parentLod;
                    mesh.retainedSampleObjects = previousTerrain.data().retainedSampleObjects;
                    mesh.retainedVolumeBytes = previousTerrain.data().retainedVolumeBytes;
                    var completed = new PredictionTile(key, previousTerrain.heights(), previousTerrain.groundHeights(),
                            previousTerrain.samples(), mesh, new PredictionDepthBound(previousTerrain.depthBound().minY(),
                            Math.max(previousTerrain.depthBound().maxY(), plants.maxY())), System.nanoTime(), meshIds.incrementAndGet(),
                            previousTerrain.cellAxis(), previousTerrain.spacingBlocks(), previousTerrain.scopeOnly());
                    decorationNanos.add(System.nanoTime() - surfaceStarted);
                    if (publishTile(completed, reservation, revision, captureEpoch, complete, complete,
                            storedTiles.contains(key), PredictionRelief.of(completed))) {
                        reservation.completeBuild(completed.retainedHeapBytes(), 0, false);
                        reservation = null;
                        if(complete)persistVoxels(completed,revision,captureEpoch,buildSurfaceSettings,voxelIdentity);
                        builtTiles.incrementAndGet();
                    }
                    continueSurface=!complete;
                    return;
                }
                PredictionTile resident = ready.get(key);
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
                ClientColumnSample[] batch = cached == null ? null : cached.samples();
                boolean diskHit = batch != null;
                boolean coverage = !surface && !diskHit && resident == null;
                int cellAxis = diskHit ? cached.cellAxis() : surface ? tileLayout.cellAxis(key.lod())
                        : terrainCellAxis(key, resident);
                if (resident != null) cellAxis = Math.max(cellAxis, resident.cellAxis());
                if (surface && !PredictionWorkOrder.scoped(key, tileLayout, buildFocus)) mediumSinceSurface.set(0);
                buildAdmitted = true;
                PredictionParentLod.Input parentSource = !surface && !cacheOnly && !diskHit
                        ? parentInput(key, cellAxis, tileLayout) : null;
                PredictionParentLod.Result derivedParent = parentSource == null ? null : PredictionParentLod.reduce(parentSource);
                if (derivedParent != null) batch = derivedParent.samples();
                final int activeAxis = cellAxis;
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                boolean preview = cellAxis < tileLayout.cellAxis(key.lod());
                int gridSize = cellAxis + VssLodLayout.SAMPLE_MARGIN * 2;
                int stepBlocks = tileLayout.tileBlocks(key.lod()) / cellAxis;
                int baseBlockX = key.tileX() * (key.lod() < tileLayout.levelCount()
                        ? tileLayout.tileBlocks(key.lod()) : PredictionTileManager.TILE_CHUNKS << key.lod());
                int baseBlockZ = key.tileZ() * tileLayout.tileBlocks(key.lod());
                if (stepBlocks <= 16)
                    cityHints.observeArea(baseBlockX - 16, baseBlockZ - 16,
                        tileLayout.tileBlocks(key.lod()) + 32);
                LostCityHints.Snapshot citySnapshot = stepBlocks <= 16
                        ? cityHints.cacheSnapshot(baseBlockX, baseBlockZ, tileLayout.tileBlocks(key.lod()))
                        : new LostCityHints.Snapshot(null, true);
                dev.xantha.vss.common.worldgen.LostCityPreview.Tile cityBuildings = citySnapshot.buildings();
                int[] heights = new int[gridSize * gridSize];
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
                        ClientColumnSample captured = cachedGridSample(blockX, blockZ, stepBlocks);
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
                            () -> buildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly));
                }
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                nativeSampleNanos.add(System.nanoTime() - sampleStartedNanos);
                if (diskHit) diskHitBuilds.increment(); else diskMissBuilds.increment();
                long colorStartedNanos = System.nanoTime();
                for (int dz = 0; dz < gridSize; dz++) {
                    requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                    if (captureEpoch != captureEpochs.getOrDefault(key, 0L)) { captureEarlyExits.increment(); return; }
                    if (closed || paused || revision != meshRevision.get()
                            || Thread.currentThread().isInterrupted() || !effectivelyDesired(key)) return;
                    for (int dx = 0; dx < gridSize; dx++) {
                        int blockX = baseBlockX
                                + (dx - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int blockZ = baseBlockZ
                                + (dz - VssLodLayout.SAMPLE_MARGIN) * stepBlocks;
                        int sampleIndex = dz * gridSize + dx;
                        // Captures are protected by captureEpoch; predicted columns can improve concurrently.
                        ClientColumnSample sample = derivedParent != null ? batch[sampleIndex]
                                : sampler.interiorTerrain() ? null
                                : !diskHit && capturedOverrides != null && capturedOverrides[sampleIndex] != null
                                        ? capturedOverrides[sampleIndex] : cachedGridSample(blockX, blockZ, stepBlocks);
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
                        if (!sampler.interiorTerrain())
                            sample = capturedTerrain.sample(blockX, blockZ, stepBlocks, sample);
                        samples[sampleIndex] = sample;
                        heights[sampleIndex] = sample.surfaceY();
                    }
                }
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                colorNanos.add(System.nanoTime() - colorStartedNanos);
                long exteriorStartedNanos = System.nanoTime();
                int exteriorUpdates = derivedParent != null ? 0 : PredictionExteriorColumns.enrich(samples, gridSize, stepBlocks,
                        baseBlockX, baseBlockZ, sampler, () -> buildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly));
                // Complete floating columns can correct a preview's top as well as its underside.
                if (exteriorUpdates != 0)
                    for (int i = 0; i < samples.length; i++) heights[i] = samples[i].surfaceY();
                int biomeUpdates = sampler.resolveBiomeIndices(samples, gridSize, baseBlockX, baseBlockZ, stepBlocks);
                exteriorNanos.add(System.nanoTime() - exteriorStartedNanos);
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                // Every published sampling stage survives a restart. In-memory
                // refinement is monotonic; a restored grid also prevents a
                // smaller preview from overwriting the best stored result.
                if (captureEpoch != captureEpochs.getOrDefault(key, 0L)) { captureEarlyExits.increment(); return; }
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                long commitStartedNanos = System.nanoTime();
                java.util.concurrent.CompletableFuture<Boolean> persisted = diskLease == null || derivedParent != null
                        ? java.util.concurrent.CompletableFuture.completedFuture(false)
                        : diskHit && exteriorUpdates == 0 && biomeUpdates == 0
                                ? java.util.concurrent.CompletableFuture.completedFuture(true)
                                : diskCache.writeTerrainLater(diskLease, new PredictionDiskCache.TerrainData(
                                        samples, Long.MIN_VALUE, null, null, null));
                boolean cacheAccepted = !persisted.isDone() || persisted.getNow(false);
                commitNanos.add(System.nanoTime() - commitStartedNanos);
                long surfaceStarted = System.nanoTime();
                samplingNanos.add(surfaceStarted - buildStartedNanos);
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                PredictionVegetation.Tile plants = vegetation.cachedDisplayForRendering(baseBlockX, baseBlockZ,
                        tileLayout.tileBlocks(key.lod()), stepBlocks, editedCells::contains);
                if (captureEpoch != captureEpochs.getOrDefault(key, 0L)) { captureEarlyExits.increment(); return; }
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                long meshStarted = System.nanoTime();
                decorationNanos.add(meshStarted - surfaceStarted);
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                PredictionVoxySource.Tile voxels = PredictionVoxySource.build(key, samples,
                        gridSize, stepBlocks, sampler, plants, cityBuildings, surface);
                if (voxels != null && !surface && resident != null && retainSurface)
                    voxels = voxels.retainFeatures(resident.data().voxyTile);
                PredictionVoxelData mesh = PredictionVoxelData.forVoxy(voxels);
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                int meshGridSize = cellAxis + 1;
                ClientColumnSample[] meshSamples = cropMargin(samples, gridSize, meshGridSize);
                int[] meshHeights = cropMargin(heights, gridSize, meshGridSize);
                if (cityBuildings != null) {
                    meshSamples = PredictionCityGeometry.ground(meshSamples, cityBuildings, baseBlockX, baseBlockZ,
                            stepBlocks, meshGridSize, null).samples();
                    for (int i = 0; i < meshHeights.length; i++) meshHeights[i] = meshSamples[i].surfaceY();
                }
                int[] meshGroundHeights = meshHeights;
                // Retain sampling provenance and immutable voxel columns for Voxy workers.
                mesh.parentLod = derivedParent == null ? null : derivedParent.proof();
                mesh.retainedSampleObjects = PredictionSampleCompaction.compact(meshSamples);
                mesh.retainedVolumeBytes = PredictionSampleCompaction.volumeBytes(meshSamples);
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                PredictionDepthBound surfaceBounds = PredictionDepthBound.fromSamples(samples);
                if (resident != null && !surface && retainSurface
                        && mesh.voxyTile.hasVegetation())
                    surfaceBounds = new PredictionDepthBound(Math.min(surfaceBounds.minY(), resident.depthBound().minY()),
                            Math.max(surfaceBounds.maxY(), resident.depthBound().maxY()));
                PredictionTile completed = new PredictionTile(key, meshHeights, meshGroundHeights,
                        meshSamples, mesh, LostCityHints.includeBuildings(new PredictionDepthBound(surfaceBounds.minY(),
                        Math.max(surfaceBounds.maxY(), plants.maxY())), cityBuildings),
                        System.nanoTime(), meshIds.incrementAndGet(), mesh.cellAxis(), stepBlocks,
                        // Refining an ordinary resident must not revoke its ordinary coverage.
                        scopePromoted && (resident == null || resident.scopeOnly()));
                long packStarted = System.nanoTime();
                meshingNanos.add(packStarted - meshStarted);
                requireBuildCurrent(key, captureEpoch, revision, activeAxis, surface, cacheOnly);
                if (publishTile(completed, reservation, revision, captureEpoch, surface, surface,
                        persisted.getNow(false) && diskLease.valid(), PredictionRelief.of(completed))) {
                    reservation.completeBuild(completed.retainedHeapBytes(), 0, false);
                    reservation = null;
                    long completedRevision = completed.revision();
                    persistVoxels(completed,revision,captureEpoch,buildSurfaceSettings,voxelIdentity);
                    persisted.thenAccept(written -> {
                        if (written) terrainStored(key, completedRevision, revision, captureEpoch);
                    });
                    if (!surface && cellAxis >= PredictionWorkOrder.PREVIEW_CELL_AXIS
                            && (terrainLeaves.contains(key) || mediumCoverage.frontier().contains(key))
                            && (resident == null || resident.cellAxis() < cellAxis)) {
                        mediumSinceSurface.updateAndGet(count -> Math.min(MEDIUM_BUILDS_PER_SURFACE_TURN, count + 1));
                    }
                    continueSurface = !surface && surfaceBuildReady(key);
                    long built = builtTiles.incrementAndGet();
                    if (built == 1L && firstTileLogged.compareAndSet(false, true) && VSSClientConfig.CONFIG.debugLogging) {
                        dev.xantha.vss.common.VSSLogger.info(
                                "VSS prediction first tile ready: key=" + key
                                        + ", samples=" + (gridSize * gridSize)
                                        + ", preview=" + preview
                                        + ", voxelBytes=" + mesh.voxyTile.bytes()
                                        + ", buildMs=" + String.format(java.util.Locale.ROOT,
                                        "%.1f", (System.nanoTime() - buildStartedNanos)
                                                / 1_000_000.0D));
                    }
                }
            } catch (PredictionDemandChanged changed) {
                // A turn/zoom is immediately retryable. Preserve the old tile,
                // release all leases in finally, and never create failure backoff.
                detailEvent(key,"superseded");supersededBuilds.increment();
            } catch (PredictionWorkDeferred deferred) {
                detailEvent(key,"contention");contentionDeferrals.incrementAndGet();
                // The owning chunk build will populate the shared cache. Give
                // this worker back to other terrain instead of parking it.
                if (!closed && !paused && revision == meshRevision.get() && effectivelyDesired(key))
                    deferredUntil.put(key, System.nanoTime() + CONTENTION_RETRY_NANOS);
            } catch (java.util.concurrent.CancellationException cancelled) {
                // Lifecycle cancellation is immediately retryable after resume.
                // A backend declining a still-current tile needs backoff so it
                // cannot monopolize the first execution slot every replan.
                if (!closed && !paused && revision == meshRevision.get()
                        && navigation == navigationGeneration
                        && !Thread.currentThread().isInterrupted() && effectivelyDesired(key)
                        && captureEpoch == captureEpochs.getOrDefault(key, 0L)) {
                    failedAt.put(key, System.nanoTime());
                }
            } catch (OutOfMemoryError error) {
                buildOutcome = PredictionMemoryBudget.BuildOutcome.FAILED;
                failedTiles.incrementAndGet();
                if (memoryBudget.pauseAfterOutOfMemory()) {
                    dev.xantha.vss.common.VSSLogger.warn(
                            "VSS prediction builds paused after JVM heap exhaustion; retaining coarse coverage");
                }
            } catch (Throwable throwable) {
                buildOutcome = PredictionMemoryBudget.BuildOutcome.FAILED;
                failedAt.put(key, System.nanoTime());
                if (closed || paused || Thread.currentThread().isInterrupted()) return;
                failedTiles.incrementAndGet();
                dev.xantha.vss.common.VSSLogger.error(
                        "VSS prediction tile build failed: " + key, throwable);
            } finally {
                if (buildAdmitted) {
                    (visibleBuild ? visibleCpuNanos : backgroundCpuNanos)
                            .add(Math.max(0, currentCpuNanos() - buildCpuStart));
                    (visibleBuild ? visibleQueueTimings : backgroundQueueTimings).record(waitNanos);
                }
                // The reservation is consumed before the final lease check;
                // close is therefore a no-op for normal execution and still
                // releases a queued slot when a task is cancelled early.
                if (cpuReservation != null) cpuReservation.close();
                if (captureEpoch != captureEpochs.getOrDefault(key, 0L))
                    captureDiscardNanos.add(System.nanoTime() - buildStartedNanos);
                if (diskLease != null) diskLease.close();
                activeBuildThreads.remove(Thread.currentThread());
                if (buildSlots != null) buildSlots.close();
                if (reservation != null) { reservation.finishBuild(buildOutcome); reservation.close(); }
                if (cpuLease != null) cpuLease.close();
                backgroundPending.remove(key);
                surfacePending.remove(key);
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
                if (buildAdmitted && !closed && !paused) synchronized (PredictionTileManager.this) {
                    boolean currentSurface=continueSurface && revision == meshRevision.get()
                            && captureEpoch == captureEpochs.getOrDefault(key, 0L);
                    if(unifiedVoxy) {
                        if(currentSurface) queueSurfaceCompletion(key);
                        serviceGenerationWork(centerChunkX, centerChunkZ, System.nanoTime()+1_000_000L);
                    } else if(currentSurface) enqueue(key,centerChunkX,centerChunkZ,true);
                }
            }
            }, cpuReservation, buildSlots));
            if (!cacheOnly) generationEnqueues++;
        } catch (RejectedExecutionException rejected) {
            if (cpuReservation != null) cpuReservation.close();
            if (buildSlots != null) buildSlots.close();
            idlePending.remove(key);
            backgroundPending.remove(key);
            surfacePending.remove(key);
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

    private ClientColumnSample[] sampleGridFast(int baseBlockX, int baseBlockZ,
                                                int stepBlocks, int gridSize, ClientColumnSample[] samples, boolean preview, boolean display,
                                                java.util.function.BooleanSupplier captureCurrent) {
        boolean floating = sampler.floatingTerrain();
        if (sampler.interiorTerrain() || floating) {
            long revision = meshRevision.get();
            for (int z = 0; z < gridSize; z++) for (int x = 0; x < gridSize; x++) {
                if (!captureCurrent.getAsBoolean()) return samples;
                if (closed || paused || revision != meshRevision.get() || Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                int i = z * gridSize + x;
                // End captures keep their real-world authority; generated islands need complete
                // columns from the first preview, without an earlier surface-only sampling pass.
                if (floating && samples[i] != null && samples[i].captured()) continue;
                // A captured height alone cannot replace the entire underground column.
                if (samples[i] == null || samples[i].volume() == null
                        || floating && PredictionExteriorColumns.profiled(samples[i]))
                    samples[i] = sampler.sampleInterior(baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks,
                            baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks);
            }
            return samples;
        }
        if (sampler instanceof PredictionLodSampler) {
            long revision = meshRevision.get();
            for (int z = 0; z < gridSize; z += 8) for (int x = 0; x < gridSize; x += 8) {
                if (!captureCurrent.getAsBoolean()) return samples;
                if (closed || paused || Thread.currentThread().isInterrupted() || meshRevision.get() != revision)
                    throw new java.util.concurrent.CancellationException();
                sampler.sampleLodGrid(baseBlockX + (x - VssLodLayout.SAMPLE_MARGIN) * stepBlocks,
                        baseBlockZ + (z - VssLodLayout.SAMPLE_MARGIN) * stepBlocks, stepBlocks,
                        Math.min(8, gridSize - x), Math.min(8, gridSize - z), samples,
                        z * gridSize + x, gridSize, preview);
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
        if (captured != null && stepBlocks == 1) return captured;
        if (stepBlocks > 1) {
            ClientColumnSample base = captured != null ? captured : sampler.sampleForLod(blockX, blockZ, stepBlocks);
            return capturedTerrain.sample(blockX, blockZ, stepBlocks, base);
        }
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

    private ClientColumnSample cachedGridSample(int blockX, int blockZ, int step) {
        if (step < 16) return cachedSample(blockX, blockZ);
        // Coarse grids read area mips later, without probing thousands of unrelated fine chunk records.
        return sampleStore == null ? null : sampleStore.get(pack(blockX, blockZ));
    }

    private ClientColumnSample cachedSample(int blockX, int blockZ) {
        ClientColumnSample real = capturedTerrain.exact(blockX, blockZ);
        if (real != null) return real;
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

    private synchronized void pruneReady(int centerChunkX, int centerChunkZ) {
        if (retirementScan == null) retirementScan = ready.values().iterator();
        long now = System.nanoTime(), until = now + 1_000_000L;
        int checked = 0, retired = 0;
        while (retirementScan.hasNext() && checked++ < 64 && retired < 64 && System.nanoTime() < until) {
            var tile = retirementScan.next(); var key = tile.key();
            if (ready.get(key) != tile) continue;
            // Native rendering no longer needs a hand-off for a region entirely
            // outside the current horizon. Do not generate old-route ancestors
            // just to make that invisible region eligible for retirement.
            boolean pinned = unifiedVoxy && !desiredKeys.contains(key)
                    && outOfRetirementRange(key, layout, centerChunkX * 16, centerChunkZ * 16)
                    || shouldRetirePinned(key, ready.keySet(), desiredKeys, layout, centerChunkX * 16, centerChunkZ * 16);
            if (!pinned && !desiredKeys.contains(key) && outOfRetirementRange(key, layout, centerChunkX * 16, centerChunkZ * 16)
                    && key.lod() < layout.levelCount() - 1) {
                var fallback = firstMissingAncestor(key, ready.keySet(), layout);
                if (fallback != null) { queueFallbackAncestors(fallback, centerChunkX, centerChunkZ); retainedForFallback.incrementAndGet(); }
            }
            long lastUse = Math.max(tile.generatedAtNanos(), desiredGrace.getOrDefault(key, 0L));
            boolean outside = beyondHorizon(tile.baseBlockX(), tile.baseBlockZ(), tile.spanBlocks(),
                    centerChunkX * 16, centerChunkZ * 16, layout.maxDistanceBlocks() + RETIREMENT_MARGIN_BLOCKS);
            boolean stored = diskCache != null && canRetireStored(key, now - lastUse, effectivelyDesired(key),
                    storedTiles.contains(key), layout, outside);
            // Inactive pressure retirement needs a currently resident fallback;
            // optional displayed downgrades use reclaimPreparedWork instead.
            boolean pressure = !voxySource.retiring() && memoryBudget.reclaimTargetBytes() > 0 && !effectivelyDesired(key)
                    && !PredictionWorkOrder.scoped(key, layout, buildFocus) && coveredByAncestor(ready.keySet(), layout, key)
                    && surfaceFallbackReady(key);
            if (pinned || stored || pressure) {
                removeTile(key); fallbackKeys.remove(key); retired++; if (pinned) retiredOutOfRange.incrementAndGet();
            }
        }
        retirementChecks += checked;
        if (!retirementScan.hasNext()) retirementScan = null;
    }

    /** Scan and retirement both share a bounded client-tick time slice. */
    private synchronized void maintainCpuCache(int centerChunkX, int centerChunkZ, long now) {
        long timingStart = PredictionRenderTimings.start();
        try { maintainCpuCacheStep(centerChunkX, centerChunkZ, now); }
        finally { PredictionRenderTimings.end(PredictionRenderTimings.Stage.CPU_CACHE, timingStart); }
    }

    private void maintainCpuCacheStep(int centerChunkX, int centerChunkZ, long now) {
        if (memoryBudget.hasFixedAllowance()) return;
        if (cacheScan == null) {
            if (now < nextCacheMaintenance) return;
            cacheScan = ready.values().iterator(); cacheScanBytes = 0; cacheScanCandidates.clear();
        }
        long until = System.nanoTime() + 1_000_000L;
        int inspected = 0;
        while (cacheScan.hasNext() && inspected++ < 64 && System.nanoTime() < until) {
            PredictionTile tile = cacheScan.next();
            if (ready.get(tile.key()) != tile) continue;
            long retained = tile.retainedHeapBytes();
            var reservation = residentMemory.get(tile.key());
            if (reservation != null) reservation.accountResident(retained);
            if (!cacheEligible(tile, now)) continue;
            cacheScanBytes += retained;
            cacheScanCandidates.add(tile);
            if (cacheScanCandidates.size() > 64) cacheScanCandidates.poll();
        }
        cacheScanSlices++;
        if (cacheScan.hasNext()) return;
        cacheScan = null; nextCacheMaintenance = now + 2_000_000_000L;
        coldCacheBytes = cacheScanBytes;
        long reclaim = cpuCachePolicy.reclaim(coldCacheBytes);
        var cold = new ArrayList<>(cacheScanCandidates); cacheScanCandidates.clear();
        cold.sort(Comparator.comparingLong((PredictionTile tile) -> desiredGrace.getOrDefault(tile.key(), tile.generatedAtNanos()))
                .thenComparing(Comparator.comparingDouble((PredictionTile tile) -> distanceSquared(tile, centerChunkX, centerChunkZ)).reversed()));
        int removed = 0;
        for (PredictionTile tile : cold) {
            if (reclaim <= 0 || removed >= 8 || System.nanoTime() >= until) break;
            if (ready.get(tile.key()) != tile || !cacheEligible(tile, now)) continue;
            long retained = tile.retainedHeapBytes(); removeTile(tile.key()); removed++;
            reclaim -= retained; coldCacheBytes -= retained; coldCacheEvictions++; coldCacheReleasedBytes += retained;
        }
        if (reclaim > 0) nextCacheMaintenance = now;
    }

    private boolean cacheEligible(PredictionTile tile, long now) {
        var key = tile.key();
        long lastUse = Math.max(tile.generatedAtNanos(), desiredGrace.getOrDefault(key, 0L));
        boolean near = PredictionWorkOrder.distanceSquared(key, layout, cameraBlockX, cameraBlockZ) <= 256.0 * 256;
        return PredictionCpuCachePolicy.safe(effectivelyDesired(key), near,
                PredictionWorkOrder.scoped(key, layout, buildFocus), pending.contains(key), storedTiles.contains(key),
                coveredByAncestor(ready.keySet(), layout, key),
                tile.data().voxyTile.surfaceComplete() || tile.data().voxyTile.hasVegetation(), surfaceFallbackReady(key), now - lastUse);
    }

    /** Some cached plants do not prove that an ancestor can replace all of a
     * child's plants (or its completed empty placement) during in-range eviction. */
    private boolean surfaceFallbackReady(PredictionTileKey key) {
        var tile=ready.get(key);
        if(tile==null || !tile.data().voxyTile.surfaceComplete() && !tile.data().voxyTile.hasVegetation())return true;
        for (int lod = key.lod() + 1; lod < layout.levelCount(); lod++) {
            int shift = lod - key.lod();
            var parent = ready.get(new PredictionTileKey(dimension, key.tileX() >> shift, key.tileZ() >> shift, lod));
            if (parent != null && !parent.scopeOnly() && !dirtyTiles.contains(parent.key())
                    && parent.data().voxyTile.surfaceComplete()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retires tiles that left the prediction horizon and whose region stays
     * covered by a resident ancestor.  Tiles inside the horizon are pinned
     * regardless of the desired set; the ancestor test guarantees the far
     * field never exposes a hole when a tile is finally dropped.
     */
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
        voxyDemand.clear();
        voxyDetailTargets.clear(); voxyDetailWork.clear();
        detailTraces.clear();
        voxySource.close();
        planning.invalidate();
        inputSnapshots.clear(); planningCandidates.clear();
        deferredCleanup = cacheMissCleanup = null; cacheScan = retirementScan = null; cacheScanCandidates.clear();
        coverageSpatial.clear(); coverageSpatialSize = 0;
        if (closed) return;
        closed = true;
        visibilityFeedback = lastScheduledVisibility = PredictionVisibilityFeedback.Snapshot.EMPTY;
        displayGoals = Map.of(); recentlyVisible.clear();
        viewBands = Map.of(); viewDependencies = Set.of();
        scheduledOccluded = Set.of();
        scheduledPlan = List.of(); scheduledLeaves = List.of();
        meshRevision.incrementAndGet();
        ready.clear();
        coverageSpatial.clear(); coverageSpatialSize = 0; cacheScan = retirementScan = null; cacheScanCandidates.clear();
        coverageEpochs.clear();
        renderSnapshot = null;
        coverageHot.clear();
        publishedTiles = null; publishedEpochs = null;
        snapshotChanges.clear(); scopeFamilies.clear();
        // Release CPU reservations for tasks that never reached a worker.
        for (Runnable task : executor.getQueue()) {
            if (task instanceof PredictionTask prediction) prediction.releaseAdmission();
        }
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof PredictionTask prediction) prediction.releaseAdmission();
        }
        cacheExecutor.shutdownNow();
        cacheMissUntil.clear();
        surfaceCacheAttempts.clear();
        ready.clear();
        coverageSpatial.clear(); coverageSpatialSize = 0; cacheScan = retirementScan = null; cacheScanCandidates.clear();
        residentMemory.values().forEach(PredictionMemoryBudget.Reservation::close);
        residentMemory.clear();
        pending.clear();
        cachePending.clear();
        cacheCandidates.clear();
        scopePending.clear();
        scopeCandidates.clear(); preparedWork.clear(); preparedReclaim.clear(); clearValidatedReclaim(); reclaimTarget = reclaimAdmissionTarget = null; scheduledHints = Map.of();
        queuedRefresh = new Runnable[0]; queuedRefreshIndex = 0; scheduleAgain = false;
        backgroundPending.clear();
        surfacePending.clear();
        refinementPending.clear();
        idlePending.clear();
        idleResidents.clear();
        idleTargets = Map.of();
        ordinaryTargets = Map.of();
        idleAllowed = false;
        pendingCaptures.clear();
        pendingCaptureBytes = 0;
        activeCapture = null; captureEdits.clear();
        localCaptures.clear();
        capturedTerrain.clear();
        latestCaptures.clear();
        captureVersions.clear();
        captureRetryAfter.clear();
        captureHandoff.clear();
        desiredKeys.clear();
        desiredGrace.clear();
        voxyOwnedTiles = Set.of();
        fallbackKeys.clear();
        terrainLeaves.clear();
        failedAt.clear();
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
        invalidatedSurfaces.clear();
        sampleCache.clear();
        storedTiles.clear();
        if (diskCache != null) diskCache.finishWrites();
        // Never wait for worker termination or disk I/O on the client thread.
        PredictionResources.retire(executor, sampleStore, sampler, cacheExecutor);
    }

    /** Invalidates in-flight publications without destroying reusable meshes. */
    void setPaused(boolean value) {
        if (closed || paused == value) return;
        synchronized (this) {
            if (closed || paused == value) return;
            paused = value;
            meshRevision.incrementAndGet();
            planning.invalidate();
            selectionTicks = 0;
            // Leave running keys reserved until their finally blocks execute.
            // Clearing those keys here lets an obsolete job remove a new job's key.
            if (value) {
                preparedWork.clear(); preparedReclaim.clear(); clearValidatedReclaim(); reclaimAdmissionTarget = null;
                queuedRefresh = new Runnable[0]; queuedRefreshIndex = 0; scheduledHints = Map.of();
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
    }

    public record PredictionTileKey(ResourceKey<Level> dimension, int tileX, int tileZ, int lod) {
        @Override public int hashCode() {
            // Adjacent x/z pairs collide in the record's linear 31*x+z hash.
            // These keys dominate render residency and planning lookups.
            return PredictionKeyHash.hash(tileX,tileZ,lod) ^ dimension.hashCode();
        }
    }

    public record PredictionTile(PredictionTileKey key, int[] heights, int[] groundHeights,
                                 ClientColumnSample[] samples,
                                 PredictionVoxelData data,
                                 PredictionDepthBound depthBound,
                                 long generatedAtNanos, long revision,
                                 int cellAxis, int spacingBlocks, boolean scopeOnly) {
        long terrainRevision() { return data.terrainRevision < 0 ? revision : data.terrainRevision; }
        public PredictionTile(PredictionTileKey key, int[] heights, int[] groundHeights,
                              ClientColumnSample[] samples, PredictionVoxelData data, PredictionDepthBound depthBound,
                              long generatedAtNanos, long revision, int cellAxis, int spacingBlocks) {
            this(key, heights, groundHeights, samples, data, depthBound, generatedAtNanos, revision, cellAxis, spacingBlocks, false);
        }
        long retainedHeapBytes() {
            // Published meshes account for their single visual payload and seam
            // summary. Unprepared legacy fixtures still reserve the future payload.
            int sampleObjects = data.retainedSampleObjects < 0 ? samples.length : data.retainedSampleObjects;
            return 1024L + 4L * (heights.length + (groundHeights == heights ? 0 : groundHeights.length))
                    + 4L * samples.length + 104L * sampleObjects + data.retainedVolumeBytes
                    + data.retainedHeapBytes()
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
        private final BuildSlots buildSlots;

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, Runnable delegate) {
            this(key, priority, distanceSquared, false, delegate, null);
        }

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, boolean surface, Runnable delegate) {
            this(key, priority, distanceSquared, surface, delegate, null);
        }

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, boolean surface,
                       Runnable delegate, PredictionCpuBudget.Reservation cpuReservation) {
            this(key, priority, distanceSquared, surface, delegate, cpuReservation, null);
        }

        PredictionTask(PredictionTileKey key, int priority, double distanceSquared, boolean surface,
                       Runnable delegate, PredictionCpuBudget.Reservation cpuReservation, BuildSlots buildSlots) {
            this.key = key;
            this.priority = priority;
            this.distanceSquared = distanceSquared;
            this.surface = surface;
            this.delegate = delegate;
            this.cpuReservation = cpuReservation;
            this.buildSlots = buildSlots;
        }

        private PredictionTask(int priority, long distanceSquared, Runnable delegate) {
            this(null, priority, distanceSquared, false, delegate, null);
        }

        void releaseAdmission() {
            if (cpuReservation != null) cpuReservation.close();
            if (buildSlots != null) buildSlots.close();
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
