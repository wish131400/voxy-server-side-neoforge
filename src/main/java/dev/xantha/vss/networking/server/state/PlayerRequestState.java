package dev.xantha.vss.networking.server.state;


import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.common.BandwidthLimiter;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PlayerRequestState {
    private static final int MIN_NORMAL_QUEUE_BACKPRESSURE_COLUMNS = 64;
    private static final int NORMAL_QUEUE_BACKPRESSURE_NUMERATOR = 3;
    private static final int NORMAL_QUEUE_BACKPRESSURE_DENOMINATOR = 4;
    private static final long MIN_NORMAL_QUEUE_BACKPRESSURE_BYTES = 2L * 1024L * 1024L;
    private static final long MIN_BANDWIDTH_LATENCY_BACKPRESSURE_BYTES = 128L * 1024L;
    private static final long MAX_NORMAL_QUEUE_LATENCY_SECONDS = 10L;

    private final Set<Integer> cancelled = new HashSet<>();
    private final Map<Integer, RequestPosition> requestPositions = new HashMap<>();
    private final Set<RequestPosition> activePositions = new HashSet<>();
    private final PlayerSendQueue sendQueue = new PlayerSendQueue();
    private final PreloadColumnFrontier preloadColumnFrontier = new PreloadColumnFrontier();
    private final PreloadRegionWindow preloadRegionWindow = new PreloadRegionWindow();
    private final ClientKnownColumnIndex clientKnownColumns = new ClientKnownColumnIndex();
    private final BandwidthLimiter normalBandwidthLimiter = new BandwidthLimiter(System::nanoTime);
    private final BandwidthLimiter priorityBandwidthLimiter = new BandwidthLimiter(System::nanoTime);
    private int clientCapabilities;

    public synchronized void cancel(int requestId) {
        cancelled.add(requestId);
        RequestPosition position = requestPositions.remove(requestId);
        if (position != null) {
            activePositions.remove(position);
        }
    }

    public synchronized boolean consumeCancelled(int requestId) {
        boolean wasCancelled = cancelled.remove(requestId);
        if (wasCancelled) {
            clearRequest(requestId);
        }
        return wasCancelled;
    }

    public synchronized boolean beginRequest(int requestId, ResourceKey<Level> dimension, long position) {
        RequestPosition requestPosition = new RequestPosition(dimension, position);
        if (!activePositions.add(requestPosition)) {
            return false;
        }
        requestPositions.put(requestId, requestPosition);
        return true;
    }

    public synchronized void clearRequest(int requestId) {
        RequestPosition position = requestPositions.remove(requestId);
        if (position != null) {
            activePositions.remove(position);
        }
    }

    public synchronized boolean isActiveRequest(int requestId) {
        return requestId < 0 || requestPositions.containsKey(requestId);
    }

    public synchronized boolean enqueue(VoxelColumnS2CPayload payload, boolean priority) {
        return enqueue(List.of(payload), priority);
    }

    public synchronized boolean enqueue(List<VoxelColumnS2CPayload> payloads, boolean priority) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return sendQueue.enqueue(
                payloads,
                priority,
                config.sendQueueLimitPerPlayer,
                config.sendQueueBytesLimitPerPlayer,
                config.enableNetworkColumnCompression,
                this::clearRequest);
    }

    public synchronized void prepareSendOrder(int playerCx, int playerCz) {
        sendQueue.prepareOrder(playerCx, playerCz);
    }

    public synchronized QueuedPayload peekPriorityQueuedPayload(int playerCx, int playerCz) {
        return sendQueue.peekPriority(playerCx, playerCz);
    }

    public synchronized QueuedPayloadBatch peekPriorityQueuedBatch(int playerCx, int playerCz) {
        return sendQueue.peekPriorityBatch(playerCx, playerCz);
    }

    public synchronized QueuedPayload peekQueuedPayload(int playerCx, int playerCz) {
        return sendQueue.peek(playerCx, playerCz);
    }

    public synchronized QueuedPayloadBatch peekQueuedBatch(int playerCx, int playerCz) {
        return sendQueue.peekBatch(playerCx, playerCz);
    }

    public synchronized QueuedPayload peekNormalQueuedPayload(int playerCx, int playerCz) {
        return sendQueue.peekNormal(playerCx, playerCz);
    }

    public synchronized QueuedPayloadBatch peekNormalQueuedBatch(int playerCx, int playerCz) {
        return sendQueue.peekNormalBatch(playerCx, playerCz);
    }

    public synchronized QueuedPayload pollPriorityQueuedPayload(QueuedPayload payload) {
        return sendQueue.pollPriority(payload);
    }

    public synchronized QueuedPayloadBatch pollPriorityQueuedBatch(QueuedPayloadBatch batch) {
        return sendQueue.pollPriorityBatch(batch);
    }

    public synchronized QueuedPayload pollQueuedPayload(QueuedPayload payload) {
        return sendQueue.poll(payload);
    }

    public synchronized QueuedPayloadBatch pollQueuedBatch(QueuedPayloadBatch batch) {
        return sendQueue.pollBatch(batch);
    }

    public synchronized QueuedPayload pollNormalQueuedPayload(QueuedPayload payload) {
        return sendQueue.pollNormal(payload);
    }

    public synchronized QueuedPayloadBatch pollNormalQueuedBatch(QueuedPayloadBatch batch) {
        return sendQueue.pollNormalBatch(batch);
    }

    public synchronized QueuedPayload consumeQueuedPayload(QueuedPayloadBatch batch) {
        return sendQueue.consumeBatchPayload(batch);
    }

    public synchronized int queuedPayloadCount() {
        return sendQueue.queuedPayloadCount();
    }

    public synchronized int priorityQueuedPayloadCount() {
        return sendQueue.priorityQueuedPayloadCount();
    }

    public synchronized int normalQueuedPayloadCount() {
        return sendQueue.normalQueuedPayloadCount();
    }

    public synchronized long queuedBytes() {
        return sendQueue.queuedBytes();
    }

    public synchronized void addPreloadColumns(ArrayList<PersistentColumnLodStore.ExistingColumn> columns) {
        preloadColumnFrontier.addColumns(columns);
    }

    public synchronized void addPreloadColumn(PreloadColumn column) {
        preloadColumnFrontier.addColumn(column);
    }

    public synchronized PreloadColumn pollFrontierPreloadColumn(int playerCx, int playerCz, int ringSlack) {
        return preloadColumnFrontier.pollFrontierColumn(playerCx, playerCz, ringSlack);
    }

    public synchronized void beginPreloadColumnRead() {
        preloadColumnFrontier.beginColumnRead();
    }

    public synchronized void finishPreloadColumnRead() {
        preloadColumnFrontier.finishColumnRead();
    }

    public synchronized void beginPreloadRegionScan(int minimumChunkDistance) {
        preloadColumnFrontier.beginRegionScan(minimumChunkDistance);
    }

    public synchronized void finishPreloadRegionScan(int minimumChunkDistance) {
        preloadColumnFrontier.finishRegionScan(minimumChunkDistance);
    }

    public synchronized int preloadColumnCount() {
        return preloadColumnFrontier.count();
    }

    public synchronized void updateClientKnownColumns(ResourceKey<Level> dimension, RegionPresenceC2SPayload payload) {
        clientKnownColumns.updateFromPresence(dimension, payload);
    }

    public synchronized void markClientKnownColumn(ResourceKey<Level> dimension, long packed, long timestamp) {
        clientKnownColumns.markKnown(dimension, packed, timestamp);
    }

    public synchronized boolean isClientKnownCurrent(ResourceKey<Level> dimension, int cx, int cz, long serverTimestamp) {
        return clientKnownColumns.isCurrent(dimension, cx, cz, serverTimestamp);
    }

    public synchronized void resetPreloadRegions(ResourceKey<Level> dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        preloadRegionWindow.reset(dimension, centerRegionX, centerRegionZ, maxRegionRing);
    }

    public synchronized void updatePreloadRegions(ResourceKey<Level> dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        preloadRegionWindow.update(dimension, centerRegionX, centerRegionZ, maxRegionRing);
    }

    public synchronized PreloadRegion pollPreloadRegion() {
        return preloadRegionWindow.poll();
    }

    public synchronized int preloadRegionCount() {
        return preloadRegionWindow.count();
    }

    public synchronized boolean shouldBackpressureNormalRequests() {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return shouldBackpressureNormalRequests(
                normalQueuedPayloadCount(),
                queuedBytes(),
                config.sendQueueLimitPerPlayer,
                config.sendQueueBytesLimitPerPlayer,
                config.bandwidthBytesPerSecond(),
                desiredBandwidth());
    }

    static boolean shouldBackpressureNormalRequests(
            int normalQueuedPayloadCount,
            long queuedBytes,
            int sendQueueLimit,
            long sendQueueBytesLimit,
            long configuredBandwidth,
            long desiredBandwidth) {
        int columnThreshold = backpressureThreshold(
                sendQueueLimit,
                MIN_NORMAL_QUEUE_BACKPRESSURE_COLUMNS);
        long configuredByteThreshold = Math.max(
                MIN_NORMAL_QUEUE_BACKPRESSURE_BYTES,
                backpressureThreshold(sendQueueBytesLimit, 1L));
        long latencyByteThreshold = bandwidthLatencyBackpressureThreshold(configuredBandwidth, desiredBandwidth);
        long byteThreshold = Math.min(configuredByteThreshold, latencyByteThreshold);
        return normalQueuedPayloadCount >= columnThreshold || queuedBytes >= byteThreshold;
    }

    public synchronized boolean canSend(long configuredLimit) {
        return canSend(false, configuredLimit);
    }

    public synchronized boolean canSend(boolean priority, long configuredLimit) {
        return bandwidthLimiter(priority).canSend(configuredLimit);
    }

    public synchronized void recordSend(int wireBytes) {
        recordSend(false, wireBytes);
    }

    public synchronized void recordSend(boolean priority, int wireBytes) {
        bandwidthLimiter(priority).recordSend(wireBytes);
    }

    public synchronized void primeSendCredit(long configuredLimit) {
        normalBandwidthLimiter.primeSendCredit(configuredLimit);
        priorityBandwidthLimiter.primeSendCredit(configuredLimit);
    }

    public synchronized long totalBytesSent() {
        return normalBandwidthLimiter.totalBytesSent() + priorityBandwidthLimiter.totalBytesSent();
    }

    public synchronized long sendCreditBytes() {
        return normalSendCreditBytes();
    }

    public synchronized long normalSendCreditBytes() {
        return normalBandwidthLimiter.availableBytes();
    }

    public synchronized long prioritySendCreditBytes() {
        return priorityBandwidthLimiter.availableBytes();
    }

    public synchronized void setDesiredBandwidth(long desiredBandwidth) {
        normalBandwidthLimiter.setDesiredBandwidth(desiredBandwidth);
        priorityBandwidthLimiter.setDesiredBandwidth(desiredBandwidth);
    }

    public synchronized long desiredBandwidth() {
        return normalBandwidthLimiter.desiredBandwidth();
    }

    public synchronized void setClientCapabilities(int clientCapabilities) {
        this.clientCapabilities = clientCapabilities;
    }

    public synchronized boolean supportsZstdColumns() {
        return (clientCapabilities & dev.xantha.vss.common.VSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0;
    }

    public synchronized void clearAll() {
        cancelled.clear();
        requestPositions.clear();
        activePositions.clear();
        sendQueue.clear();
        preloadColumnFrontier.clear();
        preloadRegionWindow.clear();
        clientKnownColumns.clear();
    }

    private static int backpressureThreshold(int limit, int minimum) {
        int safeLimit = Math.max(1, limit);
        int threshold = Math.max(1, safeLimit * NORMAL_QUEUE_BACKPRESSURE_NUMERATOR / NORMAL_QUEUE_BACKPRESSURE_DENOMINATOR);
        return Math.min(safeLimit, Math.max(minimum, threshold));
    }

    private static long backpressureThreshold(long limit, long minimum) {
        long safeLimit = Math.max(1L, limit);
        long threshold = Math.max(1L, safeLimit * NORMAL_QUEUE_BACKPRESSURE_NUMERATOR / NORMAL_QUEUE_BACKPRESSURE_DENOMINATOR);
        return Math.min(safeLimit, Math.max(minimum, threshold));
    }

    private static long bandwidthLatencyBackpressureThreshold(long configuredLimit, long desiredBandwidth) {
        long effectiveLimit = Math.min(Math.max(1L, configuredLimit), Math.max(1L, desiredBandwidth));
        if (effectiveLimit >= Long.MAX_VALUE / Math.max(1L, MAX_NORMAL_QUEUE_LATENCY_SECONDS)) {
            return Long.MAX_VALUE;
        }
        return Math.max(
                MIN_BANDWIDTH_LATENCY_BACKPRESSURE_BYTES,
                effectiveLimit * MAX_NORMAL_QUEUE_LATENCY_SECONDS);
    }

    private BandwidthLimiter bandwidthLimiter(boolean priority) {
        return priority ? priorityBandwidthLimiter : normalBandwidthLimiter;
    }

    private record RequestPosition(ResourceKey<Level> dimension, long packedPosition) {
    }

    public record PreloadColumn(int chunkX, int chunkZ, long timestamp) {
    }

    public record PreloadRegion(int regionX, int regionZ) {
    }

    public record QueuedPayload(
            VoxelColumnS2CPayload payload,
            int wireBytes,
            int rawBytes,
            boolean priority,
            long sequence,
            long queuedNanos,
            long queuedBytesAheadAtEnqueue) {
    }

    public static final class QueuedPayloadBatch {
        private final List<QueuedPayload> payloads;
        private final boolean priority;
        private final long sequence;
        private final long queuedNanos;
        private final int wireBytes;
        private final int rawBytes;
        private final long queuedBytesAheadAtEnqueue;
        private int nextPayloadIndex;
        private int consumedWireBytes;
        private int consumedRawBytes;

        public QueuedPayloadBatch(
                List<QueuedPayload> payloads,
                boolean priority,
                long sequence,
                long queuedNanos,
                int wireBytes,
                int rawBytes,
                long queuedBytesAheadAtEnqueue) {
            this.payloads = payloads;
            this.priority = priority;
            this.sequence = sequence;
            this.queuedNanos = queuedNanos;
            this.wireBytes = wireBytes;
            this.rawBytes = rawBytes;
            this.queuedBytesAheadAtEnqueue = queuedBytesAheadAtEnqueue;
        }

        public List<QueuedPayload> payloads() {
            return payloads;
        }

        public boolean priority() {
            return priority;
        }

        public long sequence() {
            return sequence;
        }

        public long queuedNanos() {
            return queuedNanos;
        }

        public int wireBytes() {
            return Math.max(0, wireBytes - consumedWireBytes);
        }

        public int rawBytes() {
            return Math.max(0, rawBytes - consumedRawBytes);
        }

        public long queuedBytesAheadAtEnqueue() {
            return queuedBytesAheadAtEnqueue;
        }

        public int payloadCount() {
            return Math.max(0, payloads.size() - nextPayloadIndex);
        }

        public boolean hasSentPayloads() {
            return nextPayloadIndex > 0;
        }

        public QueuedPayload firstPayload() {
            return payloads.get(0);
        }

        public QueuedPayload nextPayload() {
            return nextPayloadIndex < payloads.size() ? payloads.get(nextPayloadIndex) : null;
        }

        QueuedPayload consumeNextPayload() {
            QueuedPayload payload = nextPayload();
            if (payload == null) {
                return null;
            }
            nextPayloadIndex++;
            consumedWireBytes += payload.wireBytes();
            consumedRawBytes += payload.rawBytes();
            return payload;
        }

        public int requestId() {
            return firstPayload().payload().requestId();
        }

        public VoxelColumnS2CPayload completingPayload() {
            for (QueuedPayload payload : payloads) {
                if (payload.payload().completesRequest()) {
                    return payload.payload();
                }
            }
            return null;
        }
    }

}
