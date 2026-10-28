package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real admission, reservations, generation and CPU/GPU completion. */
class PredictionViewRefinementTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    private static PredictionTileKey FRONT, REAR;

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        FRONT = new PredictionTileKey(PROFILE.levelKey(), 4, 0, 1);
        REAR = new PredictionTileKey(PROFILE.levelKey(), -4, 0, 1);
    }
    @BeforeEach void resetFrames() { PredictionFramePace.resetForTesting(); }
    @AfterEach void clearFrames() { PredictionFramePace.resetForTesting(); }

    @Test void projectedZoomBuildsMissingVisibleDetailAndZoomOutRetainsTheFinishedMesh() throws Exception {
        try (var f = new Fixture()) {
            var far = new PredictionTileKey(PROFILE.levelKey(), 4, 0, 3);
            f.plan(far, REAR);
            f.ready().put(far, f.tile(far, 32));
            f.face(1);
            var camera = new net.minecraft.world.phys.Vec3(0, 80, 0);
            var direction = new net.minecraft.world.phys.Vec3(1, 0, 0);
            var model = new org.joml.Matrix4f().lookAlong(1, 0, 0, 0, 1, 0);
            double base = VssLodProjection.selectionScale(PredictionVoxelFixtures.projection(70), 256);
            double enlarged = VssLodProjection.selectionScale(PredictionVoxelFixtures.projection(8.75), 256);
            var normal = PredictionWorkView.rendered(PredictionVoxelFixtures.projection(70), model, camera, direction, base);
            var zoom = PredictionWorkView.rendered(PredictionVoxelFixtures.projection(8.75), model, camera, direction,
                    VssLodProjection.refinementScale(enlarged, base, true, true));
            f.manager.setRenderWorkView(normal);
            assertEquals(32, PredictionDemandTestSupport.ordinaryAxis(f.manager, far, 0D, 80D, 0D, base, null));
            f.manager.setRenderWorkView(zoom);
            PredictionDemandTestSupport.refresh(f.manager);
            assertEquals(64, PredictionDemandTestSupport.ordinaryAxis(f.manager, far, 0D, 80D, 0D, base, null));
            f.enqueue(far); f.awaitIdle();
            var finished = f.ready().get(far);
            assertEquals(64, finished.cellAxis()); assertTrue(f.samples.get() > 0);
            f.manager.setRenderWorkView(normal);
            assertEquals(32, PredictionDemandTestSupport.ordinaryAxis(f.manager, far, 0D, 80D, 0D, base, null));
            assertSame(finished, f.ready().get(far), "zooming out keeps a reusable high quality CPU mesh");
        }
    }

    @Test void distantVisibleTargetBuildsWithoutIdleOrSixtyFpsAndRearStaysStopped() throws Exception {
        try (var f = new Fixture()) {
            var far = new PredictionTileKey(PROFILE.levelKey(), 4, 0, 3);
            f.plan(far, REAR);
            f.ready().put(parent(far), f.tile(parent(far), 32));
            f.face(1);
            long now = System.nanoTime();
            for (int i = 0; i <= 40; i++) PredictionFramePace.recordFrame(now - (40L - i) * 25_000_000L);
            assertEquals(40.0, PredictionFramePace.averageFps(), .01);
            assertEquals(64, PredictionDemandTestSupport.ordinaryAxis(f.manager, far, 0D, 80D, 0D, 1000D, null));
            assertEquals(false, get(f.manager, "idleAllowed"));
            f.enqueue(far);
            f.awaitIdle();
            assertEquals(64, f.ready().get(far).cellAxis(), "visible distant error is ordinary detail, not idle-only quality");
            f.face(1);
            assertEquals(false, get(f.manager, "foregroundWorkPending"));
            int work = f.samples.get();
            for (int i = 0; i < 100; i++) f.enqueue(REAR);
            assertEquals(0, f.manager.pendingCount());
            assertEquals(work, f.samples.get(), "completing the view does not resume invisible expensive upgrades");
        }
    }

    @Test void screenSatisfiedLeafDoesNotOweTheMediumBootstrapUpgrade() throws Exception {
        try (var f = new Fixture()) {
            var far = new PredictionTileKey(PROFILE.levelKey(), 100, 0, 2);
            f.plan(far, REAR);
            f.ready().put(far, f.tile(far, 8));
            set(f.manager, "ordinaryTargets", Map.of(far, 8));
            // Coverage refresh precedes publication of the new combined terrain/idle targets.
            set(f.manager, "terrainTargets", Map.of(far, 64));
            set(f.manager, "mediumCoverage", new PredictionMediumCoverage(Set.of(far), Set.of(far, parent(far))));
            set(f.manager, "mediumCoveragePending", true);
            f.face(1);
            assertEquals(false, invoke(f.manager, "mediumCoverageWork", new Class<?>[]{PredictionTileKey.class}, far));
            assertEquals(true, invoke(f.manager, "localWorkAllowed", new Class<?>[]{PredictionTileKey.class, boolean.class}, far, true));
            set(f.manager, "terrainTargets", Map.of(far, 8));
            f.enqueue(far);
            f.awaitIdle();
            assertEquals(0, f.samples.get(), "pixel-satisfied ordinary leaves must not repeatedly upgrade to 32");
            assertEquals(8, f.ready().get(far).cellAxis());
        }
    }

    @Test void nearVisibleCellsOutrankDistantCellsWithLargerScreenError() throws Exception {
        try (var f = new Fixture()) {
            var far = new PredictionTileKey(PROFILE.levelKey(), 4, 0, 3);
            f.plan(FRONT, far);
            f.ready().put(far, f.tile(far, 8));
            f.face(1);
            set(f.manager, "schedulingPixels", 1000D);
            int nearPriority = (int) invoke(f.manager, "workPriority", new Class<?>[]{PredictionTileKey.class, boolean.class}, FRONT, false);
            int farPriority = (int) invoke(f.manager, "workPriority", new Class<?>[]{PredictionTileKey.class, boolean.class}, far, false);
            assertTrue(nearPriority < farPriority, "screen error refines ordering within a distance band rather than reversing near-first loading");
        }
    }

    @Test void hiddenFarTerrainYieldsToExposedTerrainButRemainsAdmitted() throws Exception {
        try (var f = new Fixture()) {
            var far = new PredictionTileKey(PROFILE.levelKey(), 4, 0, 3);
            f.plan(FRONT, far);
            f.ready().put(far, f.tile(far, 8));
            f.face(1);
            set(f.manager, "schedulingPixels", 1000D);
            var history = new PredictionVisibilityFeedback.History();
            long now = System.nanoTime();
            var feedback = history.observe(List.of(new PredictionVisibilityFeedback.Stamp(far, 8, 0)),
                    new boolean[]{true}, PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 16D / 9), now, now);
            f.manager.setVisibilityFeedback(feedback);
            int nearPriority = (int) invoke(f.manager, "workPriority", new Class<?>[]{PredictionTileKey.class, boolean.class}, FRONT, false);
            int hiddenPriority = (int) invoke(f.manager, "workPriority", new Class<?>[]{PredictionTileKey.class, boolean.class}, far, false);
            assertTrue(hiddenPriority > nearPriority, "confirmed hidden error must yield to exposed work");
            f.enqueue(far); f.awaitIdle();
            assertTrue(f.ready().get(far).cellAxis() > 8, "hidden scheduling hints must not remove coverage or prevent progress");
            assertEquals(false, invoke(f.manager, "occludedWork", new Class<?>[]{PredictionTileKey.class}, far),
                    "newly published terrain invalidates the old hint");
        }
    }

    @Test void unseenNearRefinementWaitsBeforeAdmissionAndTurningRestoresIt() throws Exception {
        try (var f = new Fixture()) {
            f.plan(FRONT, REAR);
            f.face(1);
            for (int i = 0; i < 100; i++) f.enqueue(REAR);
            assertEquals(0, f.manager.pendingCount(), "offscreen upgrades must never consume queue/CPU reservations");
            assertEquals(0, f.samples.get());
            assertEquals(32, f.ready().get(REAR).cellAxis());
            assertEquals(0, reserved(f.cpu));
            f.face(-1);
            f.enqueue(REAR);
            f.awaitIdle();
            assertTrue(f.samples.get() > 0);
            assertEquals(64, f.ready().get(REAR).cellAxis());
            assertTrue(f.ready().containsKey(parent(REAR)), "the coarse parent must survive the turn");
        }
    }

    @Test void minimumRearCoverageRunsWhileItsUpgradeWaitsForVisibleDetail() throws Exception {
        try (var f = new Fixture()) {
            var coarse = new PredictionTileKey(PROFILE.levelKey(), -4, 0, 2);
            f.plan(FRONT, coarse);
            f.ready().remove(coarse);
            set(f.manager, "mediumCoverage", new PredictionMediumCoverage(Set.of(coarse), Set.of(coarse, parent(coarse))));
            set(f.manager, "mediumCoveragePending", true);
            f.face(1);
            f.enqueue(coarse);
            f.awaitIdle();
            assertNotNull(f.ready().get(coarse), "all-direction minimum coverage must remain admitted");
            assertEquals(8, f.ready().get(coarse).cellAxis());
            int calls = f.samples.get();
            f.enqueue(coarse);
            f.awaitIdle();
            assertEquals(calls, f.samples.get(), "the rear upgrade to 32 must wait while visible targets are missing");
            assertEquals(8, f.ready().get(coarse).cellAxis());
            assertTrue(f.ready().containsKey(parent(coarse)));
        }
    }

    @Test void turnRemovesUnstartedRearReservationAndAdmitsNewForeground() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var f = new Fixture()) {
            f.plan(FRONT, REAR);
            f.face(-1);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) get(f.manager, "executor");
            executor.execute(new PredictionTileManager.PredictionTask(null, 0, 0D, () -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.enqueue(REAR);
            assertTrue(f.pending().contains(REAR));
            assertEquals(1, reserved(f.cpu));
            f.face(1);
            invoke(f.manager, "refreshQueuedWork", new Class<?>[0]);
            assertFalse(f.pending().contains(REAR));
            assertEquals(0, reserved(f.cpu), "cancelled queued work must release its CPU reservation immediately");
            assertFalse(((Set<?>) get(f.manager, "refinementPending")).contains(REAR));
            f.enqueue(FRONT);
            assertTrue(f.pending().contains(FRONT), "released capacity must be reusable before the running worker finishes");
            assertEquals(1, reserved(f.cpu));
            assertEquals(4, f.ready().size(), "queue cancellation must not remove resident geometry");
            release.countDown();
            f.awaitIdle();
            assertEquals(64, f.ready().get(FRONT).cellAxis());
        } finally { release.countDown(); }
    }

    @Test void visibleReadyRequiresVoxyUploadAndIgnoresRearDebt() throws Exception {
        try (var f = new Fixture(); var nativeUpload = new PredictionVoxyUploadFixture()) {
            f.plan(FRONT, REAR);
            var complete = f.tile(FRONT, 64);
            f.ready().put(FRONT, complete);
            f.keys("surfaceDesired").addAll(List.of(FRONT, REAR));
            f.keys("surfaceReady").add(FRONT);
            f.face(1);
            var progress = f.progress(List.of(FRONT, REAR));
            assertEquals(2, progress.targetTotal(), "the full surrounding CPU progress must remain available");
            assertEquals(1, progress.targetReady());
            assertEquals(1, progress.view().total());
            assertEquals(1, progress.view().terrainReady());
            assertEquals(1, progress.view().deferred());
            assertFalse(progress.ready(95));
            assertEquals("view-upload", progress.waitingFor(95));
            nativeUpload.upload(complete);
            var position=dev.xantha.vss.compat.StrictVoxyNodeIndex.key(0,
                    Math.floorDiv(complete.data().voxyTile.baseX(),32),2,Math.floorDiv(complete.data().voxyTile.baseZ(),32));
            nativeUpload.pipeline.queued(position);
            assertFalse(f.progress(List.of(FRONT,REAR)).ready(95),"resident nodes cannot finish a pending native mesh update");
            nativeUpload.pipeline.started(position).uploaded();
            assertTrue(f.progress(List.of(FRONT, REAR)).ready(95), "rear target and vegetation debt must not hold pregen forever");
            f.face(-1);
            assertFalse(f.progress(List.of(FRONT, REAR)).ready(95), "turning must publish the new view's detail debt");
            f.keys("dirtyTiles").add(FRONT);
            f.face(1);
            assertFalse(f.progress(List.of(FRONT, REAR)).ready(95), "an invalidated visible tile must reacquire readiness");
        }
    }

    @Test void offscreenDependencyTerrainRunsWhileItsVegetationDoesNotHoldForeground() throws Exception {
        try (var f = new Fixture()) {
            f.plan(FRONT, REAR);
            f.ready().put(FRONT, f.tile(FRONT, 64));
            f.keys("surfaceDesired").add(REAR);
            f.face(1);
            set(f.manager, "viewDependencies", Set.of(REAR));
            assertEquals(true, PredictionDemandTestSupport.foreground(f.manager));
            set(f.manager, "foregroundWorkPending", true);
            assertEquals(false, invoke(f.manager, "deferBackground",
                    new Class<?>[]{PredictionTileKey.class, boolean.class}, REAR, false));
            assertEquals(true, invoke(f.manager, "deferBackground",
                    new Class<?>[]{PredictionTileKey.class, boolean.class}, REAR, true));
            f.enqueue(REAR);
            f.awaitIdle();
            assertEquals(64, f.ready().get(REAR).cellAxis());
            assertEquals(false, PredictionDemandTestSupport.foreground(f.manager),
                    "offscreen vegetation must not wait on the foreground gate that deferred it");
            set(f.manager, "foregroundWorkPending", false);
            set(f.manager, "idleAllowed", true);
            long now = System.nanoTime();
            for (int i = 0; i <= 40; i++) PredictionFramePace.recordFrame(now - (40L - i) * 10_000_000L);
            assertEquals(true, invoke(f.manager, "idleAdmissionReady", new Class<?>[0]),
                    "the fixture must satisfy the independent idle resource gates");
            f.pending().add(REAR);
            assertEquals(false, invoke(f.manager, "idleAdmissionReady", new Class<?>[0]),
                    "a queued terrain dependency must still protect the visible handoff");
            f.keys("surfacePending").add(REAR);
            assertEquals(true, invoke(f.manager, "idleAdmissionReady", new Class<?>[0]),
                    "a queued offscreen surface cannot block visible optional quality");
            f.pending().remove(REAR);
            f.keys("surfacePending").remove(REAR);
        }
    }

    @Test void voxyOwnershipRemovalAndRevocationUpdateVisibleDebt() throws Exception {
        try (var f = new Fixture()) {
            f.plan(FRONT, REAR);
            f.face(1);
            assertFalse(f.progress(List.of(FRONT, REAR)).ready(95));
            set(f.manager, "voxyOwnedTiles", Set.of(FRONT));
            f.face(1);
            assertEquals(0, f.progress(List.of(FRONT, REAR)).view().total());
            assertTrue(f.progress(List.of(FRONT, REAR)).ready(95));
            set(f.manager, "voxyOwnedTiles", Set.of());
            f.face(1);
            assertEquals(1, f.progress(List.of(FRONT, REAR)).view().total());
            assertFalse(f.progress(List.of(FRONT, REAR)).ready(95),
                    "Voxy ownership withdrawal must restore visible prediction debt");
        }
    }

    @Test void optionalUpgradePreservesOrdinaryVoxyReadinessUntilCaptureChanges() throws Exception {
        try (var f = new Fixture(); var nativeUpload = new PredictionVoxyUploadFixture()) {
            f.plan(FRONT, REAR);
            var ordinary = f.tile(FRONT, 64);
            f.ready().put(FRONT, ordinary);
            f.face(1);
            nativeUpload.upload(ordinary);
            assertTrue(f.progress(List.of(FRONT, REAR)).ready(95));
            var position=dev.xantha.vss.compat.StrictVoxyNodeIndex.key(0,
                    Math.floorDiv(ordinary.data().voxyTile.baseX(),32),2,Math.floorDiv(ordinary.data().voxyTile.baseZ(),32));
            nativeUpload.pipeline.queued(position);
            set(f.manager, "idleTargets", Map.of(FRONT, 128));
            set(f.manager, "terrainTargets", Map.of(FRONT, 128, REAR, 64));
            f.ready().put(FRONT, f.tile(FRONT, 128));
            assertTrue(f.progress(List.of(FRONT, REAR)).ready(95),
                    "a pending optional GPU upgrade cannot reacquire the ordinary generation turn");
            synchronized(dev.xantha.vss.compat.StrictLodVisibility.class) { nativeUpload.nodes.clear(); }
            assertFalse(f.progress(List.of(FRONT,REAR)).ready(95),"actual Voxy node loss retracts the retained ordinary completion");
            nativeUpload.pipeline.started(position).uploaded(); nativeUpload.upload(ordinary);
            assertTrue(f.progress(List.of(FRONT,REAR)).ready(95));
            set(f.manager, "captureEpochs", new java.util.concurrent.ConcurrentHashMap<>(Map.of(FRONT, 1L)));
            f.keys("dirtyTiles").add(FRONT);
            assertFalse(f.progress(List.of(FRONT, REAR)).ready(95),
                    "content invalidation must revoke the old ordinary GPU goal immediately");
        }
    }

    @Test void rearDependenciesAndDirtyRecoveryRemainAdmitted() throws Exception {
        try (var f = new Fixture()) {
            f.plan(FRONT, REAR);
            f.face(1);
            set(f.manager, "viewDependencies", Set.of(REAR));
            set(f.manager, "transitionTargets", Map.of(REAR, 64));
            f.enqueue(REAR);
            f.awaitIdle();
            assertEquals(64, f.ready().get(REAR).cellAxis(), "an offscreen seam/transition dependency must complete");
            f.keys("dirtyTiles").add(REAR);
            f.enqueue(REAR);
            f.awaitIdle();
            assertFalse(f.keys("dirtyTiles").contains(REAR), "dirty coverage recovery must not be hidden behind the view gate");
        }
    }

    @Test void turningRefreshesAdmissionWithoutRebuildingTheSpatialPlan() throws Exception {
        try (var f = new Fixture()) {
            f.plan(FRONT, REAR);
            f.ready().put(parent(FRONT), f.tile(parent(FRONT), 32));
            f.face(-1);
            var plan = List.of(FRONT, REAR, parent(FRONT), parent(REAR));
            set(f.manager, "scheduledPlan", plan);
            set(f.manager, "scheduledLeaves", List.of(FRONT, REAR));
            set(f.manager, "selectionTicks", 1);
            f.manager.setWorkView(PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 16D / 9));
            f.manager.tick(0, 80, 0, 1000, null);
            assertSame(plan, get(f.manager, "scheduledPlan"), "a turn must reuse the spatial plan between five-tick rebuilds");
            assertEquals(true, invoke(f.manager, "backgroundWork", new Class<?>[]{PredictionTileKey.class}, REAR));
            var preparation = get(f.manager, "demandPreparation");
            ((java.util.concurrent.CompletableFuture<?>) get(preparation, "pending")).get(10, TimeUnit.SECONDS);
            f.manager.tick(0, 80, 0, 1000, null);
            assertSame(plan, get(f.manager, "scheduledPlan"), "publishing prepared view priorities keeps the spatial plan");
            PredictionClientTestTicks.awaitPreparation(f.manager);
            f.manager.tick(0, 80, 0, 1000, null);
            f.awaitIdle();
            assertEquals(64, f.ready().get(FRONT).cellAxis());
            assertEquals(32, f.ready().get(REAR).cellAxis());
        }
    }

    private static PredictionTileKey parent(PredictionTileKey key) {
        return new PredictionTileKey(key.dimension(), key.tileX() >> 1, key.tileZ() >> 1, key.lod() + 1);
    }

    private static int reserved(PredictionCpuBudget cpu) throws Exception {
        return (int) get(cpu, "reserved") + (int) get(cpu, "queuedOverflow");
    }

    private static Object get(Object target, String name) throws Exception {
        return PredictionPlanningTestSupport.get(target, name);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        PredictionPlanningTestSupport.set(target, name, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger samples = new AtomicInteger();
        final ClientColumnSample sample = PredictionVoxelFixtures.sample(64);
        final PredictionCpuBudget cpu = PredictionCpuBudget.fixed(1);
        final PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(),
                new ClientTerrainSampler(PROFILE.seed(), PROFILE) {
                    @Override int initialTerrainCellAxis(int lod) { return PredictionWorkOrder.initialCellAxis(lod); }
                    @Override public ClientColumnSample sample(int x, int z) { samples.incrementAndGet(); return sample; }
                    @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
                }, new PredictionMemoryBudget(1024L * PredictionMemoryBudget.MIB, 0,
                        () -> Long.MAX_VALUE, System::nanoTime, 1), null, cpu);

        @SuppressWarnings("unchecked") Map<PredictionTileKey, PredictionTile> ready() throws Exception {
            return (Map<PredictionTileKey, PredictionTile>) get(manager, "ready");
        }
        @SuppressWarnings("unchecked") Set<PredictionTileKey> keys(String name) throws Exception {
            return (Set<PredictionTileKey>) get(manager, name);
        }
        Set<PredictionTileKey> pending() throws Exception { return keys("pending"); }

        PredictionTile tile(PredictionTileKey key, int axis) {
            int spacing = manager.layout().tileBlocks(key.lod()) / axis;
            int[] heights = new int[(axis + 1) * (axis + 1)];
            Arrays.fill(heights, 64);
            var points = new ClientColumnSample[heights.length]; Arrays.fill(points, sample);
            var mesh = PredictionVoxelFixtures.flat(key,axis,spacing,64);
            return new PredictionTile(key, heights, heights, points, mesh, new PredictionDepthBound(64, 64),
                    0, axis, axis, spacing);
        }

        void plan(PredictionTileKey a, PredictionTileKey b) throws Exception {
            var targets = Map.of(a, 64, b, 64);
            set(manager, "ordinaryTargets", targets); set(manager, "terrainTargets", targets);
            keys("desiredKeys").addAll(List.of(a, b, parent(a), parent(b)));
            keys("terrainLeaves").addAll(List.of(a, b));
            for (var key : List.of(a, b)) { ready().put(key, tile(key, 32)); ready().put(parent(key), tile(parent(key), 8)); }
            set(manager, "mediumCoverage", new PredictionMediumCoverage(
                    Set.of(parent(a), parent(b)), Set.of(parent(a), parent(b))));
        }
        void face(double direction) throws Exception {
            manager.setWorkView(PredictionWorkView.of(0, 80, 0, direction, 0, 0, 70, 16D / 9));
            PredictionDemandTestSupport.refresh(manager);
        }
        void enqueue(PredictionTileKey key) throws Exception {
            invoke(manager, "enqueue", new Class<?>[]{PredictionTileKey.class, int.class, int.class, boolean.class}, key, 0, 0, false);
        }
        PredictionLoadingProgress progress(List<PredictionTileKey> leaves) throws Exception {
            PredictionDemandTestSupport.loading(manager, leaves, 0D, 0D, System.nanoTime());
            return manager.loadingProgress();
        }
        void awaitIdle() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (manager.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(0, manager.pendingCount());
        }
        @Override public void close() { manager.close(); }
    }
}
