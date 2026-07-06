package dev.xantha.vss.networking.server.request;


import dev.xantha.vss.networking.server.diagnostics.ServerRequestStats;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ColumnRequestBatchHandler {
    private final PlayerRequestRegistry playerRegistry;
    private final ColumnLodCache columnCache;
    private final ColumnStorageReadPipeline storageReadPipeline;
    private final ServerRequestStats requestStats;
    private final DiskTaskRuntime diskRuntime;
    private final long diagnosticIntervalNanos;
    private volatile long lastRequestDiagnosticNanos;

    public ColumnRequestBatchHandler(
            PlayerRequestRegistry playerRegistry,
            ColumnLodCache columnCache,
            ColumnStorageReadPipeline storageReadPipeline,
            ServerRequestStats requestStats,
            DiskTaskRuntime diskRuntime,
            long diagnosticIntervalNanos) {
        this.playerRegistry = playerRegistry;
        this.columnCache = columnCache;
        this.storageReadPipeline = storageReadPipeline;
        this.requestStats = requestStats;
        this.diskRuntime = diskRuntime;
        this.diagnosticIntervalNanos = diagnosticIntervalNanos;
    }

    public void handle(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        PlayerRequestState state = playerRegistry.get(player.getUUID());
        if (state == null) {
            return;
        }

        ServerLevel level = player.serverLevel();
        BatchResponseAccumulator responses = new BatchResponseAccumulator(payload.count());
        logRequestBatch(player, payload.count());

        for (int i = 0; i < payload.count(); i++) {
            handleColumnRequest(player, state, level, payload, responses, i);
        }

        if (responses.hasResponses()) {
            VSSNetworking.sendToPlayer(player, responses.toPayload());
        }
    }

    private void handleColumnRequest(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            BatchChunkRequestC2SPayload payload,
            BatchResponseAccumulator responses,
            int index) {
        requestStats.recordColumnRequest();
        int requestId = payload.requestIds()[index];
        if (state.consumeCancelled(requestId)) {
            return;
        }

        long packed = payload.packedPositions()[index];
        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        logFirstColumn(player, payload.count(), index, cx, cz);

        if (!state.beginRequest(requestId, level.dimension(), packed)) {
            requestStats.recordDuplicateRequest();
            return;
        }
        if (!isWithinRequestDistance(player, cx, cz)) {
            requestStats.recordDistanceRejectedRequest();
            responses.add(VSSConstants.RESPONSE_RATE_LIMITED, requestId);
            state.clearRequest(requestId);
            return;
        }

        long clientTimestamp = payload.clientTimestamps()[index];
        if (clientTimestamp > 0L) {
            state.markClientKnownColumn(level.dimension(), packed, clientTimestamp);
        }
        boolean allowGeneration = index < payload.allowGeneration().length && payload.allowGeneration()[index];
        long dirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), cx, cz);
        boolean priorityRefresh = clientTimestamp > 0L;
        if (!priorityRefresh && state.shouldBackpressureNormalRequests()) {
            responses.add(VSSConstants.RESPONSE_BACKPRESSURE, requestId);
            state.clearRequest(requestId);
            return;
        }
        if (tryServeCachedColumn(player, state, level, responses, requestId, cx, cz, clientTimestamp, dirtyTimestamp, priorityRefresh)) {
            return;
        }

        long columnTimestamp = Math.max(VSSConstants.columnVersion(), dirtyTimestamp);
        if (clientTimestamp >= columnTimestamp) {
            requestStats.recordUpToDateResponse();
            responses.add(VSSConstants.RESPONSE_UP_TO_DATE, requestId);
            state.clearRequest(requestId);
            return;
        }

        submitColumnWork(player, state, level, requestId, cx, cz, columnTimestamp, dirtyTimestamp, allowGeneration, priorityRefresh, index);
    }

    private boolean tryServeCachedColumn(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            BatchResponseAccumulator responses,
            int requestId,
            int cx,
            int cz,
            long clientTimestamp,
            long dirtyTimestamp,
            boolean priorityRefresh) {
        ColumnLodCache.Entry cachedColumn = columnCache.get(level.dimension(), cx, cz);
        if (cachedColumn != null && !cachedColumn.completeColumn()) {
            columnCache.invalidate(level.dimension(), cx, cz);
            cachedColumn = null;
        }
        if (cachedColumn == null) {
            return false;
        }

        long requiredTimestamp = Math.max(cachedColumn.timestamp(), dirtyTimestamp);
        if (clientTimestamp >= requiredTimestamp) {
            requestStats.recordUpToDateResponse();
            responses.add(VSSConstants.RESPONSE_UP_TO_DATE, requestId);
            state.clearRequest(requestId);
            return true;
        }
        if (dirtyTimestamp > cachedColumn.timestamp()) {
            columnCache.invalidate(level.dimension(), cx, cz);
            return false;
        }

        requestStats.recordCacheHit();
        VSSServerNetworking.queueColumn(player, state, new VoxelColumnS2CPayload(
                requestId,
                level.dimension(),
                cachedColumn.columnData().withColumnStamp(requiredTimestamp)), priorityRefresh);
        return true;
    }

    private void submitColumnWork(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            int requestId,
            int cx,
            int cz,
            long columnTimestamp,
            long dirtyTimestamp,
            boolean allowGeneration,
            boolean priorityRefresh,
            int index) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            if (storageReadPipeline.submitLoadedColumn(player, state, level, chunk, requestId, cx, cz, columnTimestamp, priorityRefresh)) {
                if (index == 0) {
                    VSSLogger.debug("Submitted loaded column for " + cx + "," + cz);
                }
                return;
            }
            storageReadPipeline.submitStorageRead(
                    player,
                    state,
                    requestId,
                    cx,
                    cz,
                    columnTimestamp,
                    dirtyTimestamp,
                    true,
                    allowGeneration,
                    priorityRefresh);
            return;
        }

        if (allowGeneration && index == 0) {
            VSSLogger.debug("Chunk not loaded at " + cx + "," + cz + ", allowGeneration=" + allowGeneration);
        }
        storageReadPipeline.submitStorageRead(
                player,
                state,
                requestId,
                cx,
                cz,
                columnTimestamp,
                dirtyTimestamp,
                false,
                allowGeneration,
                priorityRefresh);
    }

    private static boolean isWithinRequestDistance(ServerPlayer player, int cx, int cz) {
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER;
        return PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= maxDistance;
    }

    private static void logFirstColumn(ServerPlayer player, int count, int index, int cx, int cz) {
        if (index == 0) {
            VSSLogger.debug("Processing batch request from " + player.getGameProfile().getName()
                    + ": count=" + count + ", first chunk=" + cx + "," + cz);
        }
    }

    private void logRequestBatch(ServerPlayer player, int count) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastRequestDiagnosticNanos < diagnosticIntervalNanos) {
            return;
        }
        lastRequestDiagnosticNanos = now;
        ServerRequestStats.Snapshot stats = requestStats.snapshot();
        VSSLogger.debug("LOD requests received from " + player.getGameProfile().getName()
                + ": batch=" + count
                + ", total=" + stats.columnRequests()
                + ", diskPending=" + diskRuntime.pendingReads()
                + ", cacheHits=" + stats.cacheHits()
                + ", diskHits=" + stats.diskReadHits()
                + ", diskMisses=" + stats.diskReadMisses()
                + ", distanceRejected=" + stats.distanceRejectedRequests());
    }
}
