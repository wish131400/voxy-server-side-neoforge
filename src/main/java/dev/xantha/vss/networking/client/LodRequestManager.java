package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class LodRequestManager {
    private static final int SCAN_INTERVAL_TICKS = 1;
    private static final long SYNC_REQUEST_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long GENERATION_REQUEST_TIMEOUT_NANOS = 300_000_000_000L;
    private static final long DIRTY_REFRESH_BACKOFF_NANOS = 500_000_000L;
    private static final long RATE_LIMIT_BACKOFF_NANOS = 3_000_000_000L;
    private static final long GENERATION_BACKOFF_NANOS = 15_000_000_000L;
    private static final long MAX_RETRY_BACKOFF_NANOS = 60_000_000_000L;
    private static final int DIRTY_REFRESH_RATE_LIMIT = 512;
    private static final int DIRTY_REFRESH_CONCURRENCY_LIMIT = 64;
    private static final int MAX_SCAN_CANDIDATES_PER_TICK = 4096;
    private static final int MAX_DEFERRED_CANDIDATES_PER_TICK = 2048;
    private static final int MAX_DEFERRED_COLUMNS = 65536;
    private static final int SOFT_FRONTIER_LEAD_CHUNKS = 8;
    private static final int VANILLA_RENDER_DISTANCE_BUFFER_CHUNKS = 2;
    private static final int FAST_MOVE_CHUNK_THRESHOLD = 4;
    private static final int FAST_MOVE_KEEP_RADIUS_CHUNKS = 16;
    private static final int MOVING_FRONTIER_TRAIL_CHUNKS = 16;
    private static final int STATIONARY_REFRESH_DELAY_TICKS = 20;
    private static final int MAX_STATIONARY_REFRESH_CANDIDATES_PER_TICK = 2048;
    private static final long REQUEST_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;

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

    private SessionConfigS2CPayload sessionConfig;
    private ResourceKey<Level> lastDimension;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int lastPlayerBlockX = Integer.MIN_VALUE;
    private int lastPlayerBlockZ = Integer.MIN_VALUE;
    private int nextRequestId;
    private int scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    private int orderedOffsetDistance = -1;
    private long[] orderedOffsets = new long[0];
    private int scanOffsetIndex;
    private int softFrontierRadius;
    private int lastEffectiveLodDistance = -1;
    private boolean scanCompletedForCurrentOffsets;
    private double syncRequestBudget;
    private double generationRequestBudget;
    private double dirtyRefreshBudget;
    private int stationaryTicks;
    private boolean stationaryRefreshArmed;
    private boolean stationaryRefreshActive;
    private int stationaryRefreshCenterX = Integer.MIN_VALUE;
    private int stationaryRefreshCenterZ = Integer.MIN_VALUE;
    private int stationaryRefreshDistance = -1;
    private int stationaryRefreshOffsetIndex;
    private long lastRequestDiagnosticNanos;

    public LodRequestManager() {
        columnTimestamps.defaultReturnValue(-1L);
        positionToRequestId.defaultReturnValue(-1);
        requestIdToPosition.defaultReturnValue(Long.MIN_VALUE);
        requestSendTimes.defaultReturnValue(0L);
        retryAfterNanos.defaultReturnValue(0L);
        retryAttempts.defaultReturnValue(0);
        dirtyColumnTimestamps.defaultReturnValue(0L);
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
            int moveDistance = lastPlayerChunkX == Integer.MIN_VALUE
                    ? 0
                    : Math.max(Math.abs(playerCx - lastPlayerChunkX), Math.abs(playerCz - lastPlayerChunkZ));
            int previousPlayerChunkX = lastPlayerChunkX;
            int previousPlayerChunkZ = lastPlayerChunkZ;
            pruneAround(playerCx, playerCz, lodDistance + VSSConstants.LOD_DISTANCE_BUFFER);
            if (lastPlayerChunkX == Integer.MIN_VALUE || moveDistance >= FAST_MOVE_CHUNK_THRESHOLD) {
                pruneLowPriorityRequestsAround(playerCx, playerCz,
                        Math.min(lodDistance, FAST_MOVE_KEEP_RADIUS_CHUNKS));
                resetScanCursor();
            } else {
                int frontierDistance = Math.min(lodDistance,
                        Math.max(0, softFrontierRadius - MOVING_FRONTIER_TRAIL_CHUNKS));
                deferNewlyVisibleColumns(previousPlayerChunkX, previousPlayerChunkZ, playerCx, playerCz, frontierDistance);
                scanTickCounter = SCAN_INTERVAL_TICKS - 1;
            }
            lastPlayerChunkX = playerCx;
            lastPlayerChunkZ = playerCz;
        }
        updateStationaryRefreshState(playerBlockX, playerBlockZ, playerCx, playerCz, lodDistance);
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
                if (shouldRefreshDirtyImmediately()) {
                    deferColumn(packed, true);
                } else {
                    armStationaryRefresh();
                }
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
            } else if (sessionConfig != null && sessionConfig.generationEnabled()) {
                columnTimestamps.remove(packed);
                diskMissedColumns.add(packed);
                clearBackoff(packed);
                deferredColumns.remove(packed);
                deferColumn(packed);
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

    public void disconnect() {
        resetRequestState();
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
        int syncRate = Math.max(1, sessionConfig.syncOnLoadRateLimitPerPlayer());
        int generationRate = Math.max(1, sessionConfig.generationRateLimitPerPlayer());
        syncRequestBudget = Math.max(syncRequestBudget, syncRate);
        generationRequestBudget = sessionConfig.generationEnabled()
                ? Math.max(generationRequestBudget, generationRate)
                : 0.0D;
        dirtyRefreshBudget = Math.max(dirtyRefreshBudget, DIRTY_REFRESH_RATE_LIMIT);
        clampRequestBudgets();
    }

    private void clampRequestBudgets() {
        if (sessionConfig == null) {
            syncRequestBudget = 0.0D;
            generationRequestBudget = 0.0D;
            dirtyRefreshBudget = 0.0D;
            return;
        }
        syncRequestBudget = Math.min(syncRequestBudget, Math.max(1, sessionConfig.syncOnLoadRateLimitPerPlayer()));
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
        resetScanCursor();
    }

    private void deferNewlyVisibleColumns(
            int previousPlayerCx,
            int previousPlayerCz,
            int playerCx,
            int playerCz,
            int radius) {
        if (radius <= 0) {
            return;
        }

        int minX = playerCx - radius;
        int maxX = playerCx + radius;
        int minZ = playerCz - radius;
        int maxZ = playerCz + radius;

        if (playerCx > previousPlayerCx) {
            int startX = Math.max(minX, previousPlayerCx + radius + 1);
            deferNewlyVisibleStrip(startX, maxX, minZ, maxZ, previousPlayerCx, previousPlayerCz, playerCx, playerCz, radius);
        } else if (playerCx < previousPlayerCx) {
            int endX = Math.min(maxX, previousPlayerCx - radius - 1);
            deferNewlyVisibleStrip(minX, endX, minZ, maxZ, previousPlayerCx, previousPlayerCz, playerCx, playerCz, radius);
        }

        if (playerCz > previousPlayerCz) {
            int startZ = Math.max(minZ, previousPlayerCz + radius + 1);
            deferNewlyVisibleStrip(minX, maxX, startZ, maxZ, previousPlayerCx, previousPlayerCz, playerCx, playerCz, radius);
        } else if (playerCz < previousPlayerCz) {
            int endZ = Math.min(maxZ, previousPlayerCz - radius - 1);
            deferNewlyVisibleStrip(minX, maxX, minZ, endZ, previousPlayerCx, previousPlayerCz, playerCx, playerCz, radius);
        }
    }

    private void deferNewlyVisibleStrip(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int previousPlayerCx,
            int previousPlayerCz,
            int playerCx,
            int playerCz,
            int radius) {
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > radius
                        || PositionUtil.chebyshevDistance(cx, cz, previousPlayerCx, previousPlayerCz) <= radius) {
                    continue;
                }

                long packed = PositionUtil.packPosition(cx, cz);
                if (inFlight.contains(packed) || hasKnownColumn(packed)) {
                    continue;
                }
                deferColumn(packed);
            }
        }
    }

    private void updateStationaryRefreshState(int playerBlockX, int playerBlockZ, int playerCx, int playerCz, int lodDistance) {
        if (lastPlayerBlockX == Integer.MIN_VALUE) {
            lastPlayerBlockX = playerBlockX;
            lastPlayerBlockZ = playerBlockZ;
            stationaryTicks = 0;
            return;
        }

        if (playerBlockX != lastPlayerBlockX || playerBlockZ != lastPlayerBlockZ) {
            lastPlayerBlockX = playerBlockX;
            lastPlayerBlockZ = playerBlockZ;
            stationaryTicks = 0;
            stationaryRefreshArmed = true;
            stationaryRefreshActive = false;
            return;
        }

        stationaryTicks = Math.min(STATIONARY_REFRESH_DELAY_TICKS, stationaryTicks + 1);
        if (stationaryTicks < STATIONARY_REFRESH_DELAY_TICKS) {
            return;
        }

        if (!stationaryRefreshActive && stationaryRefreshArmed) {
            startStationaryRefresh(playerCx, playerCz, lodDistance);
        }
    }

    private boolean shouldRefreshDirtyImmediately() {
        return stationaryTicks >= STATIONARY_REFRESH_DELAY_TICKS;
    }

    private void armStationaryRefresh() {
        stationaryRefreshArmed = true;
        if (stationaryTicks < STATIONARY_REFRESH_DELAY_TICKS) {
            stationaryRefreshActive = false;
        }
    }

    private void startStationaryRefresh(int playerCx, int playerCz, int lodDistance) {
        if (lodDistance <= 0) {
            stationaryRefreshArmed = false;
            stationaryRefreshActive = false;
            return;
        }

        ensureOrderedOffsets(lodDistance);
        stationaryRefreshCenterX = playerCx;
        stationaryRefreshCenterZ = playerCz;
        stationaryRefreshDistance = lodDistance;
        stationaryRefreshOffsetIndex = 0;
        stationaryRefreshActive = true;
        stationaryRefreshArmed = false;
    }

    private int scanStationaryRefreshColumns(
            int playerCx,
            int playerCz,
            int lodDistance,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            boolean[] allowGeneration,
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now) {
        if (!stationaryRefreshActive || orderedOffsets.length == 0 || !requestWindow.hasCapacity()) {
            return count;
        }
        if (playerCx != stationaryRefreshCenterX
                || playerCz != stationaryRefreshCenterZ
                || lodDistance != stationaryRefreshDistance) {
            startStationaryRefresh(playerCx, playerCz, lodDistance);
        }

        int scanned = 0;
        while (count < maxCount
                && requestWindow.hasCapacity()
                && stationaryRefreshOffsetIndex < orderedOffsets.length
                && scanned < MAX_STATIONARY_REFRESH_CANDIDATES_PER_TICK) {
            long offset = orderedOffsets[stationaryRefreshOffsetIndex++];
            scanned++;

            int cx = stationaryRefreshCenterX + decodeOffsetX(offset);
            int cz = stationaryRefreshCenterZ + decodeOffsetZ(offset);
            long packed = PositionUtil.packPosition(cx, cz);
            if (!shouldRequestColumn(packed, now)) {
                continue;
            }

            boolean dirtyRefresh = dirtyColumns.contains(packed);
            boolean generationCandidate = !dirtyRefresh && isGenerationCandidate(packed);
            if (!requestWindow.canSend(dirtyRefresh, generationCandidate)) {
                continue;
            }

            deferredColumns.remove(packed);
            count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, generationCandidate);
            requestWindow.record(dirtyRefresh, generationCandidate);
        }

        if (stationaryRefreshOffsetIndex >= orderedOffsets.length) {
            stationaryRefreshActive = false;
        }
        return count;
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
        int[] requestIds = new int[maxCount];
        long[] positions = new long[maxCount];
        long[] timestamps = new long[maxCount];
        boolean[] allowGeneration = new boolean[maxCount];
        int count = 0;

        if (lodDistance <= 0) {
            return;
        }

        ensureOrderedOffsets(lodDistance);
        int protectedSyncDistance = getVanillaProtectedSyncDistance();
        long now = System.nanoTime();
        count = drainDeferredColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions,
                timestamps, allowGeneration, count, maxCount, requestWindow, now);
        count = scanStationaryRefreshColumns(playerCx, playerCz, lodDistance, requestIds, positions, timestamps,
                allowGeneration, count, maxCount, requestWindow, now);
        count = scanNewSyncColumns(playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds, positions, timestamps,
                allowGeneration, count, maxCount, requestWindow, now);

        if (count > 0) {
            syncRequestBudget = Math.max(0.0D, syncRequestBudget - requestWindow.syncSent);
            generationRequestBudget = Math.max(0.0D, generationRequestBudget - requestWindow.generationSent);
            dirtyRefreshBudget = Math.max(0.0D, dirtyRefreshBudget - requestWindow.dirtySent);
            VSSClientNetworking.sendBatchRequest(new BatchChunkRequestC2SPayload(requestIds, positions, timestamps, allowGeneration, count));
            logRequestBatch(now, count, requestWindow.syncSent, requestWindow.generationSent, requestWindow.dirtySent, lodDistance, playerCx, playerCz);
        }
    }

    private RequestWindow createRequestWindow() {
        int dirtyInFlightCount = dirtyRefreshInFlight.size();
        int generationInFlightCount = generationInFlight.size();
        int syncInFlightCount = Math.max(0, inFlight.size() - generationInFlightCount - dirtyInFlightCount);

        int syncConcurrencyLimit = Math.max(1, sessionConfig.syncOnLoadConcurrencyLimitPerPlayer());
        int generationConcurrencyLimit = sessionConfig.generationEnabled()
                ? Math.max(1, sessionConfig.generationConcurrencyLimitPerPlayer())
                : 0;

        int syncSlots = Math.max(0, syncConcurrencyLimit - syncInFlightCount);
        int generationSlots = Math.max(0, generationConcurrencyLimit - generationInFlightCount);
        int dirtySlots = Math.max(0, DIRTY_REFRESH_CONCURRENCY_LIMIT - dirtyInFlightCount);

        int syncRate = Math.max(1, sessionConfig.syncOnLoadRateLimitPerPlayer());
        int generationRate = Math.max(1, sessionConfig.generationRateLimitPerPlayer());
        syncRequestBudget = Math.min(syncRate, syncRequestBudget + syncRate / 20.0D);
        generationRequestBudget = Math.min(generationRate, generationRequestBudget + generationRate / 20.0D);
        dirtyRefreshBudget = Math.min(DIRTY_REFRESH_RATE_LIMIT, dirtyRefreshBudget + DIRTY_REFRESH_RATE_LIMIT / 20.0D);

        return new RequestWindow(
                Math.min(syncSlots, (int) syncRequestBudget),
                Math.min(generationSlots, (int) generationRequestBudget),
                Math.min(dirtySlots, (int) dirtyRefreshBudget));
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
            long now) {
        int attempts = Math.min(deferredQueue.size(), MAX_DEFERRED_CANDIDATES_PER_TICK);
        if (attempts <= 0) {
            return count;
        }

        List<Long> candidates = new ArrayList<>(attempts);
        LongOpenHashSet seen = new LongOpenHashSet();
        while (attempts-- > 0 && !deferredQueue.isEmpty()) {
            long packed = deferredQueue.removeFirst();
            if (!deferredColumns.contains(packed) || !seen.add(packed)) {
                continue;
            }
            candidates.add(packed);
        }

        candidates.sort(deferredColumnComparator(playerCx, playerCz));
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
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > lodDistance) {
                continue;
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
            if (!dirtyRefresh && !isWithinSoftFrontier(packed, playerCx, playerCz, lodDistance)) {
                requeueDeferredColumn(packed, false);
                continue;
            }
            if (!requestWindow.canSend(dirtyRefresh, generationCandidate)) {
                requeueDeferredColumn(packed, dirtyRefresh);
                continue;
            }

            count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, generationCandidate);
            requestWindow.record(dirtyRefresh, generationCandidate);
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
            long now) {
        if (!requestWindow.hasSyncCapacity() || orderedOffsets.length == 0) {
            return count;
        }
        if (scanCompletedForCurrentOffsets) {
            return count;
        }

        int scannedCandidates = 0;
        int totalCandidates = orderedOffsets.length;
        int maxScans = Math.min(MAX_SCAN_CANDIDATES_PER_TICK, totalCandidates);
        while (count < maxCount
                && requestWindow.hasSyncCapacity()
                && scannedCandidates < maxScans) {
            if (scanOffsetIndex >= totalCandidates) {
                scanCompletedForCurrentOffsets = true;
                softFrontierRadius = lodDistance;
                return count;
            }

            long offset = orderedOffsets[scanOffsetIndex++];
            scannedCandidates++;
            updateSoftFrontier(offsetRing(offset), lodDistance);

            int cx = playerCx + decodeOffsetX(offset);
            int cz = playerCz + decodeOffsetZ(offset);
            long packed = PositionUtil.packPosition(cx, cz);
            if (isInsideProtectedSyncWindow(packed, playerCx, playerCz, protectedSyncDistance)
                    || !shouldRequestColumn(packed, now)
                    || dirtyColumns.contains(packed)
                    || isGenerationCandidate(packed)) {
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
                    now);
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
            long now) {
        int centerX = PositionUtil.unpackX(centerPacked);
        int centerZ = PositionUtil.unpackZ(centerPacked);
        int maxClusterRing = chebyshevDistance(centerPacked, playerCx, playerCz);
        count = appendClusterCandidate(centerPacked, playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds,
                positions, timestamps, allowGeneration, count, maxCount, requestWindow, now, maxClusterRing);
        for (int dz = -1; dz <= 1 && count < maxCount && requestWindow.hasSyncCapacity(); dz++) {
            for (int dx = -1; dx <= 1 && count < maxCount && requestWindow.hasSyncCapacity(); dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long packed = PositionUtil.packPosition(centerX + dx, centerZ + dz);
                count = appendClusterCandidate(packed, playerCx, playerCz, lodDistance, protectedSyncDistance, requestIds,
                        positions, timestamps, allowGeneration, count, maxCount, requestWindow, now, maxClusterRing);
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
        if (count >= maxCount || !requestWindow.hasSyncCapacity()) {
            return count;
        }
        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        int ring = PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz);
        if (ring > lodDistance
                || (protectedSyncDistance > 0 && ring <= protectedSyncDistance)
                || ring > maxClusterRing
                || !shouldRequestColumn(packed, now)) {
            return count;
        }

        if (dirtyColumns.contains(packed) || isGenerationCandidate(packed) || !requestWindow.canSend(false, false)) {
            return count;
        }

        count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, false);
        requestWindow.record(false, false);
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
        if (timestamp == 0L) {
            return false;
        }
        return true;
    }

    private void forgetColumn(ResourceKey<Level> dimension, int cx, int cz, boolean applyBackoff) {
        if (sessionConfig == null || !sessionConfig.enabled() || !isActiveDimension(dimension)) {
            return;
        }

        long packed = PositionUtil.packPosition(cx, cz);
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
            resetScanCursor();
        }
    }

    private boolean isActiveDimension(ResourceKey<Level> dimension) {
        if (lastDimension != null) {
            return lastDimension.equals(dimension);
        }
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(dimension);
    }

    private boolean isGenerationCandidate(long packed) {
        if (sessionConfig == null || !sessionConfig.generationEnabled()) {
            return false;
        }
        return diskMissedColumns.contains(packed);
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
        lastPlayerBlockX = Integer.MIN_VALUE;
        lastPlayerBlockZ = Integer.MIN_VALUE;
        scanOffsetIndex = 0;
        softFrontierRadius = 0;
        lastEffectiveLodDistance = -1;
        scanCompletedForCurrentOffsets = false;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        syncRequestBudget = 0.0D;
        generationRequestBudget = 0.0D;
        dirtyRefreshBudget = 0.0D;
        stationaryTicks = 0;
        stationaryRefreshArmed = false;
        stationaryRefreshActive = false;
        stationaryRefreshCenterX = Integer.MIN_VALUE;
        stationaryRefreshCenterZ = Integer.MIN_VALUE;
        stationaryRefreshDistance = -1;
        stationaryRefreshOffsetIndex = 0;
    }

    private void resetScanCursor() {
        scanOffsetIndex = 0;
        softFrontierRadius = 0;
        scanCompletedForCurrentOffsets = false;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
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
        scanOffsetIndex = index;
        softFrontierRadius = Math.max(0, Math.min(ring - 1, orderedOffsetDistance));
        scanCompletedForCurrentOffsets = index >= orderedOffsets.length;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void ensureOrderedOffsets(int lodDistance) {
        if (orderedOffsetDistance == lodDistance) {
            return;
        }

        int side = lodDistance * 2 + 1;
        long[] offsets = new long[side * side];
        int index = 0;
        offsets[index++] = encodeOffset(0, 0);
        for (int ring = 1; ring <= lodDistance; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                offsets[index++] = encodeOffset(-ring, dz);
                offsets[index++] = encodeOffset(ring, dz);
            }
            for (int dx = -ring + 1; dx <= ring - 1; dx++) {
                offsets[index++] = encodeOffset(dx, -ring);
                offsets[index++] = encodeOffset(dx, ring);
            }
        }
        orderedOffsets = offsets;
        orderedOffsetDistance = lodDistance;
        resetScanCursor();
    }

    private static int getVanillaProtectedSyncDistance() {
        try {
            return Math.max(0, Minecraft.getInstance().options.renderDistance().get()
                    + VANILLA_RENDER_DISTANCE_BUFFER_CHUNKS);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean isInsideProtectedSyncWindow(
            long packed,
            int playerCx,
            int playerCz,
            int protectedSyncDistance) {
        return protectedSyncDistance > 0 && chebyshevDistance(packed, playerCx, playerCz) <= protectedSyncDistance;
    }

    private boolean isWithinSoftFrontier(long packed, int playerCx, int playerCz, int lodDistance) {
        int radius = Math.min(maxFrontierRadius(lodDistance), softFrontierRadius + SOFT_FRONTIER_LEAD_CHUNKS);
        return chebyshevDistance(packed, playerCx, playerCz) <= radius;
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
        int attempts = Math.min(6, retryAttempts.addTo(packed, 1) + 1);
        long baseDelay = dirtyColumns.contains(packed)
                ? DIRTY_REFRESH_BACKOFF_NANOS
                : generationCandidate ? GENERATION_BACKOFF_NANOS : RATE_LIMIT_BACKOFF_NANOS;
        long delay = Math.min(MAX_RETRY_BACKOFF_NANOS, baseDelay << Math.max(0, attempts - 1));
        retryAfterNanos.put(packed, System.nanoTime() + delay);
    }

    private void clearBackoff(long packed) {
        retryAfterNanos.remove(packed);
        retryAttempts.remove(packed);
    }

    private static final class RequestWindow {
        private int syncRemaining;
        private int generationRemaining;
        private int dirtyRemaining;
        private int syncSent;
        private int generationSent;
        private int dirtySent;

        private RequestWindow(int syncRemaining, int generationRemaining, int dirtyRemaining) {
            this.syncRemaining = syncRemaining;
            this.generationRemaining = generationRemaining;
            this.dirtyRemaining = dirtyRemaining;
        }

        private boolean hasCapacity() {
            return syncRemaining > 0 || generationRemaining > 0 || dirtyRemaining > 0;
        }

        private boolean hasSyncCapacity() {
            return syncRemaining > 0;
        }

        private int remaining() {
            return Math.max(0, syncRemaining) + Math.max(0, generationRemaining) + Math.max(0, dirtyRemaining);
        }

        private boolean canSend(boolean dirtyRefresh, boolean generationCandidate) {
            if (dirtyRefresh) {
                return dirtyRemaining > 0;
            }
            return generationCandidate ? generationRemaining > 0 : syncRemaining > 0;
        }

        private void record(boolean dirtyRefresh, boolean generationCandidate) {
            if (dirtyRefresh) {
                dirtyRemaining--;
                dirtySent++;
            } else if (generationCandidate) {
                generationRemaining--;
                generationSent++;
            } else {
                syncRemaining--;
                syncSent++;
            }
        }
    }

    public record ColumnReceiveResult(boolean knownRequest, boolean priority, long packedPosition) {
    }
}
