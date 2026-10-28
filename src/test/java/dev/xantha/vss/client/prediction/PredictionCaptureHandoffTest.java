package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.config.VSSClientConfig;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionCaptureHandoffTest {
    @TempDir Path directory;
    private int distance;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void configure() { distance=VSSClientConfig.CONFIG.predictionDistanceBlocks; VSSClientConfig.CONFIG.predictionDistanceBlocks=4096; }
    @AfterEach void restore() throws Exception { VSSClientConfig.CONFIG.predictionDistanceBlocks=distance; PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void noticePreservesResidentTerrainAndPlantsAndBlocksEveryBuildLaneUntilCommit() throws Exception {
        var source=source();
        try (var manager=manager(source,null)) {
            var key=key(0,0,0);
            Set<PredictionTileManager.PredictionTileKey> desired=field(manager,"desiredKeys"); desired.add(key);
            var resident=PredictionVoxelFixtures.tile(0,0,0,manager.layout());
            Map<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile> ready=field(manager,"ready");ready.put(key,resident);
            Set<PredictionTileManager.PredictionTileKey> surfaces=field(manager,"surfaceReady"); surfaces.add(key);
            Set<PredictionTileManager.PredictionTileKey> dirty=field(manager,"dirtyTiles");
            manager.awaitExactColumns(new long[]{pack(0,0)},new long[]{20});
            assertSame(resident,manager.readyTiles().iterator().next());
            assertTrue(surfaces.contains(key),"waiting must not remove completed vegetation");
            assertFalse(dirty.contains(key),"notice alone must not schedule a replacement");
            enqueue(manager,key,false,false); enqueue(manager,key,true,false); enqueue(manager,key,false,true);
            assertEquals(0,manager.pendingCount(),"all lanes use the same input barrier");
            assertFalse(current(manager,key));
            assertTrue(manager.captureExactColumn(0,0,data(20)));
            awaitIdle(manager);
            assertFalse(manager.requiresExactCapture(0,0));
            assertTrue(dirty.contains(key),"new data commits before rebuild becomes eligible");
            assertSame(resident,manager.readyTiles().iterator().next(),"old geometry survives until a replacement publishes");
        }
    }

    @Test void overlappingDirtyColumnsCoalesceAndDoNotBlockUnrelatedWorkOrFutureKeys() throws Exception {
        try (var manager=manager(source(),null)) {
            var key=key(0,0,0);var unrelated=key(1000,1000,0);
            Set<PredictionTileManager.PredictionTileKey> desired=field(manager,"desiredKeys"); desired.addAll(Set.of(key,unrelated));
            manager.awaitExactColumns(new long[]{pack(0,0),pack(1,0)},new long[]{10,11});
            assertFalse(current(manager,key),"a newly requested tile must also wait, even without a previous resident");
            assertTrue(current(manager,unrelated));
            assertTrue(manager.captureExactColumn(0,0,data(10))); awaitIdle(manager);
            assertFalse(current(manager,key),"the overlapping dependency is still in transit");
            assertTrue(manager.captureExactColumn(1,0,data(11))); awaitIdle(manager);
            assertTrue(current(manager,key));
        }
    }

    @Test void newerNoticeDuringCaptureIoCannotReleaseWaitOrPublishOldSamples() throws Exception {
        try (var disk=new PredictionDiskCache(directory,77);var manager=manager(source(),disk)) {
            PredictionCapturedTerrain terrain=field(manager,"capturedTerrain");
            var leaf=new PredictionDiskCache.Key(3,0,0,0);
            Map<PredictionDiskCache.Key,Boolean> regions=field(terrain,"regions");regions.put(leaf,true);
            var entered=new CountDownLatch(1);
            var release=new CompletableFuture<PredictionCapturedTerrain.Node>() {
                @Override public PredictionCapturedTerrain.Node join() { entered.countDown(); return super.join(); }
            };
            Map<PredictionDiskCache.Key,CompletableFuture<PredictionCapturedTerrain.Node>> decoding=field(terrain,"decoding");decoding.put(leaf,release);
            manager.awaitExactColumns(new long[]{pack(0,0)},new long[]{10});
            assertTrue(manager.captureExactColumn(0,0,data(10)));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS),"capture has passed extraction and entered IO");
                manager.awaitExactColumns(new long[]{pack(0,0)},new long[]{11});
            } finally { release.complete(null); }
            awaitIdle(manager);
            assertTrue(manager.requiresExactCapture(0,0));
            VssLodSampleCache samples=field(manager,"sampleCache");assertEquals(0,samples.size());
            assertFalse(manager.captureExactColumn(0,0,data(10)),"an old response cannot satisfy the new watermark");
            assertFalse(manager.captureLocalColumn(0,0,data(Long.MAX_VALUE),true),"client clocks cannot settle server watermarks");
            assertTrue(manager.captureExactColumn(0,0,data(11)));awaitIdle(manager);
            assertFalse(manager.requiresExactCapture(0,0));assertEquals(256,samples.size());
        }
    }

    @Test void fullQueueReportsDirtyCaptureBackpressureAndCanRetryWithoutLosingTheWait() throws Exception {
        Map<Object,Object> managers=stateMap();Object previous=managers.get(Level.OVERWORLD);
        try (var manager=manager(source(),null)) {
            managers.put(Level.OVERWORLD,manager);
            Set<Long> pending=field(manager,"pendingCaptures");
            for(int i=0;i<32;i++)pending.add(pack(i+10,0));
            manager.awaitExactColumns(new long[]{pack(0,0)},new long[]{10});
            assertFalse(ClientPredictionState.onExactColumn(Level.OVERWORLD,0,0,data(10)),"network processor must retry a rejected dirty snapshot");
            assertTrue(manager.requiresExactCapture(0,0));
            pending.clear();
            assertTrue(manager.captureExactColumn(0,0,data(10)));awaitIdle(manager);
            assertFalse(manager.requiresExactCapture(0,0));
        } finally { if(previous==null)managers.remove(Level.OVERWORLD);else managers.put(Level.OVERWORLD,previous); }
    }

    @Test void failedExtractionRetainsBoundedSnapshotAndRetriesWhileOtherCapturesProgress() throws Exception {
        var failures=new AtomicInteger(1);
        var source=new ClientTerrainSampler(42,PredictionDecorationQueryTest.PROFILE) {
            @Override public ClientColumnSample samplePreview(int x,int z,int step) {
                if(x==8 && failures.getAndDecrement()>0)throw new IllegalStateException("injected temporary capture failure");
                return PredictionVoxelFixtures.sample(120);
            }
        };
        try (var manager=manager(source,null)) {
            manager.awaitExactColumns(new long[]{pack(0,0),pack(1,0)},new long[]{10,10});
            assertTrue(manager.captureExactColumn(0,0,data(10)));assertTrue(manager.captureExactColumn(1,0,data(10)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(manager.requiresExactCapture(1,0) && System.nanoTime()<deadline) { manager.serviceCaptures();Thread.sleep(5); }
            assertFalse(manager.requiresExactCapture(1,0),"a failed column cannot hold up unrelated capture extraction");
            Map<Long,VoxelColumnData> retained=field(manager,"latestCaptures");
            synchronized(manager) { assertNotNull(retained.get(pack(0,0)),"failed snapshot stays in the bounded inbox"); }
            awaitIdle(manager);
            assertFalse(manager.requiresExactCapture(0,0));
            assertTrue(retained.isEmpty());assertEquals(0L,((Number)field(manager,"pendingCaptureBytes")).longValue());
        }
    }

    @Test void identicalSampledGroundStillRefreshesEditedVegetationAndSparseParents() throws Exception {
        VSSClientConfig.CONFIG.predictionDistanceBlocks=65536;
        try (var manager=manager(source(),null)) {
            assertTrue(manager.captureExactColumn(1,1,data(1)));awaitIdle(manager);
            var ancestor=key(0,0,6);
            Set<PredictionTileManager.PredictionTileKey> pending=field(manager,"pending");pending.add(ancestor);
            Set<PredictionTileManager.PredictionTileKey> surfaces=field(manager,"surfaceReady");surfaces.add(ancestor);
            manager.awaitExactColumns(new long[]{pack(1,1)},new long[]{2});
            assertTrue(manager.isAuthoritative(1,1));
            assertTrue(manager.isRenderAuthoritative(1,1),"notice uses the same coordinate encoding as ownership queries");
            assertTrue(manager.revokeAuthoritative(1,1));
            assertFalse(manager.isRenderAuthoritative(1,1));
            assertFalse(PredictionTileManager.captureIntersectsGrid(ancestor,manager.layout(),1,1));
            assertTrue(surfaces.contains(ancestor));
            assertTrue(manager.captureExactColumn(1,1,data(2)));awaitIdle(manager);
            assertTrue(manager.isRenderAuthoritative(1,1),"committed edits restore queryable ownership");
            Set<PredictionTileManager.PredictionTileKey> dirty=field(manager,"dirtyTiles");
            assertTrue(dirty.contains(ancestor));assertFalse(surfaces.contains(ancestor));
        }
    }

    @Test void committedEditSuppressesPredictedPlantsOnlyInItsRealColumnDuringSurfacePublication() throws Exception {
        var fixture=new PredictionVegetationLocalInvalidationTest.Fixture();
        try(var manager=manager(fixture.terrain,null)) {
            manager.awaitExactColumns(new long[]{pack(1,1)},new long[]{2});
            assertTrue(manager.captureExactColumn(1,1,data(2)));awaitIdle(manager);
            var key=key(0,0,0);
            Set<PredictionTileManager.PredictionTileKey> desired=field(manager,"desiredKeys");desired.add(key);
            Set<PredictionTileManager.PredictionTileKey> surfaces=field(manager,"surfaceDesired");surfaces.add(key);
            Map<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile> ready=field(manager,"ready");
            ready.put(key,PredictionVoxelFixtures.tile(0,0,0,manager.layout()));
            enqueue(manager,key,true,false);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            Set<PredictionTileManager.PredictionTileKey> completed=field(manager,"surfaceReady");
            while(!completed.contains(key) && System.nanoTime()<deadline)Thread.sleep(5);
            assertTrue(completed.contains(key),manager.surfaceDiagnostics());
            var plants=ready.get(key).data().voxyTile.features();
            assertTrue(plants.stream().anyMatch(box->box.minX()==0 && box.minZ()==0),"unmodified neighboring plants still publish");
            assertFalse(plants.stream().anyMatch(box->box.minX()>=16 && box.minX()<32 && box.minZ()>=16 && box.minZ()<32),
                    "the real edited column must not regain generated plants at surface publication");
        }
    }

    @Test void taskQueuedBeforeNoticeCannotReadTheNewEpochAndPublishOldInput() throws Exception {
        var release=new CountDownLatch(1);
        var calls=new AtomicInteger();
        var samplerMethod=PredictionFirstCoverageTest.class.getDeclaredMethod("sampler",AtomicInteger.class,CountDownLatch.class,CountDownLatch.class);
        samplerMethod.setAccessible(true);
        var source=(ClientTerrainSampler)samplerMethod.invoke(null,calls,null,null);
        try(var manager=manager(source,null)) {
            var key=key(0,0,manager.layout().levelCount()-1);
            Set<PredictionTileManager.PredictionTileKey> desired=field(manager,"desiredKeys");desired.add(key);
            ThreadPoolExecutor executor=field(manager,"executor");
            var entered=new CountDownLatch(executor.getCorePoolSize());
            for(int i=0;i<executor.getCorePoolSize();i++)executor.execute(()->{
                entered.countDown();try{release.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();}
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            enqueue(manager,key,false,false);assertEquals(1,manager.pendingCount());
            manager.awaitExactColumns(new long[]{pack(0,0)},new long[]{20});
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(manager.pendingCount()!=0 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(0,manager.pendingCount());assertEquals(0,manager.readyCount());assertEquals(0,calls.get());
            assertTrue(manager.captureExactColumn(0,0,data(20)));awaitIdle(manager);
            enqueue(manager,key,false,false);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(manager.pendingCount()!=0 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(1,manager.readyCount(),manager.surfaceDiagnostics());
        } finally { release.countDown(); }
    }

    @Test void finalPublicationRejectsWaitingInputEvenWhenEpochMatches() throws Exception {
        try(var manager=manager(source(),null)) {
            var key=key(0,0,0);
            Set<PredictionTileManager.PredictionTileKey> desired=field(manager,"desiredKeys");desired.add(key);
            manager.awaitExactColumns(new long[]{pack(0,0)},new long[]{20});
            var tile=PredictionVoxelFixtures.tile(0,0,0,manager.layout());
            var publish=PredictionTileManager.class.getDeclaredMethod("publishTile",PredictionTileManager.PredictionTile.class,
                    PredictionMemoryBudget.Reservation.class,long.class,long.class,boolean.class,boolean.class,boolean.class,PredictionRelief.class);
            publish.setAccessible(true);
            assertEquals(false,publish.invoke(manager,tile,null,0L,0L,false,false,false,null));
            assertEquals(0,manager.readyCount());
            manager.retireExactColumns(new long[]{pack(0,0)});
            assertFalse(manager.requiresExactCapture(0,0));assertTrue(current(manager,key));
        }
    }

    @Test void requestPruningAndSessionResetRetireTheirPredictionWaits() throws Exception {
        Map<Object,Object> managers=stateMap();Object previous=managers.get(Level.OVERWORLD);
        try(var prediction=manager(source(),null)) {
            managers.put(Level.OVERWORLD,prediction);
            var network=new dev.xantha.vss.networking.client.LodRequestManager("retire");
            var dimension=network.getClass().getDeclaredField("lastDimension");dimension.setAccessible(true);dimension.set(network,Level.OVERWORLD);
            long column=pack(300,0);
            network.onDirtyColumns(new long[]{column},new long[]{200});
            prediction.awaitExactColumns(new long[]{column},new long[]{200});
            assertTrue(prediction.requiresExactCapture(300,0));
            var prune=network.getClass().getDeclaredMethod("pruneAround",int.class,int.class,int.class);prune.setAccessible(true);
            prune.invoke(network,0,0,160);
            assertEquals(0,network.dirtyColumnTimestamp(column));assertFalse(prediction.requiresExactCapture(300,0));
            column=pack(1,0);
            network.onDirtyColumns(new long[]{column},new long[]{200});
            prediction.awaitExactColumns(new long[]{column},new long[]{200});
            var reset=network.getClass().getDeclaredMethod("resetRequestState");reset.setAccessible(true);reset.invoke(network);
            assertEquals(0,network.dirtyColumnTimestamp(column));assertFalse(prediction.requiresExactCapture(1,0));
        } finally { if(previous==null)managers.remove(Level.OVERWORLD);else managers.put(Level.OVERWORLD,previous); }
    }

    private static VoxelColumnData data(long version) { return new VoxelColumnData(new VoxelColumnData.SectionData[0],version,true); }
    private static long pack(int x,int z) { return PositionUtil.packPosition(x,z); }
    private static PredictionTileManager.PredictionTileKey key(int x,int z,int lod) { return new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x,z,lod); }
    private static ClientTerrainSampler source() { return new ClientTerrainSampler(42,PredictionDecorationQueryTest.PROFILE) {
        @Override public ClientColumnSample samplePreview(int x,int z,int step) { return PredictionVoxelFixtures.sample(120); }
    }; }
    private static PredictionTileManager manager(ClientTerrainSampler source,PredictionDiskCache disk) {
        return new PredictionTileManager(Level.OVERWORLD,source,new PredictionMemoryBudget(2048L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),disk);
    }
    private static void enqueue(PredictionTileManager manager,PredictionTileManager.PredictionTileKey key,boolean surface,boolean cache) throws Exception {
        var method=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileManager.PredictionTileKey.class,int.class,int.class,boolean.class,boolean.class);method.setAccessible(true);method.invoke(manager,key,0,0,surface,cache);
    }
    private static boolean current(PredictionTileManager manager,PredictionTileManager.PredictionTileKey key) throws Exception {
        Map<PredictionTileManager.PredictionTileKey,Long> epochs=field(manager,"captureEpochs");
        var method=PredictionTileManager.class.getDeclaredMethod("captureCurrent",PredictionTileManager.PredictionTileKey.class,long.class,long.class);method.setAccessible(true);
        return (boolean)method.invoke(manager,key,epochs.getOrDefault(key,0L),0L);
    }
    private static void awaitIdle(PredictionTileManager manager) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            manager.serviceCaptures();
            Set<Long> captures=field(manager,"pendingCaptures");
            if(captures.isEmpty() && manager.pendingCount()==0)return;
            // Some tests use synthetic tile membership without an actual queued task.
            if(captures.isEmpty())return;
            Thread.sleep(5);
        }
        fail(manager.surfaceDiagnostics());
    }
    @SuppressWarnings("unchecked") private static <T>T field(Object object,String name) throws Exception { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(object); }
    @SuppressWarnings("unchecked") private static Map<Object,Object> stateMap() throws Exception { var f=ClientPredictionState.class.getDeclaredField("MANAGERS");f.setAccessible(true);return(Map<Object,Object>)f.get(null); }
}
