package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Explicit area pre-generation without VSS admission or throughput limits. */
public final class ChunkyGenerationService {
    private final ChunkGenerationService generation;
    private final ColumnLodCache cache;
    private final PersistentColumnLodStore store;
    private final PersistentColumnWriter writer;
    private final DiskTaskRuntime disk;
    private final PlayerRequestRegistry players;
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private Job job;
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    public ChunkyGenerationService(ChunkGenerationService generation, ColumnLodCache cache,
            PersistentColumnLodStore store, PersistentColumnWriter writer, DiskTaskRuntime disk,
            PlayerRequestRegistry players) {
        this.generation = generation;
        this.cache = cache;
        this.store = store;
        this.writer = writer;
        this.disk = disk;
        this.players = players;
    }

    public int start(CommandSourceStack source, ChunkyArea area) {
        if (VSSServerNetworking.isServerStopping()) return error(source, "vss.command.chunky.server_stopping");
        if (hasActiveJob()) return error(source, "vss.command.chunky.busy");
        if (!VSSServerConfig.CONFIG.enabled || !VSSServerConfig.CONFIG.enableChunkGeneration)
            return error(source, "vss.command.chunky.generation_disabled");
        if (!store.enabled() && !IntegratedPregenBridge.supports(source.getServer()))
            return error(source, "vss.command.chunky.storage_disabled");
        ServerLevel level = source.getLevel();
        if (!insideBorder(level, area.minChunkX(), area.minChunkZ())
                || !insideBorder(level, area.maxChunkX(), area.maxChunkZ()))
            return error(source, "vss.command.chunky.outside_border");
        job = new Job(source, level, area);
        Job proposed = job;
        if (IntegratedPregenBridge.supports(level.getServer())) {
            activate(proposed);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("vss.command.chunky.checking_capacity"), false);
        long epoch = VSSServerNetworking.lifecycleEpoch();
        boolean queued = disk.submitReadUnrestricted(() -> {
            dev.xantha.vss.networking.server.storage.PregenCacheCapacity estimate = null;
            try {
                estimate = dev.xantha.vss.networking.server.storage.PregenCacheCapacity.inspect(
                        level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                                .resolve("data").resolve("vss-column-cache"),
                        VSSServerConfig.CONFIG.persistentColumnCacheRetentionDays);
            } catch (Exception e) { VSSLogger.warn("Pregen capacity scan failed: " + e.getMessage()); }
            var result = estimate;
            level.getServer().execute(() -> {
                if (job != proposed || proposed.stopped || VSSServerNetworking.isLifecycleStale(epoch)) return;
                var config = VSSServerConfig.CONFIG;
                if (result == null) {
                    stop(proposed);
                    source.sendFailure(Component.translatable("vss.command.chunky.capacity_scan_failed"));
                } else if (!result.fits(area.columnCount(), config.persistentColumnCacheMaxMiB, config.persistentColumnCacheMaxEntries)) {
                    stop(proposed);
                    source.sendFailure(Component.translatable("vss.command.chunky.capacity_rejected",
                            String.format(Locale.ROOT, "%.2f", config.persistentColumnCacheMaxMiB / 1024.0),
                            result.capacity(config.persistentColumnCacheMaxMiB, config.persistentColumnCacheMaxEntries),
                            area.columnCount(), result.estimatedColumnBytes()));
                } else {
                    activate(proposed);
                }
            });
        }, error -> { });
        if (!queued) {
            stop(proposed);
            return error(source, "vss.command.chunky.capacity_scan_failed");
        }
        return 1;
    }

    private void activate(Job current) {
        current.capacityPending = false;
        generation.registerBackgroundOwner(current.id);
        disk.setUnrestrictedMode(true);
        CommandSourceStack source = current.source;
        ServerLevel level = current.level;
        ChunkyArea area = current.area;
        source.sendSuccess(() -> Component.translatable("vss.command.chunky.started",
                level.dimension().location().toString(), area.columnCount(),
                area.minChunkX() * 16, area.minChunkZ() * 16,
                area.maxChunkX() * 16 + 15, area.maxChunkZ() * 16 + 15)
                .withStyle(ChatFormatting.GREEN), true);
    }

