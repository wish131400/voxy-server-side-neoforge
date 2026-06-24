package dev.xantha.vss.networking.server;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.common.processing.LoadedColumnData;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

final class ColumnLodCache {
    private final VSSServerConfig config;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(1024, 0.75F, true);
    private long cachedBytes;
    private long hits;
    private long misses;
    private long puts;
    private long evictions;
    private long invalidations;

    ColumnLodCache(VSSServerConfig config) {
        this.config = config;
    }

    synchronized Entry get(ResourceKey<Level> dimension, int cx, int cz) {
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

    synchronized void put(ResourceKey<Level> dimension, LoadedColumnData columnData, long timestamp) {
        if (!config.enableColumnCache || columnData == null || columnData.sectionBytes() == null || !columnData.completeColumn()) {
            return;
        }

        int sizeBytes = Math.max(columnData.sizeBytes(), columnData.sectionBytes().length);
        if (sizeBytes <= 0 || sizeBytes > config.columnCacheMaxBytes) {
            return;
        }

        Key key = new Key(dimension.location(), columnData.chunkX(), columnData.chunkZ());
        Entry previous = entries.remove(key);
        if (previous != null) {
            cachedBytes -= previous.sizeBytes();
        }

        byte[] cachedSections = Arrays.copyOf(columnData.sectionBytes(), columnData.sectionBytes().length);
        entries.put(key, new Entry(
                columnData.chunkX(),
                columnData.chunkZ(),
                timestamp,
                cachedSections,
                sizeBytes,
                columnData.completeColumn()));
        cachedBytes += sizeBytes;
        puts++;
        evictOverflow();
    }

    synchronized void invalidate(ResourceKey<Level> dimension, int cx, int cz) {
        Entry removed = entries.remove(new Key(dimension.location(), cx, cz));
        if (removed != null) {
            cachedBytes -= removed.sizeBytes();
            invalidations++;
        }
    }

    synchronized void clear() {
        entries.clear();
        cachedBytes = 0L;
    }

    synchronized String diagnostics() {
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

    record Entry(int chunkX, int chunkZ, long timestamp, byte[] sectionBytes, int sizeBytes, boolean completeColumn) {
    }
}
