package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionVoxyLoadingIntegrationTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L,-64,384,"noise","minecraft:overworld",123L);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test @SuppressWarnings("unchecked")
    void rendererReloadRetainsInFlightVoxelBuildAndReleasesItsMemoryOnClose() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        var budget = new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1);
        var sampler = sampler(new AtomicInteger(), () -> {
            if (!once.compareAndSet(false,true)) return;
            entered.countDown();
            try { if (!resume.await(10,TimeUnit.SECONDS)) throw new AssertionError("Sampler was not resumed"); }
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException();
            }
        });
        try (var cache = new PredictionDiskCache(directory,PROFILE.fingerprint());
             var manager = new PredictionTileManager(PROFILE.levelKey(),sampler,budget,cache,
                     new PredictionCpuBudget(()->8,()->.1,()->.1,System::nanoTime,false))) {
            var key = new PredictionTileKey(PROFILE.levelKey(),-1,-1,manager.layout().levelCount()-1);
            setup(manager,key); enqueue(manager,key,false);
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var managersField=ClientPredictionState.class.getDeclaredField("MANAGERS");managersField.setAccessible(true);
            var managers=(Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,PredictionTileManager>)managersField.get(null);
            assertTrue(managers.isEmpty());managers.put(PROFILE.levelKey(),manager);
            try { ClientPredictionState.resourcesChanged(); }
            finally { managers.remove(PROFILE.levelKey()); }
            manager.connectVoxy();
            resume.countDown(); await(manager);
            assertFalse(manager.usesVoxyRendering()); assertTrue(manager.hasReadyTiles());
            checkTile(manager,key);
        } finally { resume.countDown(); }
        assertEquals(0,budget.usedBytes()); assertEquals(0,budget.activeBuildCount());
    }
    @Test @SuppressWarnings("unchecked")
    void nodeDemandCompletesColdAndWarmCoarseRegionsWithoutWaitingForParentOrDistantCoverage() throws Exception {
        int distance=VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.predictionDistanceBlocks=1024;
        var calls=new AtomicInteger();
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
        var parent=new PredictionTileKey(PROFILE.levelKey(),-1,-1,4);
        long position=VoxyPredictionBridge.position(2,-1,0,-1);
        try {
            for(int entry=0;entry<2;entry++) {
                var load=new AtomicInteger(10);var clock=new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
                var cpu=new PredictionCpuBudget(()->1,()->load.get()/100D,()->load.get()/100D,clock::get,false);
                try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());
                    var manager=new PredictionTileManager(PROFILE.levelKey(),sampler(calls),
                        new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),cache,cpu)) {
                    setup(manager,parent);enqueue(manager,parent,false);await(manager);
                    var source=(PredictionVoxySource)field("voxySource").get(manager);
                    var originalParent=manager.renderSnapshot().tiles().get(parent);
                    assertNotNull(originalParent);
                    load.set(99);clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
                    assertEquals(PredictionCpuBudget.ThrottleLevel.PAUSE,cpu.currentThrottle());
                    assertFalse(source.requestDetail(position,(x,z)->false));
                    // Neither the immediate parent nor medium coverage exists locally.
                    field("mediumCoveragePending").setBoolean(manager,true);
                    field("mediumCoverage").set(manager,PredictionMediumCoverage.EMPTY);
                    refreshDetails(manager);
                    assertTrue(((Set<PredictionTileKey>)field("desiredKeys").get(manager)).contains(key));
                    var initial=manager.demandSnapshot();
                    var pipeline=(Map<String,Object>)initial.get("detailPipeline");
                    var levels=(Map<Integer,Map<String,Object>>)pipeline.get("byLod");
                    assertEquals(1,levels.get(1).get("missing"));
                    assertEquals(1,pipeline.get("remaining"));
                    assertEquals(10,((Map<?,?>)initial.get("admission")).size());
                    // A plan publication must not cancel a live node demand between its clear/add phases.
                    ((Set<PredictionTileKey>)field("desiredKeys").get(manager)).remove(key);
                    var desired=PredictionTileManager.class.getDeclaredMethod("effectivelyDesired",PredictionTileKey.class);
                    desired.setAccessible(true);assertEquals(true,desired.invoke(manager,key));
                    invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},false);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(System.nanoTime()<deadline) {
                        invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());
                        var resident=manager.renderSnapshot().tiles().get(key);
                        if(resident!=null && resident.cellAxis()==64)break;
                        Thread.sleep(2);
                    }
                    await(manager);
                    var tile=manager.renderSnapshot().tiles().get(key);
                    assertNotNull(tile,manager.surfaceDiagnostics());
                    assertEquals(64,tile.cellAxis(),"Voxy demand must not stop at another 8/16/32-cell preview");
                    assertEquals(2,tile.spacingBlocks());assertNotNull(tile.data().voxyTile);
                    var finished=(Map<String,Object>)manager.demandSnapshot().get("detailPipeline");
                    assertEquals(0,finished.get("remaining"));
                    assertTrue(((List<?>)finished.get("oldestIncomplete")).isEmpty());
                    assertTrue(source.requestDetail(position,(x,z)->false));
                    assertSame(originalParent,manager.renderSnapshot().tiles().get(parent),"Parent coverage remains usable");
                    cache.flush();
                }
                PredictionCacheTestFiles.awaitBackgroundClose();
            }
        } finally {VSSClientConfig.CONFIG.predictionDistanceBlocks=distance;
            }
    }
    @Test void movingTwoKilometersThenStoppingCompletesNativeLeafDemandAndWarmReentry() throws Exception {
        var calls=new AtomicInteger();
        var key=new PredictionTileKey(PROFILE.levelKey(),32,0,0);
        long nativeLeaf=VoxyPredictionBridge.position(0,64,1,0);
        VoxyPredictionBridge.Mapper mapper=(state,biome)->1L<<27;
        for(int entry=0;entry<2;entry++) {
            int before=calls.get();
            try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());
                var manager=manager(sampler(calls),cache)) {
                field("unifiedVoxy").setBoolean(manager,true);
                field("cameraBlockX").setDouble(manager,2064);
                field("cameraBlockY").setDouble(manager,80);
                field("cameraBlockZ").setDouble(manager,16);
                var source=(PredictionVoxySource)field("voxySource").get(manager);
                source.window(2064,16,manager.layout().maxDistanceBlocks());
                var ancestor=new PredictionTileKey(PROFILE.levelKey(),4,0,3);
                source.put(PredictionVoxySource.build(ancestor,newPreview(),10,64,manager.sampler(),
                        PredictionVegetation.Tile.EMPTY,null));
                assertNotNull(source.section(nativeLeaf,mapper));
                // This native LOD 0 node has no subdivision callback, on either entry.
                source.requestMeshDetail(nativeLeaf,(x,z)->false);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(System.nanoTime()<deadline) {
                    invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},true);
                    invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());
                    var tile=manager.renderSnapshot().tiles().get(key);
                    if(tile!=null && tile.cellAxis()==64)break;
                    Thread.sleep(3);
                }
                await(manager);
                var tile=manager.renderSnapshot().tiles().get(key);
                assertNotNull(tile,manager.surfaceDiagnostics());assertEquals(64,tile.cellAxis());
                assertEquals(1,tile.spacingBlocks());
                assertEquals(0,((Number)source.diagnostics().get("meshDetailPending")).intValue());
                assertEquals(0,((Number)source.diagnostics().get("detailPending")).intValue());
                assertNotNull(source.section(nativeLeaf,mapper));cache.flush();
                if(entry==0)assertTrue(calls.get()>before);
                else assertEquals(before,calls.get(),"Warm native leaf demand restores complete samples without resampling");
            }
            PredictionCacheTestFiles.awaitBackgroundClose();
        }
    }
    @Test void warmPreviewIsDisplayedThenAdvancesToRequestedSpacingInsteadOfBeingRestoredForever() throws Exception {
        var calls=new AtomicInteger();var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
        long pos=VoxyPredictionBridge.position(2,-1,0,-1);
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(sampler(calls),cache)) {
            var samples=new ClientColumnSample[10*10];Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
            try(var lease=cache.lease(PredictionDiskCache.Key.terrain(-1,-1,1))) { assertTrue(cache.writeTerrain(lease,samples)); }
            field("unifiedVoxy").setBoolean(manager,true);
            var source=(PredictionVoxySource)field("voxySource").get(manager);
            source.put(PredictionVoxySource.build(key,samples,10,16,manager.sampler(),
                    PredictionVegetation.Tile.EMPTY,null));
            assertFalse(source.requestDetail(pos,(x,z)->false));
            refreshDetails(manager);
            cache.flush(); // Wait for the asynchronous header probe before servicing this isolated planner turn.
            invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());await(manager);
            assertEquals(8,manager.renderSnapshot().tiles().get(key).cellAxis());assertEquals(0,calls.get());
            assertFalse(source.requestDetail(pos,(x,z)->false),"Cache presence cannot claim final precision");
            refreshDetails(manager);
            invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());await(manager);
            assertEquals(64,manager.renderSnapshot().tiles().get(key).cellAxis());assertTrue(calls.get()>0);
            assertTrue(source.requestDetail(pos,(x,z)->false));
        } finally {}
    }
    @Test @SuppressWarnings("unchecked")
    void nodeRefinementGeneratesFullPlantsWithoutOrdinarySurfaceDemandAndRestoresOnReentry() throws Exception {
        var calls=new AtomicInteger();
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
        var position=VoxyPredictionBridge.position(2,-1,0,-1);
        List<PredictionVoxySource.Box> firstPlants=null;
        try {
            for(int entry=0;entry<2;entry++) {
                int before=calls.get();
                try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());
                    var manager=manager(vegetatedSampler(calls),cache)) {
                    field("unifiedVoxy").setBoolean(manager,true);
                    field("surfaceRadius").setInt(manager,1024);
                    field("mediumCoveragePending").setBoolean(manager,true);
                    var source=(PredictionVoxySource)field("voxySource").get(manager);
                    var preview=new ClientColumnSample[100]; Arrays.fill(preview,PredictionVoxelFixtures.sample(64));
                    source.put(PredictionVoxySource.build(key,preview,10,16,manager.sampler(),
                            PredictionVegetation.Tile.EMPTY,null));
                    assertFalse(source.requestDetail(position,(x,z)->false));
                    if(entry>0) { cache.probeTerrain(List.of(PredictionDiskCache.Key.terrain(-1,-1,1)));cache.flush(); }
                    var surfaces=(Set<PredictionTileKey>)field("surfaceReady").get(manager);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    while(!surfaces.contains(key) && System.nanoTime()<deadline) {
                        invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},true);
                        invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());
                        Thread.sleep(5);
                    }
                    await(manager);
                    assertTrue(((Set<?>)field("surfaceDesired").get(manager)).isEmpty(),"Only Voxy requested this tile");
                    assertTrue(surfaces.contains(key),manager.surfaceDiagnostics());
                    var tile=manager.renderSnapshot().tiles().get(key);
                    assertNotNull(tile); assertEquals(64,tile.cellAxis());
                    assertTrue(tile.data().voxyTile.surfaceComplete());
                    var plants=tile.data().voxyTile.features();assertFalse(plants.isEmpty());
                    assertTrue(source.requestDetail(position,(x,z)->false));
                    if(entry==0) { firstPlants=plants;assertTrue(calls.get()>before); }
                    else { assertEquals(firstPlants,plants);assertTrue(calls.get()-before<before,"Warm entry reuses persisted decoration sources; a bounded write queue may omit some"); }
                    cache.flush();
                }
                PredictionCacheTestFiles.awaitBackgroundClose();
            }
        } finally {  }
    }

    @Test @SuppressWarnings("unchecked")
    void currentVoxySurfaceCompletesDuringSustainedCpuPressureWithoutWaitingForTheDetailTrickle() throws Exception {
        var calls=new AtomicInteger();var clock=new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var cpu=new PredictionCpuBudget(()->1,()->.99,()->.99,clock::get,false);
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());
            var manager=new PredictionTileManager(PROFILE.levelKey(),vegetatedSampler(calls),
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),cache,cpu)) {
            field("unifiedVoxy").setBoolean(manager,true);field("surfaceRadius").setInt(manager,1024);
            var source=(PredictionVoxySource)field("voxySource").get(manager);
            var preview=new ClientColumnSample[100];Arrays.fill(preview,PredictionVoxelFixtures.sample(64));
            source.put(PredictionVoxySource.build(key,preview,10,16,manager.sampler(),PredictionVegetation.Tile.EMPTY,null));
            assertFalse(source.requestDetail(VoxyPredictionBridge.position(2,-1,0,-1),(x,z)->false));
            assertEquals(PredictionCpuBudget.ThrottleLevel.PAUSE,cpu.currentThrottle());
            assertFalse(cpu.allowsDetail(),"The fake clock leaves the ordinary detail trickle closed throughout this test");
            var surfaces=(Set<PredictionTileKey>)field("surfaceReady").get(manager);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!surfaces.contains(key)&&System.nanoTime()<deadline){
                invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},true);
                invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());Thread.sleep(5);
            }
            await(manager);assertTrue(surfaces.contains(key),manager.surfaceDiagnostics());
            assertTrue(manager.renderSnapshot().tiles().get(key).data().voxyTile.surfaceComplete());assertTrue(calls.get()>0);
            assertFalse(cpu.allowsDetail(),"Visible completion must not open the unrelated ordinary-detail lane");
        } finally {}
    }

    @Test @SuppressWarnings("unchecked")
    void queuedVisibleSurfaceKeepsItsPriorityWhenOrdinaryScheduleHintsRefresh() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var key=new PredictionTileKey(PROFILE.levelKey(),2,2,1);
        try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(vegetatedSampler(new AtomicInteger()),cache)) {
            setup(manager,key);field("surfaceRadius").setInt(manager,0);((Set<PredictionTileKey>)field("voxyDetailTargets").get(manager)).add(key);
            enqueue(manager,key,false);await(manager);assertNotNull(manager.renderSnapshot().tiles().get(key));
            ((Set<PredictionTileKey>)field("surfaceDesired").get(manager)).add(key);
            field("scheduledHints").set(manager,Map.of(key,new PredictionSchedulePreparation.Hint(100,999999,123D)));
            var executor=(java.util.concurrent.ThreadPoolExecutor)field("executor").get(manager);
            executor.execute(new PredictionTileManager.PredictionTask(null,0,0D,()->{
                entered.countDown();try{release.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            invoke(manager,"enqueue",new Class[]{PredictionTileKey.class,int.class,int.class,boolean.class,boolean.class},key,0,0,true,false);
            var queued=(PredictionTileManager.PredictionTask)executor.getQueue().peek();
            assertNotNull(queued);var far=new PredictionTileKey(PROFILE.levelKey(),40,40,1);
            var terrain=new PredictionTileManager.PredictionTask(far,
                    PredictionWorkOrder.priority(far,manager.layout(),4000D*4000,0,false,null),4000D*4000,()->{});
            assertTrue(queued.compareTo(terrain)<0);
            invoke(manager,"refreshQueuedWork",new Class[0]);
            var refreshed=(PredictionTileManager.PredictionTask)executor.getQueue().peek();
            assertNotNull(refreshed);assertTrue(refreshed.compareTo(terrain)<0,"Queue refresh must preserve nearby completion ahead of distant work");
            assertEquals(0,refreshed.compareTo(queued),"Stale ordinary hints cannot demote queued visible plants");
            release.countDown();await(manager);
            assertTrue(((Set<PredictionTileKey>)field("surfaceReady").get(manager)).contains(key));
        } finally {release.countDown();}
    }


    @Test void warmNodeOnlyDemandRestoresWhileEveryCpuSlotIsOccupied() throws Exception {
        try {
            for(int slots:List.of(1,2)) {
                var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
                var calls=new AtomicInteger();
                var cpu=new PredictionCpuBudget(()->slots*4,()->.99,()->.99,System::nanoTime,false);
                var leases=new ArrayList<PredictionCpuBudget.Lease>();
                try(var cache=new PredictionDiskCache(directory.resolve("warm-"+slots),PROFILE.fingerprint());
                    var manager=new PredictionTileManager(PROFILE.levelKey(),sampler(calls),
                        new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),cache,cpu)) {
                    var samples=new ClientColumnSample[66*66];Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
                    var diskKey=PredictionDiskCache.Key.terrain(-1,-1,1);
                    try(var lease=cache.lease(diskKey)){assertTrue(cache.writeTerrain(lease,samples));}cache.flush();
                    for(int slot=0;slot<slots;slot++){var lease=cpu.tryAcquire(true);assertNotNull(lease);leases.add(lease);}
                    field("unifiedVoxy").setBoolean(manager,true);
                    var source=(PredictionVoxySource)field("voxySource").get(manager);
                    source.put(PredictionVoxySource.build(key,newPreview(),10,16,manager.sampler(),
                            PredictionVegetation.Tile.EMPTY,null));
                    assertFalse(source.requestDetail(VoxyPredictionBridge.position(2,-1,0,-1),(x,z)->false));
                    refreshDetails(manager);
                    assertTrue(((List<?>)field("scheduledPlan").get(manager)).isEmpty(),"Demand is exclusively from Voxy");
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(cache.terrainProbePending(diskKey) && System.nanoTime()<deadline)Thread.sleep(2);
                    assertEquals(64,cache.cachedTerrainAxis(diskKey));
                    do {invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());Thread.sleep(2);}
                    while(manager.renderSnapshot().tiles().get(key)==null && System.nanoTime()<deadline);
                    await(manager);
                    assertNotNull(manager.renderSnapshot().tiles().get(key),manager.surfaceDiagnostics());
                    assertEquals(64,manager.renderSnapshot().tiles().get(key).cellAxis());
                    assertEquals(0,calls.get(),"Disk restoration must not acquire or wait for a native-generation CPU slot");
                    assertTrue(source.requestDetail(VoxyPredictionBridge.position(2,-1,0,-1),(x,z)->false));
                } finally {for(var lease:leases)lease.close();}
            }
        } finally {}
    }

    @Test @SuppressWarnings("unchecked")
    void middleAndOuterCoverageProgressDuringContinuousNativeNodeDemandWithOneOrTwoCpuSlots() throws Exception {
        try {
            for(int slots:List.of(1,2)) {
                var blocked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
                var once=new java.util.concurrent.atomic.AtomicBoolean();var calls=new AtomicInteger();
                var cpu=new PredictionCpuBudget(()->slots*4,()->.99,()->.99,System::nanoTime,false);
                try(var cache=new PredictionDiskCache(directory.resolve("fair-"+slots),PROFILE.fingerprint());
                    var manager=new PredictionTileManager(PROFILE.levelKey(),sampler(calls,()->{
                        if(once.compareAndSet(false,true)) {blocked.countDown();
                            try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Build not released");}
                            catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}}
                    }),new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),cache,cpu)) {
                    var pool=(java.util.concurrent.ThreadPoolExecutor)field("executor").get(manager);
                    pool.setMaximumPoolSize(Math.max(slots,pool.getCorePoolSize()));pool.setCorePoolSize(slots);pool.setMaximumPoolSize(slots);
                    var outer=new PredictionTileKey(PROFILE.levelKey(),-1,-1,manager.layout().levelCount()-1);
                    setup(manager,outer);
                    field("mediumCoverage").set(manager,new PredictionMediumCoverage(Set.of(outer),Set.of(outer)));
                    field("mediumCoveragePending").setBoolean(manager,true);
                    var source=(PredictionVoxySource)field("voxySource").get(manager);
                    var coarse=new PredictionTileKey(PROFILE.levelKey(),0,0,8);
                    source.put(PredictionVoxySource.build(coarse,newPreview(),10,2048,manager.sampler(),
                            PredictionVegetation.Tile.EMPTY,null));
                    for(int x=2;x<10;x++)assertFalse(source.requestDetail(VoxyPredictionBridge.position(2,x,0,0),(a,b)->false));
                    invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},true);
                    var prepared=(java.util.ArrayDeque<PredictionSchedulePreparation.Request>)field("preparedWork").get(manager);
                    prepared.add(new PredictionSchedulePreparation.Request(outer,false,10_000,0));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                    while(blocked.getCount()!=0 && System.nanoTime()<deadline) {
                        invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());Thread.sleep(2);
                    }
                    assertEquals(0,blocked.getCount());
                    release.countDown();
                    // Completion must arbitrate the waiting ordinary request itself;
                    // another tick that starts by feeding Voxy must not be necessary.
                    while(!manager.renderSnapshot().tiles().containsKey(outer) && System.nanoTime()<deadline)Thread.sleep(2);
                    assertTrue(manager.renderSnapshot().tiles().containsKey(outer),manager.surfaceDiagnostics());
                    long finishedNodes=manager.renderSnapshot().tiles().keySet().stream().filter(k->k.lod()==1).count();
                    assertTrue(finishedNodes<8,"Outer coverage must complete before all continuous node work drains");
                    assertEquals(PredictionCpuBudget.ThrottleLevel.PAUSE,cpu.currentThrottle());
                    await(manager);
                } finally {release.countDown();}
            }
        } finally {}
    }
    private static ClientColumnSample[] newPreview() {
        var samples=new ClientColumnSample[100];Arrays.fill(samples,PredictionVoxelFixtures.sample(64));return samples;
    }

    private static Object invoke(PredictionTileManager manager,String name,Class<?>[] types,Object... values) throws Exception {
        var method=PredictionTileManager.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(manager,values);
    }

    @Test @SuppressWarnings("unchecked")
    void coarseTerrainPublicationKeepsCachedIndividualLeavesLogsAndSmallPlants() throws Exception {
        for (int lod : new int[]{1, 2}) {
            var sampled = new AtomicInteger();
            try (var manager = manager(sampler(sampled), null)) {
                var key = new PredictionTileKey(PROFILE.levelKey(), 0, 0, lod);
                // A fresh ordinary leaf runs after its parent coverage is available.
                var parent = PredictionVoxelFixtures.tile(0, 0, lod + 1, manager.layout());
                ((Map<PredictionTileKey, PredictionTileManager.PredictionTile>) field("ready").get(manager)).put(parent.key(), parent);
                setup(manager, key);
                var vegetation = (PredictionVegetation) field("vegetation").get(manager);
                var chunksField = PredictionVegetation.class.getDeclaredField("chunks"); chunksField.setAccessible(true);
                var chunks = (Map<Long, Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>>) chunksField.get(vegetation);
                chunks.put(0L, Map.of(
                        new net.minecraft.core.BlockPos(1, 64, 1), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState(),
                        new net.minecraft.core.BlockPos(2, 65, 1), net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState(),
                        new net.minecraft.core.BlockPos(3, 64, 1), net.minecraft.world.level.block.Blocks.FERN.defaultBlockState()));
                enqueue(manager, key, false); await(manager);
                var tile = manager.renderSnapshot().tiles().get(key);
                assertNotNull(tile, manager.surfaceDiagnostics()); assertTrue(tile.spacingBlocks() >= 4);
                var plants = tile.data().voxyTile.features();
                assertEquals(3, plants.size(), "Coarse terrain must retain every cached plant without running features");
                assertTrue(plants.stream().allMatch(b -> b.maxX() - b.minX() == 1
                        && b.maxY() - b.minY() == 1 && b.maxZ() - b.minZ() == 1),
                        "Only Voxy may simplify the complete vegetation voxels");
                assertTrue(plants.stream().anyMatch(b -> b.state().is(net.minecraft.world.level.block.Blocks.FERN)));
            }
        }
    }

    @Test void productionBuildPublishesVoxelsAndWarmSecondEntryRestoresWithoutResamplingOrGpuPayload() throws Exception {
        int distance=VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.predictionDistanceBlocks=1024;
        var samples=new AtomicInteger();
        try {
            var sampler=sampler(samples);
            PredictionTileKey key;
            try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint()); var manager=manager(sampler,cache)) {
                key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,manager.layout().levelCount()-1);
                setup(manager,key); enqueue(manager,key,false); await(manager);
                assertEquals(1,manager.builtTileCount(),manager.surfaceDiagnostics());
                checkTile(manager,key);
                cache.flush();
            }
            PredictionCacheTestFiles.awaitBackgroundClose();
            int original=samples.get(); assertTrue(original>0);
            try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint()); var manager=manager(sampler(samples),cache)) {
                setup(manager,key);
                cache.probeTerrain(List.of(PredictionDiskCache.Key.terrain(key.tileX(),key.tileZ(),key.lod()))); cache.flush();
                assertTrue(cache.cachedTerrainAxis(PredictionDiskCache.Key.terrain(key.tileX(),key.tileZ(),key.lod()))>0);
                enqueue(manager,key,true); await(manager);
                assertEquals(1,manager.builtTileCount(),manager.surfaceDiagnostics());
                assertEquals(original,samples.get(),"Warm voxel restore cannot resample noise");
                checkTile(manager,key);
                // A temporarily unavailable renderer retains already restored voxel records.
                manager.connectVoxy(); assertFalse(manager.usesVoxyRendering()); assertTrue(manager.hasReadyTiles());
            }
        } finally {
            VSSClientConfig.CONFIG.predictionDistanceBlocks=distance;
        }
    }

    @Test @SuppressWarnings("unchecked")
    void productionSurfaceContinuesBeforeFinalTerrainAndWarmReentryRestoresPlants() throws Exception {
        var calls=new AtomicInteger();
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,0);
        try {
            for(int entry=0;entry<2;entry++) {
                int before=calls.get();
                try(var cache=new PredictionDiskCache(directory,PROFILE.fingerprint());
                    var manager=manager(vegetatedSampler(calls),cache)) {
                    setup(manager,key);
                    ((Set<PredictionTileKey>)field("surfaceDesired").get(manager)).add(key);
                    if(entry==0) {
                        var samples=new ClientColumnSample[18*18]; Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
                        var previewMesh=PredictionVoxelData.forVoxy(PredictionVoxySource.build(key,samples,18,4,manager.sampler(),
                                PredictionVegetation.Tile.EMPTY,null));
                        var preview=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                                previewMesh,new PredictionDepthBound(64,64),0,1,16,4);
                        ((Map<PredictionTileKey,PredictionTileManager.PredictionTile>)field("ready").get(manager)).put(key,preview);
                    }
                    if(entry>0) { cache.probeTerrain(List.of(PredictionDiskCache.Key.terrain(-1,-1,0))); cache.flush(); }
                    enqueue(manager,key,entry>0);
                    var surfaces=(Set<PredictionTileKey>)field("surfaceReady").get(manager);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    while(!surfaces.contains(key) && System.nanoTime()<deadline) {
                        invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());
                        Thread.sleep(5);
                    }
                    assertTrue(surfaces.contains(key),manager.surfaceDiagnostics()); await(manager);
                    var tile=manager.readyTiles().iterator().next();
                    assertTrue(tile.data().voxyTile.surfaceComplete());
                    assertEquals(32,tile.cellAxis(),"Plants cannot wait for the final 64-cell terrain grid");
                    assertEquals(2,tile.spacingBlocks());
                    assertNotNull(tile.data().voxyTile); assertFalse(tile.data().voxyTile.features().isEmpty());
                    var source=(PredictionVoxySource)field("voxySource").get(manager);
                    long[] data=source.section(VoxyPredictionBridge.position(0,-2,2,-2),
                            (s,b)->s.is(net.minecraft.world.level.block.Blocks.OAK_LOG)?7L<<27:1L<<27);
                    assertNotNull(data); assertTrue(Arrays.stream(data).anyMatch(v->v==(7L<<27)),
                            "Generated world-coordinate vegetation must reach Voxy, including negative tile coordinates");
                    cache.flush();
                    if(entry==0) assertTrue(calls.get()>before); else assertEquals(before,calls.get(),"Warm surface cache must not replay features");
                    verifyResourceReloadRetention(manager,tile,source,calls);
                }
                PredictionCacheTestFiles.awaitBackgroundClose();
            }
        } finally {   }
    }

    @Test @SuppressWarnings("unchecked")
    void voxelSurfaceBuildReusesTerrainAndSubsequentRefinementRetainsItsCompletePlants() throws Exception {
        var calls=new AtomicInteger(); var sampled=new AtomicInteger();
        var decorated=vegetatedSampler(calls);
        var sampler=new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override public ClientColumnSample sample(int x,int z){sampled.incrementAndGet();return PredictionVoxelFixtures.sample(64);}
            @Override public ClientColumnSample sampleForLod(int x,int z,int step){return sample(x,z);}
            @Override public ClientColumnSample samplePreview(int x,int z,int step){return sample(x,z);}
            @Override public ClientColumnSample sampleSurface(int x,int z){return decorated.sampleSurface(x,z);}
            @Override ClientTerrainSampler decorationContext(){return decorated;}
        };
        var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,0);
        try(var manager=manager(sampler,null)) {
            setup(manager,key);
            var grid=new ClientColumnSample[18*18]; Arrays.fill(grid,PredictionVoxelFixtures.sample(64));
            var previewMesh=PredictionVoxelData.forVoxy(PredictionVoxySource.build(key,grid,18,4,sampler,
                    PredictionVegetation.Tile.EMPTY,null));
            var preview=new PredictionTileManager.PredictionTile(key,new int[17*17],new int[17*17],
                    PredictionTileManager.cropMargin(grid,18,17),previewMesh,new PredictionDepthBound(64,64),0,1,16,4);
            ((Map<PredictionTileKey,PredictionTileManager.PredictionTile>)field("ready").get(manager)).put(key,preview);
            enqueue(manager,key,false); await(manager);
            var before=manager.readyTiles().iterator().next();
            assertEquals(32,before.cellAxis());
            var surfaces=(Set<PredictionTileKey>)field("surfaceReady").get(manager);
            ((Set<PredictionTileKey>)field("surfaceDesired").get(manager)).add(key);
            assertTrue(PredictionSchedulePreparation.build(scheduleInput(manager,key)).work().stream().anyMatch(r->r.surface()),
                    "The scheduler must admit plants on a voxel tile before its terrain grid reaches 64");
            int terrainCalls=sampled.get();
            var method=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class,boolean.class);
            method.setAccessible(true); method.invoke(manager,key,0,0,true,false); await(manager);
            assertTrue(surfaces.contains(key),manager.surfaceDiagnostics());
            var surface=manager.readyTiles().iterator().next();
            assertSame(before.samples(),surface.samples()); assertSame(before.data().voxyTile.columns(),surface.data().voxyTile.columns());
            assertEquals(before.terrainRevision(),surface.terrainRevision());
            assertEquals(terrainCalls,sampled.get(),"A decoration job must not resample the Rust/Java terrain grid");
            assertTrue(calls.get()>0); assertTrue(surface.data().voxyTile.surfaceComplete());
            var features=surface.data().voxyTile.features();
            assertFalse(features.isEmpty());
            var schedule=PredictionSchedulePreparation.build(scheduleInput(manager,key));
            assertTrue(schedule.work().stream().noneMatch(r->r.surface()),"Completed surface must not regenerate");
            for(String name:List.of("terrainTargets","ordinaryTargets")) field(name).set(manager,Map.of(key,64));
            enqueue(manager,key,false); await(manager);
            var refined=manager.readyTiles().iterator().next();
            assertEquals(64,refined.cellAxis()); assertTrue(refined.data().voxyTile.surfaceComplete());
            assertEquals(features,refined.data().voxyTile.features()); assertTrue(surfaces.contains(key));
            ((Set<PredictionTileKey>)field("surfaceDesired").get(manager)).remove(key);
            // A new real-terrain capture refreshes ground, not the placement inputs.
            var vegetation=(PredictionVegetation)field("vegetation").get(manager);
            var chunks=PredictionVegetation.class.getDeclaredField("chunks"); chunks.setAccessible(true);
            ((Map<?,?>)chunks.get(vegetation)).clear(); // placement LRU eviction is not evidence of empty plants
            manager.capturedTerrainChanged(-1,-1);
            enqueue(manager,key,false); await(manager);
            var refreshed=manager.readyTiles().iterator().next();
            assertTrue(refreshed.data().voxyTile.surfaceComplete(),"Ground refresh must preserve completed plants");
            assertEquals(features,refreshed.data().voxyTile.features());
            assertTrue(refreshed.depthBound().maxY()>=surface.depthBound().maxY());
            // Actual content invalidation must still remove the old surface.
            manager.invalidate(-1,-1);
            enqueue(manager,key,false); await(manager);
            assertFalse(manager.readyTiles().iterator().next().data().voxyTile.surfaceComplete());
            assertTrue(manager.readyTiles().iterator().next().data().voxyTile.features().isEmpty());
        } finally {}
    }

    @Test @SuppressWarnings("unchecked")
    void nativeVisibleBoxPreviewCompletesBeforeTerrainRefinementOutsideStartupRadius() throws Exception {
        try {
            for(int lod : new int[]{1,2}) {
                var generated=new AtomicInteger(); var sampled=new AtomicInteger();
                var decorated=vegetatedSampler(generated);
                var terrain=new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
                    @Override public ClientColumnSample sample(int x,int z){sampled.incrementAndGet();return PredictionVoxelFixtures.sample(64);}
                    @Override public ClientColumnSample sampleForLod(int x,int z,int step){return sample(x,z);}
                    @Override public ClientColumnSample samplePreview(int x,int z,int step){return sample(x,z);}
                    @Override public ClientColumnSample sampleSurface(int x,int z){return decorated.sampleSurface(x,z);}
                    @Override ClientTerrainSampler decorationContext(){return decorated;}
                };
                try(var manager=manager(terrain,null)) {
                    field("unifiedVoxy").setBoolean(manager,true);
                    field("surfaceRadius").setInt(manager,128);
                    field("cameraBlockY").setDouble(manager,80);
                    field("schedulingPixels").setDouble(manager,1000);
                    manager.setRenderWorkView(PredictionWorkView.of(0,80,0,1,0,0,70,16D/9));
                    var key=new PredictionTileKey(PROFILE.levelKey(),4,0,lod);
                    int span=manager.layout().tileBlocks(lod), step=8, axis=span/step, gridSize=axis+2;
                    var grid=new ClientColumnSample[gridSize*gridSize];Arrays.fill(grid,PredictionVoxelFixtures.sample(64));
                    var base=PredictionVoxySource.build(key,grid,gridSize,step,terrain,PredictionVegetation.Tile.EMPTY,null);
                    assertTrue(base.features().isEmpty(), "Coarse terrain must not invent vegetation previews");
                    var preview=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                            PredictionVoxelData.forVoxy(base),new PredictionDepthBound(64,64),0,1,axis,step);
                    ((Map<PredictionTileKey,PredictionTileManager.PredictionTile>)field("ready").get(manager)).put(key,preview);
                    var source=(PredictionVoxySource)field("voxySource").get(manager);source.put(base);
                    assertFalse(source.requestDetail(VoxyPredictionBridge.position(lod+1,4,0,0),(x,z)->false));
                    refreshDetails(manager);
                    assertTrue(((Set<?>)field("surfaceDesired").get(manager)).isEmpty());
                    assertEquals(true,invoke(manager,"surfaceBuildReady",new Class[]{PredictionTileKey.class},key),
                            "Visible native plants must not wait for terrain LOD <= 1 or the startup radius");
                    // Inspect the independently completed surface before automatic worker
                    // refill also services the still-valid native terrain refinement.
                    ((ArrayDeque<?>)field("voxyDetailWork").get(manager)).clear();
                    assertEquals(true,invoke(manager,"serviceVisibleSurfaces",new Class[]{int.class,int.class,long.class},
                            0,0,System.nanoTime()+TimeUnit.SECONDS.toNanos(1)));
                    await(manager);
                    var completed=manager.renderSnapshot().tiles().get(key);
                    assertTrue(completed.data().voxyTile.surfaceComplete(),manager.surfaceDiagnostics());
                    assertEquals(axis,completed.cellAxis());assertEquals(step,completed.spacingBlocks());
                    assertSame(base.columns(),completed.data().voxyTile.columns());assertSame(preview.samples(),completed.samples());
                    assertEquals(0,sampled.get(),"Completing plants must not resample the Rust/Java terrain grid");
                    assertTrue(generated.get()>0);assertFalse(completed.data().voxyTile.features().isEmpty());
                    assertTrue(completed.data().voxyTile.features().stream().noneMatch(b->b.state().is(net.minecraft.world.level.block.Blocks.OAK_LEAVES)),
                            "Only completed placement supplies vegetation blocks");
                    int before=generated.get();
                    refreshDetails(manager);
                    assertEquals(false,invoke(manager,"serviceVisibleSurfaces",new Class[]{int.class,int.class,long.class},
                            0,0,System.nanoTime()+TimeUnit.SECONDS.toNanos(1)));
                    assertEquals(before,generated.get(),"A completed surface is not regenerated");
                }
            }
        } finally {}
    }

    private static PredictionSchedulePreparation.Input scheduleInput(PredictionTileManager manager,PredictionTileKey key) throws Exception {
        return new PredictionSchedulePreparation.Input(manager.layout(),null,null,0,80,0,.01,-64,320,0,List.of(key),
                manager.renderSnapshot(),Set.of(key),Set.of(key),Set.copyOf((Set<PredictionTileKey>)field("surfaceReady").get(manager)),
                Set.of(),Set.of(),Set.of(),Map.of(key,32),Map.of(key,32),Map.of(),Map.of(),PredictionMediumCoverage.EMPTY,
                false,false,false,false,Map.of(),Set.of(),Set.of(),Collections.nCopies(manager.layout().levelCount(),32),null,
                Map.of(),Map.of(),Map.of(),System.nanoTime(),false);
    }

    private static ClientTerrainSampler vegetatedSampler(AtomicInteger calls) {
        var feature=new net.minecraft.world.level.levelgen.feature.Feature<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration>(
                net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration> context) {
                calls.incrementAndGet();
                for(int y=0;y<6;y++) context.level().setBlock(context.origin().above(y),net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState(),3);
                return true;
            }
        };
        var placed=new net.minecraft.world.level.levelgen.placement.PlacedFeature(net.minecraft.core.Holder.direct(
                new net.minecraft.world.level.levelgen.feature.ConfiguredFeature<>(feature,
                        net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE)),List.of(
                net.minecraft.world.level.levelgen.placement.HeightmapPlacement.onHeightmap(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG)));
        var generation=new net.minecraft.world.level.biome.BiomeGenerationSettings.PlainBuilder();
        generation.addFeature(net.minecraft.world.level.levelgen.GenerationStep.Decoration.VEGETAL_DECORATION,net.minecraft.core.Holder.direct(placed));
        var biome=new net.minecraft.world.level.biome.Biome.BiomeBuilder().hasPrecipitation(true).temperature(.7F).downfall(.5F)
                .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
                .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY).generationSettings(generation.build()).build();
        var biomes=new net.minecraft.world.level.biome.FixedBiomeSource(net.minecraft.core.Holder.direct(biome));
        var lookup=net.minecraft.data.registries.VanillaRegistries.createLookup();
        var noise=lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS).getOrThrow(
                net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biomes,noise);
        var random=net.minecraft.world.level.levelgen.RandomState.create(noise.value(),lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),PROFILE.seed());
        return new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override public ClientColumnSample sample(int x,int z) { return PredictionVoxelFixtures.sample(64); }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample(x,z); }
            @Override public ClientColumnSample samplePreview(int x,int z,int step) { return sample(x,z); }
            @Override net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generatorContext(){return generator;}
            @Override net.minecraft.world.level.levelgen.RandomState randomStateContext(){return random;}
            @Override net.minecraft.world.level.biome.BiomeSource biomeSourceContext(){return biomes;}
            @Override net.minecraft.core.RegistryAccess decorationAccess(){return net.minecraft.core.RegistryAccess.EMPTY;}
        };
    }
    @SuppressWarnings("unchecked")
    private static void verifyResourceReloadRetention(PredictionTileManager manager,
            PredictionTileManager.PredictionTile tile,PredictionVoxySource source,AtomicInteger calls) throws Exception {
        var f=ClientPredictionState.class.getDeclaredField("MANAGERS");f.setAccessible(true);
        var managers=(Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,PredictionTileManager>)f.get(null);
        assertTrue(managers.isEmpty());managers.put(PROFILE.levelKey(),manager);
        int generated=calls.get();
        long pos=VoxyPredictionBridge.position(0,-2,2,-2);
        VoxyPredictionBridge.Mapper mapper=(s,b)->s.is(net.minecraft.world.level.block.Blocks.OAK_LOG)?7L<<27:1L<<27;
        long[] voxels=source.section(pos,mapper);assertNotNull(voxels);
        assertTrue(Arrays.stream(voxels).anyMatch(v->v==(7L<<27)));
        try {
            for(int reload=0;reload<3;reload++) {
                ClientPredictionState.resourcesChanged();manager.connectVoxy();
                assertSame(tile,manager.renderSnapshot().tiles().get(tile.key()));
                assertSame(voxels,source.section(pos,mapper));
                assertEquals(generated,calls.get(),"Resource changes must not replay vegetation generation");
                assertTrue(tile.data().voxyTile.surfaceComplete());assertEquals(0,manager.pendingCount());
            }
            ClientPredictionState.tagsChanged();
            assertFalse(manager.hasReadyTiles());assertNull(source.section(pos,mapper));
        } finally {managers.remove(PROFILE.levelKey());}
    }

    @Test @SuppressWarnings("unchecked")
    void rustColorRefreshCompletionRetainsTheCurrentContentRevision() throws Exception {
        var rust=org.mockito.Mockito.mock(RustTerrainSampler.class);
        org.mockito.Mockito.when(rust.profile()).thenReturn(PROFILE);
        org.mockito.Mockito.when(rust.decorationContext()).thenReturn(sampler(new AtomicInteger()));
        var f=ClientPredictionState.class.getDeclaredField("MANAGERS");f.setAccessible(true);
        var managers=(Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,PredictionTileManager>)f.get(null);
        assertTrue(managers.isEmpty());
        try(var manager=manager(rust,null)) {
            managers.put(PROFILE.levelKey(),manager);
            var revision=(java.util.concurrent.atomic.AtomicLong)field("meshRevision").get(manager);
            long before=revision.get();
            ClientPredictionState.resourcesChanged();
            var decoder=ClientPredictionState.class.getDeclaredField("PROFILE_DECODER");decoder.setAccessible(true);
            ((java.util.concurrent.ExecutorService)decoder.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);
            org.mockito.Mockito.verify(rust).reloadColormaps();
            assertEquals(before,revision.get());
        } finally {managers.remove(PROFILE.levelKey());}
    }


    @Test void nativeZoomDemandRunsThroughTicksWhileBootstrapWorkerIsStillPending() throws Exception {
        for(var dimension:List.of(net.minecraft.world.level.Level.OVERWORLD,net.minecraft.world.level.Level.NETHER))
        for(boolean scoped:new boolean[]{false,true}) {
        var calls=new AtomicInteger();
        var jobs=new ArrayDeque<Runnable>();
        java.util.concurrent.Executor executor=jobs::add;
        var planning=new PredictionClientPreparation<PredictionPlanPreparation.Input,PredictionPlanPreparation.Result>(
                executor,PredictionPlanningTestSupport::buildPlan);
        try(var manager=new PredictionTileManager(dimension,sampler(calls),
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),null,
                new PredictionCpuBudget(()->8,()->.1,()->.1,System::nanoTime,false))) {
            field("unifiedVoxy").setBoolean(manager,true);
            PredictionPlanningTestSupport.set(manager,"planPreparation",planning);
            var source=(PredictionVoxySource)field("voxySource").get(manager);
            var parent=new PredictionTileKey(dimension,-1,-1,3);
            source.put(PredictionVoxySource.build(parent,newPreview(),10,64,manager.sampler(),
                    PredictionVegetation.Tile.EMPTY,null));
            var key=new PredictionTileKey(dimension,-1,-1,1);
            long node=VoxyPredictionBridge.position(2,-1,0,-1);
            assertFalse(source.requestDetail(node,(x,z)->false));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline) {
                var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(8),2,.1f,1000);
                var observation=new PredictionViewObservation().observe(projection,new org.joml.Matrix4f(),
                        new net.minecraft.world.phys.Vec3(-32,80,-32),540,scoped,true,System.nanoTime());
                manager.setRenderWorkView(observation.view());
                manager.tick(-32,80,-32,4000,null,0);
                var tile=manager.renderSnapshot().tiles().get(key);
                if(tile!=null && tile.cellAxis()==64)break;
                Thread.sleep(3);
            }
            await(manager);
            assertTrue(((List<?>)field("scheduledPlan").get(manager)).isEmpty());
            assertEquals(1,jobs.size(),"Bootstrap stays queued throughout");
            assertEquals(64,manager.renderSnapshot().tiles().get(key).cellAxis());
            assertTrue(source.requestDetail(node,(x,z)->false));assertTrue(calls.get()>0);
        }
        }
    }

    @Test void zoomReadmitsAFullQueueTargetAndPublishesPreciseSamplesWithoutAnotherGpuRequest() throws Exception {
        try(var manager=manager(sampler(new AtomicInteger()),null)) {
            field("unifiedVoxy").setBoolean(manager,true);
            var source=(PredictionVoxySource)field("voxySource").get(manager);
            var detailsField=PredictionVoxySource.class.getDeclaredField("details");detailsField.setAccessible(true);
            var queue=new PredictionVoxyDetailQueue(1);detailsField.set(source,queue);
            var parent=new PredictionTileKey(PROFILE.levelKey(),0,-1,5);
            source.put(PredictionVoxySource.build(parent,newPreview(),10,256,manager.sampler(),
                    PredictionVegetation.Tile.EMPTY,null));
            long near=VoxyPredictionBridge.position(2,3,0,-1);
            long target=VoxyPredictionBridge.position(2,0,0,-8);
            var key=new PredictionTileKey(PROFILE.levelKey(),0,-8,1);
            assertFalse(source.requestDetail(near,(x,z)->false));
            assertFalse(source.requestDetail(target,(x,z)->false));
            assertFalse(source.detailRequests().contains(new PredictionVoxySource.Detail(0,-8,1)));
            assertEquals(1,queue.waiting());
            var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(5),2,.1f,1000);
            var view=PredictionWorkView.rendered(projection,new org.joml.Matrix4f(),
                    new net.minecraft.world.phys.Vec3(32,80,0),new net.minecraft.world.phys.Vec3(0,0,-1),8000);
            assertEquals(PredictionWorkView.VISIBLE,view.band(key,manager.layout(),null,-64,320));
            assertEquals(PredictionWorkView.BACKGROUND,view.band(
                    new PredictionTileKey(PROFILE.levelKey(),3,-1,1),manager.layout(),null,-64,320));
            manager.setRenderWorkView(view);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline) {
                invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},true);
                invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());
                var tile=manager.renderSnapshot().tiles().get(key);
                if(tile!=null && tile.cellAxis()==64)break;
                Thread.sleep(2);
            }
            await(manager);
            var tile=manager.renderSnapshot().tiles().get(key);
            assertNotNull(tile,manager.surfaceDiagnostics());assertEquals(64,tile.cellAxis());
            assertTrue(source.detailRequests().contains(new PredictionVoxySource.Detail(0,-8,1)),
                    "The cached desired snapshot includes targets admitted by asynchronous reprioritization");
            assertTrue(source.requestDetail(target,(x,z)->false),"The next GPU retry observes already-published samples");
        }
    }

    @Test void scopeLoadingSwitchDefersNewSamplesButAllowsReadyDataAndResumesWithoutAnotherGpuRequest() throws Exception {
        var calls=new AtomicInteger();
        try(var manager=manager(sampler(calls),null)) {
            field("unifiedVoxy").setBoolean(manager,true);
            var source=(PredictionVoxySource)field("voxySource").get(manager);
            var key=new PredictionTileKey(PROFILE.levelKey(),-1,-1,1);
            source.put(PredictionVoxySource.build(key,newPreview(),10,16,manager.sampler(),PredictionVegetation.Tile.EMPTY,null));
            long node=VoxyPredictionBridge.position(2,-1,0,-1);
            manager.setNativeDetailLoading(false);
            assertFalse(source.requestDetail(node,(x,z)->false));
            source.requestMeshDetail(VoxyPredictionBridge.position(1,-2,0,-2),(x,z)->false);
            assertTrue(source.detailRequests().isEmpty());
            source.detailChanges(); assertTrue(source.detailRequests().isEmpty());
            manager.setNativeDetailLoading(true);
            refreshDetails(manager);
            // An exhausted frame budget may admit no generation at all. An empty
            // pending set is not proof that the retained detail demand has finished.
            invoke(manager,"serviceGenerationWork",new Class[]{int.class,int.class,long.class},
                    0,0,System.nanoTime()-1);
            assertEquals(0,manager.pendingCount());
            assertNull(manager.renderSnapshot().tiles().get(key));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());
                var tile=manager.renderSnapshot().tiles().get(key);
                if(tile!=null && tile.cellAxis()==64)break;
                Thread.sleep(2);
            }
            await(manager);
            var tile=manager.renderSnapshot().tiles().get(key);
            assertNotNull(tile,manager.surfaceDiagnostics());
            assertEquals(64,tile.cellAxis(),manager.surfaceDiagnostics());
            assertTrue(calls.get()>0,"Retained demand must resume sampling without another GPU request");
            manager.setNativeDetailLoading(false);
            int completedSamples=calls.get();
            assertTrue(source.requestDetail(node,(x,z)->false),"Ready data is never blocked by the loading switch");
            assertEquals(completedSamples,calls.get(),"Ready data does not require further sampling");
            assertEquals(0,manager.pendingCount());
        }
    }

    @Test void observedSpyglassCreatesAndPublishesMissingTerrainWithoutAnyGpuCallback() throws Exception {
        int distance=VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.predictionDistanceBlocks=1024;
        var calls=new AtomicInteger();
        try(var manager=manager(sampler(calls),null)) {
            field("unifiedVoxy").setBoolean(manager,true);
            field("cameraBlockX").setDouble(manager,32);field("cameraBlockY").setDouble(manager,1000);
            var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(5),1.7f,.1f,1024);
            var model=new org.joml.Matrix4f().lookAt(0,0,0,0,-936,-768,0,1,0);
            var observation=new PredictionViewObservation().observe(projection,model,
                    new net.minecraft.world.phys.Vec3(32,1000,0),1080,true,true,System.nanoTime());
            manager.setRenderWorkView(observation.view());
            var key=new PredictionTileKey(PROFILE.levelKey(),0,-12,0);
            var source=(PredictionVoxySource)field("voxySource").get(manager);
            assertTrue(source.detailRequests().isEmpty());assertTrue(manager.readyTiles().isEmpty());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},true);
                invoke(manager,"servicePreparedWork",new Class[]{long.class},System.nanoTime());
                var tile=manager.renderSnapshot().tiles().get(key);
                if(tile!=null && tile.cellAxis()==64)break;
                Thread.sleep(2);
            }
            var tile=manager.renderSnapshot().tiles().get(key);
            assertNotNull(tile,manager.surfaceDiagnostics());assertEquals(64,tile.cellAxis());
            assertEquals(1,tile.spacingBlocks());assertTrue(calls.get()>0);
            assertTrue(source.detailRequests().contains(new PredictionVoxySource.Detail(0,-12,0)));
            assertNotNull(source.section(VoxyPredictionBridge.position(0,0,1,-24),(s,b)->1L<<27));
        } finally {VSSClientConfig.CONFIG.predictionDistanceBlocks=distance;}
    }

    private static void checkTile(PredictionTileManager manager,PredictionTileKey key) throws Exception {
        var tile=manager.readyTiles().iterator().next();
        assertEquals(key,tile.key()); assertNotNull(tile.data().voxyTile);
        assertTrue(tile.cellAxis()>0);
        var source=(PredictionVoxySource)field("voxySource").get(manager);
        var tiles=PredictionVoxySource.class.getDeclaredField("tiles");tiles.setAccessible(true);
        assertSame(tile.data().voxyTile,((Map<?,?>)tiles.get(source)).get(key));
        int x=Math.floorDiv(tile.baseBlockX(),512),z=Math.floorDiv(tile.baseBlockZ(),512);
        long[] data=source.section(VoxyPredictionBridge.position(4,x,0,z),(s,b)->1L<<27);
        assertNotNull(data); assertTrue(Arrays.stream(data).anyMatch(v->(v&(0xfffffL<<27))!=0));
    }
    private static ClientTerrainSampler sampler(AtomicInteger count) {
        return sampler(count,()->{});
    }
    private static ClientTerrainSampler sampler(AtomicInteger count,Runnable beforeSample) {
        return new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return 32; }
            @Override public ClientColumnSample sample(int x,int z) { beforeSample.run(); count.incrementAndGet(); return PredictionVoxelFixtures.sample(64); }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample(x,z); }
            @Override public ClientColumnSample samplePreview(int x,int z,int step) { return sample(x,z); }
            @Override public int surfaceColor(int x,int y,int z) { return 0xff70aa30; }
            @Override public int foliageColor(int x,int y,int z) { return 0xff70aa30; }
        };
    }
    private static PredictionTileManager manager(ClientTerrainSampler sampler,PredictionDiskCache cache) {
        return new PredictionTileManager(PROFILE.levelKey(),sampler,new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,
                0,()->Long.MAX_VALUE,System::nanoTime,1),cache,
                new PredictionCpuBudget(()->8,()->.1,()->.1,System::nanoTime,false));
    }
    @SuppressWarnings("unchecked") private static void setup(PredictionTileManager manager,PredictionTileKey key) throws Exception {
        field("unifiedVoxy").setBoolean(manager,true);
        for(String name:List.of("desiredKeys","terrainLeaves")) ((Set<PredictionTileKey>)field(name).get(manager)).add(key);
        field("terrainTargets").set(manager,Map.of(key,32));
        field("ordinaryTargets").set(manager,Map.of(key,32));
    }
    private static java.lang.reflect.Field field(String name) throws Exception {
        var f=PredictionTileManager.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void enqueue(PredictionTileManager manager,PredictionTileKey key,boolean cached) throws Exception {
        var method=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class,boolean.class);
        method.setAccessible(true); method.invoke(manager,key,0,0,false,cached);
    }
    /** This fixture inspects a single prepared turn, so explicitly await its asynchronous hint handoff. */
    private static void refreshDetails(PredictionTileManager manager) throws Exception {
        invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},true);
        // Demand now crosses the source monitor on the worker too. This fixture
        // awaits that handoff; production ticks only poll and never wait here.
        PredictionClientPreparation.WORKER.submit(()->{}).get(10,TimeUnit.SECONDS);
        invoke(manager,"refreshVoxyDetails",new Class[]{boolean.class},false);
        var pipeline=(PredictionPlanningPipeline)field("planning").get(manager);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(pipeline.details.pending() && System.nanoTime()<deadline) {
            invoke(manager,"publishVoxyWork",new Class[0]);
            if(pipeline.details.pending())Thread.sleep(2);
        }
        assertFalse(pipeline.details.pending(),"Priority preparation must complete without waiting for generation");
    }
    private static void await(PredictionTileManager manager) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(manager.pendingCount()>0 && System.nanoTime()<deadline)Thread.sleep(5);
        assertEquals(0,manager.pendingCount()); assertEquals(0,manager.failedTileCount(),manager.surfaceDiagnostics());
    }
}
