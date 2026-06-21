package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;

public final class LodRequestManager {
    private static final int SCAN_INTERVAL_TICKS = 1;
    private static final long SYNC_REQUEST_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long GENERATION_REQUEST_TIMEOUT_NANOS = 300_000_000_000L;
    private static final long RATE_LIMIT_BACKOFF_NANOS = 2_000_000_000L;
    private static final long GENERATION_BACKOFF_NANOS = 10_000_000_000L;
    private static final long MAX_RETRY_BACKOFF_NANOS = 60_000_000_000L;

    private final Long2LongOpenHashMap columnTimestamps = new Long2LongOpenHashMap();
    private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();
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

    private SessionConfigS2CPayload sessionConfig;
    private ResourceKey<Level> lastDimension;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int nextRequestId;
    private int scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    private int scanRing;
    private int scanIndex;
    private double syncRequestBudget;
    private double generationRequestBudget;

    public LodRequestManager() {
        columnTimestamps.defaultReturnValue(-1L);
        positionToRequestId.defaultReturnValue(-1);
        requestIdToPosition.defaultReturnValue(Long.MIN_VALUE);
        requestSendTimes.defaultReturnValue(0L);
        retryAfterNanos.defaultReturnValue(0L);
        retryAttempts.defaultReturnValue(0);
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

    public boolean onColumnReceived(int requestId, long columnTimestamp) {
        boolean replaceMissingSections = isDirtyRefreshRequest(requestId);
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            dirtyColumns.remove(packed);
            columnTimestamps.put(packed, columnTimestamp);
            clearBackoff(packed);
        }
        return replaceMissingSections;
    }

    public void onDirtyColumns(long[] dirtyPositions) {
        for (long packed : dirtyPositions) {
            dirtyColumns.add(packed);
            deferColumn(packed);
        }
    }

