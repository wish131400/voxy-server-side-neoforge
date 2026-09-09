package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PredictionBackgroundSchedulingTest {
    @Test @SuppressWarnings("unchecked")
    void rearJobsHaveBoundedAdmissionAndTurningPromotesWorkWithoutRemovingTerrain() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var config=VSSClientConfig.CONFIG;
        int oldBackground=config.predictionBackgroundWorkers;
        config.predictionBackgroundWorkers=2;
        var release=new CountDownLatch(1);
        var entered=new CountDownLatch(3);
        var profile=new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),42L,-64,384,"noise","minecraft:overworld",123L);
        var sample=new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var sampler=new ClientTerrainSampler(42,profile) {
            @Override public ClientColumnSample sample(int x,int z) {
                entered.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("worker fixture timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                return sample;
            }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample(x,z); }
        };
        var budget=new PredictionMemoryBudget(2048L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,4);
        try(var manager=new PredictionTileManager(profile.levelKey(),sampler,budget,null)) {
            manager.setWorkView(PredictionWorkView.of(0,80,0,1,0,0,70,16D/9));
            var background=PredictionTileManager.class.getDeclaredMethod("backgroundWork",PredictionTileManager.PredictionTileKey.class);
            background.setAccessible(true);
            assertFalse((Boolean)background.invoke(manager,new PredictionTileManager.PredictionTileKey(
                    profile.levelKey(),0,0,manager.layout().levelCount())),"shrinking the layout must tolerate old pending keys");
            var ready=(Map<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile>)field(manager,"ready");
            var desired=(Set<Object>)field(manager,"desiredKeys");
            var leaves=(Set<Object>)field(manager,"terrainLeaves");
            var pending=(Set<?>)field(manager,"pending");
            var rear=new ArrayList<PredictionTileManager.PredictionTileKey>();
            for(int x:new int[]{-40,-44,-48,40}) {
                var key=new PredictionTileManager.PredictionTileKey(profile.levelKey(),x,0,1);
                rear.add(key);
                var parent=new PredictionTileManager.PredictionTileKey(profile.levelKey(),x>>1,0,2);
                desired.add(key);desired.add(parent);leaves.add(key);
                ready.put(parent,new PredictionTileManager.PredictionTile(parent,new int[0],new int[0],
                        new ClientColumnSample[0],null,new PredictionDepthBound(64,64),0,1,8,32));
            }
            var enqueue=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileManager.PredictionTileKey.class,int.class,int.class,boolean.class);
            enqueue.setAccessible(true);
            for(int i=0;i<20;i++) for(var key:rear) enqueue.invoke(manager,key,0,0,false);
            assertTrue(entered.await(5,TimeUnit.SECONDS),"two background jobs must leave a foreground worker available");
            assertEquals(2,((Set<?>)field(manager,"backgroundPending")).size());
            assertFalse(pending.contains(rear.get(2)),"excess background work must not bounce through worker threads");
            assertTrue(pending.contains(rear.get(3)),"foreground admission bypasses the background cap");
            manager.setWorkView(PredictionWorkView.of(0,80,0,-1,0,0,70,16D/9));
            enqueue.invoke(manager,rear.get(2),0,0,false);
            assertTrue(pending.contains(rear.get(2)),"turning promotes previously deferred rear terrain");
            assertEquals(4,ready.size(),"scheduling must retain all parent coverage");
            release.countDown();
        } finally {release.countDown();config.predictionBackgroundWorkers=oldBackground;}
    }
    private static Object field(Object target,String name) throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
}
