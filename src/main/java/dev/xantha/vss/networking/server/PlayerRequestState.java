package dev.xantha.vss.networking.server;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class PlayerRequestState {
    private static final int MIN_NORMAL_QUEUE_BACKPRESSURE_COLUMNS = 8;
    private static final long MIN_NORMAL_QUEUE_BACKPRESSURE_BYTES = 256L * 1024L;
    private static final long NORMAL_QUEUE_BACKPRESSURE_SECONDS = 2L;
    private static final long ESTIMATED_NORMAL_COLUMN_BYTES = 5L * 1024L;
    private static final long MAX_PRIMED_SEND_CREDIT_BYTES = 128L * 1024L;
    private static final long PRELOAD_REGION_RETAIN_NANOS = 3_000_000_000L;

    private final Set<Integer> cancelled = new HashSet<>();
    private final Map<Integer, RequestPosition> requestPositions = new HashMap<>();
    private final Set<RequestPosition> activePositions = new HashSet<>();
    private final Queue<QueuedPayload> prioritySendQueue = new ArrayDeque<>();
    private final Queue<QueuedPayload> sendQueue = new ArrayDeque<>();
    private final Queue<PreloadColumn> preloadColumns = new ArrayDeque<>();
    private final Set<Long> preloadPositions = new HashSet<>();
    private final Queue<PreloadRegion> preloadRegions = new ArrayDeque<>();
    private final Set<Long> queuedPreloadRegionKeys = new HashSet<>();
    private final Set<Long> coveredPreloadRegionKeys = new HashSet<>();
    private final Map<Long, Long> retainedPreloadRegionDeadlines = new HashMap<>();
    private final Map<ResourceKey<Level>, Long2LongOpenHashMap> clientKnownColumns = new HashMap<>();
    private int priorityQueuedPayloads;
    private long queuedBytes;
    private long desiredBandwidth = Long.MAX_VALUE;
    private long availableBytes;
    private long lastRefillNanos = System.nanoTime();
    private long totalBytesSent;
    private int clientCapabilities;
    private int orderedForPlayerCx = Integer.MIN_VALUE;
    private int orderedForPlayerCz = Integer.MIN_VALUE;
    private long nextQueueSequence;
    private boolean queueOrderDirty = true;
    private ResourceKey<Level> preloadDimension;
    private boolean preloadWindowInitialized;
    private int preloadCenterRegionX;
    private int preloadCenterRegionZ;
    private int preloadMaxRegionRing;
    private int preloadMinRegionX;
    private int preloadMaxRegionX;
    private int preloadMinRegionZ;
    private int preloadMaxRegionZ;

    void cancel(int requestId) {
        cancelled.add(requestId);
        RequestPosition position = requestPositions.remove(requestId);
        if (position != null) {
            activePositions.remove(position);
        }
    }

    boolean consumeCancelled(int requestId) {
        boolean wasCancelled = cancelled.remove(requestId);
        if (wasCancelled) {
            clearRequest(requestId);
        }
        return wasCancelled;
    }

    boolean beginRequest(int requestId, ResourceKey<Level> dimension, long position) {
        RequestPosition requestPosition = new RequestPosition(dimension, position);
        if (!activePositions.add(requestPosition)) {
            return false;
        }
        requestPositions.put(requestId, requestPosition);
        return true;
    }

    void clearRequest(int requestId) {
        RequestPosition position = requestPositions.remove(requestId);
        if (position != null) {
            activePositions.remove(position);
        }
    }

    boolean enqueue(VoxelColumnS2CPayload payload, boolean priority) {
        int estimatedBytes = payload.estimatedBytes();
        VSSServerConfig config = VSSServerConfig.CONFIG;
        int currentQueueSize = queuedPayloadCount();
        if (currentQueueSize >= config.sendQueueLimitPerPlayer
                || queuedBytes + estimatedBytes > config.sendQueueBytesLimitPerPlayer) {
            clearRequest(payload.requestId());
            if (currentQueueSize > config.sendQueueLimitPerPlayer / 2) {
                trimQueue();
            }
            return false;
        }

        if (priority) {
            prioritySendQueue.add(new QueuedPayload(payload, estimatedBytes, true, nextQueueSequence++));
            priorityQueuedPayloads++;
        } else {
            sendQueue.add(new QueuedPayload(payload, estimatedBytes, false, nextQueueSequence++));
        }
        queuedBytes += estimatedBytes;
        queueOrderDirty = true;
        return true;
    }

    private void trimQueue() {
        int toRemove = Math.max(1, sendQueue.size() / 10);
        while (toRemove-- > 0 && !sendQueue.isEmpty()) {
            QueuedPayload removed = sendQueue.poll();
            if (removed != null) {
                queuedBytes = Math.max(0L, queuedBytes - removed.estimatedBytes());
                clearRequest(removed.payload().requestId());
            }
        }
        queueOrderDirty = true;
    }

    void prepareSendOrder(int playerCx, int playerCz) {
        if (!queueOrderDirty && playerCx == orderedForPlayerCx && playerCz == orderedForPlayerCz) {
            return;
        }

        reorderQueue(prioritySendQueue, playerCx, playerCz);
        reorderQueue(sendQueue, playerCx, playerCz);
        orderedForPlayerCx = playerCx;
        orderedForPlayerCz = playerCz;
        queueOrderDirty = false;
    }

    QueuedPayload peekPriorityQueuedPayload(int playerCx, int playerCz) {
        prepareSendOrder(playerCx, playerCz);
        return prioritySendQueue.peek();
    }

    QueuedPayload peekQueuedPayload(int playerCx, int playerCz) {
        prepareSendOrder(playerCx, playerCz);
        QueuedPayload priorityPayload = prioritySendQueue.peek();
        return priorityPayload != null ? priorityPayload : sendQueue.peek();
    }

    QueuedPayload pollPriorityQueuedPayload(QueuedPayload payload) {
        if (payload == null) {
            return null;
        }
        QueuedPayload removed = prioritySendQueue.peek() == payload ? prioritySendQueue.poll() : null;
        if (removed == null && !prioritySendQueue.remove(payload)) {
            return null;
        }
        priorityQueuedPayloads = Math.max(0, priorityQueuedPayloads - 1);
        queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        return payload;
    }

    QueuedPayload pollQueuedPayload(QueuedPayload payload) {
        if (payload == null) {
            return null;
        }
        if (payload.priority()) {
            QueuedPayload removed = prioritySendQueue.peek() == payload ? prioritySendQueue.poll() : null;
            if (removed == null && !prioritySendQueue.remove(payload)) {
                return null;
            }
            priorityQueuedPayloads = Math.max(0, priorityQueuedPayloads - 1);
        } else {
            QueuedPayload removed = sendQueue.peek() == payload ? sendQueue.poll() : null;
            if (removed == null && !sendQueue.remove(payload)) {
                return null;
            }
        }
        queuedBytes = Math.max(0L, queuedBytes - payload.estimatedBytes());
        return payload;
    }

    private static void reorderQueue(Queue<QueuedPayload> queue, int playerCx, int playerCz) {
        if (queue.size() <= 1) {
            return;
        }

        ArrayList<QueuedPayload> ordered = new ArrayList<>(queue);
        ordered.sort(sendOrderComparator(playerCx, playerCz));
        queue.clear();
        queue.addAll(ordered);
    }

    private static Comparator<QueuedPayload> sendOrderComparator(int playerCx, int playerCz) {
        return Comparator
                .comparingInt((QueuedPayload candidate) -> chebyshevDistance(candidate.payload(), playerCx, playerCz))
                .thenComparingLong(QueuedPayload::sequence);
    }

    private static int chebyshevDistance(VoxelColumnS2CPayload payload, int playerCx, int playerCz) {
        return Math.max(Math.abs(payload.chunkX() - playerCx), Math.abs(payload.chunkZ() - playerCz));
    }

    int queuedPayloadCount() {
        return prioritySendQueue.size() + sendQueue.size();
    }

    int priorityQueuedPayloadCount() {
        return priorityQueuedPayloads;
    }

    long queuedBytes() {
        return queuedBytes;
    }

    void addPreloadColumns(ArrayList<PersistentColumnLodStore.ExistingColumn> columns) {
        for (PersistentColumnLodStore.ExistingColumn column : columns) {
            addPreloadColumn(new PreloadColumn(column.chunkX(), column.chunkZ(), column.timestamp()));
        }
    }

    void addPreloadColumn(PreloadColumn column) {
        long packed = PositionUtil.packPosition(column.chunkX(), column.chunkZ());
        if (preloadPositions.add(packed)) {
            preloadColumns.add(column);
        }
    }

    PreloadColumn pollPreloadColumn() {
        PreloadColumn column = preloadColumns.poll();
        if (column != null) {
            preloadPositions.remove(PositionUtil.packPosition(column.chunkX(), column.chunkZ()));
        }
        return column;
    }

    PreloadColumn pollPreloadColumnInDistanceRange(int playerCx, int playerCz, int minDistance, int maxDistance) {
        var iterator = preloadColumns.iterator();
        while (iterator.hasNext()) {
            PreloadColumn column = iterator.next();
            int distance = PositionUtil.chebyshevDistance(column.chunkX(), column.chunkZ(), playerCx, playerCz);
            if (distance < minDistance || distance > maxDistance) {
                continue;
            }
            iterator.remove();
            preloadPositions.remove(PositionUtil.packPosition(column.chunkX(), column.chunkZ()));
            return column;
        }
        return null;
    }

    int preloadColumnCount() {
        return preloadColumns.size();
    }

    void updateClientKnownColumns(ResourceKey<Level> dimension, RegionPresenceC2SPayload payload) {
        Long2LongOpenHashMap known = clientKnownColumns.computeIfAbsent(dimension, ignored -> {
            Long2LongOpenHashMap map = new Long2LongOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        if (payload.reset()) {
            known.clear();
        }
        for (RegionPresenceC2SPayload.RegionEntry entry : payload.entries()) {
            int baseX = entry.regionX() * RegionPresenceC2SPayload.REGION_SIZE;
            int baseZ = entry.regionZ() * RegionPresenceC2SPayload.REGION_SIZE;
            int[] slots = entry.slots();
            long[] timestamps = entry.timestamps();
            int count = Math.min(entry.count(), Math.min(slots.length, timestamps.length));
            for (int i = 0; i < count; i++) {
                int slot = slots[i];
                long timestamp = timestamps[i];
                if (slot < 0 || slot >= RegionPresenceC2SPayload.REGION_SLOT_COUNT || timestamp <= 0L) {
                    continue;
                }
                int cx = baseX + (slot & (RegionPresenceC2SPayload.REGION_SIZE - 1));
                int cz = baseZ + (slot >>> 5);
                known.put(PositionUtil.packPosition(cx, cz), timestamp);
            }
        }
    }

    void markClientKnownColumn(ResourceKey<Level> dimension, long packed, long timestamp) {
        if (timestamp <= 0L) {
            return;
        }
        Long2LongOpenHashMap known = clientKnownColumns.computeIfAbsent(dimension, ignored -> {
            Long2LongOpenHashMap map = new Long2LongOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        known.put(packed, Math.max(known.get(packed), timestamp));
    }

    boolean isClientKnownCurrent(ResourceKey<Level> dimension, int cx, int cz, long serverTimestamp) {
        if (serverTimestamp <= 0L) {
            return false;
        }
        Long2LongOpenHashMap known = clientKnownColumns.get(dimension);
        return known != null && known.get(PositionUtil.packPosition(cx, cz)) >= serverTimestamp;
    }

    void resetPreloadRegions(ResourceKey<Level> dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        maxRegionRing = Math.max(0, maxRegionRing);
        preloadRegions.clear();
        queuedPreloadRegionKeys.clear();
        coveredPreloadRegionKeys.clear();
        retainedPreloadRegionDeadlines.clear();
        setPreloadWindow(dimension, centerRegionX, centerRegionZ, maxRegionRing);
        for (int ring = 0; ring <= maxRegionRing; ring++) {
            addRegionRing(centerRegionX, centerRegionZ, ring);
        }
    }

    void updatePreloadRegions(ResourceKey<Level> dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        maxRegionRing = Math.max(0, maxRegionRing);
        if (!preloadWindowInitialized
                || preloadDimension == null
                || !preloadDimension.equals(dimension)
                || preloadMaxRegionRing != maxRegionRing) {
            resetPreloadRegions(dimension, centerRegionX, centerRegionZ, maxRegionRing);
            return;
        }

        int oldMinRegionX = preloadMinRegionX;
        int oldMaxRegionX = preloadMaxRegionX;
        int oldMinRegionZ = preloadMinRegionZ;
        int oldMaxRegionZ = preloadMaxRegionZ;
        int newMinRegionX = centerRegionX - maxRegionRing;
        int newMaxRegionX = centerRegionX + maxRegionRing;
        int newMinRegionZ = centerRegionZ - maxRegionRing;
        int newMaxRegionZ = centerRegionZ + maxRegionRing;
        if (newMinRegionX == oldMinRegionX
                && newMaxRegionX == oldMaxRegionX
                && newMinRegionZ == oldMinRegionZ
                && newMaxRegionZ == oldMaxRegionZ) {
            expireCoveredPreloadRegions();
            return;
        }

        int overlapMinRegionX = Math.max(oldMinRegionX, newMinRegionX);
        int overlapMaxRegionX = Math.min(oldMaxRegionX, newMaxRegionX);
        int overlapMinRegionZ = Math.max(oldMinRegionZ, newMinRegionZ);
        int overlapMaxRegionZ = Math.min(oldMaxRegionZ, newMaxRegionZ);
        if (overlapMinRegionX > overlapMaxRegionX || overlapMinRegionZ > overlapMaxRegionZ) {
            resetPreloadRegions(dimension, centerRegionX, centerRegionZ, maxRegionRing);
            return;
        }

        preloadCenterRegionX = centerRegionX;
        preloadCenterRegionZ = centerRegionZ;
        preloadMinRegionX = newMinRegionX;
        preloadMaxRegionX = newMaxRegionX;
        preloadMinRegionZ = newMinRegionZ;
        preloadMaxRegionZ = newMaxRegionZ;
        prunePreloadRegionsToWindow();

        ArrayList<PreloadRegion> enteredRegions = new ArrayList<>();
        collectRegionRectangle(enteredRegions, newMinRegionX, oldMinRegionX - 1, newMinRegionZ, newMaxRegionZ);
        collectRegionRectangle(enteredRegions, oldMaxRegionX + 1, newMaxRegionX, newMinRegionZ, newMaxRegionZ);
        collectRegionRectangle(enteredRegions, overlapMinRegionX, overlapMaxRegionX, newMinRegionZ, oldMinRegionZ - 1);
        collectRegionRectangle(enteredRegions, overlapMinRegionX, overlapMaxRegionX, oldMaxRegionZ + 1, newMaxRegionZ);
        addPreloadRegionsOrdered(enteredRegions, centerRegionX, centerRegionZ);
        reorderPreloadRegions(centerRegionX, centerRegionZ);
    }

    PreloadRegion pollPreloadRegion() {
        PreloadRegion region = preloadRegions.poll();
        if (region != null) {
            long key = regionKey(region.regionX(), region.regionZ());
            queuedPreloadRegionKeys.remove(key);
            coveredPreloadRegionKeys.add(key);
            retainedPreloadRegionDeadlines.remove(key);
        }
        return region;
    }

    int preloadRegionCount() {
        return preloadRegions.size();
    }

    private void addRegionRing(int centerRegionX, int centerRegionZ, int ring) {
        if (ring == 0) {
            addPreloadRegion(centerRegionX, centerRegionZ);
            return;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            addPreloadRegion(centerRegionX + dx, centerRegionZ - ring);
            addPreloadRegion(centerRegionX + dx, centerRegionZ + ring);
        }
        for (int dz = -ring + 1; dz <= ring - 1; dz++) {
            addPreloadRegion(centerRegionX - ring, centerRegionZ + dz);
            addPreloadRegion(centerRegionX + ring, centerRegionZ + dz);
        }
    }

    private void addPreloadRegion(int regionX, int regionZ) {
        long key = regionKey(regionX, regionZ);
        if (!queuedPreloadRegionKeys.contains(key) && !coveredPreloadRegionKeys.contains(key)) {
            preloadRegions.add(new PreloadRegion(regionX, regionZ));
            queuedPreloadRegionKeys.add(key);
        }
    }

    private void addPreloadRegionsOrdered(ArrayList<PreloadRegion> regions, int centerRegionX, int centerRegionZ) {
        if (regions.isEmpty()) {
            return;
        }
        regions.sort(preloadRegionComparator(centerRegionX, centerRegionZ));
        for (PreloadRegion region : regions) {
            addPreloadRegion(region.regionX(), region.regionZ());
        }
    }

    private void collectRegionRectangle(
            ArrayList<PreloadRegion> output,
            int minRegionX,
            int maxRegionX,
            int minRegionZ,
            int maxRegionZ) {
        if (minRegionX > maxRegionX || minRegionZ > maxRegionZ) {
            return;
        }
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                long key = regionKey(regionX, regionZ);
                if (!queuedPreloadRegionKeys.contains(key) && !coveredPreloadRegionKeys.contains(key)) {
                    output.add(new PreloadRegion(regionX, regionZ));
                }
            }
        }
    }

    private void prunePreloadRegionsToWindow() {
        expireCoveredPreloadRegions();
        if (preloadRegions.isEmpty()) {
            queuedPreloadRegionKeys.clear();
            return;
        }

        ArrayDeque<PreloadRegion> retained = new ArrayDeque<>(preloadRegions.size());
        queuedPreloadRegionKeys.clear();
        while (!preloadRegions.isEmpty()) {
            PreloadRegion region = preloadRegions.poll();
            if (isRegionInPreloadWindow(region.regionX(), region.regionZ())) {
                retained.add(region);
                queuedPreloadRegionKeys.add(regionKey(region.regionX(), region.regionZ()));
            }
        }
        preloadRegions.addAll(retained);
    }

    private void expireCoveredPreloadRegions() {
        long now = System.nanoTime();
        coveredPreloadRegionKeys.removeIf(key -> shouldDropCoveredPreloadRegion(key, now));
    }

    private boolean shouldDropCoveredPreloadRegion(long key, long now) {
        int regionX = regionKeyX(key);
        int regionZ = regionKeyZ(key);
        if (isRegionInPreloadWindow(regionX, regionZ)) {
            retainedPreloadRegionDeadlines.remove(key);
            return false;
        }

        long deadline = retainedPreloadRegionDeadlines.computeIfAbsent(key, ignored -> now + PRELOAD_REGION_RETAIN_NANOS);
        if (now < deadline) {
            return false;
        }
        retainedPreloadRegionDeadlines.remove(key);
        return true;
    }

    private void reorderPreloadRegions(int centerRegionX, int centerRegionZ) {
        if (preloadRegions.size() <= 1) {
            return;
        }
        ArrayList<PreloadRegion> ordered = new ArrayList<>(preloadRegions);
        ordered.sort(preloadRegionComparator(centerRegionX, centerRegionZ));
        preloadRegions.clear();
        preloadRegions.addAll(ordered);
    }

    private void setPreloadWindow(ResourceKey<Level> dimension, int centerRegionX, int centerRegionZ, int maxRegionRing) {
        preloadDimension = dimension;
        preloadWindowInitialized = true;
        preloadCenterRegionX = centerRegionX;
        preloadCenterRegionZ = centerRegionZ;
        preloadMaxRegionRing = maxRegionRing;
        preloadMinRegionX = centerRegionX - maxRegionRing;
        preloadMaxRegionX = centerRegionX + maxRegionRing;
        preloadMinRegionZ = centerRegionZ - maxRegionRing;
        preloadMaxRegionZ = centerRegionZ + maxRegionRing;
    }

    private boolean isRegionInPreloadWindow(int regionX, int regionZ) {
        return regionX >= preloadMinRegionX
                && regionX <= preloadMaxRegionX
                && regionZ >= preloadMinRegionZ
                && regionZ <= preloadMaxRegionZ;
    }

    private static Comparator<PreloadRegion> preloadRegionComparator(int centerRegionX, int centerRegionZ) {
        return Comparator
                .comparingInt((PreloadRegion region) -> Math.max(
                        Math.abs(region.regionX() - centerRegionX),
                        Math.abs(region.regionZ() - centerRegionZ)))
                .thenComparingLong(region -> {
                    long dx = (long) region.regionX() - centerRegionX;
                    long dz = (long) region.regionZ() - centerRegionZ;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(PreloadRegion::regionX)
                .thenComparingInt(PreloadRegion::regionZ);
    }

    boolean shouldBackpressureNormalRequests(long configuredLimit) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        long effectiveLimit = effectiveLimit(configuredLimit);
        int queueLimitThreshold = Math.max(1, config.sendQueueLimitPerPlayer / 2);
        int bandwidthColumnThreshold = (int) Math.max(
                1L,
                effectiveLimit * NORMAL_QUEUE_BACKPRESSURE_SECONDS / ESTIMATED_NORMAL_COLUMN_BYTES);
        int columnThreshold = Math.max(
                MIN_NORMAL_QUEUE_BACKPRESSURE_COLUMNS,
                Math.min(queueLimitThreshold, bandwidthColumnThreshold));
        long byteThreshold = Math.max(
                MIN_NORMAL_QUEUE_BACKPRESSURE_BYTES,
                Math.min(
                        Math.max(1L, config.sendQueueBytesLimitPerPlayer / 2L),
                        effectiveLimit * NORMAL_QUEUE_BACKPRESSURE_SECONDS));
        return queuedPayloadCount() >= columnThreshold || queuedBytes >= byteThreshold;
    }

    boolean canSend(long configuredLimit) {
        refill(configuredLimit);
        return availableBytes > 0L;
    }

    void recordSend(int bytes) {
        availableBytes = Math.max(0L, availableBytes - bytes);
        totalBytesSent += bytes;
    }

    void primeSendCredit(long configuredLimit) {
        refill(configuredLimit);
        long primedCredit = Math.min(sendBurstCap(effectiveLimit(configuredLimit)), MAX_PRIMED_SEND_CREDIT_BYTES);
        availableBytes = primedCredit;
        lastRefillNanos = System.nanoTime();
    }

    long totalBytesSent() {
        return totalBytesSent;
    }

    void setDesiredBandwidth(long desiredBandwidth) {
        this.desiredBandwidth = desiredBandwidth > 0L ? desiredBandwidth : Long.MAX_VALUE;
    }

    long desiredBandwidth() {
        return desiredBandwidth;
    }

    void setClientCapabilities(int clientCapabilities) {
        this.clientCapabilities = clientCapabilities;
    }

    boolean supportsZstdColumns() {
        return (clientCapabilities & dev.xantha.vss.common.VSSConstants.CAPABILITY_ZSTD_COLUMNS) != 0;
    }

    void clearAll() {
        cancelled.clear();
        requestPositions.clear();
        activePositions.clear();
        prioritySendQueue.clear();
        sendQueue.clear();
        preloadColumns.clear();
        preloadPositions.clear();
        preloadRegions.clear();
        queuedPreloadRegionKeys.clear();
        coveredPreloadRegionKeys.clear();
        retainedPreloadRegionDeadlines.clear();
        clientKnownColumns.clear();
        preloadDimension = null;
        preloadWindowInitialized = false;
        priorityQueuedPayloads = 0;
        queuedBytes = 0L;
        queueOrderDirty = true;
        orderedForPlayerCx = Integer.MIN_VALUE;
        orderedForPlayerCz = Integer.MIN_VALUE;
    }

    private void refill(long configuredLimit) {
        long limit = effectiveLimit(configuredLimit);
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos < 1_000_000L) {
            return;
        }

        lastRefillNanos = now;
        elapsedNanos = Math.min(elapsedNanos, 1_000_000_000L);
        long refill = elapsedNanos * limit / 1_000_000_000L;
        availableBytes = Math.min(availableBytes + refill, sendBurstCap(limit));
    }

    private long effectiveLimit(long configuredLimit) {
        long safeConfiguredLimit = Math.max(1L, configuredLimit);
        return Math.min(safeConfiguredLimit, desiredBandwidth);
    }

    private static long sendBurstCap(long limit) {
        return Math.max(1L, limit / 4L);
    }

    private record RequestPosition(ResourceKey<Level> dimension, long packedPosition) {
    }

    record PreloadColumn(int chunkX, int chunkZ, long timestamp) {
    }

    record PreloadRegion(int regionX, int regionZ) {
    }

    record QueuedPayload(VoxelColumnS2CPayload payload, int estimatedBytes, boolean priority, long sequence) {
    }

    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int regionKeyX(long key) {
        return (int) (key >> 32);
    }

    private static int regionKeyZ(long key) {
        return (int) key;
    }
}
