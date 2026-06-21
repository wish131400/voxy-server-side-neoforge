package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LoadedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.ClientDirtyColumnsC2SPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;

public final class VSSServerNetworking {
    private static final Map<UUID, PlayerRequestState> PLAYER_STATES = new HashMap<>();
    private static final ChunkGenerationService GENERATION_SERVICE = new ChunkGenerationService(VSSServerConfig.CONFIG);
    private static final ColumnLodCache COLUMN_CACHE = new ColumnLodCache(VSSServerConfig.CONFIG);
    private static final ExecutorService DISK_EXECUTOR = Executors.newFixedThreadPool(
            VSSServerConfig.CONFIG.diskReaderThreads,
            task -> {
                Thread thread = new Thread(task, "VSS-DiskReader");
                thread.setDaemon(true);
                return thread;
            });

    private VSSServerNetworking() {
    }

    public static boolean isRegistered(ServerPlayer player) {
        return PLAYER_STATES.containsKey(player.getUUID());
    }

    static boolean hasRegisteredPlayers() {
        return !PLAYER_STATES.isEmpty();
    }

    static boolean hasRegisteredPlayers(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (isRegistered(player)) {
                return true;
            }
        }
        return false;
    }

    public static void refreshSessionConfigs(MinecraftServer server) {
        for (UUID uuid : PLAYER_STATES.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                sendSessionConfig(player);
            }
        }
    }

    static String generationDiagnostics() {
        return GENERATION_SERVICE.diagnostics();
    }

    static Component generationDiagnosticsComponent() {
        return GENERATION_SERVICE.diagnosticsComponent();
    }

    static String diagnostics() {
        int queuedPayloads = 0;
        long queuedBytes = 0L;
        for (PlayerRequestState state : PLAYER_STATES.values()) {
            queuedPayloads += state.queuedPayloadCount();
            queuedBytes += state.queuedBytes();
        }
        return String.format(
                "players=%d, queuedColumns=%d, queuedBytes=%.2f MiB, generation={%s}, dirty={%s}, cache={%s}",
                PLAYER_STATES.size(),
                queuedPayloads,
                queuedBytes / (double) VSSServerConfig.BYTES_PER_MIB,
                GENERATION_SERVICE.diagnostics(),
                DirtyColumnBroadcaster.diagnostics(),
                COLUMN_CACHE.diagnostics());
    }

    static Component diagnosticsComponent() {
        int queuedPayloads = 0;
        long queuedBytes = 0L;
        for (PlayerRequestState state : PLAYER_STATES.values()) {
            queuedPayloads += state.queuedPayloadCount();
            queuedBytes += state.queuedBytes();
        }
        return Component.translatable(
                "vss.command.stats.details",
                PLAYER_STATES.size(),
                queuedPayloads,
                String.format(Locale.ROOT, "%.2f", queuedBytes / (double) VSSServerConfig.BYTES_PER_MIB),
                GENERATION_SERVICE.diagnosticsComponent(),
                DirtyColumnBroadcaster.diagnosticsComponent());
    }

    public static void handleHandshake(HandshakeC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }

        VSSServerConfig config = VSSServerConfig.CONFIG;
        boolean compatible = payload.protocolVersion() == VSSConstants.PROTOCOL_VERSION;
        boolean enabled = config.enabled && compatible;
        sendSessionConfig(player, enabled);

        if (!compatible) {
            VSSLogger.warn("Player " + player.getGameProfile().getName() + " has incompatible VSS protocol " + payload.protocolVersion());
            return;
        }
        if (enabled) {
            PLAYER_STATES.put(player.getUUID(), new PlayerRequestState());
            VSSLogger.info("Player " + player.getGameProfile().getName() + " registered for VSS LOD sync");
        }
    }

    private static void sendSessionConfig(ServerPlayer player) {
        sendSessionConfig(player, VSSServerConfig.CONFIG.enabled);
    }

    private static void sendSessionConfig(ServerPlayer player, boolean enabled) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        int serverCaps = VSSConstants.CAPABILITY_VOXEL_COLUMNS;
        VSSNetworking.sendToPlayer(
                player,
                new SessionConfigS2CPayload(
                        VSSConstants.PROTOCOL_VERSION,
                        enabled,
                        config.lodDistanceChunks,
                        serverCaps,
                        config.syncOnLoadRateLimitPerPlayer,
                        config.syncOnLoadConcurrencyLimitPerPlayer,
                        config.generationRateLimitPerPlayer,
                        config.generationConcurrencyLimitPerPlayer,
                        config.enableChunkGeneration,
                        config.bytesPerSecondLimitPerPlayer));
    }

    public static void handleBatchRequest(BatchChunkRequestC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
        if (state == null) {
            return;
        }

        ServerLevel level = player.serverLevel();
        byte[] responseTypes = new byte[payload.count()];
        int[] responseIds = new int[payload.count()];
        int responseCount = 0;

        for (int i = 0; i < payload.count(); i++) {
            int requestId = payload.requestIds()[i];
            if (state.consumeCancelled(requestId)) {
                continue;
            }

            long packed = payload.packedPositions()[i];
            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            if (!state.beginRequest(requestId, packed)) {
                continue;
            }
            int playerCx = player.getBlockX() >> 4;
            int playerCz = player.getBlockZ() >> 4;
            int maxDistance = VSSServerConfig.CONFIG.lodDistanceChunks + VSSConstants.LOD_DISTANCE_BUFFER;
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDistance) {
                responseTypes[responseCount] = VSSConstants.RESPONSE_RATE_LIMITED;
                responseIds[responseCount++] = requestId;
                state.clearRequest(requestId);
                continue;
            }

            long clientTimestamp = payload.clientTimestamps()[i];
            long dirtyTimestamp = DirtyColumnBroadcaster.latestDirtyTimestamp(level.dimension(), cx, cz);
            ColumnLodCache.Entry cachedColumn = COLUMN_CACHE.get(level.dimension(), cx, cz);
            if (cachedColumn != null) {
                long requiredTimestamp = Math.max(cachedColumn.timestamp(), dirtyTimestamp);
                if (clientTimestamp >= requiredTimestamp) {
                    responseTypes[responseCount] = VSSConstants.RESPONSE_UP_TO_DATE;
                    responseIds[responseCount++] = requestId;
                    state.clearRequest(requestId);
                } else if (dirtyTimestamp > cachedColumn.timestamp()) {
                    COLUMN_CACHE.invalidate(level.dimension(), cx, cz);
                } else {
                    queueColumn(player, state, new VoxelColumnS2CPayload(
                            requestId,
                            cachedColumn.chunkX(),
                            cachedColumn.chunkZ(),
                            level.dimension(),
                            requiredTimestamp,
                            cachedColumn.sectionBytes()));
                    continue;
                }
                if (clientTimestamp >= requiredTimestamp || dirtyTimestamp <= cachedColumn.timestamp()) {
                    continue;
                }
            }

            long columnTimestamp = Math.max(VSSConstants.epochMillis(), dirtyTimestamp);
            if (clientTimestamp >= columnTimestamp) {
                responseTypes[responseCount] = VSSConstants.RESPONSE_UP_TO_DATE;
                responseIds[responseCount++] = requestId;
                state.clearRequest(requestId);
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                submitDiskRead(player, state, requestId, cx, cz, columnTimestamp);
                continue;
            }

            if (!submitLoadedColumn(player, state, level, chunk, requestId, cx, cz, columnTimestamp)) {
                responseTypes[responseCount] = VSSConstants.RESPONSE_RATE_LIMITED;
                responseIds[responseCount++] = requestId;
                state.clearRequest(requestId);
            }
        }

        if (responseCount > 0) {
            byte[] trimmedTypes = new byte[responseCount];
            int[] trimmedIds = new int[responseCount];
            System.arraycopy(responseTypes, 0, trimmedTypes, 0, responseCount);
            System.arraycopy(responseIds, 0, trimmedIds, 0, responseCount);
            VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(trimmedTypes, trimmedIds, responseCount));
        }
    }

    private static boolean submitLoadedColumn(
            ServerPlayer player,
            PlayerRequestState state,
            ServerLevel level,
            LevelChunk chunk,
            int requestId,
            int cx,
            int cz,
            long minimumTimestamp) {
        return GENERATION_SERVICE.submitLoadedColumn(
                player.getUUID(),
                state,
                requestId,
                level,
                chunk,
                cx,
                cz,
                minimumTimestamp);
    }

    private static void submitDiskRead(ServerPlayer player, PlayerRequestState state, int requestId, int cx, int cz, long columnTimestamp) {
        UUID playerId = player.getUUID();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        try {
            DISK_EXECUTOR.execute(() -> {
                LoadedColumnData diskData = null;
                try {
                    diskData = NbtSectionSerializer.readAndSerializeSections(
                            level,
                            chunkMap,
                            cx,
                            cz,
                            VSSServerConfig.CONFIG.diskReadTimeoutMillis);
                } catch (Exception e) {
                    VSSLogger.warn("Failed to read chunk NBT from disk at " + cx + ", " + cz + ": " + e.getMessage());
                }

                LoadedColumnData result = diskData;
                server.execute(() -> finishDiskRead(playerId, level, state, requestId, cx, cz, columnTimestamp, result));
            });
        } catch (RejectedExecutionException e) {
            state.clearRequest(requestId);
            VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(new byte[] {VSSConstants.RESPONSE_RATE_LIMITED}, new int[] {requestId}, 1));
        }
    }

    private static void finishDiskRead(UUID playerId, ServerLevel level, PlayerRequestState requestState, int requestId, int cx, int cz, long columnTimestamp, LoadedColumnData diskData) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null || PLAYER_STATES.get(playerId) != requestState || requestState.consumeCancelled(requestId)) {
            return;
        }
        if (!player.serverLevel().dimension().equals(level.dimension())) {
            requestState.clearRequest(requestId);
            return;
        }
        if (diskData == null || diskData.sectionBytes() == null || diskData.sizeBytes() == 0) {
            if (diskData != null) {
                requestState.clearRequest(requestId);
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(new byte[] {VSSConstants.RESPONSE_UP_TO_DATE}, new int[] {requestId}, 1));
            } else if (VSSServerConfig.CONFIG.enableChunkGeneration) {
                submitGeneration(player, requestState, level, requestId, cx, cz);
            } else {
                requestState.clearRequest(requestId);
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(new byte[] {VSSConstants.RESPONSE_NOT_GENERATED}, new int[] {requestId}, 1));
            }
            return;
        }
        queueColumn(player, requestState, new VoxelColumnS2CPayload(requestId, cx, cz, level.dimension(), columnTimestamp, diskData.sectionBytes()));
        COLUMN_CACHE.put(level.dimension(), diskData, columnTimestamp);
    }

    private static void submitGeneration(ServerPlayer player, PlayerRequestState state, ServerLevel level, int requestId, int cx, int cz) {
        boolean accepted = GENERATION_SERVICE.submitGeneration(player.getUUID(), state, requestId, level, cx, cz);
        if (!accepted) {
            state.clearRequest(requestId);
            VSSNetworking.sendToPlayer(
                    player,
                    new BatchResponseS2CPayload(
                            new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                            new int[] {requestId},
                            1));
        }
    }

    private static void flushGeneratedColumns(MinecraftServer server) {
        for (ChunkGenerationService.GenerationResult result : GENERATION_SERVICE.tick(server)) {
            PlayerRequestState state = PLAYER_STATES.get(result.playerUuid());
            ServerPlayer player = server.getPlayerList().getPlayer(result.playerUuid());
            if (state == null || state != result.requestState() || player == null || state.consumeCancelled(result.requestId())) {
                continue;
            }

            if (!player.serverLevel().dimension().equals(result.dimension())) {
                state.clearRequest(result.requestId());
                continue;
            }

            LoadedColumnData columnData = result.columnData();
            if (result.notGenerated() || columnData == null) {
                state.clearRequest(result.requestId());
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_NOT_GENERATED},
                        new int[] {result.requestId()},
                        1));
                continue;
            }

            if (columnData.sectionBytes() == null || columnData.sizeBytes() == 0) {
                state.clearRequest(result.requestId());
                VSSNetworking.sendToPlayer(player, new BatchResponseS2CPayload(
                        new byte[] {VSSConstants.RESPONSE_UP_TO_DATE},
                        new int[] {result.requestId()},
                        1));
                continue;
            }

            queueColumn(player, state, new VoxelColumnS2CPayload(
                    result.requestId(),
                    columnData.chunkX(),
                    columnData.chunkZ(),
                    result.dimension(),
                    result.columnTimestamp(),
                    columnData.sectionBytes()));
            COLUMN_CACHE.put(result.dimension(), columnData, result.columnTimestamp());
        }
    }

    static void invalidateCachedColumn(ServerLevel level, int cx, int cz) {
        COLUMN_CACHE.invalidate(level.dimension(), cx, cz);
    }

    private static void queueColumn(ServerPlayer player, PlayerRequestState state, VoxelColumnS2CPayload payload) {
        if (!state.enqueue(payload)) {
            VSSNetworking.sendToPlayer(
                    player,
                    new BatchResponseS2CPayload(
                            new byte[] {VSSConstants.RESPONSE_RATE_LIMITED},
                            new int[] {payload.requestId()},
                            1));
        }
    }

    public static void handleCancel(CancelRequestC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        ServerPlayer player = contextSupplier.get().getSender();
        if (player != null) {
            PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
            if (state != null) {
                state.cancel(payload.requestId());
                GENERATION_SERVICE.cancelRequest(player.getUUID(), payload.requestId());
            }
        }
    }

    public static void handleBandwidthUpdate(BandwidthUpdateC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        ServerPlayer player = contextSupplier.get().getSender();
        if (player != null) {
            PlayerRequestState state = PLAYER_STATES.get(player.getUUID());
            if (state != null) {
                state.setDesiredBandwidth(payload.desiredRate());
            }
        }
    }

    public static void handleClientDirtyColumns(ClientDirtyColumnsC2SPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        ServerPlayer player = contextSupplier.get().getSender();
        if (player == null || !VSSServerConfig.CONFIG.enabled || !isRegistered(player)) {
            return;
        }

        ServerLevel level = player.serverLevel();
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = VSSServerConfig.CONFIG.lodDistanceChunks + VSSConstants.LOD_DISTANCE_BUFFER;
        int accepted = 0;
        for (long packed : payload.dirtyPositions()) {
            if (accepted >= VSSConstants.MAX_CLIENT_DIRTY_COLUMN_HINTS) {
                break;
            }

            int cx = PositionUtil.unpackX(packed);
            int cz = PositionUtil.unpackZ(packed);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDistance) {
                continue;
            }

            DirtyColumnBroadcaster.markDirtyColumnAndNeighbors(level, cx, cz);
            accepted++;
        }
    }

    private static void flushQueuedColumns(MinecraftServer server) {
        long configuredLimit = VSSServerConfig.CONFIG.bytesPerSecondLimitPerPlayer;
        for (Map.Entry<UUID, PlayerRequestState> entry : PLAYER_STATES.entrySet()) {
            PlayerRequestState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }

            long effectiveLimit = Math.min(configuredLimit, state.desiredBandwidth());
            while (state.queuedPayloadCount() > 0) {
                PlayerRequestState.QueuedPayload queued = state.peekQueuedPayload();
                if (queued == null || !state.canSend(effectiveLimit)) {
                    break;
                }
                state.pollQueuedPayload();
                if (state.consumeCancelled(queued.payload().requestId())) {
                    continue;
                }
                VSSNetworking.sendToPlayer(player, queued.payload());
                state.recordSend(queued.estimatedBytes());
                state.clearRequest(queued.payload().requestId());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PLAYER_STATES.remove(player.getUUID());
            GENERATION_SERVICE.removePlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MinecraftServer server = event.getServer();
            flushGeneratedColumns(server);
            flushQueuedColumns(server);
            DirtyColumnBroadcaster.tick(server);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        GENERATION_SERVICE.shutdown();
        DirtyColumnBroadcaster.clear();
        COLUMN_CACHE.clear();
        PLAYER_STATES.clear();
    }
}
