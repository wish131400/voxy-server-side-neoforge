package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

/** Exercises actual manager publication, queue admission and relocation, without a running client. */
class PredictionTeleportLoadingTest {
    static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    int previousDistance;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void configure() { previousDistance=VSSClientConfig.CONFIG.predictionDistanceBlocks; VSSClientConfig.CONFIG.predictionDistanceBlocks=8192; }
    @AfterEach void restore() { VSSClientConfig.CONFIG.predictionDistanceBlocks=previousDistance; }

    @Test void equivalentReplanPreservesReadinessAndAcceptsAlreadyPreparedLoading() throws Exception {
        try(var manager=manager((x,z)->64,2)) {
            var input=PredictionDemandTestSupport.planningInput(manager,0);
            var plans=new PredictionClientPreparation<PredictionPlanPreparation.Input,PredictionPlanPreparation.Result>(
                    Runnable::run,PredictionPlanningTestSupport::buildPlan);
            set(manager,"planPreparation",plans);
            plans.request(input); invoke(manager,"publishPlan",System.nanoTime());
            Object originalPlan=get(manager,"scheduledPlan");
            var complete=new PredictionLoadingProgress(1,1,1,1,1,1,0,0,true,null);
            set(manager,"loadingProgress",complete); set(manager,"foregroundWorkPending",false);
            var schedule=(PredictionSchedulePreparation.Input)invoke(manager,"schedulingInput",System.nanoTime());
            var request=(PredictionLoadingPreparation.Input)invoke(manager,"loadingInput",schedule);
            var jobs=new ArrayDeque<Runnable>();
            var loading=new PredictionClientPreparation<PredictionLoadingPreparation.Input,PredictionLoadingPreparation.Result>(
                    jobs::add,PredictionLoadingPreparation::build);
            set(manager,"loadingPreparation",loading); assertTrue(loading.request(request));
            plans.request(input); invoke(manager,"publishPlan",System.nanoTime());
            assertSame(originalPlan,get(manager,"scheduledPlan"),"equal goals must retain their publication token");
            assertSame(complete,manager.loadingProgress(),"periodic polling cannot reset completed loading");
            assertEquals(false,get(manager,"foregroundWorkPending"));
            jobs.removeFirst().run(); invoke(manager,"publishLoadingWork");
            assertEquals(1L,get(manager,"loadingCommits"),"an equivalent replan cannot starve loading publication");
            assertEquals(0L,get(manager,"loadingDiscards"));
        }
    }

