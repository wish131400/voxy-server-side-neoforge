package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.file.*;

/** Fixture mutations bypass the normal encoder to exercise persisted invalid data. */
final class PredictionCacheTestFiles {
    static final class ResourceIdentity implements AutoCloseable {
        @Override public void close() { }
    }
    static void finishedTerrain(PredictionDiskCache cache, PredictionDiskCache.Key key,
            ClientColumnSample[] samples, ClientTerrainSampler sampler) throws Exception {
        try (var lease = cache.lease(key)) {
            if (!cache.writeTerrain(lease,samples)) throw new AssertionError("terrain fixture was not saved");
        }
        cache.flush();
    }

    static void awaitBackgroundClose() throws Exception {
        awaitQueue(PredictionResources.class, "DISPOSER");
        awaitQueue(PredictionDiskCache.class, "COMMITS");
    }

    private static void awaitQueue(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(null)).submit(() -> { })
                .get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    static PredictionRegionStorage storage(PredictionDiskCache cache) throws Exception {
        var sharedField = PredictionDiskCache.class.getDeclaredField("shared");
        sharedField.setAccessible(true);
        Object shared = sharedField.get(cache);
        var field = shared.getClass().getDeclaredField("regions");
        field.setAccessible(true);
        return (PredictionRegionStorage) field.get(shared);
    }
    static byte[] read(PredictionDiskCache cache, PredictionDiskCache.Key key) throws Exception {
        return storage(cache).read(key).bytes();
    }
    static void write(PredictionDiskCache cache, PredictionDiskCache.Key key, byte[] bytes) throws Exception {
        Path temp = Files.createTempFile(cache.root(), "test-record-", ".tmp");
        try { Files.write(temp, bytes); storage(cache).write(key, temp); }
        finally { Files.deleteIfExists(temp); }
    }
    static void legacy(PredictionDiskCache cache, PredictionDiskCache.Key key, byte[] bytes) throws Exception {
        cache.flush();
        var storage = storage(cache);
        storage.close();
        Files.deleteIfExists(storage.path(key)); // These fixtures have one record per region.
        Files.createDirectories(cache.file(key).getParent());
        Files.write(cache.file(key), bytes);
    }
    static void corruptPayload(PredictionDiskCache cache, PredictionDiskCache.Key key) throws Exception {
        Path path = storage(cache).path(key);
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            int slot = (key.x() & 31) + ((key.z() & 31) << 5);
            file.seek(PredictionRegionStorage.HEADER_BYTES + (long) slot * PredictionRegionStorage.ENTRY_BYTES + 8);
            long offset = file.readLong();
            file.seek(offset); file.write(new byte[]{0, 1, 2});
        }
    }
}
