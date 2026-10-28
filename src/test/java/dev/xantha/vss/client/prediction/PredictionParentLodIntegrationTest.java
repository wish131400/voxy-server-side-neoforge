package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.config.VSSClientConfig;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionParentLodIntegrationTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,ClientColumnSample.FLAG_APPROXIMATE,ClientColumnSample.FLAG_DISPLAY,ClientColumnSample.FLAG_CAPTURE_MIP})
    void productionQueueBuildsParentWithoutNewDensityReadsAndRejectsObsoleteProof(int flags) throws Exception {
        var config=VSSClientConfig.CONFIG;
        int distance=config.predictionDistanceBlocks;
        config.predictionDistanceBlocks=4096;
        var calls=new AtomicInteger();
        try {
            var samplerMethod=PredictionFirstCoverageTest.class.getDeclaredMethod("sampler",AtomicInteger.class,
                    java.util.concurrent.CountDownLatch.class,java.util.concurrent.CountDownLatch.class);
            samplerMethod.setAccessible(true);
            var sampler=(ClientTerrainSampler)samplerMethod.invoke(null,calls,null,null);
            var create=PredictionFirstCoverageTest.class.getDeclaredMethod("manager",ClientTerrainSampler.class,PredictionDiskCache.class);
            create.setAccessible(true);
            try(var manager=(PredictionTileManager)create.invoke(null,sampler,null)) {
                var key=new PredictionTileKey(Level.OVERWORLD,0,0,1);
                var ready=(Map<PredictionTileKey,PredictionTile>)field("ready").get(manager);
                ready.putAll(PredictionParentLodTest.children(key));
                for(var child:ready.values()) for(int i=0;i<child.samples().length;i++) {
                    var sample=child.samples()[i];
                    child.samples()[i]=new ClientColumnSample(sample.surfaceY(),sample.fluidY(),sample.biomeIndex(),
                            sample.topBlockIndex(),0,0,0,0,0,flags,0,sample.underBlockIndex(),sample.deepBlockIndex(),
                            sample.surfaceBottom(),sample.lowerTop(),sample.lowerBottom(),sample.spanFloor(),sample.volume());
                }
                var previous=PredictionParentLodTest.tile(key,10,64);
                var tree=new PredictionVoxySource.Box(8,64,8,9,80,9,
                        net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
                var ground=previous.data().voxyTile;
                previous.data().voxyTile=new PredictionVoxySource.Tile(key,ground.baseX(),ground.baseZ(),
                        ground.span(),ground.axis(),ground.step(),ground.columns(),List.of(tree),ground.terrainBytes(),true,0);
                ready.put(key,new PredictionTile(key,previous.heights(),previous.groundHeights(),previous.samples(),
                        previous.data(),new PredictionDepthBound(64,80),0,10,previous.cellAxis(),previous.spacingBlocks()));
                ((Set<PredictionTileKey>)field("dirtyTiles").get(manager)).add(key);
                var desire=PredictionFirstCoverageTest.class.getDeclaredMethod("desire",PredictionTileManager.class,PredictionTileKey.class,boolean.class);
                desire.setAccessible(true);desire.invoke(null,manager,key,true);
                var enqueue=PredictionFirstCoverageTest.class.getDeclaredMethod("enqueue",PredictionTileManager.class,PredictionTileKey.class);
                enqueue.setAccessible(true);enqueue.invoke(null,manager,key);
                var idle=PredictionFirstCoverageTest.class.getDeclaredMethod("awaitIdle",PredictionTileManager.class);
                idle.setAccessible(true);idle.invoke(null,manager);
                var parent=ready.get(key);assertNotNull(parent);
                assertNotNull(parent.data().parentLod,"real queue must publish source proof, not a separately sampled parent");
                assertEquals(0,calls.get(),"mip generation must reuse all four fine grids without new density calls");
                assertEquals(List.of(tree),parent.data().voxyTile.features(),"Child-derived ground refresh keeps completed plants");
                assertTrue(parent.data().voxyTile.surfaceComplete());
                assertEquals(80,parent.depthBound().maxY());
                for(var sample:parent.samples())assertFalse(sample.reusableFor(false));
                var refresh=PredictionTileManager.class.getDeclaredMethod("refreshDerivedParent",PredictionTileKey.class);refresh.setAccessible(true);
                var children=PredictionParentLodTest.children(key).keySet();
                for(int repeat=0;repeat<50;repeat++)for(var child:children)refresh.invoke(manager,child);
                assertFalse(((Set<?>)field("dirtyTiles").get(manager)).contains(key),"Accepted display sources must settle after one parent reduction");
                var source=parent.data().parentLod.child(0).key();
                ready.put(source,PredictionParentLodTest.tile(source,777,70));
                var publish=PredictionTileManager.class.getDeclaredMethod("publishTile",PredictionTile.class,
                        PredictionMemoryBudget.Reservation.class,long.class,long.class,boolean.class,boolean.class,boolean.class,PredictionRelief.class);
                publish.setAccessible(true);
                assertEquals(false,publish.invoke(manager,parent,null,0L,0L,false,false,false,null),
                        "stale child proof must fail before retaining memory or changing the live tile");
                refresh.invoke(manager,source);
                var epochs=(Map<PredictionTileKey,Long>)field("captureEpochs").get(manager);
                long epoch=epochs.get(key);
                refresh.invoke(manager,source);
                assertEquals(epoch,epochs.get(key),"repeated child notifications must merge into one dirty parent epoch");
                assertSame(parent,ready.get(key),"refresh must preserve old geometry until replacement is ready");
            }
        } finally {
            config.predictionDistanceBlocks=distance;
        }
    }
    @SuppressWarnings("unchecked")
    @Test void reclamationKeepsParentInputsAndStillReleasesUnrelatedCoveredDetail() throws Exception {
        var samplerMethod=PredictionFirstCoverageTest.class.getDeclaredMethod("sampler",AtomicInteger.class,
                java.util.concurrent.CountDownLatch.class,java.util.concurrent.CountDownLatch.class);
        samplerMethod.setAccessible(true);
        var sampler=(ClientTerrainSampler)samplerMethod.invoke(null,new AtomicInteger(),null,null);
        var budget=new PredictionMemoryBudget(PredictionMemoryBudget.BUILD_BYTES-1,0,()->Long.MAX_VALUE,System::nanoTime,1);
        try(var manager=new PredictionTileManager(Level.OVERWORLD,sampler,budget,null)) {
            var parentKey=new PredictionTileKey(Level.OVERWORLD,0,0,1);
            var ready=(Map<PredictionTileKey,PredictionTile>)field("ready").get(manager);
            var children=PredictionParentLodTest.children(parentKey);
            ready.putAll(children);
            var parent=PredictionParentLodTest.tile(parentKey,90,64);ready.put(parentKey,parent);
            var unrelatedKey=new PredictionTileKey(Level.OVERWORLD,8,8,0);
            var unrelatedParent=new PredictionTileKey(Level.OVERWORLD,4,4,1);
            ready.put(unrelatedKey,PredictionParentLodTest.tile(unrelatedKey,91,64));
            ready.put(unrelatedParent,PredictionParentLodTest.tile(unrelatedParent,92,64));
            ((Set<PredictionTileKey>)field("desiredKeys").get(manager)).add(parentKey);
            var dirty=(Set<PredictionTileKey>)field("dirtyTiles").get(manager);dirty.add(parentKey);
            for(int round=0;round<8;round++) {
                assertTrue(PredictionDemandTestSupport.reclaim(manager,0,0));
                for(var entry:children.entrySet()) assertSame(entry.getValue(),ready.get(entry.getKey()),
                        "The pending parent cannot evict the child evidence needed to compose it");
                assertSame(parent,ready.get(parentKey),"Old coverage remains visible until replacement fits");
                assertTrue(dirty.contains(parentKey),"Memory pressure must preserve unfinished work");
            }
            assertFalse(ready.containsKey(unrelatedKey),"Unrelated covered detail must remain reclaimable");
            assertEquals(0,manager.builtTileCount(),"No independent fallback build can replace missing source evidence");
        }
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        var f=PredictionTileManager.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
}
