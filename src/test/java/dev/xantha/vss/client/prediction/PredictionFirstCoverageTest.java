package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionFirstCoverageTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void optionalTerrainWaitsForIdleAndFrameBudgetThenPublishesWithoutLosingParent() throws Exception {
        try (var manager = manager(sampler(new AtomicInteger(), null, null, 32), null)) {
            var root = new PredictionTileKey(PROFILE.levelKey(), -1, -1, manager.layout().levelCount() - 1);
            desire(manager, root, true);
            var targets = PredictionTileManager.class.getDeclaredField("terrainTargets"); targets.setAccessible(true);
            targets.set(manager, java.util.Map.of(root, 32));
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(32, manager.readyTiles().iterator().next().cellAxis());
            var ordinary = PredictionTileManager.class.getDeclaredField("ordinaryTargets"); ordinary.setAccessible(true);
            ordinary.set(manager, java.util.Map.of(root, 32));
            var optional = PredictionTileManager.class.getDeclaredField("idleTargets"); optional.setAccessible(true);
            optional.set(manager, java.util.Map.of(root, 64));
            targets.set(manager, java.util.Map.of(root, 64));
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(32, manager.readyTiles().iterator().next().cellAxis());
            var allowed = PredictionTileManager.class.getDeclaredField("idleAllowed"); allowed.setAccessible(true);
            allowed.setBoolean(manager, true);
            PredictionFramePace.resetForTesting();
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(32, manager.readyTiles().iterator().next().cellAxis(), "unknown frame pace cannot admit optional work");
            long now = System.nanoTime();
            for (int i = 60; i >= 0; i--) PredictionFramePace.recordFrame(now - i * 10_000_000L);
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(64, manager.readyTiles().iterator().next().cellAxis());
            assertTrue(manager.surfaceDiagnostics().contains("pending=0"));
            long built = manager.builtTileCount();
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(built, manager.builtTileCount(), "finished improvement must not rebuild every tick");
            assertNotNull(manager.coveringTile(-1, -1, root.lod()));
        } finally { PredictionFramePace.resetForTesting(); }
    }

    @Test void livePlannerStartsOptionalWorkAfterCompletingOrdinaryTargets() throws Exception {
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        int fine = VSSClientConfig.CONFIG.predictionFineDistanceBlocks;
        boolean trees = VSSClientConfig.CONFIG.predictionTrees;
        boolean structures = VSSClientConfig.CONFIG.predictionStructures;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 1024;
        VSSClientConfig.CONFIG.predictionFineDistanceBlocks = 256;
        VSSClientConfig.CONFIG.predictionTrees = false;
        VSSClientConfig.CONFIG.predictionStructures = false;
        try (var manager = manager(sampler(new AtomicInteger(), null, null, 16), null)) {
            assertFalse(manager.loadingProgress().ready(95), "unplanned terrain must reserve the initial prediction turn");
            var optionalResidents = PredictionTileManager.class.getDeclaredField("idleResidents");
            optionalResidents.setAccessible(true);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (((java.util.Map<?, ?>) optionalResidents.get(manager)).isEmpty() && System.nanoTime() < until) {
                PredictionFramePace.resetForTesting();
                long now = System.nanoTime();
                for (int i = 60; i >= 0; i--) PredictionFramePace.recordFrame(now - i * 10_000_000L);
                manager.tick(-32, 80, -32, 120, null, 0);
                Thread.sleep(10);
            }
            assertFalse(((java.util.Map<?, ?>) optionalResidents.get(manager)).isEmpty(), manager.surfaceDiagnostics());
            assertEquals(0, manager.failedTileCount());
            var targets = PredictionTileManager.class.getDeclaredField("ordinaryTargets"); targets.setAccessible(true);
            @SuppressWarnings("unchecked") var ordinary = (java.util.Map<PredictionTileKey, Integer>) targets.get(manager);
            var axes = new java.util.HashMap<PredictionTileKey, Integer>();
            manager.readyTiles().forEach(tile -> axes.put(tile.key(), tile.cellAxis()));
            assertTrue(ordinary.entrySet().stream().allMatch(e -> axes.getOrDefault(e.getKey(), 0) >= e.getValue()));
            assertTrue(manager.loadingProgress().ready(95), "optional refinement must not hold generation: " + manager.loadingProgress());
        } finally {
            VSSClientConfig.CONFIG.predictionDistanceBlocks = distance;
            VSSClientConfig.CONFIG.predictionFineDistanceBlocks = fine;
            VSSClientConfig.CONFIG.predictionTrees = trees;
            VSSClientConfig.CONFIG.predictionStructures = structures;
            PredictionFramePace.resetForTesting();
        }
    }

    @Test void onlyOneOptionalBuildRunsAndNewOrdinaryWorkKeepsItsPlace() throws Exception {
        var blocking = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var base = sampler(new AtomicInteger(), null, null, 32);
        var source = new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return 32; }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) {
                if (blocking.get()) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("build timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
                }
                return base.sample(x, z);
            }
            @Override public int surfaceColor(int x, int y, int z) { return 0xff70aa30; }
            @Override public int foliageColor(int x, int y, int z) { return 0xff70aa30; }
        };
        try (var manager = manager(source, null)) {
            int lod = manager.layout().levelCount() - 1;
            var first = new PredictionTileKey(PROFILE.levelKey(), -1, -1, lod);
            var second = new PredictionTileKey(PROFILE.levelKey(), 0, -1, lod);
            var urgent = new PredictionTileKey(PROFILE.levelKey(), 0, 0, lod);
            desire(manager, first, true); desire(manager, second, true);
            var targets = PredictionTileManager.class.getDeclaredField("terrainTargets"); targets.setAccessible(true);
            targets.set(manager, java.util.Map.of(first, 32, second, 32));
            enqueue(manager, first); awaitIdle(manager);
            enqueue(manager, second); awaitIdle(manager);
            var ordinary = PredictionTileManager.class.getDeclaredField("ordinaryTargets"); ordinary.setAccessible(true);
            ordinary.set(manager, java.util.Map.of(first, 32, second, 32));
            var optional = PredictionTileManager.class.getDeclaredField("idleTargets"); optional.setAccessible(true);
            optional.set(manager, java.util.Map.of(first, 64, second, 64));
            targets.set(manager, java.util.Map.of(first, 64, second, 64));
            var allowed = PredictionTileManager.class.getDeclaredField("idleAllowed"); allowed.setAccessible(true);
            allowed.setBoolean(manager, true);
            PredictionFramePace.resetForTesting();
            long now = System.nanoTime();
            for (int i = 60; i >= 0; i--) PredictionFramePace.recordFrame(now - i * 10_000_000L);
            blocking.set(true);
            enqueue(manager, first);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            enqueue(manager, second);
            assertEquals(1, manager.pendingCount(), "second optional build must not occupy another slot");
            desire(manager, urgent, true);
            enqueue(manager, urgent);
            assertEquals(2, manager.pendingCount(), "new ordinary loading stays admitted");
            blocking.set(false); release.countDown(); awaitIdle(manager);
            assertTrue(manager.readyTiles().stream().anyMatch(t -> t.key().equals(urgent)));
            assertTrue(manager.readyTiles().stream().anyMatch(t -> t.key().equals(second) && t.cellAxis() == 32));
        } finally { blocking.set(false); release.countDown(); PredictionFramePace.resetForTesting(); }
    }

    @Test void externalHeapPressurePreservesFinishedDetailAndDoesNotRatchetCoverage() throws Exception {
        long mib = PredictionMemoryBudget.MIB;
        var free = new java.util.concurrent.atomic.AtomicLong(4096L * mib);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var budget = PredictionMemoryBudget.adaptive(8192L * mib, free::get, clock::get, 1, () -> 0);
        try (var manager = new PredictionTileManager(PROFILE.levelKey(), sampler(new AtomicInteger(), null, null, 64), budget, null)) {
            int top = manager.layout().levelCount() - 1;
            var root = new PredictionTileKey(PROFILE.levelKey(), 0, 0, top);
            var detail = new PredictionTileKey(PROFILE.levelKey(), 1, 1, top - 1);
            var target = new PredictionTileKey(PROFILE.levelKey(), 0, 0, top - 1);
            desire(manager, root, true); enqueue(manager, root); awaitIdle(manager);
            desire(manager, detail, true); enqueue(manager, detail); awaitIdle(manager);
            desire(manager, target, true);
            var before = manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(before.contains(detail));
            var coverage = PredictionTileManager.class.getDeclaredField("mediumCoverage"); coverage.setAccessible(true);
            coverage.set(manager, new PredictionMediumCoverage(Set.of(root, target), Set.of(target)));
            var pending = PredictionTileManager.class.getDeclaredField("mediumCoveragePending"); pending.setAccessible(true);
            pending.setBoolean(manager, true);
            var reclaim = PredictionTileManager.class.getDeclaredMethod("makeRoomForSurface", int.class, int.class);
            reclaim.setAccessible(true);
            free.set(154L * mib);
            for (int tick = 0; tick < 30; tick++) {
                clock.addAndGet(1_000_000_000L);
                reclaim.invoke(manager, 0, 0);
                assertEquals(before, manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                        .collect(java.util.stream.Collectors.toSet()), "a small detail cache cannot pay for external heap pressure");
            }
            var bias = PredictionTileManager.class.getDeclaredField("mediumCoverageLevelBias"); bias.setAccessible(true);
            assertEquals(0, bias.getInt(manager));
            assertEquals(0, manager.pendingCount());
            free.set(4096L * mib);
            assertEquals(false, reclaim.invoke(manager, 0, 0));
            enqueue(manager, target); awaitIdle(manager);
            assertTrue(manager.readyTiles().stream().anyMatch(tile -> tile.key().equals(target)));
            assertTrue(manager.readyTiles().stream().anyMatch(tile -> tile.key().equals(detail)));
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void backgroundBuildersDoNotInheritRenderThreadPriority() throws Exception {
        int previous=Thread.currentThread().getPriority();
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            try(var manager=manager(sampler(new AtomicInteger(),null,null),null)) {
                var field=PredictionTileManager.class.getDeclaredField("executor"); field.setAccessible(true);
                var pool=(java.util.concurrent.ThreadPoolExecutor)field.get(manager);
                var worker=pool.getThreadFactory().newThread(()->{});
                assertEquals(Thread.NORM_PRIORITY-1,worker.getPriority());
                assertTrue(worker.isDaemon());
            }
        } finally {Thread.currentThread().setPriority(previous);}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void spareWorkersFinishMultipleSurfacesWhileNewPreviewStillRuns(boolean nearbyPreview) throws Exception {
        var blocking = new java.util.concurrent.atomic.AtomicBoolean();
        var firstEntered = new CountDownLatch(1);
        var bothEntered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var base = sampler(new AtomicInteger(), null, null);
        var source = new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return blocking.get() ? 8 : 64; }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return base.sample(x,z); }
            @Override public int surfaceColor(int x, int y, int z) {
                if (blocking.get() && (x < -8192 || z < -8192)) {
                    firstEntered.countDown(); bothEntered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("surface worker stalled"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
                }
                return 0x70aa30;
            }
        };
        try (var manager = new PredictionTileManager(PROFILE.levelKey(), source,
                new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,4),null)) {
            int lod = manager.layout().levelCount()-1;
            assertEquals(8192, manager.layout().tileBlocks(lod));
            var first = new PredictionTileKey(PROFILE.levelKey(), -1, 0, lod);
            var second = new PredictionTileKey(PROFILE.levelKey(), 0, -1, lod);
            var preview = new PredictionTileKey(PROFILE.levelKey(), 0, 0, lod);
            desire(manager, first, true); desire(manager, second, true);
            enqueue(manager, first); awaitIdle(manager); enqueue(manager, second); awaitIdle(manager);
            assertTrue(manager.readyTiles().stream().allMatch(t -> t.cellAxis() == 64));
            var desired = PredictionTileManager.class.getDeclaredField("surfaceDesired"); desired.setAccessible(true);
            ((Set<PredictionTileKey>)desired.get(manager)).addAll(Set.of(first,second));
            var waiting = PredictionTileManager.class.getDeclaredField("previewWorkPending"); waiting.setAccessible(true);
            waiting.setBoolean(manager,true);
            var nearby = PredictionTileManager.class.getDeclaredField("surfacePreviewWorkPending"); nearby.setAccessible(true);
            nearby.setBoolean(manager,nearbyPreview);
            var submit = PredictionTileManager.class.getDeclaredMethod("enqueue",PredictionTileKey.class,int.class,int.class,boolean.class);
            submit.setAccessible(true);
            blocking.set(true);
            try {
                submit.invoke(manager,first,0,0,true); assertTrue(firstEntered.await(5,TimeUnit.SECONDS));
                long frameNow = System.nanoTime();
                for (int i=50;i>=0;i--) PredictionFramePace.recordFrame(frameNow-i*(nearbyPreview ? 10_000_000L : 66_666_667L));
                submit.invoke(manager,second,0,0,true);
                assertTrue(bothEntered.await(5,TimeUnit.SECONDS), "spare worker must be allowed to start the second surface");
                desire(manager,preview,true);
                // Actual uncovered horizon work uses the coverage lane, which
                // must still progress under the ordinary refinement fuse.
                var coverage = PredictionTileManager.class.getDeclaredField("mediumCoverage"); coverage.setAccessible(true);
                coverage.set(manager,new PredictionMediumCoverage(Set.of(preview),Set.of(preview)));
                var coveragePending = PredictionTileManager.class.getDeclaredField("mediumCoveragePending"); coveragePending.setAccessible(true);
                coveragePending.setBoolean(manager,true);
                enqueue(manager,preview);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(manager.readyTiles().stream().noneMatch(t->t.key().equals(preview)) && System.nanoTime()<until) Thread.sleep(5);
                assertTrue(manager.readyTiles().stream().anyMatch(t->t.key().equals(preview)), "borrowed surfaces must leave capacity for preview");
            } finally { blocking.set(false); release.countDown(); PredictionFramePace.resetForTesting(); }
            awaitIdle(manager);
            assertEquals(0,manager.failedTileCount());
            assertTrue(manager.surfaceDiagnostics().contains("borrowedSurfaceBuilds=" + (nearbyPreview ? 1 : 0)));
        }
    }

    @Test void denserPreviewPublishesInStagesAndOuterBandKeepsItsCheapFallback() throws Exception {
        var calls = new AtomicInteger();
        try (var manager = manager(sampler(calls, null, null, PredictionWorkOrder.INITIAL_CELL_AXIS), null)) {
            var key = new PredictionTileKey(PROFILE.levelKey(), 0, 0, manager.layout().levelCount() - 1);
            desire(manager, key, true);
            enqueue(manager, key); awaitIdle(manager);
            assertEquals(324, calls.get(), "first preview must not pay for a full 4,356-point grid");
            assertEquals(16, manager.readyTiles().iterator().next().cellAxis());
            enqueue(manager, key); awaitIdle(manager);
            assertEquals(32, manager.readyTiles().iterator().next().cellAxis());
            enqueue(manager, key); awaitIdle(manager);
            assertEquals(64, manager.readyTiles().iterator().next().cellAxis());
        }
        calls.set(0);
        try (var manager = manager(sampler(calls, null, null, PredictionWorkOrder.INITIAL_CELL_AXIS), null)) {
            var key = new PredictionTileKey(PROFILE.levelKey(), 0, 0, manager.layout().levelCount() - 1);
            desire(manager, key, true);
            var targets = PredictionTileManager.class.getDeclaredField("terrainTargets"); targets.setAccessible(true);
            targets.set(manager, java.util.Map.of(key, 8));
            enqueue(manager, key); awaitIdle(manager);
            enqueue(manager, key); awaitIdle(manager);
            assertEquals(100, calls.get());
            assertEquals(8, manager.readyTiles().iterator().next().cellAxis());
        }
    }

    @Test void declinedCurrentTileBacksOffAndCanRecover() throws Exception {
        var calls = new AtomicInteger();
        var base = sampler(calls, null, null);
        var decline = new java.util.concurrent.atomic.AtomicBoolean(true);
        var source = new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return 16; }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) {
                if (decline.get()) {
                    calls.incrementAndGet();
                    throw new java.util.concurrent.CancellationException("backend deferred this tile");
                }
                return base.sample(x, z);
            }
        };
        try (var manager = manager(source, null)) {
            var root = new PredictionTileKey(PROFILE.levelKey(), 0, 0, manager.layout().levelCount() - 1);
            desire(manager, root, true);
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(1, calls.get());
            decline.set(false);
            for (int i = 0; i < 5; i++) { enqueue(manager, root); awaitIdle(manager); }
            assertEquals(1, calls.get(), "a deferred current tile must not take a worker on every replan");
            var field = PredictionTileManager.class.getDeclaredField("failedAt"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var failures = (java.util.Map<PredictionTileKey, Long>) field.get(manager);
            failures.put(root, System.nanoTime() - TimeUnit.SECONDS.toNanos(31));
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(16, manager.readyTiles().iterator().next().cellAxis());
            assertFalse(failures.containsKey(root));
        }
    }

    @Test void slowFinalTerrainCannotOccupyTheWorkersNeededByOtherPreviews() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockFine = new java.util.concurrent.atomic.AtomicBoolean();
        var blocked = new AtomicInteger();
        var base = sampler(new AtomicInteger(), null, null);
        var source = new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return 8; }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {
                if (blockFine.get() && step == 128) {
                    blocked.incrementAndGet(); entered.countDown();
                    try { if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("preview starved behind final terrain"); }
                    catch(InterruptedException e) {Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
                }
                return base.sample(x,z);
            }
        };
        try (var manager = new PredictionTileManager(PROFILE.levelKey(),source,
                new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,2),null)) {
            assertEquals(8192,manager.layout().tileBlocks(manager.layout().levelCount()-1));
            var first=new PredictionTileKey(PROFILE.levelKey(),-1,0,manager.layout().levelCount()-1);
            var second=new PredictionTileKey(PROFILE.levelKey(),0,0,first.lod());
            var preview=new PredictionTileKey(PROFILE.levelKey(),0,1,first.lod());
            desire(manager,first,true);desire(manager,second,true);
            for(int stage=0;stage<3;stage++) {enqueue(manager,first);enqueue(manager,second);awaitIdle(manager);}
            assertTrue(manager.readyTiles().stream().allMatch(t->t.cellAxis()==32));
            desire(manager,preview,true);
            var waiting=PredictionTileManager.class.getDeclaredField("previewWorkPending");waiting.setAccessible(true);waiting.setBoolean(manager,true);
            blockFine.set(true);
            try {
                enqueue(manager,first); assertTrue(entered.await(5,TimeUnit.SECONDS));
                // A new uncovered region starts a coverage pass while an old
                // fine job drains; it must bypass ordinary refinement slots.
                var coverage=PredictionTileManager.class.getDeclaredField("mediumCoverage");coverage.setAccessible(true);
                coverage.set(manager,new PredictionMediumCoverage(Set.of(preview),Set.of(preview)));
                var coverageWaiting=PredictionTileManager.class.getDeclaredField("mediumCoveragePending");coverageWaiting.setAccessible(true);
                coverageWaiting.setBoolean(manager,true);
                enqueue(manager,second);enqueue(manager,preview);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(manager.readyTiles().stream().noneMatch(t->t.key().equals(preview)) && System.nanoTime()<deadline) Thread.sleep(5);
                assertTrue(manager.readyTiles().stream().anyMatch(t->t.key().equals(preview)),"preview needs a worker while full grids remain blocked");
                assertEquals(1,blocked.get(),"one worker remains for previews while expensive terrain waits");
            } finally {blockFine.set(false);release.countDown();}
            awaitIdle(manager);
            waiting.setBoolean(manager,false);
            var coverageWaiting=PredictionTileManager.class.getDeclaredField("mediumCoveragePending");coverageWaiting.setAccessible(true);
            coverageWaiting.setBoolean(manager,false);
            enqueue(manager,preview);awaitIdle(manager);
            enqueue(manager,preview);awaitIdle(manager);
            enqueue(manager,second);awaitIdle(manager);
            assertEquals(64,manager.readyTiles().stream().filter(t->t.key().equals(second)).findFirst().orElseThrow().cellAxis(),"deferred detail must retry without failure backoff");
            assertTrue(manager.surfaceDiagnostics().contains("detailActive=0"));
        }
    }

    @Test void firstCoveragePublishesBeforeFullSamplingAndUpgradeRetainsItUntilReady() throws Exception {
        AtomicInteger samples = new AtomicInteger();
        CountDownLatch refining = new CountDownLatch(1), release = new CountDownLatch(1);
        var sampler = sampler(samples, refining, release);
        PredictionTileKey root;
        try (var disk = new PredictionDiskCache(directory, PROFILE.fingerprint());
             var manager = manager(sampler, disk)) {
            root = new PredictionTileKey(PROFILE.levelKey(), -1, -1, manager.layout().levelCount() - 1);
            desire(manager, root, true);
            enqueue(manager, root);
            awaitIdle(manager);
            assertEquals(100, samples.get(), "first render must not wait for 4,356 full samples");
            var preview = manager.readyTiles().iterator().next();
            assertEquals(8, preview.cellAxis());
            assertEquals(81, preview.samples().length);
            assertEquals(manager.layout().tileBlocks(root.lod()), preview.spanBlocks());
            assertEquals(-preview.spanBlocks(), preview.baseBlockX());
            assertNotNull(preview.mesh().gpuPayload());
            try (var lease = disk.lease(PredictionDiskCache.Key.terrain(-1, -1, root.lod()))) {
                assertEquals(8, disk.readTerrainData(lease, 0).cellAxis(), "first preview must survive a restart");
            }
            enqueue(manager, root);
            assertTrue(refining.await(10, TimeUnit.SECONDS));
            assertSame(preview, manager.readyTiles().iterator().next(), "keep coverage while final grid is incomplete");
            release.countDown();
            awaitIdle(manager);
            var intermediate = manager.readyTiles().iterator().next();
            assertEquals(16, intermediate.cellAxis(), "first refinement should publish before the full grid");
            assertEquals(preview.spanBlocks(), intermediate.spanBlocks());
            try (var lease = disk.lease(PredictionDiskCache.Key.terrain(-1, -1, root.lod()))) {
                assertEquals(16, disk.readTerrainData(lease, 0).cellAxis(), "intermediate detail must survive a restart");
            }
            enqueue(manager, root);
            awaitIdle(manager);
            assertEquals(32, manager.readyTiles().iterator().next().cellAxis());
            enqueue(manager, root);
            awaitIdle(manager);
            var full = manager.readyTiles().iterator().next();
            assertEquals(64, full.cellAxis());
            assertEquals(preview.baseBlockX(), full.baseBlockX());
            assertEquals(preview.baseBlockZ(), full.baseBlockZ());
            assertEquals(preview.spanBlocks(), full.spanBlocks());
            assertTrue(full.revision() > preview.revision());
            disk.flush();
            try (var lease = disk.lease(PredictionDiskCache.Key.terrain(-1, -1, root.lod()))) {
                assertNotNull(disk.readTerrain(lease, 4356));
            }
        } finally { release.countDown(); }
        int completed = samples.get();
        try (var disk = new PredictionDiskCache(directory, PROFILE.fingerprint());
             var manager = manager(sampler, disk)) {
            desire(manager, root, true);
            enqueue(manager, root);
            awaitIdle(manager);
            assertEquals(completed, samples.get(), "existing final cache bypasses preview and all resampling");
            assertEquals(64, manager.readyTiles().iterator().next().cellAxis());
        }
    }

    @Test void childStartsWithParentCoverageWithoutPayingForParentsFullGrid() throws Exception {
        var samples = new AtomicInteger();
        try (var manager = manager(sampler(samples, null, null), null)) {
            var root = new PredictionTileKey(PROFILE.levelKey(), 0, 0, manager.layout().levelCount() - 1);
            var child = new PredictionTileKey(PROFILE.levelKey(), 0, 0, root.lod() - 1);
            desire(manager, root, false);
            desire(manager, child, true);
            enqueue(manager, root);
            awaitIdle(manager);
            enqueue(manager, root);
            enqueue(manager, child);
            awaitIdle(manager);
            assertEquals(200, samples.get());
            assertEquals(2, manager.readyCount());
            assertTrue(manager.readyTiles().stream().allMatch(t -> t.cellAxis() == 8));
        }
    }

    @Test void dirtyIntermediateRestoresItsResolutionBeforeAdvancing() throws Exception {
        try (var manager = manager(sampler(new AtomicInteger(), null, null), null)) {
            var root = new PredictionTileKey(PROFILE.levelKey(), -1, 0, manager.layout().levelCount() - 1);
            desire(manager, root, true);
            enqueue(manager, root); awaitIdle(manager);
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(16, manager.readyTiles().iterator().next().cellAxis());
            var dirty = PredictionTileManager.class.getDeclaredField("dirtyTiles"); dirty.setAccessible(true);
            @SuppressWarnings("unchecked") var keys = (Set<PredictionTileKey>) dirty.get(manager);
            keys.add(root);
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(16, manager.readyTiles().iterator().next().cellAxis());
            assertFalse(keys.contains(root));
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(32, manager.readyTiles().iterator().next().cellAxis());
        }
    }

    @Test void bandTargetsStopAtTheirOwnResolutionAndCanLaterUpgrade() throws Exception {
        for (int target : new int[]{8,32,64}) {
            try (var manager = manager(sampler(new AtomicInteger(), null, null), null)) {
                var key = new PredictionTileKey(PROFILE.levelKey(),0,0,manager.layout().levelCount()-1);
                desire(manager,key,true);
                var field = PredictionTileManager.class.getDeclaredField("terrainTargets");
                field.setAccessible(true);
                field.set(manager,java.util.Map.of(key,target));
                for (int i=0; i<5; i++) { enqueue(manager,key); awaitIdle(manager); }
                assertEquals(target,manager.readyTiles().iterator().next().cellAxis());
                long builds = manager.builtTileCount();
                enqueue(manager,key); awaitIdle(manager);
                assertEquals(builds,manager.builtTileCount(),"Completed band must not loop on full-grid work");
                field.set(manager,java.util.Map.of(key,64));
                for (int i=0; i<3; i++) { enqueue(manager,key); awaitIdle(manager); }
                assertEquals(64,manager.readyTiles().iterator().next().cellAxis());
                field.set(manager,java.util.Map.of(key,8));
                enqueue(manager,key); awaitIdle(manager);
                assertEquals(64,manager.readyTiles().iterator().next().cellAxis(),"Movement must retain loaded finer detail");
            }
        }
    }

    @Test void liveTicksAdvanceNearbyAndNewTelescopeTargetWithoutManualEnqueue() throws Exception {
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 65536;
        try (var manager = manager(sampler(new AtomicInteger(), null, null, PredictionWorkOrder.INITIAL_CELL_AXIS), null)) {
            advanceAt(manager, null, 282, -85);
            advanceAt(manager, new VssLodFocus(8192, 8192, 1024, 1000), 8192, 8192);
        } finally { VSSClientConfig.CONFIG.predictionDistanceBlocks = distance; }
    }

    @Test void unrelatedCapturedColumnsDoNotCancelInFlightCoarseRefinement() throws Exception {
        CountDownLatch refining = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var manager = manager(sampler(new AtomicInteger(), refining, release), null)) {
            var root = new PredictionTileKey(PROFILE.levelKey(), -1, -1, manager.layout().levelCount() - 1);
            desire(manager, root, true);
            enqueue(manager, root); awaitIdle(manager);
            enqueue(manager, root);
            assertTrue(refining.await(10, TimeUnit.SECONDS));
            // This column lies between even the final grid's sample lines.
            for (int i = 0; i < 20; i++) manager.capturedTerrainChanged(-3, -3);
            release.countDown(); awaitIdle(manager);
            assertEquals(16, manager.readyTiles().iterator().next().cellAxis(),
                    "Unrelated exact-column traffic discarded a complete refinement stage");
            int baseChunk = -manager.layout().tileBlocks(root.lod()) / 16;
            manager.capturedTerrainChanged(baseChunk, baseChunk);
            enqueue(manager, root); awaitIdle(manager);
            assertEquals(16, manager.readyTiles().iterator().next().cellAxis(),
                    "A captured sample must rebuild current resolution before advancing");
        } finally { release.countDown(); }
    }

    @Test void obsoleteCaptureStopsRefinementBeforeColorAndMeshWork() throws Exception {
        var calls = new AtomicInteger();
        var refining = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var manager = manager(sampler(calls, refining, release), null)) {
            var root = new PredictionTileKey(PROFILE.levelKey(), -1, -1, manager.layout().levelCount()-1);
            desire(manager,root,true); enqueue(manager,root); awaitIdle(manager);
            long built = manager.builtTileCount();
            enqueue(manager,root); assertTrue(refining.await(10,TimeUnit.SECONDS));
            int before=calls.get();
            int chunk = -manager.layout().tileBlocks(root.lod())/16;
            manager.capturedTerrainChanged(chunk,chunk);
            release.countDown(); awaitIdle(manager);
            assertEquals(built,manager.builtTileCount(),"obsolete refinement must not replace retained coverage");
            assertEquals(before,calls.get(),"stop after the in-flight sample, not after the entire obsolete grid");
            enqueue(manager,root); awaitIdle(manager);
            assertTrue(manager.builtTileCount()>built,"current capture epoch can rebuild without failure backoff");
        } finally { release.countDown(); }
    }

    @Test void obsoleteQueuedCaptureDoesNoSamplingBeforeRetry() throws Exception {
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        try (var manager = manager(sampler(calls, null, null), null)) {
            var root = new PredictionTileKey(PROFILE.levelKey(), -1, -1, manager.layout().levelCount()-1);
            desire(manager, root, true); enqueue(manager, root); awaitIdle(manager);
            var field = PredictionTileManager.class.getDeclaredField("executor"); field.setAccessible(true);
            var executor = (java.util.concurrent.ThreadPoolExecutor) field.get(manager);
            int workers = executor.getCorePoolSize();
            var entered = new CountDownLatch(workers);
            for (int i = 0; i < workers; i++) executor.execute(new PredictionTileManager.PredictionTask(root, Integer.MIN_VALUE, 0, () -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            int before = calls.get(); long built = manager.builtTileCount();
            enqueue(manager, root);
            int chunk = -manager.layout().tileBlocks(root.lod()) / 16;
            manager.capturedTerrainChanged(chunk, chunk);
            release.countDown(); awaitIdle(manager);
            assertEquals(before, calls.get(), "stale queued jobs must exit before their first terrain query");
            assertEquals(built, manager.builtTileCount());
            enqueue(manager, root); awaitIdle(manager);
            assertTrue(manager.builtTileCount() > built, "latest capture remains eligible for rebuilding");
        } finally { release.countDown(); }
    }

    private static void advanceAt(PredictionTileManager manager, VssLodFocus focus, int x, int z) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            manager.tick(282, 151, -85, 700, focus, 128);
            if (manager.readyTiles().stream().anyMatch(t -> t.key().lod() == 0 && t.cellAxis() == 64
                    && x >= t.baseBlockX() && x < t.baseBlockX() + t.spanBlocks()
                    && z >= t.baseBlockZ() && z < t.baseBlockZ() + t.spanBlocks())) return;
            Thread.sleep(10);
        }
        var leafField = PredictionTileManager.class.getDeclaredField("terrainLeaves"); leafField.setAccessible(true);
        @SuppressWarnings("unchecked") var leafKeys = (Set<PredictionTileKey>) leafField.get(manager);
        var plannedAtTarget = leafKeys.stream().filter(k -> Math.floorDiv(x,manager.layout().tileBlocks(k.lod())) == k.tileX()
                && Math.floorDiv(z,manager.layout().tileBlocks(k.lod())) == k.tileZ()).toList();
        fail("Live scheduler did not refine target " + x + "," + z + ": " + plannedAtTarget + " " + java.util.Arrays.toString(manager.readyLodCounts())
                + ", pending=" + manager.pendingCount() + ", built=" + manager.builtTileCount()
                + ", failed=" + manager.failedTileCount() + ", " + manager.surfaceDiagnostics()
                + ", target=" + manager.readyTiles().stream().filter(t -> x >= t.baseBlockX() && x < t.baseBlockX()+t.spanBlocks()
                        && z >= t.baseBlockZ() && z < t.baseBlockZ()+t.spanBlocks()).map(t -> t.key()+"/"+t.cellAxis()).toList());
    }

    @Test void captureGridIncludesNegativeCoordinatesAndEveryProgressiveMargin() {
        var layout = VssLodLayout.of(65536, 2, true, true);
        for (int lod = 0; lod < layout.levelCount(); lod++) {
            int span = layout.tileBlocks(lod);
            for (int tileX : new int[]{-1, 0, 2}) {
                var key = new PredictionTileKey(PROFILE.levelKey(), tileX, -1, lod);
                for (int axis : lod >= 2 ? new int[]{8,16,32,64} : new int[]{64}) {
                    int step = span / axis;
                    for (int sample = -1; sample <= axis; sample++) {
                        int x = tileX * span + sample * step, z = -span + sample * step;
                        assertTrue(PredictionTileManager.captureIntersectsGrid(key, layout,
                                Math.floorDiv(x,16), Math.floorDiv(z,16)), "Lost sampled column at " + key);
                    }
                }
                assertFalse(PredictionTileManager.captureIntersectsGrid(key, layout,
                        Math.floorDiv((tileX+1)*span+16,16), 0));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={8,16,32})
    void restartRestoresEveryPreviewAndColorsThenStillRefines(int axis) throws Exception {
        var samples=new AtomicInteger();var colors=new AtomicInteger();
        var appearance=new java.util.concurrent.atomic.AtomicLong(123);
        var base=sampler(samples,null,null);
        var source=new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
            @Override int initialTerrainCellAxis(int lod) {return 8;}
            @Override long colorCacheFingerprint() {return appearance.get();}
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) {return base.sample(x,z);}
            @Override public int surfaceColor(int x,int y,int z) {colors.incrementAndGet();return 0xff70aa30;}
            @Override public int foliageColor(int x,int y,int z) {colors.incrementAndGet();return 0xff50bb20;}
        };
        PredictionTileKey root;
        long coldStart=System.nanoTime();
        try(var disk=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(source,disk)) {
            root=new PredictionTileKey(PROFILE.levelKey(),-1,-1,manager.layout().levelCount()-1);
            desire(manager,root,true);
            for(int stage=8;stage<=axis;stage*=2){enqueue(manager,root);awaitIdle(manager);}
            assertEquals(axis,manager.readyTiles().iterator().next().cellAxis());
        }
        double coldMs=(System.nanoTime()-coldStart)/1e6;
        assertTrue(samples.getAndSet(0)>0);assertTrue(colors.getAndSet(0)>0);
        long warmStart=System.nanoTime();
        try(var disk=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(source,disk)) {
            desire(manager,root,true);enqueue(manager,root);awaitIdle(manager);
            assertEquals(axis,manager.readyTiles().iterator().next().cellAxis());
            assertEquals(0,samples.get(),"warm previews must not resample heights");
            assertEquals(0,colors.get(),"warm previews must not resample biome tints");
            assertTrue(disk.hits()>0);
        }
        System.out.printf("PREVIEW_RESTART axis=%d coldMs=%.2f warmMs=%.2f warmHeightCalls=0 warmTintCalls=0%n",axis,coldMs,(System.nanoTime()-warmStart)/1e6);
        appearance.incrementAndGet();
        try(var disk=new PredictionDiskCache(directory,PROFILE.fingerprint());var manager=manager(source,disk)) {
            desire(manager,root,true);enqueue(manager,root);awaitIdle(manager);
            assertEquals(0,samples.get(),"resource colormap changes keep stored terrain reusable");
            assertTrue(colors.get()>0,"new resource colormaps must replace cached colors");
            enqueue(manager,root);awaitIdle(manager);
            assertEquals(axis*2,manager.readyTiles().iterator().next().cellAxis(),"restored previews must not block the next refinement");
            assertTrue(samples.get()>0);
            manager.invalidate(root.tileX()*manager.layout().tileBlocks(root.lod())/16,
                    root.tileZ()*manager.layout().tileBlocks(root.lod())/16);
            disk.flush();
            try(var lease=disk.lease(PredictionDiskCache.Key.terrain(root.tileX(),root.tileZ(),root.lod()))) {
                assertNull(disk.readTerrainData(lease,0),"dirty columns invalidate persisted previews as well");
            }
        }
    }

    private static ClientTerrainSampler sampler(AtomicInteger calls, CountDownLatch refining, CountDownLatch release) {
        return sampler(calls, refining, release, 8);
    }

    private static ClientTerrainSampler sampler(AtomicInteger calls, CountDownLatch refining, CountDownLatch release, int initialAxis) {
        return new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
            @Override int initialTerrainCellAxis(int lod) { return initialAxis; }
            @Override public ClientColumnSample sample(int x, int z) {
                if (calls.incrementAndGet() == (initialAxis + 2) * (initialAxis + 2) + 1 && refining != null) {
                    refining.countDown();
                    try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("refinement timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
                }
                return new ClientColumnSample(64, 64, 0, PredictionMaterialPalette.grassBlockIndex(), 0, 0, 0, 0, 0,
                        ClientColumnSample.FLAG_SURFACE_ONLY, 0, PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.stoneIndex(),
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            }
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
            @Override public int surfaceColor(int x, int y, int z) { return 0xff70aa30; }
            @Override public int foliageColor(int x, int y, int z) { return 0xff70aa30; }
        };
    }
    private static PredictionTileManager manager(ClientTerrainSampler sampler, PredictionDiskCache disk) {
        return new PredictionTileManager(PROFILE.levelKey(), sampler,
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 1), disk);
    }
    @SuppressWarnings("unchecked")
    private static void desire(PredictionTileManager manager, PredictionTileKey key, boolean leaf) throws Exception {
        var desired = PredictionTileManager.class.getDeclaredField("desiredKeys"); desired.setAccessible(true);
        ((Set<PredictionTileKey>) desired.get(manager)).add(key);
        if (leaf) {
            var leaves = PredictionTileManager.class.getDeclaredField("terrainLeaves"); leaves.setAccessible(true);
            ((Set<PredictionTileKey>) leaves.get(manager)).add(key);
        }
    }
    private static void enqueue(PredictionTileManager manager, PredictionTileKey key) throws Exception {
        var method = PredictionTileManager.class.getDeclaredMethod("enqueue", PredictionTileKey.class, int.class, int.class);
        method.setAccessible(true); method.invoke(manager, key, 0, 0);
    }
    private static void awaitIdle(PredictionTileManager manager) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (manager.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, manager.pendingCount());
        assertEquals(0, manager.failedTileCount());
    }
}
