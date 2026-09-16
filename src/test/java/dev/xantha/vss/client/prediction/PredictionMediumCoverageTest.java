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

    @Test @SuppressWarnings("unchecked")
    void completedNonLeafMediumFrontierPromotesLocalCompletionAheadOfRemainingWave() throws Exception {
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 123L);
        var sample = new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var sampler = new ClientTerrainSampler(42,profile) {
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample; }
        };
        try (var manager = new PredictionTileManager(profile.levelKey(),sampler,
                new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,2),null)) {
            var near = new PredictionTileKey(profile.levelKey(),0,0,0);
            var frontier = new PredictionTileKey(profile.levelKey(),0,0,2);
            var far = new PredictionTileKey(profile.levelKey(),4,0,2);
            ((Set<PredictionTileKey>)get(manager,"desiredKeys")).addAll(List.of(near,frontier,far));
            ((Set<PredictionTileKey>)get(manager,"terrainLeaves")).add(near);
            ((Set<PredictionTileKey>)get(manager,"surfaceDesired")).add(near);
            set(manager,"terrainTargets",Map.of(near,64));
            set(manager,"mediumCoverage",new PredictionMediumCoverage(Set.of(frontier,far),Set.of(frontier,far)));
            set(manager,"mediumCoveragePending",true);
            set(manager,"previewWorkPending",true);
            var ready = (Map<PredictionTileKey,PredictionTile>)get(manager,"ready");
            for (var key : List.of(near,frontier,far)) ready.put(key,new PredictionTile(key,new int[0],new int[0],
                    new ClientColumnSample[0],null,new PredictionDepthBound(64,64),0,1,
                    key.equals(near)?32:16,manager.layout().tileBlocks(key.lod())/(key.equals(near)?32:16)));
            var counter = (AtomicInteger)get(manager,"mediumSinceSurface");
            counter.set(15);
            var priority = manager.getClass().getDeclaredMethod("workPriority",PredictionTileKey.class,boolean.class);
            priority.setAccessible(true);
            assertTrue((int)priority.invoke(manager,far,false)<(int)priority.invoke(manager,near,false));
            enqueue(manager,frontier); idle(manager);
            assertEquals(32,ready.get(frontier).cellAxis());
            assertEquals(16,counter.get(),"frontier tiles must count even when not final terrain leaves");
            int promoted = (int)priority.invoke(manager,near,false);
            assertTrue(promoted<(int)priority.invoke(manager,far,false),"local completion must interrupt the remaining wave");
            assertTrue((int)priority.invoke(manager,near,true)<promoted,"finish ready plants before another same-band ground task");
            set(manager,"buildFocus",new VssLodFocus(32,32,1024,9000));
            assertTrue((int)priority.invoke(manager,near,false)<promoted,"explicit telescope remains ahead of periodic local turns");
            set(manager,"buildFocus",null);
            counter.set(0);
            assertTrue((int)priority.invoke(manager,far,false)<(int)priority.invoke(manager,near,false),"consuming a turn restores coverage priority");
        }
    }

    @Test @SuppressWarnings("unchecked")
    void oversizedOuterCoverageAdvancesThroughWorkerAfterBootstrapCompletes() throws Exception {
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 123L);
        var sample = new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var sampler = new ClientTerrainSampler(42, profile) {
            @Override int initialTerrainCellAxis(int lod) { return 8; }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample; }
        };
        var layout = VssLodLayout.of(65536, 6, true, false);
        var leaves = PredictionLodPlanner.plan(profile.levelKey(), 114.5, 4998, 125.5, layout, null, 1300);
        var target = leaves.stream().filter(key -> {
            int span = layout.tileBlocks(key.lod());
            return Math.floorDiv(9000, span) == key.tileX() && Math.floorDiv(125, span) == key.tileZ();
        }).findFirst().orElseThrow();
        assertTrue(target.lod() < layout.levelCount() - 4,
                "outer terrain must request detail below the horizon-dependent bootstrap level");
        var pass = PredictionMediumCoverage.plan(leaves, layout);
        System.out.println("OUTER_MEDIUM_WORK leaves=" + leaves.size() + " bootstrap=" + pass.frontier().size()
                + " targetSpacing=" + layout.tileBlocks(target.lod()) / 32);
        try (var manager = new PredictionTileManager(profile.levelKey(), sampler,
                new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,2),null)) {
            set(manager, "layout", layout);
            var desired = (Set<PredictionTileKey>) get(manager, "desiredKeys");
            desired.addAll(PredictionTileManager.withCoarseCoverage(leaves, layout));
            ((Set<PredictionTileKey>) get(manager, "terrainLeaves")).addAll(leaves);
            set(manager, "terrainTargets", Map.of(target, 32));
            var ready = (Map<PredictionTileKey,PredictionTile>) get(manager, "ready");
            for (var key = target; key.lod() < layout.levelCount(); key =
                    new PredictionTileKey(key.dimension(),key.tileX()>>1,key.tileZ()>>1,key.lod()+1)) {
                int axis = key.equals(target) ? 8 : 32;
                ready.put(key, new PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],null,
                        new PredictionDepthBound(64,64),0,1,axis,layout.tileBlocks(key.lod())/axis));
            }
            refresh(manager, leaves);
            assertEquals(true, get(manager, "mediumCoveragePending"), "other regions are still unfinished");
            enqueue(manager, target); idle(manager);
            assertEquals(16, ready.get(target).cellAxis(), "first useful intermediate grid publishes");
            enqueue(manager, target); idle(manager);
            assertEquals(32, ready.get(target).cellAxis(), "ordinary outer medium finishes without a telescope");
            assertSame(ready.get(target), manager.renderSnapshot().coveringTileAtDetail(9000>>4, 125>>4, 6),
                    "finished refinement becomes the visible owner instead of the bootstrap ancestor");
            var revision = ready.get(target).revision();
            enqueue(manager, target); idle(manager);
            assertEquals(revision, ready.get(target).revision(), "completed target must not rebuild in a loop");
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
