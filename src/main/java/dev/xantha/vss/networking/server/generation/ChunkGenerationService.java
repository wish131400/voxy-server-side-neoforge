package dev.xantha.vss.networking.server.generation;


import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.SectionSerializer;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkGenerationService {
    private static final TicketType<ChunkPos> VSS_GEN_TICKET =
            TicketType.create("vss_generation", Comparator.comparingLong(ChunkPos::toLong));
    private static final int VSS_GEN_TICKET_DISTANCE = 0;
    private static final int PRIORITY_PACKING_QUEUE_EXTRA_LIMIT = 256;

    private final LinkedHashMap<PendingGenerationKey, PendingGeneration> active = new LinkedHashMap<>();
    private final LinkedHashMap<PendingGenerationKey, PendingGeneration> queued = new LinkedHashMap<>();
    private final PriorityQueue<QueuedGenerationEntry> queuedPriority = new PriorityQueue<>();
    private final Map<PendingGenerationKey, PendingPacking> packingByColumn = new HashMap<>();
    private final Map<RequestKey, CancelableTaskCallbacks.Token<GenerationCallback>> packingCallbacks = new HashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    private final Map<UUID, Integer> perPlayerQueuedCount = new HashMap<>();
    private final Map<RequestKey, GenerationLocation> requestIndex = new HashMap<>();
    private final Map<UUID, Set<RequestKey>> playerRequestIndex = new HashMap<>();
    private final Map<UUID, PlayerGenerationView> playerGenerationViews = new HashMap<>();
    private final Map<UUID, PlayerGenerationView> lastPrunedPlayerViews = new HashMap<>();
    private final ConcurrentLinkedQueue<PackingResult> completedPackingResults = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<GenerationResult> deferredGenerationResults = new ArrayDeque<>();
    private final VSSServerConfig config;
    private ThreadPoolExecutor packingExecutor;
    private long totalSubmitted;
    private long totalCompleted;
    private long totalQueued;
    private long totalTimeouts;
    private long totalPackingSubmitted;
    private long totalLivePackingSubmitted;
    private long totalLivePackingCompleted;
    private long totalPackingFailures;
    private long totalPackingCancelled;
    private long totalPackingCompleted;
    private long totalPackingFinished;
    private long totalPackingRejected;
    private long totalPackingCallbacksCompleted;
    private long totalQueueRejected;
    private long totalQueueEvicted;
    private long totalQueueHeapRebuilds;
    private long totalQueueStaleEntries;
    private long totalQueuePromoted;
    private long totalQueueWaitNanos;
    private long maxQueueWaitNanos;
    private long totalTicketWaitTicks;
    private long totalTicketHandoffs;
    private long maxTicketWaitTicks;
    private long totalPackingWaitNanos;
    private long maxPackingWaitNanos;
    private double startBudget;
    private int startsThisTick;
    private volatile long packingEpoch;
    private long nextPackingTaskSequence;
    private long nextQueuedSequence;
    private boolean rebuildingQueuedPriority;

    public ChunkGenerationService(VSSServerConfig config) {
        this.config = config;
    }

    public synchronized boolean submitGeneration(
            UUID playerUuid,
            PlayerRequestState requestState,
            int requestId,
            ServerLevel level,
            int cx,
            int cz,
            long minimumTimestamp) {
        return submitGeneration(
                playerUuid,
                requestState,
                requestId,
                level,
                cx,
                cz,
                minimumTimestamp,
                false);
    }

    public synchronized boolean submitGeneration(
            UUID playerUuid,
            PlayerRequestState requestState,
            int requestId,
            ServerLevel level,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        PendingGenerationKey key = new PendingGenerationKey(level.dimension(), cx, cz);
        currentGenerationView(playerUuid, level);
        GenerationCallback callback = new GenerationCallback(playerUuid, requestState, requestId, priority);
        PendingPacking existingPacking = packingByColumn.get(key);
        if (existingPacking != null) {
            CancelableTaskCallbacks.Token<GenerationCallback> packingCallback =
                    existingPacking.tryAddCallback(callback, minimumTimestamp);
            if (packingCallback != null) {
                indexPackingCallback(key, packingCallback);
                return true;
            }
            if (existingPacking.isFinished()) {
                packingByColumn.remove(key, existingPacking);
            }
        }

        PendingGeneration existing = active.get(key);
        if (existing != null) {
            if (perPlayerActiveCount.getOrDefault(playerUuid, 0)
                    >= config.generationConcurrencyLimitPerPlayer) {
                totalQueueRejected++;
                return false;
            }
            existing.minimumTimestamp = Math.max(existing.minimumTimestamp, minimumTimestamp);
            existing.callbacks.add(callback);
            indexCallback(key, callback, GenerationStage.TICKET_WAIT);
            incrementCount(playerUuid);
            return true;
        }

        PendingGeneration queuedGeneration = queued.get(key);
        if (queuedGeneration != null) {
            if (callbackCountForPlayer(queuedGeneration, playerUuid)
                    >= config.generationConcurrencyLimitPerPlayer
                    || !ensureQueueCapacityFor(playerUuid, queuedGeneration)) {
                totalQueueRejected++;
                return false;
            }
            queuedGeneration.minimumTimestamp = Math.max(queuedGeneration.minimumTimestamp, minimumTimestamp);
            queuedGeneration.callbacks.add(callback);
            indexCallback(key, callback, GenerationStage.QUEUED);
            incrementQueuedCount(playerUuid);
            refreshQueuedPriority(key, queuedGeneration);
            return true;
        }

        ChunkPos pos = new ChunkPos(cx, cz);
        PendingGeneration generation = new PendingGeneration(pos, level, minimumTimestamp);
        generation.callbacks.add(callback);
        if (queued.isEmpty() && canStart(generation) && tryConsumeStartBudget()) {
            startGeneration(key, generation);
        } else {
            if (!ensureQueueCapacityFor(playerUuid, generation)) {
                totalQueueRejected++;
                return false;
            }
            enqueueGeneration(key, generation);
            incrementQueuedCount(playerUuid);
            indexGeneration(key, generation, GenerationStage.QUEUED);
            totalQueued++;
        }
        return true;
    }

    public synchronized boolean isGenerating(ResourceKey<Level> dimension, int cx, int cz) {
        PendingGenerationKey key = new PendingGenerationKey(dimension, cx, cz);
        return active.containsKey(key) || queued.containsKey(key) || packingByColumn.containsKey(key);
    }

    public synchronized boolean submitLoadedColumn(
            UUID playerUuid,
            PlayerRequestState requestState,
            int requestId,
            ServerLevel level,
            LevelChunk chunk,
            int cx,
            int cz,
            long minimumTimestamp,
            boolean priority) {
        PendingGenerationKey key = new PendingGenerationKey(level.dimension(), cx, cz);
        currentGenerationView(playerUuid, level);
        GenerationCallback callback = new GenerationCallback(playerUuid, requestState, requestId, priority);
        PendingPacking existingPacking = packingByColumn.get(key);
        if (existingPacking != null) {
            CancelableTaskCallbacks.Token<GenerationCallback> packingCallback =
                    existingPacking.tryAddCallback(callback, minimumTimestamp);
            if (packingCallback != null) {
                indexPackingCallback(key, packingCallback);
                return true;
            }
            if (existingPacking.isFinished()) {
                packingByColumn.remove(key, existingPacking);
            }
        }
        if (!canSubmitPackingTask(priority)) {
            totalPackingRejected++;
            return false;
        }

        SectionSerializer.ColumnSnapshot snapshot;
        try {
            snapshot = SectionSerializer.snapshotColumn(level, chunk, cx, cz);
        } catch (Exception e) {
            VSSLogger.error("Failed to snapshot loaded chunk at " + cx + ", " + cz, e);
            return false;
        }

        try {
            long taskEpoch = packingEpoch;
            long columnTimestamp = Math.max(VSSConstants.columnVersion(), minimumTimestamp);
            PendingPacking packing = new PendingPacking(
                    key,
                    taskEpoch,
                    level.dimension(),
                    snapshot,
                    false,
                    columnTimestamp,
                    callback,
                    System.nanoTime());
            submitPackingRunnable(packing);
            packingByColumn.put(key, packing);
            indexPackingCallback(key, packing.initialCallback());
            totalPackingSubmitted++;
            totalLivePackingSubmitted++;
            return true;
        } catch (RejectedExecutionException e) {
            totalPackingRejected++;
            return false;
        }
    }

    public synchronized List<GenerationResult> tick(MinecraftServer server) {
        startsThisTick = 0;
        refillStartBudget();
        List<GenerationResult> results = new ArrayList<>();
        drainPackingResults(results);
        drainDeferredGenerationResults(results);
        pruneStalePlayerRequests(server, results);
        promoteQueued();
        if (active.isEmpty()) {
            return results.isEmpty() ? List.of() : results;
        }

        Iterator<Map.Entry<PendingGenerationKey, PendingGeneration>> iterator = active.entrySet().iterator();
        int processedThisTick = 0;
        while (iterator.hasNext()) {
            Map.Entry<PendingGenerationKey, PendingGeneration> activeEntry = iterator.next();
            PendingGenerationKey generationKey = activeEntry.getKey();
            PendingGeneration generation = activeEntry.getValue();
            generation.ticksWaiting++;

            LevelChunk chunk = generation.level.getChunkSource().getChunkNow(generation.pos.x, generation.pos.z);
            if (chunk == null) {
                if (generation.ticksWaiting <= config.generationTimeoutSeconds * 20) {
                    continue;
                }
                VSSLogger.debug("Generation timeout for chunk " + generation.pos.x + ", " + generation.pos.z
                        + " after " + generation.ticksWaiting + " ticks (" + generation.callbacks.size() + " callbacks)");
                for (GenerationCallback callback : generation.callbacks) {
                    results.add(GenerationResult.notGenerated(
                            callback.playerUuid(),
                            callback.requestState(),
                            callback.requestId(),
                            generation.level.dimension(),
                            callback.priority()));
                    decrementCount(callback.playerUuid());
                }
                unindexGeneration(generation);
                removeTicket(generation);
                iterator.remove();
                totalTimeouts++;
                continue;
            }

            if (processedThisTick >= config.generationCompletionsPerTickLimit) {
                continue;
            }
            if (!canSubmitPackingTask(generation.priority())) {
                continue;
            }

            processedThisTick++;
            PendingPacking handedOffPacking = null;
            try {
                SectionSerializer.ColumnSnapshot snapshot = SectionSerializer.snapshotColumn(
                        generation.level, chunk, generation.pos.x, generation.pos.z);
                handedOffPacking = submitPackingTask(generationKey, generation, snapshot);
            } catch (Exception e) {
                if (e instanceof RejectedExecutionException) {
                    totalPackingRejected++;
                }
                VSSLogger.error("Failed to snapshot generated chunk at " + generation.pos.x + ", " + generation.pos.z, e);
                for (GenerationCallback callback : generation.callbacks) {
                    results.add(GenerationResult.notGenerated(
                            callback.playerUuid(),
                            callback.requestState(),
                            callback.requestId(),
                            generation.level.dimension(),
                            callback.priority()));
                }
            }

            GenerationSchedulingPolicy.releaseSlots(
                    perPlayerActiveCount,
                    generation.callbacks.stream()
                            .map(GenerationCallback::playerUuid)
                            .toList());
            totalTicketWaitTicks += generation.ticksWaiting;
            totalTicketHandoffs++;
            maxTicketWaitTicks = Math.max(maxTicketWaitTicks, generation.ticksWaiting);
            unindexGeneration(generation);
            if (handedOffPacking != null) {
                registerPacking(handedOffPacking);
            }
            removeTicket(generation);
            iterator.remove();
        }
        promoteQueued();
        return results;
    }

    public synchronized void cancelRequest(UUID playerUuid, int requestId) {
        cancelIndexedRequest(new RequestKey(playerUuid, requestId), true);
    }

    public synchronized void removePlayer(UUID playerUuid) {
        Set<RequestKey> indexedRequests = playerRequestIndex.get(playerUuid);
        if (indexedRequests != null) {
            for (RequestKey requestKey : List.copyOf(indexedRequests)) {
                cancelIndexedRequest(requestKey, false);
            }
        }
        playerRequestIndex.remove(playerUuid);
        perPlayerActiveCount.remove(playerUuid);
        perPlayerQueuedCount.remove(playerUuid);
        promoteQueued();
    }

    public synchronized void releaseIdleMemory() {
        packingEpoch++;
        for (PendingGeneration generation : active.values()) {
            removeTicket(generation);
        }
        shutdownPackingExecutor();
        completedPackingResults.clear();
        deferredGenerationResults.clear();
        for (CancelableTaskCallbacks.Token<GenerationCallback> callback : packingCallbacks.values()) {
            callback.cancel();
        }
        active.clear();
        queued.clear();
        queuedPriority.clear();
        packingByColumn.clear();
        packingCallbacks.clear();
        perPlayerActiveCount.clear();
        perPlayerQueuedCount.clear();
        requestIndex.clear();
        playerRequestIndex.clear();
        playerGenerationViews.clear();
        lastPrunedPlayerViews.clear();
    }

    public synchronized void shutdown() {
        releaseIdleMemory();
    }

    public synchronized String diagnostics() {
        ThreadPoolExecutor executor = this.packingExecutor;
        int packingActive = executor != null ? executor.getActiveCount() : 0;
        int packingQueued = executor != null ? executor.getQueue().size() : 0;
        double averageTicketWaitMs = totalTicketHandoffs == 0L
                ? 0.0D
                : totalTicketWaitTicks * 50.0D / totalTicketHandoffs;
        double averagePackingWaitMs = totalPackingFinished == 0L
                ? 0.0D
                : totalPackingWaitNanos / 1_000_000.0D / totalPackingFinished;
        double averageQueueWaitMs = totalQueuePromoted == 0L
                ? 0.0D
                : totalQueueWaitNanos / 1_000_000.0D / totalQueuePromoted;
        return String.format("submitted=%d, completed=%d, ticketWaiting=%d, queued=%d, everQueued=%d, queueRejected=%d, queueEvicted=%d, queueWaitAvgMs=%.1f, queueWaitMaxMs=%.1f, timeouts=%d, ticketWaitAvgMs=%.1f, ticketWaitMaxMs=%d, heapRebuilds=%d, staleHeapEntries=%d, packingSubmitted=%d, packingFinished=%d, packingCompleted=%d, packingRejected=%d, packingCallbacksPending=%d, packingCallbacksCompleted=%d, packingActive=%d, packingQueued=%d, resultsPending=%d, packingFailures=%d, packingCancelled=%d, packingWaitAvgMs=%.1f, packingWaitMaxMs=%.1f, startBudget=%.1f",
                totalSubmitted,
                totalCompleted,
                active.size(),
                queued.size(),
                totalQueued,
                totalQueueRejected,
                totalQueueEvicted,
                averageQueueWaitMs,
                maxQueueWaitNanos / 1_000_000.0D,
                totalTimeouts,
                averageTicketWaitMs,
                maxTicketWaitTicks * 50L,
                totalQueueHeapRebuilds,
                totalQueueStaleEntries,
                totalPackingSubmitted,
                totalPackingFinished,
                totalPackingCompleted,
                totalPackingRejected,
                packingCallbacks.size(),
                totalPackingCallbacksCompleted,
                packingActive,
                packingQueued,
                completedPackingResults.size(),
                totalPackingFailures,
                totalPackingCancelled,
                averagePackingWaitMs,
                maxPackingWaitNanos / 1_000_000.0D,
                startBudget)
                + String.format(", livePackingSubmitted=%d, livePackingCompleted=%d", totalLivePackingSubmitted, totalLivePackingCompleted);
    }

    public synchronized Component diagnosticsComponent(Component storageDiagnostics) {
        ThreadPoolExecutor executor = this.packingExecutor;
        int packingActive = executor != null ? executor.getActiveCount() : 0;
        int packingQueued = executor != null ? executor.getQueue().size() : 0;
        return Component.translatable(
                "vss.command.generation.stats.details",
                totalSubmitted,
                totalCompleted,
                active.size(),
                queued.size(),
                totalQueued,
                totalTimeouts)
                .append(Component.literal("; "))
                .append(Component.translatable(
                        "vss.command.generation.packing.details",
                        totalPackingSubmitted,
                        packingActive,
                        packingQueued,
                        totalPackingRejected,
                        totalPackingFailures,
                        totalLivePackingSubmitted,
                        totalLivePackingCompleted))
                .append(Component.literal("; "))
                .append(Component.translatable(
                        "vss.command.generation.runtime.extra",
                        totalQueueRejected,
                        totalQueueEvicted,
                        String.format(java.util.Locale.ROOT, "%.1f", totalQueuePromoted == 0L
                                ? 0.0D
                                : totalQueueWaitNanos / 1_000_000.0D / totalQueuePromoted),
                        String.format(java.util.Locale.ROOT, "%.1f", maxQueueWaitNanos / 1_000_000.0D),
                        packingCallbacks.size(),
                        totalPackingCallbacksCompleted,
                        totalPackingCancelled,
                        String.format(java.util.Locale.ROOT, "%.1f", totalTicketHandoffs == 0L
                                ? 0.0D
                                : totalTicketWaitTicks * 50.0D / totalTicketHandoffs),
                        maxTicketWaitTicks * 50L,
                        String.format(java.util.Locale.ROOT, "%.1f", totalPackingFinished == 0L
                                ? 0.0D
                                : totalPackingWaitNanos / 1_000_000.0D / totalPackingFinished),
                        String.format(java.util.Locale.ROOT, "%.1f", maxPackingWaitNanos / 1_000_000.0D)))
                .append(Component.literal("; "))
                .append(storageDiagnostics);
    }

    private void promoteQueued() {
        if (queued.isEmpty() || startBudget < 1.0D || startsThisTick >= config.generationStartsPerTickLimit) {
            return;
        }

        ArrayList<QueuedGenerationEntry> blocked = new ArrayList<>();
        try {
            while (active.size() < config.generationConcurrencyLimitGlobal
                    && startBudget >= 1.0D
                    && startsThisTick < config.generationStartsPerTickLimit) {
                QueuedSelection selection = selectNearestStartableQueuedGeneration(blocked);
                if (selection == null) {
                    break;
                }
                PendingGeneration generation = selection.generation();

                if (!tryConsumeStartBudget()) {
                    refreshQueuedPriority(selection.key(), generation);
                    break;
                }
                for (GenerationCallback callback : generation.callbacks) {
                    decrementQueuedCount(callback.playerUuid());
                }
                long queueWaitNanos = Math.max(0L, System.nanoTime() - generation.queuedNanos);
                totalQueuePromoted++;
                totalQueueWaitNanos += queueWaitNanos;
                maxQueueWaitNanos = Math.max(maxQueueWaitNanos, queueWaitNanos);
                queued.remove(selection.key());
                startGeneration(selection.key(), generation);
            }
        } finally {
            queuedPriority.addAll(blocked);
        }
    }

    private QueuedSelection selectNearestStartableQueuedGeneration(
            List<QueuedGenerationEntry> blocked) {
        QueuedSelection selected = null;
        while (!queuedPriority.isEmpty()) {
            QueuedGenerationEntry candidate = queuedPriority.poll();
            PendingGeneration generation = queued.get(candidate.key());
            if (generation == null
                    || !GenerationSchedulingPolicy.isCurrentRevision(
                            generation.queueRevision,
                            candidate.revision())) {
                totalQueueStaleEntries++;
                continue;
            }
            if (!canStart(generation)) {
                blocked.add(candidate);
                continue;
            }
            selected = new QueuedSelection(candidate.key(), generation);
            break;
        }
        return selected;
    }

    private boolean canStart(PendingGeneration generation) {
        if (active.size() >= config.generationConcurrencyLimitGlobal) {
            return false;
        }
        return GenerationSchedulingPolicy.hasPerPlayerCapacity(
                perPlayerActiveCount,
                generation.callbacks.stream()
                        .map(GenerationCallback::playerUuid)
                        .toList(),
                config.generationConcurrencyLimitPerPlayer);
    }

    private boolean canQueue(UUID playerUuid) {
        int queuedForPlayer = perPlayerQueuedCount.getOrDefault(playerUuid, 0);
        int maxQueuedForPlayer = Math.max(1, config.generationRateLimitPerPlayer * config.generationTimeoutSeconds);
        return queuedForPlayer < maxQueuedForPlayer;
    }

    private static int callbackCountForPlayer(PendingGeneration generation, UUID playerUuid) {
        int count = 0;
        for (GenerationCallback callback : generation.callbacks) {
            if (callback.playerUuid().equals(playerUuid)) {
                count++;
            }
        }
        return count;
    }

    private boolean ensureQueueCapacityFor(UUID playerUuid, PendingGeneration incomingGeneration) {
        if (canQueue(playerUuid)) {
            return true;
        }
        return evictFarthestQueuedRequestFor(playerUuid, incomingGeneration);
    }

    private boolean evictFarthestQueuedRequestFor(UUID playerUuid, PendingGeneration incomingGeneration) {
        PlayerGenerationView view = currentGenerationView(playerUuid, incomingGeneration.level);
        if (view == null) {
            return false;
        }

        int incomingRing = priorityRingForView(incomingGeneration, view);
        QueuedCallbackCandidate farthest = null;
        Set<RequestKey> playerRequests = playerRequestIndex.get(playerUuid);
        if (playerRequests == null || playerRequests.isEmpty()) {
            return false;
        }

        for (RequestKey requestKey : playerRequests) {
            GenerationLocation location = requestIndex.get(requestKey);
            if (location == null || location.stage() != GenerationStage.QUEUED) {
                continue;
            }
            PendingGeneration generation = queued.get(location.key());
            if (generation == null) {
                continue;
            }
            GenerationCallback queuedCallback = findCallback(
                    generation.callbacks,
                    requestKey.playerUuid(),
                    requestKey.requestId());
            if (queuedCallback == null
                    || (!incomingGeneration.priority() && queuedCallback.priority())) {
                continue;
            }
            int ring = priorityRingForView(generation, view);
            if (farthest == null
                    || (farthest.priority() && !queuedCallback.priority())
                    || (farthest.priority() == queuedCallback.priority() && ring > farthest.ring())) {
                farthest = new QueuedCallbackCandidate(
                        requestKey,
                        location,
                        ring,
                        queuedCallback.priority());
            }
        }

        if (farthest == null
                || (incomingGeneration.priority() == farthest.priority()
                && incomingRing >= farthest.ring())) {
            return false;
        }
        return evictQueuedCallback(farthest);
    }

    private boolean evictQueuedCallback(QueuedCallbackCandidate candidate) {
        PendingGeneration generation = queued.get(candidate.location().key());
        if (generation == null) {
            unindexCallback(candidate.requestKey().playerUuid(), candidate.requestKey().requestId());
            return false;
        }

        GenerationCallback callback = removeCallbackEntry(
                generation.callbacks,
                candidate.requestKey().playerUuid(),
                candidate.requestKey().requestId());
        if (callback == null) {
            unindexCallback(candidate.requestKey().playerUuid(), candidate.requestKey().requestId());
            return false;
        }

        deferredGenerationResults.add(GenerationResult.notGenerated(
                callback.playerUuid(),
                callback.requestState(),
                callback.requestId(),
                generation.level.dimension(),
                callback.priority()));
        unindexCallback(callback.playerUuid(), callback.requestId());
        decrementQueuedCount(callback.playerUuid());
        totalQueueEvicted++;
        if (generation.callbacks.isEmpty()) {
            queued.remove(candidate.location().key());
        } else {
            refreshQueuedPriority(candidate.location().key(), generation);
        }
        return true;
    }

    private PlayerGenerationView currentGenerationView(UUID playerUuid, ServerLevel level) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuid);
        if (player != null) {
            PlayerGenerationView view = PlayerGenerationView.from(player);
            PlayerGenerationView previous = playerGenerationViews.put(playerUuid, view);
            if (previous != null && !previous.equals(view) && !queued.isEmpty()) {
                rebuildQueuedPriority();
            }
            return view;
        }
        return playerGenerationViews.get(playerUuid);
    }

    private int priorityRing(PendingGeneration generation) {
        int bestRing = Integer.MAX_VALUE;
        for (GenerationCallback callback : generation.callbacks) {
            PlayerGenerationView view = playerGenerationViews.get(callback.playerUuid());
            if (view == null) {
                continue;
            }
            bestRing = Math.min(bestRing, priorityRingForView(generation, view));
        }
        return bestRing;
    }

    private int priorityRingForView(PendingGeneration generation, PlayerGenerationView view) {
        if (!view.dimension().equals(generation.level.dimension())) {
            return Integer.MAX_VALUE;
        }
        return PositionUtil.chebyshevDistance(generation.pos.x, generation.pos.z, view.chunkX(), view.chunkZ());
    }

    private void refillStartBudget() {
        int playerCount = Math.max(1, trackedPlayerCount());
        int globalStartRate = Math.max(1, Math.min(
                config.generationConcurrencyLimitGlobal,
                config.generationRateLimitPerPlayer * playerCount));
        startBudget = Math.min(
                Math.max(1, config.generationConcurrencyLimitGlobal),
                startBudget + globalStartRate / 20.0D);
    }

    private int trackedPlayerCount() {
        return Math.max(perPlayerActiveCount.size(), perPlayerQueuedCount.size());
    }

    private boolean tryConsumeStartBudget() {
        if (startBudget < 1.0D || startsThisTick >= config.generationStartsPerTickLimit) {
            return false;
        }
        startBudget = Math.max(0.0D, startBudget - 1.0D);
        startsThisTick++;
        return true;
    }

    private void startGeneration(PendingGenerationKey key, PendingGeneration generation) {
        // Distance 0 targets FULL chunks without promoting remote LOD work to ticking forced chunks.
        generation.level.getChunkSource().addRegionTicket(VSS_GEN_TICKET, generation.pos, VSS_GEN_TICKET_DISTANCE, generation.pos);
        generation.ticksWaiting = 0;
        active.put(key, generation);
        for (GenerationCallback callback : generation.callbacks) {
            incrementCount(callback.playerUuid());
        }
        indexGeneration(key, generation, GenerationStage.TICKET_WAIT);
        totalSubmitted++;
    }

    private void enqueueGeneration(PendingGenerationKey key, PendingGeneration generation) {
        queued.put(key, generation);
        refreshQueuedPriority(key, generation);
    }

    private void refreshQueuedPriority(PendingGenerationKey key, PendingGeneration generation) {
        generation.queueRevision++;
        queuedPriority.add(new QueuedGenerationEntry(
                key,
                generation.queueRevision,
                generation.priority(),
                priorityRing(generation),
                nextQueuedSequence++));
        if (!rebuildingQueuedPriority
                && queuedPriority.size() > Math.max(64, queued.size() * 4)) {
            rebuildQueuedPriority();
        }
    }

    private void rebuildQueuedPriority() {
        rebuildingQueuedPriority = true;
        try {
            queuedPriority.clear();
            for (Map.Entry<PendingGenerationKey, PendingGeneration> entry : queued.entrySet()) {
                refreshQueuedPriority(entry.getKey(), entry.getValue());
            }
        } finally {
            rebuildingQueuedPriority = false;
        }
        totalQueueHeapRebuilds++;
    }

    private void removeTicket(PendingGeneration generation) {
        generation.level.getChunkSource().removeRegionTicket(VSS_GEN_TICKET, generation.pos, VSS_GEN_TICKET_DISTANCE, generation.pos);
    }

    private boolean canSubmitPackingTask(boolean priority) {
        ThreadPoolExecutor executor = packingExecutor();
        int queueLimit = Math.max(1, config.generationPackingQueueLimit);
        if (priority) {
            queueLimit += PRIORITY_PACKING_QUEUE_EXTRA_LIMIT;
        }
        return executor.getActiveCount() < executor.getCorePoolSize()
                || executor.getQueue().size() < queueLimit;
    }

    private PendingPacking submitPackingTask(
            PendingGenerationKey key,
            PendingGeneration generation,
            SectionSerializer.ColumnSnapshot snapshot) {
        ResourceKey<Level> dimension = generation.level.dimension();
        List<GenerationCallback> callbacks = List.copyOf(generation.callbacks);
        long taskEpoch = packingEpoch;
        long columnTimestamp = Math.max(VSSConstants.columnVersion(), generation.minimumTimestamp);
        PendingPacking packing = new PendingPacking(
                key,
                taskEpoch,
                dimension,
                snapshot,
                true,
                columnTimestamp,
                callbacks,
                System.nanoTime());
        submitPackingRunnable(packing);
        totalPackingSubmitted++;
        return packing;
    }

    private void submitPackingRunnable(PendingPacking packing) {
        packingExecutor().execute(new PackingTask(
                packing.priority(),
                nextPackingTaskSequence++,
                () -> packSnapshot(packing)));
    }

    private void registerPacking(PendingPacking packing) {
        packingByColumn.put(packing.key(), packing);
        for (CancelableTaskCallbacks.Token<GenerationCallback> callback : packing.callbacksSnapshot()) {
            indexPackingCallback(packing.key(), callback);
        }
    }

    private void packSnapshot(PendingPacking packing) {
        if (packing.taskEpoch() != packingEpoch || Thread.currentThread().isInterrupted()) {
            return;
        }
        long waitNanos = Math.max(0L, System.nanoTime() - packing.queuedNanos());
        EncodedColumnData columnData = null;
        boolean completed = false;
        boolean failed = false;
        try {
            LoadedColumnData rawColumnData = SectionSerializer.serializeSnapshot(packing.snapshot());
            if (packing.taskEpoch() != packingEpoch || Thread.currentThread().isInterrupted()) {
                return;
            }
            columnData = EncodedColumnData.encode(rawColumnData, packing.columnTimestamp());
            completed = true;
        } catch (Exception e) {
            failed = true;
            VSSLogger.error("Failed to pack generated chunk at "
                    + packing.snapshot().chunkX() + ", " + packing.snapshot().chunkZ(), e);
        }
        if (packing.taskEpoch() != packingEpoch || Thread.currentThread().isInterrupted()) {
            return;
        }

        List<CancelableTaskCallbacks.Token<GenerationCallback>> callbacks =
                packing.finishAndSnapshotCallbacks();
        ArrayList<GenerationResult> results = new ArrayList<>(callbacks.size());
        for (CancelableTaskCallbacks.Token<GenerationCallback> packingCallback : callbacks) {
            if (packingCallback.isCancelled()) {
                continue;
            }
            GenerationCallback callback = packingCallback.callback();
            if (completed) {
                results.add(new GenerationResult(
                        callback.playerUuid(),
                        callback.requestState(),
                        callback.requestId(),
                        packing.dimension(),
                        columnData,
                        false,
                        callback.priority()));
            } else {
                results.add(GenerationResult.notGenerated(
                        callback.playerUuid(),
                        callback.requestState(),
                        callback.requestId(),
                        packing.dimension(),
                        callback.priority()));
            }
        }
        completedPackingResults.add(new PackingResult(
                packing,
                callbacks,
                results,
                completed,
                failed,
                waitNanos));
    }

    private void drainPackingResults(List<GenerationResult> results) {
        PackingResult packingResult;
        while ((packingResult = completedPackingResults.poll()) != null) {
            PendingPacking packing = packingResult.packing();
            packingByColumn.remove(packing.key(), packing);
            for (CancelableTaskCallbacks.Token<GenerationCallback> callback : packingResult.callbacks()) {
                unindexPackingCallback(callback);
            }
            if (packing.taskEpoch() != packingEpoch) {
                continue;
            }
            results.addAll(packingResult.results());
            totalPackingFinished++;
            totalPackingCallbacksCompleted += packingResult.results().size();
            totalPackingWaitNanos += packingResult.waitNanos();
            maxPackingWaitNanos = Math.max(maxPackingWaitNanos, packingResult.waitNanos());
            if (packingResult.completed()) {
                totalPackingCompleted++;
                if (packing.generationWork()) {
                    totalCompleted++;
                } else {
                    totalLivePackingCompleted++;
                }
            }
            if (packingResult.failed()) {
                totalPackingFailures++;
            }
        }
    }

    private void drainDeferredGenerationResults(List<GenerationResult> results) {
        while (!deferredGenerationResults.isEmpty()) {
            results.add(deferredGenerationResults.removeFirst());
        }
    }

    private ThreadPoolExecutor packingExecutor() {
        ThreadPoolExecutor executor = this.packingExecutor;
        if (executor != null && !executor.isShutdown()) {
            resizePackingExecutor(executor, Math.max(1, config.generationPackingThreads));
            return executor;
        }

        int threads = Math.max(1, config.generationPackingThreads);
        ThreadPoolExecutor created = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "VSS-LOD-Packer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        created.prestartAllCoreThreads();
        this.packingExecutor = created;
        return created;
    }

    private void resizePackingExecutor(ThreadPoolExecutor executor, int threads) {
        if (executor.getCorePoolSize() == threads && executor.getMaximumPoolSize() == threads) {
            return;
        }
        if (threads > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(threads);
            executor.setCorePoolSize(threads);
        } else {
            executor.setCorePoolSize(threads);
            executor.setMaximumPoolSize(threads);
        }
    }

    private void shutdownPackingExecutor() {
        ThreadPoolExecutor executor = this.packingExecutor;
        if (executor != null) {
            executor.shutdownNow();
            this.packingExecutor = null;
        }
    }

    private void incrementCount(UUID playerUuid) {
        perPlayerActiveCount.merge(playerUuid, 1, Integer::sum);
    }

    private void decrementCount(UUID playerUuid) {
        Integer count = perPlayerActiveCount.get(playerUuid);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            perPlayerActiveCount.remove(playerUuid);
        } else {
            perPlayerActiveCount.put(playerUuid, count - 1);
        }
    }

    private void incrementQueuedCount(UUID playerUuid) {
        perPlayerQueuedCount.merge(playerUuid, 1, Integer::sum);
    }

    private void decrementQueuedCount(UUID playerUuid) {
        Integer count = perPlayerQueuedCount.get(playerUuid);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            perPlayerQueuedCount.remove(playerUuid);
        } else {
            perPlayerQueuedCount.put(playerUuid, count - 1);
        }
    }

    private static boolean removeCallback(List<GenerationCallback> callbacks, UUID playerUuid, int requestId) {
        return removeCallbackEntry(callbacks, playerUuid, requestId) != null;
    }

    private static GenerationCallback removeCallbackEntry(List<GenerationCallback> callbacks, UUID playerUuid, int requestId) {
        Iterator<GenerationCallback> iterator = callbacks.iterator();
        while (iterator.hasNext()) {
            GenerationCallback callback = iterator.next();
            if (callback.playerUuid().equals(playerUuid) && callback.requestId() == requestId) {
                iterator.remove();
                return callback;
            }
        }
        return null;
    }

    private static GenerationCallback findCallback(
            List<GenerationCallback> callbacks,
            UUID playerUuid,
            int requestId) {
        for (GenerationCallback callback : callbacks) {
            if (callback.playerUuid().equals(playerUuid) && callback.requestId() == requestId) {
                return callback;
            }
        }
        return null;
    }

    private void pruneStalePlayerRequests(MinecraftServer server, List<GenerationResult> results) {
        if (playerRequestIndex.isEmpty()) {
            playerGenerationViews.clear();
            lastPrunedPlayerViews.clear();
            return;
        }

        boolean rebuildPriority = false;
        for (UUID playerUuid : List.copyOf(playerRequestIndex.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null) {
                pruneAllRequestsForPlayer(playerUuid, results);
                playerGenerationViews.remove(playerUuid);
                lastPrunedPlayerViews.remove(playerUuid);
                continue;
            }

            PlayerGenerationView view = PlayerGenerationView.from(player);
            playerGenerationViews.put(playerUuid, view);
            PlayerGenerationView previous = lastPrunedPlayerViews.put(playerUuid, view);
            if (view.equals(previous)) {
                continue;
            }
            pruneMovedPlayerRequests(playerUuid, player, results);
            rebuildPriority = true;
        }

        playerGenerationViews.keySet().removeIf(playerUuid -> !playerRequestIndex.containsKey(playerUuid));
        lastPrunedPlayerViews.keySet().removeIf(playerUuid -> !playerRequestIndex.containsKey(playerUuid));
        if (rebuildPriority && !queued.isEmpty()) {
            rebuildQueuedPriority();
        }
    }

    private void pruneAllRequestsForPlayer(UUID playerUuid, List<GenerationResult> results) {
        Set<RequestKey> requests = playerRequestIndex.get(playerUuid);
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (RequestKey requestKey : List.copyOf(requests)) {
            cancelIndexedRequestAsStale(requestKey, null, results);
        }
    }

    private void pruneMovedPlayerRequests(UUID playerUuid, ServerPlayer player, List<GenerationResult> results) {
        Set<RequestKey> requests = playerRequestIndex.get(playerUuid);
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (RequestKey requestKey : List.copyOf(requests)) {
            cancelIndexedRequestAsStale(requestKey, player, results);
        }
    }

    private boolean cancelIndexedRequestAsStale(
            RequestKey requestKey,
            ServerPlayer player,
            List<GenerationResult> results) {
        GenerationLocation location = requestIndex.get(requestKey);
        if (location == null) {
            unindexCallback(requestKey.playerUuid(), requestKey.requestId());
            return false;
        }

        if (location.stage() == GenerationStage.PACKING) {
            CancelableTaskCallbacks.Token<GenerationCallback> packingCallback = packingCallbacks.get(requestKey);
            if (packingCallback == null) {
                unindexCallback(requestKey.playerUuid(), requestKey.requestId());
                return false;
            }
            if (player != null && !isStale(player, location.key())) {
                return false;
            }
            GenerationCallback callback = packingCallback.callback();
            if (packingCallback.cancel()) {
                totalPackingCancelled++;
            }
            results.add(GenerationResult.notGenerated(
                    callback.playerUuid(),
                    callback.requestState(),
                    callback.requestId(),
                    location.key().dimension(),
                    callback.priority()));
            unindexPackingCallback(packingCallback);
            return true;
        }

        boolean ticketWait = location.stage() == GenerationStage.TICKET_WAIT;
        LinkedHashMap<PendingGenerationKey, PendingGeneration> generations = ticketWait ? active : queued;
        PendingGeneration generation = generations.get(location.key());
        if (generation == null) {
            unindexCallback(requestKey.playerUuid(), requestKey.requestId());
            return false;
        }
        if (player != null && !isStale(player, generation)) {
            return false;
        }

        GenerationCallback callback = removeCallbackEntry(
                generation.callbacks,
                requestKey.playerUuid(),
                requestKey.requestId());
        if (callback == null) {
            unindexCallback(requestKey.playerUuid(), requestKey.requestId());
            return false;
        }

        results.add(GenerationResult.notGenerated(
                callback.playerUuid(),
                callback.requestState(),
                callback.requestId(),
                generation.level.dimension(),
                callback.priority()));
        unindexCallback(callback.playerUuid(), callback.requestId());
        if (ticketWait) {
            decrementCount(callback.playerUuid());
        } else {
            decrementQueuedCount(callback.playerUuid());
        }
        if (generation.callbacks.isEmpty()) {
            if (ticketWait) {
                removeTicket(generation);
            }
            generations.remove(location.key());
        } else if (!ticketWait) {
            refreshQueuedPriority(location.key(), generation);
        }
        return true;
    }

    private boolean isStale(ServerPlayer player, PendingGeneration generation) {
        return isStale(
                player,
                new PendingGenerationKey(
                        generation.level.dimension(),
                        generation.pos.x,
                        generation.pos.z));
    }

    private boolean isStale(ServerPlayer player, PendingGenerationKey key) {
        if (!player.serverLevel().dimension().equals(key.dimension())) {
            return true;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = config.effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER;
        return PositionUtil.chebyshevDistance(key.cx(), key.cz(), playerCx, playerCz) > maxDistance;
    }

    private boolean cancelIndexedRequest(RequestKey requestKey, boolean promoteAfterActiveRemoval) {
        GenerationLocation location = requestIndex.get(requestKey);
        if (location == null) {
            return false;
        }

        if (location.stage() == GenerationStage.PACKING) {
            CancelableTaskCallbacks.Token<GenerationCallback> packingCallback = packingCallbacks.get(requestKey);
            if (packingCallback == null) {
                unindexCallback(requestKey.playerUuid(), requestKey.requestId());
                return false;
            }
            if (packingCallback.cancel()) {
                totalPackingCancelled++;
            }
            unindexPackingCallback(packingCallback);
            return true;
        }

        boolean ticketWait = location.stage() == GenerationStage.TICKET_WAIT;
        LinkedHashMap<PendingGenerationKey, PendingGeneration> generations = ticketWait ? active : queued;
        PendingGeneration generation = generations.get(location.key());
        if (generation == null) {
            unindexCallback(requestKey.playerUuid(), requestKey.requestId());
            return false;
        }

        if (!removeCallback(generation.callbacks, requestKey.playerUuid(), requestKey.requestId())) {
            unindexCallback(requestKey.playerUuid(), requestKey.requestId());
            return false;
        }

        unindexCallback(requestKey.playerUuid(), requestKey.requestId());
        if (ticketWait) {
            decrementCount(requestKey.playerUuid());
        } else {
            decrementQueuedCount(requestKey.playerUuid());
        }
        if (generation.callbacks.isEmpty()) {
            if (ticketWait) {
                removeTicket(generation);
            }
            generations.remove(location.key());
            if (ticketWait && promoteAfterActiveRemoval) {
                promoteQueued();
            }
        } else if (!ticketWait) {
            refreshQueuedPriority(location.key(), generation);
        }
        return true;
    }

    private void indexGeneration(
            PendingGenerationKey key,
            PendingGeneration generation,
            GenerationStage stage) {
        for (GenerationCallback callback : generation.callbacks) {
            indexCallback(key, callback, stage);
        }
    }

    private void unindexGeneration(PendingGeneration generation) {
        for (GenerationCallback callback : generation.callbacks) {
            unindexCallback(callback.playerUuid(), callback.requestId());
        }
    }

    private void indexCallback(
            PendingGenerationKey key,
            GenerationCallback callback,
            GenerationStage stage) {
        RequestKey requestKey = new RequestKey(callback.playerUuid(), callback.requestId());
        requestIndex.put(requestKey, new GenerationLocation(key, stage));
        playerRequestIndex.computeIfAbsent(callback.playerUuid(), ignored -> new HashSet<>()).add(requestKey);
    }

    private void indexPackingCallback(
            PendingGenerationKey key,
            CancelableTaskCallbacks.Token<GenerationCallback> callback) {
        GenerationCallback generationCallback = callback.callback();
        RequestKey requestKey = new RequestKey(
                generationCallback.playerUuid(),
                generationCallback.requestId());
        packingCallbacks.put(requestKey, callback);
        requestIndex.put(requestKey, new GenerationLocation(key, GenerationStage.PACKING));
        playerRequestIndex.computeIfAbsent(
                generationCallback.playerUuid(),
                ignored -> new HashSet<>()).add(requestKey);
    }

    private void unindexPackingCallback(CancelableTaskCallbacks.Token<GenerationCallback> callback) {
        GenerationCallback generationCallback = callback.callback();
        RequestKey requestKey = new RequestKey(
                generationCallback.playerUuid(),
                generationCallback.requestId());
        if (!GenerationSchedulingPolicy.removeIdentity(packingCallbacks, requestKey, callback)) {
            return;
        }
        unindexCallback(generationCallback.playerUuid(), generationCallback.requestId());
    }

    private void unindexCallback(UUID playerUuid, int requestId) {
        RequestKey requestKey = new RequestKey(playerUuid, requestId);
        requestIndex.remove(requestKey);
        Set<RequestKey> playerRequests = playerRequestIndex.get(playerUuid);
        if (playerRequests == null) {
            return;
        }
        playerRequests.remove(requestKey);
        if (playerRequests.isEmpty()) {
            playerRequestIndex.remove(playerUuid);
            playerGenerationViews.remove(playerUuid);
            lastPrunedPlayerViews.remove(playerUuid);
        }
    }

    private record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {
    }

    private record RequestKey(UUID playerUuid, int requestId) {
    }

    private enum GenerationStage {
        QUEUED,
        TICKET_WAIT,
        PACKING
    }

    private record GenerationLocation(PendingGenerationKey key, GenerationStage stage) {
    }

    private record QueuedCallbackCandidate(
            RequestKey requestKey,
            GenerationLocation location,
            int ring,
            boolean priority) {
    }

    private record PlayerGenerationView(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        private static PlayerGenerationView from(ServerPlayer player) {
            return new PlayerGenerationView(
                    player.serverLevel().dimension(),
                    player.getBlockX() >> 4,
                    player.getBlockZ() >> 4);
        }
    }

    private static final class PendingGeneration {
        private final ChunkPos pos;
        private final ServerLevel level;
        private final List<GenerationCallback> callbacks = new ArrayList<>();
        private long minimumTimestamp;
        private int ticksWaiting;
        private long queueRevision;
        private final long queuedNanos = System.nanoTime();

        private PendingGeneration(ChunkPos pos, ServerLevel level, long minimumTimestamp) {
            this.pos = pos;
            this.level = level;
            this.minimumTimestamp = minimumTimestamp;
        }

        private boolean priority() {
            return callbacks.stream().anyMatch(GenerationCallback::priority);
        }
    }

    public record GenerationCallback(UUID playerUuid, PlayerRequestState requestState, int requestId, boolean priority) {
    }

    private static final class PendingPacking {
        private final PendingGenerationKey key;
        private final long taskEpoch;
        private final ResourceKey<Level> dimension;
        private final SectionSerializer.ColumnSnapshot snapshot;
        private final boolean generationWork;
        private final long columnTimestamp;
        private final boolean priority;
        private final long queuedNanos;
        private final CancelableTaskCallbacks<GenerationCallback> callbacks = new CancelableTaskCallbacks<>();

        private PendingPacking(
                PendingGenerationKey key,
                long taskEpoch,
                ResourceKey<Level> dimension,
                SectionSerializer.ColumnSnapshot snapshot,
                boolean generationWork,
                long columnTimestamp,
                GenerationCallback callback,
                long queuedNanos) {
            this(
                    key,
                    taskEpoch,
                    dimension,
                    snapshot,
                    generationWork,
                    columnTimestamp,
                    List.of(callback),
                    queuedNanos);
        }

        private PendingPacking(
                PendingGenerationKey key,
                long taskEpoch,
                ResourceKey<Level> dimension,
                SectionSerializer.ColumnSnapshot snapshot,
                boolean generationWork,
                long columnTimestamp,
                List<GenerationCallback> generationCallbacks,
                long queuedNanos) {
            this.key = key;
            this.taskEpoch = taskEpoch;
            this.dimension = dimension;
            this.snapshot = snapshot;
            this.generationWork = generationWork;
            this.columnTimestamp = columnTimestamp;
            this.priority = generationCallbacks.stream().anyMatch(GenerationCallback::priority);
            this.queuedNanos = queuedNanos;
            for (GenerationCallback callback : generationCallbacks) {
                this.callbacks.add(callback);
            }
        }

        private CancelableTaskCallbacks.Token<GenerationCallback> tryAddCallback(
                GenerationCallback callback,
                long minimumTimestamp) {
            if (minimumTimestamp > columnTimestamp
                    || (callback.priority() && !priority)) {
                return null;
            }
            return callbacks.add(callback);
        }

        private List<CancelableTaskCallbacks.Token<GenerationCallback>> callbacksSnapshot() {
            return callbacks.snapshot();
        }

        private List<CancelableTaskCallbacks.Token<GenerationCallback>> finishAndSnapshotCallbacks() {
            return callbacks.finish();
        }

        private boolean isFinished() {
            return callbacks.isFinished();
        }

        private CancelableTaskCallbacks.Token<GenerationCallback> initialCallback() {
            return callbacks.snapshot().get(0);
        }

        private PendingGenerationKey key() {
            return key;
        }

        private long taskEpoch() {
            return taskEpoch;
        }

        private ResourceKey<Level> dimension() {
            return dimension;
        }

        private SectionSerializer.ColumnSnapshot snapshot() {
            return snapshot;
        }

        private boolean generationWork() {
            return generationWork;
        }

        private long columnTimestamp() {
            return columnTimestamp;
        }

        private boolean priority() {
            return priority;
        }

        private long queuedNanos() {
            return queuedNanos;
        }
    }

    private record PackingResult(
            PendingPacking packing,
            List<CancelableTaskCallbacks.Token<GenerationCallback>> callbacks,
            List<GenerationResult> results,
            boolean completed,
            boolean failed,
            long waitNanos) {
    }

    private record QueuedSelection(PendingGenerationKey key, PendingGeneration generation) {
    }

    private record QueuedGenerationEntry(
            PendingGenerationKey key,
            long revision,
            boolean priority,
            int ring,
            long sequence) implements Comparable<QueuedGenerationEntry> {
        @Override
        public int compareTo(QueuedGenerationEntry other) {
            return GenerationSchedulingPolicy.compare(
                    priority,
                    ring,
                    sequence,
                    other.priority,
                    other.ring,
                    other.sequence);
        }
    }

    private record PackingTask(boolean priority, long sequence, Runnable delegate) implements Runnable, Comparable<PackingTask> {
        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public int compareTo(PackingTask other) {
            int priorityCompare = Boolean.compare(other.priority, priority);
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(sequence, other.sequence);
        }
    }

    public record GenerationResult(
            UUID playerUuid,
            PlayerRequestState requestState,
            int requestId,
            ResourceKey<Level> dimension,
            EncodedColumnData columnData,
            boolean notGenerated,
            boolean priority) {
        private static GenerationResult notGenerated(UUID playerUuid, PlayerRequestState requestState, int requestId, ResourceKey<Level> dimension, boolean priority) {
            return new GenerationResult(playerUuid, requestState, requestId, dimension, null, true, priority);
        }
    }
}
