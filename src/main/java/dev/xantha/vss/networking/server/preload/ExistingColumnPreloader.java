package dev.xantha.vss.networking.server.preload;


import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ExistingColumnPreloader {
    private static final int PRELOAD_COLUMNS_PER_REGION = 1024;
    private static final int PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD = 2048;
    private static final int PRELOAD_FRONTIER_RING_SLACK = 1;
    private static final int PRELOAD_PENDING_DISK_LIMIT = 256;

    private final PlayerRequestRegistry playerRegistry;
    private final PersistentColumnLodStore persistentStore;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final DiskTaskRuntime diskRuntime;

    public ExistingColumnPreloader(
            PlayerRequestRegistry playerRegistry,
            PersistentColumnLodStore persistentStore,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            DiskTaskRuntime diskRuntime) {
        this.playerRegistry = playerRegistry;
        this.persistentStore = persistentStore;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.diskRuntime = diskRuntime;
    }

    public void schedule(ServerPlayer player, PlayerRequestState state) {
        if (VSSServerNetworking.isServerStopping() || !persistentStore.enabled()) {
            return;
        }
        int regionSize = PersistentColumnLodStore.regionSize();
        int centerRegionX = Math.floorDiv(player.getBlockX() >> 4, regionSize);
        int centerRegionZ = Math.floorDiv(player.getBlockZ() >> 4, regionSize);
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks();
        int maxRegionRing = Math.max(0, (maxDistance + regionSize - 1) / regionSize);
        state.resetPreloadRegions(player.serverLevel().dimension(), centerRegionX, centerRegionZ, maxRegionRing);
        VSSLogger.debug("Queued " + state.preloadRegionCount() + " LOD cache regions for cold-start indexed preload around region "
                + centerRegionX + "," + centerRegionZ);
    }

    public void scanRegions(MinecraftServer server) {
        if (VSSServerNetworking.isServerStopping() || !persistentStore.enabled()) {
            return;
        }
        for (Map.Entry<UUID, PlayerRequestState> entry : playerRegistry.entries()) {
            if (!diskRuntime.hasReadCapacity(PRELOAD_PENDING_DISK_LIMIT)) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            PlayerRequestState state = entry.getValue();
            if (player != null) {
                updateWindow(player, state);
            }
            if (player == null
                    || state.preloadRegionCount() <= 0
                    || state.preloadColumnCount() >= PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD) {
                continue;
            }
            while (diskRuntime.hasReadCapacity(PRELOAD_PENDING_DISK_LIMIT)
                    && state.preloadColumnCount() < PRELOAD_COLUMN_QUEUE_RESUME_THRESHOLD) {
                PlayerRequestState.PreloadRegion region = state.pollPreloadRegion();
                if (region == null) {
                    break;
                }
                submitRegionScan(player, state, region);
            }
        }
    }

    public void flushColumns(MinecraftServer server) {
        if (VSSServerNetworking.isServerStopping() || !persistentStore.enabled()) {
            return;
        }
        boolean submittedAny;
        do {
            submittedAny = false;
            for (Map.Entry<UUID, PlayerRequestState> entry : playerRegistry.entries()) {
                if (!diskRuntime.hasReadCapacity(PRELOAD_PENDING_DISK_LIMIT)) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                PlayerRequestState state = entry.getValue();
                if (player == null || state.preloadColumnCount() <= 0) {
                    continue;
                }
                int playerCx = player.getBlockX() >> 4;
                int playerCz = player.getBlockZ() >> 4;
                submittedAny |= submitNextColumnRead(player, state, playerCx, playerCz);
            }
        } while (submittedAny && diskRuntime.hasReadCapacity(PRELOAD_PENDING_DISK_LIMIT));
    }

    private void updateWindow(ServerPlayer player, PlayerRequestState state) {
        int regionSize = PersistentColumnLodStore.regionSize();
        int centerRegionX = Math.floorDiv(player.getBlockX() >> 4, regionSize);
        int centerRegionZ = Math.floorDiv(player.getBlockZ() >> 4, regionSize);
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks();
        int maxRegionRing = Math.max(0, (maxDistance + regionSize - 1) / regionSize);
        state.updatePreloadRegions(player.serverLevel().dimension(), centerRegionX, centerRegionZ, maxRegionRing);
    }

    private void submitRegionScan(ServerPlayer player, PlayerRequestState state, PlayerRequestState.PreloadRegion region) {
        UUID playerId = player.getUUID();
        MinecraftServer server = player.server;
        ServerLevel level = player.serverLevel();
        long lifecycleEpoch = VSSServerNetworking.lifecycleEpoch();
        int centerCx = player.getBlockX() >> 4;
        int centerCz = player.getBlockZ() >> 4;
        int minDistance = 0;
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks();
        int regionMinimumDistance = minimumChunkDistanceToRegion(centerCx, centerCz, region.regionX(), region.regionZ());
        state.beginPreloadRegionScan(regionMinimumDistance);
        diskRuntime.submitRead(PRELOAD_PENDING_DISK_LIMIT, () -> {
            ArrayList<PersistentColumnLodStore.ExistingColumn> columns = new ArrayList<>();
            try {
                if (!VSSServerNetworking.isLifecycleStale(lifecycleEpoch)) {
                    columns = persistentStore.findExistingColumnsInRegion(
                            server,
                            level.dimension(),
                            region.regionX(),
                            region.regionZ(),
                            centerCx,
                            centerCz,
                            minDistance,
                            maxDistance,
                            PRELOAD_COLUMNS_PER_REGION);
                }
            } catch (Exception e) {
                VSSLogger.debug("Existing LOD preload region scan failed: " + e.getMessage());
            }
            if (VSSServerNetworking.isLifecycleStale(lifecycleEpoch)) {
                state.finishPreloadRegionScan(regionMinimumDistance);
                return;
            }
            ArrayList<PersistentColumnLodStore.ExistingColumn> foundColumns = columns;
            try {
                server.execute(() -> {
                    try {
                        if (VSSServerNetworking.isLifecycleStale(lifecycleEpoch) || !playerRegistry.isCurrent(playerId, state)) {
                            return;
                        }
                        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
                        if (onlinePlayer == null || foundColumns.isEmpty()) {
                            return;
                        }
                        foundColumns.removeIf(column -> generationService.isGenerating(level.dimension(), column.chunkX(), column.chunkZ()));
                        foundColumns.removeIf(column -> state.isClientKnownCurrent(
                                level.dimension(),
                                column.chunkX(),
                                column.chunkZ(),
                                Math.max(
                                        column.timestamp(),
                                        DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), column.chunkX(), column.chunkZ()))));
                        state.addPreloadColumns(foundColumns);
                    } finally {
                        state.finishPreloadRegionScan(regionMinimumDistance);
                    }
                });
            } catch (RejectedExecutionException e) {
                state.finishPreloadRegionScan(regionMinimumDistance);
                VSSLogger.debug("Existing LOD preload region scan handoff rejected: " + e.getMessage());
            }
        }, e -> {
            state.finishPreloadRegionScan(regionMinimumDistance);
            VSSLogger.debug("Existing LOD preload region scan rejected: " + e.getMessage());
        });
    }

    private boolean submitNextColumnRead(
            ServerPlayer player,
            PlayerRequestState state,
            int playerCx,
            int playerCz) {
        while (diskRuntime.hasReadCapacity(PRELOAD_PENDING_DISK_LIMIT)) {
            PlayerRequestState.PreloadColumn preload = state.pollFrontierPreloadColumn(
                    playerCx,
                    playerCz,
                    PRELOAD_FRONTIER_RING_SLACK);
            if (preload == null) {
                return false;
            }
            if (!VSSServerNetworking.isColumnStillRelevant(player, player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ())) {
                continue;
            }
            long requiredTimestamp = Math.max(
                    preload.timestamp(),
                    DirtyColumnBroadcaster.latestDirtyTimestamp(player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ()));
            if (state.isClientKnownCurrent(player.serverLevel().dimension(), preload.chunkX(), preload.chunkZ(), requiredTimestamp)) {
                continue;
            }
            submitRead(player, state, preload);
            return true;
        }
        return false;
    }

    private void submitRead(ServerPlayer player, PlayerRequestState state, PlayerRequestState.PreloadColumn preload) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        long lifecycleEpoch = VSSServerNetworking.lifecycleEpoch();
        state.beginPreloadColumnRead();
        diskRuntime.submitRead(PRELOAD_PENDING_DISK_LIMIT, () -> {
            PersistentColumnLodStore.Entry storedData = null;
            if (!VSSServerNetworking.isLifecycleStale(lifecycleEpoch)) {
                storedData = persistentStore.read(
                        server,
                        level.dimension(),
                        preload.chunkX(),
                        preload.chunkZ(),
                        DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), preload.chunkX(), preload.chunkZ()));
            }
            if (storedData == null || storedData.columnData() == null
                    || VSSServerNetworking.isLifecycleStale(lifecycleEpoch)) {
                state.finishPreloadColumnRead();
                return;
            }
            EncodedColumnData columnData = storedData.columnData();
            try {
                server.execute(() -> {
                    try {
                        if (VSSServerNetworking.isLifecycleStale(lifecycleEpoch) || !playerRegistry.isCurrent(playerId, state)) {
                            return;
                        }
                        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerId);
                        if (onlinePlayer == null) {
                            return;
                        }
                        if (generationService.isGenerating(level.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                            return;
                        }
                        if (!VSSServerNetworking.isColumnStillRelevant(onlinePlayer, level.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                            return;
                        }
                        long requiredTimestamp = Math.max(
                                columnData.columnStamp(),
                                DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), columnData.chunkX(), columnData.chunkZ()));
                        if (state.isClientKnownCurrent(level.dimension(), columnData.chunkX(), columnData.chunkZ(), requiredTimestamp)) {
                            return;
                        }
                        columnCache.put(level.dimension(), columnData);
                    } finally {
                        state.finishPreloadColumnRead();
                    }
                });
            } catch (RejectedExecutionException e) {
                state.finishPreloadColumnRead();
                VSSLogger.debug("Existing LOD preload read handoff rejected: " + e.getMessage());
            }
        }, e -> {
            state.finishPreloadColumnRead();
            VSSLogger.debug("Existing LOD preload read rejected: " + e.getMessage());
        });
    }

    public static int minimumChunkDistanceToRegion(int centerCx, int centerCz, int regionX, int regionZ) {
        int regionSize = PersistentColumnLodStore.regionSize();
        int minX = regionX * regionSize;
        int minZ = regionZ * regionSize;
        int maxX = minX + regionSize - 1;
        int maxZ = minZ + regionSize - 1;
        int dx = centerCx < minX ? minX - centerCx : Math.max(0, centerCx - maxX);
        int dz = centerCz < minZ ? minZ - centerCz : Math.max(0, centerCz - maxZ);
        return Math.max(dx, dz);
    }
}
