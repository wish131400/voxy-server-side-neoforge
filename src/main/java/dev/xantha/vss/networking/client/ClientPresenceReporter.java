package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class ClientPresenceReporter {
    private static final int REGIONS_PER_PACKET = 64;
    private static final int INTEGRATED_REGIONS_PER_PACKET = 16;
    private static final int COLUMNS_PER_PACKET = 4096;
    private static final int INTEGRATED_COLUMNS_PER_PACKET = 1024;
    private static final int PACKETS_PER_TICK = 2;
    private static final int INTEGRATED_PACKETS_PER_TICK = 1;
    private static final long INCREMENTAL_RESEND_INTERVAL_NANOS = 5_000_000_000L;
    private static final int INCREMENTAL_RESENDS_PER_TICK = 32;

    private final String scope;
    private final LongOpenHashSet sentRegions = new LongOpenHashSet();
    private final LongOpenHashSet queuedRegions = new LongOpenHashSet();
    private final ArrayDeque<Long> pendingRegions = new ArrayDeque<>();
    private final ArrayDeque<Long> delayedRegions = new ArrayDeque<>();
    private final Long2LongOpenHashMap delayedRegionDeadlines = new Long2LongOpenHashMap();

    private ResourceKey<Level> dimension;
    private int centerRegionX = Integer.MIN_VALUE;
    private int centerRegionZ = Integer.MIN_VALUE;
    private int maxRegionRing = -1;
    private boolean resetPending = true;

    ClientPresenceReporter(String scope) {
        this.scope = scope;
        delayedRegionDeadlines.defaultReturnValue(0L);
    }

    void recordKnownColumn(ResourceKey<Level> dimension, long packed, long columnTimestamp) {
        if (dimension == null || columnTimestamp <= 0L) {
            return;
        }
        ClientLodPresenceCache.recordColumn(scope, dimension, packed, columnTimestamp);
        queueRegionForColumn(dimension, packed);
    }

    void removeKnownColumn(ResourceKey<Level> dimension, long packed) {
        ClientLodPresenceCache.removeColumn(scope, dimension, packed);
    }

    void updateWindow(
            ClientLevel level,
            ResourceKey<Level> dimension,
            int playerCx,
            int playerCz,
            int lodDistance,
            Long2LongOpenHashMap columnTimestamps) {
        if (lodDistance <= 0) {
            return;
        }

        int regionSize = RegionPresenceC2SPayload.REGION_SIZE;
        int nextCenterRegionX = Math.floorDiv(playerCx, regionSize);
        int nextCenterRegionZ = Math.floorDiv(playerCz, regionSize);
        int nextMaxRegionRing = Math.max(0, (lodDistance + regionSize - 1) / regionSize);
        boolean reset = this.dimension == null
                || !this.dimension.equals(dimension)
                || maxRegionRing != nextMaxRegionRing;
        if (reset) {
            reset(dimension);
            maxRegionRing = nextMaxRegionRing;
        }
        if (!reset
                && nextCenterRegionX == centerRegionX
                && nextCenterRegionZ == centerRegionZ) {
            return;
        }

        this.dimension = dimension;
        centerRegionX = nextCenterRegionX;
        centerRegionZ = nextCenterRegionZ;
        maxRegionRing = nextMaxRegionRing;

        for (int ring = 0; ring <= nextMaxRegionRing; ring++) {
            queueRegionRing(nextCenterRegionX, nextCenterRegionZ, ring);
        }
        seedRegion(level, dimension, nextCenterRegionX, nextCenterRegionZ, columnTimestamps);
    }

    void drain(
            ClientLevel level,
            ResourceKey<Level> dimension,
            boolean allowZstd,
            Long2LongOpenHashMap columnTimestamps) {
        queueDueIncrementalRegions();
        if (pendingRegions.isEmpty()) {
            if (resetPending) {
                sendPayload(dimension, true, new ArrayList<>(), allowZstd);
                resetPending = false;
            }
            return;
        }

        int packetsSent = 0;
        int packetsPerTick = packetsPerTick();
        int regionsPerPacket = regionsPerPacket();
        int columnsPerPacket = columnsPerPacket();
        while (packetsSent < packetsPerTick && !pendingRegions.isEmpty()) {
            ArrayList<RegionPresenceC2SPayload.RegionEntry> entries = new ArrayList<>();
            int consumedRegions = 0;
            int columnCount = 0;
            while (consumedRegions < regionsPerPacket
                    && columnCount < columnsPerPacket
                    && !pendingRegions.isEmpty()) {
                long key = pendingRegions.pollFirst();
                queuedRegions.remove(key);
                sentRegions.add(key);
                consumedRegions++;
                int regionX = ClientLodPresenceCache.regionKeyX(key);
                int regionZ = ClientLodPresenceCache.regionKeyZ(key);
                seedRegion(level, dimension, regionX, regionZ, columnTimestamps);
                RegionPresenceC2SPayload.RegionEntry entry = ClientLodPresenceCache.regionEntry(
                        scope,
                        dimension,
                        regionX,
                        regionZ,
                        level);
                if (entry == null || entry.count() <= 0) {
                    continue;
                }
                entries.add(entry);
                columnCount += entry.count();
            }

            if (entries.isEmpty()) {
                if (resetPending && pendingRegions.isEmpty()) {
                    sendPayload(dimension, true, entries, allowZstd);
                    resetPending = false;
                    packetsSent++;
                }
                continue;
            }

            boolean reset = resetPending;
            resetPending = false;
            sendPayload(dimension, reset, entries, allowZstd);
            packetsSent++;
        }
    }

    void reset(ResourceKey<Level> dimension) {
        this.dimension = dimension;
        sentRegions.clear();
        queuedRegions.clear();
        pendingRegions.clear();
        delayedRegions.clear();
        delayedRegionDeadlines.clear();
        centerRegionX = Integer.MIN_VALUE;
        centerRegionZ = Integer.MIN_VALUE;
        maxRegionRing = -1;
        resetPending = true;
    }

    private void queueRegionRing(int centerRegionX, int centerRegionZ, int ring) {
        if (ring == 0) {
            queueRegion(centerRegionX, centerRegionZ, false);
            return;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            queueRegion(centerRegionX + dx, centerRegionZ - ring, false);
            queueRegion(centerRegionX + dx, centerRegionZ + ring, false);
        }
        for (int dz = -ring + 1; dz <= ring - 1; dz++) {
            queueRegion(centerRegionX - ring, centerRegionZ + dz, false);
            queueRegion(centerRegionX + ring, centerRegionZ + dz, false);
        }
    }

    private void seedRegion(
            ClientLevel level,
            ResourceKey<Level> dimension,
            int regionX,
            int regionZ,
            Long2LongOpenHashMap columnTimestamps) {
        ClientLodPresenceCache.seedRegion(scope, dimension, regionX, regionZ, columnTimestamps, level);
    }

    private void queueRegionForColumn(ResourceKey<Level> dimension, long packed) {
        if (this.dimension == null || !this.dimension.equals(dimension)) {
            return;
        }
        int regionX = Math.floorDiv(PositionUtil.unpackX(packed), RegionPresenceC2SPayload.REGION_SIZE);
        int regionZ = Math.floorDiv(PositionUtil.unpackZ(packed), RegionPresenceC2SPayload.REGION_SIZE);
        queueIncrementalRegion(regionX, regionZ);
    }

    private void queueIncrementalRegion(int regionX, int regionZ) {
        long key = ClientLodPresenceCache.regionKey(regionX, regionZ);
        if (!sentRegions.contains(key)) {
            queueRegion(regionX, regionZ, false);
            return;
        }
        if (queuedRegions.contains(key) || delayedRegionDeadlines.containsKey(key)) {
            return;
        }
        delayedRegionDeadlines.put(key, System.nanoTime() + INCREMENTAL_RESEND_INTERVAL_NANOS);
        delayedRegions.add(key);
    }

    private void queueRegion(int regionX, int regionZ, boolean forceResend) {
        long key = ClientLodPresenceCache.regionKey(regionX, regionZ);
        if (forceResend) {
            sentRegions.remove(key);
        }
        if (sentRegions.contains(key) || !queuedRegions.add(key)) {
            return;
        }
        pendingRegions.add(key);
    }

    private void sendPayload(
            ResourceKey<Level> dimension,
            boolean reset,
            ArrayList<RegionPresenceC2SPayload.RegionEntry> entries,
            boolean allowZstd) {
        try {
            VSSClientNetworking.sendRegionPresence(RegionPresenceC2SPayload.create(dimension, reset, entries, allowZstd));
        } catch (Exception e) {
            VSSLogger.debug("LOD presence summary send failed: " + e.getMessage());
        }
    }

    private void queueDueIncrementalRegions() {
        long now = System.nanoTime();
        int moved = 0;
        while (moved < INCREMENTAL_RESENDS_PER_TICK && !delayedRegions.isEmpty()) {
            long key = delayedRegions.peekFirst();
            if (!delayedRegionDeadlines.containsKey(key)) {
                delayedRegions.pollFirst();
                continue;
            }
            long deadline = delayedRegionDeadlines.get(key);
            if (now - deadline < 0L) {
                break;
            }
            delayedRegions.pollFirst();
            delayedRegionDeadlines.remove(key);
            if (!sentRegions.contains(key) || queuedRegions.contains(key)) {
                continue;
            }
            int regionX = ClientLodPresenceCache.regionKeyX(key);
            int regionZ = ClientLodPresenceCache.regionKeyZ(key);
            if (!isRegionInWindow(regionX, regionZ)) {
                continue;
            }
            queueRegion(regionX, regionZ, true);
            moved++;
        }
    }

    private boolean isRegionInWindow(int regionX, int regionZ) {
        return maxRegionRing >= 0
                && Math.max(Math.abs(regionX - centerRegionX), Math.abs(regionZ - centerRegionZ)) <= maxRegionRing;
    }

    private static int packetsPerTick() {
        return isIntegratedServer() ? INTEGRATED_PACKETS_PER_TICK : PACKETS_PER_TICK;
    }

    private static int regionsPerPacket() {
        return isIntegratedServer() ? INTEGRATED_REGIONS_PER_PACKET : REGIONS_PER_PACKET;
    }

    private static int columnsPerPacket() {
        return isIntegratedServer() ? INTEGRATED_COLUMNS_PER_PACKET : COLUMNS_PER_PACKET;
    }

    private static boolean isIntegratedServer() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }
}