    public boolean hasActiveJob() { return job != null && !job.stopped; }

    public int status(CommandSourceStack source) {
        if (job == null) return error(source, "vss.command.chunky.no_job");
        Component status = status(job);
        source.sendSuccess(() -> status, false);
        return 1;
    }

    public int pause(CommandSourceStack source) {
        if (!hasActiveJob()) return error(source, "vss.command.chunky.no_active_job");
        job.paused = true;
        job.scanning = false;
        source.sendSuccess(() -> Component.translatable("vss.command.chunky.paused"), true);
        return 1;
    }

    public int resume(CommandSourceStack source) {
        if (!hasActiveJob()) return error(source, "vss.command.chunky.no_active_job");
        if (!VSSServerConfig.CONFIG.enabled || !VSSServerConfig.CONFIG.enableChunkGeneration)
            return error(source, "vss.command.chunky.generation_disabled");
        if (!store.enabled() && !IntegratedPregenBridge.supports(source.getServer()))
            return error(source, "vss.command.chunky.storage_disabled");
        job.paused = false;
        source.sendSuccess(() -> Component.translatable("vss.command.chunky.resumed"), true);
        return 1;
    }

    public int cancel(CommandSourceStack source) {
        if (!hasActiveJob()) return error(source, "vss.command.chunky.no_active_job");
        stop(job);
        source.sendSuccess(() -> Component.translatable("vss.command.chunky.cancelled",
                job.queue.completed(), job.area.columnCount()), true);
        return 1;
    }

    public void clear() {
        if (hasActiveJob()) stop(job);
        job = null;
        completions.clear();
    }

    private void stop(Job current) {
        current.scanning = false;
        current.stopped = true;
        current.stoppedNanos = System.nanoTime();
        generation.removeBackgroundOwner(current.id);
        current.requests.clearAll();
        // Drop encoded column references immediately on cancel/server stop.
        current.queue.close();
        current.ready.clear();
        current.availability.clear();
        disk.setUnrestrictedMode(false);
    }

    public void tick(MinecraftServer server) {
        if (!hasActiveJob()) { completions.clear(); return; }
        Job current = job;
        if (current.capacityPending) return;
        Completion completion;
        while ((completion = completions.poll()) != null) acceptCompletion(current, completion);
        // Deliver confirmed columns while the rest of the area is still running.
        // Strict clients deliberately never drain the unsolicited preload queue.
        current.availability.flush();
        if (current.queue.finished()) {
            finish(current);
            return;
        }
        boolean running = !current.paused && VSSServerConfig.CONFIG.enabled
                && VSSServerConfig.CONFIG.enableChunkGeneration
                && (store.enabled() || IntegratedPregenBridge.supports(current.level.getServer()));
        current.scanning = running;
        if (running && !current.queue.allReserved()) startCacheScanners(current);

        // Visit all ready work once. In-flight reads/tickets/writes never require a per-frame scan.
        int ready = current.ready.size();
        for (int i = 0; i < ready; i++) {
            ChunkyWorkQueue.Work work = current.ready.removeFirst();
            if (work.stage == ChunkyWorkQueue.Stage.GENERATION) {
                if (!running || !submitGeneration(current, work)) current.ready.addLast(work);
            } else if (work.stage == ChunkyWorkQueue.Stage.PERSIST) {
                if (!persist(current, work)) current.ready.addLast(work);
            }
        }
        if (current.queue.finished()) finish(current);
        else if (running && System.nanoTime() - current.lastReportNanos >= REPORT_INTERVAL_NANOS) {
            current.lastReportNanos = System.nanoTime();
            notifyOwner(current, status(current));
        }
    }

