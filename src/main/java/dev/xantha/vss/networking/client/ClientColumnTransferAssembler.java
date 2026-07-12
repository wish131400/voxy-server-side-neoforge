package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class ClientColumnTransferAssembler {
    private final int maxLogicalColumns;
    private final long maxBytes;
    private final Map<Long, PendingTransfer> transfers = new HashMap<>();
    private final Map<RequestKey, Long> requestTransfers = new HashMap<>();
    private int activeLogicalColumns;
    private long activeBytes;

    ClientColumnTransferAssembler(int maxLogicalColumns, long maxBytes) {
        this.maxLogicalColumns = Math.max(1, maxLogicalColumns);
        this.maxBytes = Math.max(1L, maxBytes);
    }

    synchronized OfferResult offer(
            VoxelColumnS2CPayload payload,
            boolean priority,
            boolean replaceMissingSections,
            int queuedLogicalColumns,
            long queuedBytes,
            long nowNanos) {
        if (!isValidPart(payload)) {
            return OfferResult.rejected(FailedTransfer.from(payload));
        }

        PendingTransfer pending = transfers.get(payload.transferId());
        RequestKey requestKey = RequestKey.from(payload);
        if (pending == null) {
            Long conflictingTransferId = requestTransfers.get(requestKey);
            if (conflictingTransferId != null && conflictingTransferId.longValue() != payload.transferId()) {
                PendingTransfer conflicting = removeTransfer(conflictingTransferId);
                return OfferResult.rejected(conflicting != null
                        ? conflicting.failedTransfer()
                        : FailedTransfer.from(payload));
            }

            int partBytes = retainedBytes(payload);
            if (activeLogicalColumns + Math.max(0, queuedLogicalColumns) >= maxLogicalColumns
                    || exceedsByteLimit(activeBytes, Math.max(0L, queuedBytes), partBytes, maxBytes)) {
                return OfferResult.rejected(FailedTransfer.from(payload));
            }

            pending = new PendingTransfer(payload, priority, replaceMissingSections, nowNanos);
            transfers.put(payload.transferId(), pending);
            requestTransfers.put(requestKey, payload.transferId());
            activeLogicalColumns++;
        } else if (!pending.matches(payload, priority, replaceMissingSections)
                || pending.parts[payload.partIndex()] != null) {
            FailedTransfer failed = pending.failedTransfer();
            removeTransfer(payload.transferId());
            return OfferResult.rejected(failed);
        }

        int partBytes = retainedBytes(payload);
        if (exceedsByteLimit(activeBytes, Math.max(0L, queuedBytes), partBytes, maxBytes)) {
            FailedTransfer failed = pending.failedTransfer();
            removeTransfer(payload.transferId());
            return OfferResult.rejected(failed);
        }

        pending.parts[payload.partIndex()] = payload;
        pending.receivedParts++;
        pending.retainedBytes += partBytes;
        pending.lastPartNanos = nowNanos;
        activeBytes += partBytes;

        if (pending.receivedParts != pending.parts.length) {
            return OfferResult.accepted();
        }

        removeTransfer(payload.transferId());
        return OfferResult.completed(pending.assemble());
    }

    synchronized List<FailedTransfer> expireIdle(long nowNanos, long idleTimeoutNanos) {
        long timeout = Math.max(0L, idleTimeoutNanos);
        ArrayList<Long> expiredIds = new ArrayList<>();
        for (Map.Entry<Long, PendingTransfer> entry : transfers.entrySet()) {
            if (nowNanos - entry.getValue().lastPartNanos >= timeout) {
                expiredIds.add(entry.getKey());
            }
        }
        ArrayList<FailedTransfer> failed = new ArrayList<>(expiredIds.size());
        for (long transferId : expiredIds) {
            PendingTransfer removed = removeTransfer(transferId);
            if (removed != null) {
                failed.add(removed.failedTransfer());
            }
        }
        return failed;
    }

    synchronized List<FailedTransfer> invalidatePositions(
            ResourceKey<Level> dimension,
            long[] packedPositions) {
        if (packedPositions == null || packedPositions.length == 0) {
            return List.of();
        }
        ArrayList<Long> invalidIds = new ArrayList<>();
        for (Map.Entry<Long, PendingTransfer> entry : transfers.entrySet()) {
            PendingTransfer transfer = entry.getValue();
            if (!Objects.equals(dimension, transfer.dimension)) {
                continue;
            }
            long packed = packPosition(transfer.chunkX, transfer.chunkZ);
            for (long invalidPosition : packedPositions) {
                if (packed == invalidPosition) {
                    invalidIds.add(entry.getKey());
                    break;
                }
            }
        }
        return removeTransfers(invalidIds);
    }

    synchronized List<FailedTransfer> clear() {
        ArrayList<FailedTransfer> failed = new ArrayList<>(transfers.size());
        for (PendingTransfer transfer : transfers.values()) {
            failed.add(transfer.failedTransfer());
        }
        transfers.clear();
        requestTransfers.clear();
        activeLogicalColumns = 0;
        activeBytes = 0L;
        return failed;
    }

    synchronized int activeLogicalColumns() {
        return activeLogicalColumns;
    }

    synchronized long activeBytes() {
        return activeBytes;
    }

    private List<FailedTransfer> removeTransfers(List<Long> transferIds) {
        ArrayList<FailedTransfer> failed = new ArrayList<>(transferIds.size());
        for (long transferId : transferIds) {
            PendingTransfer removed = removeTransfer(transferId);
            if (removed != null) {
                failed.add(removed.failedTransfer());
            }
        }
        return failed;
    }

    private PendingTransfer removeTransfer(long transferId) {
        PendingTransfer removed = transfers.remove(transferId);
        if (removed == null) {
            return null;
        }
        requestTransfers.remove(removed.requestKey, transferId);
        activeLogicalColumns = Math.max(0, activeLogicalColumns - 1);
        activeBytes = Math.max(0L, activeBytes - removed.retainedBytes);
        return removed;
    }

    private static boolean isValidPart(VoxelColumnS2CPayload payload) {
        if (payload == null
                || !payload.hasTransferMetadata()
                || !payload.completeColumn()
                || payload.partCount() < 1
                || payload.partCount() > VoxelColumnS2CPayload.MAX_TRANSFER_PARTS
                || payload.partIndex() < 0
                || payload.partIndex() >= payload.partCount()) {
            return false;
        }
        return payload.completesRequest() || payload.replacementSectionYs().length == 0;
    }

    private static int retainedBytes(VoxelColumnS2CPayload payload) {
        long bytes = Math.max(payload.estimatedBytes(), payload.rawSectionBytesLength());
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, bytes);
    }

    private static boolean exceedsByteLimit(long active, long queued, long incoming, long limit) {
        if (active > limit - Math.min(limit, queued)) {
            return true;
        }
        long used = active + queued;
        return incoming > limit - Math.min(limit, used);
    }

    private static long packPosition(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    enum OfferStatus {
        ACCEPTED,
        COMPLETED,
        REJECTED
    }

    record OfferResult(OfferStatus status, AssembledColumn column, FailedTransfer failedTransfer) {
        private static OfferResult accepted() {
            return new OfferResult(OfferStatus.ACCEPTED, null, null);
        }

        private static OfferResult completed(AssembledColumn column) {
            return new OfferResult(OfferStatus.COMPLETED, column, null);
        }

        private static OfferResult rejected(FailedTransfer failedTransfer) {
            return new OfferResult(OfferStatus.REJECTED, null, failedTransfer);
        }
    }

    record AssembledColumn(
            int requestId,
            long transferId,
            int chunkX,
            int chunkZ,
            ResourceKey<Level> dimension,
            long columnTimestamp,
            boolean priority,
            boolean replaceMissingSections,
            List<VoxelColumnS2CPayload> parts,
            int retainedBytes) {
        VoxelColumnS2CPayload finalPart() {
            return parts.get(parts.size() - 1);
        }
    }

    record FailedTransfer(
            int requestId,
            long transferId,
            ResourceKey<Level> dimension,
            int chunkX,
            int chunkZ) {
        static FailedTransfer from(VoxelColumnS2CPayload payload) {
            return new FailedTransfer(
                    payload.requestId(),
                    payload.transferId(),
                    payload.dimension(),
                    payload.chunkX(),
                    payload.chunkZ());
        }
    }

    private static final class PendingTransfer {
        private final int requestId;
        private final long transferId;
        private final int chunkX;
        private final int chunkZ;
        private final ResourceKey<Level> dimension;
        private final long columnTimestamp;
        private final boolean completeColumn;
        private final boolean priority;
        private final boolean replaceMissingSections;
        private final VoxelColumnS2CPayload[] parts;
        private final RequestKey requestKey;
        private int receivedParts;
        private int retainedBytes;
        private long lastPartNanos;

        private PendingTransfer(
                VoxelColumnS2CPayload firstPart,
                boolean priority,
                boolean replaceMissingSections,
                long nowNanos) {
            requestId = firstPart.requestId();
            transferId = firstPart.transferId();
            chunkX = firstPart.chunkX();
            chunkZ = firstPart.chunkZ();
            dimension = firstPart.dimension();
            columnTimestamp = firstPart.columnTimestamp();
            completeColumn = firstPart.completeColumn();
            this.priority = priority;
            this.replaceMissingSections = replaceMissingSections;
            parts = new VoxelColumnS2CPayload[firstPart.partCount()];
            requestKey = RequestKey.from(firstPart);
            lastPartNanos = nowNanos;
        }

        private boolean matches(
                VoxelColumnS2CPayload part,
                boolean incomingPriority,
                boolean incomingReplaceMissingSections) {
            return requestId == part.requestId()
                    && transferId == part.transferId()
                    && chunkX == part.chunkX()
                    && chunkZ == part.chunkZ()
                    && Objects.equals(dimension, part.dimension())
                    && columnTimestamp == part.columnTimestamp()
                    && completeColumn == part.completeColumn()
                    && parts.length == part.partCount()
                    && priority == incomingPriority
                    && replaceMissingSections == incomingReplaceMissingSections;
        }

        private AssembledColumn assemble() {
            return new AssembledColumn(
                    requestId,
                    transferId,
                    chunkX,
                    chunkZ,
                    dimension,
                    columnTimestamp,
                    priority,
                    replaceMissingSections,
                    List.copyOf(Arrays.asList(parts)),
                    retainedBytes);
        }

        private FailedTransfer failedTransfer() {
            return new FailedTransfer(requestId, transferId, dimension, chunkX, chunkZ);
        }
    }

    private record RequestKey(int requestId, ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        private static RequestKey from(VoxelColumnS2CPayload payload) {
            return new RequestKey(
                    payload.requestId(),
                    payload.dimension(),
                    payload.chunkX(),
                    payload.chunkZ());
        }
    }
}
