package dev.xantha.vss.networking.server.runtime;


import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
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
            QueuedColumnSender queuedColumnSender) {
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
    }

    public void applyRuntimeConfig() {
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
        if (playerRegistry.isEmpty()) {
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
        if (playerRegistry.isEmpty()) {
            releaseIdleMemory();
            return;
        }
        generatedColumnFlusher.flush(server);
        existingColumnPreloader.flushColumns(server);
        existingColumnPreloader.scanRegions(server);
        queuedColumnSender.flush(server);
        DirtyColumnBroadcaster.tick(server);
        persistentColumnWriter.flushInvalidations(server);
    }

    public void onServerStarting() {
        lifecycleGuard.start();
        diskRuntime.restart();
        applyRuntimeConfig();
        idleMemoryReleased = true;
        playerRegistry.clear();
        DirtyColumnBroadcaster.clear();
        columnCache.clear();
        persistentStore.clearMemory();
        diskRuntime.resetPendingCounts();
    }

    public void onServerStopping(MinecraftServer server) {
        persistentColumnWriter.flushInvalidationsBlocking(server);
        lifecycleGuard.stop();
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