    private void startCacheScanners(Job current) {
        int readers = Math.max(1, Runtime.getRuntime().availableProcessors());
        while (current.scanners.get() < readers && current.scanning && !current.queue.allReserved()) {
            current.scanners.incrementAndGet();
            if (!disk.submitReadUnrestricted(() -> {
                try { scanCache(current); }
                finally { current.scanners.decrementAndGet(); }
            }, error -> { })) {
                current.scanners.decrementAndGet();
                break;
            }
        }
    }

    private void scanCache(Job current) {
        long epoch = VSSServerNetworking.lifecycleEpoch();
        while (current.scanning && !current.stopped && !Thread.currentThread().isInterrupted()
                && !VSSServerNetworking.isLifecycleStale(epoch)) {
            ChunkyWorkQueue.Work work = current.queue.reserve();
            if (work == null) return;
            EncodedColumnData data = null;
            boolean saved = false;
            try {
                long dirty = dirty(current, work);
                ColumnLodCache.Entry cached = cache.get(current.level.dimension(), work.chunkX, work.chunkZ);
                if (cached != null && cached.completeColumn() && cached.timestamp() >= dirty) {
                    data = cached.columnData();
                } else {
                    PersistentColumnLodStore.Entry entry = store.read(current.level.getServer(),
                            current.level.dimension(), work.chunkX, work.chunkZ, dirty);
                    if (entry != null) { data = entry.columnData(); saved = true; }
                }
            } catch (Exception error) {
                VSSLogger.warn("VSS chunky cache read failed: " + error.getMessage());
            }
            if (!current.stopped && !VSSServerNetworking.isLifecycleStale(epoch))
                completions.add(new Completion(current.id, work.requestId, data, saved));
        }
    }

    private boolean submitGeneration(Job current, ChunkyWorkQueue.Work work) {
        if (!insideBorder(current.level, work.chunkX, work.chunkZ)) {
            current.queue.finish(work, false);
            return true;
        }
        if (!current.requests.beginRequest(work.requestId, current.level.dimension(),
                PositionUtil.packPosition(work.chunkX, work.chunkZ))) return false;
        boolean accepted = generation.submitGeneration(current.id, current.requests, work.requestId,
                current.level, work.chunkX, work.chunkZ, Math.max(VSSConstants.columnVersion(), dirty(current, work)));
        if (!accepted) { current.requests.clearRequest(work.requestId); return false; }
        work.generationAttempts++;
        work.stage = ChunkyWorkQueue.Stage.GENERATING;
        return true;
    }

    /** Called on the server thread before the normal player result handling. */
    public boolean handleResult(ChunkGenerationService.GenerationResult result) {
        if (job == null || !job.id.equals(result.playerUuid()) || job.requests != result.requestState()) return false;
        if (job.stopped) return true;
        ChunkyWorkQueue.Work work = job.queue.get(result.requestId());
        if (work == null || work.stage != ChunkyWorkQueue.Stage.GENERATING) return true;
        job.requests.clearRequest(work.requestId);
        EncodedColumnData data = result.columnData();
        if (result.notGenerated() || data == null || !data.hasBody() || !data.completeColumn()
                || data.columnStamp() < dirty(job, work)) {
            retryGeneration(job, work);
        } else {
            work.columnData = data;
            work.stage = ChunkyWorkQueue.Stage.PERSIST;
            job.ready.addLast(work);
        }
        return true;
    }

    private boolean persist(Job current, ChunkyWorkQueue.Work work) {
        if (work.columnData.columnStamp() < dirty(current, work)) {
            retryGeneration(current, work);
            return true;
        }
        if (tryLocalImport(current, work)) return true;
        work.stage = ChunkyWorkQueue.Stage.WRITING;
        boolean accepted = writer.writeConfirmed(current.level.getServer(), current.level.dimension(), work.columnData,
                saved -> completions.add(new Completion(current.id, work.requestId, null, saved)));
        if (accepted) work.writeAttempts++;
        else work.stage = ChunkyWorkQueue.Stage.PERSIST;
        return accepted;
    }

