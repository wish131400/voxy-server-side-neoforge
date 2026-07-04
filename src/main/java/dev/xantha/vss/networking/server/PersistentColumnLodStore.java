package dev.xantha.vss.networking.server;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.config.VSSServerConfig;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

final class PersistentColumnLodStore {
    private static final int FILE_MAGIC = 0x5653534C;
    private static final int FILE_VERSION_CURRENT = 6;
    private static final int INDEX_MAGIC = 0x56535349;
    private static final int INDEX_VERSION_CURRENT = 1;
    private static final int REGION_SIZE = 32;
    private static final int REGION_SLOT_COUNT = REGION_SIZE * REGION_SIZE;
    private static final int REGION_BITMAP_LONGS = REGION_SLOT_COUNT / Long.SIZE;
    private static final int MAX_COLUMN_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENCODED_COLUMN_BYTES = MAX_COLUMN_BYTES + 65536;
    private static final int MIN_REGION_INDEX_CACHE_ENTRIES = 512;
    private static final int MAX_REGION_INDEX_CACHE_ENTRIES = 8192;
    private static final String CACHE_DIR = "vss-column-cache";
    private static final String COLUMN_EXTENSION = ".vcl";
    private static final String INDEX_FILE_NAME = "index.vci";

    private final VSSServerConfig config;
    private final Map<RegionKey, RegionIndex> regionIndexes = new LinkedHashMap<>(128, 0.75F, true);
    private long reads;
    private long hits;
    private long misses;
    private long writes;
    private long writeFailures;
    private long invalidations;
    private long cleanupRuns;
    private long cleanupDeleted;
    private long indexScans;
    private long indexMissSkips;
    private long indexEvictions;
    private long nextCleanupMillis;
    private long knownCacheBytes = -1L;
    private int knownCacheEntries = -1;
    private volatile boolean indexedZstdAvailable = LodByteCompression.isZstdAvailable();

    PersistentColumnLodStore(VSSServerConfig config) {
        this.config = config;
    }

    boolean enabled() {
        return config.enablePersistentColumnCache;
    }

    static int regionSize() {
        return REGION_SIZE;
    }

    void clearMemory() {
        clearRegionIndexes();
        nextCleanupMillis = 0L;
        knownCacheBytes = -1L;
        knownCacheEntries = -1;
        indexedZstdAvailable = LodByteCompression.isZstdAvailable();
    }

    Entry read(MinecraftServer server, ResourceKey<Level> dimension, int cx, int cz, long minimumTimestamp) {
        if (!config.enablePersistentColumnCache) {
            return null;
        }

        reads++;
        refreshCompressionState();
        RegionIndex index = regionIndex(server, dimension, cx, cz);
        IndexSlot indexedSlot = index.slot(cx, cz);
        if (!isReadableSlot(indexedSlot, minimumTimestamp)) {
            misses++;
            indexMissSkips++;
            if (indexedSlot != null && indexedSlot.timestamp() < minimumTimestamp) {
                deleteQuietly(columnPath(server, dimension, cx, cz));
                markIndexed(server, dimension, cx, cz, null);
            }
            return null;
        }

        Path path = columnPath(server, dimension, cx, cz);
        if (!Files.isRegularFile(path)) {
            misses++;
            markIndexed(server, dimension, cx, cz, null);
            return null;
        }

        try (InputStream fileIn = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(fileIn)) {
            IndexSlot header = readColumnHeader(in, cx, cz);
            if (header == null) {
                misses++;
                deleteQuietly(path);
                markIndexed(server, dimension, cx, cz, null);
                return null;
            }
            if (!isReadableSlot(header, minimumTimestamp)) {
                misses++;
                if (header.timestamp() < minimumTimestamp) {
                    deleteQuietly(path);
                    markIndexed(server, dimension, cx, cz, null);
                } else {
                    markIndexed(server, dimension, cx, cz, header);
                }
                return null;
            }

            byte[] encodedBytes = in.readNBytes(header.length());
            if (encodedBytes.length != header.length()) {
                misses++;
                deleteQuietly(path);
                markIndexed(server, dimension, cx, cz, null);
                return null;
            }

            hits++;
            markIndexed(server, dimension, cx, cz, header);
            return new Entry(new EncodedColumnData(
                    cx,
                    cz,
                    header.method(),
                    header.rawSize(),
                    encodedBytes,
                    header.timestamp(),
                    header.schemaVersion(),
                    true));
        } catch (Exception e) {
            misses++;
            deleteQuietly(path);
            markIndexed(server, dimension, cx, cz, null);
            VSSLogger.debug("Failed to read persistent LOD column " + cx + "," + cz + ": " + e.getMessage());
            return null;
        }
    }

