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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
    private final LongOpenHashSet generationInFlight = new LongOpenHashSet();
    private final LongOpenHashSet dirtyRefreshInFlight = new LongOpenHashSet();
    private final Long2LongOpenHashMap retryAfterNanos = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap retryAttempts = new Long2IntOpenHashMap();
    private final LongOpenHashSet diskMissedColumns = new LongOpenHashSet();

    private SessionConfigS2CPayload sessionConfig;
    private ResourceKey<Level> lastDimension;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int nextRequestId;
    private int scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    private int orderedOffsetDistance = -1;
    private long[] orderedOffsets = new long[0];
    private int scanOffsetIndex;
    private int softFrontierRadius;
    private double syncRequestBudget;
    private double generationRequestBudget;
    private double dirtyRefreshBudget;
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

    public void onSessionConfig(SessionConfigS2CPayload config) {
        sessionConfig = config;
        resetRequestState();
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
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        if (playerCx != lastPlayerChunkX || playerCz != lastPlayerChunkZ) {
            pruneAround(playerCx, playerCz, getEffectiveLodDistance() + VSSConstants.LOD_DISTANCE_BUFFER);
            resetScanCursor();
            lastPlayerChunkX = playerCx;
            lastPlayerChunkZ = playerCz;
        }
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
            } else if (sessionConfig != null && sessionConfig.generationEnabled()) {
                columnTimestamps.remove(packed);
                diskMissedColumns.add(packed);
                markBackoff(packed, true);
                deferredColumns.remove(packed);
                deferColumn(packed);
            } else {
                columnTimestamps.put(packed, 0L);
                diskMissedColumns.remove(packed);
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
                    markBackoff(packed, true);
                    deferColumn(packed);
                } else {
                    columnTimestamps.put(packed, 0L);
                    diskMissedColumns.remove(packed);
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
        int preservedNextRequestId = nextRequestId;
        for (int requestId : requestIdToPosition.keySet()) {
            sendCancelPacket(requestId);
        }
        resetRequestState();
        nextRequestId = preservedNextRequestId;
    }

    public int getPendingCount() {
        return inFlight.size();
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
        long now = System.nanoTime();
        count = drainDeferredColumns(playerCx, playerCz, lodDistance, requestIds, positions, timestamps, allowGeneration, count, maxCount, requestWindow, now);
        count = scanNewSyncColumns(playerCx, playerCz, lodDistance, requestIds, positions, timestamps,
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

        int scannedCandidates = 0;
        int totalCandidates = orderedOffsets.length;
        int maxScans = Math.min(MAX_SCAN_CANDIDATES_PER_TICK, totalCandidates);
        while (count < maxCount
                && requestWindow.hasSyncCapacity()
                && scannedCandidates < maxScans) {
            if (scanOffsetIndex >= totalCandidates) {
                scanOffsetIndex = 0;
                softFrontierRadius = maxFrontierRadius(lodDistance);
            }

            long offset = orderedOffsets[scanOffsetIndex++];
            scannedCandidates++;
            updateSoftFrontier(offsetDistanceSquared(offset), lodDistance);

            int cx = playerCx + decodeOffsetX(offset);
            int cz = playerCz + decodeOffsetZ(offset);
            long packed = PositionUtil.packPosition(cx, cz);
            if (!shouldRequestColumn(packed, now)
                    || dirtyColumns.contains(packed)
                    || isGenerationCandidate(packed)) {
                continue;
            }

            count = appendRequestCluster(
                    packed,
                    playerCx,
                    playerCz,
                    lodDistance,
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
        long maxNormalDistanceSquared = softFrontierDistanceSquared(lodDistance);
        count = appendClusterCandidate(centerPacked, playerCx, playerCz, lodDistance, requestIds, positions, timestamps,
                allowGeneration, count, maxCount, requestWindow, now, maxNormalDistanceSquared);
        for (int dz = -1; dz <= 1 && count < maxCount && requestWindow.hasSyncCapacity(); dz++) {
            for (int dx = -1; dx <= 1 && count < maxCount && requestWindow.hasSyncCapacity(); dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long packed = PositionUtil.packPosition(centerX + dx, centerZ + dz);
                count = appendClusterCandidate(packed, playerCx, playerCz, lodDistance, requestIds, positions, timestamps,
                        allowGeneration, count, maxCount, requestWindow, now, maxNormalDistanceSquared);
            }
        }
        return count;
    }

    private int appendClusterCandidate(
            long packed,
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
            long now,
            long maxNormalDistanceSquared) {
        if (count >= maxCount || !requestWindow.hasSyncCapacity()) {
            return count;
        }
        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > lodDistance
                || distanceSquared(packed, playerCx, playerCz) > maxNormalDistanceSquared
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
        requestSendTimes.put(packed, System.nanoTime());
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
        LongOpenHashSet expired = new LongOpenHashSet();
        for (long packed : requestSendTimes.keySet()) {
            if (now - requestSendTimes.get(packed) > timeoutFor(packed)) {
                expired.add(packed);
            }
        }
        for (long packed : expired) {
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

    private long timeoutFor(long packed) {
        if (sessionConfig != null
                && sessionConfig.generationEnabled()
                && isGenerationCandidate(packed)
                && !dirtyColumns.contains(packed)) {
            return GENERATION_REQUEST_TIMEOUT_NANOS;
        }
        return SYNC_REQUEST_TIMEOUT_NANOS;
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
        generationInFlight.clear();
        dirtyRefreshInFlight.clear();
        retryAfterNanos.clear();
        retryAttempts.clear();
        nextRequestId = 0;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        scanOffsetIndex = 0;
        softFrontierRadius = 0;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        syncRequestBudget = 0.0D;
        generationRequestBudget = 0.0D;
        dirtyRefreshBudget = 0.0D;
    }

    private void resetScanCursor() {
        scanOffsetIndex = 0;
        softFrontierRadius = 0;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void ensureOrderedOffsets(int lodDistance) {
        if (orderedOffsetDistance == lodDistance) {
            return;
        }

        int side = lodDistance * 2 + 1;
        long[] offsets = new long[side * side];
        int index = 0;
        for (int dz = -lodDistance; dz <= lodDistance; dz++) {
            for (int dx = -lodDistance; dx <= lodDistance; dx++) {
                offsets[index++] = encodeOffset(dx, dz);
            }
        }
        Arrays.sort(offsets);
        orderedOffsets = offsets;
        orderedOffsetDistance = lodDistance;
        resetScanCursor();
    }

    private boolean isWithinSoftFrontier(long packed, int playerCx, int playerCz, int lodDistance) {
        return distanceSquared(packed, playerCx, playerCz) <= softFrontierDistanceSquared(lodDistance);
    }

    private long softFrontierDistanceSquared(int lodDistance) {
        int radius = Math.min(maxFrontierRadius(lodDistance), softFrontierRadius + SOFT_FRONTIER_LEAD_CHUNKS);
        return (long) radius * radius;
    }

    private void updateSoftFrontier(long distanceSquared, int lodDistance) {
        softFrontierRadius = Math.min(maxFrontierRadius(lodDistance),
                Math.max(softFrontierRadius, ceilSqrt(distanceSquared)));
    }

    private static int maxFrontierRadius(int lodDistance) {
        return ceilSqrt(2L * lodDistance * lodDistance);
    }

    private static int ceilSqrt(long value) {
        int root = (int) Math.sqrt(value);
        return (long) root * root == value ? root : root + 1;
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

    private static long offsetDistanceSquared(long offset) {
        return offset >>> 32;
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
