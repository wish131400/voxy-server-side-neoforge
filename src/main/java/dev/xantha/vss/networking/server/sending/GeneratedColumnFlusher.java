package dev.xantha.vss.networking.server.sending;


import dev.xantha.vss.networking.server.generation.ChunkGenerationService;
import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import dev.xantha.vss.networking.server.state.PlayerRequestRegistry;
import dev.xantha.vss.networking.server.state.PlayerRequestState;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnWriter;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class GeneratedColumnFlusher {
    private final PlayerRequestRegistry playerRegistry;
    private final ChunkGenerationService generationService;
    private final ColumnLodCache columnCache;
    private final PersistentColumnWriter persistentColumnWriter;

    public GeneratedColumnFlusher(
            PlayerRequestRegistry playerRegistry,
            ChunkGenerationService generationService,
            ColumnLodCache columnCache,
            PersistentColumnWriter persistentColumnWriter) {
        this.playerRegistry = playerRegistry;
        this.generationService = generationService;
        this.columnCache = columnCache;
        this.persistentColumnWriter = persistentColumnWriter;
    }

    public void flush(MinecraftServer server) {
        if (VSSServerNetworking.isServerStopping()) {
            return;
        }
        for (ChunkGenerationService.GenerationResult result : generationService.tick(server)) {
            PlayerRequestState state = playerRegistry.get(result.playerUuid());
            ServerPlayer player = server.getPlayerList().getPlayer(result.playerUuid());
            if (state == null || state != result.requestState() || player == null || state.consumeCancelled(result.requestId())) {
                continue;
            }

            if (!player.serverLevel().dimension().equals(result.dimension())) {
                state.clearRequest(result.requestId());
                continue;
            }

            EncodedColumnData columnData = result.columnData();
            if (result.notGenerated() || columnData == null) {
                sendNotGenerated(player, state, result.requestId());
                continue;
            }

            if (!VSSServerNetworking.isColumnStillRelevant(player, result.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                state.clearRequest(result.requestId());
                continue;
            }

            if (!columnData.hasBody() || !columnData.completeColumn()) {
                sendNotGenerated(player, state, result.requestId());
                continue;
            }

            long latestDirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(
                    result.dimension(),
                    columnData.chunkX(),
                    columnData.chunkZ());
            if (latestDirtyTimestamp > columnData.columnStamp()) {
                sendRateLimited(player, state, result.requestId());
                continue;
            }

            boolean queued = VSSServerNetworking.queueColumn(player, state, new VoxelColumnS2CPayload(
                    result.requestId(),
                    result.dimension(),
                    columnData), result.priority());
            columnCache.put(result.dimension(), columnData);
            if (!queued && VSSServerNetworking.isColumnStillRelevant(player, result.dimension(), columnData.chunkX(), columnData.chunkZ())) {
                state.addPreloadColumn(new PlayerRequestState.PreloadColumn(
                        columnData.chunkX(),
                        columnData.chunkZ(),
                        columnData.columnStamp()));
            }
            persistentColumnWriter.write(server, result.dimension(), columnData);
        }
    }

    private static void sendNotGenerated(ServerPlayer player, PlayerRequestState state, int requestId) {
        state.clearRequest(requestId);
        VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                new int[] {requestId},
                1));
    }

    private static void sendRateLimited(ServerPlayer player, PlayerRequestState state, int requestId) {
        state.clearRequest(requestId);
        VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                new int[] {requestId},
                1));
    }
}
