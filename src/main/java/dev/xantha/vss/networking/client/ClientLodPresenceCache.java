package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class ClientLodPresenceCache {
    private static final int FILE_MAGIC = 0x56535043;
    private static final int FILE_VERSION = 1;
    private static final int MAX_SCOPES = 64;
    private static final int MAX_DIMENSIONS_PER_SCOPE = 16;
    private static final int MAX_REGIONS_PER_DIMENSION = 262_144;
    private static final int MAX_COLUMNS_PER_DIMENSION = 1_000_000;
    private static final int SAVE_UPDATE_THRESHOLD = 512;
    private static final long SAVE_INTERVAL_NANOS = 10_000_000_000L;
    private static final String CACHE_FILE = "vss-lod-presence.dat";

    private static final Map<String, ScopeCache> scopes = new HashMap<>();
    private static boolean loaded;
    private static boolean dirty;
    private static int updatesSinceSave;
    private static long lastSaveNanos;
    private static ExecutorService saveExecutor;
    private static Future<?> saveFuture;
    private static boolean saveQueued;

    private ClientLodPresenceCache() {
    }

    static synchronized String currentScope() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            try {
                return "singleplayer:" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
            } catch (Exception ignored) {
                return "singleplayer";
            }
        }
        try {
            var serverData = minecraft.getCurrentServer();
            if (serverData != null && serverData.ip != null && !serverData.ip.isBlank()) {
                return "server:" + serverData.ip.toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    static synchronized void recordColumn(String scopeKey, ResourceKey<Level> dimension, long packed, long timestamp) {
        if (timestamp <= 0L) {
            return;
        }
        ensureLoaded();
        DimensionCache dimensionCache = dimensionCache(scopeKey, dimension, true);
        if (dimensionCache == null) {
            return;
        }

        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        RegionPresence region = dimensionCache.region(regionKey(Math.floorDiv(cx, RegionPresenceC2SPayload.REGION_SIZE),
                Math.floorDiv(cz, RegionPresenceC2SPayload.REGION_SIZE)), true);
        if (region == null) {
            return;
        }
        int slot = localSlot(cx, cz);
        long previous = region.timestamp(slot);
        if (previous == timestamp) {
            return;
        }
        region.put(slot, timestamp);
        dimensionCache.columnCount += previous <= 0L ? 1 : 0;
        trimDimension(dimensionCache);
        markDirty();
    }

    static synchronized void removeColumn(String scopeKey, ResourceKey<Level> dimension, long packed) {
        ensureLoaded();
        DimensionCache dimensionCache = dimensionCache(scopeKey, dimension, false);
        if (dimensionCache == null) {
            return;
        }

        int cx = PositionUtil.unpackX(packed);
        int cz = PositionUtil.unpackZ(packed);
        long regionKey = regionKey(Math.floorDiv(cx, RegionPresenceC2SPayload.REGION_SIZE),
                Math.floorDiv(cz, RegionPresenceC2SPayload.REGION_SIZE));
        RegionPresence region = dimensionCache.regions.get(regionKey);
        if (region == null) {
            return;
        }
        if (region.remove(localSlot(cx, cz))) {
            dimensionCache.columnCount = Math.max(0, dimensionCache.columnCount - 1);
            if (region.count == 0) {
                dimensionCache.regions.remove(regionKey);
            }
            markDirty();
        }
    }

    static synchronized RegionPresenceC2SPayload.RegionEntry regionEntry(
            String scopeKey,
            ResourceKey<Level> dimension,
            int regionX,
            int regionZ,
            Level level) {
        ensureLoaded();
        DimensionCache dimensionCache = dimensionCache(scopeKey, dimension, false);
        if (dimensionCache == null) {
            return null;
        }
        long key = regionKey(regionX, regionZ);
        RegionPresence region = dimensionCache.regions.get(key);
        if (region == null) {
            return null;
        }
        RegionPresenceC2SPayload.RegionEntry entry = buildRegionEntry(dimensionCache, region, regionX, regionZ, level);
        if (region.count == 0) {
            dimensionCache.regions.remove(key);
        }
        return entry;
    }

    static synchronized int seedRegion(
            String scopeKey,
            ResourceKey<Level> dimension,
            int regionX,
            int regionZ,
            Long2LongOpenHashMap target,
            Level level) {
        ensureLoaded();
        DimensionCache dimensionCache = dimensionCache(scopeKey, dimension, false);
        if (dimensionCache == null) {
            return 0;
        }
        long key = regionKey(regionX, regionZ);
        RegionPresence region = dimensionCache.regions.get(key);
        if (region == null) {
            return 0;
        }

        boolean verifyVoxy = ModCompat.isVoxyLoaded() && level != null;
        int seeded = 0;
        int removed = 0;
        int baseX = regionX * RegionPresenceC2SPayload.REGION_SIZE;
        int baseZ = regionZ * RegionPresenceC2SPayload.REGION_SIZE;
        for (int slot = 0; slot < RegionPresenceC2SPayload.REGION_SLOT_COUNT; slot++) {
            long timestamp = region.timestamp(slot);
            if (timestamp <= 0L) {
                continue;
            }
            int cx = baseX + (slot & (RegionPresenceC2SPayload.REGION_SIZE - 1));
            int cz = baseZ + (slot >>> 5);
            if (verifyVoxy) {
                ModCompat.LocalColumnState state = ModCompat.getVoxyLocalColumnState(level, cx, cz);
                if (state == ModCompat.LocalColumnState.MISSING) {
                    if (region.remove(slot)) {
                        removed++;
                    }
                    continue;
                }
                if (state != ModCompat.LocalColumnState.PRESENT) {
                    continue;
                }
            }
            target.put(PositionUtil.packPosition(cx, cz), timestamp);
            seeded++;
        }
        if (removed > 0) {
            dimensionCache.columnCount = Math.max(0, dimensionCache.columnCount - removed);
            if (region.count == 0) {
                dimensionCache.regions.remove(key);
            }
            markDirty();
        }
        return seeded;
    }

    private static RegionPresenceC2SPayload.RegionEntry buildRegionEntry(
            DimensionCache dimensionCache,
            RegionPresence region,
            int regionX,
            int regionZ,
            Level level) {
        boolean verifyVoxy = ModCompat.isVoxyLoaded() && level != null;
        int[] slots = new int[Math.max(0, region.count)];
        long[] entryTimestamps = new long[Math.max(0, region.count)];
        long[] copiedBitmap = new long[RegionPresenceC2SPayload.REGION_BITMAP_LONGS];
        int count = 0;
        int removed = 0;
        int baseX = regionX * RegionPresenceC2SPayload.REGION_SIZE;
        int baseZ = regionZ * RegionPresenceC2SPayload.REGION_SIZE;
        for (int slot = 0; slot < RegionPresenceC2SPayload.REGION_SLOT_COUNT; slot++) {
            long timestamp = region.timestamp(slot);
            if (timestamp <= 0L) {
                continue;
            }
            if (verifyVoxy) {
                int cx = baseX + (slot & (RegionPresenceC2SPayload.REGION_SIZE - 1));
                int cz = baseZ + (slot >>> 5);
                ModCompat.LocalColumnState state = ModCompat.getVoxyLocalColumnState(level, cx, cz);
                if (state == ModCompat.LocalColumnState.MISSING) {
                    if (region.remove(slot)) {
                        removed++;
                    }
                    continue;
                }
                if (state != ModCompat.LocalColumnState.PRESENT) {
                    continue;
                }
            }
            copiedBitmap[slot >>> 6] |= 1L << (slot & 63);
            slots[count] = slot;
            entryTimestamps[count] = timestamp;
            count++;
        }
        if (removed > 0) {
            dimensionCache.columnCount = Math.max(0, dimensionCache.columnCount - removed);
            markDirty();
        }
        if (count == 0) {
            return null;
        }
        return new RegionPresenceC2SPayload.RegionEntry(
                regionX,
                regionZ,
                copiedBitmap,
                Arrays.copyOf(slots, count),
                Arrays.copyOf(entryTimestamps, count),
                count);
    }

    static void flush() {
        waitForScheduledSaves();
        CacheSnapshot snapshot = null;
        synchronized (ClientLodPresenceCache.class) {
            ensureLoaded();
            if (dirty) {
                snapshot = snapshotLocked(cachePath());
                dirty = false;
                updatesSinceSave = 0;
                lastSaveNanos = System.nanoTime();
            }
        }
        if (snapshot != null) {
            writeSnapshot(snapshot);
        }
    }

    private static DimensionCache dimensionCache(String scopeKey, ResourceKey<Level> dimension, boolean create) {
        String dimensionKey = dimension.location().toString();
        ScopeCache scope = scopes.get(scopeKey);
        if (scope == null) {
            if (!create || scopes.size() >= MAX_SCOPES) {
                return null;
            }
            scope = new ScopeCache();
            scopes.put(scopeKey, scope);
        }

        DimensionCache dimensionCache = scope.dimensions.get(dimensionKey);
        if (dimensionCache == null && create) {
            if (scope.dimensions.size() >= MAX_DIMENSIONS_PER_SCOPE) {
                return null;
            }
            dimensionCache = new DimensionCache();
            scope.dimensions.put(dimensionKey, dimensionCache);
        }
        return dimensionCache;
    }

    private static void trimDimension(DimensionCache dimensionCache) {
        while (dimensionCache.columnCount > MAX_COLUMNS_PER_DIMENSION && !dimensionCache.regions.isEmpty()) {
            Iterator<Map.Entry<Long, RegionPresence>> iterator = dimensionCache.regions.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Map.Entry<Long, RegionPresence> removed = iterator.next();
            dimensionCache.columnCount = Math.max(0, dimensionCache.columnCount - removed.getValue().count);
            iterator.remove();
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        lastSaveNanos = System.nanoTime();
        Path path = cachePath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream fileIn = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(fileIn)) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION) {
                return;
            }
            int scopeCount = Math.min(Math.max(0, in.readInt()), MAX_SCOPES);
            for (int s = 0; s < scopeCount; s++) {
                String scopeKey = in.readUTF();
                ScopeCache scope = new ScopeCache();
                int dimensionCount = Math.min(Math.max(0, in.readInt()), MAX_DIMENSIONS_PER_SCOPE);
                for (int d = 0; d < dimensionCount; d++) {
                    String dimensionKey = in.readUTF();
                    DimensionCache dimension = new DimensionCache();
                    int regionCount = Math.min(Math.max(0, in.readInt()), MAX_REGIONS_PER_DIMENSION);
                    for (int r = 0; r < regionCount; r++) {
                        long key = in.readLong();
                        RegionPresence region = new RegionPresence();
                        int columnCount = Math.min(Math.max(0, in.readInt()), RegionPresenceC2SPayload.REGION_SLOT_COUNT);
                        for (int c = 0; c < columnCount; c++) {
                            int slot = in.readUnsignedShort();
                            long timestamp = in.readLong();
                            if (slot < RegionPresenceC2SPayload.REGION_SLOT_COUNT && timestamp > 0L) {
                                long previous = region.timestamp(slot);
                                region.put(slot, timestamp);
                                if (previous <= 0L) {
                                    dimension.columnCount++;
                                }
                            }
                        }
                        if (region.count > 0) {
                            dimension.regions.put(key, region);
                        }
                    }
                    trimDimension(dimension);
                    scope.dimensions.put(dimensionKey, dimension);
                }
                scopes.put(scopeKey, scope);
            }
        } catch (Exception e) {
            scopes.clear();
            VSSLogger.debug("Failed to load VSS LOD presence cache: " + e.getMessage());
        }
    }

    private static synchronized void markDirty() {
        dirty = true;
        updatesSinceSave++;
        long now = System.nanoTime();
        if (updatesSinceSave >= SAVE_UPDATE_THRESHOLD || now - lastSaveNanos >= SAVE_INTERVAL_NANOS) {
            scheduleSaveLocked(now);
        }
    }

    private static void scheduleSaveLocked(long now) {
        if (!dirty || saveQueued) {
            return;
        }
        CacheSnapshot snapshot = snapshotLocked(cachePath());
        dirty = false;
        updatesSinceSave = 0;
        lastSaveNanos = now;
        saveQueued = true;
        saveFuture = saveExecutorLocked().submit(() -> {
            boolean saved = writeSnapshot(snapshot);
            synchronized (ClientLodPresenceCache.class) {
                if (!saved) {
                    dirty = true;
                }
                saveQueued = false;
                if (dirty) {
                    long nextNow = System.nanoTime();
                    if (updatesSinceSave >= SAVE_UPDATE_THRESHOLD || nextNow - lastSaveNanos >= SAVE_INTERVAL_NANOS) {
                        scheduleSaveLocked(nextNow);
                    }
                }
            }
        });
    }

    private static ExecutorService saveExecutorLocked() {
        if (saveExecutor == null || saveExecutor.isShutdown() || saveExecutor.isTerminated()) {
            saveExecutor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "VSS-PresenceCacheWriter");
                thread.setDaemon(true);
                return thread;
            });
        }
        return saveExecutor;
    }

    private static void waitForScheduledSaves() {
        while (true) {
            Future<?> pending;
            synchronized (ClientLodPresenceCache.class) {
                if (!saveQueued) {
                    return;
                }
                pending = saveFuture;
            }
            if (pending == null) {
                return;
            }
            try {
                pending.get();
            } catch (Exception e) {
                VSSLogger.debug("Failed while waiting for VSS LOD presence cache save: " + e.getMessage());
                return;
            }
        }
    }

    private static CacheSnapshot snapshotLocked(Path path) {
        Map<String, ScopeSnapshot> scopeSnapshots = new HashMap<>();
        for (Map.Entry<String, ScopeCache> scopeEntry : scopes.entrySet()) {
            Map<String, DimensionSnapshot> dimensionSnapshots = new HashMap<>();
            ScopeCache scope = scopeEntry.getValue();
            for (Map.Entry<String, DimensionCache> dimensionEntry : scope.dimensions.entrySet()) {
                Map<Long, RegionSnapshot> regionSnapshots = new HashMap<>();
                DimensionCache dimension = dimensionEntry.getValue();
                for (Map.Entry<Long, RegionPresence> regionEntry : dimension.regions.entrySet()) {
                    RegionPresence region = regionEntry.getValue();
                    if (region.count > 0) {
                        regionSnapshots.put(regionEntry.getKey(), new RegionSnapshot(
                                region.count,
                                Arrays.copyOf(region.timestamps, region.timestamps.length)));
                    }
                }
                dimensionSnapshots.put(dimensionEntry.getKey(), new DimensionSnapshot(regionSnapshots));
            }
            scopeSnapshots.put(scopeEntry.getKey(), new ScopeSnapshot(dimensionSnapshots));
        }
        return new CacheSnapshot(path, scopeSnapshots);
    }

    private static boolean writeSnapshot(CacheSnapshot snapshot) {
        Path path = snapshot.path();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream fileOut = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(fileOut)) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(FILE_VERSION);
                out.writeInt(snapshot.scopes().size());
                for (Map.Entry<String, ScopeSnapshot> scopeEntry : snapshot.scopes().entrySet()) {
                    out.writeUTF(scopeEntry.getKey());
                    ScopeSnapshot scope = scopeEntry.getValue();
                    out.writeInt(scope.dimensions.size());
                    for (Map.Entry<String, DimensionSnapshot> dimensionEntry : scope.dimensions.entrySet()) {
                        out.writeUTF(dimensionEntry.getKey());
                        DimensionSnapshot dimension = dimensionEntry.getValue();
                        out.writeInt(dimension.regions.size());
                        for (Map.Entry<Long, RegionSnapshot> regionEntry : dimension.regions.entrySet()) {
                            out.writeLong(regionEntry.getKey());
                            RegionSnapshot region = regionEntry.getValue();
                            out.writeInt(region.count);
                            long[] timestamps = region.timestamps();
                            for (int slot = 0; slot < timestamps.length; slot++) {
                                long timestamp = timestamps[slot];
                                if (timestamp > 0L) {
                                    out.writeShort(slot);
                                    out.writeLong(timestamp);
                                }
                            }
                        }
                    }
                }
            }
            moveIntoPlace(tmp, path);
            return true;
        } catch (Exception e) {
            VSSLogger.debug("Failed to save VSS LOD presence cache: " + e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private static void moveIntoPlace(Path tmp, Path path) throws Exception {
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path cachePath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CACHE_FILE);
    }

    private static int localSlot(int cx, int cz) {
        return (cx & (RegionPresenceC2SPayload.REGION_SIZE - 1))
                | ((cz & (RegionPresenceC2SPayload.REGION_SIZE - 1)) << 5);
    }

    static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    static int regionKeyX(long key) {
        return (int) (key >> 32);
    }

    static int regionKeyZ(long key) {
        return (int) key;
    }

    private static final class ScopeCache {
        private final Map<String, DimensionCache> dimensions = new HashMap<>();
    }

    private static final class DimensionCache {
        private final Map<Long, RegionPresence> regions = new HashMap<>();
        private int columnCount;

        private RegionPresence region(long key, boolean create) {
            RegionPresence region = regions.get(key);
            if (region == null && create) {
                region = new RegionPresence();
                regions.put(key, region);
            }
            return region;
        }
    }

    private static final class RegionPresence {
        private final long[] timestamps = new long[RegionPresenceC2SPayload.REGION_SLOT_COUNT];
        private int count;

        private long timestamp(int slot) {
            return timestamps[slot];
        }

        private void put(int slot, long timestamp) {
            if (timestamps[slot] <= 0L) {
                count++;
            }
            timestamps[slot] = timestamp;
        }

        private boolean remove(int slot) {
            if (timestamps[slot] <= 0L) {
                return false;
            }
            timestamps[slot] = 0L;
            count = Math.max(0, count - 1);
            return true;
        }
    }

    private record CacheSnapshot(Path path, Map<String, ScopeSnapshot> scopes) {
    }

    private record ScopeSnapshot(Map<String, DimensionSnapshot> dimensions) {
    }

    private record DimensionSnapshot(Map<Long, RegionSnapshot> regions) {
    }

    private record RegionSnapshot(int count, long[] timestamps) {
    }
}
