package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class LodRequestManager {
    private static final int SCAN_INTERVAL_TICKS = 0;
    private static final long SYNC_REQUEST_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long GENERATION_REQUEST_TIMEOUT_NANOS = 300_000_000_000L;
    private static final long DIRTY_REFRESH_BACKOFF_NANOS = 500_000_000L;
    private static final long BACKPRESSURE_BACKOFF_NANOS = 250_000_000L;
    private static final long RATE_LIMIT_BACKOFF_NANOS = 3_000_000_000L;
    private static final long GENERATION_BACKOFF_NANOS = 15_000_000_000L;
    private static final long MAX_RETRY_BACKOFF_NANOS = 60_000_000_000L;
    private static final int DIRTY_REFRESH_RATE_LIMIT = 512;
    private static final int DIRTY_REFRESH_CONCURRENCY_LIMIT = 64;
    private static final int MAX_SCAN_CANDIDATES_PER_TICK = 4096;
    private static final int BOOSTED_SCAN_CANDIDATES_PER_TICK = 32768;
    private static final int SCAN_BOOST_TICKS = 100;
    private static final int MAX_DEFERRED_CANDIDATES_PER_TICK = 2048;
    private static final int MAX_DEFERRED_COLUMNS = 65536;
    private static final int FAST_MOVE_CHUNK_THRESHOLD = 8;
    private static final int FAST_MOVE_KEEP_RADIUS_CHUNKS = 48;
    private static final int MOVEMENT_RESCAN_TRAIL_CHUNKS = 16;
    private static final int ESTIMATED_SYNC_COLUMN_BYTES = 5 * 1024;
    private static final int PRESENCE_REGIONS_PER_PACKET = 256;
    private static final int PRESENCE_COLUMNS_PER_PACKET = 8192;
    private static final int PRESENCE_PACKETS_PER_TICK = 4;
    private static final long PRESENCE_INCREMENTAL_RESEND_INTERVAL_NANOS = 5_000_000_000L;
    private static final int PRESENCE_INCREMENTAL_RESENDS_PER_TICK = 32;
    private static final long REQUEST_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;

    private static final Map<Integer, long[]> OFFSET_CACHE = new HashMap<>();

    static {
        for (int dist : new int[]{32, 64, 96, 128, 192, 256}) {
            OFFSET_CACHE.put(dist, generateOffsetsForDistance(dist));
        }
    }

    private static long[] generateOffsetsForDistance(int lodDistance) {
        int side = lodDistance * 2 + 1;
        long[] offsets = new long[side * side];
        LongOpenHashSet seenOffsets = new LongOpenHashSet(offsets.length);
        int index = 0;
        index = appendOffsetStatic(offsets, seenOffsets, index, 0, 0);
        for (int ring = 1; ring <= lodDistance; ring++) {
            index = appendOffsetStatic(offsets, seenOffsets, index, -ring, 0);
            index = appendOffsetStatic(offsets, seenOffsets, index, ring, 0);
            index = appendOffsetStatic(offsets, seenOffsets, index, 0, -ring);
            index = appendOffsetStatic(offsets, seenOffsets, index, 0, ring);
            for (int step = 1; step <= ring; step++) {
                index = appendOffsetStatic(offsets, seenOffsets, index, -ring, -step);
                index = appendOffsetStatic(offsets, seenOffsets, index, -ring, step);
                index = appendOffsetStatic(offsets, seenOffsets, index, ring, -step);
                index = appendOffsetStatic(offsets, seenOffsets, index, ring, step);
                index = appendOffsetStatic(offsets, seenOffsets, index, -step, -ring);
                index = appendOffsetStatic(offsets, seenOffsets, index, step, -ring);
                index = appendOffsetStatic(offsets, seenOffsets, index, -step, ring);
                index = appendOffsetStatic(offsets, seenOffsets, index, step, ring);
            }
        }
        return offsets;
    }

    private static int appendOffsetStatic(long[] offsets, LongOpenHashSet seenOffsets, int index, int dx, int dz) {
        long offset = encodeOffset(dx, dz);
        if (seenOffsets.add(offset)) {
            offsets[index++] = offset;
        }
        return index;
    }

    private final Long2LongOpenHashMap columnTimestamps = new Long2LongOpenHashMap();
    private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    private final Long2LongOpenHashMap dirtyColumnTimestamps = new Long2LongOpenHashMap();
    private final LongOpenHashSet inFlight = new LongOpenHashSet();
    private final LongOpenHashSet deferredColumns = new LongOpenHashSet();
    private final ArrayDeque<Long> deferredQueue = new ArrayDeque<>();
    private final Long2IntOpenHashMap positionToRequestId = new Long2IntOpenHashMap();
    private final Int2LongOpenHashMap requestIdToPosition = new Int2LongOpenHashMap();
    private final Long2LongOpenHashMap requestSendTimes = new Long2LongOpenHashMap();
    private final PriorityQueue<RequestDeadline> requestDeadlines = new PriorityQueue<>();
    private final LongOpenHashSet generationInFlight = new LongOpenHashSet();
    private final LongOpenHashSet dirtyRefreshInFlight = new LongOpenHashSet();
    private final Long2LongOpenHashMap retryAfterNanos = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap retryAttempts = new Long2IntOpenHashMap();
    private final LongOpenHashSet diskMissedColumns = new LongOpenHashSet();
    private final String presenceScope;
    private final LongOpenHashSet sentPresenceRegions = new LongOpenHashSet();
    private final LongOpenHashSet queuedPresenceRegions = new LongOpenHashSet();
    private final ArrayDeque<Long> pendingPresenceRegions = new ArrayDeque<>();
    private final ArrayDeque<Long> delayedPresenceRegions = new ArrayDeque<>();
    private final Long2LongOpenHashMap delayedPresenceRegionDeadlines = new Long2LongOpenHashMap();

    private SessionConfigS2CPayload sessionConfig;
    private ResourceKey<Level> lastDimension;
    private ResourceKey<Level> presenceDimension;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int nextRequestId;
    private int scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    private int orderedOffsetDistance = -1;
    private long[] orderedOffsets = new long[0];
    private int scanOffsetIndex;
    private int nearScanOffsetIndex;
    private int scanBoostTicks;
    private int softFrontierRadius;
    private int lastEffectiveLodDistance = -1;
    private boolean scanCompletedForCurrentOffsets;
    private boolean nearScanCompletedForCurrentOffsets;
    private double generationRequestBudget;
    private double dirtyRefreshBudget;
    private long lastRequestDiagnosticNanos;
    private int presenceCenterRegionX = Integer.MIN_VALUE;
    private int presenceCenterRegionZ = Integer.MIN_VALUE;
    private int presenceMaxRegionRing = -1;
    private boolean presenceResetPending = true;

    private static class RequestBuffers {
        final int[] requestIds = new int[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final long[] positions = new long[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final long[] timestamps = new long[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final boolean[] allowGeneration = new boolean[VSSConstants.MAX_BATCH_CHUNK_REQUESTS];
    }

    private final RequestBuffers requestBuffers = new RequestBuffers();

    public LodRequestManager() {
        this(ClientLodPresenceCache.currentScope());
    }

    public LodRequestManager(String presenceScope) {
        this.presenceScope = presenceScope;
        columnTimestamps.defaultReturnValue(-1L);
        positionToRequestId.defaultReturnValue(-1);
        requestIdToPosition.defaultReturnValue(Long.MIN_VALUE);
        requestSendTimes.defaultReturnValue(0L);
        retryAfterNanos.defaultReturnValue(0L);
        retryAttempts.defaultReturnValue(0);
        dirtyColumnTimestamps.defaultReturnValue(0L);
        delayedPresenceRegionDeadlines.defaultReturnValue(0L);
        generationRequestBudget = 8.0;
        dirtyRefreshBudget = 16.0;
    }

    public boolean onSessionConfig(SessionConfigS2CPayload config) {
        SessionConfigS2CPayload previousConfig = sessionConfig;
        boolean shouldReset = shouldResetForSessionConfig(config);
        sessionConfig = config;
        if (shouldReset) {
            resetRequestStateAfterConfigChange();
            primeRequestBudgets();
            return true;
        }

        clampRequestBudgets();
        primeRequestBudgets();
        if (previousConfig != null && previousConfig.generationEnabled() != config.generationEnabled()) {
            if (config.generationEnabled()) {
                resumeGenerationCandidatesNearPlayer();
            } else {
                suspendGenerationCandidates();
            }
            scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        }
        return shouldReset;
    }

    public void tick() {
        if (sessionConfig == null || !sessionConfig.enabled()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || player.isRemoved()) {
            return;
        }

        if (lastDimension != null && !lastDimension.equals(level.dimension())) {
            resetRequestState();
        }
        lastDimension = level.dimension();
        int playerBlockX = player.getBlockX();
        int playerBlockZ = player.getBlockZ();
        int playerCx = playerBlockX >> 4;
        int playerCz = playerBlockZ >> 4;
        int lodDistance = getEffectiveLodDistance();
        handleEffectiveLodDistance(playerCx, playerCz, lodDistance);
        if (playerCx != lastPlayerChunkX || playerCz != lastPlayerChunkZ) {
            boolean firstKnownPosition = lastPlayerChunkX == Integer.MIN_VALUE;
            int moveDistance = firstKnownPosition
                    ? 0
                    : Math.max(Math.abs(playerCx - lastPlayerChunkX), Math.abs(playerCz - lastPlayerChunkZ));

            boolean isTeleport = !firstKnownPosition && moveDistance >= lodDistance / 2;

            if (isTeleport) {
                resetRequestState();
                primeRequestBudgets();
                armScanBoost();
            } else {
                pruneAround(playerCx, playerCz, lodDistance + VSSConstants.LOD_DISTANCE_BUFFER);
                if (moveDistance >= FAST_MOVE_CHUNK_THRESHOLD) {
                    pruneLowPriorityRequestsAround(playerCx, playerCz,
                            Math.min(lodDistance, FAST_MOVE_KEEP_RADIUS_CHUNKS));
                }
            }
            if (firstKnownPosition || isTeleport) {
                resetScanCursorPastProtectedSyncWindow(lodDistance);
            } else if (moveDistance > 0) {
                rebaseScanCursorAfterMove(lodDistance, moveDistance);
                armScanBoost();
            }
            lastPlayerChunkX = playerCx;
            lastPlayerChunkZ = playerCz;
        }
        updatePresenceSummaryWindow(level, level.dimension(), playerCx, playerCz, lodDistance);
        drainPresenceSummaries(level, level.dimension());
        timeoutSweep();

        if (++scanTickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        scanTickCounter = 0;
        scanAndSend(level, player);
    }

    public ColumnReceiveResult onColumnReceived(int requestId, long columnTimestamp) {
        boolean dirtyRefreshRequest = isDirtyRefreshRequest(requestId);
        long packed = removeRequest(requestId);
        if (packed == Long.MIN_VALUE) {
            return new ColumnReceiveResult(false, false, Long.MIN_VALUE);
        }

        long requiredTimestamp = dirtyColumnTimestamps.get(packed);
        columnTimestamps.put(packed, columnTimestamp);
        rememberKnownColumn(lastDimension, packed, columnTimestamp);
        diskMissedColumns.remove(packed);
        if (requiredTimestamp > 0L && columnTimestamp < requiredTimestamp) {
            dirtyColumns.add(packed);
            deferredColumns.remove(packed);
            deferColumn(packed, true);
        } else {
            dirtyColumns.remove(packed);
            dirtyColumnTimestamps.remove(packed);
            clearBackoff(packed);
        }
        return new ColumnReceiveResult(true, dirtyRefreshRequest, packed);
    }

    public void onPushedColumnReceived(ResourceKey<Level> dimension, int cx, int cz, long columnTimestamp) {
        if (!isActiveDimension(dimension)) {
            return;
        }
        long packed = PositionUtil.packPosition(cx, cz);
        long requiredTimestamp = dirtyColumnTimestamps.get(packed);
        columnTimestamps.put(packed, columnTimestamp);
        rememberKnownColumn(dimension, packed, columnTimestamp);
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

    public void onColumnProcessingFailed(ResourceKey<Level> dimension, int cx, int cz) {
        forgetColumn(dimension, cx, cz, true);
    }

    public void onClientChunkDropped(ResourceKey<Level> dimension, int cx, int cz) {
        forgetColumn(dimension, cx, cz, false);
    }

    public void onDirtyColumns(long[] dirtyPositions, long[] dirtyTimestamps) {
        for (int i = 0; i < dirtyPositions.length; i++) {
            long packed = dirtyPositions[i];
            if (hasKnownColumn(packed)) {
                long dirtyTimestamp = i < dirtyTimestamps.length ? dirtyTimestamps[i] : VSSConstants.epochMillis();
                long existingTimestamp = dirtyColumnTimestamps.get(packed);
                if (dirtyTimestamp > existingTimestamp) {
                    dirtyColumnTimestamps.put(packed, dirtyTimestamp);
                }
                clearBackoff(packed);
                dirtyColumns.add(packed);
                deferColumn(packed, true);
            } else {
                dirtyColumnTimestamps.remove(packed);
                dirtyColumns.remove(packed);
            }
        }
    }

    public void onColumnNotGenerated(int requestId) {
        boolean dirtyRefreshRequest = isDirtyRefreshRequest(requestId);
        boolean generationRequest = isGenerationRequest(requestId);
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
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

            dirtyColumns.remove(packed);
            if (generationRequest) {
                columnTimestamps.remove(packed);
                diskMissedColumns.add(packed);
                markBackoff(packed, true);
                deferredColumns.remove(packed);
                deferColumn(packed);
            } else if (shouldPromoteNotGeneratedToGeneration(packed)) {
                columnTimestamps.remove(packed);
                diskMissedColumns.add(packed);
                clearBackoff(packed);
                deferredColumns.remove(packed);
                deferColumn(packed);
            } else if (sessionConfig != null && sessionConfig.generationEnabled()) {
                columnTimestamps.remove(packed);
                clearMissState(packed);
                deferredColumns.remove(packed);
            } else {
                columnTimestamps.put(packed, 0L);
                diskMissedColumns.add(packed);
                clearBackoff(packed);
                deferredColumns.remove(packed);
            }
        }
    }

    public void onColumnUpToDate(int requestId) {
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            long requiredTimestamp = dirtyColumnTimestamps.get(packed);
            long localTimestamp = columnTimestamps.get(packed);
            if (localTimestamp <= 0L) {
                dirtyColumns.remove(packed);
                dirtyColumnTimestamps.remove(packed);
                deferredColumns.remove(packed);
                if (sessionConfig != null && sessionConfig.generationEnabled()) {
                    columnTimestamps.remove(packed);
                    diskMissedColumns.add(packed);
                    clearBackoff(packed);
                    deferColumn(packed);
                } else {
                    columnTimestamps.put(packed, 0L);
                    diskMissedColumns.add(packed);
                    clearBackoff(packed);
                }
                return;
            }

            boolean dirtySatisfied = requiredTimestamp <= 0L || localTimestamp >= requiredTimestamp;
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

    public void onRateLimited(int requestId) {
        boolean generationRequest = isGenerationRequest(requestId);
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            markBackoff(packed, generationRequest || isGenerationCandidate(packed));
            deferredColumns.remove(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    public void onBackpressured(int requestId) {
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            markBackpressure(packed);
            deferredColumns.remove(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    public void disconnect() {
        resetRequestState();
        ClientLodPresenceCache.flush();
    }

    public void forceResync() {
        resetRequestStateAfterConfigChange();
    }

    public int getPendingCount() {
        return inFlight.size();
    }

    private boolean shouldResetForSessionConfig(SessionConfigS2CPayload config) {
        if (sessionConfig == null) {
            return true;
        }
        return sessionConfig.enabled() != config.enabled()
                || sessionConfig.serverCapabilities() != config.serverCapabilities();
    }

    private void resetRequestStateAfterConfigChange() {
        int preservedNextRequestId = nextRequestId;
        for (int requestId : requestIdToPosition.keySet()) {
            sendCancelPacket(requestId);
        }
        resetRequestState();
        nextRequestId = preservedNextRequestId;
    }

    private void primeRequestBudgets() {
        if (sessionConfig == null) {
            return;
        }
        int generationRate = Math.max(1, sessionConfig.generationRateLimitPerPlayer());
        generationRequestBudget = sessionConfig.generationEnabled()
                ? Math.max(generationRequestBudget, generationRate)
                : 0.0D;
        dirtyRefreshBudget = Math.max(dirtyRefreshBudget, DIRTY_REFRESH_RATE_LIMIT);
        clampRequestBudgets();
    }

    private void clampRequestBudgets() {
        if (sessionConfig == null) {
            generationRequestBudget = 0.0D;
            dirtyRefreshBudget = 0.0D;
            return;
        }
        generationRequestBudget = sessionConfig.generationEnabled()
                ? Math.min(generationRequestBudget, Math.max(1, sessionConfig.generationRateLimitPerPlayer()))
                : 0.0D;
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
        LongOpenHashSet cancelled = new LongOpenHashSet(generationInFlight);
        for (long packed : cancelled) {
            cancelInFlightColumn(packed);
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
            deferredQueue.removeIf(packed -> !deferredColumns.contains(packed.longValue()));
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
        LongOpenHashSet candidates = new LongOpenHashSet(diskMissedColumns);
        int resumed = 0;

        for (long packed : candidates) {
            if (resumed >= MAX_DEFERRED_COLUMNS) {
                break;
            }
            if (inFlight.contains(packed) || dirtyColumns.contains(packed)) {
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
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int lodDistance = getEffectiveLodDistance();
        RequestWindow requestWindow = createRequestWindow();
        if (!requestWindow.hasCapacity()) {
            return;
        }

        int maxCount = Math.min(VSSConstants.MAX_BATCH_CHUNK_REQUESTS, requestWindow.remaining());
        int[] requestIds = requestBuffers.requestIds;
        long[] positions = requestBuffers.positions;
        long[] timestamps = requestBuffers.timestamps;
        boolean[] allowGeneration = requestBuffers.allowGeneration;
        int count = 0;

        if (lodDistance <= 0) {
            return;
        }

        ensureOrderedOffsets(lodDistance);
        int protectedSyncDistance = getVanillaProtectedSyncDistance();
        long now = System.nanoTime();
        int maxAllowedRingThisTick = lodDistance;

        count = scanNearSyncColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions,
                timestamps, allowGeneration, count, maxCount, requestWindow, now);
        count = drainDeferredColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions,
                timestamps, allowGeneration, count, maxCount, requestWindow, now, maxAllowedRingThisTick);
        count = scanNewSyncColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions, timestamps,
                allowGeneration, count, maxCount, requestWindow, now, maxAllowedRingThisTick);

        if (count > 0) {
            generationRequestBudget = Math.max(0.0D, generationRequestBudget - requestWindow.generationSent);
            dirtyRefreshBudget = Math.max(0.0D, dirtyRefreshBudget - requestWindow.dirtySent);
            VSSClientNetworking.sendBatchRequest(new BatchChunkRequestC2SPayload(requestIds, positions, timestamps, allowGeneration, count));
            logRequestBatch(now, count, requestWindow.syncSent, requestWindow.generationSent, requestWindow.dirtySent, lodDistance, playerCx, playerCz);
        }
    }

    private RequestWindow createRequestWindow() {
        int dirtyInFlightCount = dirtyRefreshInFlight.size();
        int generationInFlightCount = generationInFlight.size();

        int generationConcurrencyLimit = sessionConfig.generationEnabled()
                ? Math.max(1, sessionConfig.generationConcurrencyLimitPerPlayer())
                : 0;

        int generationSlots = Math.max(0, generationConcurrencyLimit - generationInFlightCount);
        int dirtySlots = Math.max(0, DIRTY_REFRESH_CONCURRENCY_LIMIT - dirtyInFlightCount);

        int generationRate = Math.max(1, sessionConfig.generationRateLimitPerPlayer());
        generationRequestBudget = Math.min(generationRate, generationRequestBudget + generationRate / 20.0D);
        dirtyRefreshBudget = Math.min(DIRTY_REFRESH_RATE_LIMIT, dirtyRefreshBudget + DIRTY_REFRESH_RATE_LIMIT / 20.0D);

        return new RequestWindow(
                syncBucketLimit(sessionConfig.nearSyncRateLimitPerTick(), true),
                syncBucketLimit(sessionConfig.midSyncRateLimitPerTick(), false),
                syncBucketLimit(sessionConfig.farSyncRateLimitPerTick(), false),
                syncBucketLimit(sessionConfig.distantSyncRateLimitPerTick(), false),
                Math.min(generationSlots, (int) generationRequestBudget),
                Math.min(dirtySlots, (int) dirtyRefreshBudget));
    }

    private static int syncBucketLimit(int configuredLimit, boolean zeroMeansUnlimited) {
        if (configuredLimit <= 0) {
            return zeroMeansUnlimited ? VSSConstants.MAX_BATCH_CHUNK_REQUESTS : 0;
        }
        return Math.min(configuredLimit, VSSConstants.MAX_BATCH_CHUNK_REQUESTS);
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
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now) {
        if (nearScanCompletedForCurrentOffsets
                || orderedOffsets.length == 0
                || !requestWindow.hasNearSyncCapacity()
                || lodDistance <= 0) {
            return count;
        }

        int maxAllowedRing = Math.min(lodDistance, VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS);
        int scannedCandidates = 0;
        int totalCandidates = orderedOffsets.length;
        int scanLimit = scanBoostTicks > 0 ? BOOSTED_SCAN_CANDIDATES_PER_TICK : MAX_SCAN_CANDIDATES_PER_TICK;
        int maxScans = Math.min(scanLimit, totalCandidates);
        while (count < maxCount
                && requestWindow.hasNearSyncCapacity()
                && scannedCandidates < maxScans) {
            if (nearScanOffsetIndex >= totalCandidates) {
                nearScanCompletedForCurrentOffsets = true;
                return count;
            }

            long offset = orderedOffsets[nearScanOffsetIndex];
            int offsetRing = offsetRing(offset);
            if (offsetRing > maxAllowedRing) {
                nearScanCompletedForCurrentOffsets = true;
                return count;
            }

            nearScanOffsetIndex++;
            scannedCandidates++;
            updateSoftFrontier(offsetRing, lodDistance);

            int cx = playerCx + decodeOffsetX(offset);
            int cz = playerCz + decodeOffsetZ(offset);
            long packed = PositionUtil.packPosition(cx, cz);
            if (isInsideProtectedSyncWindow(packed, playerCx, playerCz, protectedSyncDistance)
                    || dirtyColumns.contains(packed)
                    || requeueGenerationCandidate(packed)
                    || !shouldRequestColumn(packed, now)) {
                continue;
            }

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
                    count,
                    maxCount,
                    requestWindow,
                    now,
                    maxAllowedRing);
        }
        return count;
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
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            int maxAllowedRing) {
        int attempts = Math.min(deferredQueue.size(), MAX_DEFERRED_CANDIDATES_PER_TICK);
        if (attempts <= 0) {
            return count;
        }

        List<Long> candidates = new ArrayList<>(attempts);
        LongOpenHashSet seen = new LongOpenHashSet();
        while (attempts-- > 0 && !deferredQueue.isEmpty()) {
            Long queued = deferredQueue.pollFirst();
            if (queued == null) {
                break;
            }
            long packed = queued.longValue();
            if (!deferredColumns.contains(packed) || !seen.add(packed)) {
                continue;
            }
            candidates.add(packed);
        }

        candidates.sort(deferredColumnComparator(playerCx, playerCz));

        int firstNormalDeferredRing = -1;

        // 追踪当前处理的最远距离，防止跳跃式处理
        int maxProcessedRing = 0;

        for (long packed : candidates) {
            boolean dirtyRefresh = dirtyColumns.contains(packed);
            if (count >= maxCount || !requestWindow.hasCapacity()) {
                requeueDeferredColumn(packed, dirtyRefresh);
                continue;
            }
            if (!deferredColumns.remove(packed)) {
                continue;
            }

            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            int ring = PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz);

            if (ring > lodDistance) {
                if (!dirtyRefresh) {
                    clearMissState(packed);
                }
                continue;
            }

            // 全局环限制：脏刷新也应该有适度限制，避免跳跃过大
            if (ring > maxAllowedRing) {
                // 脏刷新允许+10环的容差，但不是完全绕过
                if (!dirtyRefresh || ring > maxAllowedRing + 10) {
                    requeueDeferredColumn(packed, dirtyRefresh);
                    continue;
                }
            }

            // 对于非脏刷新的普通请求，严格按环顺序处理，防止跳跃
            // 脏刷新（dirtyRefresh）可以跳过此限制，因为需要立即更新
            if (!dirtyRefresh) {
                if (firstNormalDeferredRing < 0) {
                    firstNormalDeferredRing = ring;
                } else if (ring > firstNormalDeferredRing + 2) {
                    requeueDeferredColumn(packed, false);
                    continue;
                }
            }

            if (isCoolingDown(packed, now)) {
                requeueDeferredColumn(packed, dirtyRefresh);
                continue;
            }
            if (!shouldRequestColumn(packed, now)) {
                continue;
            }

            boolean generationCandidate = !dirtyRefresh && isGenerationCandidate(packed);
            if (!dirtyRefresh
                    && !generationCandidate
                    && isInsideProtectedSyncWindow(packed, playerCx, playerCz, protectedSyncDistance)) {
                continue;
            }
            if (!dirtyRefresh && !isWithinSoftFrontier(packed, playerCx, playerCz)) {
                requeueDeferredColumn(packed, false);
                continue;
            }
            if (!requestWindow.canSend(dirtyRefresh, generationCandidate, ring)) {
                requeueDeferredColumn(packed, dirtyRefresh);
                continue;
            }

            count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, generationCandidate);
            requestWindow.record(dirtyRefresh, generationCandidate, ring);

            // 更新已处理的最远环
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
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            int maxAllowedRing) {
        if (!requestWindow.hasAnySyncCapacity() || orderedOffsets.length == 0) {
            return count;
        }
        if (scanCompletedForCurrentOffsets) {
            return count;
        }

        int scannedCandidates = 0;
        int totalCandidates = orderedOffsets.length;
        int scanLimit = scanBoostTicks > 0 ? BOOSTED_SCAN_CANDIDATES_PER_TICK : MAX_SCAN_CANDIDATES_PER_TICK;
        int maxScans = Math.min(scanLimit, totalCandidates);
        if (scanBoostTicks > 0) {
            scanBoostTicks--;
        }

        // 记录本次tick开始时的环，防止跳跃式请求远处区块
        int startingRing = scanOffsetIndex < totalCandidates
            ? offsetRing(orderedOffsets[scanOffsetIndex])
            : Integer.MAX_VALUE;
        int currentRing = startingRing;
        int requestsInCurrentBatch = 0;

        while (count < maxCount
                && requestWindow.hasAnySyncCapacity()
                && scannedCandidates < maxScans) {
            if (scanOffsetIndex >= totalCandidates) {
                scanCompletedForCurrentOffsets = true;
                softFrontierRadius = lodDistance;
                return count;
            }

            long offset = orderedOffsets[scanOffsetIndex];
            int offsetRing = offsetRing(offset);

            // 全局环限制检查
            if (offsetRing > maxAllowedRing) {
                break;
            }
            if (!requestWindow.hasSyncCapacity(offsetRing)) {
                break;
            }

            // 防止在单个tick内跳过多个环，确保由内向外渐进加载
            // 如果已经跳到了新的环，并且这个环比起始环远了2个以上，就停止本次扫描
            if (offsetRing > startingRing + 1 && requestsInCurrentBatch > 0) {
                break;
            }

            scanOffsetIndex++;
            scannedCandidates++;
            currentRing = offsetRing;
            updateSoftFrontier(offsetRing, lodDistance);

            int cx = playerCx + decodeOffsetX(offset);
            int cz = playerCz + decodeOffsetZ(offset);
            long packed = PositionUtil.packPosition(cx, cz);
            if (isInsideProtectedSyncWindow(packed, playerCx, playerCz, protectedSyncDistance)
                    || dirtyColumns.contains(packed)
                    || requeueGenerationCandidate(packed)
                    || !shouldRequestColumn(packed, now)) {
                continue;
            }

            int beforeCount = count;
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
                    count,
                    maxCount,
                    requestWindow,
                    now,
                    maxAllowedRing);

            // 记录本批次实际发送的请求数
            if (count > beforeCount) {
                requestsInCurrentBatch += (count - beforeCount);
            }
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
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            int maxAllowedRing) {
        int centerX = PositionUtil.unpackX(centerPacked);
        int centerZ = PositionUtil.unpackZ(centerPacked);
        int centerRing = chebyshevDistance(centerPacked, playerCx, playerCz);

        // 全局环限制检查
        if (centerRing > maxAllowedRing) {
            return count;
        }

        // 首先添加中心区块
        count = appendClusterCandidate(centerPacked, playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds,
                positions, timestamps, allowGeneration, count, maxCount, requestWindow, now, centerRing);

        // 对于周围的区块，只添加距离不超过中心区块的
        // 防止集群请求导致远处区块越过近处区块被优先请求
        for (int dz = -1; dz <= 1 && count < maxCount && requestWindow.hasSyncCapacity(centerRing); dz++) {
            for (int dx = -1; dx <= 1 && count < maxCount && requestWindow.hasSyncCapacity(centerRing); dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long packed = PositionUtil.packPosition(centerX + dx, centerZ + dz);
                int neighborRing = chebyshevDistance(packed, playerCx, playerCz);

                // 只请求距离不超过中心区块且不超过全局限制的邻居，避免跳跃式加载
                if (neighborRing <= centerRing && neighborRing <= maxAllowedRing) {
                    count = appendClusterCandidate(packed, playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds,
                            positions, timestamps, allowGeneration, count, maxCount, requestWindow, now, centerRing);
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
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now,
            int maxClusterRing) {
        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        int ring = PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz);
        if (count >= maxCount || !requestWindow.hasSyncCapacity(ring)) {
            return count;
        }
        if (ring > lodDistance
                || (protectedSyncDistance > 0 && ring <= protectedSyncDistance)
                || ring > maxClusterRing
                || !shouldRequestColumn(packed, now)) {
            return count;
        }

        if (dirtyColumns.contains(packed) || requeueGenerationCandidate(packed) || !requestWindow.canSend(false, false, ring)) {
            return count;
        }

        count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, false);
        requestWindow.record(false, false, ring);
        return count;
    }

    private boolean shouldRequestColumn(long packed, long now) {
        boolean dirty = dirtyColumns.contains(packed);
        if (inFlight.contains(packed)) {
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

    private void forgetColumn(ResourceKey<Level> dimension, int cx, int cz, boolean applyBackoff) {
        if (sessionConfig == null || !sessionConfig.enabled() || !isActiveDimension(dimension)) {
            return;
        }

        long packed = PositionUtil.packPosition(cx, cz);
        if (applyBackoff) {
            ClientLodPresenceCache.removeColumn(presenceScope, dimension, packed);
        }
        columnTimestamps.remove(packed);
        dirtyColumns.remove(packed);
        dirtyColumnTimestamps.remove(packed);
        diskMissedColumns.remove(packed);
        if (applyBackoff) {
            markBackoff(packed, false);
        } else {
            clearBackoff(packed);
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || player.isRemoved() || !level.dimension().equals(dimension)) {
            return;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= getEffectiveLodDistance()) {
            deferColumn(packed, true);
        }
    }

    private boolean isActiveDimension(ResourceKey<Level> dimension) {
        if (lastDimension != null) {
            return lastDimension.equals(dimension);
        }
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(dimension);
    }

    private void rememberKnownColumn(ResourceKey<Level> dimension, long packed, long columnTimestamp) {
        if (dimension == null || columnTimestamp <= 0L) {
            return;
        }
        ClientLodPresenceCache.recordColumn(presenceScope, dimension, packed, columnTimestamp);
        queuePresenceRegionForColumn(dimension, packed);
    }

    private void updatePresenceSummaryWindow(ClientLevel level, ResourceKey<Level> dimension, int playerCx, int playerCz, int lodDistance) {
        if (sessionConfig == null || !sessionConfig.enabled() || lodDistance <= 0) {
            return;
        }
        int regionSize = RegionPresenceC2SPayload.REGION_SIZE;
        int centerRegionX = Math.floorDiv(playerCx, regionSize);
        int centerRegionZ = Math.floorDiv(playerCz, regionSize);
        int maxRegionRing = Math.max(0, (lodDistance + regionSize - 1) / regionSize);
        boolean reset = presenceDimension == null
                || !presenceDimension.equals(dimension)
                || presenceMaxRegionRing != maxRegionRing;
        if (reset) {
            resetPresenceState(dimension);
            presenceMaxRegionRing = maxRegionRing;
        }
        if (!reset
                && centerRegionX == presenceCenterRegionX
                && centerRegionZ == presenceCenterRegionZ) {
            return;
        }

        presenceDimension = dimension;
        presenceCenterRegionX = centerRegionX;
        presenceCenterRegionZ = centerRegionZ;
        presenceMaxRegionRing = maxRegionRing;

        for (int ring = 0; ring <= maxRegionRing; ring++) {
            queuePresenceRegionRing(level, dimension, centerRegionX, centerRegionZ, ring);
        }
    }

    private void queuePresenceRegionRing(ClientLevel level, ResourceKey<Level> dimension, int centerRegionX, int centerRegionZ, int ring) {
        if (ring == 0) {
            seedAndQueuePresenceRegion(level, dimension, centerRegionX, centerRegionZ);
            return;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            seedAndQueuePresenceRegion(level, dimension, centerRegionX + dx, centerRegionZ - ring);
            seedAndQueuePresenceRegion(level, dimension, centerRegionX + dx, centerRegionZ + ring);
        }
        for (int dz = -ring + 1; dz <= ring - 1; dz++) {
            seedAndQueuePresenceRegion(level, dimension, centerRegionX - ring, centerRegionZ + dz);
            seedAndQueuePresenceRegion(level, dimension, centerRegionX + ring, centerRegionZ + dz);
        }
    }

    private void seedAndQueuePresenceRegion(ClientLevel level, ResourceKey<Level> dimension, int regionX, int regionZ) {
        ClientLodPresenceCache.seedRegion(presenceScope, dimension, regionX, regionZ, columnTimestamps, level);
        queuePresenceRegion(regionX, regionZ, false);
    }

    private void queuePresenceRegionForColumn(ResourceKey<Level> dimension, long packed) {
        if (presenceDimension == null || !presenceDimension.equals(dimension)) {
            return;
        }
        int regionX = Math.floorDiv(PositionUtil.unpackX(packed), RegionPresenceC2SPayload.REGION_SIZE);
        int regionZ = Math.floorDiv(PositionUtil.unpackZ(packed), RegionPresenceC2SPayload.REGION_SIZE);
        queueIncrementalPresenceRegion(regionX, regionZ);
    }

    private void queueIncrementalPresenceRegion(int regionX, int regionZ) {
        long key = ClientLodPresenceCache.regionKey(regionX, regionZ);
        if (!sentPresenceRegions.contains(key)) {
            queuePresenceRegion(regionX, regionZ, false);
            return;
        }
        if (queuedPresenceRegions.contains(key) || delayedPresenceRegionDeadlines.containsKey(key)) {
            return;
        }
        delayedPresenceRegionDeadlines.put(key, System.nanoTime() + PRESENCE_INCREMENTAL_RESEND_INTERVAL_NANOS);
        delayedPresenceRegions.add(key);
    }

    private void queuePresenceRegion(int regionX, int regionZ, boolean forceResend) {
        long key = ClientLodPresenceCache.regionKey(regionX, regionZ);
        if (forceResend) {
            sentPresenceRegions.remove(key);
        }
        if (sentPresenceRegions.contains(key) || !queuedPresenceRegions.add(key)) {
            return;
        }
        pendingPresenceRegions.add(key);
    }

    private void drainPresenceSummaries(ClientLevel level, ResourceKey<Level> dimension) {
        if (sessionConfig == null) {
            return;
        }
        queueDueIncrementalPresenceRegions();
        boolean allowZstd = (sessionConfig.serverCapabilities() & VSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0;
        if (pendingPresenceRegions.isEmpty()) {
            if (presenceResetPending) {
                sendPresencePayload(dimension, true, new ArrayList<>(), allowZstd);
                presenceResetPending = false;
            }
            return;
        }
        int packetsSent = 0;
        while (packetsSent < PRESENCE_PACKETS_PER_TICK && !pendingPresenceRegions.isEmpty()) {
            ArrayList<RegionPresenceC2SPayload.RegionEntry> entries = new ArrayList<>();
            int consumedRegions = 0;
            int columnCount = 0;
            while (consumedRegions < PRESENCE_REGIONS_PER_PACKET
                    && columnCount < PRESENCE_COLUMNS_PER_PACKET
                    && !pendingPresenceRegions.isEmpty()) {
                long key = pendingPresenceRegions.pollFirst();
                queuedPresenceRegions.remove(key);
                sentPresenceRegions.add(key);
                consumedRegions++;
                int regionX = ClientLodPresenceCache.regionKeyX(key);
                int regionZ = ClientLodPresenceCache.regionKeyZ(key);
                RegionPresenceC2SPayload.RegionEntry entry = ClientLodPresenceCache.regionEntry(
                        presenceScope,
                        dimension,
                        regionX,
                        regionZ,
                        level);
                if (entry == null || entry.count() <= 0) {
                    continue;
                }
                entries.add(entry);
                columnCount += entry.count();
            }

            if (entries.isEmpty()) {
                if (presenceResetPending && pendingPresenceRegions.isEmpty()) {
                    sendPresencePayload(dimension, true, entries, allowZstd);
                    presenceResetPending = false;
                    packetsSent++;
                }
                continue;
            }

            boolean reset = presenceResetPending;
            presenceResetPending = false;
            sendPresencePayload(dimension, reset, entries, allowZstd);
            packetsSent++;
        }
    }

    private void sendPresencePayload(
            ResourceKey<Level> dimension,
            boolean reset,
            ArrayList<RegionPresenceC2SPayload.RegionEntry> entries,
            boolean allowZstd) {
        try {
            VSSClientNetworking.sendRegionPresence(RegionPresenceC2SPayload.create(dimension, reset, entries, allowZstd));
        } catch (Exception e) {
            VSSLogger.debug("LOD presence summary send failed: " + e.getMessage());
        }
    }

    private void queueDueIncrementalPresenceRegions() {
        long now = System.nanoTime();
        int moved = 0;
        while (moved < PRESENCE_INCREMENTAL_RESENDS_PER_TICK && !delayedPresenceRegions.isEmpty()) {
            long key = delayedPresenceRegions.peekFirst();
            if (!delayedPresenceRegionDeadlines.containsKey(key)) {
                delayedPresenceRegions.pollFirst();
                continue;
            }
            long deadline = delayedPresenceRegionDeadlines.get(key);
            if (now - deadline < 0L) {
                break;
            }
            delayedPresenceRegions.pollFirst();
            delayedPresenceRegionDeadlines.remove(key);
            if (!sentPresenceRegions.contains(key) || queuedPresenceRegions.contains(key)) {
                continue;
            }
            int regionX = ClientLodPresenceCache.regionKeyX(key);
            int regionZ = ClientLodPresenceCache.regionKeyZ(key);
            if (!isPresenceRegionInWindow(regionX, regionZ)) {
                continue;
            }
            queuePresenceRegion(regionX, regionZ, true);
            moved++;
        }
    }

    private boolean isPresenceRegionInWindow(int regionX, int regionZ) {
        return presenceMaxRegionRing >= 0
                && Math.max(Math.abs(regionX - presenceCenterRegionX), Math.abs(regionZ - presenceCenterRegionZ)) <= presenceMaxRegionRing;
    }

    private void resetPresenceState(ResourceKey<Level> dimension) {
        presenceDimension = dimension;
        sentPresenceRegions.clear();
        queuedPresenceRegions.clear();
        pendingPresenceRegions.clear();
        delayedPresenceRegions.clear();
        delayedPresenceRegionDeadlines.clear();
        presenceCenterRegionX = Integer.MIN_VALUE;
        presenceCenterRegionZ = Integer.MIN_VALUE;
        presenceMaxRegionRing = -1;
        presenceResetPending = true;
    }

    private boolean isGenerationCandidate(long packed) {
        if (sessionConfig == null || !sessionConfig.generationEnabled()) {
            return false;
        }
        return diskMissedColumns.contains(packed);
    }

    private boolean requeueGenerationCandidate(long packed) {
        if (!isGenerationCandidate(packed)) {
            return false;
        }
        if (!inFlight.contains(packed) && !deferredColumns.contains(packed)) {
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
        if (sessionConfig == null || !sessionConfig.generationEnabled()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
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
            int count,
            boolean generationCandidate) {
        int requestId = nextRequestId++;
        requestIds[count] = requestId;
        positions[count] = packed;
        timestamps[count] = columnTimestamps.get(packed);
        allowGeneration[count] = generationCandidate;
        markRequest(packed, requestId, generationCandidate);
        return count + 1;
    }

    private int getEffectiveLodDistance() {
        int serverDistance = sessionConfig.lodDistanceChunks();
        int clientDistance = VSSClientConfig.CONFIG.lodDistanceChunks;
        int hardClientLimit = VSSClientConfig.MAX_LOD_DISTANCE_CHUNKS;
        if (clientDistance > 0) {
            return Math.min(Math.min(clientDistance, serverDistance), hardClientLimit);
        }
        int voxyDistance = ModCompat.getVoxyViewDistanceChunks().orElse(hardClientLimit);
        return Math.min(Math.min(serverDistance, voxyDistance), hardClientLimit);
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
                + ", sync=" + syncCount
                + ", generation=" + generationCount
                + ", dirty=" + dirtyCount
                + ", distance=" + lodDistance
                + ", pending=" + inFlight.size()
                + ", deferred=" + deferredColumns.size()
                + ", playerChunk=" + playerCx + "," + playerCz);
    }

    private void markRequest(long packed, int requestId, boolean generationCandidate) {
        inFlight.add(packed);
        if (generationCandidate) {
            generationInFlight.add(packed);
        }
        if (dirtyColumns.contains(packed)) {
            dirtyRefreshInFlight.add(packed);
        }
        positionToRequestId.put(packed, requestId);
        requestIdToPosition.put(requestId, packed);
        long now = System.nanoTime();
        requestSendTimes.put(packed, now);
        requestDeadlines.add(new RequestDeadline(packed, requestId, now + timeoutFor(packed)));
    }

    private long removeRequest(int requestId) {
        long packed = requestIdToPosition.remove(requestId);
        if (packed == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        positionToRequestId.remove(packed);
        requestSendTimes.remove(packed);
        inFlight.remove(packed);
        generationInFlight.remove(packed);
        dirtyRefreshInFlight.remove(packed);
        return packed;
    }

    private boolean isDirtyRefreshRequest(int requestId) {
        long packed = requestIdToPosition.get(requestId);
        return packed != Long.MIN_VALUE && dirtyRefreshInFlight.contains(packed);
    }

    private boolean isGenerationRequest(int requestId) {
        long packed = requestIdToPosition.get(requestId);
        return packed != Long.MIN_VALUE && generationInFlight.contains(packed);
    }

    private void timeoutSweep() {
        long now = System.nanoTime();
        while (!requestDeadlines.isEmpty() && requestDeadlines.peek().isDue(now)) {
            RequestDeadline deadline = requestDeadlines.poll();
            long packed = deadline.packed();
            if (positionToRequestId.get(packed) != deadline.requestId()
                    || requestIdToPosition.get(deadline.requestId()) != packed
                    || !requestSendTimes.containsKey(packed)) {
                continue;
            }
            boolean generationCandidate = generationInFlight.contains(packed)
                    || (isGenerationCandidate(packed) && !dirtyColumns.contains(packed));
            int requestId = positionToRequestId.remove(packed);
            if (requestId != -1) {
                requestIdToPosition.remove(requestId);
                sendCancelPacket(requestId);
            }
            requestSendTimes.remove(packed);
            inFlight.remove(packed);
            generationInFlight.remove(packed);
            dirtyRefreshInFlight.remove(packed);
            markBackoff(packed, generationCandidate);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    private void cancelInFlightColumn(long packed) {
        int requestId = positionToRequestId.remove(packed);
        if (requestId != -1) {
            requestIdToPosition.remove(requestId);
            sendCancelPacket(requestId);
        }
        requestSendTimes.remove(packed);
        inFlight.remove(packed);
        generationInFlight.remove(packed);
        dirtyRefreshInFlight.remove(packed);
    }

    private long timeoutFor(long packed) {
        if (sessionConfig != null
                && sessionConfig.generationEnabled()
                && isGenerationCandidate(packed)
                && !dirtyColumns.contains(packed)) {
            return GENERATION_REQUEST_TIMEOUT_NANOS;
        }
        return SYNC_REQUEST_TIMEOUT_NANOS;
    }

    private void pruneLowPriorityRequestsAround(int playerCx, int playerCz, int keepDistance) {
        LongOpenHashSet staleRequests = new LongOpenHashSet();
        for (long packed : inFlight) {
            if (!dirtyRefreshInFlight.contains(packed)
                    && chebyshevDistance(packed, playerCx, playerCz) > keepDistance) {
                staleRequests.add(packed);
            }
        }
        for (long packed : staleRequests) {
            int requestId = positionToRequestId.remove(packed);
            if (requestId != -1) {
                requestIdToPosition.remove(requestId);
                sendCancelPacket(requestId);
            }
            requestSendTimes.remove(packed);
            inFlight.remove(packed);
            generationInFlight.remove(packed);
            diskMissedColumns.remove(packed);
            retryAfterNanos.remove(packed);
            retryAttempts.remove(packed);
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
            retryAfterNanos.remove(packed);
            retryAttempts.remove(packed);
        }
        if (!staleDeferred.isEmpty()) {
            deferredQueue.removeIf(packed -> !deferredColumns.contains(packed.longValue()));
        }
    }

    private void pruneAround(int playerCx, int playerCz, int pruneDistance) {
        LongOpenHashSet staleColumns = new LongOpenHashSet();
        for (long packed : columnTimestamps.keySet()) {
            if (PositionUtil.isOutOfRange(packed, playerCx, playerCz, pruneDistance)) {
                staleColumns.add(packed);
            }
        }
        for (long packed : staleColumns) {
            columnTimestamps.remove(packed);
            dirtyColumns.remove(packed);
            dirtyColumnTimestamps.remove(packed);
            deferredColumns.remove(packed);
            diskMissedColumns.remove(packed);
            retryAfterNanos.remove(packed);
            retryAttempts.remove(packed);
        }

        LongOpenHashSet staleRequests = new LongOpenHashSet();
        for (long packed : inFlight) {
            if (PositionUtil.isOutOfRange(packed, playerCx, playerCz, pruneDistance)) {
                staleRequests.add(packed);
            }
        }
        for (long packed : staleRequests) {
            int requestId = positionToRequestId.remove(packed);
            if (requestId != -1) {
                requestIdToPosition.remove(requestId);
                sendCancelPacket(requestId);
            }
            requestSendTimes.remove(packed);
            inFlight.remove(packed);
            generationInFlight.remove(packed);
            dirtyRefreshInFlight.remove(packed);
            deferredColumns.remove(packed);
            clearMissState(packed);
        }
    }

    private void deferColumn(long packed) {
        deferColumn(packed, false);
    }

    private void deferColumn(long packed, boolean urgent) {
        boolean alreadyDeferred = deferredColumns.contains(packed);
        if (!alreadyDeferred && deferredColumns.size() >= MAX_DEFERRED_COLUMNS) {
            Long oldest = deferredQueue.pollFirst();
            if (oldest != null) {
                deferredColumns.remove(oldest.longValue());
            }
        }
        if (deferredColumns.add(packed) || urgent) {
            if (urgent) {
                deferredQueue.addFirst(packed);
                return;
            }
            deferredQueue.addLast(packed);
        }
    }

    private void requeueDeferredColumn(long packed, boolean urgent) {
        if (deferredColumns.size() >= MAX_DEFERRED_COLUMNS && !deferredColumns.contains(packed)) {
            Long oldest = deferredQueue.pollFirst();
            if (oldest != null) {
                deferredColumns.remove(oldest.longValue());
            }
        }
        deferredColumns.add(packed);
        if (urgent) {
            deferredQueue.addFirst(packed);
        } else {
            deferredQueue.addLast(packed);
        }
    }

    private Comparator<Long> deferredColumnComparator(int playerCx, int playerCz) {
        return (left, right) -> compareDeferredColumns(left, right, playerCx, playerCz);
    }

    private int compareDeferredColumns(long left, long right, int playerCx, int playerCz) {
        boolean leftDirty = dirtyColumns.contains(left);
        boolean rightDirty = dirtyColumns.contains(right);
        if (leftDirty != rightDirty) {
            return leftDirty ? -1 : 1;
        }

        int leftRing = chebyshevDistance(left, playerCx, playerCz);
        int rightRing = chebyshevDistance(right, playerCx, playerCz);
        if (leftRing != rightRing) {
            return Integer.compare(leftRing, rightRing);
        }

        long leftDistanceSquared = distanceSquared(left, playerCx, playerCz);
        long rightDistanceSquared = distanceSquared(right, playerCx, playerCz);
        if (leftDistanceSquared != rightDistanceSquared) {
            return Long.compare(leftDistanceSquared, rightDistanceSquared);
        }

        boolean leftGeneration = !leftDirty && isGenerationCandidate(left);
        boolean rightGeneration = !rightDirty && isGenerationCandidate(right);
        if (leftGeneration != rightGeneration) {
            return leftGeneration ? 1 : -1;
        }

        int leftX = PositionUtil.unpackX(left);
        int rightX = PositionUtil.unpackX(right);
        if (leftX != rightX) {
            return Integer.compare(leftX, rightX);
        }
        return Integer.compare(PositionUtil.unpackZ(left), PositionUtil.unpackZ(right));
    }

    private static long distanceSquared(long packed, int playerCx, int playerCz) {
        long dx = (long) PositionUtil.unpackX(packed) - playerCx;
        long dz = (long) PositionUtil.unpackZ(packed) - playerCz;
        return dx * dx + dz * dz;
    }

    private static int chebyshevDistance(long packed, int playerCx, int playerCz) {
        return PositionUtil.chebyshevDistance(PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed),
                playerCx, playerCz);
    }

    private void sendCancelPacket(int requestId) {
        try {
            VSSClientNetworking.sendCancelRequest(new CancelRequestC2SPayload(requestId));
        } catch (Exception ignored) {
        }
    }

    private void resetRequestState() {
        columnTimestamps.clear();
        dirtyColumns.clear();
        dirtyColumnTimestamps.clear();
        inFlight.clear();
        deferredColumns.clear();
        deferredQueue.clear();
        diskMissedColumns.clear();
        positionToRequestId.clear();
        requestIdToPosition.clear();
        requestSendTimes.clear();
        requestDeadlines.clear();
        generationInFlight.clear();
        dirtyRefreshInFlight.clear();
        retryAfterNanos.clear();
        retryAttempts.clear();
        nextRequestId = 0;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        scanOffsetIndex = 0;
        nearScanOffsetIndex = 0;
        softFrontierRadius = 0;
        lastEffectiveLodDistance = -1;
        scanCompletedForCurrentOffsets = false;
        nearScanCompletedForCurrentOffsets = false;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        generationRequestBudget = 0.0D;
        dirtyRefreshBudget = 0.0D;
        resetPresenceState(lastDimension);
        armScanBoost();
    }

    private void armScanBoost() {
        scanBoostTicks = SCAN_BOOST_TICKS;
    }

    private void resetScanCursor() {
        scanOffsetIndex = 0;
        resetNearScanCursor();
        scanCompletedForCurrentOffsets = false;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void resetNearScanCursor() {
        nearScanOffsetIndex = 0;
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

    private void rebaseScanCursorAfterMove(int lodDistance, int moveDistance) {
        ensureOrderedOffsets(lodDistance);
        int frontier = scanCompletedForCurrentOffsets ? lodDistance : softFrontierRadius;
        int trail = Math.max(MOVEMENT_RESCAN_TRAIL_CHUNKS, moveDistance + 2);
        resetNearScanCursor();
        setScanCursorAtRing(Math.max(0, Math.min(lodDistance, frontier) - trail));
    }

    private void setScanCursorAtRing(int ring) {
        if (orderedOffsets.length == 0 || ring <= 0) {
            resetScanCursor();
            return;
        }
        int index = 0;
        while (index < orderedOffsets.length && offsetRing(orderedOffsets[index]) < ring) {
            index++;
        }
        resetNearScanCursor();
        scanOffsetIndex = index;
        softFrontierRadius = Math.max(0, Math.min(ring - 1, orderedOffsetDistance));
        scanCompletedForCurrentOffsets = index >= orderedOffsets.length;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void ensureOrderedOffsets(int lodDistance) {
        if (orderedOffsetDistance == lodDistance) {
            return;
        }

        long[] cached = OFFSET_CACHE.get(lodDistance);
        if (cached != null) {
            orderedOffsets = cached;
            orderedOffsetDistance = lodDistance;
            resetScanCursor();
            return;
        }

        int side = lodDistance * 2 + 1;
        long[] offsets = new long[side * side];
        LongOpenHashSet seenOffsets = new LongOpenHashSet(offsets.length);
        int index = 0;
        index = appendOffset(offsets, seenOffsets, index, 0, 0);
        for (int ring = 1; ring <= lodDistance; ring++) {
            index = appendOffset(offsets, seenOffsets, index, -ring, 0);
            index = appendOffset(offsets, seenOffsets, index, ring, 0);
            index = appendOffset(offsets, seenOffsets, index, 0, -ring);
            index = appendOffset(offsets, seenOffsets, index, 0, ring);
            for (int step = 1; step <= ring; step++) {
                index = appendOffset(offsets, seenOffsets, index, -ring, -step);
                index = appendOffset(offsets, seenOffsets, index, -ring, step);
                index = appendOffset(offsets, seenOffsets, index, ring, -step);
                index = appendOffset(offsets, seenOffsets, index, ring, step);
                index = appendOffset(offsets, seenOffsets, index, -step, -ring);
                index = appendOffset(offsets, seenOffsets, index, step, -ring);
                index = appendOffset(offsets, seenOffsets, index, -step, ring);
                index = appendOffset(offsets, seenOffsets, index, step, ring);
            }
        }
        orderedOffsets = offsets;
        orderedOffsetDistance = lodDistance;
        resetScanCursor();
    }

    private static int appendOffset(long[] offsets, LongOpenHashSet seenOffsets, int index, int dx, int dz) {
        long offset = encodeOffset(dx, dz);
        if (seenOffsets.add(offset)) {
            offsets[index++] = offset;
        }
        return index;
    }

    private static int getVanillaProtectedSyncDistance() {
        return 0;
    }

    private static boolean isInsideProtectedSyncWindow(
            long packed,
            int playerCx,
            int playerCz,
            int protectedSyncDistance) {
        return protectedSyncDistance > 0 && chebyshevDistance(packed, playerCx, playerCz) <= protectedSyncDistance;
    }

    private boolean isWithinSoftFrontier(long packed, int playerCx, int playerCz) {
        return chebyshevDistance(packed, playerCx, playerCz) <= softFrontierRadius;
    }

    private void updateSoftFrontier(int ring, int lodDistance) {
        softFrontierRadius = Math.min(maxFrontierRadius(lodDistance), Math.max(softFrontierRadius, ring));
    }

    private static int maxFrontierRadius(int lodDistance) {
        return lodDistance;
    }

    private static long encodeOffset(int dx, int dz) {
        long distanceSquared = (long) dx * dx + (long) dz * dz;
        return distanceSquared << 32
                | (long) (dx & 0xFFFF) << 16
                | (long) (dz & 0xFFFF);
    }

    private static int decodeOffsetX(long offset) {
        return (short) ((offset >>> 16) & 0xFFFF);
    }

    private static int decodeOffsetZ(long offset) {
        return (short) (offset & 0xFFFF);
    }

    private static int offsetRing(long offset) {
        return Math.max(Math.abs(decodeOffsetX(offset)), Math.abs(decodeOffsetZ(offset)));
    }

    private record RequestDeadline(long packed, int requestId, long timeoutAtNanos) implements Comparable<RequestDeadline> {
        private boolean isDue(long nowNanos) {
            return timeoutAtNanos - nowNanos <= 0L;
        }

        @Override
        public int compareTo(RequestDeadline other) {
            return Long.compare(timeoutAtNanos, other.timeoutAtNanos);
        }
    }

    private boolean isCoolingDown(long packed, long now) {
        long retryAfter = retryAfterNanos.get(packed);
        if (retryAfter <= 0L) {
            return false;
        }
        if (retryAfter > now) {
            return true;
        }
        retryAfterNanos.remove(packed);
        return false;
    }

    private void markBackoff(long packed, boolean generationCandidate) {
        // 降低重试次数上限，避免长时间阻塞
        int attempts = Math.min(3, retryAttempts.addTo(packed, 1) + 1);
        long baseDelay = dirtyColumns.contains(packed)
                ? DIRTY_REFRESH_BACKOFF_NANOS      // 0.5秒
                : generationCandidate ? 5_000_000_000L : 1_000_000_000L; // 降低基础延迟：5秒/1秒
        // 降低最大延迟上限到10秒，避免永久卡住
        long delay = Math.min(10_000_000_000L, baseDelay << Math.max(0, attempts - 1));
        retryAfterNanos.put(packed, System.nanoTime() + delay);
    }

    private void markBackpressure(long packed) {
        retryAfterNanos.put(packed, System.nanoTime() + BACKPRESSURE_BACKOFF_NANOS);
    }

    private void clearBackoff(long packed) {
        retryAfterNanos.remove(packed);
        retryAttempts.remove(packed);
    }

    private static final class RequestWindow {
        private int nearSyncRemaining;
        private int midSyncRemaining;
        private int farSyncRemaining;
        private int distantSyncRemaining;
        private int generationRemaining;
        private int dirtyRemaining;
        private int syncSent;
        private int generationSent;
        private int dirtySent;

        private RequestWindow(
                int nearSyncRemaining,
                int midSyncRemaining,
                int farSyncRemaining,
                int distantSyncRemaining,
                int generationRemaining,
                int dirtyRemaining) {
            this.nearSyncRemaining = nearSyncRemaining;
            this.midSyncRemaining = midSyncRemaining;
            this.farSyncRemaining = farSyncRemaining;
            this.distantSyncRemaining = distantSyncRemaining;
            this.generationRemaining = generationRemaining;
            this.dirtyRemaining = dirtyRemaining;
        }

        private boolean hasCapacity() {
            return hasAnySyncCapacity() || generationRemaining > 0 || dirtyRemaining > 0;
        }

        private boolean hasAnySyncCapacity() {
            return nearSyncRemaining > 0 || midSyncRemaining > 0 || farSyncRemaining > 0 || distantSyncRemaining > 0;
        }

        private boolean hasNearSyncCapacity() {
            return nearSyncRemaining > 0;
        }

        private boolean hasSyncCapacity(int ring) {
            return syncRemainingForRing(ring) > 0;
        }

        private int remaining() {
            return Math.max(0, nearSyncRemaining)
                    + Math.max(0, midSyncRemaining)
                    + Math.max(0, farSyncRemaining)
                    + Math.max(0, distantSyncRemaining)
                    + Math.max(0, generationRemaining)
                    + Math.max(0, dirtyRemaining);
        }

        private boolean canSend(boolean dirtyRefresh, boolean generationCandidate, int ring) {
            if (dirtyRefresh) {
                return dirtyRemaining > 0;
            }
            return generationCandidate ? generationRemaining > 0 : hasSyncCapacity(ring);
        }

        private void record(boolean dirtyRefresh, boolean generationCandidate, int ring) {
            if (dirtyRefresh) {
                dirtyRemaining--;
                dirtySent++;
            } else if (generationCandidate) {
                generationRemaining--;
                generationSent++;
            } else {
                decrementSyncRemaining(ring);
                syncSent++;
            }
        }

        private int syncRemainingForRing(int ring) {
            if (ring <= VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS) {
                return nearSyncRemaining;
            }
            if (ring <= VSSConstants.SYNC_MID_DISTANCE_CHUNKS) {
                return midSyncRemaining;
            }
            if (ring <= VSSConstants.SYNC_FAR_DISTANCE_CHUNKS) {
                return farSyncRemaining;
            }
            return distantSyncRemaining;
        }

        private void decrementSyncRemaining(int ring) {
            if (ring <= VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS) {
                nearSyncRemaining--;
            } else if (ring <= VSSConstants.SYNC_MID_DISTANCE_CHUNKS) {
                midSyncRemaining--;
            } else if (ring <= VSSConstants.SYNC_FAR_DISTANCE_CHUNKS) {
                farSyncRemaining--;
            } else {
                distantSyncRemaining--;
            }
        }
    }

    public record ColumnReceiveResult(boolean knownRequest, boolean priority, long packedPosition) {
    }
}
