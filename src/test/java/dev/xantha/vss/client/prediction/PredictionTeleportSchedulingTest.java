package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real admission and publication with deliberately blocked IO/CPU lanes. */
class PredictionTeleportSchedulingTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void flush() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void localMissingTerrainPrecedesNearPlantsAndRefinementWithoutAnAllHorizonBarrier() {
        var layout=VssLodLayout.of(65536,6,true,false);
        var near=key(2,0); var middle=key(16,0); var horizon=key(2,5);
        var targets=Map.of(near,64,middle,64,horizon,32);
        var input=input(layout,List.of(horizon,near,middle),Map.of(near,tile(layout,near,32)),Set.of(near),targets);
        var work=PredictionSchedulePreparation.build(input).work();
        assertEquals(List.of(middle,near,near,horizon),work.stream().map(PredictionSchedulePreparation.Request::key).toList());
        assertTrue(work.get(1).surface(),"Trees keep their ordinary local turn once nearby coverage has a first terrain");
    }

    @Test void boundedMiddleCoverageExistsWithoutAnyNativeNodeDemandAfterSignedTeleport() {
        for (var dimension : List.of(Level.OVERWORLD,Level.NETHER)) for (int horizon : new int[]{1024,8192,65536}) {
            var layout=VssLodLayout.of(horizon,6,true,false);
            double x=-12345.25,z=6789.5;
            var keys=PredictionPlanPreparation.nativeCoverage(dimension,x,z,layout,0);
            var bootstrap=PredictionPlanPreparation.bootstrap(dimension,x,z,layout,0);
            assertTrue(keys.containsAll(bootstrap));assertTrue(keys.size()-bootstrap.size()<=32);
            int level=Math.min(4,PredictionMediumCoverage.level(layout,0)),span=layout.tileBlocks(level);
            for (int angle=0;angle<360;angle+=10) for (double ratio:new double[]{0,.5,.999}) {
                double radius=Math.min(2048,horizon)*ratio;
                var key=new PredictionTileKey(dimension,(int)Math.floor((x+radius*Math.cos(Math.toRadians(angle)))/span),
                        (int)Math.floor((z+radius*Math.sin(Math.toRadians(angle)))/span),level);
                assertTrue(keys.contains(key),"Every part of the local band has explicit demand before native retries");
            }
        }
    }

    @Test void coldNativePlanPublishesMiddleTerrainWithoutAnyGpuDetailRequest() throws Exception {
        int previous=dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks=65536;
        try(var manager=manager(null,new AtomicInteger())) {
            set(manager,"unifiedVoxy",true);
            double x=-20000,z=20000;int span=manager.layout().tileBlocks(4);
            var middle=new PredictionTileKey(Level.OVERWORLD,(int)Math.floor((x+1024)/span),(int)Math.floor(z/span),4);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!residents(manager).containsKey(middle) && System.nanoTime()<deadline) {
                manager.tick(x,80,z,.01,null);Thread.sleep(2);
            }
            assertTrue(residents(manager).containsKey(middle),manager.surfaceDiagnostics());
            assertTrue(keys(manager,"voxyDetailTargets").isEmpty(),"Initial middle coverage does not depend on native node retries");
        } finally {dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionDistanceBlocks=previous;}
    }

    @Test void nativeDemandSortFindsMissingMiddleBeforeAnAlreadyResidentNearUpgrade() {
        var layout=VssLodLayout.of(65536,6,true,false);
        var near=key(2,0);var middle=key(16,0);var far=key(60,0);
        var input=new PredictionVoxyWorkPreparation.Input(layout,null,null,0,80,0,1000,-64,320,768,0,0,
                Set.of(near,middle,far),Set.of(),Set.of(),Set.of(),
                new RenderSnapshot(Level.OVERWORLD,layout,Map.of(near,tile(layout,near,32)),Map.of()),Map.of());
        assertEquals(List.of(middle,near,far),PredictionVoxyWorkPreparation.build(input).terrain());
    }

    @Test @SuppressWarnings("unchecked") void managerAdmitsMissingMiddleBeforeQueuedNearVegetation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var manager=manager(null,new AtomicInteger())) {
            set(manager,"unifiedVoxy",true);
            var executor=(ThreadPoolExecutor)get(manager,"executor");
            executor.execute(new PredictionTask(null,Integer.MIN_VALUE,0,()->{
                entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var near=key(2,0);var middle=key(16,0);
            residents(manager).put(near,tile(manager.layout(),near,64));
            set(manager,"surfaceDesired",Set.of(near));keys(manager,"desiredKeys").addAll(Set.of(near,middle));
            keys(manager,"voxyDetailTargets").add(middle);
            ((ArrayDeque<PredictionTileKey>)get(manager,"visibleSurfaceWork")).add(near);
            ((ArrayDeque<PredictionTileKey>)get(manager,"voxyDetailWork")).add(middle);
            invoke(manager,"serviceGenerationTurn",0,0,Long.MAX_VALUE);
            assertTrue(keys(manager,"pending").contains(middle));
            assertFalse(keys(manager,"pending").contains(near),"First admission fills the missing band instead of decorating covered terrain");
        } finally {release.countDown();}
    }

    @Test void oldInvisibleRegionRetiresWithoutGeneratingAnAncestorChain() throws Exception {
        var calls=new AtomicInteger();
        try(var manager=manager(null,calls)) {
            set(manager,"unifiedVoxy",true);
            var old=key(-100000,0);residents(manager).put(old,tile(manager.layout(),old,16));
            invoke(manager,"pruneReady",0,0);
            assertFalse(residents(manager).containsKey(old));
            assertTrue(keys(manager,"fallbackKeys").isEmpty());assertTrue(keys(manager,"desiredKeys").isEmpty());
            assertEquals(0,manager.pendingCount());assertEquals(0,calls.get());
        }
    }

    @Test void currentHorizonResidentRemainsAvailableWhileItsPlanChanges() throws Exception {
        try(var manager=manager(null,new AtomicInteger())) {
            set(manager,"unifiedVoxy",true);var visible=key(2,0);
            var retained=tile(manager.layout(),visible,16);residents(manager).put(visible,retained);
            invoke(manager,"pruneReady",0,0);
            assertSame(retained,residents(manager).get(visible));assertEquals(0,manager.pendingCount());
        }
    }

    @Test @SuppressWarnings("unchecked") void relocationDiscardsQueuedOldProbesButKeepsInFlightLeasesAndNewRequests() throws Exception {
        try(var cache=new PredictionDiskCache(directory,77);var gate=gate()) {
            var old=new ArrayList<PredictionDiskCache.Key>();for(int x=0;x<300;x++)old.add(PredictionDiskCache.Key.terrain(x,0,0));
            cache.probeTerrain(old);
            assertTrue(cache.terrainProbePending(old.get(299)));
            cache.resetTerrainProbes();
            assertFalse(cache.terrainProbePending(old.get(299)),"Queued probes from the old view must not consume the new view's wait");
            assertTrue(cache.terrainProbePending(old.get(0)),"Already submitted batch retains its lease and ownership");
            var current=PredictionDiskCache.Key.terrain(10000,0,0);cache.probeTerrain(List.of(current));
            assertTrue(cache.terrainProbePending(current));
            gate.close();awaitProbe(cache,current);
            assertFalse(cache.terrainProbePending(current));
        }
    }

    @Test @SuppressWarnings("unchecked") void stalledProbeCannotHoldMissingTerrainUntilDiskWriterUnblocks() throws Exception {
        var calls=new AtomicInteger();
        try(var cache=new PredictionDiskCache(directory,77);var gate=gate();var manager=manager(cache,calls)) {
            set(manager,"unifiedVoxy",true);var key=key(16,0);var disk=PredictionDiskCache.Key.terrain(16,0,0);
            keys(manager,"desiredKeys").add(key);keys(manager,"voxyDetailTargets").add(key);
            cache.probeTerrain(List.of(disk));
            invoke(manager,"enqueue",key,0,0,false);
            assertEquals(0,manager.pendingCount(),"A fresh header lookup still gets its short warm-cache head start");
            var probes=(Map<PredictionDiskCache.Key,Long>)get(cache,"pendingProbes");
            probes.put(disk,System.nanoTime()-TimeUnit.SECONDS.toNanos(1));
            invoke(manager,"enqueue",key,0,0,false);
            awaitTile(manager,key);
            assertTrue(calls.get()>0);assertTrue(cache.terrainProbePending(disk));
            assertEquals(1,gate.release.getCount(),"New terrain must publish while the serial writer is still blocked");
        }
    }

    @Test @SuppressWarnings("unchecked") void expiredProbeStillRestoresWarmPayloadBeforeSampling() throws Exception {
        var disk=PredictionDiskCache.Key.terrain(16,0,0);var samples=new ClientColumnSample[66*66];
        Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
        try(var writer=new PredictionDiskCache(directory,77);var lease=writer.lease(disk)) {
            assertTrue(writer.writeTerrain(lease,samples));
        }
        var calls=new AtomicInteger();
        try(var cache=new PredictionDiskCache(directory,77);var gate=gate();var manager=manager(cache,calls)) {
            set(manager,"unifiedVoxy",true);var key=key(16,0);
            keys(manager,"desiredKeys").add(key);keys(manager,"voxyDetailTargets").add(key);
            cache.probeTerrain(List.of(disk));
            ((Map<PredictionDiskCache.Key,Long>)get(cache,"pendingProbes")).put(disk,System.nanoTime()-TimeUnit.SECONDS.toNanos(1));
            invoke(manager,"enqueue",key,0,0,false);awaitTile(manager,key);
            assertEquals(0,calls.get(),"Wait expiry skips the optional header wait, never validated payload restoration");
            assertTrue(cache.terrainProbePending(disk));
        }
    }

    private static PredictionSchedulePreparation.Input input(VssLodLayout layout,List<PredictionTileKey> plan,
            Map<PredictionTileKey,PredictionTile> residents,Set<PredictionTileKey> surfaces,Map<PredictionTileKey,Integer> targets) {
        return new PredictionSchedulePreparation.Input(layout,null,null,0,80,0,1000,-64,320,0,plan,
                new RenderSnapshot(Level.OVERWORLD,layout,residents,Map.of()),Set.copyOf(plan),surfaces,Set.of(),
                Set.of(),Set.of(),Set.of(),targets,targets,Map.of(),Map.of(),PredictionMediumCoverage.EMPTY,false,true,false,false,
                Map.of(),Set.of(),Set.of(),Collections.nCopies(layout.levelCount(),8),null,Map.of(),Map.of(),Map.of(),System.nanoTime(),false);
    }
    static final class Gate implements AutoCloseable {
        final CountDownLatch release=new CountDownLatch(1);final Future<?> task;
        Gate() throws Exception {
            var field=PredictionDiskCache.class.getDeclaredField("COMMITS");field.setAccessible(true);
            var executor=(ExecutorService)field.get(null);var entered=new CountDownLatch(1);
            task=executor.submit(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
        }
        public void close() throws Exception {release.countDown();task.get(5,TimeUnit.SECONDS);}
    }
    static Gate gate() throws Exception {return new Gate();}
    static PredictionTileManager manager(PredictionDiskCache cache,AtomicInteger calls) {
        var sampler=new ClientTerrainSampler(PredictionTeleportLoadingTest.PROFILE.seed(),PredictionTeleportLoadingTest.PROFILE) {
            @Override public ClientColumnSample sampleForLod(int x,int z,int step){calls.incrementAndGet();return PredictionVoxelFixtures.sample(64);}
            @Override public ClientColumnSample sampleSurface(int x,int z){return sampleForLod(x,z,1);}
            @Override public ClientColumnSample samplePreview(int x,int z,int step){return sampleForLod(x,z,step);}
        };
        return new PredictionTileManager(Level.OVERWORLD,sampler,new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,
                0,()->Long.MAX_VALUE,System::nanoTime,1),cache,PredictionCpuBudget.fixed(1));
    }
    static PredictionTileKey key(int x,int lod){return new PredictionTileKey(Level.OVERWORLD,x,0,lod);}
    static PredictionTile tile(VssLodLayout layout,PredictionTileKey key,int axis) {
        int step=layout.tileBlocks(key.lod())/axis;
        return new PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],PredictionVoxelFixtures.flat(key,axis,step,64),
                new PredictionDepthBound(64,64),0,1,axis,step);
    }
    @SuppressWarnings("unchecked") static Set<PredictionTileKey> keys(Object m,String n)throws Exception{return(Set<PredictionTileKey>)get(m,n);}
    @SuppressWarnings("unchecked") static Map<PredictionTileKey,PredictionTile> residents(Object m)throws Exception{return(Map<PredictionTileKey,PredictionTile>)get(m,"ready");}
    static Object get(Object o,String n)throws Exception{return PredictionPlanningTestSupport.get(o,n);}
    static void set(Object o,String n,Object v)throws Exception{PredictionPlanningTestSupport.set(o,n,v);}
    static Object invoke(Object o,String name,Object...args)throws Exception {
        var method=Arrays.stream(o.getClass().getDeclaredMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==args.length).findFirst().orElseThrow();
        method.setAccessible(true);synchronized(o){return method.invoke(o,args);}
    }
    static void awaitTile(PredictionTileManager m,PredictionTileKey key)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!residents(m).containsKey(key)&&System.nanoTime()<deadline)Thread.sleep(2);
        assertTrue(residents(m).containsKey(key),m.surfaceDiagnostics());assertEquals(0,m.failedTileCount());
    }
    static void awaitProbe(PredictionDiskCache cache,PredictionDiskCache.Key key)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(cache.terrainProbePending(key)&&System.nanoTime()<deadline)Thread.sleep(2);
    }
}
