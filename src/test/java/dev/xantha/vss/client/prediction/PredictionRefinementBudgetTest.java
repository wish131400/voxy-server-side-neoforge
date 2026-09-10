package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionRefinementBudgetTest {
    @Test void automaticBudgetUsesHalfTheProcessorsAndHonorsSmallerOverrides() {
        assertEquals(1,PredictionWorkOrder.refinementWorkers(1,0));
        assertEquals(2,PredictionWorkOrder.refinementWorkers(4,0));
        assertEquals(8,PredictionWorkOrder.refinementWorkers(16,0));
        assertEquals(16,PredictionWorkOrder.refinementWorkers(32,0));
        assertEquals(4,PredictionWorkOrder.refinementWorkers(16,4));
        assertEquals(8,PredictionWorkOrder.refinementWorkers(16,32));
    }

    @Test void completedMediumPassFinishesNearbyFineTerrainBeforeDistantChildPreviews() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var profile=new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",123);
        try(var manager=new PredictionTileManager(profile.levelKey(),new ClientTerrainSampler(42,profile),
                new PredictionMemoryBudget(2048L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,8),null)) {
            var priority=manager.getClass().getDeclaredMethod("workPriority",PredictionTileKey.class,boolean.class);
            priority.setAccessible(true);
            var near=new PredictionTileKey(profile.levelKey(),8,0,0);
            var far=new PredictionTileKey(profile.levelKey(),80,0,0);
            @SuppressWarnings("unchecked") var ready=(Map<PredictionTileKey,PredictionTile>)field(manager,"ready");
            ready.put(near,new PredictionTile(near,new int[0],new int[0],new ClientColumnSample[0],null,
                    new PredictionDepthBound(64,64),0,1,32,2));
            assertTrue((int)priority.invoke(manager,near,false)<(int)priority.invoke(manager,far,false),
                    "fine work beyond the old256-block exception must precede distant child previews");
            assertTrue((int)priority.invoke(manager,near,true)<(int)priority.invoke(manager,far,false),
                    "nearby plants need not wait for a second horizon preview wave");
            var focus=manager.getClass().getDeclaredField("buildFocus");focus.setAccessible(true);
            focus.set(manager,new VssLodFocus(5152,32,1024,9000));
            assertTrue((int)priority.invoke(manager,far,false)<(int)priority.invoke(manager,near,false),
                    "explicit telescope target retains priority");
        }
    }

    @Test @SuppressWarnings("unchecked")
    void ordinaryForegroundHasBoundedAdmissionAndScopeBypassesIt() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int previous=VSSClientConfig.CONFIG.predictionRefinementWorkers;
        VSSClientConfig.CONFIG.predictionRefinementWorkers=2;
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(2);
        var profile=new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",123);
        var sample=new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var sampler=new ClientTerrainSampler(42,profile) {
            @Override int initialTerrainCellAxis(int lod) { return 8; }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {
                started.countDown();
                try { if(!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("blocked test worker"); }
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new CancellationException();}
                return sample;
            }
        };
        try(var manager=new PredictionTileManager(profile.levelKey(),sampler,
                new PredictionMemoryBudget(2048L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,4),null)) {
            var desired=(Set<PredictionTileKey>)field(manager,"desiredKeys");
            var leaves=(Set<PredictionTileKey>)field(manager,"terrainLeaves");
            var pending=(Set<PredictionTileKey>)field(manager,"pending");
            int lod=manager.layout().levelCount()-1;
            var keys=List.of(new PredictionTileKey(profile.levelKey(),0,0,lod),
                    new PredictionTileKey(profile.levelKey(),1,0,lod),new PredictionTileKey(profile.levelKey(),2,0,lod));
            desired.addAll(keys);leaves.addAll(keys);
            var enqueue=manager.getClass().getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class);
            enqueue.setAccessible(true);
            for(int i=0;i<30;i++) for(var key:keys) enqueue.invoke(manager,key,0,0,false);
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertEquals(2,((Set<?>)field(manager,"refinementPending")).size());
            assertFalse(pending.contains(keys.get(2)),"excess foreground work must stay out of the executor");
            var focus=manager.getClass().getDeclaredField("buildFocus");focus.setAccessible(true);
            focus.set(manager,new VssLodFocus(manager.layout().tileBlocks(lod)*2.5,32,1024,9000));
            enqueue.invoke(manager,keys.get(2),0,0,false);
            assertTrue(pending.contains(keys.get(2)),"scope bypasses the ordinary budget");
            release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!pending.isEmpty() && System.nanoTime()<until) Thread.sleep(5);
            assertTrue(pending.isEmpty());
            assertTrue(((Set<?>)field(manager,"refinementPending")).isEmpty(),"publication releases admission");
            assertEquals(0,manager.failedTileCount());
        } finally {release.countDown();VSSClientConfig.CONFIG.predictionRefinementWorkers=previous;}
    }

    @Test void distantTargetsStopAtMediumWhileNearbyAndScopeStillRefine() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var layout=VssLodLayout.of(8192,6,true,false);
        var plain=PredictionLodPlanner.plan(Level.OVERWORLD,32,80,32,layout,null,1300);
        var far=owner(plain,layout,6000,32);
        assertEquals(layout.levelCount()-4,far.lod(),"ordinary far terrain ends at spatial medium coverage");
        assertTrue(owner(plain,layout,32,32).lod()<=1,"local block terrain remains eligible");
        var focus=new VssLodFocus(6000,32,1024,9000);
        var scoped=PredictionLodPlanner.plan(Level.OVERWORLD,32,80,32,layout,focus,1300);
        assertEquals(0,owner(scoped,layout,6000,32).lod(),"scope can refine distant terrain fully");
        var moved=PredictionLodPlanner.plan(Level.OVERWORLD,6000,80,32,layout,null,1300);
        assertTrue(owner(moved,layout,6000,32).lod()<=1,"moving into the region restores local detail");
    }

    private static PredictionTileKey owner(List<PredictionTileKey> keys,VssLodLayout layout,int x,int z) {
        return keys.stream().filter(k -> Math.floorDiv(x,layout.tileBlocks(k.lod()))==k.tileX()
                && Math.floorDiv(z,layout.tileBlocks(k.lod()))==k.tileZ()).findFirst().orElseThrow();
    }
    private static Object field(Object target,String name)throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
}