    @Test void scheduleOmitsWholeResidentReclaimSortUntilMemoryNeedsIt() throws Exception {
        var free=new AtomicLong(4096L*PredictionMemoryBudget.MIB);
        var memory=PredictionMemoryBudget.adaptive(8192L*PredictionMemoryBudget.MIB,free::get,System::nanoTime,2,()->0);
        try(var manager=new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,PROFILE,(x,z)->64),memory,null)) {
            int top=manager.layout().levelCount()-1;
            var root=new PredictionTileKey(Level.OVERWORLD,0,0,top);
            var child=new PredictionTileKey(Level.OVERWORLD,0,0,top-1);
            residents(manager).put(root,tile(manager,root,8)); residents(manager).put(child,tile(manager,child,32));
            var normal=(PredictionSchedulePreparation.Input)invoke(manager,"schedulingInput",System.nanoTime());
            assertTrue(PredictionSchedulePreparation.build(normal).reclaim().isEmpty(),
                    "normal scheduling must not traverse/sort cold residents for an unused reclaim list");
            free.set(0);
            var pressure=(PredictionSchedulePreparation.Input)invoke(manager,"schedulingInput",System.nanoTime());
            assertTrue(PredictionSchedulePreparation.build(pressure).reclaim().contains(child),
                    "memory pressure still supplies safe ancestor-backed reclaim candidates");
        }
    }

    @Test void unavailableDetailSlotNeverStartsThrowawayWorkersAndCoverageStillProgresses() throws Exception {
        var gate=new CountDownLatch(1); var entered=new CountDownLatch(1);
        try(var manager=manager((x,z)->{
            if(x < -8192 || z < -8192) {
                entered.countDown();
                try { if(!gate.await(10,TimeUnit.SECONDS)) throw new AssertionError("test gate timeout"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
            }
            return 64;
        },2)) {
            int top=manager.layout().levelCount()-1;
            var first=new PredictionTileKey(Level.OVERWORLD,-1,-1,top);
            var second=new PredictionTileKey(Level.OVERWORLD,0,-1,top);
            var coverage=new PredictionTileKey(Level.OVERWORLD,0,0,top);
            for(var key:List.of(first,second,coverage)) keys(manager,"desiredKeys").add(key);
            residents(manager).put(first,tile(manager,first,32)); residents(manager).put(second,tile(manager,second,32));
            set(manager,"terrainTargets",Map.of(first,64,second,64,coverage,8));
            set(manager,"ordinaryTargets",Map.of(first,64,second,64,coverage,8));
            set(manager,"previewWorkPending",true);
            enqueue(manager,first); assertTrue(entered.await(5,TimeUnit.SECONDS));
            var executor=(ThreadPoolExecutor)get(manager,"executor");
            long before=executor.getCompletedTaskCount();
            for(int retry=0;retry<40;retry++) {enqueue(manager,second);Thread.sleep(2);}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(manager.pendingCount()>1&&System.nanoTime()<deadline)Thread.sleep(2);
            assertEquals(before,executor.getCompletedTaskCount(),"capacity refusal belongs before executor admission");
            assertFalse(keys(manager,"pending").contains(second));
            enqueue(manager,coverage);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!residents(manager).containsKey(coverage)&&System.nanoTime()<deadline)Thread.sleep(2);
            assertTrue(residents(manager).containsKey(coverage),"first coverage retains its own worker capacity");
            assertEquals(1,gate.getCount(),"coverage must publish while the detail build remains blocked");
            gate.countDown(); awaitIdle(manager);
            enqueue(manager,second);awaitIdle(manager);
            assertEquals(64,residents(manager).get(second).cellAxis(),"a released slot retries without failure backoff");
        } finally {gate.countDown();}
    }

    @Test void queuedDetailSlotsSurviveReprioritizationAndAreReleasedOnPause() throws Exception {
        try(var manager=manager((x,z)->64,2)) {
            var executor=(ThreadPoolExecutor)get(manager,"executor");
            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
            try {
                for(int i=0;i<2;i++)executor.execute(new PredictionTask(null,Integer.MIN_VALUE,0,()->{
                    entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                }));
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                var key=new PredictionTileKey(Level.OVERWORLD,0,0,manager.layout().levelCount()-1);
                keys(manager,"desiredKeys").add(key); residents(manager).put(key,tile(manager,key,32));
                set(manager,"terrainTargets",Map.of(key,64));set(manager,"ordinaryTargets",Map.of(key,64));
                enqueue(manager,key);
                assertEquals(1,executor.getQueue().size());
                assertEquals(1,((AtomicInteger)get(manager,"activeDetailBuilds")).get());
                PredictionTileManager.refreshQueuedWork(executor.getQueue(),keys(manager,"pending"),k->true,(k,s)->-1,k->0);
                manager.setPaused(true);
                assertTrue(executor.getQueue().isEmpty());
                assertEquals(0,((AtomicInteger)get(manager,"activeDetailBuilds")).get());
                assertEquals(0,((AtomicInteger)get(manager,"activeSurfaceBuilds")).get());
                var lease=((PredictionCpuBudget)get(manager,"cpuBudget")).tryAcquire(true);
                assertNotNull(lease);lease.close();
            }finally{release.countDown();}
        }
    }

    @Test void executorRejectionReleasesDetailAdmission() throws Exception {
        try(var manager=manager((x,z)->64,2)) {
            var key=new PredictionTileKey(Level.OVERWORLD,0,0,manager.layout().levelCount()-1);
            keys(manager,"desiredKeys").add(key);residents(manager).put(key,tile(manager,key,32));
            set(manager,"terrainTargets",Map.of(key,64));set(manager,"ordinaryTargets",Map.of(key,64));
            ((ThreadPoolExecutor)get(manager,"executor")).shutdown();
            enqueue(manager,key);
            assertEquals(0,manager.pendingCount());
            assertEquals(0,((AtomicInteger)get(manager,"activeDetailBuilds")).get());
            var lease=((PredictionCpuBudget)get(manager,"cpuBudget")).tryAcquire(true);
            assertNotNull(lease);lease.close();
        }
    }

    @Test void teleportRequestsNewPlanWithoutWaitingForPeriodicTickAndKeepsResidentFallback() throws Exception {
        try(var manager=manager((x,z)->64,2)) {
            var requests=new ArrayList<PredictionPlanPreparation.Input>();
            var plans=new PredictionClientPreparation<PredictionPlanPreparation.Input,PredictionPlanPreparation.Result>(
                    Runnable::run,i->{requests.add(i);return PredictionPlanningTestSupport.buildPlan(i);});
            set(manager,"planPreparation",plans);
            var jobs=new ArrayDeque<Runnable>();
            set(manager,"workPreparation",new PredictionClientPreparation<PredictionSchedulePreparation.Input,
                    PredictionSchedulePreparation.Result>(jobs::add,PredictionSchedulePreparation::build));
            manager.tick(0,80,0,.01,null);manager.tick(0,80,0,.01,null);
            assertEquals(1,requests.size());
            var old=((List<PredictionTileKey>)get(manager,"scheduledPlan")).get(0);
            var resident=tile(manager,old,8);residents(manager).put(old,resident);
            manager.tick(20000,80,20000,.01,null);
            assertEquals(2,requests.size(),"teleport must request current coordinates immediately between periodic ticks");
            assertEquals(20000,requests.get(1).x());
            assertSame(resident,residents(manager).get(old),"abandoning old work must not destroy resident fallback");
            assertFalse((boolean)invoke(manager,"effectivelyDesired",old),"old work cannot consume the grace period after teleport");
            manager.tick(20000,80,20000,.01,null);
            assertFalse(((List<?>)get(manager,"scheduledPlan")).isEmpty());
        }
    }

    static PredictionTileManager manager(ClientTerrainSampler.TerrainFunction f,int workers) {
        return new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,PROFILE,f),
                new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,workers),null);
    }
    static PredictionTile tile(PredictionTileManager manager,PredictionTileKey key,int axis) {
        int spacing=manager.layout().tileBlocks(key.lod())/axis;
        int[] heights=new int[(axis+1)*(axis+1)];Arrays.fill(heights,64);
        var samples=new ClientColumnSample[heights.length];Arrays.fill(samples,PredictionVoxelFixtures.sample(64));
        var mesh=PredictionVoxelFixtures.flat(key,axis,spacing,64);
        return new PredictionTile(key,heights,heights,samples,mesh,new PredictionDepthBound(64,64),0,1,axis,spacing,false);
    }
    static void enqueue(PredictionTileManager m,PredictionTileKey key)throws Exception {
        Method method=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class);
        method.setAccessible(true);method.invoke(m,key,0,0,false);
    }
    static void awaitIdle(PredictionTileManager manager)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(manager.pendingCount()>0&&System.nanoTime()<deadline)Thread.sleep(2);
        assertEquals(0,manager.pendingCount());
    }
    @SuppressWarnings("unchecked") static Set<PredictionTileKey> keys(Object m,String n)throws Exception{return(Set<PredictionTileKey>)get(m,n);}
    @SuppressWarnings("unchecked") static Map<PredictionTileKey,PredictionTile> residents(Object m)throws Exception{return(Map<PredictionTileKey,PredictionTile>)get(m,"ready");}
    static Field field(Object m,String n)throws Exception{Field f=m.getClass().getDeclaredField(n);f.setAccessible(true);return f;}
    static Object get(Object m,String n)throws Exception{return PredictionPlanningTestSupport.get(m,n);}
    static void set(Object m,String n,Object v)throws Exception{PredictionPlanningTestSupport.set(m,n,v);}
    static Object invoke(Object m,String n,Object...args)throws Exception {
        Method method=Arrays.stream(m.getClass().getDeclaredMethods()).filter(it->it.getName().equals(n)&&it.getParameterCount()==args.length)
                .findFirst().orElseThrow();method.setAccessible(true);return method.invoke(m,args);
    }
}