    private void acceptCompletion(Job current, Completion completion) {
        if (!current.id.equals(completion.jobId)) return;
        ChunkyWorkQueue.Work work = current.queue.get(completion.requestId);
        if (work == null) return;
        if (work.stage == ChunkyWorkQueue.Stage.READING) {
            EncodedColumnData data = completion.columnData;
            if (data != null && data.completeColumn() && data.hasBody() && data.columnStamp() >= dirty(current, work)) {
                work.reused = true;
                if (completion.saved) {
                    work.columnData = data;
                    if (tryLocalImport(current, work)) return;
                    current.availability.available(work.chunkX, work.chunkZ, data.columnStamp());
                    current.queue.finish(work, true);
                }
                else {
                    work.columnData = data;
                    work.stage = ChunkyWorkQueue.Stage.PERSIST;
                    current.ready.addLast(work);
                }
            } else {
                work.stage = ChunkyWorkQueue.Stage.GENERATION;
                current.ready.addLast(work);
            }
        } else if (work.stage == ChunkyWorkQueue.Stage.IMPORTING) {
            if (completion.saved && work.columnData.columnStamp() >= dirty(current, work)) {
                current.queue.finish(work, true);
            } else if (work.columnData.columnStamp() < dirty(current, work)) {
                retryGeneration(current, work);
            } else {
                work.stage = ChunkyWorkQueue.Stage.PERSIST;
                current.ready.addLast(work);
            }
        } else if (work.stage == ChunkyWorkQueue.Stage.WRITING) {
            if (completion.saved && work.columnData.columnStamp() >= dirty(current, work)) {
                cache.put(current.level.dimension(), work.columnData);
                current.availability.available(work.chunkX, work.chunkZ, work.columnData.columnStamp());
                current.queue.finish(work, true);
            } else if (work.columnData.columnStamp() < dirty(current, work)) retryGeneration(current, work);
            else if (work.writeAttempts < 3) {
                work.stage = ChunkyWorkQueue.Stage.PERSIST;
                current.ready.addLast(work);
            } else current.queue.finish(work, false);
        }
    }

    private boolean tryLocalImport(Job current, ChunkyWorkQueue.Work work) {
        if (work.localAttempted) return false;
        work.localAttempted = true;
        work.stage = ChunkyWorkQueue.Stage.IMPORTING;
        long epoch = VSSServerNetworking.lifecycleEpoch();
        boolean accepted = IntegratedPregenBridge.offer(current.level.getServer(), current.level.dimension(), work.columnData,
                () -> !current.stopped && !VSSServerNetworking.isLifecycleStale(epoch),
                imported -> completions.add(new Completion(current.id, work.requestId, null, imported)));
        if (!accepted) {
            work.stage = ChunkyWorkQueue.Stage.PERSIST;
            // A full local import queue is transient. Keep this work ready for
            // another tick rather than spilling distant columns to an unseen cache.
            if (IntegratedPregenBridge.supports(current.level.getServer())
                    && current.level.players().stream().anyMatch(player -> !player.isRemoved())) {
                work.localAttempted = false;
                current.ready.addLast(work);
                return true;
            }
        }
        return accepted;
    }

    private static void notifyAvailableColumns(Job current, DirtyColumnsS2CPayload batch) {
        int distance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER;
        for (ServerPlayer player : current.level.players()) {
            if (!VSSServerNetworking.isRegistered(player)) continue;
            var notice = ChunkyColumnAvailability.missingForPlayer(batch,
                    player.getBlockX() >> 4, player.getBlockZ() >> 4, distance,
                    (cx, cz) -> VSSServerNetworking.clientKnownColumnTimestamp(player, current.level.dimension(), (int) cx, (int) cz));
            if (notice.dirtyPositions().length > 0) VSSNetworking.sendToPlayer(player, notice);
        }
    }

