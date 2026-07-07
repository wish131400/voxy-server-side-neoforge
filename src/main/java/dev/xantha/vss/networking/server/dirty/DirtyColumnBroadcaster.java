package dev.xantha.vss.networking.server.dirty;


import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class DirtyColumnBroadcaster {
    private static final int DIRTY_BUCKET_SIZE = 32;
    private static final int MAX_BLOCK_AFFECTED_COLUMNS = 4;
    private static final Map<Level, LongOpenHashSet> DIRTY = new HashMap<>();
    private static final Map<ResourceLocation, Long2LongLinkedOpenHashMap> DIRTY_VERSIONS = new HashMap<>();
    private static int tickCounter;
    private static int cleanupTickCounter;

    private DirtyColumnBroadcaster() {
    }

    public static synchronized void tick(MinecraftServer server) {
        if (!VSSServerNetworking.hasRegisteredPlayers()) {
            DIRTY.clear();
            tickCounter = 0;
            cleanupDirtyVersionCache(VSSConstants.epochMillis());
            return;
        }

        if (++cleanupTickCounter >= 1200) {
            cleanupTickCounter = 0;
            cleanupDirtyVersionCache(VSSConstants.epochMillis());
        }

        int interval = VSSServerConfig.CONFIG.dirtyBroadcastIntervalTicks;
        if (++tickCounter < interval) {
            return;
        }
        tickCounter = 0;

        for (ServerLevel level : server.getAllLevels()) {
            LongOpenHashSet positions = DIRTY.get(level);
            if (positions == null || positions.isEmpty()) {
                continue;
            }
            if (!VSSServerNetworking.hasRegisteredPlayers(level)) {
                continue;
            }

            long[] packed = drainLimitedArray(positions);
            if (positions.isEmpty()) {
                DIRTY.remove(level);
            }
            long[] timestamps = dirtyTimestamps(level, packed);
            DirtyBatchIndex dirtyIndex = DirtyBatchIndex.of(packed, timestamps);
            for (ServerPlayer player : level.players()) {
                if (!VSSServerNetworking.isRegistered(player)) {
                    continue;
                }

                DirtyColumnsS2CPayload playerPayload = filterColumnsForPlayer(player, level.dimension(), dirtyIndex);
                if (playerPayload.dirtyPositions().length > 0) {
                    VSSNetworking.sendToPlayer(player, playerPayload);
                }
            }
        }
    }

    public static synchronized void clear() {
        DIRTY.clear();
        DIRTY_VERSIONS.clear();
        tickCounter = 0;
        cleanupTickCounter = 0;
    }

    public static synchronized String diagnostics() {
        int columns = 0;
        for (LongOpenHashSet positions : DIRTY.values()) {
            columns += positions.size();
        }
        return "dirtyLevels=" + DIRTY.size()
                + ", dirtyColumns=" + columns
                + ", versionLevels=" + DIRTY_VERSIONS.size()
                + ", versionColumns=" + dirtyVersionCount();
    }

    public static synchronized Component diagnosticsComponent() {
        int columns = 0;
        for (LongOpenHashSet positions : DIRTY.values()) {
            columns += positions.size();
        }
        return Component.translatable("vss.command.stats.dirty", DIRTY.size(), columns, DIRTY_VERSIONS.size(), dirtyVersionCount());
    }

    public static synchronized long latestDirtyTimestamp(ResourceKey<Level> dimension, int cx, int cz) {
        if (!VSSServerConfig.CONFIG.dirtyVersionCacheEnabled) {
            return 0L;
        }

        Long2LongLinkedOpenHashMap versions = DIRTY_VERSIONS.get(dimension.location());
        if (versions == null) {
            return 0L;
        }
        long timestamp = versions.get(PositionUtil.packPosition(cx, cz));
        if (timestamp <= 0L) {
            return 0L;
        }
        long maxAgeMillis = VSSServerConfig.CONFIG.dirtyVersionCacheRetentionSeconds * 1000L;
        if (VSSConstants.epochMillis() - timestamp > maxAgeMillis) {
            versions.remove(PositionUtil.packPosition(cx, cz));
            if (versions.isEmpty()) {
                DIRTY_VERSIONS.remove(dimension.location());
            }
            return 0L;
        }
        return timestamp;
    }

    public static synchronized void sendStaleColumnsForPresence(ServerPlayer player, RegionPresenceC2SPayload payload) {
        if (player == null
                || payload == null
                || VSSServerNetworking.isServerStopping()
                || !VSSServerConfig.CONFIG.enabled
                || !player.serverLevel().dimension().equals(payload.dimension())) {
            return;
        }

        long[] packedColumns = new long[VSSConstants.MAX_DIRTY_COLUMN_POSITIONS];
        long[] dirtyTimestamps = new long[VSSConstants.MAX_DIRTY_COLUMN_POSITIONS];
        int dirtyCount = 0;
        for (RegionPresenceC2SPayload.RegionEntry entry : payload.entries()) {
            int baseX = entry.regionX() * RegionPresenceC2SPayload.REGION_SIZE;
            int baseZ = entry.regionZ() * RegionPresenceC2SPayload.REGION_SIZE;
            int[] slots = entry.slots();
            long[] timestamps = entry.timestamps();
            int count = Math.min(entry.count(), Math.min(slots.length, timestamps.length));
            for (int i = 0; i < count && dirtyCount < packedColumns.length; i++) {
                int slot = slots[i];
                long clientTimestamp = timestamps[i];
                if (slot < 0 || slot >= RegionPresenceC2SPayload.REGION_SLOT_COUNT || clientTimestamp <= 0L) {
                    continue;
                }

                int cx = baseX + (slot & (RegionPresenceC2SPayload.REGION_SIZE - 1));
                int cz = baseZ + (slot >>> 5);
                long dirtyTimestamp = latestDirtyTimestamp(payload.dimension(), cx, cz);
                if (dirtyTimestamp <= clientTimestamp) {
                    continue;
                }

                packedColumns[dirtyCount] = PositionUtil.packPosition(cx, cz);
                dirtyTimestamps[dirtyCount] = dirtyTimestamp;
                dirtyCount++;
            }
            if (dirtyCount >= packedColumns.length) {
                break;
            }
        }

        if (dirtyCount > 0) {
            VSSNetworking.sendToPlayer(player, new DirtyColumnsS2CPayload(
                    Arrays.copyOf(packedColumns, dirtyCount),
                    Arrays.copyOf(dirtyTimestamps, dirtyCount)));
        }
    }

    public static synchronized void markDirtyColumn(Object levelAccess, int cx, int cz) {
        if (levelAccess instanceof ServerLevel level
                && VSSServerConfig.CONFIG.enabled) {
            markDirtyColumn(level, cx, cz);
        }
    }

    public static synchronized void markDirtyBlock(Object levelAccess, BlockPos pos) {
        if (levelAccess instanceof ServerLevel level
                && VSSServerConfig.CONFIG.enabled
                && pos != null) {
            markDirtyBlock(level, pos);
        }
    }

    private static void markDirtyBlock(ServerLevel level, BlockPos pos) {
        long timestamp = VSSConstants.columnVersion();
        for (long packed : affectedColumnsForBlock(pos)) {
            markDirtyColumn(level, PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed), timestamp);
        }
    }

    static long[] affectedColumnsForBlock(BlockPos pos) {
        if (pos == null) {
            return new long[0];
        }

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int minDx = localX == 0 ? -1 : 0;
        int maxDx = localX == 15 ? 1 : 0;
        int minDz = localZ == 0 ? -1 : 0;
        int maxDz = localZ == 15 ? 1 : 0;

        long[] columns = new long[MAX_BLOCK_AFFECTED_COLUMNS];
        int count = 0;
        columns[count++] = PositionUtil.packPosition(cx, cz);
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dz = minDz; dz <= maxDz; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                columns[count++] = PositionUtil.packPosition(cx + dx, cz + dz);
            }
        }
        return Arrays.copyOf(columns, count);
    }

    private static void markDirtyColumn(ServerLevel level, int cx, int cz) {
        markDirtyColumn(level, cx, cz, VSSConstants.columnVersion());
    }

    private static void markDirtyColumn(ServerLevel level, int cx, int cz, long timestamp) {
        long packed = PositionUtil.packPosition(cx, cz);
        recordDirtyVersion(level.dimension(), packed, timestamp);
        VSSServerNetworking.invalidateCachedColumn(level, cx, cz, timestamp);
        if (!VSSServerNetworking.hasRegisteredPlayers(level)) {
            return;
        }

        LongOpenHashSet positions = DIRTY.computeIfAbsent(level, ignored -> new LongOpenHashSet());
        if (positions.size() < VSSServerConfig.CONFIG.dirtyVersionCacheMaxEntries) {
            positions.add(packed);
        }
    }

    private static void recordDirtyVersion(ResourceKey<Level> dimension, long packed, long timestamp) {
        if (!VSSServerConfig.CONFIG.dirtyVersionCacheEnabled) {
            return;
        }

        Long2LongLinkedOpenHashMap versions = DIRTY_VERSIONS.computeIfAbsent(dimension.location(), ignored -> {
            Long2LongLinkedOpenHashMap map = new Long2LongLinkedOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        versions.putAndMoveToLast(packed, timestamp);
        evictDirtyVersionOverflow();
    }

    private static void evictDirtyVersionOverflow() {
        int maxEntries = VSSServerConfig.CONFIG.dirtyVersionCacheMaxEntries;
        while (dirtyVersionCount() > maxEntries && !DIRTY_VERSIONS.isEmpty()) {
            ResourceLocation oldestDimension = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<ResourceLocation, Long2LongLinkedOpenHashMap> entry : DIRTY_VERSIONS.entrySet()) {
                Long2LongLinkedOpenHashMap versions = entry.getValue();
                if (!versions.isEmpty()) {
                    long timestamp = versions.get(versions.firstLongKey());
                    if (timestamp < oldestTimestamp) {
                        oldestTimestamp = timestamp;
                        oldestDimension = entry.getKey();
                    }
                }
            }
            if (oldestDimension == null) {
                DIRTY_VERSIONS.clear();
                return;
            }
            Long2LongLinkedOpenHashMap versions = DIRTY_VERSIONS.get(oldestDimension);
            versions.removeFirstLong();
            if (versions.isEmpty()) {
                DIRTY_VERSIONS.remove(oldestDimension);
            }
        }
    }

    private static void cleanupDirtyVersionCache(long nowMillis) {
        if (!VSSServerConfig.CONFIG.dirtyVersionCacheEnabled) {
            DIRTY_VERSIONS.clear();
            return;
        }

        long maxAgeMillis = VSSServerConfig.CONFIG.dirtyVersionCacheRetentionSeconds * 1000L;
        DIRTY_VERSIONS.entrySet().removeIf(entry -> {
            Long2LongLinkedOpenHashMap versions = entry.getValue();
            while (!versions.isEmpty()) {
                long timestamp = versions.get(versions.firstLongKey());
                if (nowMillis - timestamp <= maxAgeMillis) {
                    break;
                }
                versions.removeFirstLong();
            }
            return versions.isEmpty();
        });
        evictDirtyVersionOverflow();
    }

    private static int dirtyVersionCount() {
        int count = 0;
        for (Long2LongLinkedOpenHashMap versions : DIRTY_VERSIONS.values()) {
            count += versions.size();
        }
        return count;
    }

    private static long[] drainLimitedArray(LongOpenHashSet positions) {
        int count = Math.min(positions.size(), VSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        long[] packed = new long[count];
        LongIterator iterator = positions.iterator();
        for (int i = 0; i < count && iterator.hasNext(); i++) {
            packed[i] = iterator.nextLong();
            iterator.remove();
        }
        return packed;
    }

    private static long[] dirtyTimestamps(ServerLevel level, long[] packedColumns) {
        long[] timestamps = new long[packedColumns.length];
        for (int i = 0; i < packedColumns.length; i++) {
            int cx = PositionUtil.unpackX(packedColumns[i]);
            int cz = PositionUtil.unpackZ(packedColumns[i]);
            timestamps[i] = latestDirtyTimestamp(level.dimension(), cx, cz);
        }
        return timestamps;
    }

    private static DirtyColumnsS2CPayload filterColumnsForPlayer(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            DirtyBatchIndex dirtyIndex) {
        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;
        int maxDistance = VSSServerConfig.CONFIG.effectiveColumnSyncDistanceChunks() + VSSConstants.LOD_DISTANCE_BUFFER;
        long[] packedColumns = dirtyIndex.packedColumns();
        long[] timestamps = dirtyIndex.timestamps();
        long[] filtered = new long[packedColumns.length];
        long[] filteredTimestamps = new long[packedColumns.length];
        int count = 0;
        int minBucketX = Math.floorDiv(playerCx - maxDistance, DIRTY_BUCKET_SIZE);
        int maxBucketX = Math.floorDiv(playerCx + maxDistance, DIRTY_BUCKET_SIZE);
        int minBucketZ = Math.floorDiv(playerCz - maxDistance, DIRTY_BUCKET_SIZE);
        int maxBucketZ = Math.floorDiv(playerCz + maxDistance, DIRTY_BUCKET_SIZE);
        for (int bucketX = minBucketX; bucketX <= maxBucketX; bucketX++) {
            for (int bucketZ = minBucketZ; bucketZ <= maxBucketZ; bucketZ++) {
                IntArrayList indices = dirtyIndex.indices(bucketX, bucketZ);
                if (indices == null) {
                    continue;
                }
                for (int cursor = 0; cursor < indices.size(); cursor++) {
                    int i = indices.getInt(cursor);
                    long packed = packedColumns[i];
                    int cx = PositionUtil.unpackX(packed);
                    int cz = PositionUtil.unpackZ(packed);
                    long dirtyTimestamp = i < timestamps.length ? timestamps[i] : 0L;
                    if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= maxDistance
                            && shouldNotifyPlayerOfDirtyColumn(player, dimension, cx, cz, dirtyTimestamp)) {
                        filtered[count] = packed;
                        filteredTimestamps[count] = dirtyTimestamp;
                        count++;
                    }
                }
            }
        }
        if (count == packedColumns.length) {
            return new DirtyColumnsS2CPayload(packedColumns, timestamps);
        }
        long[] trimmed = new long[count];
        long[] trimmedTimestamps = new long[count];
        System.arraycopy(filtered, 0, trimmed, 0, count);
        System.arraycopy(filteredTimestamps, 0, trimmedTimestamps, 0, count);
        return new DirtyColumnsS2CPayload(trimmed, trimmedTimestamps);
    }

    private static boolean shouldNotifyPlayerOfDirtyColumn(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            int cx,
            int cz,
            long dirtyTimestamp) {
        if (dirtyTimestamp <= 0L) {
            return true;
        }

        long clientTimestamp = VSSServerNetworking.clientKnownColumnTimestamp(player, dimension, cx, cz);
        return clientTimestamp > 0L && clientTimestamp < dirtyTimestamp;
    }

    private record DirtyBatchIndex(
            long[] packedColumns,
            long[] timestamps,
            Long2ObjectOpenHashMap<IntArrayList> buckets) {
        static DirtyBatchIndex of(long[] packedColumns, long[] timestamps) {
            Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
            for (int i = 0; i < packedColumns.length; i++) {
                int cx = PositionUtil.unpackX(packedColumns[i]);
                int cz = PositionUtil.unpackZ(packedColumns[i]);
                long bucketKey = bucketKey(Math.floorDiv(cx, DIRTY_BUCKET_SIZE), Math.floorDiv(cz, DIRTY_BUCKET_SIZE));
                buckets.computeIfAbsent(bucketKey, ignored -> new IntArrayList()).add(i);
            }
            return new DirtyBatchIndex(packedColumns, timestamps, buckets);
        }

        IntArrayList indices(int bucketX, int bucketZ) {
            return buckets.get(bucketKey(bucketX, bucketZ));
        }
    }

    private static long bucketKey(int bucketX, int bucketZ) {
        return ((long) bucketX << 32) ^ (bucketZ & 0xFFFF_FFFFL);
    }
}
