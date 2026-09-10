package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionMediumCoverageTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void groundAndHighAltitudeRefineCompletedRegionsWhileDistantMediumIsPending() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        for (int altitude : new int[]{80, 5000}) verifyPass(altitude);
    }

    @SuppressWarnings("unchecked")
    private void verifyPass(int altitude) throws Exception {
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 123L);
        var calls = new AtomicInteger();
        var distantEntered = new CountDownLatch(1);
        var releaseDistant = new CountDownLatch(1);
        var sample = new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var sampler = new ClientTerrainSampler(42, profile) {
            @Override int initialTerrainCellAxis(int lod) { return 8; }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {
                calls.incrementAndGet();
                if (x < -128) {
                    distantEntered.countDown();
                    try {
                        if (!releaseDistant.await(10,TimeUnit.SECONDS)) throw new AssertionError("local detail waited for distant sampling");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException();
                    }
                }
                return sample;
            }
        };
        try (var manager = new PredictionTileManager(profile.levelKey(),sampler,
                new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,2),null)) {
            manager.setWorkView(PredictionWorkView.of(32,altitude,32,1,0,0,70,16D/9));
            int lod = manager.layout().levelCount()-4;
            var near = new PredictionTileKey(profile.levelKey(),0,0,0);
            var localParent = new PredictionTileKey(profile.levelKey(),0,0,1);
            var localFront = new PredictionTileKey(profile.levelKey(),0,0,lod);
            var far = new PredictionTileKey(profile.levelKey(),-5,0,lod);
            var farParent = new PredictionTileKey(profile.levelKey(),-3,0,lod+1);
            var leaves = List.of(near,far);
            var desired = (Set<PredictionTileKey>)get(manager,"desiredKeys");
            desired.addAll(PredictionTileManager.withCoarseCoverage(leaves,manager.layout()));
            ((Set<PredictionTileKey>)get(manager,"terrainLeaves")).addAll(leaves);
            set(manager,"terrainTargets",Map.of(near,64,far,32));
            var ready = (Map<PredictionTileKey,PredictionTile>)get(manager,"ready");
            for (var key : List.of(near,localParent,localFront,far,farParent)) {
                int axis = key.equals(far) ? 8 : 32;
                ready.put(key,new PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],null,
                        new PredictionDepthBound(64,64),0,1,axis,manager.layout().tileBlocks(key.lod())/axis));
            }
            refresh(manager,leaves);
            assertEquals(true,get(manager,"mediumCoveragePending"));
            var allowed = PredictionTileManager.class.getDeclaredMethod("mediumWorkAllowed",PredictionTileKey.class,boolean.class);
            allowed.setAccessible(true);
            var localCoverage = ready.remove(localFront);
            assertEquals(false,allowed.invoke(manager,near,false),"initial local coverage still precedes detail");
            assertEquals(false,allowed.invoke(manager,near,true));
            ready.put(localFront,localCoverage);
            assertEquals(true,allowed.invoke(manager,near,true),"local surface admission must not wait for a distant region");
            try {
                enqueue(manager,far);
                assertTrue(distantEntered.await(3,TimeUnit.SECONDS));
                enqueue(manager,near);
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while (ready.get(near).cellAxis()<64 && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(64,ready.get(near).cellAxis(),"local detail must advance while distant medium is unfinished at altitude "+altitude);
                assertEquals(8,ready.get(far).cellAxis());
                assertEquals(true,get(manager,"mediumCoveragePending"));
            } finally { releaseDistant.countDown(); }
            idle(manager);
            assertEquals(16,ready.get(far).cellAxis());
            enqueue(manager,far); idle(manager);
            assertEquals(32,ready.get(far).cellAxis(),"distant coverage must still progress");
            refresh(manager,leaves);
            assertEquals(false,get(manager,"mediumCoveragePending"));
            enqueue(manager,near); idle(manager);
            assertEquals(64,ready.get(near).cellAxis());
            int completed = calls.get();
            enqueue(manager,near); enqueue(manager,far); idle(manager);
            assertEquals(completed,calls.get(),"completed coverage must not resample");

            ready.remove(far);
            ((Map<PredictionTileKey,Long>)get(manager,"failedAt")).put(farParent,System.nanoTime());
            refresh(manager,leaves);
            assertEquals(false,get(manager,"mediumCoveragePending"),"parent backoff must not freeze all detail");
            ((Map<?,?>)get(manager,"failedAt")).clear();
            refresh(manager,leaves);
            set(manager,"buildFocus",new VssLodFocus(32,32,1024,9000));
            assertEquals(true,allowed.invoke(manager,near,true),"explicit telescope target can bypass the wave");
        }
    }

    @Test void spatialPassHasBoundedSizeAndIncludesDistantCoveragePaths() {
        for(int horizon : new int[]{1024,8192,65536}) for(int y : new int[]{80,5000}) {
            var layout=VssLodLayout.of(horizon,6,true,false);
            var leaves=PredictionLodPlanner.plan(Level.OVERWORLD,32,y,32,layout,null,1300);
            var pass=PredictionMediumCoverage.plan(leaves,layout);
            assertTrue(pass.frontier().size()<=400,"medium pass must not expand into all fine leaves");
            assertTrue(pass.paths().containsAll(pass.frontier()));
            for(var leaf:leaves) assertTrue(pass.frontier().stream().anyMatch(parent ->
                    parent.lod()>=leaf.lod() && leaf.tileX()>>(parent.lod()-leaf.lod())==parent.tileX()
                            && leaf.tileZ()>>(parent.lod()-leaf.lod())==parent.tileZ()));
        }
    }

    private static Object get(Object o,String name)throws Exception { var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o); }
    private static void set(Object o,String name,Object value)throws Exception { var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value); }
    private static void refresh(PredictionTileManager m,List<PredictionTileKey> leaves)throws Exception { var f=m.getClass().getDeclaredMethod("refreshMediumCoverage",List.class);f.setAccessible(true);f.invoke(m,leaves); }
    private static void enqueue(PredictionTileManager m,PredictionTileKey key)throws Exception { var f=m.getClass().getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class);f.setAccessible(true);f.invoke(m,key,0,0,false); }
    private static void idle(PredictionTileManager m)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!((Set<?>)get(m,"pending")).isEmpty() && System.nanoTime()<until) Thread.sleep(5);
        assertTrue(((Set<?>)get(m,"pending")).isEmpty());
        assertEquals(0,m.failedTileCount());
    }
}
