package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionNearFirstSchedulingTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L,-64,384,"noise","minecraft:overworld",123L);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void nearTerrainAndPlantsBeatDistantMediumEvenWhileMoving() {
        var layout=VssLodLayout.of(65536,6,true,false);
        var near=key(2,0); var far=key(2,5);
        var resident=tile(near,32,layout);
        var targets=Map.of(near,64,far,32);
        var medium=new PredictionMediumCoverage(Set.of(far),Set.of(far));
        for (boolean moving:List.of(false,true)) {
            var input=input(layout,List.of(far,near),Map.of(near,resident),Set.of(near),targets,medium,moving,null,Map.of());
            var result=PredictionSchedulePreparation.build(input);
            assertEquals(near,result.work().get(0).key());
            assertTrue(result.work().get(0).surface(),"near plants can finish before another same-band refinement");
            assertEquals(List.of(near,near,far),result.work().stream().map(PredictionSchedulePreparation.Request::key).toList());
        }
    }

    @Test void warmCacheRestoresNearbyUpgradeBeforeMissingDistantCoarseTile() throws Exception {
        var layout=VssLodLayout.of(65536,6,true,false);
        var near=key(2,0); var far=key(2,5);
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint())) {
            write(cache,near); write(cache,far);
            var input=input(layout,List.of(far,near),Map.of(near,tile(near,16,layout)),Set.of(),
                    Map.of(near,64,far,64),PredictionMediumCoverage.EMPTY,false,cache,Map.of());
            assertEquals(List.of(near,far),PredictionSchedulePreparation.build(input).cached());
            // A visible region still precedes a nearer offscreen buffer.
            var bands=Map.of(near,PredictionWorkView.BUFFER,far,PredictionWorkView.VISIBLE);
            assertEquals(List.of(far,near),PredictionSchedulePreparation.build(input(layout,List.of(near,far),Map.of(),
                    Set.of(),Map.of(near,64,far,64),PredictionMediumCoverage.EMPTY,false,cache,bands)).cached());
        }
    }

    @Test @SuppressWarnings("unchecked")
    void coldNearRegionSelectsItsNecessaryParentBeforeUnrelatedDistantRoot() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new AtomicInteger();
        var cpu=new PredictionCpuBudget(()->1, System::nanoTime);
        try(var manager=manager(sampler(calls,entered,release),null,cpu)) {
            set(manager,"unifiedVoxy",true);
            var near=key(1,0); int top=manager.layout().levelCount()-1;
            var localRoot=key(0,top); var farRoot=key(2,top);
            var desired=(Set<PredictionTileKey>)get(manager,"desiredKeys");
            desired.addAll(PredictionLodPlanner.withCoarseCoverage(List.of(near,farRoot),manager.layout()));
            ((Set<PredictionTileKey>)get(manager,"terrainLeaves")).addAll(List.of(near,farRoot));
            set(manager,"terrainTargets",Map.of(near,64,farRoot,32));
            set(manager,"mediumCoverage",PredictionMediumCoverage.plan(List.of(near,farRoot),manager.layout()));
            set(manager,"mediumCoveragePending",true);
            var prepared=(ArrayDeque<PredictionSchedulePreparation.Request>)get(manager,"preparedWork");
            prepared.add(new PredictionSchedulePreparation.Request(near,false,0,0));
            prepared.add(new PredictionSchedulePreparation.Request(farRoot,false,0,1e12));
            invoke(manager,"serviceGenerationWork",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var pending=(Set<PredictionTileKey>)get(manager,"pending");
            assertTrue(pending.contains(localRoot),"resolve the near region's ancestor chain on demand");
            assertFalse(pending.contains(farRoot),"an unrelated coarse root must not win the first admission");
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!manager.renderSnapshot().tiles().containsKey(near) && System.nanoTime()<deadline) {
                invoke(manager,"serviceGenerationWork",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
                Thread.sleep(2);
            }
            assertNotNull(manager.renderSnapshot().tiles().get(near),manager.surfaceDiagnostics());
            await(manager);
        } finally { release.countDown(); }
    }

    @Test @SuppressWarnings("unchecked")
    void generationPreservesOrderAfterCpuRefusalAndGivesEveryFourthAdmissionAFairLaneTurn() throws Exception {
        var cpu=new PredictionCpuBudget(()->1, System::nanoTime);
        var held=cpu.tryAcquire(true);assertNotNull(held);
        var order=new CopyOnWriteArrayList<Integer>();var priorities=new CopyOnWriteArrayList<Integer>();
        var nearTerrain=new CopyOnWriteArrayList<Integer>();var fairTerrain=new CopyOnWriteArrayList<Integer>();
        var observed=ConcurrentHashMap.<Integer>newKeySet();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var firstSample=new java.util.concurrent.atomic.AtomicBoolean(true);
        var sampler=new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override public ClientColumnSample sample(int x,int z) {return sampleForLod(x,z,1);}
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {
                if (firstSample.getAndSet(false)) {entered.countDown();try {release.await(10,TimeUnit.SECONDS);}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}}
                return PredictionVoxelFixtures.sample(64);
            }
        };
        try(var manager=manager(sampler,null,cpu)) {
            // Record the worker's actual task; pending is an unordered concurrent set and
            // may also contain a queued task when sampling starts.
            var original=(ThreadPoolExecutor)get(manager,"executor");
            var recording=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new PriorityBlockingQueue<>(),original.getThreadFactory()) {
                @Override protected void beforeExecute(Thread thread,Runnable task) {
                    super.beforeExecute(thread,task);
                    try {
                        var key=(PredictionTileKey)get(task,"key");
                        if(key!=null && observed.add(key.tileX()))order.add(key.tileX());
                        int priority=(int)get(task,"priority");
                        priorities.add(priority);
                        if (!(boolean)get(task,"surface")) {
                            if(priority != (int)get(manager,"FAIR_WORK_PRIORITY")) nearTerrain.add(key.tileX());
                            else fairTerrain.add(key.tileX());
                        }
                    } catch(Exception failure) {throw new AssertionError(failure);}
                }
            };
            original.shutdownNow();set(manager,"executor",recording);
            set(manager,"unifiedVoxy",true);
            var keys=new ArrayList<PredictionTileKey>();for(int x=1;x<=8;x++)keys.add(key(x,0));
            ((Set<PredictionTileKey>)get(manager,"voxyDetailTargets")).addAll(keys);
            ((Set<PredictionTileKey>)get(manager,"desiredKeys")).addAll(keys);
            var work=(ArrayDeque<PredictionTileKey>)get(manager,"voxyDetailWork");work.addAll(keys);
            for(int i=0;i<3;i++)invoke(manager,"serviceGenerationWork",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
            assertEquals(keys,List.copyOf(work),"refused CPU permits must not rotate near work behind distant work");
            assertEquals(0,get(manager,"nearGenerationAdmissions"));
            held.close();
            invoke(manager,"serviceGenerationWork",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(manager.renderSnapshot().tiles().size()<keys.size() && System.nanoTime()<deadline) {
                // The live client retries after CPU admission backoff even when no worker is running.
                invoke(manager,"serviceGenerationWork",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
                Thread.sleep(2);
            }
            await(manager);
            // Plants consume admissions too, so a fair distant terrain turn can precede
            // the third ordinary terrain job. Ordinary terrain must still remain near-first.
            assertEquals(List.of(1,2,3),nearTerrain.subList(0,3));
            // Check admitted fair terrain, rather than a wall-clock race between
            // CPU-refused tail candidates. The deterministic lane fixture below
            // separately proves the exact distant key selected with free capacity.
            assertFalse(fairTerrain.isEmpty(),"Native terrain retains a fair lane turn");
            assertTrue(fairTerrain.get(0)>=5,"A native fair turn must reach the far end: "+order);
            // A terrain publication now starts its plant completion too. Fairness counts
            // admissions across all lanes, so every fourth task (including plants) is fair.
            int fairPriority=(int)get(manager,"FAIR_WORK_PRIORITY");
            assertTrue(priorities.size()>=8);
            for(int admission=3;admission<priorities.size();admission+=4)
                assertEquals(fairPriority,priorities.get(admission),"Fair admission "+(admission+1));
            assertEquals(8,manager.renderSnapshot().tiles().size(),manager.surfaceDiagnostics()
                    + "; terrainHints="+get(manager,"voxyDetailWork")+"; plants="+get(manager,"visibleSurfaceWork"));
        } finally {held.close();release.countDown();}
    }

    @Test @SuppressWarnings("unchecked")
    void fairTurnsVisitEveryLaneEvenWhenNearWorkAlwaysEndsInTheSameLane() throws Exception {
        var entered=new CountDownLatch(5);var release=new CountDownLatch(1);
        try(var manager=manager(sampler(new AtomicInteger(),null,null),null,PredictionCpuBudget.fixed(8))) {
            set(manager,"unifiedVoxy",true);
            var pool=(ThreadPoolExecutor)get(manager,"executor");
            pool.setMaximumPoolSize(3);pool.setCorePoolSize(3);
            for(int worker=0;worker<3;worker++) pool.execute(new PredictionTask(null,0,0D,()->{
                entered.countDown();try {release.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            }));
            // Cache restoration now precedes surface generation. Hold both lanes so
            // this test observes admission order rather than racing task completion.
            var cachePool=(ThreadPoolExecutor)get(manager,"cacheExecutor");
            assertEquals(2,cachePool.getCorePoolSize());
            for(int worker=0;worker<2;worker++)cachePool.execute(new PredictionTask(null,0,0D,()->{
                entered.countDown();try {release.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var surfaces=(ArrayDeque<PredictionTileKey>)get(manager,"visibleSurfaceWork");
            var desired=(Set<PredictionTileKey>)get(manager,"desiredKeys");
            for(int x=1;x<=3;x++) {
                var key=key(x,0);desired.add(key);surfaces.add(key);
                ((Map<PredictionTileKey,PredictionTile>)get(manager,"ready")).put(key,tile(key,64,manager.layout()));
                ((Set<PredictionTileKey>)get(manager,"surfaceDesired")).add(key);
            }
            var nativeNode=key(8,0);desired.add(nativeNode);
            ((Set<PredictionTileKey>)get(manager,"voxyDetailTargets")).add(nativeNode);
            ((ArrayDeque<PredictionTileKey>)get(manager,"voxyDetailWork")).add(nativeNode);
            var outer=key(4,manager.layout().levelCount()-1);desired.add(outer);
            ((ArrayDeque<PredictionSchedulePreparation.Request>)get(manager,"preparedWork"))
                    .add(new PredictionSchedulePreparation.Request(outer,false,100000,100000D));
            var expected=List.of(key(1,0),nativeNode,outer);
            for(int round=0;round<3;round++) {
                set(manager,"nearGenerationAdmissions",3);set(manager,"generationAdmissionLane",0);
                invoke(manager,"serviceGenerationTurn",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
                assertTrue(((Set<PredictionTileKey>)get(manager,"pending")).contains(expected.get(round)),
                        "Fair round "+round+" must reach the next lane even while nearby plants remain queued");
            }
            assertEquals(List.of(key(2,0),key(3,0)),List.copyOf(surfaces),
                    "A guaranteed plant turn must finish nearby trees before distant decoration");
        } finally {release.countDown();}
    }

    @Test @SuppressWarnings("unchecked")
    void admittedFairWorkKeepsItsTurnThroughDirtyParentQueueRefresh() throws Exception {
        var entered=new CountDownLatch(3);var release=new CountDownLatch(1);
        try(var manager=manager(sampler(new AtomicInteger(),null,null),null,PredictionCpuBudget.fixed(8))) {
            set(manager,"unifiedVoxy",true);
            var pool=(ThreadPoolExecutor)get(manager,"executor");
            pool.setMaximumPoolSize(3);pool.setCorePoolSize(3);
            for(int worker=0;worker<3;worker++)pool.execute(new PredictionTask(null,0,0D,()->{
                entered.countDown();try {release.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var desired=(Set<PredictionTileKey>)get(manager,"desiredKeys");
            var dirty=(Set<PredictionTileKey>)get(manager,"dirtyTiles");
            var targets=(Set<PredictionTileKey>)get(manager,"voxyDetailTargets");
            var work=(ArrayDeque<PredictionTileKey>)get(manager,"voxyDetailWork");
            var near=key(1,0);var far=key(8,0);
            desired.addAll(List.of(near,far));targets.addAll(desired);dirty.add(near);work.addAll(List.of(near,far));
            set(manager,"nearGenerationAdmissions",3);set(manager,"fairGenerationLane",1);
            invoke(manager,"serviceGenerationTurn",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
            invoke(manager,"serviceGenerationTurn",new Class[]{int.class,int.class,long.class},0,0,Long.MAX_VALUE);
            assertEquals(2,pool.getQueue().size());
            // Dirtying the fair key after admission must not erase its priority.
            dirty.add(far);
            invoke(manager,"refreshQueuedWork",new Class[]{});
            var next=(PredictionTask)pool.getQueue().peek();
            assertEquals(far,get(next,"key"),"Reserved far work must precede repeated dirty refreshes");
        }finally{release.countDown();}
    }

    @Test @SuppressWarnings("unchecked")
    void cacheUsesThreeNearbyAdmissionsThenOneDistantTurnWithoutCpuOrParent() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var cpu=new PredictionCpuBudget(()->1, System::nanoTime);
        try(var held=cpu.tryAcquire(true);var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());
            var manager=manager(sampler(calls,null,null),cache,cpu)) {
            assertNotNull(held); set(manager,"unifiedVoxy",true);
            var keys=new ArrayList<PredictionTileKey>();
            for(int x=1;x<=8;x++){var key=key(x,0);keys.add(key);write(cache,key);}
            ((Set<PredictionTileKey>)get(manager,"desiredKeys")).addAll(keys);
            var candidates=(ArrayDeque<PredictionTileKey>)get(manager,"cacheCandidates"); candidates.addAll(keys);
            var pool=(ThreadPoolExecutor)get(manager,"cacheExecutor");pool.setCorePoolSize(1);pool.setMaximumPoolSize(1);
            pool.execute(()-> {entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(int i=0;i<8;i++)invoke(manager,"restoreCached",new Class[]{int.class,int.class},0,0);
            assertEquals(Set.of(keys.get(0),keys.get(1),keys.get(2),keys.get(7)),get(manager,"cachePending"));
            release.countDown();await(manager);
            assertTrue(manager.renderSnapshot().tiles().keySet().containsAll(keys));
            assertEquals(0,calls.get(),"warm restoration never waits for generation or local parents");
        } finally {release.countDown();}
    }

    @Test @SuppressWarnings("unchecked")
    void anOlderBackgroundSortCannotEraseANewTerrainToPlantHandoff() throws Exception {
        try(var manager=manager(sampler(new AtomicInteger(),null,null),null,PredictionCpuBudget.fixed(1))) {
            set(manager,"unifiedVoxy",true);
            var key=key(1,0);
            ((Set<PredictionTileKey>)get(manager,"desiredKeys")).add(key);
            set(manager,"surfaceDesired",Set.of(key));
            var pipeline=new PredictionPlanningPipeline(Runnable::run,(dimension,now)->null);
            set(manager,"planning",pipeline);
            var before=new PredictionVoxyWorkPreparation.Input(manager.layout(),null,null,
                    0,80,0,1000,-64,384,512,0,0,Set.of(),Set.of(key),Set.of(),Set.of(),
                    manager.renderSnapshot(),Map.of());
            assertTrue(pipeline.details.request(before));
            var residents=(Map<PredictionTileKey,PredictionTile>)get(manager,"ready");
            residents.put(key,tile(key,16,manager.layout()));
            invoke(manager,"queueSurfaceCompletion",new Class[]{PredictionTileKey.class},key);
            invoke(manager,"publishVoxyWork",new Class[]{});
            assertTrue(((ArrayDeque<PredictionTileKey>)get(manager,"visibleSurfaceWork")).contains(key),
                    "The input predates terrain completion; its empty surface result must keep the new handoff");
            assertEquals(1,pipeline.details.commits());
            var stages=(Map<String,Object>)manager.demandSnapshot().get("surfacePipeline");
            assertEquals(1,stages.get("desired"));assertEquals(1,stages.get("terrainReady"));
            assertEquals(0,stages.get("completed"));assertEquals(1,stages.get("queuedHints"));
            // A later sort may retire a completed handoff; retaining it cannot resurrect finished work.
            ((Set<PredictionTileKey>)get(manager,"surfaceReady")).add(key);
            assertTrue(pipeline.details.request(before));
            invoke(manager,"publishVoxyWork",new Class[]{});
            assertTrue(((ArrayDeque<PredictionTileKey>)get(manager,"visibleSurfaceWork")).isEmpty());
        }
    }

    private static PredictionSchedulePreparation.Input input(VssLodLayout layout,List<PredictionTileKey> keys,
            Map<PredictionTileKey,PredictionTile> residents,Set<PredictionTileKey> surfaces,Map<PredictionTileKey,Integer> targets,
            PredictionMediumCoverage medium,boolean moving,PredictionDiskCache cache,Map<PredictionTileKey,Integer> bands) {
        return new PredictionSchedulePreparation.Input(layout,null,null,0,80,0,1000,-64,320,0,keys,
                new RenderSnapshot(Level.OVERWORLD,layout,residents,Map.of()),Set.copyOf(keys),surfaces,Set.of(),
                Set.of(),Set.of(),Set.of(),targets,targets,Map.of(),Map.of(),medium,!medium.paths().isEmpty(),true,false,moving,
                bands,Set.of(),Set.of(),Collections.nCopies(layout.levelCount(),8),cache,Map.of(),Map.of(),Map.of(),System.nanoTime(),false);
    }
    private static PredictionTileKey key(int x,int lod) {return new PredictionTileKey(Level.OVERWORLD,x,0,lod);}
    private static PredictionTile tile(PredictionTileKey key,int axis,VssLodLayout layout) {
        int step=layout.tileBlocks(key.lod())/axis;
        return new PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],PredictionVoxelFixtures.flat(key,axis,step,64),
                new PredictionDepthBound(64,64),0,1,axis,step);
    }
    private static void write(PredictionDiskCache cache,PredictionTileKey key) throws Exception {
        var samples=new ClientColumnSample[66*66];Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
        var diskKey=PredictionDiskCache.Key.terrain(key.tileX(),key.tileZ(),key.lod());
        try(var lease=cache.lease(diskKey)) {assertTrue(cache.writeTerrain(lease,samples));}
        cache.probeTerrain(List.of(diskKey));cache.flush();assertEquals(64,cache.cachedTerrainAxis(diskKey));
    }
    private static ClientTerrainSampler sampler(AtomicInteger calls,CountDownLatch entered,CountDownLatch release) {
        return new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {
                if(calls.getAndIncrement()==0 && entered!=null) {
                    entered.countDown();try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("sampling not released");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
                }
                return PredictionVoxelFixtures.sample(64);
            }
            @Override public ClientColumnSample samplePreview(int x,int z,int step){return sampleForLod(x,z,step);}
        };
    }
    private static PredictionTileManager manager(ClientTerrainSampler sampler,PredictionDiskCache cache,PredictionCpuBudget cpu) {
        return new PredictionTileManager(PROFILE.levelKey(),sampler,new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,
                0,()->Long.MAX_VALUE,System::nanoTime,1),cache,cpu);
    }
    private static Object get(Object owner,String name)throws Exception{return PredictionPlanningTestSupport.get(owner,name);}
    private static void set(Object owner,String name,Object value)throws Exception{PredictionPlanningTestSupport.set(owner,name,value);}
    private static void invoke(Object owner,String name,Class<?>[] types,Object... args)throws Exception {
        var method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);synchronized(owner){method.invoke(owner,args);}
    }
    private static void await(PredictionTileManager manager)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(manager.pendingCount()>0 && System.nanoTime()<deadline)Thread.sleep(5);
        assertEquals(0,manager.pendingCount());assertEquals(0,manager.failedTileCount(),manager.surfaceDiagnostics());
    }
}
