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
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

final class PersistentColumnLodStore {
    private static final int FILE_MAGIC = 0x5653534C;
    private static final int FILE_VERSION_CURRENT = 6;
    private static final int MAX_COLUMN_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENCODED_COLUMN_BYTES = MAX_COLUMN_BYTES + 65536;
    private static final String CACHE_DIR = "vss-column-cache";

    private final VSSServerConfig config;
    private long reads;
    private long hits;
    private long misses;
    private long writes;
    private long writeFailures;
    private long invalidations;
    private long cleanupRuns;
    private long cleanupDeleted;
    private long nextCleanupMillis;

    PersistentColumnLodStore(VSSServerConfig config) {
        this.config = config;
    }

    boolean enabled() {
        return config.enablePersistentColumnCache;
    }

    Entry read(MinecraftServer server, ResourceKey<Level> dimension, int cx, int cz, long minimumTimestamp) {
        if (!config.enablePersistentColumnCache) {
            return null;
        }

        reads++;
        Path path = columnPath(server, dimension, cx, cz);
        if (!Files.isRegularFile(path)) {
            misses++;
            return null;
        }

        try (InputStream fileIn = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(fileIn)) {
            if (in.readInt() != FILE_MAGIC) {
                misses++;
                deleteQuietly(path);
                return null;
            }
            int version = in.readInt();
            if (version != FILE_VERSION_CURRENT) {
                misses++;
                deleteQuietly(path);
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
            if (storedCx != cx
                    || storedCz != cz
                    || !completeColumn
                    || timestamp < minimumTimestamp
                    || !isReadableCompression(method)
                    || rawSize <= 0
                    || rawSize > MAX_COLUMN_BYTES
                    || schemaVersion != EncodedColumnData.SCHEMA_VERSION
                    || length <= 0
                    || length > MAX_ENCODED_COLUMN_BYTES) {
                misses++;
                deleteQuietly(path);
                return null;
            }

            byte[] zstdFrame = in.readNBytes(length);
            if (zstdFrame.length != length) {
                misses++;
                deleteQuietly(path);
                return null;
            }

            hits++;
            touch(path);
            return new Entry(new EncodedColumnData(
                    cx,
                    cz,
                    method,
                    rawSize,
                    zstdFrame,
                    timestamp,
                    schemaVersion,
                    true));
        } catch (Exception e) {
            misses++;
            deleteQuietly(path);
            VSSLogger.debug("Failed to read persistent LOD column " + cx + "," + cz + ": " + e.getMessage());
            return null;
        }
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
        if (deleteQuietly(path)) {
            invalidations++;
        }
    }

    String diagnostics() {
        return String.format(
                Locale.ROOT,
                "persistent={enabled=%s, reads=%d, hits=%d, misses=%d, writes=%d, writeFailures=%d, invalidations=%d, cleanupRuns=%d, cleanupDeleted=%d}",
                config.enablePersistentColumnCache,
                reads,
                hits,
                misses,
                writes,
                writeFailures,
                invalidations,
                cleanupRuns,
                cleanupDeleted);
    }

    private static boolean isReadableCompression(int method) {
        return method == LodByteCompression.METHOD_DEFLATE
                || method == LodByteCompression.METHOD_ZSTD && LodByteCompression.isZstdAvailable();
    }

    private static boolean isPersistentCompression(int method) {
        return method == LodByteCompression.METHOD_DEFLATE
                || method == LodByteCompression.METHOD_ZSTD;
    }

    private void cleanupIfNeeded(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now < nextCleanupMillis) {
            return;
        }
        nextCleanupMillis = now + 60_000L;
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
                if (!path.getFileName().toString().endsWith(".vcl")) {
                    deleteQuietly(path);
                    continue;
                }
                long size = Files.size(path);
                long lastAccess = lastAccessMillis(path);
                entries.add(new FileEntry(path, size, lastAccess));
                totalBytes += size;
            }
        } catch (IOException e) {
            VSSLogger.debug("Failed to scan persistent LOD cache: " + e.getMessage());
            return;
        }

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
            }
        }
    }

    private Path columnPath(MinecraftServer server, ResourceKey<Level> dimension, int cx, int cz) {
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        return root(server)
                .resolve(safeDimension(dimension.location()))
                .resolve(rx + "_" + rz)
                .resolve(cx + "_" + cz + ".vcl");
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

    private static void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
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

    private record FileEntry(Path path, long sizeBytes, long lastAccessMillis) {
    }

    record Entry(EncodedColumnData columnData) {
        long timestamp() {
            return columnData.columnStamp();
        }
    }
}
