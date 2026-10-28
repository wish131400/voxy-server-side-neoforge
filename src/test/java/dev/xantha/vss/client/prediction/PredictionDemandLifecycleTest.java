package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Changes demand during actual worker execution, rather than only before enqueue. */
class PredictionDemandLifecycleTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);
    private static final boolean BASELINE = Boolean.getBoolean("vss.demandBaseline");
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void loadingSwitchStopsRunningRefinementAndPreservesDrawableLod() throws Exception {
        try (var f = new Fixture(true); var loading = new LoadingSwitch(f.manager)) {
            var previous = f.ready().get(f.key);
            var snapshot = ClientPredictionState.renderSnapshot(PROFILE.levelKey());
            f.start();
            loading.enabled(false);
            f.finish();
            assertTrue(VSSClientConfig.CONFIG.enablePrediction, "loading must not turn off prediction rendering");
            assertSame(previous, f.ready().get(f.key));
            assertSame(snapshot, ClientPredictionState.renderSnapshot(PROFILE.levelKey()));
            assertFalse(f.map("failedAt").containsKey(f.key));
            f.assertReleased();

            int sampled = f.samples.get();
            f.enqueue();
            invoke(f.manager, "enqueue",
                    new Class<?>[]{PredictionTileKey.class, int.class, int.class, boolean.class, boolean.class},
                    f.key, 0, 0, false, true);
            f.manager.tick(0, 0);
            assertEquals(0, f.manager.pendingCount());
            assertEquals(sampled, f.samples.get(), "paused loading must not generate or restore new meshes");
            assertSame(snapshot, ClientPredictionState.renderSnapshot(PROFILE.levelKey()));
            f.assertReleased();

            loading.enabled(true);
            f.enqueue(); f.awaitIdle();
            assertEquals(64, f.ready().get(f.key).cellAxis(), "reenabling must resume refinement");
            assertTrue(f.samples.get() > sampled);
            f.assertReleased();
        }
    }

    @Test void loadingSwitchAlsoPausesRequiredFirstCoverage() throws Exception {
        try (var f = new Fixture(false); var loading = new LoadingSwitch(f.manager)) {
            var parent = f.ready().get(f.parent);
            f.start();
            loading.enabled(false);
            f.finish();
            assertNull(f.ready().get(f.key), "manual loading pause must override minimum-coverage admission");
            assertSame(parent, f.ready().get(f.parent));
            f.assertReleased();
            loading.enabled(true);
            f.enqueue(); f.awaitIdle();
            assertNotNull(f.ready().get(f.key));
            f.assertReleased();
        }
    }

    @Test void loadingCannotResumeAnInactiveDimensionOrOverrideTheMasterSwitch() throws Exception {
        try (var f = new Fixture(true); var loading = new LoadingSwitch(f.manager)) {
            ClientPredictionState.updateLoadingState(null);
            assertEquals(true, get(f.manager, "paused"));
            f.enqueue();
            assertEquals(0, f.manager.pendingCount());
            loading.enabled(true);
            assertEquals(false, get(f.manager, "paused"));
            VSSClientConfig.CONFIG.enablePrediction = false;
            loading.enabled(true);
            assertEquals(true, get(f.manager, "paused"));
            assertTrue(VSSClientConfig.CONFIG.enablePredictionLoading);
            VSSClientConfig.CONFIG.enablePrediction = true;
            loading.enabled(true);
            assertEquals(false, get(f.manager, "paused"));
            f.assertReleased();
        }
    }

    /** Exercise the same config-to-manager update used by the client tick. */
    private static final class LoadingSwitch implements AutoCloseable {
        private final boolean display = VSSClientConfig.CONFIG.enablePrediction;
        private final boolean loading = VSSClientConfig.CONFIG.enablePredictionLoading;
        private final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, PredictionTileManager> managers;
        private final PredictionTileManager previous;

        @SuppressWarnings("unchecked")
        LoadingSwitch(PredictionTileManager manager) throws Exception {
            var field = ClientPredictionState.class.getDeclaredField("MANAGERS");
            field.setAccessible(true);
            managers = (Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, PredictionTileManager>) field.get(null);
            previous = managers.put(PROFILE.levelKey(), manager);
            VSSClientConfig.CONFIG.enablePrediction = true;
            enabled(true);
        }

        void enabled(boolean value) {
            VSSClientConfig.CONFIG.enablePredictionLoading = value;
            ClientPredictionState.updateLoadingState(PROFILE.levelKey());
        }

        @Override public void close() {
            if (previous == null) managers.remove(PROFILE.levelKey());
            else managers.put(PROFILE.levelKey(), previous);
            VSSClientConfig.CONFIG.enablePrediction = display;
            VSSClientConfig.CONFIG.enablePredictionLoading = loading;
        }
    }

    @Test void turningStopsRunningOptionalWorkAndReturningRetriesImmediately() throws Exception {
        try (var f = new Fixture(true)) {
            var previous = f.ready().get(f.key);
            f.start(); f.face(-1); f.finish();
            int stoppedSamples = f.samples.get();
            if (BASELINE) assertEquals(64, f.ready().get(f.key).cellAxis());
            else {
                assertSame(previous, f.ready().get(f.key), "cancellation cannot retire drawable ground");
                assertTrue(stoppedSamples <= 66, "sampling must stop at a bounded grid checkpoint");
                assertFalse(f.map("failedAt").containsKey(f.key), "a changed view is not a backend failure");
                assertEquals(1L,((LongAdder)get(f.manager,"supersededBuilds")).sum());
            }
            f.assertReleased();
            f.face(1); f.enqueue(); f.awaitIdle();
            assertEquals(64, f.ready().get(f.key).cellAxis(), "returning must not wait for the 30-second failure retry");
            if (!BASELINE) assertTrue(f.samples.get() > stoppedSamples);
            f.assertReleased();
        }
    }

    @Test void shrinkingTheScreenGoalStopsAnAlreadyRunningUpgrade() throws Exception {
        try (var f = new Fixture(true)) {
            var previous = f.ready().get(f.key);
            f.start(); f.target(32); f.finish();
            if (BASELINE) assertEquals(64, f.ready().get(f.key).cellAxis());
            else {
                assertSame(previous, f.ready().get(f.key));
                assertTrue(f.samples.get() <= 66);
                assertFalse(f.map("failedAt").containsKey(f.key));
            }
            f.assertReleased();
            f.target(64); f.enqueue(); f.awaitIdle();
            assertEquals(64, f.ready().get(f.key).cellAxis());
            f.assertReleased();
        }
    }

    @Test void firstCoverageSurvivesATurnDuringSampling() throws Exception {
        try (var f = new Fixture(false)) {
            f.start(); f.face(-1); f.finish();
            assertNotNull(f.ready().get(f.key), "initial omnidirectional ground remains required");
            assertEquals(PredictionWorkOrder.initialCellAxis(f.key.lod()), f.ready().get(f.key).cellAxis());
            assertTrue(f.samples.get() > 66);
            f.assertReleased();
        }
    }

    @Test void dirtyRecoverySurvivesATurnDuringSampling() throws Exception {
        try (var f = new Fixture(true)) {
            f.keys("dirtyTiles").add(f.key);
            var old = f.ready().get(f.key);
            f.start(); f.face(-1); f.finish();
            assertNotSame(old, f.ready().get(f.key));
            assertFalse(f.keys("dirtyTiles").contains(f.key));
            assertTrue(f.samples.get() > 66);
            f.assertReleased();
        }
    }

    @Test void offscreenTransitionDependenciesContinueToCompletion() throws Exception {
        try (var f = new Fixture(true)) {
            f.start(); f.face(-1);
            set(f.manager,"viewDependencies",Set.of(f.key));
            set(f.manager,"transitionTargets",Map.of(f.key,64));
            f.finish();
            assertEquals(64,f.ready().get(f.key).cellAxis());
            assertTrue(f.samples.get() > 66);
            f.assertReleased();
        }
    }

    @Test void completedFineMeshesRemainReusableWhenTheGoalShrinks() throws Exception {
        try (var f = new Fixture(true)) {
            f.start(); f.finish();
            var fine = f.ready().get(f.key); int sampled = f.samples.get();
            f.target(8); f.face(-1); f.enqueue(); f.awaitIdle();
            assertSame(fine,f.ready().get(f.key));
            assertEquals(sampled,f.samples.get());
            f.assertReleased();
        }
    }

    @Test void replanningTheLeafSetCannotTransientlyCancelAnUnchangedTarget() throws Exception {
        try (var f = new Fixture(true)) {
            f.start();
            f.keys("terrainLeaves").clear();
            f.finish();
            assertEquals(64,f.ready().get(f.key).cellAxis());
            f.assertReleased();
        }
    }

    @Test void cachedFinishedGeometryDoesNotBecomeViewDependent() throws Exception {
        if (BASELINE) return;
        try (var f = new Fixture(true)) {
            f.face(-1); f.target(8);
            assertEquals(true,invoke(f.manager,"buildDemandCurrent",
                    new Class<?>[]{PredictionTileKey.class,int.class,boolean.class,boolean.class},f.key,64,false,true));
            assertEquals(false,invoke(f.manager,"buildDemandCurrent",
                    new Class<?>[]{PredictionTileKey.class,int.class,boolean.class,boolean.class},f.key,64,false,false));
            f.assertReleased();
        }
    }

    @Test void mediumFrontierAndLocalRefinementCanProgressIndependently() throws Exception {
        try (var f = new Fixture(true, true)) {
            var leaf = new PredictionTileKey(PROFILE.levelKey(), 0, 0, 2);
            f.keys("desiredKeys").add(leaf);
            f.keys("terrainLeaves").add(leaf);
            f.ready().put(leaf, f.tile(leaf, 32));
            set(f.manager, "ordinaryTargets", Map.of(leaf, 64, f.key, 8));
            set(f.manager, "terrainTargets", Map.of(leaf, 64));
            assertEquals(true, invoke(f.manager, "localWorkAllowed",
                    new Class<?>[]{PredictionTileKey.class, boolean.class}, leaf, false));
            f.release.countDown();
            f.enqueue(); f.awaitIdle();
            assertEquals(16, f.ready().get(f.key).cellAxis(), "a resident preview must advance in the medium wave");
            f.enqueue(); f.awaitIdle();
            assertEquals(32, f.ready().get(f.key).cellAxis());
            assertEquals(true, invoke(f.manager, "localWorkAllowed",
                    new Class<?>[]{PredictionTileKey.class, boolean.class}, leaf, false));
            invoke(f.manager, "enqueue", new Class<?>[]{PredictionTileKey.class, int.class, int.class, boolean.class},
                    leaf, 0, 0, false);
            f.awaitIdle();
            assertEquals(64, f.ready().get(leaf).cellAxis(), "nearby detail completes without a medium-quality barrier");
            assertEquals(0L, ((LongAdder)get(f.manager, "supersededBuilds")).sum());
            f.assertReleased();
        }
    }

    @Test void mediumDemandAlsoSurvivesAnUnchangedLeafSetPublication() throws Exception {
        try (var f = new Fixture(true, true)) {
            // The coarse frontier can also be a final leaf whose screen goal is only 8 cells.
            // Its published target must clamp bootstrap work even while the mutable leaf set is cleared.
            f.target(8);
            f.keys("terrainLeaves").clear();
            assertEquals(8, invoke(f.manager, "targetCellAxis", new Class<?>[]{PredictionTileKey.class}, f.key));
            assertEquals(true, invoke(f.manager, "buildDemandCurrent",
                    new Class<?>[]{PredictionTileKey.class, int.class, boolean.class, boolean.class}, f.key, 8, false, false));
            assertEquals(false, invoke(f.manager, "buildDemandCurrent",
                    new Class<?>[]{PredictionTileKey.class, int.class, boolean.class, boolean.class}, f.key, 16, false, false));
            f.assertReleased();
        }
    }



    static Object get(Object target,String name) throws Exception {
        return PredictionPlanningTestSupport.get(target, name);
    }
    static void set(Object target,String name,Object value) throws Exception {
        PredictionPlanningTestSupport.set(target, name, value);
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... args) throws Exception {
        var method=target.getClass().getDeclaredMethod(name,types); method.setAccessible(true); return method.invoke(target,args);
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger samples=new AtomicInteger();
        final AtomicReference<Thread> worker=new AtomicReference<>();
        final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        final PredictionCpuBudget cpu=PredictionCpuBudget.fixed(1);
        final ClientColumnSample sample=PredictionVoxelFixtures.sample(64);
        final PredictionTileKey key;
        final PredictionTileKey parent;
        final PredictionTileManager manager;
        Fixture(boolean resident) throws Exception { this(resident, false); }
        Fixture(boolean resident, boolean medium) throws Exception {
            key = new PredictionTileKey(PROFILE.levelKey(), medium ? 0 : 4, 0, medium ? 3 : 1);
            parent = new PredictionTileKey(PROFILE.levelKey(), medium ? 0 : 2, 0, medium ? 4 : 2);
            manager=new PredictionTileManager(PROFILE.levelKey(),new ClientTerrainSampler(PROFILE.seed(),PROFILE) {
                @Override int initialTerrainCellAxis(int lod) { return PredictionWorkOrder.initialCellAxis(lod); }
                @Override public ClientColumnSample sample(int x,int z) {
                    if (samples.incrementAndGet()==1) {
                        worker.set(Thread.currentThread()); entered.countDown();
                        try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("demand test timed out"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                    }
                    return sample;
                }
                @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample(x,z); }
            },new PredictionMemoryBudget(1024L * PredictionMemoryBudget.MIB,0,() -> Long.MAX_VALUE,System::nanoTime,1),null,cpu);
            target(64); keys("desiredKeys").addAll(List.of(key,parent)); keys("terrainLeaves").add(key);
            ready().put(parent,tile(parent,32)); if (resident) ready().put(key,tile(key,medium ? 8 : 32));
            set(manager,"mediumCoverage",new PredictionMediumCoverage(Set.of(medium ? key : parent),
                    medium ? Set.of(key, parent) : Set.of(parent)));
            if (medium) {
                keys("terrainLeaves").clear();
                set(manager, "terrainTargets", Map.of());
                set(manager, "ordinaryTargets", Map.of(key, 8));
                set(manager, "mediumCoveragePending", true);
            }
            face(1);
        }
        PredictionTile tile(PredictionTileKey key,int axis) {
            int spacing=manager.layout().tileBlocks(key.lod())/axis;
            int[] heights=new int[(axis+1)*(axis+1)]; Arrays.fill(heights,64);
            var points=new ClientColumnSample[heights.length]; Arrays.fill(points,sample);
            var mesh=PredictionVoxelFixtures.flat(key,axis,spacing,64);
            return new PredictionTile(key,heights,heights,points,mesh,new PredictionDepthBound(64,64),0,axis,axis,spacing);
        }
        @SuppressWarnings("unchecked") Map<PredictionTileKey,PredictionTile> ready() throws Exception { return (Map<PredictionTileKey,PredictionTile>)get(manager,"ready"); }
        @SuppressWarnings("unchecked") Set<PredictionTileKey> keys(String name) throws Exception { return (Set<PredictionTileKey>)get(manager,name); }
        @SuppressWarnings("unchecked") Map<PredictionTileKey,?> map(String name) throws Exception { return (Map<PredictionTileKey,?>)get(manager,name); }
        void target(int axis) throws Exception { set(manager,"ordinaryTargets",Map.of(key,axis)); set(manager,"terrainTargets",Map.of(key,axis)); }
        void face(double direction) throws Exception {
            manager.setWorkView(PredictionWorkView.of(0,80,0,direction,0,0,70,16D/9));
            PredictionDemandTestSupport.refresh(manager);
        }
        void enqueue() throws Exception { invoke(manager,"enqueue",new Class<?>[]{PredictionTileKey.class,int.class,int.class,boolean.class},key,0,0,false); }
        void start() throws Exception { enqueue(); assertTrue(entered.await(10,TimeUnit.SECONDS),"worker must enter the sampler"); }
        void finish() throws Exception { release.countDown(); awaitIdle(); }
        void awaitIdle() throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (manager.pendingCount()!=0 && System.nanoTime()<deadline) Thread.sleep(2);
            assertEquals(0,manager.pendingCount());
        }
        void assertReleased() throws Exception {
            assertEquals(0,(int)get(cpu,"reserved")+(int)get(cpu,"queuedOverflow"));
            assertEquals(0,(int)get(cpu,"active"));
            for (String name:List.of("refinementPending","backgroundPending","scopePending","surfacePending")) assertTrue(keys(name).isEmpty());
        }
        @Override public void close() { release.countDown(); manager.close(); }
    }
}
