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
    @Test void productionQueueBuildsParentWithoutNewDensityReadsAndRejectsObsoleteProof() throws Exception {
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
                ready.put(key,PredictionParentLodTest.tile(key,10,64));
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
                var source=parent.data().parentLod.child(0).key();
                ready.put(source,PredictionParentLodTest.tile(source,777,70));
                var publish=PredictionTileManager.class.getDeclaredMethod("publishTile",PredictionTile.class,
                        PredictionMemoryBudget.Reservation.class,long.class,long.class,boolean.class,boolean.class,boolean.class,PredictionRelief.class);
                publish.setAccessible(true);
                assertEquals(false,publish.invoke(manager,parent,null,0L,0L,false,false,false,null),
                        "stale child proof must fail before retaining memory or changing the live tile");
                var refresh=PredictionTileManager.class.getDeclaredMethod("refreshDerivedParent",PredictionTileKey.class);refresh.setAccessible(true);
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
    private static java.lang.reflect.Field field(String name) throws Exception {
        var f=PredictionTileManager.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
}
