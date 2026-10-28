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
    private PredictionCacheTestFiles.ResourceIdentity resourceIdentity;
    @org.junit.jupiter.api.BeforeEach void resourceIdentity() throws Exception {
        resourceIdentity = new PredictionCacheTestFiles.ResourceIdentity();
    }
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @org.junit.jupiter.api.AfterEach void finishBackgroundCloseBeforeTempCleanup() throws Exception {
        PredictionCacheTestFiles.awaitBackgroundClose();
        resourceIdentity.close();
    }

    @Test void reopenedManagerUsesStoredGroundWithoutTerrainSamplingAndDirtyRebuildsIt() throws Exception {
        var config = VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks;
        config.predictionDistanceBlocks = 1024;
        AtomicInteger calls = new AtomicInteger();
        int ready;
        try {
            try (var cache = new PredictionDiskCache(directory, PROFILE.fingerprint());
                 var manager = manager(calls, cache)) {
                replan(manager); await(manager);
                ready = manager.readyCount();
                assertTrue(ready > 0,manager.surfaceDiagnostics());
                assertTrue(calls.get() > 0, "cold fixture must calculate terrain");
                assertEquals(0, manager.failedTileCount());
                cache.flush();
            }
            PredictionCacheTestFiles.awaitBackgroundClose();
            int firstCalls = calls.getAndSet(0);
            try (var cache = new PredictionDiskCache(directory, PROFILE.fingerprint());
                 var manager = manager(calls, cache)) {
                replan(manager); await(manager);
                assertEquals(ready, manager.readyCount());
                assertEquals(0, calls.get(), "warm manager must not call Java/native terrain generation");
                assertTrue(cache.hits() >= ready);
                var tile = manager.readyTiles().iterator().next();
                manager.invalidate(tile.baseBlockX() >> 4, tile.baseBlockZ() >> 4);
                manager.invalidateContent(); // This fixture explicitly requests a cold rebuild after the edit.
                cache.flush();
                replan(manager); await(manager);
                assertTrue(calls.get() > 0, "dirty chunk must remove stale fine columns as well as tile files");
                assertEquals(0, manager.failedTileCount());
            }
            System.out.println("Manager restart terrain calls: cold=" + firstCalls + ", warm=0; restoredTiles=" + ready);
        } finally {
            config.predictionDistanceBlocks = distance;
        }
    }

    @Test void identicalAuthoritativeColumnsAfterReconnectKeepStoredTiles() throws Exception {
        var empty=new dev.xantha.vss.api.VoxelColumnData(new dev.xantha.vss.api.VoxelColumnData.SectionData[0],1L,true);
        var key=PredictionDiskCache.Key.terrain(0,0,0);
        var sample=sampler(new AtomicInteger(),PROFILE).sample(0,0);
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(new AtomicInteger(),cache)) {
            manager.captureExactColumn(0,0,empty);awaitCaptures(manager);cache.flush();
            assertEquals(256,capturedCount(manager,0,0));
            assertEquals(0,manager.persistentSampleCount(),"captures use bounded region records, not the legacy heap store");
            try(var lease=cache.lease(key)){assertTrue(cache.writeTerrain(lease,new ClientColumnSample[]{sample}));}
        }
        finishBackgroundCloseBeforeTempCleanup();
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(new AtomicInteger(),cache)) {
            assertEquals(256,capturedCount(manager,0,0));
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

    @Test void cacheSeparatesWorldSeedAndDimensionButSharesGeneratorAndBackendChanges() {
        var sampler = sampler(new AtomicInteger(), PROFILE);
        var storage = PredictionCacheStorage.forWorld(directory, directory.resolve("saves/world-a"), null, null);
        Path a = storage.directory(sampler);
        assertNotEquals(a, PredictionCacheStorage.forWorld(directory, directory.resolve("saves/world-b"), null, null).directory(sampler));
        var otherSeed = new DimensionProfile(PROFILE.dimension(), 43L, -64, 384, "noise", "minecraft:overworld", 123L);
        assertNotEquals(a, storage.directory(sampler(new AtomicInteger(), otherSeed)));
        byte[] generator = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var otherGenerator = new DimensionProfile(PROFILE.dimension(), 42L, -64, 384, "noise", "minecraft:overworld", 123L, 0, generator.length, generator);
        assertEquals(a, storage.directory(sampler(new AtomicInteger(), otherGenerator)));
        assertEquals(a, storage.directory(ClientTerrainSampler.custom(42L, PROFILE, (x, z) -> 64)));
        var otherDimension = new DimensionProfile(ResourceLocation.withDefaultNamespace("the_nether"), 42L, -64, 384,
                "noise", "minecraft:overworld", 123L);
        assertNotEquals(a, storage.directory(sampler(new AtomicInteger(), otherDimension)));
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
            assertEquals(replace ? 256 : 0, capturedCount(manager,-3,4), "old in-flight captures must not undo dirty invalidation");
            manager.invalidate(-3, 4);
            assertEquals(replace ? 256 : 0, capturedCount(manager,-3,4), "dirty retains the last real snapshot until its replacement arrives");
        } finally { release.countDown(); }
    }

    @Test void coldStoredDetailReleasesOnlyOutsideTheHorizonAndAfterGrace() {
        var layout = VssLodLayout.of(1024, 2, true, true);
        var key = new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), -2, 2, 0);
        long cold = TimeUnit.SECONDS.toNanos(31);
        assertFalse(PredictionTileResidencyPolicy.canRetireStored(key, cold, false, true, layout, false),
                "Looking away must not discard loaded detail inside the prediction horizon");
        assertFalse(PredictionTileResidencyPolicy.canRetireStored(key, cold, true, true, layout, true));
        assertFalse(PredictionTileResidencyPolicy.canRetireStored(key, cold, false, false, layout, true));
        assertFalse(PredictionTileResidencyPolicy.canRetireStored(key, TimeUnit.SECONDS.toNanos(29), false, true, layout, true));
        assertTrue(PredictionTileResidencyPolicy.canRetireStored(key, TimeUnit.SECONDS.toNanos(30), false, true, layout, true));
        var root = new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), -100, 100, layout.levelCount() - 1);
        assertTrue(PredictionTileResidencyPolicy.canRetireStored(root, cold, false, true, layout, true),
                "stored roots beyond the horizon cannot accumulate forever merely because they have no parent");
    }

    private static PredictionTileManager manager(AtomicInteger calls, PredictionDiskCache disk) {
        return new PredictionTileManager(PROFILE.levelKey(), sampler(calls, PROFILE),
                new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), disk);
    }
    private static ClientTerrainSampler sampler(AtomicInteger calls, DimensionProfile profile) {
        return new ClientTerrainSampler(profile.seed(), profile) {
            @Override long colorCacheFingerprint() { return 77; }
            @Override public ClientColumnSample sample(int x, int z) {
                calls.incrementAndGet();
                return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(), 0, 0, 0, 0, 0,
                        ClientColumnSample.FLAG_SURFACE_ONLY, 0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
        };
    }
    @SuppressWarnings("unchecked")
    private static void replan(PredictionTileManager manager) throws Exception {
        // Drive one real Voxy-owned tile deterministically; no GPU or native Voxy instance is created by this suite.
        var key = new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(),0,0,manager.layout().levelCount()-1);
        var unified=PredictionTileManager.class.getDeclaredField("unifiedVoxy");unified.setAccessible(true);unified.setBoolean(manager,true);
        for (String name : java.util.List.of("desiredKeys","terrainLeaves")) {
            var field=PredictionTileManager.class.getDeclaredField(name);field.setAccessible(true);
            ((Set<PredictionTileManager.PredictionTileKey>)field.get(manager)).add(key);
        }
        var targets=PredictionTileManager.class.getDeclaredField("terrainTargets");targets.setAccessible(true);
        targets.set(manager,java.util.Map.of(key,32));
        var ordinary=PredictionTileManager.class.getDeclaredField("ordinaryTargets");ordinary.setAccessible(true);
        ordinary.set(manager,java.util.Map.of(key,32));
        var enqueue=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileManager.PredictionTileKey.class,int.class,int.class);
        enqueue.setAccessible(true);enqueue.invoke(manager,key,0,0);
    }
    private static int capturedCount(PredictionTileManager manager,int chunkX,int chunkZ) throws Exception {
        var field=PredictionTileManager.class.getDeclaredField("capturedTerrain");field.setAccessible(true);
        var captures=(PredictionCapturedTerrain)field.get(manager);int count=0;
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(captures.exact(chunkX*16+x,chunkZ*16+z)!=null)count++;
        return count;
    }
    private static void await(PredictionTileManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (manager.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, manager.pendingCount(), "IO/build stage must finish");
    }
}
