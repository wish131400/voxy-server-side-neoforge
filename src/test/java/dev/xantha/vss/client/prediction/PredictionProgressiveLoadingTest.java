package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionProgressiveLoadingTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void nearbyGroundWaitsForMediumCoverageThenResumes() throws Exception {
        verifyLocalProgress(new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) { return ground(); }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return ground(); }
        }, false);
    }

    @Test void sharedWorkContentionReleasesWorkersAndRetriesWithoutFailureBackoff() throws Exception {
        var config=VSSClientConfig.CONFIG;
        int distance=config.predictionDistanceBlocks;
        boolean trees=config.predictionTrees, structures=config.predictionStructures;
        config.predictionDistanceBlocks=512;
        config.predictionTrees=false;
        config.predictionStructures=false;
        var available=new AtomicBoolean(false);
        var deferred=new CountDownLatch(1);
        var sampler=new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {
                if (!available.get()) { deferred.countDown(); throw new PredictionWorkDeferred(); }
                return ground();
            }
            @Override public ClientColumnSample sample(int x,int z) { return sampleForLod(x,z,1); }
        };
        var budget=new PredictionMemoryBudget(2048L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,2);
        ThreadPoolExecutor executor;
        try (var manager=new PredictionTileManager(PROFILE.levelKey(),sampler,budget,null)) {
            var executorField=PredictionTileManager.class.getDeclaredField("executor");executorField.setAccessible(true);
            executor=(ThreadPoolExecutor)executorField.get(manager);
            replan(manager);
            assertTrue(deferred.await(5,TimeUnit.SECONDS));
            var field=PredictionTileManager.class.getDeclaredField("deferredUntil");field.setAccessible(true);
            var retries=(java.util.Map<?,?>)field.get(manager);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(retries.isEmpty() && System.nanoTime()<deadline) Thread.sleep(10);
            assertFalse(retries.isEmpty(),"contention must take the short retry path");
            var key=retries.keySet().iterator().next();
            available.set(true);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(manager.readyTiles().stream().noneMatch(tile->tile.key().equals(key)) && System.nanoTime()<deadline) {
                replan(manager);
                Thread.sleep(10);
            }
            assertTrue(manager.readyTiles().stream().anyMatch(tile->tile.key().equals(key)),
                    "same deferred tile must retry without the 30-second failure delay: key="+key
                            +",ready="+manager.readyTiles().stream().map(tile->tile.key()).toList()
                            +",retries="+retries+",failed="+manager.failedTileCount()+","+manager.surfaceDiagnostics());
            assertEquals(0,manager.failedTileCount());
        } finally {
            config.predictionDistanceBlocks=distance;
            config.predictionTrees=trees;
            config.predictionStructures=structures;
        }
        // close() deliberately retires workers asynchronously to avoid a client
        // frame stall. Their finally blocks own the active reservations.
        assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS),"deferred workers must terminate after close");
        assertEquals(0,budget.usedBytes(),"deferred builds must release their memory reservations");
    }

    static void verifyLocalProgress(ClientTerrainSampler source, boolean surface) throws Exception {
        var config = VSSClientConfig.CONFIG;
        int oldDistance = config.predictionDistanceBlocks;
        boolean oldTrees = config.predictionTrees, oldStructures = config.predictionStructures;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockOnce = new AtomicBoolean(true);
        config.predictionDistanceBlocks = surface ? 65536 : 1024;
        config.predictionTrees = surface;
        config.predictionStructures = false;
        var sampler = new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) {
                return source.sample(x, z);
            }
            @Override ClientTerrainSampler decorationContext() { return source; }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) {
                if (step == (surface ? 1024 : 16) && x > (surface ? 65536 : 1024) && blockOnce.compareAndSet(true, false)) {
                    entered.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("blocked root timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException();
                    }
                }
                return source.sampleForLod(x, z, step);
            }
        };
        try (var manager = new PredictionTileManager(PROFILE.levelKey(), sampler,
                new PredictionMemoryBudget(2048L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 2), null)) {
            var executorField = PredictionTileManager.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            var executor = (ThreadPoolExecutor) executorField.get(manager);
            executor.setCorePoolSize(2);
            executor.setMaximumPoolSize(2);
            if(surface) {
                // Start one unrelated root before applying the telescope's
                // priority. This fixture must not build a 64 km medium world
                // merely to exercise a single decoration publication.
                var root=key(1,manager.layout().levelCount()-1);
                var desiredField=PredictionTileManager.class.getDeclaredField("desiredKeys");desiredField.setAccessible(true);
                ((Set<PredictionTileManager.PredictionTileKey>)desiredField.get(manager)).add(root);
                var enqueue=PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileManager.PredictionTileKey.class,int.class,int.class,boolean.class);
                enqueue.setAccessible(true);enqueue.invoke(manager,root,0,0,false);
            }
            replan(manager,surface);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "fixture must keep one far horizon job unfinished");
            if (!surface) {
                for (int i=0;i<20;i++) { replan(manager,false); Thread.sleep(10); }
                assertFalse(hasNearDetail(manager,false), "ordinary local fine terrain waits for medium horizon coverage");
                release.countDown();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (!hasNearDetail(manager, surface) && System.nanoTime() < deadline) {
                replan(manager,surface);
                Thread.sleep(10);
            }
            assertTrue(hasNearDetail(manager, surface), "detail resumes after medium coverage, or via explicit telescope priority: "
                    + manager.surfaceDiagnostics());
            if(surface) assertEquals(1, release.getCount(), "explicit telescope work bypasses the distant medium wave");
            assertEquals(0, manager.failedTileCount());
            release.countDown();
        } finally {
            release.countDown();
            config.predictionDistanceBlocks = oldDistance;
            config.predictionTrees = oldTrees;
            config.predictionStructures = oldStructures;
        }
    }

    @Test void mediumHorizonPrecedesOrdinaryLocalTerrainAndPlants() {
        var layout = VssLodLayout.of(65536, 6, true, false);
        var near = key(1, 0);
        var far = key(2, layout.levelCount() - 1);
        int farPriority = PredictionWorkOrder.priority(far, layout,
                PredictionWorkOrder.distanceSquared(far, layout, 32, 32), 0, false);
        for (boolean surface : new boolean[]{false, true}) {
            int nearPriority = PredictionWorkOrder.priority(near, layout,
                    PredictionWorkOrder.distanceSquared(near, layout, 32, 32), 32, surface);
            if(surface) assertTrue(nearPriority > farPriority,"distant medium terrain precedes ordinary plants");
            else assertTrue(nearPriority > farPriority,"local fine terrain waits for distant medium");
        }
    }

    @Test void fullQueueAdmitsNearUpgradesWithoutCancellingActiveWorkOrCaptures() {
        Set<PredictionTileManager.PredictionTileKey> pending = ConcurrentHashMap.newKeySet();
        var queue = new PriorityBlockingQueue<Runnable>();
        var active = key(0, 0);
        var far = key(200, 1);
        var near = key(1, 0);
        var farTask = new PredictionTileManager.PredictionTask(far, 100, 1000000, () -> fail("evicted work must not run"));
        var capture = new PredictionTileManager.PredictionTask(null, Integer.MIN_VALUE, 0, () -> { });
        pending.add(active); pending.add(far);
        queue.add(farTask); queue.add(capture);
        assertTrue(PredictionTileManager.reserveQueuedBuild(pending, queue, near, 0, 32, 2));
        assertEquals(Set.of(active, near), pending);
        assertEquals(capture, queue.poll());
        assertTrue(queue.isEmpty());
        assertFalse(PredictionTileManager.reserveQueuedBuild(pending, queue, near, 0, 32, 2),
                "duplicate admission cannot forget its original in-flight reservation");
        assertFalse(PredictionTileManager.reserveQueuedBuild(pending, queue, far, 100, 1000000, 2),
                "executing builds cannot be evicted");
        assertEquals(Set.of(active, near), pending);
    }

    @Test void movingTelescopePromotesAlreadyQueuedWorkAndRestoresNormalOrderWhenReleased() {
        var layout = VssLodLayout.of(65536, 6, true, false);
        var queue = new PriorityBlockingQueue<Runnable>();
        var completed = new java.util.ArrayList<String>();
        var near = key(1, 0);
        var middle = key(12, 0);
        var far = key(510, 0);
        var dirty = key(800, 0);
        queue.add(new PredictionTileManager.PredictionTask(null, Integer.MIN_VALUE, 0, () -> completed.add("capture")));
        for (var key : java.util.List.of(near, middle, far, dirty)) {
            double distance = PredictionWorkOrder.distanceSquared(key, layout, 32, 32);
            String label = key.equals(near) ? "near" : key.equals(middle) ? "middle" : key.equals(dirty) ? "dirty" : "far-ground";
            queue.add(new PredictionTileManager.PredictionTask(key,
                    PredictionWorkOrder.priority(key, layout, distance, 0, false), distance, () -> completed.add(label)));
        }
        double span = layout.tileBlocks(0);
        var focus = new VssLodFocus((far.tileX() + .5) * span, span * .5, 128, 9000);
        double farDistance = PredictionWorkOrder.distanceSquared(far, layout, 32, 32);
        queue.add(new PredictionTileManager.PredictionTask(key(511, 0), 999, farDistance, true, () -> completed.add("far-surface")));
        refresh(queue, layout, focus, Set.of(dirty));
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals(java.util.List.of("capture", "dirty", "far-ground", "far-surface", "near", "middle"), completed);

        completed.clear();
        queue.add(new PredictionTileManager.PredictionTask(far, 5, farDistance, () -> completed.add("far")));
        queue.add(new PredictionTileManager.PredictionTask(middle, 25, 0, () -> completed.add("middle")));
        refresh(queue, layout, null, Set.of());
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals(java.util.List.of("middle", "far"), completed, "released targets cannot retain stale boosted priorities");
    }

    @Test void telescopeBuildsFromCrosshairOutwardInsteadOfFromThePlayerFacingEdge() {
        var layout = VssLodLayout.of(65536, 6, true, false);
        for (int distance : new int[]{1536, 8192, 32768}) {
            var focus = new VssLodFocus(distance + 32, 32, 1024, 9000);
            var center = key(distance / 64, 0);
            var nearEdge = key(distance / 64 - 12, 0);
            var farEdge = key(distance / 64 + 12, 0);
            var queue = new PriorityBlockingQueue<Runnable>();
            var finished = new java.util.ArrayList<String>();
            queue.add(new PredictionTileManager.PredictionTask(nearEdge, 0, 0, () -> finished.add("near-edge")));
            queue.add(new PredictionTileManager.PredictionTask(center, 99, 999, () -> finished.add("crosshair")));
            queue.add(new PredictionTileManager.PredictionTask(farEdge, 99, 999, () -> finished.add("far-edge")));
            refresh(queue, layout, focus, Set.of());
            while (!queue.isEmpty()) queue.poll().run();
            assertEquals("crosshair", finished.getFirst(), "distance=" + distance);
            assertEquals(PredictionWorkOrder.orderingDistance(nearEdge, layout, 32, 32, focus),
                    PredictionWorkOrder.orderingDistance(farEdge, layout, 32, 32, focus), .01,
                    "equal offsets on opposite sides of the scope have equal distance priority");
        }
    }

    private static void refresh(PriorityBlockingQueue<Runnable> queue, VssLodLayout layout,
                                VssLodFocus focus, Set<PredictionTileManager.PredictionTileKey> dirty) {
        PredictionTileManager.refreshQueuedWork(queue, ConcurrentHashMap.newKeySet(),
                key -> key.lod() >= 0 && key.lod() < layout.levelCount(),
                (key, surface) -> dirty.contains(key) ? Integer.MIN_VALUE + 1 + key.lod()
                        : PredictionWorkOrder.priority(key, layout,
                        PredictionWorkOrder.distanceSquared(key, layout, 32, 32), 0, surface, focus),
                key -> PredictionWorkOrder.orderingDistance(key, layout, 32, 32, focus));
    }

    @Test void queueCleanupReleasesOnlyRemovedJobsBeforeCalculatingTheirDistance() {
        var layout = VssLodLayout.of(2048, 2, true, false);
        var invalid = key(0, layout.levelCount());
        var stale = key(30, 0);
        var active = key(2, 0);
        var retained = key(1, 0);
        Set<PredictionTileManager.PredictionTileKey> pending = ConcurrentHashMap.newKeySet();
        pending.addAll(Set.of(invalid, stale, active, retained));
        var queue = new PriorityBlockingQueue<Runnable>();
        var completed = new java.util.ArrayList<String>();
        for (var key : Set.of(invalid, stale, retained)) {
            queue.add(new PredictionTileManager.PredictionTask(key, 9, 0,
                    () -> { assertEquals(retained, key); completed.add("retained"); }));
        }
        queue.add(new PredictionTileManager.PredictionTask(null, Integer.MIN_VALUE, 0, () -> completed.add("capture")));
        PredictionTileManager.refreshQueuedWork(queue, pending,
                key -> key.lod() < layout.levelCount() && !key.equals(stale),
                (key, surface) -> 0,
                key -> { assertEquals(retained, key); return PredictionWorkOrder.distanceSquared(key, layout, 0, 0); });
        assertEquals(Set.of(active, retained), pending);
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals(java.util.List.of("capture", "retained"), completed);
    }

    @Test void mediumHorizonPrecedesPlantsAndLocalFineTerrain() {
        var layout = VssLodLayout.of(65536, 2, true, false);
        var key = key(1, 0);
        int preview = PredictionWorkOrder.priority(key, layout, 48D * 48, 16, false);
        int fine = PredictionWorkOrder.priority(key, layout, 0, 32, false);
        int plants = PredictionWorkOrder.priority(key, layout, 0, 64, true);
        int farPreview = PredictionWorkOrder.priority(key, layout, 2048D * 2048, 0, false);
        assertTrue(preview < fine);
        assertTrue(fine < plants);
        assertTrue(farPreview < fine);
        assertTrue(farPreview < plants);
        int neighborPreview = PredictionWorkOrder.priority(key, layout, 64D * 64, 16, false);
        assertTrue(neighborPreview < plants, "medium terrain precedes ordinary decoration");
    }

    @Test void distantFirstCoverageDoesNotPayForLocalPreviewDensity() {
        for (int lod=0;lod<12;lod++) {
            int axis=PredictionWorkOrder.initialCellAxis(lod);
            assertEquals(lod<2 ? 16 : 8, axis);
            int points=axis+2*VssLodLayout.SAMPLE_MARGIN;
            assertEquals(lod<2 ? 324 : 100,points*points);
        }
    }

    @Test void queuedTerrainChangesStageWhenItsPreviewBecomesResident() {
        var layout = VssLodLayout.of(8192, 2, true, false);
        var near = key(0, 0);
        var neighbor = key(4, 0);
        var queue = new PriorityBlockingQueue<Runnable>();
        var completed = new java.util.ArrayList<String>();
        queue.add(new PredictionTileManager.PredictionTask(near, 0, 0, () -> completed.add("fine")));
        queue.add(new PredictionTileManager.PredictionTask(near, 0, 0, true, () -> completed.add("plants")));
        queue.add(new PredictionTileManager.PredictionTask(neighbor, 1, 999, () -> completed.add("preview")));
        PredictionTileManager.refreshQueuedWork(queue, ConcurrentHashMap.newKeySet(), key -> true,
                (key, surface) -> PredictionWorkOrder.priority(key, layout,
                        PredictionWorkOrder.distanceSquared(key, layout, 0, 0), key.equals(near) ? 32 : 16, surface),
                key -> PredictionWorkOrder.distanceSquared(key, layout, 0, 0));
        while (!queue.isEmpty()) queue.poll().run();
        assertEquals(java.util.List.of("preview", "fine", "plants"), completed);
    }

    private static ClientColumnSample ground() {
        return new ClientColumnSample(64, 64, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_SURFACE_ONLY, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    private static PredictionTileManager.PredictionTileKey key(int x, int lod) {
        return new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, x, 0, lod);
    }

    private static boolean hasNearDetail(PredictionTileManager manager, boolean surface) throws Exception {
        var readyField = PredictionTileManager.class.getDeclaredField("surfaceReady");
        readyField.setAccessible(true);
        var surfaces = (Set<?>) readyField.get(manager);
        for (var tile : manager.readyTiles()) assertNotNull(tile.mesh().gpuPayload(),
                "workers must finish GPU payload packing before publishing any tile");
        return manager.readyTiles().stream().anyMatch(tile -> tile.spacingBlocks() == 1
                && (!surface || surfaces.contains(tile.key()) && tile.depthBound().maxY() > 64)
                && PredictionWorkOrder.distanceSquared(tile.key(), manager.layout(), 32, 32) < 256 * 256);
    }

    private static void replan(PredictionTileManager manager) {
        replan(manager,false);
    }

    private static void replan(PredictionTileManager manager, boolean scoped) {
        for (int i = 0; i < 5; i++) manager.tick(32, 90, 32, 768,
                scoped ? new VssLodFocus(32,32,1024,768) : null);
    }
}
