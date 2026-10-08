package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.ChebyshevRingOffsets;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.DiagnosticCounters;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.client.prediction.ClientPredictionState;
import dev.xantha.vss.client.prediction.PredictionCpuBudget;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collection;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class LodRequestManager {
    private final java.util.concurrent.ConcurrentHashMap<Long, int[]> strictSections =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static long strictSectionsKey(long packed) {
        // This full 64-bit mix is a reversible permutation, so coordinates
        // remain distinct. It also avoids Long.hashCode = X xor Z turning a
        // nearby grid into a few large ConcurrentHashMap collision trees.
        return it.unimi.dsi.fastutil.HashCommon.mix(packed);
    }
    /**
     * Columns the server explicitly reported as unavailable.  A strict ring
     * must be able to pass such a hole; a later generation retry can replace
     * the marker with real section data.
     */
    private final java.util.Set<Long> strictUnavailableColumns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    public boolean strictColumnReady(int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        return strictSections.containsKey(strictSectionsKey(packed)) || strictUnavailableColumns.contains(packed);
    }

    public boolean strictColumnRenderReady(int cx, int cz, java.util.function.IntPredicate ready) {
        long packed = PositionUtil.packPosition(cx, cz);
        int[] sections = strictSections.get(strictSectionsKey(packed));
        if (sections == null) return strictUnavailableColumns.contains(packed);
        for (int y : sections) if (!ready.test(y)) return false;
        return true;
    }

    synchronized void recordStrictSections(int cx, int cz, dev.xantha.vss.api.VoxelColumnData data) {
        long packed = PositionUtil.packPosition(cx, cz);
        strictSections.put(strictSectionsKey(packed), java.util.Arrays.stream(data.sections())
                .filter(section -> !section.section().hasOnlyAir()).mapToInt(section -> section.sectionY()).toArray());
        strictUnavailableColumns.remove(packed);
        dev.xantha.vss.compat.StrictLodVisibility.workChanged();
        requestScanPending = true;
    }
    // Unchanged discovery runs at 10 Hz; responses and dirty notifications wake it immediately.
    private static final int SCAN_INTERVAL_TICKS = 2;
    private static final long SYNC_REQUEST_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long GENERATION_REQUEST_TIMEOUT_NANOS = 300_000_000_000L;
    private static final long MAX_REQUEST_TIMEOUT_NANOS = 600_000_000_000L;
    private static final long REQUEST_TIMEOUT_GRACE_MULTIPLIER = 3L;
    private static final long DIRTY_REFRESH_BACKOFF_NANOS = 500_000_000L;
    private static final long BACKPRESSURE_BACKOFF_NANOS = 250_000_000L;
    private static final long RATE_LIMIT_BACKOFF_NANOS = 1_000_000_000L;
    private static final long GENERATION_BACKOFF_NANOS = 5_000_000_000L;
    private static final int DIRTY_REFRESH_BACKOFF_MAX_SHIFT = 3;
    private static final int RATE_LIMIT_BACKOFF_MAX_SHIFT = 3;
    private static final int GENERATION_BACKOFF_MAX_SHIFT = 1;
    private static final int DIRTY_REFRESH_RATE_LIMIT = 512;
    private static final int DIRTY_REFRESH_CONCURRENCY_LIMIT = 64;
    // Unknown columns after a teleport are cache probes first. Keep their
    // bounded in-flight budget separate from actual worldgen requests.
    private static final int CACHE_PROBE_CONCURRENCY_LIMIT = 32;
    private static final int MAX_SCAN_CANDIDATES_PER_TICK = 4096;
    private static final int BOOSTED_SCAN_CANDIDATES_PER_TICK = 32768;
    private static final int MAX_REQUESTS_PER_TICK = 256;
    private static final int INTEGRATED_MAX_REQUESTS_PER_TICK = 96;
    private static final long MAX_SCAN_NANOS_PER_TICK = 1_500_000L;
    private static final long INTEGRATED_MAX_SCAN_NANOS_PER_TICK = 750_000L;
    private static final int SCAN_DEADLINE_CHECK_INTERVAL = 64;
    private static final int SCAN_BOOST_TICKS = 100;
    private static final int MAX_DEFERRED_CANDIDATES_PER_TICK = 2048;
    private static final int MAX_DEFERRED_COLUMNS = 65536;
    private static final int FAST_MOVE_CHUNK_THRESHOLD = 8;
    private static final int FAST_MOVE_KEEP_RADIUS_CHUNKS = 48;
    // Walk the whole active LOD window so stale cached entries cannot permanently
    // suppress requests outside the near ring. The Voxy query itself is asynchronous.
    private static final int PRESENCE_AUDIT_INTERVAL_TICKS = 4;
    private static final int PRESENCE_AUDIT_CANDIDATES_PER_TICK = 256;
    private static final long PRESENCE_AUDIT_MAX_NANOS = 250_000L;
    private static final long FULL_SCAN_RETRY_INTERVAL_NANOS = 15_000_000_000L;
    private static final int ESTIMATED_SYNC_COLUMN_BYTES = 48 * 1024;
    private static final long REQUEST_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;

    private final Long2LongOpenHashMap columnTimestamps = new Long2LongOpenHashMap();
    private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    private final Long2LongOpenHashMap dirtyColumnTimestamps = new Long2LongOpenHashMap();
    private final ClientRequestTracker requestTracker;
    private final DeferredColumnQueue deferredColumns = new DeferredColumnQueue(MAX_DEFERRED_COLUMNS);
    private final RetryBackoff retryBackoff = new RetryBackoff(
            System::nanoTime,
            new RetryBackoffPolicy(
                    DIRTY_REFRESH_BACKOFF_NANOS,
                    RATE_LIMIT_BACKOFF_NANOS,
                    GENERATION_BACKOFF_NANOS,
                    BACKPRESSURE_BACKOFF_NANOS,
                    DIRTY_REFRESH_BACKOFF_MAX_SHIFT,
                    RATE_LIMIT_BACKOFF_MAX_SHIFT,
                    GENERATION_BACKOFF_MAX_SHIFT));
    private final LongOpenHashSet diskMissedColumns = new LongOpenHashSet();
    /** Responses from requests cancelled during a prediction toggle are stale. */
    private final LongOpenHashSet suppressedResponses = new LongOpenHashSet();
    private final ClientPresenceReporter presenceReporter;
    private final Runnable transferReset;
    private final CacheOnlyReloadTracker cacheOnlyReload = new CacheOnlyReloadTracker();

    private SessionConfigS2CPayload sessionConfig;
    private ResourceKey<Level> lastDimension;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    private boolean requestScanPending = true;
    private int orderedOffsetDistance = -1;
    private int orderedOffsetCount;
    private int scanOffsetIndex;
    private int nearScanOffsetIndex;
    private int presenceAuditOffsetIndex;
    private int scanBoostTicks;
    private int presenceAuditTickCounter;
    private int softFrontierRadius;
    private int lastEffectiveLodDistance = -1;
    private boolean scanCompletedForCurrentOffsets;
    private boolean nearScanCompletedForCurrentOffsets;
    private boolean nearScanCompletedOnce;
    private long nextFullScanRetryNanos;
    private double dirtyRefreshBudget;
    private long lastRequestDiagnosticNanos;
    private final DiagnosticCounters generationDiagnostics = new DiagnosticCounters(
            VSSLogger::isDebugEnabled, REQUEST_DIAGNOSTIC_INTERVAL_NANOS);
    private int diagnosticBatchLimit;
    private int diagnosticGenerationSlots;
    private final PredictionGenerationPriority predictionPriority = new PredictionGenerationPriority();
    private int diagnosticGenerationLimit;
    private boolean diagnosticXaeroBackpressure;

    private static class RequestBuffers {
        final int[] requestIds = new int[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final long[] positions = new long[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final long[] timestamps = new long[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final boolean[] allowGeneration = new boolean[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final boolean[] cacheProbe = new boolean[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
    }

    private final RequestBuffers requestBuffers = new RequestBuffers();

    public LodRequestManager() {
        this(ClientLodPresenceCache.currentScope(), new ClientRequestTracker(), () -> {
        });
    }

    public LodRequestManager(String presenceScope) {
        this(presenceScope, new ClientRequestTracker(), () -> {
        });
    }

    LodRequestManager(String presenceScope, ClientRequestTracker requestTracker) {
        this(presenceScope, requestTracker, () -> {
        });
    }

    LodRequestManager(String presenceScope, ClientRequestTracker requestTracker, Runnable transferReset) {
        this.requestTracker = requestTracker;
        this.presenceReporter = new ClientPresenceReporter(presenceScope, new ClientPresenceReporter.ReconciliationListener() {
            @Override
            public void onPresent(long packed, long timestamp) {
                restoreKnownColumn(packed, timestamp);
            }

            @Override
            public void onMissing(long packed) {
                reconcileMissingColumn(packed);
            }
        });
        this.transferReset = transferReset != null ? transferReset : () -> {
        };
        columnTimestamps.defaultReturnValue(-1L);
        dirtyColumnTimestamps.defaultReturnValue(0L);
        dirtyRefreshBudget = 16.0;
    }

    public synchronized boolean onSessionConfig(SessionConfigS2CPayload config) {
        SessionConfigS2CPayload previousConfig = sessionConfig;
        boolean shouldReset = shouldResetForSessionConfig(config);
        sessionConfig = config;
        if (shouldReset) {
            resetRequestStateAfterConfigChange();
            primeDirtyRefreshBudget();
            return true;
        }

        primeDirtyRefreshBudget();
        if (previousConfig != null && previousConfig.generationEnabled() != config.generationEnabled()) {
            if (generationAllowed()) {
                resumeGenerationCandidatesNearPlayer();
            } else {
                suspendGenerationCandidates();
            }
            scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        }
        return shouldReset;
    }

    /**
     * Reconcile the request scheduler with the client-side prediction option.
     *
     * The option can change without a new server session.  In that case the
     * generation quota changes immediately, but the old scan cursor and
     * deferred queue would otherwise keep the previous prediction turn alive.
     * Recentring here makes disabling prediction resume generation from the
     * player's current near rings instead of draining stale distant work.
     */
    public synchronized void onPredictionOptionChanged(boolean enabled) {
        if (sessionConfig == null || !sessionConfig.enabled()) {
            return;
        }

        predictionPriority.reset();
        requestScanPending = true;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        armScanBoost();

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft != null ? minecraft.player : null;
        ClientLevel level = minecraft != null ? minecraft.level : null;
        if (player == null || level == null || player.isRemoved()) {
            return;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int lodDistance = getEffectiveLodDistance();
        deferredColumns.recenter(playerCx, playerCz);
        restartPredictionOrder(enabled, level.dimension(), playerCx, playerCz);

        if (!enabled) {
            resetSchedulerForPredictionDisable();
        } else {
            resetScanCursorPastProtectedSyncWindow(lodDistance);
        }
        lastPlayerChunkX = playerCx;
        lastPlayerChunkZ = playerCz;
    }

    /**
     * Restart discovery from the player after a toggle. Keep valid cache state
     * and dirty refreshes, but rediscover ordinary work in distance order.
     */
    private void resetSchedulerForPredictionDisable() {
        LongOpenHashSet staleRequests = new LongOpenHashSet();
        requestTracker.forEachInFlight(packed -> {
            if (!dirtyColumns.contains(packed)) staleRequests.add(packed);
        });
        for (long packed : staleRequests) {
            suppressedResponses.add(packed);
            requestTracker.cancel(packed);
            ClientPredictionState.releaseRequested(lastDimension,
                    PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed));
            deferredColumns.remove(packed);
            // Preserve an established generation miss. Unknown cache probes
            // will be discovered again by the reset scan and probed normally.
            clearBackoff(packed);
        }

        LongOpenHashSet staleDeferred = new LongOpenHashSet();
        for (long packed : deferredColumns) {
            if (!dirtyColumns.contains(packed)) staleDeferred.add(packed);
        }
        for (long packed : staleDeferred) deferredColumns.remove(packed);
        if (!staleDeferred.isEmpty()) deferredColumns.compact();

        // Revisit the center and its rings from the new cursor. Do not prune
        // valid Voxy presence or disk-miss markers; those are cache state.
        resetScanCursor();
        softFrontierRadius = 0;
        requestScanPending = true;
        scanBoostTicks = SCAN_BOOST_TICKS;
    }

    void restartPredictionOrder(boolean enabled, ResourceKey<Level> dimension, int playerCx, int playerCz) {
        if (enabled) return;
        // A settled cache/generation miss is not loaded terrain. Recheck these
        // holes before opening distant rings when generation resumes.
        for (long packed : strictUnavailableColumns) clearBackoff(packed);
        strictUnavailableColumns.clear();
        dev.xantha.vss.compat.StrictLodVisibility.restartOrdering(dimension, playerCx, playerCz);
    }

    public synchronized void tick() {
        if (sessionConfig == null || !sessionConfig.enabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || player.isRemoved()) {
            return;
        }

        boolean dimensionChanged = lastDimension != null && !lastDimension.equals(level.dimension());
        if (dimensionChanged) {
            resetRequestState();
        }
        if (lastDimension == null || dimensionChanged) {
            cacheOnlyReload.enter(level.dimension().location().toString());
        }
        lastDimension = level.dimension();
        int playerBlockX = player.getBlockX();
        int playerBlockZ = player.getBlockZ();
        int playerCx = playerBlockX >> 4;
        int playerCz = playerBlockZ >> 4;
        int lodDistance = getEffectiveLodDistance();
        deferredColumns.recenter(playerCx, playerCz);
        handleEffectiveLodDistance(playerCx, playerCz, lodDistance);
        if (playerCx != lastPlayerChunkX || playerCz != lastPlayerChunkZ) {
            boolean firstKnownPosition = lastPlayerChunkX == Integer.MIN_VALUE;
            int moveDistance = firstKnownPosition
                    ? 0
                    : Math.max(Math.abs(playerCx - lastPlayerChunkX), Math.abs(playerCz - lastPlayerChunkZ));

            boolean isTeleport = !firstKnownPosition && moveDistance >= lodDistance / 2;

            if (isTeleport) {
                resetRequestState();
                primeDirtyRefreshBudget();
                armScanBoost();
            } else {
                pruneAround(playerCx, playerCz, lodDistance + VSSConstants.LOD_DISTANCE_BUFFER);
                if (moveDistance >= FAST_MOVE_CHUNK_THRESHOLD) {
                    pruneLowPriorityRequestsAround(playerCx, playerCz,
                            Math.min(lodDistance, FAST_MOVE_KEEP_RADIUS_CHUNKS));
                }
            }
            if (firstKnownPosition || isTeleport) {
                predictionPriority.reset();
                resetScanCursorPastProtectedSyncWindow(lodDistance);
            } else if (moveDistance > 0) {
                rebaseScanCursorAfterMove(lodDistance);
                pruneStaleGenerationWorkAround(playerCx, playerCz, lodDistance);
                armScanBoost();
            }
            lastPlayerChunkX = playerCx;
            lastPlayerChunkZ = playerCz;
        }
        presenceReporter.updateWindow(level, level.dimension(), playerCx, playerCz, lodDistance);
        boolean allowPresenceZstd = (sessionConfig.serverCapabilities() & VSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0;
        presenceReporter.drain(level, level.dimension(), allowPresenceZstd);
        if (++presenceAuditTickCounter >= PRESENCE_AUDIT_INTERVAL_TICKS) {
            presenceAuditTickCounter = 0;
            auditKnownPresence(level, playerCx, playerCz, lodDistance);
        }
        timeoutSweep();
        PredictionCpuBudget.SHARED.observeLocalGeneration(isIntegratedServer(), requestTracker.generationSize());
        dirtyRefreshBudget = Math.min(DIRTY_REFRESH_RATE_LIMIT, dirtyRefreshBudget + DIRTY_REFRESH_RATE_LIMIT / 20.0D);

        if (!shouldScanRequests()) {
            return;
        }
        scanAndSend(level, player);
        finishCacheOnlyReloadPassIfReady();
        logGenerationDiagnostics(playerCx, playerCz, lodDistance);
    }

    boolean shouldScanRequests() {
        if (++scanTickCounter < SCAN_INTERVAL_TICKS && !requestScanPending) {
            return false;
        }
        scanTickCounter = 0;
        requestScanPending = false;
        return true;
    }

    public synchronized ColumnReceiveResult onColumnTransferPart(
            int requestId,
            ResourceKey<Level> dimension,
            int cx,
            int cz,
            long columnTimestamp) {
        if (sessionConfig == null || !sessionConfig.enabled() || !isActiveDimension(dimension)) {
            return new ColumnReceiveResult(false, false, false, Long.MIN_VALUE);
        }

        long packed = PositionUtil.packPosition(cx, cz);
        if (requestId >= 0 && !requestTracker.matches(requestId, packed)
                && suppressedResponses.remove(packed)) {
            generationDiagnostics.record("suppressedStaleResponse");
            return new ColumnReceiveResult(false, false, false, packed);
        }
        if (!isWithinCurrentLodWindow(packed)) {
            return new ColumnReceiveResult(false, false, false, Long.MIN_VALUE);
        }

        if (requestId >= 0 && requestTracker.matches(requestId, packed)) {
            if (!isColumnVersionAllowed(packed, columnTimestamp, false)) {
                return new ColumnReceiveResult(false, false, false, packed);
            }
            requestTracker.refreshDeadline(requestId, timeoutFor(packed, requestTracker.size()), System.nanoTime());
            boolean dirtyRefreshRequest = requestTracker.isDirtyRefreshRequest(requestId);
            boolean replacingKnownColumn = columnTimestamps.get(packed) > 0L || dirtyRefreshRequest;
            return new ColumnReceiveResult(true, dirtyRefreshRequest, replacingKnownColumn, packed);
        }

        if (requestTracker.contains(packed) || !isColumnVersionAllowed(packed, columnTimestamp, true)) {
            return new ColumnReceiveResult(false, false, false, packed);
        }
        boolean dirtyRefreshRequest = dirtyColumns.contains(packed);
        boolean replacingKnownColumn = columnTimestamps.get(packed) > 0L || dirtyRefreshRequest;
        return new ColumnReceiveResult(true, dirtyRefreshRequest, replacingKnownColumn, packed);
    }

    /** Local explicit pregen may persist outside the display radius. It still
     * obeys the active dimension, dirty watermark and monotonic column version. */
    synchronized boolean processLocalPregenColumn(ResourceKey<Level> dimension, int cx, int cz,
            long version, int[] sections, BooleanSupplier processor) {
        if (sessionConfig == null || !sessionConfig.enabled() || !isActiveDimension(dimension)) return false;
        long packed = PositionUtil.packPosition(cx, cz);
        if (version <= 0 || version < dirtyColumnTimestamps.get(packed)) return false;
        if (columnTimestamps.get(packed) >= version) return true;
        if (!processor.getAsBoolean()) return false;
        requestTracker.cancel(packed);
        suppressedResponses.remove(packed);
        acceptColumn(dimension, packed, version, sections);
        return true;
    }

    public synchronized ColumnProcessingResult processColumnIfCurrent(
            int requestId,
            ResourceKey<Level> dimension,
            int cx,
            int cz,
            long columnTimestamp,
            int[] replacementSectionYs,
            BooleanSupplier processor) {
        if (sessionConfig == null || !sessionConfig.enabled() || !isActiveDimension(dimension)) {
            return ColumnProcessingResult.STALE;
        }
        long packed = PositionUtil.packPosition(cx, cz);
        boolean activeRequest = requestId >= 0 && requestTracker.matches(requestId, packed);
        if (!activeRequest && suppressedResponses.remove(packed)) {
            generationDiagnostics.record("suppressedStaleResponse");
            return ColumnProcessingResult.STALE;
        }
        if (!activeRequest && (requestTracker.contains(packed)
                || !isWithinCurrentLodWindow(packed)
                || !isColumnVersionAllowed(packed, columnTimestamp, true))) {
            return ColumnProcessingResult.STALE;
        }
        if (activeRequest && !isColumnVersionAllowed(packed, columnTimestamp, false)) {
            failTrackedRequest(requestId, packed);
            return ColumnProcessingResult.STALE;
        }

        boolean processed;
        try {
            processed = processor.getAsBoolean();
        } catch (RuntimeException e) {
            failTrackedRequest(requestId, packed);
            throw e;
        }
        if (!processed) {
            failTrackedRequest(requestId, packed);
            return ColumnProcessingResult.FAILED;
        }

        if (activeRequest) {
            if (requestTracker.remove(requestId) != packed) {
                return ColumnProcessingResult.STALE;
            }
        } else if (requestTracker.contains(packed)
                || !isColumnVersionAllowed(packed, columnTimestamp, true)) {
            return ColumnProcessingResult.STALE;
        }
        acceptColumn(dimension, packed, columnTimestamp, replacementSectionYs);
        return ColumnProcessingResult.APPLIED;
    }

    private void acceptColumn(
            ResourceKey<Level> dimension,
            long packed,
            long columnTimestamp,
            int[] replacementSectionYs) {
        long requiredTimestamp = dirtyColumnTimestamps.get(packed);
        columnTimestamps.put(packed, columnTimestamp);
        strictUnavailableColumns.remove(packed);
        requestScanPending = true;
        rememberKnownColumn(dimension, packed, columnTimestamp, replacementSectionYs);
        diskMissedColumns.remove(packed);
        deferredColumns.remove(packed);
        if (requiredTimestamp > 0L && columnTimestamp < requiredTimestamp) {
            dirtyColumns.add(packed);
            deferColumn(packed, true);
        } else {
            dirtyColumns.remove(packed);
            dirtyColumnTimestamps.remove(packed);
            clearBackoff(packed);
        }
    }

    public synchronized void onColumnTransferFailed(
            int requestId,
            long transferId,
            ResourceKey<Level> dimension,
            int cx,
            int cz) {
        if (!isActiveDimension(dimension)) {
            return;
        }
        long packed = PositionUtil.packPosition(cx, cz);
        if (requestId < 0 || !requestTracker.matches(requestId, packed)) {
            return;
        }
        failTrackedRequest(requestId, packed);
    }

    public synchronized void onClientChunkDropped(ResourceKey<Level> dimension, int cx, int cz) {
        // Vanilla chunk lifetime is independent from Voxy's persisted LOD lifetime.
    }

    public synchronized long[] onDirtyColumns(long[] dirtyPositions, long[] dirtyTimestamps) {
        var changed = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int i = 0; i < dirtyPositions.length; i++) {
            long packed = dirtyPositions[i];
            long dirtyTimestamp = i < dirtyTimestamps.length ? dirtyTimestamps[i] : VSSConstants.epochMillis();
            if (dirtyTimestamp <= 0L) {
                dirtyTimestamp = VSSConstants.epochMillis();
            }
            long existingTimestamp = dirtyColumnTimestamps.get(packed);
            if (dirtyTimestamp <= Math.max(existingTimestamp, columnTimestamps.get(packed))) continue;
            if (strictUnavailableColumns.remove(packed)) {
                if (dev.xantha.vss.compat.StrictLodVisibility.completed(lastDimension,
                        PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed))) {
                    dev.xantha.vss.compat.StrictLodVisibility.invalidate();
                } else {
                    dev.xantha.vss.compat.StrictLodVisibility.workChanged();
                }
            }
            dirtyColumnTimestamps.put(packed, dirtyTimestamp);
            changed.add(packed);
            clearBackoff(packed);
            dirtyColumns.add(packed);
            if (requestTracker.contains(packed)) {
                requestTracker.cancel(packed);
            }
            deferColumn(packed, true);
        }
        if (!changed.isEmpty()) requestScanPending = true;
        return changed.toLongArray();
    }

    public synchronized void onColumnNotGenerated(int requestId) {
        boolean dirtyRefreshRequest = requestTracker.isDirtyRefreshRequest(requestId);
        boolean generationRequest = requestTracker.isGenerationRequest(requestId);
        boolean cacheProbeRequest = requestTracker.isCacheProbeRequest(requestId);
        long packed = requestTracker.remove(requestId);
        if (packed != Long.MIN_VALUE) {
            requestScanPending = true;
            if (dirtyRefreshRequest && hasKnownColumn(packed)) {
                if (hasDirtyTimestamp(packed)) {
                    markBackoff(packed, false);
                    deferredColumns.remove(packed);
                    deferColumn(packed, true);
                } else {
                    dirtyColumns.remove(packed);
                    clearBackoff(packed);
                }
                return;
            }

            if ((generationRequest || sessionConfig != null && !sessionConfig.generationEnabled())
                    && !strictSections.containsKey(strictSectionsKey(packed))) {
                // A cache miss alone still needs near-first generation. Only
                // an unavailable generation result (or generation disabled)
                // settles the hole; retries can later supply its real data.
                strictUnavailableColumns.add(packed);
                dev.xantha.vss.compat.StrictLodVisibility.workChanged();
            }
            dirtyColumns.remove(packed);
            if (generationRequest) {
                columnTimestamps.remove(packed);
                diskMissedColumns.add(packed);
                markBackoff(packed, true);
                deferredColumns.remove(packed);
                deferColumn(packed);
            } else if (cacheProbeRequest) {
                handleCacheProbeMiss(packed);
            } else if (generationAllowed()) {
                columnTimestamps.remove(packed);
                clearBackoff(packed);
                deferredColumns.remove(packed);
                if (shouldPromoteNotGeneratedToGeneration(packed)) {
                    diskMissedColumns.add(packed);
                    deferColumn(packed);
                } else {
                    clearMissState(packed);
                }
            } else {
                columnTimestamps.put(packed, 0L);
                if (cacheOnlyReload.isActive()) {
                    diskMissedColumns.remove(packed);
                } else {
                    diskMissedColumns.add(packed);
                }
                clearBackoff(packed);
                deferredColumns.remove(packed);
            }
        }
    }

    public synchronized void onColumnUpToDate(int requestId) {
        long packed = requestTracker.remove(requestId);
        if (packed != Long.MIN_VALUE) {
            requestScanPending = true;
            long requiredTimestamp = dirtyColumnTimestamps.get(packed);
            long localTimestamp = columnTimestamps.get(packed);
            if (localTimestamp <= 0L) {
                dirtyColumns.remove(packed);
                dirtyColumnTimestamps.remove(packed);
                deferredColumns.remove(packed);
                if (generationAllowed()) {
                    columnTimestamps.remove(packed);
                    diskMissedColumns.add(packed);
                    clearBackoff(packed);
                    deferColumn(packed);
                } else {
                    columnTimestamps.put(packed, 0L);
                    if (cacheOnlyReload.isActive()) {
                        diskMissedColumns.remove(packed);
                    } else {
                        diskMissedColumns.add(packed);
                    }
                    clearBackoff(packed);
                }
                return;
            }

            boolean dirtySatisfied = requiredTimestamp <= 0L || localTimestamp >= requiredTimestamp;
            strictUnavailableColumns.remove(packed);
            if (dirtySatisfied) {
                dirtyColumns.remove(packed);
                dirtyColumnTimestamps.remove(packed);
            }
            clearBackoff(packed);
            if (!dirtySatisfied) {
                deferredColumns.remove(packed);
                if (hasKnownColumn(packed)) {
                    dirtyColumns.add(packed);
                    deferColumn(packed, true);
                } else {
                    dirtyColumns.remove(packed);
                    deferColumn(packed);
                }
            }
        }
    }

    public synchronized void onRateLimited(int requestId) {
        generationDiagnostics.record("rateLimitedResponse");
        long packed = requestTracker.remove(requestId);
        if (packed != Long.MIN_VALUE) {
            requestScanPending = true;
            markRateLimited(packed);
            deferredColumns.remove(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    /**
     * A cache probe deliberately asks the server not to generate. Only after
     * the server confirms that no cached column exists do we hand the column
     * back to the normal generation candidate queue.
     */
    private void handleCacheProbeMiss(long packed) {
        generationDiagnostics.record("probeMiss");
        columnTimestamps.remove(packed);
        clearBackoff(packed);
        deferredColumns.remove(packed);
        if (generationAllowed() && shouldPromoteNotGeneratedToGeneration(packed)) {
            diskMissedColumns.add(packed);
            deferColumn(packed);
            generationDiagnostics.record(deferredColumns.contains(packed)
                    ? "probePromoted" : "probePromotionQueueFull");
            return;
        }
        generationDiagnostics.record(cacheOnlyReload.isActive() ? "probeBlockedCacheOnly"
                : !generationAllowed() ? "probeBlockedGenerationDisabled" : "probeBlockedFrontierOrPlayer");
        clearMissState(packed);
    }

    public synchronized void onBackpressured(int requestId) {
        generationDiagnostics.record("backpressureResponse");
        long packed = requestTracker.remove(requestId);
        if (packed != Long.MIN_VALUE) {
            requestScanPending = true;
            markBackpressure(packed);
            deferredColumns.remove(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    public synchronized void disconnect() {
        cacheOnlyReload.clear();
        resetRequestState();
        ClientLodPresenceCache.flush();
    }

    public synchronized void forceResync() {
        cacheOnlyReload.clear();
        resetRequestStateAfterConfigChange();
    }

    public synchronized void forceResyncWithoutGeneration(
            Collection<String> dimensions,
            ResourceKey<Level> currentDimension) {
        cacheOnlyReload.begin(
                dimensions,
                currentDimension != null ? currentDimension.location().toString() : null);
        resetRequestStateAfterConfigChange();
    }

    private void finishCacheOnlyReloadPassIfReady() {
        if (!cacheOnlyReplayReadyToComplete(
                cacheOnlyReload.isActive(),
                scanCompletedForCurrentOffsets,
                requestTracker.size(),
                deferredColumns.queuedEntries(),
                VSSClientNetworking.hasPendingColumnWork(),
                ModCompat.hasPendingXaeroMapWork())) {
            return;
        }
        if (!cacheOnlyReload.completeActive()) {
            return;
        }
        VSSLogger.info("Xaero cache-only LOD replay completed for "
                + (lastDimension != null ? lastDimension.location() : "unknown")
                + "; all cached columns committed without generation, pending dimensions="
                + cacheOnlyReload.pendingCount());
    }

    static boolean cacheOnlyReplayReadyToComplete(
            boolean active,
            boolean scanComplete,
            int requestsInFlight,
            int deferredColumns,
            boolean columnPipelinePending,
            boolean xaeroWorkPending) {
        return active
                && scanComplete
                && requestsInFlight == 0
                && deferredColumns == 0
                && !columnPipelinePending
                && !xaeroWorkPending;
    }

    public synchronized int getPendingCount() {
        return requestTracker.size();
    }

    private boolean shouldResetForSessionConfig(SessionConfigS2CPayload config) {
        if (sessionConfig == null) {
            return true;
        }
        return sessionConfig.enabled() != config.enabled()
                || sessionConfig.serverCapabilities() != config.serverCapabilities();
    }

    private void resetRequestStateAfterConfigChange() {
        resetRequestState();
    }

    public synchronized void onGenerationQueued(int requestId) {
        generationDiagnostics.record("generationQueuedResponse");
        long packed = requestTracker.positionFor(requestId);
        if (packed == Long.MIN_VALUE) {
            return;
        }
        transitionToGenerationWaiting(
                requestTracker,
                requestId,
                generationTimeoutFor(requestTracker.size()),
                System.nanoTime());
    }

    public synchronized void onColumnQueued(int requestId) {
        long packed = requestTracker.positionFor(requestId);
        if (packed == Long.MIN_VALUE) return;
        generationDiagnostics.record("columnQueuedResponse");
        requestTracker.refreshDeadline(requestId,
                Math.max(VSSConstants.COLUMN_QUEUE_IDLE_TIMEOUT_NANOS, timeoutFor(packed, requestTracker.size())),
                System.nanoTime());
    }

    static boolean transitionToGenerationWaiting(
            ClientRequestTracker tracker,
            int requestId,
            long timeoutNanos,
            long nowNanos) {
        return tracker.markGenerationQueued(requestId, timeoutNanos, nowNanos);
    }

    private void primeDirtyRefreshBudget() {
        dirtyRefreshBudget = Math.max(dirtyRefreshBudget, DIRTY_REFRESH_RATE_LIMIT);
        dirtyRefreshBudget = Math.min(dirtyRefreshBudget, DIRTY_REFRESH_RATE_LIMIT);
    }

    private void handleEffectiveLodDistance(int playerCx, int playerCz, int lodDistance) {
        if (lastEffectiveLodDistance < 0) {
            lastEffectiveLodDistance = lodDistance;
            ensureOrderedOffsets(lodDistance);
            return;
        }
        if (lastEffectiveLodDistance == lodDistance) {
            return;
        }

        int previousDistance = lastEffectiveLodDistance;
        int previousResumeRing = scanCompletedForCurrentOffsets
                ? previousDistance + 1
                : Math.max(0, softFrontierRadius);
        if (lodDistance < previousDistance) {
            pruneAround(playerCx, playerCz, lodDistance + VSSConstants.LOD_DISTANCE_BUFFER);
            ensureOrderedOffsets(lodDistance);
            setScanCursorAtRing(Math.min(lodDistance + 1, previousResumeRing));
        } else {
            ensureOrderedOffsets(lodDistance);
            setScanCursorAtRing(Math.min(lodDistance + 1, previousResumeRing));
        }
        lastEffectiveLodDistance = lodDistance;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void suspendGenerationCandidates() {
        LongOpenHashSet cancelled = new LongOpenHashSet();
        requestTracker.forEachGenerationInFlight(cancelled::add);
        for (long packed : cancelled) {
            requestTracker.cancel(packed);
            diskMissedColumns.add(packed);
            columnTimestamps.put(packed, 0L);
            clearBackoff(packed);
        }

        LongOpenHashSet deferredGeneration = new LongOpenHashSet();
        for (long packed : deferredColumns) {
            if (!dirtyColumns.contains(packed) && diskMissedColumns.contains(packed)) {
                deferredGeneration.add(packed);
            }
        }
        for (long packed : deferredGeneration) {
            deferredColumns.remove(packed);
            columnTimestamps.put(packed, 0L);
            clearBackoff(packed);
        }
        if (!deferredGeneration.isEmpty()) {
            deferredColumns.compact();
        }
    }

    private void resumeGenerationCandidatesNearPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || player.isRemoved()) {
            return;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int lodDistance = getEffectiveLodDistance();
        deferredColumns.recenter(playerCx, playerCz);
        LongOpenHashSet candidates = new LongOpenHashSet(diskMissedColumns);
        int resumed = 0;
        for (long packed : candidates) {
            if (resumed >= MAX_DEFERRED_COLUMNS) {
                break;
            }
            if (requestTracker.contains(packed) || dirtyColumns.contains(packed)) {
                continue;
            }
            if (chebyshevDistance(packed, playerCx, playerCz) > lodDistance) {
                continue;
            }
            columnTimestamps.remove(packed);
            clearBackoff(packed);
            deferColumn(packed);
            resumed++;
        }
        resetScanCursorPastProtectedSyncWindow(lodDistance);
    }

    private void scanAndSend(ClientLevel level, LocalPlayer player) {
        try {
            scanAndSendWithBudget(level, player);
        } finally {
            // Release unused scan reservations even on early returns or send failure.
            PredictionCpuBudget.SHARED.observeLocalGeneration(isIntegratedServer(), requestTracker.generationSize());
        }
    }

    private void scanAndSendWithBudget(ClientLevel level, LocalPlayer player) {
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int lodDistance = getEffectiveLodDistance();
        deferredColumns.recenter(playerCx, playerCz);
        RequestWindow requestWindow = createRequestWindow();
        generationDiagnostics.record("scanTicks");
        diagnosticGenerationSlots = requestWindow.generationRemaining();
        diagnosticBatchLimit = 0;
        if (!requestWindow.hasCapacity()) {
            generationDiagnostics.record("scanNoCapacity");
            return;
        }

        int maxCount = Math.min(
                Math.min(VSSConstants.MAX_BATCH_CHUNK_REQUESTS, requestWindow.remaining()),
                maxRequestsPerTick());
        // Xaero is an optional, lossy side-channel. Its queue pressure must
        // never throttle VSS/Voxy column delivery or explicit generation.
        diagnosticXaeroBackpressure = false;
        diagnosticBatchLimit = maxCount;
        int[] requestIds = requestBuffers.requestIds;
        long[] positions = requestBuffers.positions;
        long[] timestamps = requestBuffers.timestamps;
        boolean[] allowGeneration = requestBuffers.allowGeneration;
        boolean[] cacheProbe = requestBuffers.cacheProbe;
        int count = 0;

        if (lodDistance <= 0) {
            generationDiagnostics.record("scanDistanceZero");
            return;
        }

        ensureOrderedOffsets(lodDistance);
        int protectedSyncDistance = getVanillaProtectedSyncDistance();
        long now = System.nanoTime();
        ScanBudget scanBudget = ScanBudget.create(scanBoostTicks > 0);

        count = collectRequests(playerCx, playerCz, lodDistance, protectedSyncDistance,
                requestIds, positions, timestamps, allowGeneration, cacheProbe,
                maxCount, requestWindow, now, scanBudget);
        if (scanBoostTicks > 0) {
            scanBoostTicks--;
        }

        if (count > 0) {
            dirtyRefreshBudget = Math.max(0.0D, dirtyRefreshBudget - requestWindow.dirtySent());
            VSSClientNetworking.sendBatchRequest(new BatchChunkRequestC2SPayload(
                    requestIds, positions, timestamps, allowGeneration, cacheProbe, count));
            generationDiagnostics.add("sentGeneration", requestWindow.generationSent());
            generationDiagnostics.add("sentSync", requestWindow.syncSent());
            generationDiagnostics.add("sentDirty", requestWindow.dirtySent());
            generationDiagnostics.add("sentProbe", count - requestWindow.generationSent()
                    - requestWindow.syncSent() - requestWindow.dirtySent());
            logRequestBatch(now, count, requestWindow.syncSent(), requestWindow.generationSent(), requestWindow.dirtySent(), lodDistance, playerCx, playerCz);
        }
    }

    // Service confirmed work before discovering more misses. Bound this phase so
    // cooling/deferred candidates cannot consume the whole tick or probe budget.
    private int collectRequests(int playerCx, int playerCz, int lodDistance, int protectedSyncDistance,
                                int[] requestIds, long[] positions, long[] timestamps,
                                boolean[] allowGeneration, boolean[] cacheProbe, int maxCount,
                                RequestWindow requestWindow, long now, ScanBudget scanBudget) {
        int deferredLimit = Math.max(1, maxCount / 2);
        int count = drainDeferredColumns(playerCx, playerCz, lodDistance, protectedSyncDistance,
                requestIds, positions, timestamps, allowGeneration, cacheProbe, 0,
                deferredLimit, requestWindow, now, scanBudget.slice(3));
        count = scanNearSyncColumns(playerCx, playerCz, lodDistance, protectedSyncDistance,
                requestIds, positions, timestamps, allowGeneration, cacheProbe, count,
                maxCount, requestWindow, now, nearScanCompletedOnce ? scanBudget.slice(2) : scanBudget);
        if (!nearScanCompletedOnce && nearScanCompletedForCurrentOffsets) {
            // Do not immediately scan the same 4,225 near columns a second time.
            scanOffsetIndex = Math.max(scanOffsetIndex, Math.min(orderedOffsetCount,
                    ChebyshevRingOffsets.firstIndexForRing(Math.min(lodDistance,
                            VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS) + 1)));
        }
        nearScanCompletedOnce |= nearScanCompletedForCurrentOffsets;
        if (shouldRunOuterScan(nearScanCompletedOnce)) {
            count = scanNewSyncColumns(playerCx, playerCz, lodDistance, protectedSyncDistance,
                    requestIds, positions, timestamps, allowGeneration, cacheProbe, count,
                    maxCount, requestWindow, now, lodDistance, scanBudget);
        }
        return count;
    }

    private RequestWindow createRequestWindow() {
        int dirtyInFlightCount = requestTracker.dirtyRefreshSize();
        int generationInFlightCount = requestTracker.generationSize();

        int generationConcurrencyLimit = sessionConfig.generationEnabled()
                ? Math.max(1, sessionConfig.generationConcurrencyLimitPerPlayer())
                : 0;

        boolean predictionActive = VSSClientNetworking.isPredictionActive();
        int availableBudget = isIntegratedServer()
                ? PredictionCpuBudget.SHARED.localGenerationLimit(generationConcurrencyLimit, predictionActive)
                : generationConcurrencyLimit;
        generationConcurrencyLimit = predictionPriority.limit(generationConcurrencyLimit,
                predictionActive, ClientPredictionState.loadingProgress(lastDimension), availableBudget, System.nanoTime());
        if (isIntegratedServer()) {
            generationConcurrencyLimit = PredictionCpuBudget.SHARED.reserveLocalGeneration(
                    generationConcurrencyLimit, predictionActive, generationInFlightCount);
        }
        diagnosticGenerationLimit = generationConcurrencyLimit;

        int generationSlots = Math.max(0, generationConcurrencyLimit - generationInFlightCount);
        int dirtySlots = Math.max(0, DIRTY_REFRESH_CONCURRENCY_LIMIT - dirtyInFlightCount);

        return new RequestWindow(
                syncBucketLimit(sessionConfig.nearSyncRateLimitPerTick(), true),
                syncBucketLimit(sessionConfig.midSyncRateLimitPerTick(), false),
                syncBucketLimit(sessionConfig.farSyncRateLimitPerTick(), false),
                syncBucketLimit(sessionConfig.distantSyncRateLimitPerTick(), false),
                generationSlots,
                Math.max(0, CACHE_PROBE_CONCURRENCY_LIMIT - requestTracker.cacheProbeSize()),
                Math.min(dirtySlots, (int) dirtyRefreshBudget));
    }

    static int syncBucketLimit(int configuredLimit, boolean zeroMeansUnlimited) {
        if (configuredLimit <= 0) {
            return zeroMeansUnlimited ? VSSConstants.MAX_BATCH_CHUNK_REQUESTS : 0;
        }
        return Math.min(configuredLimit, VSSServerConfig.MAX_SYNC_RATE_LIMIT_PER_TICK);
    }

    static boolean shouldRunOuterScan(boolean nearScanComplete) {
        return nearScanComplete;
    }

    private int scanNearSyncColumns(
            int playerCx,
            int playerCz,
            int lodDistance,
            int protectedSyncDistance,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            boolean[] allowGeneration,
            boolean[] cacheProbe,
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            ScanBudget scanBudget) {
        if (nearScanCompletedForCurrentOffsets
                || orderedOffsetCount == 0
                || !requestWindow.hasNormalCandidateCapacity(0)
                || lodDistance <= 0
                || !scanBudget.canScanMore()) {
            return count;
        }

        int maxAllowedRing = Math.min(lodDistance, VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS);
        int totalCandidates = orderedOffsetCount;
        while (count < maxCount
                && requestWindow.hasNormalCandidateCapacity(0)
                && scanBudget.canScanMore()) {
            if (nearScanOffsetIndex >= totalCandidates) {
                nearScanCompletedForCurrentOffsets = true;
                return count;
            }

            long offset = ChebyshevRingOffsets.offsetAt(nearScanOffsetIndex);
            int offsetRing = offsetRing(offset);
            if (offsetRing > maxAllowedRing) {
                nearScanCompletedForCurrentOffsets = true;
                return count;
            }

            int cx = playerCx + decodeOffsetX(offset);
            int cz = playerCz + decodeOffsetZ(offset);
            long packed = PositionUtil.packPosition(cx, cz);
            if (isInsideProtectedSyncWindow(packed, playerCx, playerCz, protectedSyncDistance)
                    || dirtyColumns.contains(packed)
                    || requeueGenerationCandidate(packed)
                    || !shouldRequestColumn(packed, now)) {
                nearScanOffsetIndex++;
                scanBudget.recordCandidate();
                updateSoftFrontier(offsetRing, lodDistance);
                continue;
            }
            if (shouldWaitForFirstPassCacheProbe(packed, requestWindow)) {
                break;
            }
            nearScanOffsetIndex++;
            scanBudget.recordCandidate();
            updateSoftFrontier(offsetRing, lodDistance);

            count = appendRequestCluster(
                    packed,
                    playerCx,
                    playerCz,
                    lodDistance,
                    protectedSyncDistance,
                    requestIds,
                    positions,
                    timestamps,
                    allowGeneration,
                    cacheProbe,
                    count,
                    maxCount,
                    requestWindow,
                    now,
                    maxAllowedRing);
        }
        return count;
    }

    private void auditKnownPresence(
            ClientLevel level,
            int playerCx,
            int playerCz,
            int lodDistance) {
        if (!ModCompat.isVoxyLoaded() || lodDistance <= 0) {
            return;
        }
        ensureOrderedOffsets(lodDistance);
        int candidateCount = orderedOffsetCount;
        if (candidateCount <= 0) {
            return;
        }

        long deadline = System.nanoTime() + PRESENCE_AUDIT_MAX_NANOS;
        for (int checked = 0; checked < PRESENCE_AUDIT_CANDIDATES_PER_TICK; checked++) {
            if ((checked & 7) == 0 && System.nanoTime() >= deadline) {
                break;
            }
            if (presenceAuditOffsetIndex >= candidateCount) {
                presenceAuditOffsetIndex = 0;
            }
            long offset = ChebyshevRingOffsets.offsetAt(presenceAuditOffsetIndex++);
            long packed = PositionUtil.packPosition(
                    playerCx + decodeOffsetX(offset),
                    playerCz + decodeOffsetZ(offset));
            if (columnTimestamps.get(packed) <= 0L || requestTracker.contains(packed)) {
                continue;
            }
            if (presenceReporter.getLocalColumnState(level, lastDimension, packed)
                    == ModCompat.LocalColumnState.MISSING) {
                reconcileMissingColumn(packed);
            }
        }
    }

    private int drainDeferredColumns(
            int playerCx,
            int playerCz,
            int lodDistance,
            int protectedSyncDistance,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            boolean[] allowGeneration,
            boolean[] cacheProbeFlags,
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            ScanBudget scanBudget) {
        if (count >= maxCount) {
            generationDiagnostics.record("deferredAllBatchFull");
            return count;
        }
        if (deferredColumns.queuedEntries() <= 0) {
            return count;
        }
        if (!scanBudget.canScanMore()) {
            generationDiagnostics.record("deferredAllBudgetExhausted");
            return count;
        }

        var postponed = new it.unimi.dsi.fastutil.longs.LongArrayList();
        int attempts = Math.min(MAX_DEFERRED_CANDIDATES_PER_TICK, deferredColumns.queuedEntries());
        try {
            while (attempts > 0 && count < maxCount && scanBudget.canScanMore()
                    && (requestWindow.hasAnyNormalCandidateCapacity()
                        || !dirtyColumns.isEmpty() && requestWindow.canSend(true, false, 0))) {
                // The queue already orders urgent work and then distance. Pull
                // small batches instead of removing/sorting/reinserting 2,048
                // entries even when only one request slot remains.
                LongList candidates = deferredColumns.pollClosestCandidates(
                        Math.min(32, Math.min(attempts, scanBudget.remainingCandidates())));
                if (candidates.isEmpty()) break;
                attempts -= candidates.size();
                generationDiagnostics.add("deferredAllPolled", candidates.size());
                LongIterator candidateIterator = candidates.longIterator();
                while (candidateIterator.hasNext()) {
                    long packed = candidateIterator.nextLong();
                    boolean dirtyRefresh = dirtyColumns.contains(packed);
                    if (!scanBudget.canScanMore()) {
                        generationDiagnostics.record("deferredCandidateBudgetExhausted");
                        postponed.add(packed);
                        continue;
                    }
                    scanBudget.recordCandidate();
                    if (count >= maxCount || !requestWindow.hasCapacity()) {
                        generationDiagnostics.record("deferredCandidateBatchOrWindowFull");
                        postponed.add(packed);
                        continue;
                    }
                    if (!deferredColumns.remove(packed)) {
                        generationDiagnostics.record("deferredNoLongerQueued");
                        continue;
                    }

                    int cx = PositionUtil.unpackX(packed);
                    int cz = PositionUtil.unpackZ(packed);
                    int ring = PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz);

                    if (ring > lodDistance) {
                        generationDiagnostics.record("deferredOutsideDistance");
                        if (!dirtyRefresh) {
                            clearMissState(packed);
                        }
                        continue;
                    }

                    if (isCoolingDown(packed, now)) {
                        generationDiagnostics.record("deferredCooldown");
                        postponed.add(packed);
                        continue;
                    }
                    if (!shouldRequestColumn(packed, now)) {
                        generationDiagnostics.record("deferredNotRequestable");
                        continue;
                    }

                    boolean generationCandidate = !dirtyRefresh && isGenerationCandidate(packed);
                    boolean cacheProbe = !dirtyRefresh
                            && !generationCandidate
                            && requiresFirstPassCacheProbe(packed);
                    if (!dirtyRefresh
                            && !generationCandidate
                            && !cacheProbe
                            && isInsideProtectedSyncWindow(packed, playerCx, playerCz, protectedSyncDistance)) {
                        generationDiagnostics.record("deferredProtectedWindow");
                        continue;
                    }
                    if (!requestWindow.canSend(dirtyRefresh, generationCandidate, cacheProbe, ring)) {
                        generationDiagnostics.record(generationCandidate ? "deferredNoGenerationSlot"
                                : dirtyRefresh ? "deferredNoDirtySlot" : cacheProbe ? "deferredNoProbeSlot"
                                : "deferredNoSyncSlot");
                        postponed.add(packed);
                        continue;
                    }

                    count = appendRequest(
                            packed,
                            requestIds,
                            positions,
                            timestamps,
                            allowGeneration,
                            cacheProbeFlags,
                            count,
                            generationCandidate,
                            cacheProbe,
                            now);
                    requestWindow.record(dirtyRefresh, generationCandidate, cacheProbe, ring);
                }
            }
        } finally {
            for (long packed : postponed) requeueDeferredColumn(packed, dirtyColumns.contains(packed));
        }
        return count;
    }

    private int scanNewSyncColumns(
            int playerCx,
            int playerCz,
            int lodDistance,
            int protectedSyncDistance,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            boolean[] allowGeneration,
            boolean[] cacheProbe,
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            int maxAllowedRing,
            ScanBudget scanBudget) {
        if (!requestWindow.hasAnyNormalCandidateCapacity() || orderedOffsetCount == 0 || !scanBudget.canScanMore()) {
            return count;
        }
        if (scanCompletedForCurrentOffsets) {
            if (now - nextFullScanRetryNanos < 0L) {
                return count;
            }
            scanCompletedForCurrentOffsets = false;
            scanOffsetIndex = 0;
        }

        int totalCandidates = orderedOffsetCount;

        RingScanGate ringGate = RingScanGate.fromCursor(scanOffsetIndex, totalCandidates);

        while (count < maxCount
                && requestWindow.hasAnyNormalCandidateCapacity()
                && scanBudget.canScanMore()) {
            if (scanOffsetIndex >= totalCandidates) {
                scanCompletedForCurrentOffsets = true;
                softFrontierRadius = lodDistance;
                nextFullScanRetryNanos = now + FULL_SCAN_RETRY_INTERVAL_NANOS;
                return count;
            }

            long offset = ChebyshevRingOffsets.offsetAt(scanOffsetIndex);
            int offsetRing = offsetRing(offset);

            if (offsetRing > maxAllowedRing) {
                break;
            }
            if (!requestWindow.hasNormalCandidateCapacity(offsetRing)) {
                break;
            }

            // Scan at most the ring where this tick started. Cooldowns and known
            // columns must not let the cursor leap over intermediate rings.
            if (!ringGate.allows(offsetRing)) {
                break;
            }

            int cx = playerCx + decodeOffsetX(offset);
            int cz = playerCz + decodeOffsetZ(offset);
            long packed = PositionUtil.packPosition(cx, cz);
            if (isInsideProtectedSyncWindow(packed, playerCx, playerCz, protectedSyncDistance)
                    || dirtyColumns.contains(packed)
                    || requeueGenerationCandidate(packed)
                    || !shouldRequestColumn(packed, now)) {
                scanOffsetIndex++;
                scanBudget.recordCandidate();
                updateSoftFrontier(offsetRing, lodDistance);
                continue;
            }
            if (shouldWaitForFirstPassCacheProbe(packed, requestWindow)) {
                break;
            }
            scanOffsetIndex++;
            scanBudget.recordCandidate();
            updateSoftFrontier(offsetRing, lodDistance);

            count = appendRequestCluster(
                    packed,
                    playerCx,
                    playerCz,
                    lodDistance,
                    protectedSyncDistance,
                    requestIds,
                    positions,
                    timestamps,
                    allowGeneration,
                    cacheProbe,
                    count,
                    maxCount,
                    requestWindow,
                    now,
                    maxAllowedRing);

        }
        return count;
    }

    private int appendRequestCluster(
            long centerPacked,
            int playerCx,
            int playerCz,
            int lodDistance,
            int protectedSyncDistance,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            boolean[] allowGeneration,
            boolean[] cacheProbe,
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            int maxAllowedRing) {
        int centerX = PositionUtil.unpackX(centerPacked);
        int centerZ = PositionUtil.unpackZ(centerPacked);
        int centerRing = chebyshevDistance(centerPacked, playerCx, playerCz);

        if (centerRing > maxAllowedRing) {
            return count;
        }

        count = appendClusterCandidate(centerPacked, playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds,
                positions, timestamps, allowGeneration, cacheProbe, count, maxCount, requestWindow, now, centerRing);

        for (int dz = -1; dz <= 1 && count < maxCount && requestWindow.hasNormalCandidateCapacity(centerRing); dz++) {
            for (int dx = -1; dx <= 1 && count < maxCount && requestWindow.hasNormalCandidateCapacity(centerRing); dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long packed = PositionUtil.packPosition(centerX + dx, centerZ + dz);
                int neighborRing = chebyshevDistance(packed, playerCx, playerCz);

                if (neighborRing <= centerRing && neighborRing <= maxAllowedRing) {
                    count = appendClusterCandidate(packed, playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds,
                            positions, timestamps, allowGeneration, cacheProbe, count, maxCount, requestWindow, now, centerRing);
                }
            }
        }
        return count;
    }

    private int appendClusterCandidate(
            long packed,
            int playerCx,
            int playerCz,
            int lodDistance,
            int protectedSyncDistance,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            boolean[] allowGeneration,
            boolean[] cacheProbeFlags,
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            int maxClusterRing) {
        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        int ring = PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz);
        if (count >= maxCount) {
            return count;
        }
        if (ring > lodDistance
                || (protectedSyncDistance > 0 && ring <= protectedSyncDistance)
                || ring > maxClusterRing
                || !shouldRequestColumn(packed, now)) {
            return count;
        }

        if (dirtyColumns.contains(packed) || requeueGenerationCandidate(packed)) {
            return count;
        }

        if (shouldWaitForFirstPassCacheProbe(packed, requestWindow)) {
            return count;
        }
        boolean generationCandidate = isGenerationCandidate(packed);
        boolean cacheProbe = !generationCandidate
                && shouldUseFirstPassCacheProbe(packed, ring, requestWindow);
        if (!requestWindow.canSend(false, generationCandidate, cacheProbe, ring)) {
            return count;
        }

        count = appendRequest(
                packed,
                requestIds,
                positions,
                timestamps,
                allowGeneration,
                cacheProbeFlags,
                count,
                generationCandidate,
                cacheProbe,
                now);
        requestWindow.record(false, generationCandidate, cacheProbe, ring);
        return count;
    }

    private boolean shouldUseFirstPassCacheProbe(long packed, int ring, RequestWindow requestWindow) {
        if (!requestWindow.hasCacheProbeCapacity() || !requiresFirstPassCacheProbe(packed)) {
            return false;
        }
        return requestWindow.canSend(false, false, true, ring);
    }

    private boolean shouldWaitForFirstPassCacheProbe(long packed, RequestWindow requestWindow) {
        return requiresFirstPassCacheProbe(packed) && !requestWindow.hasCacheProbeCapacity();
    }

    private boolean requiresFirstPassCacheProbe(long packed) {
        return sessionConfig != null
                && sessionConfig.enabled()
                && columnTimestamps.get(packed) <= 0L
                && !hasKnownColumn(packed);
    }

    private boolean shouldRequestColumn(long packed, long now) {
        boolean dirty = dirtyColumns.contains(packed);
        if (requestTracker.contains(packed)) {
            return false;
        }
        if (isCoolingDown(packed, now)) {
            return false;
        }

        long timestamp = columnTimestamps.get(packed);
        if (timestamp > 0L && !dirty) {
            return false;
        }
        if (dirty) {
            return true;
        }
        if (timestamp == 0L && !isGenerationCandidate(packed)) {
            return false;
        }
        return true;
    }

    private boolean isWithinCurrentLodWindow(long packed) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft != null ? minecraft.player : null;
        ClientLevel level = minecraft != null ? minecraft.level : null;
        if (player == null || level == null || player.isRemoved()) {
            return false;
        }
        if (lastDimension != null && !lastDimension.equals(level.dimension())) {
            return false;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        return chebyshevDistance(packed, playerCx, playerCz)
                <= getEffectiveLodDistance() + VSSConstants.LOD_DISTANCE_BUFFER;
    }

    private boolean isColumnVersionAllowed(long packed, long columnTimestamp, boolean strictUpdate) {
        long requiredTimestamp = dirtyColumnTimestamps.get(packed);
        long currentTimestamp = columnTimestamps.get(packed);
        return isColumnVersionAllowed(currentTimestamp, requiredTimestamp, columnTimestamp, strictUpdate);
    }

    static boolean isColumnVersionAllowed(
            long currentTimestamp,
            long requiredTimestamp,
            long columnTimestamp,
            boolean strictUpdate) {
        if (columnTimestamp <= 0L
                || (requiredTimestamp > 0L && columnTimestamp < requiredTimestamp)) {
            return false;
        }
        if (currentTimestamp <= 0L) {
            return true;
        }
        return strictUpdate ? columnTimestamp > currentTimestamp : columnTimestamp >= currentTimestamp;
    }

    private void failTrackedRequest(int requestId, long packed) {
        if (requestId < 0 || !requestTracker.matches(requestId, packed)) {
            return;
        }
        requestTracker.cancelRequest(requestId);
        requestScanPending = true;
        markBackoff(packed, isGenerationCandidate(packed));
        deferredColumns.remove(packed);
        deferColumn(packed, dirtyColumns.contains(packed));
    }

    private boolean isActiveDimension(ResourceKey<Level> dimension) {
        if (lastDimension != null) {
            return lastDimension.equals(dimension);
        }
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(dimension);
    }

    private void rememberKnownColumn(
            ResourceKey<Level> dimension,
            long packed,
            long columnTimestamp,
            int[] replacementSectionYs) {
        presenceReporter.recordKnownColumn(
                dimension,
                packed,
                columnTimestamp,
                replacementSectionYs);
    }

    void restoreKnownColumn(long packed, long columnTimestamp) {
        if (columnTimestamp > 0L) {
            if (!strictSections.containsKey(strictSectionsKey(packed))) {
                byte[] manifest = presenceReporter.sectionManifest(lastDimension, packed);
                if (manifest != null) {
                    int[] sections = new int[manifest.length];
                    for (int i = 0; i < manifest.length; i++) sections[i] = manifest[i];
                    strictSections.put(strictSectionsKey(packed), sections);
                    strictUnavailableColumns.remove(packed);
                    dev.xantha.vss.compat.StrictLodVisibility.workChanged();
                }
            }
            columnTimestamps.put(packed, Math.max(columnTimestamps.get(packed), columnTimestamp));
            diskMissedColumns.remove(packed);
            if (!dirtyColumns.contains(packed)) {
                deferredColumns.remove(packed);
                clearBackoff(packed);
            }
        }
    }

    boolean reconcileMissingColumn(long packed) {
        requestScanPending = true;
        strictUnavailableColumns.remove(packed);
        strictSections.remove(strictSectionsKey(packed));
        if (dev.xantha.vss.compat.StrictLodVisibility.completed(lastDimension,
                PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed))) {
            dev.xantha.vss.compat.StrictLodVisibility.invalidate();
        }
        columnTimestamps.remove(packed);
        if (lastDimension != null) {
            presenceReporter.removeKnownColumn(lastDimension, packed);
        }
        clearBackoff(packed);
        if (requestTracker.contains(packed)) {
            return false;
        }
        deferredColumns.remove(packed);
        deferColumn(packed, dirtyColumns.contains(packed));
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        armScanBoost();
        return true;
    }

    private boolean isGenerationCandidate(long packed) {
        if (!generationAllowed()) {
            return false;
        }
        return diskMissedColumns.contains(packed);
    }

    private boolean requeueGenerationCandidate(long packed) {
        if (!isGenerationCandidate(packed)) {
            return false;
        }
        if (!requestTracker.contains(packed) && !deferredColumns.contains(packed)) {
            columnTimestamps.remove(packed);
            deferColumn(packed);
        }
        return true;
    }

    private void clearMissState(long packed) {
        diskMissedColumns.remove(packed);
        if (columnTimestamps.get(packed) == 0L) {
            columnTimestamps.remove(packed);
        }
        clearBackoff(packed);
    }

    private boolean shouldPromoteNotGeneratedToGeneration(long packed) {
        if (!generationAllowed()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft != null ? minecraft.player : null;
        if (player == null) {
            return false;
        }
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int ring = chebyshevDistance(packed, playerCx, playerCz);
        return ring <= Math.min(getEffectiveLodDistance(), softFrontierRadius);
    }

    private boolean hasKnownColumn(long packed) {
        return columnTimestamps.get(packed) > 0L;
    }

    private boolean hasDirtyTimestamp(long packed) {
        return dirtyColumnTimestamps.get(packed) > 0L;
    }

    private int appendRequest(
            long packed,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            boolean[] allowGeneration,
            boolean[] cacheProbeFlags,
            int count,
            boolean generationCandidate,
            boolean cacheProbeRequest,
            long now) {
        int requestId = requestTracker.track(
                packed,
                generationCandidate,
                cacheProbeRequest,
                dirtyColumns.contains(packed),
                timeoutFor(packed, requestTracker.size()),
                now);
        suppressedResponses.remove(packed);
        requestIds[count] = requestId;
        positions[count] = packed;
        timestamps[count] = requestTimestampFor(packed);
        allowGeneration[count] = generationCandidate;
        cacheProbeFlags[count] = cacheProbeRequest;
        if (lastDimension != null) {
            ClientPredictionState.markRequested(
                    lastDimension,
                    PositionUtil.unpackX(packed),
                    PositionUtil.unpackZ(packed));
        }
        return count + 1;
    }

    private boolean generationAllowed() {
        // Xaero map replay is independent of terrain generation.
        return sessionConfig != null && sessionConfig.generationEnabled();
    }

    long requestTimestampFor(long packed) {
        long timestamp = columnTimestamps.get(packed);
        if (!dirtyColumns.contains(packed) || timestamp > 0L) {
            return timestamp;
        }
        long dirtyTimestamp = dirtyColumnTimestamps.get(packed);
        return dirtyTimestamp > 1L ? dirtyTimestamp - 1L : 1L;
    }

    int effectiveLodDistanceChunks() {
        return getEffectiveLodDistance();
    }

    static int limitForXaeroBackpressure(int maxCount, boolean backpressure) {
        // Retained as a compatibility seam; map backlog never limits VSS.
        return Math.max(0, maxCount);
    }

    private int getEffectiveLodDistance() {
        if (sessionConfig == null || !sessionConfig.enabled()) {
            return 0;
        }
        int serverDistance = sessionConfig.lodDistanceChunks();
        int clientDistance = VSSClientConfig.CONFIG.lodDistanceChunks;
        int hardClientLimit = VSSClientConfig.MAX_LOD_DISTANCE_CHUNKS;
        if (clientDistance > 0) {
            return Math.min(Math.min(clientDistance, serverDistance), hardClientLimit);
        }
        // Voxy's render distance controls drawing of its own persistent nodes,
        // not how far VSS may request/cache real columns. Keep the two radii
        // independent so Voxy retains its normal ultra-distance behavior.
        return Math.min(serverDistance, hardClientLimit);
    }

    private void logRequestBatch(
            long now,
            int count,
            int syncCount,
            int generationCount,
            int dirtyCount,
            int lodDistance,
            int playerCx,
            int playerCz) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
        if (now - lastRequestDiagnosticNanos < REQUEST_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastRequestDiagnosticNanos = now;
        VSSLogger.debug("LOD requests sent: count=" + count
                + ", cacheProbe=" + (count - syncCount - generationCount - dirtyCount)
                + ", sync=" + syncCount
                + ", generation=" + generationCount
                + ", dirty=" + dirtyCount
                + ", distance=" + lodDistance
                + ", pending=" + requestTracker.size()
                + ", deferred=" + deferredColumns.size()
                + ", playerChunk=" + playerCx + "," + playerCz);
    }

    /** Reports even when scanAndSend emits no packet; does not consume scan budget. */
    private void logGenerationDiagnostics(int playerCx, int playerCz, int lodDistance) {
        String events = generationDiagnostics.poll(System.nanoTime());
        if (events == null) {
            return;
        }
        VSSLogger.debug("VSS generation diagnostic client v1: scheduler=near-first-unrestricted-display-v3, strict="
                + dev.xantha.vss.compat.StrictLodVisibility.diagnostics() + ", dimension=" + lastDimension.location()
                + ", playerChunk=" + playerCx + "," + playerCz
                + ", distance=" + lodDistance
                + ", generationEnabled=" + sessionConfig.generationEnabled()
                + ", generationAllowed=" + generationAllowed()
                + ", cacheOnly=" + cacheOnlyReload.isActive()
                + ", xaeroBackpressure=" + diagnosticXaeroBackpressure
                + ", nearScanComplete=" + nearScanCompletedForCurrentOffsets
                + ", nearCursor=" + nearScanOffsetIndex
                + ", outerScanComplete=" + scanCompletedForCurrentOffsets
                + ", outerCursor=" + scanOffsetIndex + "/" + orderedOffsetCount
                + ", frontier=" + softFrontierRadius
                + ", missMarked=" + diskMissedColumns.size()
                + ", deferred=" + deferredColumns.size()
                + ", deferredQueued=" + deferredColumns.queuedEntries()
                + ", pending=" + requestTracker.size()
                + ", generationInFlight=" + requestTracker.generationSize()
                + ", probesInFlight=" + requestTracker.cacheProbeSize()
                + ", generationSlotsAtScanStart=" + diagnosticGenerationSlots
                + ", predictionPriority=" + predictionPriority.diagnostics()
                + ", generationLimit=" + diagnosticGenerationLimit
                + ", predictionLoading=" + ClientPredictionState.loadingProgress(lastDimension)
                + ", cpuBudget={" + PredictionCpuBudget.SHARED.diagnostics() + "}"
                + ", batchLimit=" + diagnosticBatchLimit
                + ", " + events);
    }

    private void timeoutSweep() {
        long now = System.nanoTime();
        for (ClientRequestTracker.TimedOutRequest request : requestTracker.drainTimedOut(now)) {
            requestScanPending = true;
            long packed = request.packed();
            generationDiagnostics.record("requestTimeout");
            markTimeout(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    private long timeoutFor(long packed, int queuedAheadRequests) {
        long baseTimeout = SYNC_REQUEST_TIMEOUT_NANOS;
        if (requestTracker.isGenerationPosition(packed)) {
            baseTimeout = GENERATION_REQUEST_TIMEOUT_NANOS;
        }
        long queueTimeout = queueTimeoutNanos(queuedAheadRequests + 1, effectiveReceiveBandwidthBytesPerSecond());
        return Math.min(MAX_REQUEST_TIMEOUT_NANOS, Math.max(baseTimeout, queueTimeout));
    }

    private long generationTimeoutFor(int queuedAheadRequests) {
        long queueTimeout = queueTimeoutNanos(
                queuedAheadRequests + 1,
                effectiveReceiveBandwidthBytesPerSecond());
        return Math.min(
                MAX_REQUEST_TIMEOUT_NANOS,
                Math.max(GENERATION_REQUEST_TIMEOUT_NANOS, queueTimeout));
    }

    static long queueTimeoutNanos(int requestCount, long bandwidthBytesPerSecond) {
        long bandwidth = Math.max(1L, bandwidthBytesPerSecond);
        long bytes = multiplySaturated(Math.max(1L, requestCount), ESTIMATED_SYNC_COLUMN_BYTES);
        long baseNanos = bytesToNanos(bytes, bandwidth);
        return multiplySaturated(baseNanos, REQUEST_TIMEOUT_GRACE_MULTIPLIER);
    }

    private long effectiveReceiveBandwidthBytesPerSecond() {
        long serverLimit = sessionConfig != null && sessionConfig.serverBandwidthLimit() > 0L
                ? sessionConfig.serverBandwidthLimit()
                : Long.MAX_VALUE;
        long clientLimit = VSSClientConfig.CONFIG.desiredBandwidthKbps > 0
                ? (long) VSSClientConfig.CONFIG.desiredBandwidthKbps * 1000L / 8L
                : Long.MAX_VALUE;
        return Math.min(serverLimit, clientLimit);
    }

    private static long bytesToNanos(long bytes, long bytesPerSecond) {
        if (bytes > Long.MAX_VALUE / 1_000_000_000L) {
            return Long.MAX_VALUE;
        }
        return bytes * 1_000_000_000L / bytesPerSecond;
    }

    private static long multiplySaturated(long value, long factor) {
        if (factor > 0L && value > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return value * factor;
    }

    private void pruneLowPriorityRequestsAround(int playerCx, int playerCz, int keepDistance) {
        LongOpenHashSet staleRequests = new LongOpenHashSet();
        requestTracker.forEachInFlight(packed -> {
            if (!requestTracker.isDirtyRefreshPosition(packed)
                    && chebyshevDistance(packed, playerCx, playerCz) > keepDistance) {
                staleRequests.add(packed);
            }
        });
        for (long packed : staleRequests) {
            suppressedResponses.add(packed);
            requestTracker.cancel(packed);
            diskMissedColumns.remove(packed);
            clearBackoff(packed);
        }

        LongOpenHashSet staleDeferred = new LongOpenHashSet();
        for (long packed : deferredColumns) {
            if (!dirtyColumns.contains(packed)
                    && chebyshevDistance(packed, playerCx, playerCz) > keepDistance) {
                staleDeferred.add(packed);
            }
        }
        for (long packed : staleDeferred) {
            deferredColumns.remove(packed);
            diskMissedColumns.remove(packed);
            clearBackoff(packed);
        }
        if (!staleDeferred.isEmpty()) {
            deferredColumns.compact();
        }
    }

    private void pruneStaleGenerationWorkAround(int playerCx, int playerCz, int lodDistance) {
        if (!generationAllowed()) {
            return;
        }

        // Scanning is discovery progress, not the lifetime of an accepted request.
        int activeGenerationRadius = lodDistance;
        LongOpenHashSet staleRequests = new LongOpenHashSet();
        requestTracker.forEachGenerationInFlight(packed -> {
            if (!requestTracker.isDirtyRefreshPosition(packed)
                    && chebyshevDistance(packed, playerCx, playerCz) > activeGenerationRadius) {
                staleRequests.add(packed);
            }
        });
        for (long packed : staleRequests) {
            requestTracker.cancel(packed);
            deferredColumns.remove(packed);
            clearMissState(packed);
        }

        LongOpenHashSet staleDeferred = new LongOpenHashSet();
        for (long packed : deferredColumns) {
            if (!dirtyColumns.contains(packed)
                    && diskMissedColumns.contains(packed)
                    && chebyshevDistance(packed, playerCx, playerCz) > activeGenerationRadius) {
                staleDeferred.add(packed);
            }
        }
        for (long packed : staleDeferred) {
            deferredColumns.remove(packed);
            clearMissState(packed);
        }
        if (!staleDeferred.isEmpty()) {
            deferredColumns.compact();
        }
    }

    private void pruneAround(int playerCx, int playerCz, int pruneDistance) {
        LongOpenHashSet staleColumns = new LongOpenHashSet();
        for (long packed : columnTimestamps.keySet()) {
            if (PositionUtil.isOutOfRange(packed, playerCx, playerCz, pruneDistance)) {
                staleColumns.add(packed);
            }
        }
        // Unavailable columns need pruning even when no timestamp was stored.
        for (long packed : strictUnavailableColumns) {
            if (PositionUtil.isOutOfRange(packed, playerCx, playerCz, pruneDistance)) {
                staleColumns.add(packed);
            }
        }
        for (long packed : staleColumns) {
            strictSections.remove(strictSectionsKey(packed));
            strictUnavailableColumns.remove(packed);
            columnTimestamps.remove(packed);
            dirtyColumns.remove(packed);
            dirtyColumnTimestamps.remove(packed);
            deferredColumns.remove(packed);
            diskMissedColumns.remove(packed);
            clearBackoff(packed);
        }

        LongOpenHashSet staleRequests = new LongOpenHashSet();
        requestTracker.forEachInFlight(packed -> {
            if (PositionUtil.isOutOfRange(packed, playerCx, playerCz, pruneDistance)) {
                staleRequests.add(packed);
            }
        });
        for (long packed : staleRequests) {
            requestTracker.cancel(packed);
            deferredColumns.remove(packed);
            clearMissState(packed);
        }
    }

    private void deferColumn(long packed) {
        deferredColumns.defer(packed);
    }

    private void deferColumn(long packed, boolean urgent) {
        deferredColumns.defer(packed, urgent);
    }

    private void requeueDeferredColumn(long packed, boolean urgent) {
        deferredColumns.requeue(packed, urgent);
    }

    private static int maxRequestsPerTick() {
        return isIntegratedServer() ? INTEGRATED_MAX_REQUESTS_PER_TICK : MAX_REQUESTS_PER_TICK;
    }

    private static boolean isIntegratedServer() {
        var minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.getSingleplayerServer() != null;
    }

    private static int chebyshevDistance(long packed, int playerCx, int playerCz) {
        return PositionUtil.chebyshevDistance(PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed),
                playerCx, playerCz);
    }

    private void resetRequestState() {
        predictionPriority.reset();
        PredictionCpuBudget.SHARED.observeLocalGeneration(false, 0);
        diagnosticGenerationLimit = 0;
        dev.xantha.vss.compat.StrictLodVisibility.invalidate();
        strictSections.clear();
        strictUnavailableColumns.clear();
        requestScanPending = true;
        generationDiagnostics.reset();
        diagnosticBatchLimit = 0;
        diagnosticGenerationSlots = 0;
        diagnosticXaeroBackpressure = false;
        requestTracker.cancelAll();
        columnTimestamps.clear();
        dirtyColumns.clear();
        dirtyColumnTimestamps.clear();
        requestTracker.clear();
        transferReset.run();
        deferredColumns.clear();
        diskMissedColumns.clear();
        suppressedResponses.clear();
        retryBackoff.clearAll();
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        scanOffsetIndex = 0;
        nearScanOffsetIndex = 0;
        presenceAuditOffsetIndex = 0;
        presenceAuditTickCounter = 0;
        softFrontierRadius = 0;
        lastEffectiveLodDistance = -1;
        scanCompletedForCurrentOffsets = false;
        nearScanCompletedForCurrentOffsets = false;
        nearScanCompletedOnce = false;
        nextFullScanRetryNanos = 0L;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        dirtyRefreshBudget = 0.0D;
        presenceReporter.reset(lastDimension);
        armScanBoost();
    }

    private void armScanBoost() {
        scanBoostTicks = SCAN_BOOST_TICKS;
    }

    private void resetScanCursor() {
        scanOffsetIndex = 0;
        resetNearScanCursor();
        nearScanCompletedOnce = false;
        scanCompletedForCurrentOffsets = false;
        nextFullScanRetryNanos = 0L;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void resetNearScanCursor() {
        nearScanOffsetIndex = 0;
        presenceAuditOffsetIndex = 0;
        nearScanCompletedForCurrentOffsets = false;
    }

    private void resetScanCursorPastProtectedSyncWindow(int lodDistance) {
        int protectedSyncDistance = getVanillaProtectedSyncDistance();
        if (protectedSyncDistance <= 0) {
            resetScanCursor();
            return;
        }
        ensureOrderedOffsets(lodDistance);
        setScanCursorAtRing(Math.min(lodDistance + 1, protectedSyncDistance + 1));
    }

    private void rebaseScanCursorAfterMove(int lodDistance) {
        ensureOrderedOffsets(lodDistance);
        // Preserve both incomplete passes while walking. Restarting each time the
        // player crosses a chunk kept distant discovery pinned to the inner rings.
        if (nearScanCompletedForCurrentOffsets) {
            nearScanOffsetIndex = 0;
            nearScanCompletedForCurrentOffsets = false;
        }
        // The new edge of the translated window must be visited on the next pass.
        // Do not reset an active pass, the audit cursor, or accepted generation.
        nextFullScanRetryNanos = 0L;
    }

    private void setScanCursorAtRing(int ring) {
        if (orderedOffsetCount == 0 || ring <= 0) {
            resetScanCursor();
            return;
        }
        int clampedRing = Math.min(ring, orderedOffsetDistance + 1);
        int index = Math.min(orderedOffsetCount, ChebyshevRingOffsets.firstIndexForRing(clampedRing));
        resetNearScanCursor();
        nearScanCompletedOnce = false;
        scanOffsetIndex = index;
        softFrontierRadius = Math.max(0, Math.min(ring - 1, orderedOffsetDistance));
        scanCompletedForCurrentOffsets = index >= orderedOffsetCount;
        nextFullScanRetryNanos = 0L;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void ensureOrderedOffsets(int lodDistance) {
        if (orderedOffsetDistance == lodDistance) {
            return;
        }

        orderedOffsetCount = ChebyshevRingOffsets.count(lodDistance);
        orderedOffsetDistance = lodDistance;
        resetScanCursor();
    }

    static int getVanillaProtectedSyncDistance() {
        return 0;
    }

    private static boolean isInsideProtectedSyncWindow(
            long packed,
            int playerCx,
            int playerCz,
            int protectedSyncDistance) {
        return protectedSyncDistance > 0 && chebyshevDistance(packed, playerCx, playerCz) <= protectedSyncDistance;
    }

    private void updateSoftFrontier(int ring, int lodDistance) {
        softFrontierRadius = Math.min(maxFrontierRadius(lodDistance), Math.max(softFrontierRadius, ring));
    }

    private static int maxFrontierRadius(int lodDistance) {
        return lodDistance;
    }

    private static long encodeOffset(int dx, int dz) {
        return ChebyshevRingOffsets.encode(dx, dz);
    }

    private static int decodeOffsetX(long offset) {
        return ChebyshevRingOffsets.decodeX(offset);
    }

    private static int decodeOffsetZ(long offset) {
        return ChebyshevRingOffsets.decodeZ(offset);
    }

    private static int offsetRing(long offset) {
        return ChebyshevRingOffsets.ring(offset);
    }

    static final class ScanBudget {
        private int remainingCandidates;
        private int checksUntilDeadline = 1;
        private final long deadlineNanos;
        private final java.util.function.LongSupplier clock;
        private final ScanBudget parent;
        private boolean expired;

        private ScanBudget(int remainingCandidates, long deadlineNanos) {
            this(remainingCandidates, deadlineNanos, System::nanoTime, null);
        }

        ScanBudget(int remainingCandidates, long deadlineNanos, java.util.function.LongSupplier clock) {
            this(remainingCandidates, deadlineNanos, clock, null);
        }

        private ScanBudget(int remainingCandidates, long deadlineNanos,
                           java.util.function.LongSupplier clock, ScanBudget parent) {
            this.remainingCandidates = Math.max(0, remainingCandidates);
            this.deadlineNanos = deadlineNanos;
            this.clock = clock;
            this.parent = parent;
        }

        static ScanBudget create(boolean boosted) {
            int candidateLimit = boosted ? BOOSTED_SCAN_CANDIDATES_PER_TICK : MAX_SCAN_CANDIDATES_PER_TICK;
            long scanNanos = isIntegratedServer() ? INTEGRATED_MAX_SCAN_NANOS_PER_TICK : MAX_SCAN_NANOS_PER_TICK;
            return new ScanBudget(candidateLimit, System.nanoTime() + scanNanos);
        }

        ScanBudget slice(int divisor) {
            long now = clock.getAsLong();
            return new ScanBudget(Math.max(1, remainingCandidates / divisor),
                    now + Math.max(0L, deadlineNanos - now) / divisor, clock, this);
        }

        boolean canScanMore() {
            if (expired || remainingCandidates <= 0 || parent != null && !parent.canScanMore()) return false;
            if (--checksUntilDeadline > 0) return true;
            checksUntilDeadline = SCAN_DEADLINE_CHECK_INTERVAL;
            expired = clock.getAsLong() - deadlineNanos >= 0L;
            return !expired;
        }

        void recordCandidate() {
            remainingCandidates = Math.max(0, remainingCandidates - 1);
            if (parent != null) parent.recordCandidate();
        }

        int remainingCandidates() {
            return parent == null ? remainingCandidates : Math.min(remainingCandidates, parent.remainingCandidates());
        }
    }

    private boolean isCoolingDown(long packed, long now) {
        return retryBackoff.isCoolingDown(packed, now);
    }

    private void markBackoff(long packed, boolean generationCandidate) {
        retryBackoff.markBackoff(packed, dirtyColumns.contains(packed), generationCandidate);
    }

    private void markRateLimited(long packed) {
        retryBackoff.markRateLimited(packed);
    }

    private void markBackpressure(long packed) {
        retryBackoff.markBackpressure(packed);
    }

    private void markTimeout(long packed) {
        retryBackoff.markTimeout(packed);
    }

    private void clearBackoff(long packed) {
        retryBackoff.clear(packed);
    }

    public record ColumnReceiveResult(boolean knownRequest, boolean priority, boolean replaceExistingColumn, long packedPosition) {
    }

    public enum ColumnProcessingResult {
        APPLIED,
        STALE,
        FAILED
    }
}
