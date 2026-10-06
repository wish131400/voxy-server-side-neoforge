package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionCaptureQueueTest {
    @TempDir Path directory;
    private PredictionCacheTestFiles.ResourceIdentity resourceIdentity;
    @BeforeEach void resourceIdentity() throws Exception { resourceIdentity = new PredictionCacheTestFiles.ResourceIdentity(); }
    @AfterEach void restoreResourceIdentity() throws Exception { resourceIdentity.close(); }
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void drain() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void queuedRestoreUsesLatestCaptureAndUpdatedCacheInsteadOfStartingOver() throws Exception {
        var config = VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks;
        boolean trees = config.predictionTrees, structures = config.predictionStructures;
        config.predictionDistanceBlocks = 4096;
        config.predictionTrees = false; config.predictionStructures = false;
        var profile = new DimensionProfile(Level.OVERWORLD.location(),42,-64,384,"noise","minecraft:overworld",77);
        var tile = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
        var diskKey = PredictionDiskCache.Key.terrain(0,0,0);
        var sampled = new AtomicInteger();
        var source = new ClientTerrainSampler(42,profile) {
            @Override long colorCacheFingerprint() { return 77; }
            @Override public ClientColumnSample sample(int x,int z) { sampled.incrementAndGet(); return PredictionSimpleVegetationTest.sample(120); }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample(x,z); }
        };
        var budget = new PredictionMemoryBudget(2048L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
        var release = new CountDownLatch(1);
        try (var cache = new PredictionDiskCache(directory,77)) {
            PredictionCacheTestFiles.finishedTerrain(cache, diskKey, PredictionAsyncTerrainCacheTest.data().samples(), source);
            cache.probeTerrain(List.of(diskKey)); cache.flush();
            try (var manager = new PredictionTileManager(Level.OVERWORLD,source,budget,cache)) {
                var desired = PredictionTileManager.class.getDeclaredField("desiredKeys"); desired.setAccessible(true);
                desired.set(manager,new HashSet<>(Set.of(tile)));
                var field = PredictionTileManager.class.getDeclaredField("cacheExecutor"); field.setAccessible(true);
                var cacheExecutor = (ThreadPoolExecutor) field.get(manager);
                var entered = new CountDownLatch(cacheExecutor.getCorePoolSize());
                for (int worker = 0; worker < cacheExecutor.getCorePoolSize(); worker++) cacheExecutor.execute(() -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileManager.PredictionTileKey.class,int.class,int.class,boolean.class,boolean.class);
                enqueue.setAccessible(true); enqueue.invoke(manager,tile,0,0,false,true);
                assertEquals(1,manager.pendingCount());
                manager.invalidate(0,0);
                cache.flush();
                var updated = new ClientColumnSample[66*66]; Arrays.fill(updated,PredictionSimpleVegetationTest.sample(160));
                PredictionCacheTestFiles.finishedTerrain(cache, diskKey, updated, source);
                release.countDown();
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while (manager.pendingCount()!=0 && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(0,manager.pendingCount());
                assertEquals(1,manager.readyCount(),manager.surfaceDiagnostics());
                assertEquals(160,manager.readyTiles().iterator().next().heightAt(0,0));
                assertEquals(0,sampled.get(),"the updated fine cache must not restart generation");
            }
        } finally {
            release.countDown(); config.predictionDistanceBlocks = distance;
            config.predictionTrees = trees; config.predictionStructures = structures;
        }
        assertEquals(0,budget.usedBytes());
    }
}
