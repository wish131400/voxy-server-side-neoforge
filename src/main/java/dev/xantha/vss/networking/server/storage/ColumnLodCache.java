package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.processing.EncodedColumnData;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class ColumnLodCache {
    private final VSSServerConfig config;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(1024, 0.75F, true);
    private long cachedBytes;
    private long hits;
    private long misses;
    private long puts;
    private long evictions;
    private long invalidations;
    private long preloadBytes;
    private int preloadEntries;
    private long preloadPuts;
    private long preloadUsefulHits;
    private long preloadUnusedEvictions;

    public ColumnLodCache(VSSServerConfig config) {
        this.config = config;
    }

    public synchronized Entry get(ResourceKey<Level> dimension, int cx, int cz) {
        if (!config.enableColumnCache) {
            return null;
        }

        Entry entry = entries.get(new Key(dimension.location(), cx, cz));
        if (entry == null) {
            misses++;
        } else {
            hits++;
            if (entry.preloaded()) {
                entry = entry.withPreloaded(false);
                entries.put(new Key(dimension.location(), cx, cz), entry);
                preloadEntries = Math.max(0, preloadEntries - 1);
                preloadBytes = Math.max(0L, preloadBytes - entry.sizeBytes());
                preloadUsefulHits++;
            }
        }
        return entry;
    }

    public synchronized Entry peek(ResourceKey<Level> dimension, int cx, int cz) {
        if (!config.enableColumnCache) {
            return null;
        }
        return entries.get(new Key(dimension.location(), cx, cz));
    }

    public synchronized void put(ResourceKey<Level> dimension, EncodedColumnData columnData) {
        put(dimension, columnData, false);
    }

    public synchronized void putPreloaded(ResourceKey<Level> dimension, EncodedColumnData columnData) {
        put(dimension, columnData, true);
    }

    private void put(ResourceKey<Level> dimension, EncodedColumnData columnData, boolean preloaded) {
        if (!config.enableColumnCache || columnData == null || columnData.encodedBytes() == null || !columnData.completeColumn()) {
            return;
        }

        int sizeBytes = columnData.encodedBytes().length;
        if (sizeBytes <= 0 || sizeBytes > config.columnCacheMaxBytes) {
            return;
        }

        Key key = new Key(dimension.location(), columnData.chunkX(), columnData.chunkZ());
        Entry previous = entries.remove(key);
        boolean effectivePreloaded = preloaded;
        if (previous != null) {
            if (previous.timestamp() > columnData.columnStamp()) {
                entries.put(key, previous);
                return;
            }
            effectivePreloaded = preloaded && previous.preloaded();
            cachedBytes -= previous.sizeBytes();
            if (previous.preloaded()) {
                preloadEntries = Math.max(0, preloadEntries - 1);
                preloadBytes = Math.max(0L, preloadBytes - previous.sizeBytes());
            }
        }

        entries.put(key, new Entry(
                columnData.chunkX(),
                columnData.chunkZ(),
                columnData.columnStamp(),
                columnData.compression(),
                columnData.rawSize(),
                columnData.encodedBytes(),
                sizeBytes,
                columnData.schemaVersion(),
                columnData.completeColumn(),
                columnData.sectionYs(),
                columnData.sectionLengths(),
                columnData.encodedCrc32c(),
                effectivePreloaded));
        this.cachedBytes += sizeBytes;
        if (effectivePreloaded) {
            preloadEntries++;
            preloadBytes += sizeBytes;
            preloadPuts++;
        }
        puts++;
        evictProbationOverflow();
        evictOverflow();
    }

    public synchronized void invalidate(ResourceKey<Level> dimension, int cx, int cz) {
        Entry removed = entries.remove(new Key(dimension.location(), cx, cz));
        if (removed != null) {
            cachedBytes -= removed.sizeBytes();
            removePreloadAccounting(removed);
            invalidations++;
        }
    }

    public synchronized void invalidateOlderThan(ResourceKey<Level> dimension, int cx, int cz, long minimumInvalidTimestamp) {
        Key key = new Key(dimension.location(), cx, cz);
        Entry entry = entries.get(key);
        if (entry == null || entry.timestamp() >= minimumInvalidTimestamp) {
            return;
        }
        entries.remove(key);
        cachedBytes -= entry.sizeBytes();
        removePreloadAccounting(entry);
        invalidations++;
    }

    public synchronized void clear() {
        entries.clear();
        cachedBytes = 0L;
        preloadBytes = 0L;
        preloadEntries = 0;
    }

    public synchronized String diagnostics() {
        return String.format(
                "entries=%d, bytes=%.2f MiB, hits=%d, misses=%d, puts=%d, evictions=%d, invalidations=%d, preload={entries=%d, bytes=%.2f MiB, puts=%d, usefulHits=%d, unusedEvictions=%d}",
                entries.size(),
                cachedBytes / (double) VSSServerConfig.BYTES_PER_MIB,
                hits,
                misses,
                puts,
                evictions,
                invalidations,
                preloadEntries,
                preloadBytes / (double) VSSServerConfig.BYTES_PER_MIB,
                preloadPuts,
                preloadUsefulHits,
                preloadUnusedEvictions);
    }

    private void evictProbationOverflow() {
        long maxPreloadBytes = (long) config.columnCacheMaxBytes * config.preloadCacheMaxPercent / 100L;
        int maxPreloadEntries = config.columnCacheMaxEntries * config.preloadCacheMaxPercent / 100;
        while ((preloadEntries > maxPreloadEntries || preloadBytes > maxPreloadBytes) && preloadEntries > 0) {
            if (!evictOldestPreloaded()) {
                break;
            }
        }
    }

    private void evictOverflow() {
        while ((entries.size() > config.columnCacheMaxEntries || cachedBytes > config.columnCacheMaxBytes)
                && !entries.isEmpty()) {
            if (preloadEntries > 0 && evictOldestPreloaded()) {
                continue;
            }
            Map.Entry<Key, Entry> eldest = entries.entrySet().iterator().next();
            cachedBytes -= eldest.getValue().sizeBytes();
            entries.remove(eldest.getKey());
            evictions++;
        }
    }

    private boolean evictOldestPreloaded() {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (!entry.preloaded()) {
                continue;
            }
            iterator.remove();
            cachedBytes -= entry.sizeBytes();
            removePreloadAccounting(entry);
            preloadUnusedEvictions++;
            evictions++;
            return true;
        }
        return false;
    }

    private void removePreloadAccounting(Entry entry) {
        if (entry.preloaded()) {
            preloadEntries = Math.max(0, preloadEntries - 1);
            preloadBytes = Math.max(0L, preloadBytes - entry.sizeBytes());
        }
    }

    private record Key(ResourceLocation dimension, int chunkX, int chunkZ) {
    }

    public record Entry(
            int chunkX,
            int chunkZ,
            long timestamp,
            int compression,
            int rawSize,
            byte[] encodedBytes,
            int sizeBytes,
            int schemaVersion,
            boolean completeColumn,
            int[] sectionYs,
            int[] sectionLengths,
            int encodedCrc32c,
            boolean preloaded) {
        public Entry {
            sectionYs = sectionYs != null ? sectionYs.clone() : new int[0];
            sectionLengths = sectionLengths != null ? sectionLengths.clone() : new int[0];
        }

        @Override
        public int[] sectionYs() {
            return sectionYs.clone();
        }

        @Override
        public int[] sectionLengths() {
            return sectionLengths.clone();
        }

        Entry withPreloaded(boolean preloaded) {
            return new Entry(
                    chunkX,
                    chunkZ,
                    timestamp,
                    compression,
                    rawSize,
                    encodedBytes,
                    sizeBytes,
                    schemaVersion,
                    completeColumn,
                    sectionYs,
                    sectionLengths,
                    encodedCrc32c,
                    preloaded);
        }

        public EncodedColumnData columnData() {
            return new EncodedColumnData(
                    chunkX,
                    chunkZ,
                    compression,
                    rawSize,
                    encodedBytes,
                    timestamp,
                    schemaVersion,
                    completeColumn,
                    sectionYs,
                    sectionLengths,
                    encodedCrc32c);
        }
    }
}
