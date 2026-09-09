package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PredictionRefinementConvergenceTest {
    @Test @SuppressWarnings("unchecked")
    void selectedTileRefinesWhileAdjacentPreviewIsStillWaiting() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var config=VSSClientConfig.CONFIG;
        int distance=config.predictionDistanceBlocks;
        boolean trees=config.predictionTrees,structures=config.predictionStructures;
        config.predictionDistanceBlocks=8192;config.predictionTrees=false;config.predictionStructures=false;
        var profile=new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),42L,-64,384,"noise","minecraft:overworld",123L);
        var sample=new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var sampledColumns=new java.util.concurrent.atomic.AtomicInteger();
        var sampler=new ClientTerrainSampler(42,profile) {
            @Override int initialTerrainCellAxis(int lod) { return PredictionWorkOrder.initialCellAxis(lod); }
            @Override public ClientColumnSample sample(int x,int z) { sampledColumns.incrementAndGet(); return sample; }
            @Override public ClientColumnSample sampleForLod(int x,int z,int spacing) { sampledColumns.incrementAndGet(); return sample; }
        };
        try(var manager=new PredictionTileManager(profile.levelKey(),sampler,
                new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,2),null)) {
            var child=new PredictionTileManager.PredictionTileKey(profile.levelKey(),1,0,3);
            var parent=new PredictionTileManager.PredictionTileKey(profile.levelKey(),0,0,4);
            var neighbor=new PredictionTileManager.PredictionTileKey(profile.levelKey(),1,0,4);
            var neighborLeaf=new PredictionTileManager.PredictionTileKey(profile.levelKey(),2,0,3);
            var ready=(Map<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile>)get(manager,"ready");
            for(var key:List.of(child,parent,neighbor)) ready.put(key,new PredictionTileManager.PredictionTile(
                    key,new int[0],new int[0],new ClientColumnSample[0],null,new PredictionDepthBound(64,64),0,1,8,
                    manager.layout().tileBlocks(key.lod())/8));
            ((Set<Object>)get(manager,"desiredKeys")).addAll(List.of(child,parent,neighbor,neighborLeaf));
            ((Set<Object>)get(manager,"terrainLeaves")).addAll(List.of(child,neighborLeaf));
            set(manager,"terrainTargets",Map.of(child,64,neighborLeaf,64));
            var enqueue=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileManager.PredictionTileKey.class,int.class,int.class,boolean.class);
            enqueue.setAccessible(true);
            for(int target:new int[]{16,32,64}) {
                enqueue.invoke(manager,child,0,0,false);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(ready.get(child).cellAxis()<target && System.nanoTime()<deadline) Thread.sleep(10);
                assertEquals(target,ready.get(child).cellAxis(),"Selected terrain must advance without waiting for the adjacent preview");
                assertEquals(8,ready.get(neighbor).cellAxis(),"fixture keeps the slow neighbor unfinished");
                assertTrue(ready.containsKey(parent),"parent fallback remains resident during refinement");
                while(((Set<?>)get(manager,"pending")).contains(child) && System.nanoTime()<deadline) Thread.sleep(1);
                assertFalse(((Set<?>)get(manager,"pending")).contains(child));
            }
            assertEquals(0,manager.failedTileCount());
            int completedSamples=sampledColumns.get();
            for(int i=0;i<100;i++) enqueue.invoke(manager,child,0,0,false);
            assertEquals(completedSamples,sampledColumns.get(),"completed terrain must stop consuming sampling work");
            assertFalse(((Set<?>)get(manager,"pending")).contains(child));
        } finally {config.predictionDistanceBlocks=distance;config.predictionTrees=trees;config.predictionStructures=structures;}
    }
    private static Object get(Object target,String name) throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
}