    public void onColumnNotGenerated(int requestId) {
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            columnTimestamps.put(packed, 0L);
            markBackoff(packed, true);
            deferColumn(packed);
        }
    }

    public void onColumnUpToDate(int requestId) {
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            dirtyColumns.remove(packed);
            if (columnTimestamps.get(packed) <= 0L) {
                columnTimestamps.put(packed, VSSConstants.epochMillis());
            }
            clearBackoff(packed);
        }
    }

    public void onRateLimited(int requestId) {
        long packed = removeRequest(requestId);
        if (packed != Long.MIN_VALUE) {
            markBackoff(packed, isGenerationCandidate(packed));
            deferColumn(packed);
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
        int count = 0;

        if (lodDistance <= 0) {
            return;
        }

        ClientChunkCache chunkSource = level.getChunkSource();
        long now = System.nanoTime();
        count = drainDeferredColumns(level, playerCx, playerCz, lodDistance, requestIds, positions, timestamps, count, maxCount, requestWindow, now);
        if (sessionConfig.generationEnabled() && !requestWindow.canSendGeneration()) {
            if (count > 0) {
                syncRequestBudget = Math.max(0.0D, syncRequestBudget - requestWindow.syncSent);
                generationRequestBudget = Math.max(0.0D, generationRequestBudget - requestWindow.generationSent);
                VSSNetworking.sendToServer(new BatchChunkRequestC2SPayload(requestIds, positions, timestamps, count));
            }
            return;
        }

        int radius = Math.max(1, scanRing);
        if (radius > lodDistance) {
            radius = 1;
            scanIndex = 0;
        }
        int ringsChecked = 0;
        int totalRings = Math.max(1, lodDistance);
        while (count < maxCount && requestWindow.hasCapacity() && ringsChecked < totalRings) {
            int ringSize = Math.max(1, radius * 8);
            int i = Math.min(scanIndex, ringSize);
            for (; i < ringSize && count < maxCount && requestWindow.hasCapacity(); i++) {
                int[] coord = ringIndexToCoord(radius, i, playerCx, playerCz);
                long packed = PositionUtil.packPosition(coord[0], coord[1]);
                if (!shouldRequestColumn(chunkSource, packed, coord[0], coord[1], now)) {
                    continue;
                }

        boolean generationCandidate = isGenerationCandidate(packed) && !dirtyColumns.contains(packed);
                if (!requestWindow.canSend(generationCandidate)) {
                    continue;
                }

                count = appendRequest(packed, requestIds, positions, timestamps, count, generationCandidate);
                requestWindow.record(generationCandidate);
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
            VSSNetworking.sendToServer(new BatchChunkRequestC2SPayload(requestIds, positions, timestamps, count));
        }
    }

    private RequestWindow createRequestWindow() {
        int generationInFlightCount = generationInFlight.size();
        int syncInFlightCount = Math.max(0, inFlight.size() - generationInFlightCount);

        int syncConcurrencyLimit = Math.max(1, sessionConfig.syncOnLoadConcurrencyLimitPerPlayer());
        int generationConcurrencyLimit = sessionConfig.generationEnabled()
                ? Math.max(1, sessionConfig.generationConcurrencyLimitPerPlayer())
                : 0;

        int syncSlots = Math.max(0, syncConcurrencyLimit - syncInFlightCount);
        int generationSlots = Math.max(0, generationConcurrencyLimit - generationInFlightCount);

        int syncRate = Math.max(1, sessionConfig.syncOnLoadRateLimitPerPlayer());
        int generationRate = Math.max(1, sessionConfig.generationRateLimitPerPlayer());
        syncRequestBudget = Math.min(syncRate, syncRequestBudget + syncRate / 20.0D);
        generationRequestBudget = Math.min(generationRate, generationRequestBudget + generationRate / 20.0D);

        return new RequestWindow(
                Math.min(syncSlots, (int) syncRequestBudget),
                Math.min(generationSlots, (int) generationRequestBudget));
    }

    private int drainDeferredColumns(
            ClientLevel level,
            int playerCx,
            int playerCz,
            int lodDistance,
            int[] requestIds,
            long[] positions,
            long[] timestamps,
            int count,
            int maxCount,
            RequestWindow requestWindow,
            long now) {
        ClientChunkCache chunkSource = level.getChunkSource();
        int attempts = deferredQueue.size();
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
            if (!shouldRequestColumn(chunkSource, packed, cx, cz, now)) {
                continue;
            }

                boolean generationCandidate = isGenerationCandidate(packed) && !dirtyColumns.contains(packed);
            if (!requestWindow.canSend(generationCandidate)) {
                deferColumn(packed);
                continue;
            }

            count = appendRequest(packed, requestIds, positions, timestamps, count, generationCandidate);
            requestWindow.record(generationCandidate);
        }
        return count;
    }

    private boolean shouldRequestColumn(ClientChunkCache chunkSource, long packed, int cx, int cz, long now) {
        if (chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) != null || inFlight.contains(packed)) {
            return false;
        }
        if (isCoolingDown(packed, now)) {
            return false;
        }

        long timestamp = columnTimestamps.get(packed);
        if (timestamp > 0L && !dirtyColumns.contains(packed)) {
            return false;
        }
        return dirtyColumns.contains(packed) || timestamp != 0L || sessionConfig.generationEnabled();
    }

    private boolean isGenerationCandidate(long packed) {
        return sessionConfig != null && sessionConfig.generationEnabled() && columnTimestamps.get(packed) <= 0L;
    }

    private int appendRequest(long packed, int[] requestIds, long[] positions, long[] timestamps, int count, boolean generationCandidate) {
        int requestId = nextRequestId++;
        requestIds[count] = requestId;
        positions[count] = packed;
        timestamps[count] = columnTimestamps.get(packed);
        markRequest(packed, requestId, generationCandidate);
        return count + 1;
    }

    private int getEffectiveLodDistance() {
        int serverDistance = sessionConfig.lodDistanceChunks();
        int clientDistance = VSSClientConfig.CONFIG.lodDistanceChunks;
        int effective = clientDistance > 0 ? Math.min(clientDistance, serverDistance) : serverDistance;
        OptionalInt voxyDistance = ModCompat.getVoxyViewDistanceChunks();
        if (voxyDistance.isPresent() && voxyDistance.getAsInt() > 0) {
            effective = Math.min(effective, voxyDistance.getAsInt());
        }
        return effective;
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
            deferColumn(packed);
        }
    }

    private long timeoutFor(long packed) {
        if (sessionConfig != null
                && sessionConfig.generationEnabled()
                && columnTimestamps.get(packed) <= 0L
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
        if (deferredColumns.add(packed)) {
            deferredQueue.addLast(packed);
        }
    }

    private void sendCancelPacket(int requestId) {
        try {
            VSSNetworking.sendToServer(new CancelRequestC2SPayload(requestId));
        } catch (Exception ignored) {
        }
    }

    private void resetRequestState() {
        columnTimestamps.clear();
        dirtyColumns.clear();
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
        nextRequestId = 0;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        scanRing = 0;
        scanIndex = 0;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
        syncRequestBudget = 0.0D;
        generationRequestBudget = 0.0D;
    }

    private void resetScanCursor() {
        scanRing = 0;
        scanIndex = 0;
        scanTickCounter = SCAN_INTERVAL_TICKS - 1;
    }

    private static int[] ringIndexToCoord(int r, int i, int centerX, int centerZ) {
        if (r == 0) {
            return new int[] {centerX, centerZ};
        }
        int edge = i / (2 * r);
        int pos = i % (2 * r);
        return switch (edge) {
            case 0 -> new int[] {centerX - r + pos, centerZ - r};
            case 1 -> new int[] {centerX + r, centerZ - r + pos};
            case 2 -> new int[] {centerX + r - pos, centerZ + r};
            default -> new int[] {centerX - r, centerZ + r - pos};
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
        long baseDelay = generationCandidate ? GENERATION_BACKOFF_NANOS : RATE_LIMIT_BACKOFF_NANOS;
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
        private int syncSent;
        private int generationSent;

        private RequestWindow(int syncRemaining, int generationRemaining) {
            this.syncRemaining = syncRemaining;
            this.generationRemaining = generationRemaining;
        }

        private boolean hasCapacity() {
            return syncRemaining > 0 || generationRemaining > 0;
        }

        private int remaining() {
            return Math.max(0, syncRemaining) + Math.max(0, generationRemaining);
        }

        private boolean canSend(boolean generationCandidate) {
            return generationCandidate ? generationRemaining > 0 : syncRemaining > 0;
        }

        private boolean canSendGeneration() {
            return generationRemaining > 0;
        }

        private void record(boolean generationCandidate) {
            if (generationCandidate) {
                generationRemaining--;
                generationSent++;
            } else {
                syncRemaining--;
                syncSent++;
            }
        }
    }
}
