package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.compat.ModCompat;
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
    private static final long VERIFICATION_RETRY_INTERVAL_NANOS = 1_000_000_000L;
    private static final int VERIFICATION_RETRIES_PER_TICK = 32;

    private final String scope;
    private final ReconciliationListener reconciliationListener;
    private final LongOpenHashSet sentRegions = new LongOpenHashSet();
    private final LongOpenHashSet queuedRegions = new LongOpenHashSet();
    private final ArrayDeque<Long> pendingRegions = new ArrayDeque<>();
    private final ArrayDeque<Long> delayedRegions = new ArrayDeque<>();
    private final Long2LongOpenHashMap delayedRegionDeadlines = new Long2LongOpenHashMap();
    private final ArrayDeque<Long> verificationRetryRegions = new ArrayDeque<>();
    private final Long2LongOpenHashMap verificationRetryDeadlines = new Long2LongOpenHashMap();

    private ResourceKey<Level> dimension;
    private int centerRegionX = Integer.MIN_VALUE;
    private int centerRegionZ = Integer.MIN_VALUE;
    private int maxRegionRing = -1;
    private boolean resetPending = true;

    ClientPresenceReporter(String scope, ReconciliationListener reconciliationListener) {
        this.scope = scope;
        this.reconciliationListener = reconciliationListener;
        delayedRegionDeadlines.defaultReturnValue(0L);
        verificationRetryDeadlines.defaultReturnValue(0L);
    }

    void recordKnownColumn(
            ResourceKey<Level> dimension,
            long packed,
            long columnTimestamp,
            int[] replacementSectionYs) {
        if (dimension == null || columnTimestamp <= 0L) {
            return;
        }
        ClientLodPresenceCache.recordColumn(
                scope,
                dimension,
                packed,
                columnTimestamp,
                replacementSectionYs);
        queueRegionForColumn(dimension, packed);
    }

    void removeKnownColumn(ResourceKey<Level> dimension, long packed) {
        ClientLodPresenceCache.removeColumn(scope, dimension, packed);
    }

    ModCompat.LocalColumnState getLocalColumnState(
            ClientLevel level,
            ResourceKey<Level> dimension,
            long packed) {
        byte[] expectedSectionYs = ClientLodPresenceCache.sectionManifest(scope, dimension, packed);
        if (expectedSectionYs == null) {
            return ModCompat.LocalColumnState.UNKNOWN;
        }
        return ModCompat.getVoxyLocalColumnState(
                level,
                PositionUtil.unpackX(packed),
                PositionUtil.unpackZ(packed),
                expectedSectionYs);
    }

    void updateWindow(
            ClientLevel level,
            ResourceKey<Level> dimension,
            int playerCx,
            int playerCz,
            int lodDistance) {
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
    }

    void drain(
            ClientLevel level,
            ResourceKey<Level> dimension,
            boolean allowZstd) {
        queueDueIncrementalRegions();
        queueDueVerificationRetries();
        if (pendingRegions.isEmpty()) {
            if (resetPending) {
                sendPayload(dimension, true, new ArrayList<>(), allowZstd);
                resetPending = false;
            }
            return;
        }

        int batchesProcessed = 0;
        int packetsPerTick = packetsPerTick();
        int regionsPerPacket = regionsPerPacket();
        int columnsPerPacket = columnsPerPacket();
        while (batchesProcessed < packetsPerTick && !pendingRegions.isEmpty()) {
            batchesProcessed++;
            ArrayList<RegionPresenceC2SPayload.RegionEntry> entries = new ArrayList<>();
            int consumedRegions = 0;
            int columnCount = 0;
            while (consumedRegions < regionsPerPacket
                    && columnCount < columnsPerPacket
                    && !pendingRegions.isEmpty()) {
                long key = pendingRegions.pollFirst();
                queuedRegions.remove(key);
                consumedRegions++;
                int regionX = ClientLodPresenceCache.regionKeyX(key);
                int regionZ = ClientLodPresenceCache.regionKeyZ(key);
                ClientLodPresenceCache.RegionReconciliation reconciliation = ClientLodPresenceCache.reconcileRegion(
                        scope,
                        dimension,
                        regionX,
                        regionZ,
                        level);

                applyReconciliation(reconciliation);
                if (!reconciliation.complete()) {
                    scheduleVerificationRetry(key);
                    continue;
                }
                sentRegions.add(key);
                RegionPresenceC2SPayload.RegionEntry entry = reconciliation.entry();
                entries.add(entry);
                columnCount += entry.count();
            }

            if (entries.isEmpty()) {
                if (resetPending && pendingRegions.isEmpty()) {
                    sendPayload(dimension, true, entries, allowZstd);
                    resetPending = false;
                }
                continue;
            }

            boolean reset = resetPending;
            resetPending = false;
            sendPayload(dimension, reset, entries, allowZstd);
        }
    }

    void reset(ResourceKey<Level> dimension) {
        this.dimension = dimension;
        sentRegions.clear();
        queuedRegions.clear();
        pendingRegions.clear();
        delayedRegions.clear();
        delayedRegionDeadlines.clear();
        verificationRetryRegions.clear();
        verificationRetryDeadlines.clear();
        ModCompat.resetVoxyLocalValidation();
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

    private void applyReconciliation(ClientLodPresenceCache.RegionReconciliation reconciliation) {
        RegionPresenceC2SPayload.RegionEntry entry = reconciliation.entry();
        int baseX = entry.regionX() * RegionPresenceC2SPayload.REGION_SIZE;
        int baseZ = entry.regionZ() * RegionPresenceC2SPayload.REGION_SIZE;
        int[] slots = entry.slots();
        long[] timestamps = entry.timestamps();
        int count = Math.min(entry.count(), Math.min(slots.length, timestamps.length));
        for (int i = 0; i < count; i++) {
            int slot = slots[i];
            long packed = PositionUtil.packPosition(
                    baseX + (slot & (RegionPresenceC2SPayload.REGION_SIZE - 1)),
                    baseZ + (slot >>> 5));
            reconciliationListener.onPresent(packed, timestamps[i]);
        }
        for (long packed : reconciliation.missingColumns()) {
            reconciliationListener.onMissing(packed);
        }
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
        }
        if (delayedRegionDeadlines.containsKey(key)) {
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

    private void scheduleVerificationRetry(long key) {
        if (verificationRetryDeadlines.containsKey(key)) {
            return;
        }
        verificationRetryDeadlines.put(key, System.nanoTime() + VERIFICATION_RETRY_INTERVAL_NANOS);
        verificationRetryRegions.addLast(key);
    }

    private void queueDueVerificationRetries() {
        long now = System.nanoTime();
        int moved = 0;
        while (moved < VERIFICATION_RETRIES_PER_TICK && !verificationRetryRegions.isEmpty()) {
            long key = verificationRetryRegions.peekFirst();
            if (!verificationRetryDeadlines.containsKey(key)) {
                verificationRetryRegions.pollFirst();
                continue;
            }
            long deadline = verificationRetryDeadlines.get(key);
            if (now - deadline < 0L) {
                break;
            }
            verificationRetryRegions.pollFirst();
            verificationRetryDeadlines.remove(key);
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

    interface ReconciliationListener {
        void onPresent(long packed, long timestamp);

        void onMissing(long packed);
    }
}