    ArrayList<ExistingColumn> findExistingColumnsInRegion(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            int regionX,
            int regionZ,
            int centerCx,
            int centerCz,
            int minDistance,
            int maxDistance,
            int limit) {
        ArrayList<ExistingColumn> columns = new ArrayList<>(Math.min(Math.max(0, limit), REGION_SLOT_COUNT));
        if (!config.enablePersistentColumnCache || limit <= 0 || maxDistance < minDistance) {
            return columns;
        }

        refreshCompressionState();
        RegionKey key = new RegionKey(safeDimension(dimension.location()), regionX, regionZ);
        RegionIndex index = regionIndex(server, key);
        index.collectReadable(columns, regionX, regionZ, centerCx, centerCz, minDistance, maxDistance);
        columns.sort(Comparator
                .comparingInt(ExistingColumn::distance)
                .thenComparingLong(ExistingColumn::distanceSquared)
                .thenComparingInt(ExistingColumn::chunkX)
                .thenComparingInt(ExistingColumn::chunkZ));
        if (columns.size() > limit) {
            columns.subList(limit, columns.size()).clear();
        }
        return columns;
    }

    void write(MinecraftServer server, ResourceKey<Level> dimension, EncodedColumnData columnData) {
        if (!config.enablePersistentColumnCache
                || columnData == null
                || columnData.encodedBytes() == null
                || !columnData.completeColumn()
                || !isPersistentCompression(columnData.compression())
                || columnData.rawSize() <= 0
                || columnData.rawSize() > MAX_COLUMN_BYTES
                || columnData.encodedBytes().length <= 0
                || columnData.encodedBytes().length > MAX_ENCODED_COLUMN_BYTES
                || columnData.schemaVersion() != EncodedColumnData.SCHEMA_VERSION) {
            return;
        }

        Path path = columnPath(server, dimension, columnData.chunkX(), columnData.chunkZ());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        long previousSize = sizeIfRegular(path);
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream fileOut = Files.newOutputStream(tmp);
                DataOutputStream out = new DataOutputStream(fileOut)) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(FILE_VERSION_CURRENT);
                out.writeInt(columnData.chunkX());
                out.writeInt(columnData.chunkZ());
                out.writeLong(columnData.columnStamp());
                out.writeBoolean(columnData.completeColumn());
                out.writeInt(columnData.compression());
                out.writeInt(columnData.rawSize());
                out.writeInt(columnData.schemaVersion());
                out.writeInt(columnData.encodedBytes().length);
                out.write(columnData.encodedBytes());
            }
            moveIntoPlace(tmp, path);
            recordColumnWrite(previousSize, sizeIfRegular(path));
            markIndexed(server, dimension, columnData.chunkX(), columnData.chunkZ(), IndexSlot.from(columnData));
            writes++;
            cleanupIfNeeded(server);
        } catch (Exception e) {
            writeFailures++;
            deleteQuietly(tmp);
            VSSLogger.debug("Failed to write persistent LOD column " + columnData.chunkX() + ","
                    + columnData.chunkZ() + ": " + e.getMessage());
        }
    }

    void invalidate(MinecraftServer server, ResourceKey<Level> dimension, int cx, int cz) {
        if (!config.enablePersistentColumnCache) {
            return;
        }

        Path path = columnPath(server, dimension, cx, cz);
        long previousSize = sizeIfRegular(path);
        if (deleteQuietly(path)) {
            recordColumnDelete(previousSize);
            markIndexed(server, dimension, cx, cz, null);
            invalidations++;
        }
    }

    String diagnostics() {
        return String.format(
                Locale.ROOT,
                "persistent={enabled=%s, reads=%d, hits=%d, misses=%d, writes=%d, writeFailures=%d, invalidations=%d, cleanupRuns=%d, cleanupDeleted=%d, indexRegions=%d/%d, indexScans=%d, indexMissSkips=%d, indexEvictions=%d}",
                config.enablePersistentColumnCache,
                reads,
                hits,
                misses,
                writes,
                writeFailures,
                invalidations,
                cleanupRuns,
                cleanupDeleted,
                regionIndexCount(),
                maxRegionIndexCacheEntries(),
                indexScans,
                indexMissSkips,
                indexEvictions);
    }

    private static boolean isReadableCompression(int method) {
        return method == LodByteCompression.METHOD_DEFLATE
                || method == LodByteCompression.METHOD_ZSTD && LodByteCompression.isZstdAvailable();
    }

    private static boolean isPersistentCompression(int method) {
        return method == LodByteCompression.METHOD_DEFLATE
                || method == LodByteCompression.METHOD_ZSTD;
    }

    private static boolean isPersistentSlot(IndexSlot slot) {
        return slot != null
                && isPersistentCompression(slot.method())
                && slot.rawSize() > 0
                && slot.rawSize() <= MAX_COLUMN_BYTES
                && slot.schemaVersion() == EncodedColumnData.SCHEMA_VERSION
                && slot.length() > 0
                && slot.length() <= MAX_ENCODED_COLUMN_BYTES;
    }

    private static boolean isReadableSlot(IndexSlot slot, long minimumTimestamp) {
        return isPersistentSlot(slot)
                && slot.timestamp() >= minimumTimestamp
                && isReadableCompression(slot.method());
    }

    private void cleanupIfNeeded(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now < nextCleanupMillis) {
            return;
        }
        nextCleanupMillis = now + 60_000L;
        if (isKnownCacheWithinLimits()) {
            return;
        }
        cleanup(server);
    }

    private void cleanup(MinecraftServer server) {
        Path root = root(server);
        if (!Files.isDirectory(root)) {
            return;
        }

        long maxBytes = (long) config.persistentColumnCacheMaxMiB * VSSServerConfig.BYTES_PER_MIB;
        int maxEntries = config.persistentColumnCacheMaxEntries;
        ArrayList<FileEntry> entries = new ArrayList<>();
        long totalBytes = 0L;
        try (Stream<Path> stream = Files.walk(root)) {
            var iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String fileName = path.getFileName().toString();
                if (!fileName.endsWith(COLUMN_EXTENSION)) {
                    if (!fileName.equals(INDEX_FILE_NAME) && !fileName.endsWith(".tmp")) {
                        deleteQuietly(path);
                    }
                    continue;
                }
                long size = Files.size(path);
                long lastAccess = lastAccessMillis(path);
                entries.add(new FileEntry(path, size, lastAccess));
                totalBytes += size;
            }
        } catch (IOException e) {
            invalidateCacheLedger();
            VSSLogger.debug("Failed to scan persistent LOD cache: " + e.getMessage());
            return;
        }
        setCacheLedger(totalBytes, entries.size());

        if (totalBytes <= maxBytes && entries.size() <= maxEntries) {
            return;
        }

        cleanupRuns++;
        entries.sort(Comparator.comparingLong(FileEntry::lastAccessMillis));
        long bytes = totalBytes;
        int count = entries.size();
        for (FileEntry entry : entries) {
            if (bytes <= maxBytes && count <= maxEntries) {
                break;
            }
            if (deleteQuietly(entry.path())) {
                bytes -= entry.sizeBytes();
                count--;
                cleanupDeleted++;
                removeIndexedPath(server, entry.path());
            }
        }
        setCacheLedger(bytes, count);
    }

    private synchronized boolean isKnownCacheWithinLimits() {
        if (knownCacheBytes < 0L || knownCacheEntries < 0) {
            return false;
        }
        long maxBytes = (long) config.persistentColumnCacheMaxMiB * VSSServerConfig.BYTES_PER_MIB;
        return knownCacheBytes <= maxBytes && knownCacheEntries <= config.persistentColumnCacheMaxEntries;
    }

    private synchronized void setCacheLedger(long bytes, int entries) {
        knownCacheBytes = Math.max(0L, bytes);
        knownCacheEntries = Math.max(0, entries);
    }

    private synchronized void recordColumnWrite(long previousSize, long newSize) {
        if (previousSize < 0L || newSize < 0L || knownCacheBytes < 0L || knownCacheEntries < 0) {
            invalidateCacheLedger();
            return;
        }
        knownCacheBytes = Math.max(0L, knownCacheBytes - previousSize + newSize);
        if (previousSize == 0L && newSize > 0L) {
            knownCacheEntries++;
        }
    }

    private synchronized void recordColumnDelete(long previousSize) {
        if (previousSize < 0L || knownCacheBytes < 0L || knownCacheEntries < 0) {
            invalidateCacheLedger();
            return;
        }
        if (previousSize > 0L) {
            knownCacheBytes = Math.max(0L, knownCacheBytes - previousSize);
            knownCacheEntries = Math.max(0, knownCacheEntries - 1);
        }
    }

    private synchronized void invalidateCacheLedger() {
        knownCacheBytes = -1L;
        knownCacheEntries = -1;
    }

    private void refreshCompressionState() {
        boolean zstdAvailable = LodByteCompression.isZstdAvailable();
        if (indexedZstdAvailable != zstdAvailable) {
            synchronized (this) {
                if (indexedZstdAvailable != zstdAvailable) {
                    regionIndexes.clear();
                    indexedZstdAvailable = zstdAvailable;
                }
            }
        }
    }

    private void markIndexed(MinecraftServer server, ResourceKey<Level> dimension, int cx, int cz, IndexSlot slot) {
        RegionKey key = RegionKey.of(dimension.location(), cx, cz);
        RegionIndex index = regionIndex(server, key);
        boolean changed = slot == null ? index.remove(cx, cz) : index.put(cx, cz, slot);
        if (changed) {
            saveIndex(server, key, index);
        }
    }

    private RegionIndex regionIndex(MinecraftServer server, ResourceKey<Level> dimension, int cx, int cz) {
        return regionIndex(server, RegionKey.of(dimension.location(), cx, cz));
    }

    private RegionIndex regionIndex(MinecraftServer server, RegionKey key) {
        refreshCompressionState();
        RegionIndex cached = cachedRegionIndex(key);
        if (cached != null) {
            return cached;
        }

        RegionIndex loaded = loadOrScanRegion(server, key);
        return cacheRegionIndex(key, loaded);
    }

    private synchronized RegionIndex cachedRegionIndex(RegionKey key) {
        return regionIndexes.get(key);
    }

    private synchronized RegionIndex cacheRegionIndex(RegionKey key, RegionIndex index) {
        RegionIndex cached = regionIndexes.get(key);
        if (cached != null) {
            return cached;
        }
        regionIndexes.put(key, index);
        int maxEntries = maxRegionIndexCacheEntries();
        while (regionIndexes.size() > maxEntries) {
            var iterator = regionIndexes.entrySet().iterator();
            if (!iterator.hasNext()) {
                return index;
            }
            iterator.next();
            iterator.remove();
            indexEvictions++;
        }
        return index;
    }

    private int maxRegionIndexCacheEntries() {
        int regionRing = Math.max(0, (config.lodDistanceChunks + REGION_SIZE - 1) / REGION_SIZE);
        long windowDiameter = (long) regionRing * 2L + 1L;
        long activeWindow = windowDiameter * windowDiameter;
        long target = activeWindow * 3L / 2L + 64L;
        return (int) Math.max(
                MIN_REGION_INDEX_CACHE_ENTRIES,
                Math.min(MAX_REGION_INDEX_CACHE_ENTRIES, target));
    }

    private synchronized void clearRegionIndexes() {
        regionIndexes.clear();
    }

    private synchronized int regionIndexCount() {
        return regionIndexes.size();
    }

    private RegionIndex loadOrScanRegion(MinecraftServer server, RegionKey key) {
        RegionIndex loaded = loadIndex(server, key);
        if (loaded != null) {
            return loaded;
        }

        indexScans++;
        RegionIndex scanned = scanRegion(server, key);
        if (Files.isDirectory(regionDir(server, key))) {
            saveIndex(server, key, scanned);
        }
        return scanned;
    }

    private RegionIndex loadIndex(MinecraftServer server, RegionKey key) {
        Path path = regionIndexPath(server, key);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        if (isIndexOlderThanRegionDirectory(path, regionDir(server, key))) {
            deleteQuietly(path);
            return null;
        }

        try (InputStream fileIn = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(fileIn)) {
            if (in.readInt() != INDEX_MAGIC
                    || in.readInt() != INDEX_VERSION_CURRENT
                    || in.readInt() != FILE_VERSION_CURRENT
                    || in.readInt() != EncodedColumnData.SCHEMA_VERSION
                    || in.readInt() != key.regionX()
                    || in.readInt() != key.regionZ()) {
                deleteQuietly(path);
                return null;
            }

            long[] bitmap = new long[REGION_BITMAP_LONGS];
            for (int i = 0; i < REGION_BITMAP_LONGS; i++) {
                bitmap[i] = in.readLong();
            }

            RegionIndex index = new RegionIndex();
            for (int localIndex = 0; localIndex < REGION_SLOT_COUNT; localIndex++) {
                if (!RegionIndex.hasBit(bitmap, localIndex)) {
                    continue;
                }
                IndexSlot slot = new IndexSlot(
                        in.readLong(),
                        in.readInt(),
                        in.readInt(),
                        in.readInt(),
                        in.readInt());
                if (!isPersistentSlot(slot)) {
                    deleteQuietly(path);
                    return null;
                }
                index.putLocal(localIndex, slot);
            }
            return index;
        } catch (Exception e) {
            deleteQuietly(path);
            return null;
        }
    }

    private void saveIndex(MinecraftServer server, RegionKey key, RegionIndex index) {
        Path path = regionIndexPath(server, key);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream fileOut = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(fileOut)) {
                out.writeInt(INDEX_MAGIC);
                out.writeInt(INDEX_VERSION_CURRENT);
                out.writeInt(FILE_VERSION_CURRENT);
                out.writeInt(EncodedColumnData.SCHEMA_VERSION);
                out.writeInt(key.regionX());
                out.writeInt(key.regionZ());
                index.writeTo(out);
            }
            moveIntoPlace(tmp, path);
            markIndexFresh(path);
        } catch (IOException e) {
            deleteQuietly(tmp);
            VSSLogger.debug("Failed to write persistent LOD region index " + key.regionX() + ","
                    + key.regionZ() + ": " + e.getMessage());
        }
    }

    private RegionIndex scanRegion(MinecraftServer server, RegionKey key) {
        RegionIndex index = new RegionIndex();
        Path regionDir = regionDir(server, key);
        if (!Files.isDirectory(regionDir)) {
            return index;
        }

        try (Stream<Path> stream = Files.list(regionDir)) {
            var iterator = stream.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String fileName = path.getFileName().toString();
                if (!fileName.endsWith(COLUMN_EXTENSION)) {
                    if (!fileName.equals(INDEX_FILE_NAME) && !fileName.endsWith(".tmp")) {
                        deleteQuietly(path);
                    }
                    continue;
                }

                PositionKey position = parsePositionKey(fileName);
                if (position == null) {
                    continue;
                }
                IndexSlot slot = readColumnHeader(path, position.chunkX(), position.chunkZ());
                if (isPersistentSlot(slot)) {
                    index.put(position.chunkX(), position.chunkZ(), slot);
                }
            }
        } catch (IOException e) {
            VSSLogger.debug("Failed to index persistent LOD region " + key.regionX() + "," + key.regionZ()
                    + ": " + e.getMessage());
        }
        return index;
    }

    private void removeIndexedPath(MinecraftServer server, Path path) {
        Path root = root(server);
        Path relative;
        try {
            relative = root.relativize(path);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (relative.getNameCount() < 3) {
            return;
        }

        String dimensionName = relative.getName(0).toString();
        String regionName = relative.getName(1).toString();
        String fileName = relative.getName(2).toString();
        int separator = regionName.lastIndexOf('_');
        if (separator <= 0) {
            return;
        }
        try {
            int regionX = Integer.parseInt(regionName.substring(0, separator));
            int regionZ = Integer.parseInt(regionName.substring(separator + 1));
            PositionKey position = parsePositionKey(fileName);
            if (position == null) {
                return;
            }
            RegionKey key = new RegionKey(dimensionName, regionX, regionZ);
            RegionIndex index = regionIndex(server, key);
            if (index.remove(position.chunkX(), position.chunkZ())) {
                saveIndex(server, key, index);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private IndexSlot readColumnHeader(Path path, int cx, int cz) {
        try (InputStream fileIn = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(fileIn)) {
            return readColumnHeader(in, cx, cz);
        } catch (Exception e) {
            return null;
        }
    }

    private IndexSlot readColumnHeader(DataInputStream in, int cx, int cz) throws IOException {
        if (in.readInt() != FILE_MAGIC || in.readInt() != FILE_VERSION_CURRENT) {
            return null;
        }

        int storedCx = in.readInt();
        int storedCz = in.readInt();
        long timestamp = in.readLong();
        boolean completeColumn = in.readBoolean();
        int method = in.readInt();
        int rawSize = in.readInt();
        int schemaVersion = in.readInt();
        int length = in.readInt();
        if (storedCx != cx || storedCz != cz || !completeColumn) {
            return null;
        }
        IndexSlot slot = new IndexSlot(timestamp, method, rawSize, schemaVersion, length);
        return isPersistentSlot(slot) ? slot : null;
    }

    private static PositionKey parsePositionKey(String fileName) {
        if (!fileName.endsWith(COLUMN_EXTENSION)) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - COLUMN_EXTENSION.length());
        int separator = stem.lastIndexOf('_');
        if (separator <= 0) {
            return null;
        }
        try {
            int cx = Integer.parseInt(stem.substring(0, separator));
            int cz = Integer.parseInt(stem.substring(separator + 1));
            return PositionKey.of(cx, cz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Path columnPath(MinecraftServer server, ResourceKey<Level> dimension, int cx, int cz) {
        RegionKey key = RegionKey.of(dimension.location(), cx, cz);
        return regionDir(server, key).resolve(cx + "_" + cz + COLUMN_EXTENSION);
    }

    private Path regionDir(MinecraftServer server, RegionKey key) {
        return root(server)
                .resolve(key.dimension())
                .resolve(key.regionX() + "_" + key.regionZ());
    }

    private Path regionIndexPath(MinecraftServer server, RegionKey key) {
        return regionDir(server, key).resolve(INDEX_FILE_NAME);
    }

    private Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(CACHE_DIR);
    }

    private static String safeDimension(ResourceLocation location) {
        return location.getNamespace() + "_" + location.getPath().replace('/', '_').replace('\\', '_').replace(':', '_');
    }

    private static long lastAccessMillis(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis();
    }

    private static long sizeIfRegular(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return -1L;
        }
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void moveIntoPlace(Path tmp, Path path) throws IOException {
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isIndexOlderThanRegionDirectory(Path indexPath, Path regionDir) {
        try {
            return Files.isDirectory(regionDir)
                    && Files.getLastModifiedTime(regionDir).compareTo(Files.getLastModifiedTime(indexPath)) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static void markIndexFresh(Path indexPath) throws IOException {
        Files.setLastModifiedTime(indexPath, FileTime.fromMillis(System.currentTimeMillis()));
    }

    private record FileEntry(Path path, long sizeBytes, long lastAccessMillis) {
    }

    private record RegionKey(String dimension, int regionX, int regionZ) {
        static RegionKey of(ResourceLocation dimension, int cx, int cz) {
            return new RegionKey(safeDimension(dimension), Math.floorDiv(cx, REGION_SIZE), Math.floorDiv(cz, REGION_SIZE));
        }
    }

    private record PositionKey(int chunkX, int chunkZ) {
        static PositionKey of(int cx, int cz) {
            return new PositionKey(cx, cz);
        }
    }

    private record IndexSlot(long timestamp, int method, int rawSize, int schemaVersion, int length) {
        static IndexSlot from(EncodedColumnData columnData) {
            return new IndexSlot(
                    columnData.columnStamp(),
                    columnData.compression(),
                    columnData.rawSize(),
                    columnData.schemaVersion(),
                    columnData.encodedBytes().length);
        }
    }

    record ExistingColumn(int chunkX, int chunkZ, long timestamp, int distance, long distanceSquared) {
    }

    private static final class RegionIndex {
        private final long[] presentBitmap = new long[REGION_BITMAP_LONGS];
        private final IndexSlot[] slots = new IndexSlot[REGION_SLOT_COUNT];

        synchronized IndexSlot slot(int cx, int cz) {
            int localIndex = localIndex(cx, cz);
            return hasBit(presentBitmap, localIndex) ? slots[localIndex] : null;
        }

        synchronized boolean put(int cx, int cz, IndexSlot slot) {
            return putLocal(localIndex(cx, cz), slot);
        }

        synchronized boolean putLocal(int localIndex, IndexSlot slot) {
            IndexSlot previous = hasBit(presentBitmap, localIndex) ? slots[localIndex] : null;
            if (slot.equals(previous)) {
                return false;
            }
            slots[localIndex] = slot;
            setBit(presentBitmap, localIndex);
            return true;
        }

        synchronized boolean remove(int cx, int cz) {
            int localIndex = localIndex(cx, cz);
            if (!hasBit(presentBitmap, localIndex)) {
                return false;
            }
            slots[localIndex] = null;
            clearBit(presentBitmap, localIndex);
            return true;
        }

        synchronized void writeTo(DataOutputStream out) throws IOException {
            for (long bits : presentBitmap) {
                out.writeLong(bits);
            }
            for (int i = 0; i < REGION_SLOT_COUNT; i++) {
                if (!hasBit(presentBitmap, i)) {
                    continue;
                }
                IndexSlot slot = slots[i];
                out.writeLong(slot.timestamp());
                out.writeInt(slot.method());
                out.writeInt(slot.rawSize());
                out.writeInt(slot.schemaVersion());
                out.writeInt(slot.length());
            }
        }

        synchronized void collectReadable(
                ArrayList<ExistingColumn> output,
                int regionX,
                int regionZ,
                int centerCx,
                int centerCz,
                int minDistance,
                int maxDistance) {
            int baseX = regionX * REGION_SIZE;
            int baseZ = regionZ * REGION_SIZE;
            for (int localIndex = 0; localIndex < REGION_SLOT_COUNT; localIndex++) {
                if (!hasBit(presentBitmap, localIndex)) {
                    continue;
                }
                IndexSlot slot = slots[localIndex];
                if (!isReadableSlot(slot, 0L)) {
                    continue;
                }
                int cx = baseX + (localIndex & (REGION_SIZE - 1));
                int cz = baseZ + (localIndex >>> 5);
                int dx = cx - centerCx;
                int dz = cz - centerCz;
                int distance = Math.max(Math.abs(dx), Math.abs(dz));
                if (distance < minDistance || distance > maxDistance) {
                    continue;
                }
                long distanceSquared = (long) dx * dx + (long) dz * dz;
                output.add(new ExistingColumn(cx, cz, slot.timestamp(), distance, distanceSquared));
            }
        }

        private static int localIndex(int cx, int cz) {
            return (cx & (REGION_SIZE - 1)) | ((cz & (REGION_SIZE - 1)) << 5);
        }

        private static boolean hasBit(long[] bitmap, int index) {
            return (bitmap[index >>> 6] & (1L << (index & 63))) != 0L;
        }

        private static void setBit(long[] bitmap, int index) {
            bitmap[index >>> 6] |= 1L << (index & 63);
        }

        private static void clearBit(long[] bitmap, int index) {
            bitmap[index >>> 6] &= ~(1L << (index & 63));
        }
    }

    record Entry(EncodedColumnData columnData) {
        long timestamp() {
            return columnData.columnStamp();
        }
    }
}
