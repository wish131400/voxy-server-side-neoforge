package dev.xantha.vss.networking.server.runtime;


import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.generation.ChunkyGenerationService;
import dev.xantha.vss.networking.server.preload.ExistingColumnPreloader;
import dev.xantha.vss.networking.server.sending.GeneratedColumnFlusher;
import dev.xantha.vss.networking.server.sending.QueuedColumnSender;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.common.VSSLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 共享服务端组件的启停协调器；业务对象由装配入口创建，本类控制其生命周期顺序。
 * <p>由服务端生命周期线程调用。开始/停止推进同一 guard 的版本；关闭执行器、
 * 注册表和存储后，异步完成路径仍须检查原 epoch 与玩家状态身份。
 */
public final class ServerNetworkingLifecycle {
    private final PlayerRequestRegistry playerRegistry;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final PersistentColumnLodStore persistentStore;
    private final PersistentColumnWriter persistentColumnWriter;
    private final ServerLifecycleGuard lifecycleGuard;
    private final DiskTaskRuntime diskRuntime;
    private final GeneratedColumnFlusher generatedColumnFlusher;
    private final ExistingColumnPreloader existingColumnPreloader;
    private final QueuedColumnSender queuedColumnSender;
    private final ChunkyGenerationService chunky;
    private boolean idleMemoryReleased = true;

    public ServerNetworkingLifecycle(
            PlayerRequestRegistry playerRegistry,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            PersistentColumnLodStore persistentStore,
            PersistentColumnWriter persistentColumnWriter,
            ServerLifecycleGuard lifecycleGuard,
            DiskTaskRuntime diskRuntime,
            GeneratedColumnFlusher generatedColumnFlusher,
            ExistingColumnPreloader existingColumnPreloader,
            QueuedColumnSender queuedColumnSender,
            ChunkyGenerationService chunky) {
        this.playerRegistry = playerRegistry;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.persistentStore = persistentStore;
        this.persistentColumnWriter = persistentColumnWriter;
        this.lifecycleGuard = lifecycleGuard;
        this.diskRuntime = diskRuntime;
        this.generatedColumnFlusher = generatedColumnFlusher;
        this.existingColumnPreloader = existingColumnPreloader;
        this.queuedColumnSender = queuedColumnSender;
        this.chunky = chunky;
    }

    public void applyRuntimeConfig() {
        columnCache.refreshCoverageConfig();
        generationService.applyRuntimeConfig();
        queuedColumnSender.applyRuntimeConfig();
        int resizedThreads = diskRuntime.resizeReadExecutor();
        if (resizedThreads > 0) {
            VSSLogger.info("VSS disk reader threads resized to " + resizedThreads);
        }
    }

    public void onPlayerLogout(ServerPlayer player) {
        PlayerRequestState state = playerRegistry.remove(player.getUUID());
        if (state != null) {
            state.clearAll();
        }
        generationService.removePlayer(player.getUUID());
        if (playerRegistry.isEmpty() && !chunky.hasActiveJob()) {
            releaseIdleMemory();
        }
    }

    public void markSessionActive() {
        idleMemoryReleased = false;
    }

    public void onServerTick(MinecraftServer server) {
        if (lifecycleGuard.isStopping()) {
            return;
        }
        persistentColumnWriter.scheduleMaintenance(server);
        if (playerRegistry.isEmpty() && !chunky.hasActiveJob()) {
            releaseIdleMemory();
            return;
        }
        generatedColumnFlusher.flush(server);
        chunky.tick(server);
        idleMemoryReleased = false;
        existingColumnPreloader.flushColumns(server);
        existingColumnPreloader.scanRegions(server);
        queuedColumnSender.flush(server);
        DirtyColumnBroadcaster.tick(server);
        persistentColumnWriter.flushInvalidations(server);
    }

    public void onServerStarting() {
        chunky.clear();
        lifecycleGuard.start();
        queuedColumnSender.reset();
        diskRuntime.restart();
        applyRuntimeConfig();
        idleMemoryReleased = true;
        playerRegistry.clear();
        DirtyColumnBroadcaster.clear();
        columnCache.clear();
        columnCache.openCoverage();
        persistentStore.clearMemory();
        diskRuntime.resetPendingCounts();
    }

    public void onServerStopping(MinecraftServer server) {
        columnCache.closeCoverage();
        chunky.clear();
        persistentColumnWriter.flushInvalidationsBlocking(server);
        persistentColumnWriter.flushWritesBlocking();
        lifecycleGuard.stop();
        if (!diskRuntime.awaitWrites(10_000L)) {
            VSSLogger.warn("Timed out while flushing VSS persistent column writes during shutdown");
        }
        persistentStore.flushDirtyIndexesBlocking(server);
        diskRuntime.clearCoalescedReads();
        queuedColumnSender.reset();
        diskRuntime.shutdown();
        diskRuntime.resetPendingCounts();
        for (PlayerRequestState state : playerRegistry.states()) {
            state.clearAll();
        }
        idleMemoryReleased = false;
        generationService.shutdown();
        DirtyColumnBroadcaster.clear();
        columnCache.clear();
        persistentStore.clearMemory();
        idleMemoryReleased = true;
        playerRegistry.clear();
        VSSLogger.info("Stopped VSS LOD sync and cleared generation tickets during server shutdown");
    }

    private void releaseIdleMemory() {
        if (idleMemoryReleased) {
            return;
        }
        generationService.releaseIdleMemory();
        DirtyColumnBroadcaster.clear();
        idleMemoryReleased = true;
        VSSLogger.info("Released idle VSS request state after the last VSS player disconnected; kept hot LOD caches");
    }
}
