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
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    private final Map<UUID, Integer> perPlayerQueuedCount = new HashMap<>();
    private final Map<RequestKey, GenerationLocation> requestIndex = new HashMap<>();
    private final Map<UUID, Set<RequestKey>> playerRequestIndex = new HashMap<>();
    private final Map<UUID, PlayerGenerationView> playerGenerationViews = new HashMap<>();
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
    private double startBudget;
    private int startsThisTick;
    private volatile long packingEpoch;
    private long nextPackingTaskSequence;

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
        PendingGenerationKey key = new PendingGenerationKey(level.dimension(), cx, cz);
        PendingGeneration existing = active.get(key);
        if (existing != null) {
            GenerationCallback callback = new GenerationCallback(playerUuid, requestState, requestId, false);
            existing.minimumTimestamp = Math.max(existing.minimumTimestamp, minimumTimestamp);
            existing.callbacks.add(callback);
            indexCallback(key, callback, true);
            incrementCount(playerUuid);
            return true;
        }

        PendingGeneration queuedGeneration = queued.get(key);
        if (queuedGeneration != null) {
            if (!ensureQueueCapacityFor(playerUuid, queuedGeneration)) {
                return false;
            }
            GenerationCallback callback = new GenerationCallback(playerUuid, requestState, requestId, false);
            queuedGeneration.minimumTimestamp = Math.max(queuedGeneration.minimumTimestamp, minimumTimestamp);
            queuedGeneration.callbacks.add(callback);
            indexCallback(key, callback, false);
            incrementQueuedCount(playerUuid);
            return true;
        }

        ChunkPos pos = new ChunkPos(cx, cz);
        PendingGeneration generation = new PendingGeneration(pos, level, minimumTimestamp);
        generation.callbacks.add(new GenerationCallback(playerUuid, requestState, requestId, false));
        if (canStart(generation) && tryConsumeStartBudget()) {
            startGeneration(key, generation);
        } else {
            if (!ensureQueueCapacityFor(playerUuid, generation)) {
                return false;
            }
            queued.put(key, generation);
            incrementQueuedCount(playerUuid);
            indexGeneration(key, generation, false);
            totalQueued++;
        }
        return true;
    }

    public synchronized boolean isGenerating(ResourceKey<Level> dimension, int cx, int cz) {
        PendingGenerationKey key = new PendingGenerationKey(dimension, cx, cz);
        return active.containsKey(key) || queued.containsKey(key);
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
        if (!canSubmitPackingTask(priority)) {
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
            submitPackingRunnable(
                    priority,
                    () -> packSnapshot(
                            taskEpoch,
                            level.dimension(),
                            snapshot,
                            List.of(new GenerationCallback(playerUuid, requestState, requestId, priority)),
                            false,
                            columnTimestamp));
            totalPackingSubmitted++;
            totalLivePackingSubmitted++;
            return true;
        } catch (RejectedExecutionException e) {
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
            PendingGeneration generation = iterator.next().getValue();
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
                            callback.playerUuid(), callback.requestState(), callback.requestId(), generation.level.dimension()));
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
            if (!canSubmitPackingTask(false)) {
                continue;
            }

            processedThisTick++;
            try {
                SectionSerializer.ColumnSnapshot snapshot = SectionSerializer.snapshotColumn(
                        generation.level, chunk, generation.pos.x, generation.pos.z);
                submitPackingTask(generation, snapshot);
            } catch (Exception e) {
                VSSLogger.error("Failed to snapshot generated chunk at " + generation.pos.x + ", " + generation.pos.z, e);
                for (GenerationCallback callback : generation.callbacks) {
                    results.add(GenerationResult.notGenerated(
                            callback.playerUuid(), callback.requestState(), callback.requestId(), generation.level.dimension()));
                    decrementCount(callback.playerUuid());
                }
            }

            unindexGeneration(generation);
            removeTicket(generation);
            iterator.remove();
        }
        return results;
    }

    public synchronized void cancelRequest(UUID playerUuid, int requestId) {
        RequestKey requestKey = new RequestKey(playerUuid, requestId);
        if (cancelIndexedRequest(requestKey, true)) {
            return;
        }

        Iterator<Map.Entry<PendingGenerationKey, PendingGeneration>> queuedIterator = queued.entrySet().iterator();
        while (queuedIterator.hasNext()) {
            Map.Entry<PendingGenerationKey, PendingGeneration> entry = queuedIterator.next();
            PendingGeneration generation = entry.getValue();
            if (removeCallback(generation.callbacks, playerUuid, requestId)) {
                unindexCallback(playerUuid, requestId);
                decrementQueuedCount(playerUuid);
                if (generation.callbacks.isEmpty()) {
                    queuedIterator.remove();
                }
                return;
            }
        }

        Iterator<Map.Entry<PendingGenerationKey, PendingGeneration>> activeIterator = active.entrySet().iterator();
        while (activeIterator.hasNext()) {
            Map.Entry<PendingGenerationKey, PendingGeneration> entry = activeIterator.next();
            PendingGeneration generation = entry.getValue();
            if (removeCallback(generation.callbacks, playerUuid, requestId)) {
                unindexCallback(playerUuid, requestId);
                decrementCount(playerUuid);
                if (generation.callbacks.isEmpty()) {
                    removeTicket(generation);
                    activeIterator.remove();
                    promoteQueued();
                }
                return;
            }
        }
    }

    public synchronized void removePlayer(UUID playerUuid) {
        Set<RequestKey> indexedRequests = playerRequestIndex.remove(playerUuid);
        if (indexedRequests != null) {
            for (RequestKey requestKey : List.copyOf(indexedRequests)) {
                cancelIndexedRequest(requestKey, false);
            }
        }

        Iterator<Map.Entry<PendingGenerationKey, PendingGeneration>> queuedIterator = queued.entrySet().iterator();
        while (queuedIterator.hasNext()) {
            PendingGeneration generation = queuedIterator.next().getValue();
            generation.callbacks.removeIf(callback -> {
                if (!callback.playerUuid().equals(playerUuid)) {
                    return false;
                }
                unindexCallback(callback.playerUuid(), callback.requestId());
                decrementQueuedCount(callback.playerUuid());
                return true;
            });
            if (generation.callbacks.isEmpty()) {
                queuedIterator.remove();
            }
        }

        Iterator<Map.Entry<PendingGenerationKey, PendingGeneration>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingGeneration generation = iterator.next().getValue();
            generation.callbacks.removeIf(callback -> {
                if (!callback.playerUuid().equals(playerUuid)) {
                    return false;
                }
                unindexCallback(callback.playerUuid(), callback.requestId());
                decrementCount(callback.playerUuid());
                return true;
            });
            if (!generation.callbacks.isEmpty()) {
                continue;
            }
            removeTicket(generation);
            iterator.remove();
        }
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
        active.clear();
        queued.clear();
        perPlayerActiveCount.clear();
        perPlayerQueuedCount.clear();
        requestIndex.clear();
        playerRequestIndex.clear();
        playerGenerationViews.clear();
    }

    public synchronized void shutdown() {
        releaseIdleMemory();
    }

    public synchronized String diagnostics() {
        ThreadPoolExecutor executor = this.packingExecutor;
        int packingActive = executor != null ? executor.getActiveCount() : 0;
        int packingQueued = executor != null ? executor.getQueue().size() : 0;
        return String.format("submitted=%d, completed=%d, active=%d, queued=%d, everQueued=%d, timeouts=%d, packingSubmitted=%d, packingActive=%d, packingQueued=%d, packingFailures=%d, startBudget=%.1f",
                totalSubmitted,
                totalCompleted,
                active.size(),
                queued.size(),
                totalQueued,
                totalTimeouts,
                totalPackingSubmitted,
                packingActive,
                packingQueued,
                totalPackingFailures,
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
                        totalPackingFailures,
                        totalLivePackingSubmitted,
                        totalLivePackingCompleted))
                .append(Component.literal("; "))
                .append(storageDiagnostics);
    }

    private void promoteQueued() {
        if (queued.isEmpty() || startBudget < 1.0D || startsThisTick >= config.generationStartsPerTickLimit) {
            return;
        }

        while (active.size() < config.generationConcurrencyLimitGlobal
                && startBudget >= 1.0D
                && startsThisTick < config.generationStartsPerTickLimit) {
            Map.Entry<PendingGenerationKey, PendingGeneration> entry = selectNearestStartableQueuedGeneration();
            if (entry == null) {
                break;
            }
            PendingGeneration generation = entry.getValue();

            if (!tryConsumeStartBudget()) {
                break;
            }
            for (GenerationCallback callback : generation.callbacks) {
                decrementQueuedCount(callback.playerUuid());
            }
            queued.remove(entry.getKey());
            startGeneration(entry.getKey(), generation);
        }
    }

    private Map.Entry<PendingGenerationKey, PendingGeneration> selectNearestStartableQueuedGeneration() {
        Map.Entry<PendingGenerationKey, PendingGeneration> best = null;
        int bestRing = Integer.MAX_VALUE;
        for (Map.Entry<PendingGenerationKey, PendingGeneration> entry : queued.entrySet()) {
            PendingGeneration generation = entry.getValue();
            if (!canStart(generation)) {
                continue;
            }
            int ring = priorityRing(generation);
            if (best == null || ring < bestRing) {
                best = entry;
                bestRing = ring;
            }
        }
        return best;
    }

    private boolean canStart(PendingGeneration generation) {
        if (active.size() >= config.generationConcurrencyLimitGlobal) {
            return false;
        }
        for (GenerationCallback callback : generation.callbacks) {
            int playerActive = perPlayerActiveCount.getOrDefault(callback.playerUuid(), 0);
            if (playerActive >= config.generationConcurrencyLimitPerPlayer) {
                return false;
            }
        }
        return true;
    }

    private boolean canQueue(UUID playerUuid) {
        int queuedForPlayer = perPlayerQueuedCount.getOrDefault(playerUuid, 0);
        int maxQueuedForPlayer = Math.max(1, config.generationRateLimitPerPlayer * config.generationTimeoutSeconds);
        return queuedForPlayer < maxQueuedForPlayer;
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
            if (location == null || location.active()) {
                continue;
            }
            PendingGeneration generation = queued.get(location.key());
            if (generation == null) {
                continue;
            }
            int ring = priorityRingForView(generation, view);
            if (farthest == null || ring > farthest.ring()) {
                farthest = new QueuedCallbackCandidate(requestKey, location, ring);
            }
        }

        if (farthest == null || incomingRing >= farthest.ring()) {
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
        if (generation.callbacks.isEmpty()) {
            queued.remove(candidate.location().key());
        }
        return true;
    }

    private PlayerGenerationView currentGenerationView(UUID playerUuid, ServerLevel level) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuid);
        if (player != null) {
            PlayerGenerationView view = PlayerGenerationView.from(player);
            playerGenerationViews.put(playerUuid, view);
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
        indexGeneration(key, generation, true);
        totalSubmitted++;
    }

    private void removeTicket(PendingGeneration generation) {
        generation.level.getChunkSource().removeRegionTicket(VSS_GEN_TICKET, generation.pos, VSS_GEN_TICKET_DISTANCE, generation.pos);
    }

    private boolean canSubmitPackingTask() {
        return canSubmitPackingTask(false);
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

    private void submitPackingTask(PendingGeneration generation, SectionSerializer.ColumnSnapshot snapshot) {
        ResourceKey<Level> dimension = generation.level.dimension();
        List<GenerationCallback> callbacks = List.copyOf(generation.callbacks);
        boolean priority = callbacks.stream().anyMatch(GenerationCallback::priority);
        try {
            long taskEpoch = packingEpoch;
            long columnTimestamp = Math.max(VSSConstants.columnVersion(), generation.minimumTimestamp);
            submitPackingRunnable(priority, () -> packSnapshot(taskEpoch, dimension, snapshot, callbacks, true, columnTimestamp));
            totalPackingSubmitted++;
        } catch (RejectedExecutionException e) {
            throw e;
        }
    }

    private void submitPackingRunnable(boolean priority, Runnable runnable) {
        packingExecutor().execute(new PackingTask(priority, nextPackingTaskSequence++, runnable));
    }

    private void packSnapshot(
            long taskEpoch,
            ResourceKey<Level> dimension,
            SectionSerializer.ColumnSnapshot snapshot,
            List<GenerationCallback> callbacks,
            boolean generationWork,
            long columnTimestamp) {
        if (taskEpoch != packingEpoch || Thread.currentThread().isInterrupted()) {
            return;
        }
        ArrayList<GenerationResult> results = new ArrayList<>(callbacks.size());
        boolean completed = false;
        boolean failed = false;
        try {
            LoadedColumnData rawColumnData = SectionSerializer.serializeSnapshot(snapshot);
            if (taskEpoch != packingEpoch || Thread.currentThread().isInterrupted()) {
                return;
            }
            EncodedColumnData columnData = EncodedColumnData.encode(rawColumnData, columnTimestamp);
            for (GenerationCallback callback : callbacks) {
                results.add(new GenerationResult(
                        callback.playerUuid(),
                        callback.requestState(),
                        callback.requestId(),
                        dimension,
                        columnData,
                        false,
                        callback.priority()));
            }
            completed = true;
        } catch (Exception e) {
            failed = true;
            VSSLogger.error("Failed to pack generated chunk at " + snapshot.chunkX() + ", " + snapshot.chunkZ(), e);
            for (GenerationCallback callback : callbacks) {
                results.add(GenerationResult.notGenerated(callback.playerUuid(), callback.requestState(), callback.requestId(), dimension, callback.priority()));
            }
        }
        if (taskEpoch != packingEpoch || Thread.currentThread().isInterrupted()) {
            return;
        }
        completedPackingResults.add(new PackingResult(results, completed, failed, generationWork));
    }

    private void drainPackingResults(List<GenerationResult> results) {
        PackingResult packingResult;
        while ((packingResult = completedPackingResults.poll()) != null) {
            results.addAll(packingResult.results());
            if (packingResult.generationWork()) {
                for (GenerationResult result : packingResult.results()) {
                    decrementCount(result.playerUuid());
                }
            }
            if (packingResult.completed()) {
                if (packingResult.generationWork()) {
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

    private void pruneStalePlayerRequests(MinecraftServer server, List<GenerationResult> results) {
        if (playerRequestIndex.isEmpty()) {
            playerGenerationViews.clear();
            return;
        }

        for (UUID playerUuid : List.copyOf(playerRequestIndex.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null) {
                pruneAllRequestsForPlayer(playerUuid, results);
                playerGenerationViews.remove(playerUuid);
                continue;
            }

            PlayerGenerationView view = PlayerGenerationView.from(player);
            PlayerGenerationView previous = playerGenerationViews.put(playerUuid, view);
            if (view.equals(previous)) {
                continue;
            }
            pruneMovedPlayerRequests(playerUuid, player, results);
        }

        playerGenerationViews.keySet().removeIf(playerUuid -> !playerRequestIndex.containsKey(playerUuid));
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

        LinkedHashMap<PendingGenerationKey, PendingGeneration> generations = location.active() ? active : queued;
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
        if (location.active()) {
            decrementCount(callback.playerUuid());
        } else {
            decrementQueuedCount(callback.playerUuid());
        }
        if (generation.callbacks.isEmpty()) {
            if (location.active()) {
                removeTicket(generation);
            }
            generations.remove(location.key());
        }
        return true;
    }

    private boolean isStale(ServerPlayer player, PendingGeneration generation) {
        if (!player.serverLevel().dimension().equals(generation.level.dimension())) {
            return true;
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = config.effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER;
        return PositionUtil.chebyshevDistance(generation.pos.x, generation.pos.z, playerCx, playerCz) > maxDistance;
    }

    private boolean cancelIndexedRequest(RequestKey requestKey, boolean promoteAfterActiveRemoval) {
        GenerationLocation location = requestIndex.get(requestKey);
        if (location == null) {
            return false;
        }

        LinkedHashMap<PendingGenerationKey, PendingGeneration> generations = location.active() ? active : queued;
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
        if (location.active()) {
            decrementCount(requestKey.playerUuid());
        } else {
            decrementQueuedCount(requestKey.playerUuid());
        }
        if (generation.callbacks.isEmpty()) {
            if (location.active()) {
                removeTicket(generation);
            }
            generations.remove(location.key());
            if (location.active() && promoteAfterActiveRemoval) {
                promoteQueued();
            }
        }
        return true;
    }

    private void indexGeneration(PendingGenerationKey key, PendingGeneration generation, boolean activeGeneration) {
        for (GenerationCallback callback : generation.callbacks) {
            indexCallback(key, callback, activeGeneration);
        }
    }

    private void unindexGeneration(PendingGeneration generation) {
        for (GenerationCallback callback : generation.callbacks) {
            unindexCallback(callback.playerUuid(), callback.requestId());
        }
    }

    private void indexCallback(PendingGenerationKey key, GenerationCallback callback, boolean activeGeneration) {
        RequestKey requestKey = new RequestKey(callback.playerUuid(), callback.requestId());
        requestIndex.put(requestKey, new GenerationLocation(key, activeGeneration));
        playerRequestIndex.computeIfAbsent(callback.playerUuid(), ignored -> new HashSet<>()).add(requestKey);
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
        }
    }

    private record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {
    }

    private record RequestKey(UUID playerUuid, int requestId) {
    }

    private record GenerationLocation(PendingGenerationKey key, boolean active) {
    }

    private record QueuedCallbackCandidate(RequestKey requestKey, GenerationLocation location, int ring) {
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

        private PendingGeneration(ChunkPos pos, ServerLevel level, long minimumTimestamp) {
            this.pos = pos;
            this.level = level;
            this.minimumTimestamp = minimumTimestamp;
        }
    }

    public record GenerationCallback(UUID playerUuid, PlayerRequestState requestState, int requestId, boolean priority) {
    }

    private record PackingResult(List<GenerationResult> results, boolean completed, boolean failed, boolean generationWork) {
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
        private static GenerationResult notGenerated(UUID playerUuid, PlayerRequestState requestState, int requestId, ResourceKey<Level> dimension) {
            return notGenerated(playerUuid, requestState, requestId, dimension, false);
        }

        private static GenerationResult notGenerated(UUID playerUuid, PlayerRequestState requestState, int requestId, ResourceKey<Level> dimension, boolean priority) {
            return new GenerationResult(playerUuid, requestState, requestId, dimension, null, true, priority);
        }
    }
}
