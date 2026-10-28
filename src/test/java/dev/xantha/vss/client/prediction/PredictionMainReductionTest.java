package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.config.VSSClientConfig;

class PredictionMainReductionTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        @Override public void execute(Runnable job) { jobs.add(job); }
        void finish() { jobs.removeFirst().run(); }
    }

    @Test void controlWorkerDoesNotRunOnTheCallerOrAccumulateResetJobs() throws Exception {
        var executor = new ManualExecutor(); var builds = new AtomicLong();
        var preparation = new PredictionClientPreparation<Integer, Integer>(executor, input -> { builds.incrementAndGet(); return input * 2; });
        assertTrue(preparation.request(2)); assertFalse(preparation.request(3));
        assertNull(preparation.poll()); assertEquals(0, builds.get());
        var field = PredictionClientPreparation.class.getDeclaredField("pending"); field.setAccessible(true);
        var abandoned = (java.util.concurrent.CompletableFuture<?>) field.get(preparation);
        preparation.clear(); preparation.clear(); assertFalse(preparation.request(4));
        assertEquals(1, executor.jobs.size()); executor.finish();
        assertNull(abandoned.getNow(null)); assertNull(preparation.poll()); assertEquals(0, builds.get());
        assertTrue(preparation.request(5)); executor.finish(); assertEquals(10, preparation.poll());
        preparation.request(6); executor.finish(); abandoned = (java.util.concurrent.CompletableFuture<?>) field.get(preparation);
        preparation.clear(); assertNull(abandoned.getNow(null)); assertNull(preparation.poll());
    }

    @Test void failedControlPreparationCanRetryWhileKeepingThePublishedState() {
        var executor = new ManualExecutor();
        var preparation = new PredictionClientPreparation<Integer, Integer>(executor, input -> {
            if (input == 0) throw new IllegalArgumentException("injected control failure"); return input;
        });
        preparation.request(0); executor.finish(); assertNull(preparation.poll());
        preparation.request(1); executor.finish(); assertEquals(1, preparation.poll());
    }

    @Test void backgroundPlanMatchesQuadtreeAndPrecisionGoalsWithoutTouchingWorldBlocks() {
        var input = input(Set.of(), false);
        var result = PredictionPlanningTestSupport.buildPlan(input);
        var expected = PredictionLodPlanner.plan(Level.OVERWORLD, input.x(), input.y(), input.z(), input.layout(),
                null, input.pixels(), input.minY(), input.maxY(), 0, key -> true, key -> false, null, key -> false);
        assertEquals(expected, result.leaves());
        assertEquals(PredictionLodPlanner.withCoarseCoverage(expected, input.layout()), result.plan());
        for (var key : result.leaves()) assertEquals(PredictionDetailBands.cellAxis(key, input.layout(),
                input.x(), input.y(), input.z(), null, input.pixels(), input.minY(), input.maxY()), result.terrain().get(key));
        assertEquals(PredictionMediumCoverage.plan(expected, input.layout()), result.medium());
        assertTrue(result.surfaces().isEmpty()); assertFalse(result.idleAllowed());
        assertThrows(UnsupportedOperationException.class, () -> result.plan().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.terrain().clear());
    }

    @Test void sparseCoverageSnapshotsStayIndependentAndPreserveNegativeCoordinatesAndSettleGrace() {
        var index = new PredictionExactCoverageIndex();
        long now = ExactCoverageGate.SETTLE_NANOS + 1;
        for (int z = -19; z <= 1; z++) for (int x = -17; x <= 3; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        index.confirm(Level.OVERWORLD, 6, 6, now);
        var view = index.planningView(Level.OVERWORLD, now);
        assertTrue(view.ownsBox(-17, -19, 3, 1)); assertFalse(view.owns(6, 6));
        assertFalse(view.ownsBox(-18, -19, 3, 1));
        index.remove(Level.OVERWORLD, -16, -16);
        assertTrue(view.ownsBox(-17, -19, 3, 1), "published input is immutable");
        assertFalse(index.planningView(Level.OVERWORLD, now).ownsBox(-17, -19, 3, 1));
        assertFalse(index.planningView(Level.NETHER, now).owns(-17, -19));
    }

    @Test void bothPlatformsEditedColumnKeysUseTheSameAuthoritativeSemantics() throws Exception {
        var layout = input(Set.of(), false).layout();
        var key = new PredictionTileKey(Level.OVERWORLD, -1, 2, 0);
        int span = layout.tileBlocks(0) / 16;
        var raw = new HashSet<Long>(); var mixed = new HashSet<Long>();
        for (int z = 2 * span; z < 3 * span; z++) for (int x = -span; x < 0; x++) {
            long column = (long) x << 32 | z & 0xffffffffL;
            raw.add(column); mixed.add(it.unimi.dsi.fastutil.HashCommon.mix(column));
        }
        var method = PredictionPlanPreparation.class.getDeclaredMethod("fullyAuthoritative", PredictionTileKey.class,
                PredictionPlanPreparation.Input.class, PredictionExactCoverageIndex.View.class); method.setAccessible(true);
        var empty = new PredictionExactCoverageIndex().planningView(Level.OVERWORLD, 0);
        assertEquals(true, method.invoke(null, key, input(raw, false), empty));
        assertEquals(true, method.invoke(null, key, input(mixed, true), empty));
        raw.remove(raw.iterator().next()); assertEquals(false, method.invoke(null, key, input(raw, false), empty));
    }







    @Test void liveTickWaitsForTheWorkerAndRejectsATeleportBeforePublishingGoals() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        var executor = new ManualExecutor();
        var preparation = new PredictionClientPreparation<PredictionPlanPreparation.Input, PredictionPlanPreparation.Result>(executor, PredictionPlanningTestSupport::buildPlan);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", 1);
        try (var manager = new PredictionTileManager(Level.OVERWORLD, ClientTerrainSampler.custom(42, profile, (x, z) -> 64),
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), null)) {
            set(manager, "planPreparation", preparation);
            for (int j = 0; j < 20; j++) manager.tick(0, 80, 0, .01, null);
            assertEquals(1, executor.jobs.size()); assertEquals(0, manager.pendingCount()); assertTrue(manager.renderSnapshot().tiles().isEmpty());
            executor.finish(); manager.tick(10000, 80, 10000, .01, null);
            assertEquals(1L, get(manager, "planDiscards")); assertEquals(List.of(), get(manager, "scheduledPlan"));
            executor.finish(); manager.tick(10000, 80, 10000, .01, null);
            assertEquals(1L, get(manager, "planCommits"));
            assertFalse(((List<?>) get(manager, "scheduledPlan")).isEmpty());
        } finally { VSSClientConfig.CONFIG.rememberTerrain = remember; }
    }

    private static PredictionPlanPreparation.Input input(Set<Long> edited, boolean mixed) {
        var layout = VssLodLayout.of(4096, 6, true, false);
        var axes = new ArrayList<Integer>(); for (int j = 0; j < layout.levelCount(); j++) axes.add(PredictionWorkOrder.initialCellAxis(j));
        return new PredictionPlanPreparation.Input(Level.OVERWORLD, layout, null, null, -32, 80, -32, 20,
                0, 768, -64, 320, false, List.copyOf(axes), 0, 0, ClientPredictionState.exactCoverageRemovalRevision(),
                new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(), Map.of()), Map.of(), Set.copyOf(edited), mixed,
                Set.of(), Set.of(), Set.of(), 0, 0, PredictionVisibilityFeedback.Snapshot.EMPTY, Set.of(), Map.of(), Set.of(),
                Map.of(), Map.of(), new PredictionIdleRefinement(), true, true, true, false, System.nanoTime(), false);
    }
    private static Object get(Object target, String name) throws Exception {
        return PredictionPlanningTestSupport.get(target, name);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        PredictionPlanningTestSupport.set(target, name, value);
    }
}
