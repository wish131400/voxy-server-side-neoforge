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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class LodRequestManager {
    private static final int SCAN_INTERVAL_TICKS = 1;
    private static final long SYNC_REQUEST_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long GENERATION_REQUEST_TIMEOUT_NANOS = 300_000_000_000L;
    private static final long DIRTY_REFRESH_BACKOFF_NANOS = 250_000_000L;
    private static final long RATE_LIMIT_BACKOFF_NANOS = 2_000_000_000L;
    private static final long GENERATION_BACKOFF_NANOS = 10_000_000_000L;
    private static final long MAX_RETRY_BACKOFF_NANOS = 60_000_000_000L;
    private static final int DIRTY_REFRESH_RATE_LIMIT = 1024;
    private static final int DIRTY_REFRESH_CONCURRENCY_LIMIT = 128;
    private static final int MAX_SCAN_CANDIDATES_PER_TICK = 4096;
    private static final int MAX_DEFERRED_CANDIDATES_PER_TICK = 2048;
    private static final int MAX_DEFERRED_COLUMNS = 65536;
    private static final long REQUEST_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final int NEAR_REPAIR_INTERVAL_TICKS = 20;
    private static final int NEAR_REPAIR_COLUMNS_PER_INTERVAL = 64;
    private static final int NEAR_REPAIR_MAX_RADIUS = 128;
    private static final long NEAR_REPAIR_COLUMN_COOLDOWN_NANOS = 60_000_000_000L;

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
    private final Long2LongOpenHashMap nearRepairAfterNanos = new Long2LongOpenHashMap();

    private SessionConfigS2CPayload sessionConfig;
    private ResourceKey<Level> lastDimension;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int nextRequestId;
    private int scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    private int scanRing;
    private int scanIndex;
    private int nearRepairTickCounter;
    private int nearRepairRing;
    private int nearRepairIndex;
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
        nearRepairAfterNanos.defaultReturnValue(0L);
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
            resetNearRepairCursor();
            lastPlayerChunkX = playerCx;
            lastPlayerChunkZ = playerCz;
        }
        timeoutSweep();
        scheduleNearRepair(playerCx, playerCz, getEffectiveLodDistance());

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
            long dirtyTimestamp = i < dirtyTimestamps.length ? dirtyTimestamps[i] : VSSConstants.epochMillis();
            long existingTimestamp = dirtyColumnTimestamps.get(packed);
            if (dirtyTimestamp > existingTimestamp) {
                dirtyColumnTimestamps.put(packed, dirtyTimestamp);
            }
            dirtyColumns.add(packed);
            clearBackoff(packed);
            deferColumn(packed, true);
        }
    }

    public void onColumnNotGenerated(int requestId) {
        boolean generationRequest = isGenerationRequest(requestId);
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            columnTimestamps.put(packed, 0L);
            if (generationRequest) {
                markBackoff(packed, true);
            } else {
                clearBackoff(packed);
            }
            deferredColumns.remove(packed);
            deferColumn(packed, dirtyColumns.contains(packed));
        }
    }

    public void onColumnUpToDate(int requestId) {
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            long requiredTimestamp = dirtyColumnTimestamps.get(packed);
            long localTimestamp = columnTimestamps.get(packed);
            boolean dirtySatisfied = requiredTimestamp <= 0L || localTimestamp >= requiredTimestamp;
            if (dirtySatisfied) {
                dirtyColumns.remove(packed);
                dirtyColumnTimestamps.remove(packed);
            }
            if (columnTimestamps.get(packed) <= 0L && dirtySatisfied) {
                columnTimestamps.put(packed, VSSConstants.epochMillis());
            }
            clearBackoff(packed);
            if (!dirtySatisfied) {
                dirtyColumns.add(packed);
                deferredColumns.remove(packed);
                deferColumn(packed, true);
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

        long now = System.nanoTime();
        count = drainDeferredColumns(playerCx, playerCz, lodDistance, requestIds, positions, timestamps, allowGeneration, count, maxCount, requestWindow, now);

        int radius = Math.max(1, scanRing);
        if (radius > lodDistance) {
            radius = 1;
            scanIndex = 0;
        }
        int scannedCandidates = 0;
        int ringsChecked = 0;
        int totalRings = Math.max(1, lodDistance);
        while (count < maxCount
                && requestWindow.hasCapacity()
                && ringsChecked < totalRings
                && scannedCandidates < MAX_SCAN_CANDIDATES_PER_TICK) {
            int ringSize = Math.max(1, radius * 8);
            int i = Math.min(scanIndex, ringSize);
            for (; i < ringSize
                    && count < maxCount
                    && requestWindow.hasCapacity()
                    && scannedCandidates < MAX_SCAN_CANDIDATES_PER_TICK; i++, scannedCandidates++) {
                long packed = ringIndexToPackedCoord(radius, i, playerCx, playerCz);
                if (!shouldRequestColumn(packed, now)) {
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
            if (i >= ringSize) {
                radius++;
                scanIndex = 0;
                ringsChecked++;
                if (radius > lodDistance) {
                    radius = 1;
                }
            } else {
                scanIndex = i;
                break;
            }
        }
        scanRing = radius;

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
        while (count < maxCount && requestWindow.hasCapacity() && attempts-- > 0 && !deferredQueue.isEmpty()) {
            long packed = deferredQueue.removeFirst();
            if (!deferredColumns.remove(packed)) {
                continue;
            }

            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > lodDistance) {
                continue;
            }
            if (isCoolingDown(packed, now)) {
                deferColumn(packed);
                continue;
            }
            if (!shouldRequestColumn(packed, now)) {
                continue;
            }

            boolean dirtyRefresh = dirtyColumns.contains(packed);
            boolean generationCandidate = !dirtyRefresh && isGenerationCandidate(packed);
            if (!requestWindow.canSend(dirtyRefresh, generationCandidate)) {
                deferColumn(packed, dirtyRefresh);
                continue;
            }

            count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, generationCandidate);
            requestWindow.record(dirtyRefresh, generationCandidate);
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
        count = appendClusterCandidate(centerPacked, playerCx, playerCz, lodDistance, requestIds, positions, timestamps,
                allowGeneration, count, maxCount, requestWindow, now);
        for (int dz = -1; dz <= 1 && count < maxCount && requestWindow.hasCapacity(); dz++) {
            for (int dx = -1; dx <= 1 && count < maxCount && requestWindow.hasCapacity(); dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long packed = PositionUtil.packPosition(centerX + dx, centerZ + dz);
                count = appendClusterCandidate(packed, playerCx, playerCz, lodDistance, requestIds, positions, timestamps,
                        allowGeneration, count, maxCount, requestWindow, now);
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
            long now) {
        if (count >= maxCount || !requestWindow.hasCapacity()) {
            return count;
        }
        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > lodDistance || !shouldRequestColumn(packed, now)) {
            return count;
        }

        boolean dirtyRefresh = dirtyColumns.contains(packed);
        boolean generationCandidate = !dirtyRefresh && isGenerationCandidate(packed);
        if (!requestWindow.canSend(dirtyRefresh, generationCandidate)) {
            return count;
        }

        count = appendRequest(packed, requestIds, positions, timestamps, allowGeneration, count, generationCandidate);
        requestWindow.record(dirtyRefresh, generationCandidate);
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
        return dirty || timestamp != 0L || sessionConfig.generationEnabled();
    }

    private void scheduleNearRepair(int playerCx, int playerCz, int lodDistance) {
        if (++nearRepairTickCounter < NEAR_REPAIR_INTERVAL_TICKS || lodDistance <= 0) {
            return;
        }
        nearRepairTickCounter = 0;

        int repairRadius = Math.min(lodDistance, NEAR_REPAIR_MAX_RADIUS);
        if (repairRadius <= 0) {
            return;
        }

        long now = System.nanoTime();
        int radius = Math.max(1, nearRepairRing);
        if (radius > repairRadius) {
            radius = 1;
            nearRepairIndex = 0;
        }

        int scheduled = 0;
        int ringsChecked = 0;
        while (scheduled < NEAR_REPAIR_COLUMNS_PER_INTERVAL && ringsChecked < repairRadius) {
            int ringSize = Math.max(1, radius * 8);
            int i = Math.min(nearRepairIndex, ringSize);
            for (; i < ringSize && scheduled < NEAR_REPAIR_COLUMNS_PER_INTERVAL; i++) {
                long packed = ringIndexToPackedCoord(radius, i, playerCx, playerCz);
                if (!shouldRepairKnownColumn(packed, now)) {
                    continue;
                }

                scheduled += scheduleNearRepairCluster(packed, playerCx, playerCz, repairRadius, now,
                        NEAR_REPAIR_COLUMNS_PER_INTERVAL - scheduled);
            }

            if (i >= ringSize) {
                radius++;
                nearRepairIndex = 0;
                ringsChecked++;
                if (radius > repairRadius) {
                    radius = 1;
                }
            } else {
                nearRepairIndex = i;
                break;
            }
        }
        nearRepairRing = radius;
    }

    private int scheduleNearRepairCluster(long centerPacked, int playerCx, int playerCz, int repairRadius, long now, int remaining) {
        int scheduled = 0;
        int centerX = PositionUtil.unpackX(centerPacked);
        int centerZ = PositionUtil.unpackZ(centerPacked);
        scheduled += scheduleNearRepairColumn(centerPacked, playerCx, playerCz, repairRadius, now);
        for (int dz = -1; dz <= 1 && scheduled < remaining; dz++) {
            for (int dx = -1; dx <= 1 && scheduled < remaining; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                scheduled += scheduleNearRepairColumn(
                        PositionUtil.packPosition(centerX + dx, centerZ + dz),
                        playerCx,
                        playerCz,
                        repairRadius,
                        now);
            }
        }
        return scheduled;
    }

    private int scheduleNearRepairColumn(long packed, int playerCx, int playerCz, int repairRadius, long now) {
        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > repairRadius || !shouldRepairKnownColumn(packed, now)) {
            return 0;
        }

        columnTimestamps.remove(packed);
        clearBackoff(packed);
        nearRepairAfterNanos.put(packed, now + NEAR_REPAIR_COLUMN_COOLDOWN_NANOS);
        deferColumn(packed, true);
        return 1;
    }

    private boolean shouldRepairKnownColumn(long packed, long now) {
        if (columnTimestamps.get(packed) <= 0L || dirtyColumns.contains(packed) || inFlight.contains(packed)) {
            return false;
        }
        if (deferredColumns.contains(packed) || isCoolingDown(packed, now)) {
            return false;
        }
        long repairAfter = nearRepairAfterNanos.get(packed);
        return repairAfter <= 0L || repairAfter <= now;
    }

    private void forgetColumn(ResourceKey<Level> dimension, int cx, int cz, boolean applyBackoff) {
        if (sessionConfig == null || !sessionConfig.enabled() || !isActiveDimension(dimension)) {
            return;
        }

        long packed = PositionUtil.packPosition(cx, cz);
        columnTimestamps.remove(packed);
        nearRepairAfterNanos.remove(packed);
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
        return sessionConfig != null && sessionConfig.generationEnabled() && columnTimestamps.get(packed) == 0L;
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
            deferredColumns.remove(packed);
            retryAfterNanos.remove(packed);
            retryAttempts.remove(packed);
            nearRepairAfterNanos.remove(packed);
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
        positionToRequestId.clear();
        requestIdToPosition.clear();
        requestSendTimes.clear();
        generationInFlight.clear();
        dirtyRefreshInFlight.clear();
        retryAfterNanos.clear();
        retryAttempts.clear();
        nearRepairAfterNanos.clear();
        nextRequestId = 0;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        scanRing = 0;
        scanIndex = 0;
        nearRepairRing = 0;
        nearRepairIndex = 0;
        nearRepairTickCounter = 0;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        syncRequestBudget = 0.0D;
        generationRequestBudget = 0.0D;
        dirtyRefreshBudget = 0.0D;
    }

    private void resetScanCursor() {
        scanRing = 0;
        scanIndex = 0;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private void resetNearRepairCursor() {
        nearRepairRing = 0;
        nearRepairIndex = 0;
        nearRepairTickCounter = NEAR_REPAIR_INTERVAL_TICKS - 1;
    }

    private static long ringIndexToPackedCoord(int r, int i, int centerX, int centerZ) {
        if (r == 0) {
            return PositionUtil.packPosition(centerX, centerZ);
        }
        int edge = i / (2 * r);
        int pos = i % (2 * r);
        return switch (edge) {
            case 0 -> PositionUtil.packPosition(centerX - r + pos, centerZ - r);
            case 1 -> PositionUtil.packPosition(centerX + r, centerZ - r + pos);
            case 2 -> PositionUtil.packPosition(centerX + r - pos, centerZ + r);
            default -> PositionUtil.packPosition(centerX - r, centerZ + r - pos);
        };
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
