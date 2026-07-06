package dev.xantha.vss.networking.server.storage;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.processing.EncodedColumnData;
import java.util.Arrays;
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
        }
        return entry;
    }

    public synchronized void put(ResourceKey<Level> dimension, EncodedColumnData columnData) {
        if (!config.enableColumnCache || columnData == null || columnData.encodedBytes() == null || !columnData.completeColumn()) {
            return;
        }

        int sizeBytes = columnData.encodedBytes().length;
        if (sizeBytes <= 0 || sizeBytes > config.columnCacheMaxBytes) {
            return;
        }

        Key key = new Key(dimension.location(), columnData.chunkX(), columnData.chunkZ());
        Entry previous = entries.remove(key);
        if (previous != null) {
            if (previous.timestamp() > columnData.columnStamp()) {
                entries.put(key, previous);
                return;
            }
            cachedBytes -= previous.sizeBytes();
        }

        byte[] cachedSections = Arrays.copyOf(columnData.encodedBytes(), columnData.encodedBytes().length);
        entries.put(key, new Entry(
                columnData.chunkX(),
                columnData.chunkZ(),
                columnData.columnStamp(),
                columnData.compression(),
                columnData.rawSize(),
                cachedSections,
                sizeBytes,
                columnData.schemaVersion(),
                columnData.completeColumn()));
        this.cachedBytes += sizeBytes;
        puts++;
        evictOverflow();
    }

    public synchronized void invalidate(ResourceKey<Level> dimension, int cx, int cz) {
        Entry removed = entries.remove(new Key(dimension.location(), cx, cz));
        if (removed != null) {
            cachedBytes -= removed.sizeBytes();
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
        invalidations++;
    }

    public synchronized void clear() {
        entries.clear();
        cachedBytes = 0L;
    }

    public synchronized String diagnostics() {
        return String.format(
                "entries=%d, bytes=%.2f MiB, hits=%d, misses=%d, puts=%d, evictions=%d, invalidations=%d",
                entries.size(),
                cachedBytes / (double) VSSServerConfig.BYTES_PER_MIB,
                hits,
                misses,
                puts,
                evictions,
                invalidations);
    }

    private void evictOverflow() {
        while ((entries.size() > config.columnCacheMaxEntries || cachedBytes > config.columnCacheMaxBytes)
                && !entries.isEmpty()) {
            Map.Entry<Key, Entry> eldest = entries.entrySet().iterator().next();
            cachedBytes -= eldest.getValue().sizeBytes();
            entries.remove(eldest.getKey());
            evictions++;
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
            boolean completeColumn) {
        public EncodedColumnData columnData() {
            return new EncodedColumnData(
                    chunkX,
                    chunkZ,
                    compression,
                    rawSize,
                    encodedBytes,
                    timestamp,
                    schemaVersion,
                    completeColumn);
        }
    }
}
