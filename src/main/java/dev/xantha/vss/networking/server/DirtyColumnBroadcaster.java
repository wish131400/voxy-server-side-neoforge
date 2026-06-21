package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class DirtyColumnBroadcaster {
    private static final Map<Level, LongOpenHashSet> DIRTY = new HashMap<>();
    private static final Map<ResourceLocation, Long2LongLinkedOpenHashMap> DIRTY_VERSIONS = new HashMap<>();
    private static int tickCounter;
    private static int cleanupTickCounter;

    private DirtyColumnBroadcaster() {
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        markDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        markDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !VSSServerConfig.CONFIG.enabled) {
            return;
        }
        LongOpenHashSet affectedColumns = new LongOpenHashSet();
        for (BlockPos pos : event.getAffectedBlocks()) {
            markDirtyBlock(level, pos);
            affectedColumns.add(PositionUtil.packPosition(pos.getX() >> 4, pos.getZ() >> 4));
        }
        for (long packed : affectedColumns) {
            markDirtyColumnAndNeighbors(level, PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed));
        }
    }

    static void tick(MinecraftServer server) {
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

        int interval = VSSServerConfig.CONFIG.dirtyBroadcastIntervalSeconds * 20;
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
            DirtyColumnsS2CPayload payload = new DirtyColumnsS2CPayload(packed);
            for (ServerPlayer player : level.players()) {
                if (VSSServerNetworking.isRegistered(player)) {
                    VSSNetworking.sendToPlayer(player, payload);
                }
            }
        }
    }

    static void clear() {
        DIRTY.clear();
        DIRTY_VERSIONS.clear();
        tickCounter = 0;
        cleanupTickCounter = 0;
    }

    static String diagnostics() {
        int columns = 0;
        for (LongOpenHashSet positions : DIRTY.values()) {
            columns += positions.size();
        }
        return "dirtyLevels=" + DIRTY.size()
                + ", dirtyColumns=" + columns
                + ", versionLevels=" + DIRTY_VERSIONS.size()
                + ", versionColumns=" + dirtyVersionCount();
    }

    static Component diagnosticsComponent() {
        int columns = 0;
        for (LongOpenHashSet positions : DIRTY.values()) {
            columns += positions.size();
        }
        return Component.translatable("vss.command.stats.dirty", DIRTY.size(), columns, DIRTY_VERSIONS.size(), dirtyVersionCount());
    }

    static long latestDirtyTimestamp(ResourceKey<Level> dimension, int cx, int cz) {
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

    public static void markDirty(Object levelAccess, BlockPos pos) {
        if (levelAccess instanceof ServerLevel level
                && VSSServerConfig.CONFIG.enabled) {
            markDirtyBlock(level, pos);
        }
    }

    public static void markDirtyColumn(Object levelAccess, int cx, int cz) {
        if (levelAccess instanceof ServerLevel level
                && VSSServerConfig.CONFIG.enabled) {
            markDirtyColumn(level, cx, cz);
        }
    }

    public static void markDirtyColumnAndNeighbors(Object levelAccess, int cx, int cz) {
        if (levelAccess instanceof ServerLevel level
                && VSSServerConfig.CONFIG.enabled) {
            markDirtyColumnAndNeighbors(level, cx, cz);
        }
    }

    private static void markDirtyBlock(ServerLevel level, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        markDirtyColumn(level, cx, cz);

        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        if (localX == 0) {
            markDirtyColumn(level, cx - 1, cz);
        } else if (localX == 15) {
            markDirtyColumn(level, cx + 1, cz);
        }
        if (localZ == 0) {
            markDirtyColumn(level, cx, cz - 1);
        } else if (localZ == 15) {
            markDirtyColumn(level, cx, cz + 1);
        }
    }

    private static void markDirtyColumnAndNeighbors(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                markDirtyColumn(level, cx + dx, cz + dz);
            }
        }
    }

    private static void markDirtyColumn(ServerLevel level, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        recordDirtyVersion(level.dimension(), packed, VSSConstants.epochMillis());
        VSSServerNetworking.invalidateCachedColumn(level, cx, cz);
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
}
