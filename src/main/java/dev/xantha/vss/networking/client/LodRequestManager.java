package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.ChebyshevRingOffsets;
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
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long RATE_LIMIT_BACKOFF_NANOS = 1_000_000_000L;
    private static final long GENERATION_BACKOFF_NANOS = 5_000_000_000L;
    private static final int DIRTY_REFRESH_BACKOFF_MAX_SHIFT = 3;
    private static final int RATE_LIMIT_BACKOFF_MAX_SHIFT = 3;
    private static final int GENERATION_BACKOFF_MAX_SHIFT = 1;
    private static final int DIRTY_REFRESH_RATE_LIMIT = 512;
    private static final int DIRTY_REFRESH_CONCURRENCY_LIMIT = 64;
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
    private static final int MOVEMENT_RESCAN_TRAIL_CHUNKS = 16;
    private static final int ESTIMATED_SYNC_COLUMN_BYTES = 5 * 1024;
    private static final int PRESENCE_REGIONS_PER_PACKET = 64;
    private static final int INTEGRATED_PRESENCE_REGIONS_PER_PACKET = 16;
    private static final int PRESENCE_COLUMNS_PER_PACKET = 4096;
    private static final int INTEGRATED_PRESENCE_COLUMNS_PER_PACKET = 1024;
    private static final int PRESENCE_PACKETS_PER_TICK = 2;
    private static final int INTEGRATED_PRESENCE_PACKETS_PER_TICK = 1;
    private static final long PRESENCE_INCREMENTAL_RESEND_INTERVAL_NANOS = 5_000_000_000L;
    private static final int PRESENCE_INCREMENTAL_RESENDS_PER_TICK = 32;
    private static final long REQUEST_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;

    private static final Map<Integer, long[]> OFFSET_CACHE = new ConcurrentHashMap<>();

    static {
        for (int dist : new int[]{32, 64, 96, 128, 192, 256}) {
            OFFSET_CACHE.put(dist, generateOffsetsForDistance(dist));
        }
    }

    private static long[] generateOffsetsForDistance(int lodDistance) {
        return ChebyshevRingOffsets.generate(lodDistance);
    }

    private final Long2LongOpenHashMap columnTimestamps = new Long2LongOpenHashMap();
    private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    private final Long2LongOpenHashMap dirtyColumnTimestamps = new Long2LongOpenHashMap();
    private final LongOpenHashSet inFlight = new LongOpenHashSet();
    private final DeferredColumnQueue deferredColumns = new DeferredColumnQueue(MAX_DEFERRED_COLUMNS);
    private final Long2IntOpenHashMap positionToRequestId = new Long2IntOpenHashMap();
    private final Int2LongOpenHashMap requestIdToPosition = new Int2LongOpenHashMap();
    private final Long2LongOpenHashMap requestSendTimes = new Long2LongOpenHashMap();
    private final PriorityQueue<RequestDeadline> requestDeadlines = new PriorityQueue<>();
    private final LongOpenHashSet generationInFlight = new LongOpenHashSet();
    private final LongOpenHashSet dirtyRefreshInFlight = new LongOpenHashSet();
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
        dirtyColumnTimestamps.defaultReturnValue(0L);
        delayedPresenceRegionDeadlines.defaultReturnValue(0L);
        generationRequestBudget = 8.0;
        dirtyRefreshBudget = 16.0;
    }

    public synchronized boolean onSessionConfig(SessionConfigS2CPayload config) {
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

    public synchronized void tick() {
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

    public synchronized ColumnReceiveResult onColumnReceived(int requestId, long columnTimestamp) {
        boolean dirtyRefreshRequest = isDirtyRefreshRequest(requestId);
        long packed = removeRequest(requestId);
        if (packed == Long.MIN_VALUE) {
            return new ColumnReceiveResult(false, false, false, Long.MIN_VALUE);
        }

        boolean replacingKnownColumn = columnTimestamps.get(packed) > 0L || dirtyRefreshRequest;
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
        return new ColumnReceiveResult(true, dirtyRefreshRequest, replacingKnownColumn, packed);
    }

    public synchronized void onPushedColumnReceived(ResourceKey<Level> dimension, int cx, int cz, long columnTimestamp) {
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

    public synchronized void onColumnProcessingFailed(ResourceKey<Level> dimension, int cx, int cz) {
        forgetColumn(dimension, cx, cz, true);
    }

    public synchronized void onClientChunkDropped(ResourceKey<Level> dimension, int cx, int cz) {
        forgetColumn(dimension, cx, cz, false);
    }

    public synchronized void onDirtyColumns(long[] dirtyPositions, long[] dirtyTimestamps) {
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

    public synchronized void onColumnNotGenerated(int requestId) {
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
            } else if (sessionConfig != null && sessionConfig.generationEnabled()) {
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
                diskMissedColumns.add(packed);
                clearBackoff(packed);
                deferredColumns.remove(packed);
            }
        }
    }

    public synchronized void onColumnUpToDate(int requestId) {
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

    public synchronized void onRateLimited(int requestId) {
        boolean generationRequest = isGenerationRequest(requestId);
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            markBackoff(packed, generationRequest || isGenerationCandidate(packed));
            deferredColumns.remove(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    public synchronized void onBackpressured(int requestId) {
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            markBackpressure(packed);
            deferredColumns.remove(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    public synchronized void disconnect() {
        resetRequestState();
        ClientLodPresenceCache.flush();
    }

    public synchronized void forceResync() {
        resetRequestStateAfterConfigChange();
    }

    public synchronized int getPendingCount() {
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

        int maxCount = Math.min(
                Math.min(VSSConstants.MAX_BATCH_CHUNK_REQUESTS, requestWindow.remaining()),
                maxRequestsPerTick());
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
        ScanBudget scanBudget = ScanBudget.create(scanBoostTicks > 0);

        count = scanNearSyncColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions,
                timestamps, allowGeneration, count, maxCount, requestWindow, now, scanBudget);
        count = drainDeferredColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions,
                timestamps, allowGeneration, count, maxCount, requestWindow, now, maxAllowedRingThisTick, scanBudget,
                DeferredDrainMode.DIRTY_ONLY);
        count = scanNewSyncColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions, timestamps,
                allowGeneration, count, maxCount, requestWindow, now, maxAllowedRingThisTick, scanBudget);
        count = drainDeferredColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions,
                timestamps, allowGeneration, count, maxCount, requestWindow, now, maxAllowedRingThisTick, scanBudget,
                DeferredDrainMode.ALL);
        if (scanBoostTicks > 0) {
            scanBoostTicks--;
        }

        if (count > 0) {
            generationRequestBudget = Math.max(0.0D, generationRequestBudget - requestWindow.generationSent());
            dirtyRefreshBudget = Math.max(0.0D, dirtyRefreshBudget - requestWindow.dirtySent());
            VSSClientNetworking.sendBatchRequest(new BatchChunkRequestC2SPayload(requestIds, positions, timestamps, allowGeneration, count));
            logRequestBatch(now, count, requestWindow.syncSent(), requestWindow.generationSent(), requestWindow.dirtySent(), lodDistance, playerCx, playerCz);
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
            long now,
            ScanBudget scanBudget) {
        if (nearScanCompletedForCurrentOffsets
                || orderedOffsets.length == 0
                || !requestWindow.hasNormalCandidateCapacity(0)
                || lodDistance <= 0
                || !scanBudget.canScanMore()) {
            return count;
        }

        int maxAllowedRing = Math.min(lodDistance, VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS);
        int totalCandidates = orderedOffsets.length;
        while (count < maxCount
                && requestWindow.hasNormalCandidateCapacity(0)
                && scanBudget.canScanMore()) {
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
            if (shouldWaitForFirstPassGenerationSlot(packed, requestWindow)) {
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
            int maxAllowedRing,
            ScanBudget scanBudget,
            DeferredDrainMode mode) {
        if (count >= maxCount || deferredColumns.queuedEntries() <= 0 || !scanBudget.canScanMore()) {
            return count;
        }
        if (mode == DeferredDrainMode.DIRTY_ONLY && dirtyColumns.isEmpty()) {
            return count;
        }

        List<Long> candidates = bucketDeferredCandidates(
                deferredColumns.pollUniqueCandidates(Math.min(MAX_DEFERRED_CANDIDATES_PER_TICK, scanBudget.remainingCandidates())),
                playerCx,
                playerCz,
                lodDistance);

        int firstNormalDeferredRing = -1;

        for (long packed : candidates) {
            boolean dirtyRefresh = dirtyColumns.contains(packed);
            if (!mode.accepts(dirtyRefresh)) {
                requeueDeferredColumn(packed, dirtyRefresh);
                continue;
            }
            if (!scanBudget.canScanMore()) {
                requeueDeferredColumn(packed, dirtyRefresh);
                continue;
            }
            scanBudget.recordCandidate();
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

            if (ring > maxAllowedRing) {
                if (!dirtyRefresh || ring > maxAllowedRing + 10) {
                    requeueDeferredColumn(packed, dirtyRefresh);
                    continue;
                }
            }

            if (!dirtyRefresh) {
                if (firstNormalDeferredRing < 0) {
                    firstNormalDeferredRing = ring;
                } else if (ring > firstNormalDeferredRing + 1) {
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
            if (!dirtyRefresh && !isWithinDeferredFrontier(packed, playerCx, playerCz)) {
                requeueDeferredColumn(packed, false);
                continue;
            }
            if (!requestWindow.canSend(dirtyRefresh, generationCandidate, ring)) {
                requeueDeferredColumn(packed, dirtyRefresh);
                continue;
            }

            count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, generationCandidate);
            requestWindow.record(dirtyRefresh, generationCandidate, ring);
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
            int maxAllowedRing,
            ScanBudget scanBudget) {
        if (!requestWindow.hasAnyNormalCandidateCapacity() || orderedOffsets.length == 0 || !scanBudget.canScanMore()) {
            return count;
        }
        if (scanCompletedForCurrentOffsets) {
            return count;
        }

        int totalCandidates = orderedOffsets.length;

        RingScanGate ringGate = RingScanGate.fromCursor(orderedOffsets, scanOffsetIndex, totalCandidates);

        while (count < maxCount
                && requestWindow.hasAnyNormalCandidateCapacity()
                && scanBudget.canScanMore()) {
            if (scanOffsetIndex >= totalCandidates) {
                scanCompletedForCurrentOffsets = true;
                softFrontierRadius = lodDistance;
                return count;
            }

            long offset = orderedOffsets[scanOffsetIndex];
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
            if (shouldWaitForFirstPassGenerationSlot(packed, requestWindow)) {
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
                positions, timestamps, allowGeneration, count, maxCount, requestWindow, now, centerRing);

        for (int dz = -1; dz <= 1 && count < maxCount && requestWindow.hasNormalCandidateCapacity(centerRing); dz++) {
            for (int dx = -1; dx <= 1 && count < maxCount && requestWindow.hasNormalCandidateCapacity(centerRing); dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long packed = PositionUtil.packPosition(centerX + dx, centerZ + dz);
                int neighborRing = chebyshevDistance(packed, playerCx, playerCz);

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

        if (shouldWaitForFirstPassGenerationSlot(packed, requestWindow)) {
            return count;
        }
        boolean generationCandidate = shouldUseFirstPassGenerationFallback(packed, ring, requestWindow);
        if (!requestWindow.canSend(false, generationCandidate, ring)) {
            return count;
        }

        count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, generationCandidate);
        requestWindow.record(false, generationCandidate, ring);
        return count;
    }

    private boolean shouldUseFirstPassGenerationFallback(long packed, int ring, RequestWindow requestWindow) {
        if (!requestWindow.hasGenerationCapacity() || !requiresFirstPassGenerationFallback(packed)) {
            return false;
        }
        if (!requestWindow.canSend(false, true, ring)) {
            return false;
        }
        return true;
    }

    private boolean shouldWaitForFirstPassGenerationSlot(long packed, RequestWindow requestWindow) {
        return isGenerationCandidate(packed) && !requestWindow.hasGenerationCapacity();
    }

    private boolean requiresFirstPassGenerationFallback(long packed) {
        return sessionConfig != null
                && sessionConfig.generationEnabled()
                && columnTimestamps.get(packed) <= 0L
                && !hasKnownColumn(packed);
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
            queuePresenceRegionRing(centerRegionX, centerRegionZ, ring);
        }
        seedPresenceRegion(level, dimension, centerRegionX, centerRegionZ);
    }

    private void queuePresenceRegionRing(int centerRegionX, int centerRegionZ, int ring) {
        if (ring == 0) {
            queuePresenceRegion(centerRegionX, centerRegionZ, false);
            return;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            queuePresenceRegion(centerRegionX + dx, centerRegionZ - ring, false);
            queuePresenceRegion(centerRegionX + dx, centerRegionZ + ring, false);
        }
        for (int dz = -ring + 1; dz <= ring - 1; dz++) {
            queuePresenceRegion(centerRegionX - ring, centerRegionZ + dz, false);
            queuePresenceRegion(centerRegionX + ring, centerRegionZ + dz, false);
        }
    }

    private void seedPresenceRegion(ClientLevel level, ResourceKey<Level> dimension, int regionX, int regionZ) {
        ClientLodPresenceCache.seedRegion(presenceScope, dimension, regionX, regionZ, columnTimestamps, level);
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
        int packetsPerTick = presencePacketsPerTick();
        int regionsPerPacket = presenceRegionsPerPacket();
        int columnsPerPacket = presenceColumnsPerPacket();
        while (packetsSent < packetsPerTick && !pendingPresenceRegions.isEmpty()) {
            ArrayList<RegionPresenceC2SPayload.RegionEntry> entries = new ArrayList<>();
            int consumedRegions = 0;
            int columnCount = 0;
            while (consumedRegions < regionsPerPacket
                    && columnCount < columnsPerPacket
                    && !pendingPresenceRegions.isEmpty()) {
                long key = pendingPresenceRegions.pollFirst();
                queuedPresenceRegions.remove(key);
                sentPresenceRegions.add(key);
                consumedRegions++;
                int regionX = ClientLodPresenceCache.regionKeyX(key);
                int regionZ = ClientLodPresenceCache.regionKeyZ(key);
                seedPresenceRegion(level, dimension, regionX, regionZ);
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
        return ring <= generationFallbackFrontier();
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
            clearBackoff(packed);
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
        deferredColumns.defer(packed);
    }

    private void deferColumn(long packed, boolean urgent) {
        deferredColumns.defer(packed, urgent);
    }

    private void requeueDeferredColumn(long packed, boolean urgent) {
        deferredColumns.requeue(packed, urgent);
    }

    private List<Long> bucketDeferredCandidates(List<Long> candidates, int playerCx, int playerCz, int lodDistance) {
        if (candidates.size() <= 1) {
            return candidates;
        }

        int bucketCount = Math.max(1, lodDistance + 2);
        ArrayDeque<Long>[] dirtyBuckets = newDeferredBuckets(bucketCount);
        ArrayDeque<Long>[] normalBuckets = newDeferredBuckets(bucketCount);
        ArrayDeque<Long>[] generationBuckets = newDeferredBuckets(bucketCount);

        for (long packed : candidates) {
            boolean dirtyRefresh = dirtyColumns.contains(packed);
            int ring = chebyshevDistance(packed, playerCx, playerCz);
            int bucket = Math.min(Math.max(0, ring), bucketCount - 1);
            ArrayDeque<Long>[] buckets;
            if (dirtyRefresh) {
                buckets = dirtyBuckets;
            } else if (isGenerationCandidate(packed)) {
                buckets = generationBuckets;
            } else {
                buckets = normalBuckets;
            }
            ArrayDeque<Long> ringBucket = buckets[bucket];
            if (ringBucket == null) {
                ringBucket = new ArrayDeque<>();
                buckets[bucket] = ringBucket;
            }
            ringBucket.addLast(packed);
        }

        ArrayList<Long> ordered = new ArrayList<>(candidates.size());
        appendBuckets(ordered, dirtyBuckets);
        for (int i = 0; i < bucketCount; i++) {
            appendBucket(ordered, normalBuckets[i]);
            appendBucket(ordered, generationBuckets[i]);
        }
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Long>[] newDeferredBuckets(int bucketCount) {
        return (ArrayDeque<Long>[]) new ArrayDeque<?>[bucketCount];
    }

    private static void appendBuckets(ArrayList<Long> ordered, ArrayDeque<Long>[] buckets) {
        for (ArrayDeque<Long> bucket : buckets) {
            appendBucket(ordered, bucket);
        }
    }

    private static void appendBucket(ArrayList<Long> ordered, ArrayDeque<Long> bucket) {
        if (bucket == null) {
            return;
        }
        while (!bucket.isEmpty()) {
            ordered.add(bucket.removeFirst());
        }
    }

    private static int maxRequestsPerTick() {
        return isIntegratedServer() ? INTEGRATED_MAX_REQUESTS_PER_TICK : MAX_REQUESTS_PER_TICK;
    }

    private static int presencePacketsPerTick() {
        return isIntegratedServer() ? INTEGRATED_PRESENCE_PACKETS_PER_TICK : PRESENCE_PACKETS_PER_TICK;
    }

    private static int presenceRegionsPerPacket() {
        return isIntegratedServer() ? INTEGRATED_PRESENCE_REGIONS_PER_PACKET : PRESENCE_REGIONS_PER_PACKET;
    }

    private static int presenceColumnsPerPacket() {
        return isIntegratedServer() ? INTEGRATED_PRESENCE_COLUMNS_PER_PACKET : PRESENCE_COLUMNS_PER_PACKET;
    }

    private static boolean isIntegratedServer() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
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
        diskMissedColumns.clear();
        positionToRequestId.clear();
        requestIdToPosition.clear();
        requestSendTimes.clear();
        requestDeadlines.clear();
        generationInFlight.clear();
        dirtyRefreshInFlight.clear();
        retryBackoff.clearAll();
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

        orderedOffsets = OFFSET_CACHE.computeIfAbsent(lodDistance, LodRequestManager::generateOffsetsForDistance);
        orderedOffsetDistance = lodDistance;
        resetScanCursor();
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

    private boolean isWithinDeferredFrontier(long packed, int playerCx, int playerCz) {
        int ring = chebyshevDistance(packed, playerCx, playerCz);
        if (isGenerationCandidate(packed)) {
            return ring <= generationFallbackFrontier();
        }
        return ring <= softFrontierRadius;
    }

    private int generationFallbackFrontier() {
        return Math.min(
                getEffectiveLodDistance(),
                Math.max(softFrontierRadius, VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS));
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

    private record RequestDeadline(long packed, int requestId, long timeoutAtNanos) implements Comparable<RequestDeadline> {
        private boolean isDue(long nowNanos) {
            return timeoutAtNanos - nowNanos <= 0L;
        }

        @Override
        public int compareTo(RequestDeadline other) {
            return Long.compare(timeoutAtNanos, other.timeoutAtNanos);
        }
    }

    private static final class ScanBudget {
        private int remainingCandidates;
        private int checksUntilDeadline;
        private final long deadlineNanos;

        private ScanBudget(int remainingCandidates, long deadlineNanos) {
            this.remainingCandidates = Math.max(0, remainingCandidates);
            this.deadlineNanos = deadlineNanos;
            this.checksUntilDeadline = SCAN_DEADLINE_CHECK_INTERVAL;
        }

        static ScanBudget create(boolean boosted) {
            int candidateLimit = boosted ? BOOSTED_SCAN_CANDIDATES_PER_TICK : MAX_SCAN_CANDIDATES_PER_TICK;
            long scanNanos = isIntegratedServer() ? INTEGRATED_MAX_SCAN_NANOS_PER_TICK : MAX_SCAN_NANOS_PER_TICK;
            return new ScanBudget(candidateLimit, System.nanoTime() + scanNanos);
        }

        boolean canScanMore() {
            if (remainingCandidates <= 0) {
                return false;
            }
            if (--checksUntilDeadline > 0) {
                return true;
            }
            checksUntilDeadline = SCAN_DEADLINE_CHECK_INTERVAL;
            return System.nanoTime() - deadlineNanos <= 0L;
        }

        void recordCandidate() {
            remainingCandidates = Math.max(0, remainingCandidates - 1);
        }

        int remainingCandidates() {
            return remainingCandidates;
        }
    }

    private enum DeferredDrainMode {
        DIRTY_ONLY,
        ALL;

        private boolean accepts(boolean dirtyRefresh) {
            return this == ALL || dirtyRefresh;
        }
    }

    private boolean isCoolingDown(long packed, long now) {
        return retryBackoff.isCoolingDown(packed, now);
    }

    private void markBackoff(long packed, boolean generationCandidate) {
        retryBackoff.markBackoff(packed, dirtyColumns.contains(packed), generationCandidate);
    }

    private void markBackpressure(long packed) {
        retryBackoff.markBackpressure(packed);
    }

    private void clearBackoff(long packed) {
        retryBackoff.clear(packed);
    }

    public record ColumnReceiveResult(boolean knownRequest, boolean priority, boolean replaceExistingColumn, long packedPosition) {
    }
}
