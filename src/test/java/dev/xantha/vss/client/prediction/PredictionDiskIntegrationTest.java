package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionDiskIntegrationTest {
    @TempDir Path directory;
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @org.junit.jupiter.api.AfterEach void finishBackgroundCloseBeforeTempCleanup() throws Exception {
        var field = PredictionResources.class.getDeclaredField("DISPOSER");
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(null)).submit(() -> { }).get(15, TimeUnit.SECONDS);
    }

    @Test void reopenedManagerUsesStoredGroundWithoutTerrainSamplingAndDirtyRebuildsIt() throws Exception {
        var config = VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks;
        boolean trees = config.predictionTrees, structures = config.predictionStructures;
        config.predictionDistanceBlocks = 1024; config.predictionTrees = false; config.predictionStructures = false;
        AtomicInteger calls = new AtomicInteger();
        int ready;
        try {
            try (var cache = new PredictionDiskCache(directory, PROFILE.fingerprint());
                 var manager = manager(calls, cache)) {
                replan(manager); await(manager);
                ready = manager.readyCount();
                assertTrue(ready > 0);
                assertTrue(calls.get() > 0, "cold fixture must calculate terrain");
                assertEquals(0, manager.failedTileCount());
            }
            int firstCalls = calls.getAndSet(0);
            try (var cache = new PredictionDiskCache(directory, PROFILE.fingerprint());
                 var manager = manager(calls, cache)) {
                replan(manager); await(manager);
                assertEquals(ready, manager.readyCount());
                assertEquals(0, calls.get(), "warm manager must not call Java/native terrain generation");
                assertTrue(cache.hits() >= ready);
                var tile = manager.readyTiles().iterator().next();
                manager.invalidate(tile.baseBlockX() >> 4, tile.baseBlockZ() >> 4);
                cache.flush();
                replan(manager); await(manager);
                assertTrue(calls.get() > 0, "dirty chunk must remove stale fine columns as well as tile files");
                assertEquals(0, manager.failedTileCount());
            }
            System.out.println("Manager restart terrain calls: cold=" + firstCalls + ", warm=0; restoredTiles=" + ready);
        } finally {
            config.predictionDistanceBlocks = distance; config.predictionTrees = trees; config.predictionStructures = structures;
        }
    }

    @Test void identicalAuthoritativeColumnsAfterReconnectKeepStoredTiles() throws Exception {
        var empty=new dev.xantha.vss.api.VoxelColumnData(new dev.xantha.vss.api.VoxelColumnData.SectionData[0],1L,true);
        var key=PredictionDiskCache.Key.terrain(0,0,0);
        var sample=sampler(new AtomicInteger(),PROFILE).sample(0,0);
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(new AtomicInteger(),cache)) {
            manager.captureExactColumn(0,0,empty);awaitCaptures(manager);cache.flush();
            assertEquals(256,manager.persistentSampleCount());
            try(var lease=cache.lease(key)){assertTrue(cache.writeTerrain(lease,new ClientColumnSample[]{sample}));}
        }
        finishBackgroundCloseBeforeTempCleanup();
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(new AtomicInteger(),cache)) {
            assertEquals(256,manager.persistentSampleCount());
            manager.captureExactColumn(0,0,empty);awaitCaptures(manager);cache.flush();
            try(var lease=cache.lease(key)){assertNotNull(cache.readTerrain(lease,1),"unchanged reconnect traffic must not delete persisted terrain");}
            manager.invalidate(0,0);cache.flush();
            try(var lease=cache.lease(key)){assertNull(cache.readTerrain(lease,1),"explicit dirty updates must still invalidate stored terrain");}
        }
    }

    private static void awaitCaptures(PredictionTileManager manager) throws Exception {
        var field=PredictionTileManager.class.getDeclaredField("pendingCaptures");field.setAccessible(true);
        var pending=(Set<?>)field.get(manager);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!pending.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(5);
        assertTrue(pending.isEmpty());assertEquals(0,manager.failedTileCount());
    }

    @Test void cacheIdentitySeparatesWorldSeedGeneratorBytesAndBackend() {
        var sampler = sampler(new AtomicInteger(), PROFILE);
        var storage = PredictionCacheStorage.forWorld(directory, directory.resolve("saves/world-a"), null, null);
        Path a = storage.directory(sampler);
        assertNotEquals(a, PredictionCacheStorage.forWorld(directory, directory.resolve("saves/world-b"), null, null).directory(sampler));
        var otherSeed = new DimensionProfile(PROFILE.dimension(), 43L, -64, 384, "noise", "minecraft:overworld", 123L);
        assertNotEquals(a, storage.directory(sampler(new AtomicInteger(), otherSeed)));
        byte[] generator = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var otherGenerator = new DimensionProfile(PROFILE.dimension(), 42L, -64, 384, "noise", "minecraft:overworld", 123L, 0, generator.length, generator);
        assertNotEquals(a, storage.directory(sampler(new AtomicInteger(), otherGenerator)));
        assertNotEquals(a, storage.directory(ClientTerrainSampler.custom(42L, PROFILE, (x, z) -> 64)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void dirtyDuringCaptureRejectsOldSampleAndRetainsLatestReplacement(boolean replace) throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var base = sampler(new AtomicInteger(), PROFILE);
        var blocking = new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
                return base.sample(x, z);
            }
        };
        try (var cache = new PredictionDiskCache(directory, PROFILE.fingerprint());
             var manager = new PredictionTileManager(PROFILE.levelKey(), blocking,
                     new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), cache)) {
            var empty = new dev.xantha.vss.api.VoxelColumnData(new dev.xantha.vss.api.VoxelColumnData.SectionData[0], 1L, true);
            manager.captureExactColumn(-3, 4, empty);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            manager.invalidate(-3, 4);
            if (replace) manager.captureExactColumn(-3, 4, empty);
            release.countDown();
            var captures = PredictionTileManager.class.getDeclaredField("pendingCaptures");
            captures.setAccessible(true);
            var pending = (Set<?>) captures.get(manager);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!pending.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(pending.isEmpty());
            assertEquals(replace ? 256 : 0, manager.persistentSampleCount(), "old in-flight captures must not undo dirty invalidation");
            manager.invalidate(-3, 4);
            assertEquals(0, manager.persistentSampleCount(), "dirty must remove all 256 captured columns, including corners");
        } finally { release.countDown(); }
    }

    @Test void coldStoredDetailReleasesOnlyWithReadyAncestorAndAfterGrace() {
        var layout = VssLodLayout.of(1024, 2, true, true);
        var key = new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), -2, 2, 0);
        var parent = new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), -1, 1, 1);
        long cold = TimeUnit.SECONDS.toNanos(31);
        assertFalse(PredictionTileManager.canRetireStored(key, cold, false, true, Set.of(parent), PROFILE.levelKey(), layout),
                "Looking away must not discard loaded detail inside the prediction horizon");
        assertFalse(PredictionTileManager.canRetireStored(key, cold, true, true, Set.of(parent), PROFILE.levelKey(), layout));
        assertFalse(PredictionTileManager.canRetireStored(key, cold, false, false, Set.of(parent), PROFILE.levelKey(), layout));
        assertFalse(PredictionTileManager.canRetireStored(key, cold, false, true, Set.of(), PROFILE.levelKey(), layout));
        assertFalse(PredictionTileManager.canRetireStored(key, TimeUnit.SECONDS.toNanos(29), false, true, Set.of(parent), PROFILE.levelKey(), layout));
        var root = new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), -100, 100, layout.levelCount() - 1);
        assertTrue(PredictionTileManager.canRetireStored(root, cold, false, true, Set.of(), PROFILE.levelKey(), layout, true),
                "stored roots beyond the horizon cannot accumulate forever merely because they have no parent");
    }

    private static PredictionTileManager manager(AtomicInteger calls, PredictionDiskCache disk) {
        return new PredictionTileManager(PROFILE.levelKey(), sampler(calls, PROFILE),
                new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), disk);
    }
    private static ClientTerrainSampler sampler(AtomicInteger calls, DimensionProfile profile) {
        return new ClientTerrainSampler(profile.seed(), profile) {
            @Override public ClientColumnSample sample(int x, int z) {
                calls.incrementAndGet();
                return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(), 0, 0, 0, 0, 0,
                        ClientColumnSample.FLAG_SURFACE_ONLY, 0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        };
    }
    private static void replan(PredictionTileManager manager) { for (int i = 0; i < 5; i++) manager.tick(2261, 123, 3901, .01, null); }
    private static void await(PredictionTileManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (manager.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, manager.pendingCount(), "IO/build stage must finish");
    }
}
