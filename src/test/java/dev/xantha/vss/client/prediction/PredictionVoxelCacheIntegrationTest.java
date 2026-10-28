package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionVoxelCacheIntegrationTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L,-64,384,"noise","minecraft:overworld",123L);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() throws Exception {
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    @Test void cacheWorkerRestoresCompleteFineVoxelsWithoutTerrainOrVegetationGenerationUnderCpuPressure() throws Exception {
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
        var original=PredictionVoxelCacheTest.tile(key,64,true);
        var calls=new AtomicInteger();var sampler=sampler(calls);
        var settings=PredictionTileManager.class.getDeclaredMethod("surfaceSettings");settings.setAccessible(true);
        int value=(Integer)settings.invoke(null);
        try(var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(PredictionDiskCache.Key.voxels(-1,-1,1))) {
            assertTrue(cache.writeVoxelsLater(lease,original,value,PredictionVoxelCache.identity(sampler)).get(5,TimeUnit.SECONDS));
        }
        var budget=new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
        try(var cache=new PredictionDiskCache(directory,77);var manager=new PredictionTileManager(PROFILE.levelKey(),sampler,budget,cache,
                new PredictionCpuBudget(()->1,()->.99,()->.99,System::nanoTime,false))) {
            setup(manager,key,32);
            cache.probeTerrain(List.of(PredictionDiskCache.Key.terrain(-1,-1,1)));cache.flush();
            enqueue(manager,key,true);await(manager);
            var restored=manager.readyTiles().iterator().next();
            assertEquals(64,restored.cellAxis());assertEquals(0,calls.get());
            assertEquals(original.data().voxyTile.features(),restored.data().voxyTile.features());
            assertTrue(restored.data().voxyTile.surfaceComplete());
            assertTrue(((Set<?>)field("surfaceReady").get(manager)).contains(key));
            var count=field("voxelRestores");assertEquals(1,((java.util.concurrent.atomic.LongAdder)count.get(manager)).sum());
            assertTrue(manager.readyVoxelBytes()>0);
            manager.connectVoxy();assertSame(restored,manager.readyTiles().iterator().next(),"renderer reconnection retains restored data");
        }
        assertEquals(0,budget.usedBytes());assertEquals(0,budget.activeBuildCount());
    }

    @Test void restoredPartialVoxelsCanRefineAndPersistTheNewStageWithoutLosingFeatures() throws Exception {
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
        var original=PredictionVoxelCacheTest.tile(key,16,false);
        var calls=new AtomicInteger();var sampler=sampler(calls);
        var settings=PredictionTileManager.class.getDeclaredMethod("surfaceSettings");settings.setAccessible(true);
        int value=(Integer)settings.invoke(null);
        try(var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(PredictionDiskCache.Key.voxels(-1,-1,1))) {
            assertTrue(cache.writeVoxelsLater(lease,original,value,PredictionVoxelCache.identity(sampler)).get(5,TimeUnit.SECONDS));
        }
        try(var cache=new PredictionDiskCache(directory,77);var manager=new PredictionTileManager(PROFILE.levelKey(),sampler,
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),cache,
                new PredictionCpuBudget(()->8,()->.1,()->.1,System::nanoTime,false))) {
            setup(manager,key,32);cache.probeTerrain(List.of(PredictionDiskCache.Key.terrain(-1,-1,1)));cache.flush();
            enqueue(manager,key,true);await(manager);assertEquals(16,manager.readyTiles().iterator().next().cellAxis());
            assertEquals(0,calls.get());assertFalse(((Set<?>)field("surfaceReady").get(manager)).contains(key));
            enqueue(manager,key,false);await(manager);cache.flush();
            var refined=manager.readyTiles().iterator().next();assertEquals(32,refined.cellAxis());assertTrue(calls.get()>0);
            assertEquals(original.data().voxyTile.features().subList(1,2),refined.data().voxyTile.features(),
                    "terrain-only refinement retains existing vegetation");
            try(var lease=cache.lease(PredictionDiskCache.Key.voxels(-1,-1,1))) {
                var saved=cache.readVoxels(lease,key,value,PredictionVoxelCache.identity(sampler));assertNotNull(saved);
                assertEquals(32,saved.voxels().axis());assertEquals(refined.data().voxyTile.features(),saved.voxels().features());
            }
        }
    }

    private static ClientTerrainSampler sampler(AtomicInteger calls) {
        return new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override public ClientColumnSample sample(int x,int z) {calls.incrementAndGet();return PredictionVoxelFixtures.sample(64);}
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {return sample(x,z);}
            @Override public ClientColumnSample samplePreview(int x,int z,int step) {return sample(x,z);}
        };
    }
    @SuppressWarnings("unchecked") private static void setup(PredictionTileManager manager,PredictionTileKey key,int axis) throws Exception {
        field("unifiedVoxy").setBoolean(manager,true);
        for(String name:List.of("desiredKeys","terrainLeaves")) ((Set<PredictionTileKey>)field(name).get(manager)).add(key);
        field("terrainTargets").set(manager,Map.of(key,axis));field("ordinaryTargets").set(manager,Map.of(key,axis));
    }
    private static java.lang.reflect.Field field(String name) throws Exception {
        var f=PredictionTileManager.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static void enqueue(PredictionTileManager manager,PredictionTileKey key,boolean cached) throws Exception {
        var method=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class,boolean.class);
        method.setAccessible(true);method.invoke(manager,key,0,0,false,cached);
    }
    private static void await(PredictionTileManager manager) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(manager.pendingCount()>0 && System.nanoTime()<deadline)Thread.sleep(5);
        assertEquals(0,manager.pendingCount());assertEquals(0,manager.failedTileCount(),manager.surfaceDiagnostics());
    }
}
