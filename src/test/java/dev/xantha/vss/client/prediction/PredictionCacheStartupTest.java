package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionCacheStartupTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(net.minecraft.resources.ResourceLocation.tryParse("minecraft:overworld"),42,-64,384,"noise","minecraft:overworld",123);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() throws Exception { PredictionResources.awaitRetired(); PredictionCacheTestFiles.awaitBackgroundClose(); }

    static PredictionCacheSampler cacheSampler(DimensionProfile profile) {
        var source=mock(BiomeSource.class);when(source.possibleBiomes()).thenReturn(Set.of());
        return new PredictionCacheSampler(profile,source,new JsonObject());
    }
    private static PredictionMemoryBudget budget() {
        return new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
    }
    private static PredictionCpuBudget cpu() { return new PredictionCpuBudget(()->8,System::nanoTime); }
    private static ClientTerrainSampler generating(AtomicInteger calls) {
        return new ClientTerrainSampler(42,PROFILE) {
            @Override public ClientColumnSample sample(int x,int z) { calls.incrementAndGet();return PredictionVoxelFixtures.sample(64); }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample(x,z); }
            @Override public ClientColumnSample samplePreview(int x,int z,int step) { return sample(x,z); }
        };
    }

    @Test void warmCacheDisplaysBeforeGenerationAndPromotionRetainsItsSourceAndTiles() throws Exception {
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);var context=cacheSampler(PROFILE);
        int settings=3|PredictionVegetation.runtimeSettings(PROFILE.dimension());
        try(var disk=new PredictionDiskCache(directory,77);var lease=disk.lease(PredictionDiskCache.Key.voxels(-1,-1,1))) {
            assertTrue(disk.writeVoxelsLater(lease,PredictionVoxelCacheTest.tile(key,64,true),settings,
                    PredictionVoxelCache.identity(context)).get(5,TimeUnit.SECONDS));
        }
        var budget=budget();var calls=new AtomicInteger();
        try(var disk=new PredictionDiskCache(directory,77);var manager=new PredictionTileManager(PROFILE.levelKey(),context,budget,disk,cpu())) {
            setup(manager,key);disk.probeTerrain(List.of(PredictionDiskCache.Key.terrain(-1,-1,1)));disk.flush();
            enqueue(manager,key,false);await(manager);
            assertFalse(manager.generationReady());assertEquals(1,manager.readyCount());
            var tile=manager.readyTiles().iterator().next();var source=field("voxySource").get(manager);
            assertTrue(tile.data().voxyTile.surfaceComplete());assertEquals(64,tile.cellAxis());
            var full=generating(calls);assertTrue(manager.acceptsGeneration(full));
            full.bindCacheFingerprint(context.cacheFingerprint());
            assertTrue(manager.enableGeneration(full,new PredictionVegetation(full,disk,true)));
            assertTrue(manager.generationReady());assertSame(full,manager.sampler());
            assertSame(source,field("voxySource").get(manager));assertSame(tile,manager.readyTiles().iterator().next());
            assertEquals(0,calls.get(),"Promotion cannot rebuild a completed cached tile");
        }
        assertEquals(0,budget.usedBytes());
    }

    @Test void missingFinishedCacheDefersGenerationEvenIfRawTerrainIsCachedThenResumes() throws Exception {
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);var context=cacheSampler(PROFILE);var calls=new AtomicInteger();
        try(var disk=new PredictionDiskCache(directory,77);var manager=new PredictionTileManager(PROFILE.levelKey(),context,budget(),disk,cpu())) {
            setup(manager,key);
            try(var lease=disk.lease(PredictionDiskCache.Key.terrain(-1,-1,1))) {
                var samples=new ClientColumnSample[18*18];Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
                var data=new PredictionDiskCache.TerrainData(samples,Long.MIN_VALUE,null,null,null);
                assertTrue(disk.writeTerrain(lease,data));
            }
            disk.probeTerrain(List.of(PredictionDiskCache.Key.terrain(-1,-1,1)));disk.flush();
            enqueue(manager,key,false);await(manager);assertEquals(0,manager.readyCount());assertEquals(0,manager.failedTileCount());
            var full=generating(calls);assertTrue(manager.acceptsGeneration(full));full.bindCacheFingerprint(context.cacheFingerprint());
            assertTrue(manager.enableGeneration(full,new PredictionVegetation(full,disk,true)));
            // A raw-cache miss recorded during bootstrap must not suppress normal generation.
            enqueue(manager,key,false);await(manager);assertEquals(1,manager.readyCount());
            enqueue(manager,key,false);await(manager);assertTrue(calls.get()>0);
        }
    }

    @Test void promotionRejectsDifferentNamespacesPalettesOrVoxelIdentityAndClosedManagerCannotRevive() throws Exception {
        var context=cacheSampler(PROFILE);var replacement=generating(new AtomicInteger());
        try(var manager=new PredictionTileManager(PROFILE.levelKey(),context,budget(),null,cpu())) {
            assertTrue(manager.acceptsGeneration(replacement));
            replacement.bindPredictionBackend("test","different-provider");assertFalse(manager.acceptsGeneration(replacement));
            replacement.bindPredictionBackend("test",null);
            var other=new ClientTerrainSampler(42,PROFILE) { @Override List<String> cacheBiomes(){return List.of("minecraft:forest");} };
            assertFalse(manager.acceptsGeneration(other));
            var floating=new ClientTerrainSampler(42,PROFILE) { @Override boolean floatingTerrain(){return true;} };
            assertFalse(manager.acceptsGeneration(floating));
            manager.close();assertFalse(manager.acceptsGeneration(replacement));
            assertFalse(manager.enableGeneration(replacement,new PredictionVegetation(replacement)));
        }
    }

    @Test void earlyOpenUsesOnlyExistingValidatedWorldAndNeverMigratesOrCreatesOnMiss() throws Exception {
        boolean remember=VSSClientConfig.CONFIG.rememberTerrain;VSSClientConfig.CONFIG.rememberTerrain=true;
        try {
            var storage=new PredictionCacheStorage(directory.resolve("new"),null);var context=cacheSampler(PROFILE);
            assertNull(storage.openExisting(context));assertFalse(Files.exists(storage.base()));
            context.legacyCacheIdentity(()->{fail("Early display cannot scan old generation-specific caches");return "";});
            try(var initialized=storage.open(new ClientTerrainSampler(42,PROFILE))) { assertNotNull(initialized); }
            try(var cache=storage.openExisting(context)) {assertNotNull(cache);assertEquals(PredictionCacheStorage.fingerprint(PROFILE),context.cacheFingerprint());}
            Files.writeString(storage.directory(context).resolve("world.json"),"{\"version\":1,\"seed\":99,\"dimension\":\"minecraft:overworld\",\"fingerprint\":1}");
            assertNull(storage.openExisting(context));
        } finally {VSSClientConfig.CONFIG.rememberTerrain=remember;}
    }

    @SuppressWarnings("unchecked") static void setup(PredictionTileManager manager,PredictionTileKey key) throws Exception {
        field("unifiedVoxy").setBoolean(manager,true);
        for(String name:List.of("desiredKeys","terrainLeaves"))((Set<PredictionTileKey>)field(name).get(manager)).add(key);
        field("terrainTargets").set(manager,Map.of(key,32));field("ordinaryTargets").set(manager,Map.of(key,32));
    }
    private static java.lang.reflect.Field field(String name) throws Exception {var f=PredictionTileManager.class.getDeclaredField(name);f.setAccessible(true);return f;}
    static void enqueue(PredictionTileManager manager,PredictionTileKey key,boolean cached) throws Exception {
        var method=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class,boolean.class);
        method.setAccessible(true);method.invoke(manager,key,0,0,false,cached);
    }
    static void await(PredictionTileManager manager) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(manager.pendingCount()>0 && System.nanoTime()<deadline)Thread.sleep(5);
        assertEquals(0,manager.pendingCount());assertEquals(0,manager.failedTileCount(),manager.surfaceDiagnostics());
    }
}
