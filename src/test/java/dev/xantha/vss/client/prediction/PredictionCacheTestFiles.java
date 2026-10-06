package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.file.*;

/** Fixture mutations bypass the normal encoder to exercise persisted invalid data. */
final class PredictionCacheTestFiles {
    static final class ResourceIdentity implements AutoCloseable {
        private final java.lang.reflect.Field current;
        private final Object previous;
        ResourceIdentity() throws Exception {
            current = PredictionMeshResources.class.getDeclaredField("current");
            current.setAccessible(true);
            previous = current.get(null);
            current.set(null, java.util.concurrent.CompletableFuture.completedFuture(new byte[32]));
        }
        @Override public void close() throws Exception { current.set(null, previous); }
    }

    /** Seed a validated complete mesh; the cache worker intentionally does not rebuild terrain-only records. */
    static void finishedTerrain(PredictionDiskCache cache, PredictionDiskCache.Key key,
            ClientColumnSample[] samples, ClientTerrainSampler sampler) throws Exception {
        int grid = (int) Math.sqrt(samples.length), axis = grid - 2;
        int step = (VssLodLayout.BASE_TILE_BLOCKS << key.detail()) / axis;
        int[] surface = new int[samples.length], foliage = new int[samples.length], water = new int[samples.length];
        java.util.Arrays.fill(surface, 0xff557733);
        java.util.Arrays.fill(foliage, 0xff447722);
        java.util.Arrays.fill(water, sampler.fluidColor());
        var data = new PredictionDiskCache.TerrainData(samples, sampler.colorCacheFingerprint(), surface, foliage, water);
        int span = VssLodLayout.BASE_TILE_BLOCKS << key.detail();
        var mesh = PredictionMeshBuilder.buildForRendering(samples, surface, sampler.seaLevel(), sampler.fluidColor(),
                step, grid, foliage, water, key.x() * span, key.z() * span, PredictionVegetation.Tile.EMPTY,
                PredictionSimpleVegetation.Result.EMPTY, null, () -> true).compactForRendering();
        if (mesh.cellAxis() != axis) throw new AssertionError("fixture axis does not match the terrain record");
        var tileKey = new PredictionTileManager.PredictionTileKey(sampler.profile().levelKey(), key.x(), key.z(), key.detail());
        int[] heights = new int[(axis + 1) * (axis + 1)];
        java.util.Arrays.fill(heights, samples[0].surfaceY());
        var tile = new PredictionTileManager.PredictionTile(tileKey, heights, heights, samples, mesh,
                PredictionDepthBound.fromSamples(samples), 0, 1, axis, step);
        mesh.prepareGpuPayload(tile);
        var settings = PredictionTileManager.class.getDeclaredMethod("surfaceSettings");
        settings.setAccessible(true);
        byte[] base = PredictionMeshCodec.baseSignature(PredictionMeshResources.ready(), samples, surface, foliage, water,
                sampler.seaLevel(), sampler.fluidColor(), step, dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionTrees,
                (int) settings.invoke(null));
        try (var lease = cache.lease(key)) {
            if (!cache.writeTerrain(lease, data)) throw new AssertionError("terrain fixture was not saved");
            cache.writeMeshLater(lease, base, mesh, base, true, false);
        }
        cache.flushMeshes(); cache.flush();
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