    private void retryGeneration(Job current, ChunkyWorkQueue.Work work) {
        work.columnData = null;
        work.reused = false;
        work.writeAttempts = 0;
        work.localAttempted = false;
        if (work.generationAttempts >= 3) current.queue.finish(work, false);
        else {
            work.stage = ChunkyWorkQueue.Stage.GENERATION;
            current.ready.addLast(work);
        }
    }

    private static long dirty(Job current, ChunkyWorkQueue.Work work) {
        return DirtyColumnBroadcaster.latestDirtyTimestamp(current.level.dimension(), work.chunkX, work.chunkZ);
    }

    private static boolean insideBorder(ServerLevel level, int cx, int cz) {
        return level.getWorldBorder().isWithinBounds(new BlockPos(cx * 16, 0, cz * 16))
                && level.getWorldBorder().isWithinBounds(new BlockPos(cx * 16 + 15, 0, cz * 16 + 15));
    }

    private void finish(Job current) {
        stop(current);
        current.finished = true;
        notifyOwner(current, Component.translatable("vss.command.chunky.completed", current.queue.completed(),
                current.queue.reused(), current.queue.failed(), seconds(current)).withStyle(ChatFormatting.GREEN));
    }

    private static Component status(Job current) {
        String state = current.capacityPending && !current.stopped ? "checking_capacity" : current.finished ? "finished" : current.stopped ? "cancelled" : current.paused
                || !VSSServerConfig.CONFIG.enabled || !VSSServerConfig.CONFIG.enableChunkGeneration
                || !VSSServerConfig.CONFIG.enablePersistentColumnCache && !IntegratedPregenBridge.supports(current.level.getServer())
                ? "paused" : "running";
        return Component.translatable("vss.command.chunky.status", current.level.dimension().location().toString(),
                Component.translatable("vss.command.chunky.state." + state),
                String.format(Locale.ROOT, "%.1f", current.queue.processed() * 100.0 / current.area.columnCount()),
                current.queue.completed(), current.area.columnCount(), current.queue.reused(), current.queue.failed(),
                current.stopped ? 0 : current.queue.inFlightCount(), seconds(current));
    }

    private static String seconds(Job current) {
        long end = current.stopped ? current.stoppedNanos : System.nanoTime();
        return String.format(Locale.ROOT, "%.1f", (end - current.startedNanos) / 1_000_000_000.0);
    }

    private static void notifyOwner(Job current, Component message) {
        if (current.source.getEntity() instanceof ServerPlayer owner) {
            ServerPlayer online = current.level.getServer().getPlayerList().getPlayer(owner.getUUID());
            if (online != null) online.sendSystemMessage(message);
        } else current.source.sendSuccess(() -> message, false);
    }

    private static int error(CommandSourceStack source, String key) {
        source.sendFailure(Component.translatable(key));
        return 0;
    }

    private record Completion(UUID jobId, int requestId, EncodedColumnData columnData, boolean saved) { }

    private static final class Job {
        final UUID id = UUID.randomUUID();
        final PlayerRequestState requests = new PlayerRequestState();
        final CommandSourceStack source;
        final ServerLevel level;
        final ChunkyArea area;
        final ChunkyWorkQueue queue;
        final ChunkyColumnAvailability availability;
        final ArrayDeque<ChunkyWorkQueue.Work> ready = new ArrayDeque<>();
        final AtomicInteger scanners = new AtomicInteger();
        volatile boolean scanning;
        final long startedNanos = System.nanoTime();
        long lastReportNanos = startedNanos;
        long stoppedNanos;
        boolean paused;
        boolean capacityPending = true;
        volatile boolean stopped;
        boolean finished;

        Job(CommandSourceStack source, ServerLevel level, ChunkyArea area) {
            this.source = source;
            this.level = level;
            this.area = area;
            this.queue = new ChunkyWorkQueue(area);
            this.availability = new ChunkyColumnAvailability(batch -> notifyAvailableColumns(this, batch));
        }
    }
}
