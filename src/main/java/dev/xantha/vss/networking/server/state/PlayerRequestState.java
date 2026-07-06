package dev.xantha.vss.networking.server.state;


import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.common.BandwidthLimiter;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PlayerRequestState {
    private static final int MIN_NORMAL_QUEUE_BACKPRESSURE_COLUMNS = 64;
    private static final int NORMAL_QUEUE_BACKPRESSURE_NUMERATOR = 3;
    private static final int NORMAL_QUEUE_BACKPRESSURE_DENOMINATOR = 4;
    private static final long MIN_NORMAL_QUEUE_BACKPRESSURE_BYTES = 2L * 1024L * 1024L;

    private final Set<Integer> cancelled = new HashSet<>();
    private final Map<Integer, RequestPosition> requestPositions = new HashMap<>();
    private final Set<RequestPosition> activePositions = new HashSet<>();
    private final PlayerSendQueue sendQueue = new PlayerSendQueue();
    private final PreloadColumnFrontier preloadColumnFrontier = new PreloadColumnFrontier();
    private final PreloadRegionWindow preloadRegionWindow = new PreloadRegionWindow();
    private final ClientKnownColumnIndex clientKnownColumns = new ClientKnownColumnIndex();
    private final BandwidthLimiter bandwidthLimiter = new BandwidthLimiter(System::nanoTime);
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

    public synchronized boolean enqueue(VoxelColumnS2CPayload payload, boolean priority) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        return sendQueue.enqueue(
                payload,
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

    public synchronized QueuedPayload peekQueuedPayload(int playerCx, int playerCz) {
        return sendQueue.peek(playerCx, playerCz);
    }

    public synchronized QueuedPayload peekNormalQueuedPayload(int playerCx, int playerCz) {
        return sendQueue.peekNormal(playerCx, playerCz);
    }

    public synchronized QueuedPayload pollPriorityQueuedPayload(QueuedPayload payload) {
        return sendQueue.pollPriority(payload);
    }

    public synchronized QueuedPayload pollQueuedPayload(QueuedPayload payload) {
        return sendQueue.poll(payload);
    }

    public synchronized QueuedPayload pollNormalQueuedPayload(QueuedPayload payload) {
        return sendQueue.pollNormal(payload);
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
        int columnThreshold = backpressureThreshold(
                config.sendQueueLimitPerPlayer,
                MIN_NORMAL_QUEUE_BACKPRESSURE_COLUMNS);
        long byteThreshold = Math.max(
                MIN_NORMAL_QUEUE_BACKPRESSURE_BYTES,
                backpressureThreshold(config.sendQueueBytesLimitPerPlayer, 1L));
        return normalQueuedPayloadCount() >= columnThreshold || queuedBytes() >= byteThreshold;
    }

    public synchronized boolean canSend(long configuredLimit) {
        return bandwidthLimiter.canSend(configuredLimit);
    }

    public synchronized void recordSend(int bytes) {
        bandwidthLimiter.recordSend(bytes);
    }

    public synchronized void primeSendCredit(long configuredLimit) {
        bandwidthLimiter.primeSendCredit(configuredLimit);
    }

    public synchronized long totalBytesSent() {
        return bandwidthLimiter.totalBytesSent();
    }

    public synchronized void setDesiredBandwidth(long desiredBandwidth) {
        bandwidthLimiter.setDesiredBandwidth(desiredBandwidth);
    }

    public synchronized long desiredBandwidth() {
        return bandwidthLimiter.desiredBandwidth();
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

    private record RequestPosition(ResourceKey<Level> dimension, long packedPosition) {
    }

    public record PreloadColumn(int chunkX, int chunkZ, long timestamp) {
    }

    public record PreloadRegion(int regionX, int regionZ) {
    }

    public record QueuedPayload(VoxelColumnS2CPayload payload, int estimatedBytes, boolean priority, long sequence) {
    }

}
