package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionPlanningPipelineTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        @Override public void execute(Runnable task) { jobs.addLast(task); }
        void finish() { jobs.removeFirst().run(); }
    }

    @Test void coverageAcquisitionRunsInsideTheAcceptedWorkerJob() {
        var executor = new ManualExecutor();
        var reads = new AtomicInteger();
        var input = planInput();
        var exact = new PredictionExactCoverageIndex().planningView(Level.OVERWORLD, input.now());
        var pipeline = new PredictionPlanningPipeline(executor, (dimension, now) -> {
            assertEquals(input.dimension(), dimension);
            assertEquals(input.now(), now);
            reads.incrementAndGet();
            return exact;
        });
        assertTrue(pipeline.plan.request(input));
        assertFalse(pipeline.plan.request(input));
        assertEquals(0, reads.get());
        assertNull(pipeline.plan.poll(result -> fail("unfinished jobs cannot be validated")));
        executor.finish();
        assertEquals(1, reads.get());
        var result = pipeline.plan.poll(candidate -> true);
        assertNotNull(result);
        assertEquals(PredictionPlanPreparation.build(input, exact).plan(), result.plan());
        assertEquals(1, pipeline.plan.jobs());
        assertEquals(1, pipeline.plan.commits());
    }

    @Test void resetsKeepAllFourChannelsBoundedAndRejectThePreviousGeneration() {
        var executor = new ManualExecutor();
        var pipeline = new PredictionPlanningPipeline(executor,
                (dimension, now) -> new PredictionExactCoverageIndex().planningView(dimension, now));
        var plan = planInput();
        var view = new PredictionDemandPreparation.Input(plan.layout(), null, null, Set.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), -64, 320, plan.now());
        var scheduling = schedulingInput(plan);
        var loading = new PredictionLoadingPreparation.Input(scheduling, List.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), 768, PredictionVisibilityFeedback.Snapshot.EMPTY, 0, 0);
        assertTrue(pipeline.plan.request(plan));
        assertTrue(pipeline.view.request(view));
        assertTrue(pipeline.schedule.request(scheduling));
        assertTrue(pipeline.loading.request(loading));
        for (int turn = 0; turn < 30; turn++) {
            pipeline.invalidate();
            assertFalse(pipeline.plan.request(plan));
            assertFalse(pipeline.view.request(view));
            assertFalse(pipeline.schedule.request(scheduling));
            assertFalse(pipeline.loading.request(loading));
            assertEquals(4, executor.jobs.size());
        }
        while (!executor.jobs.isEmpty()) executor.finish();
        assertNull(pipeline.plan.poll(result -> fail("old generation")));
        assertNull(pipeline.view.poll(result -> fail("old generation")));
        assertNull(pipeline.schedule.poll(result -> fail("old generation")));
        assertNull(pipeline.loading.poll(result -> fail("old generation")));
        assertFalse(pipeline.plan.pending());
        assertFalse(pipeline.view.pending());
        assertFalse(pipeline.schedule.pending());
        assertFalse(pipeline.loading.pending());
        assertTrue(pipeline.view.request(view));
        executor.finish();
        assertNotNull(pipeline.view.poll(result -> true));
    }

    @Test void rejectedResultsAreConsumedOnceWithoutBecomingCommittedState() {
        var executor = new ManualExecutor();
        var lane = new PredictionPlanningPipeline.Lane<Integer, Integer>(executor, value -> value * 2);
        assertTrue(lane.request(3));
        executor.finish();
        assertNull(lane.poll(result -> false));
        assertEquals(1, lane.discards());
        assertEquals(0, lane.commits());
        assertNull(lane.poll(result -> fail("already consumed")));
        assertTrue(lane.request(4));
        executor.finish();
        assertEquals(8, lane.poll(result -> result == 8));
        assertEquals(2, lane.jobs());
        assertEquals(1, lane.commits());
    }

    @Test void invalidationDuringCalculationCannotPublishItsLateCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var lane = new PredictionPlanningPipeline.Lane<Integer, Integer>(executor, value -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("worker timeout");
                    return value;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally { finished.countDown(); }
            });
            assertTrue(lane.request(1));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            lane.clear();
            assertFalse(lane.request(2));
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
            assertNull(lane.poll(result -> fail("invalidated running job")));
            assertTrue(lane.request(2));
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
            assertEquals(2, lane.poll(result -> true));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void calculationFailureKeepsTheLaneReusableWithoutFabricatingACommit() {
        var executor = new ManualExecutor();
        var lane = new PredictionPlanningPipeline.Lane<Integer, Integer>(executor, value -> {
            if (value == 0) throw new IllegalArgumentException("injected calculation failure");
            return value;
        });
        lane.request(0); executor.finish();
        assertNull(lane.poll(result -> fail("failed job")));
        assertEquals(0, lane.commits());
        lane.request(1); executor.finish();
        assertEquals(1, lane.poll(result -> true));
    }

    static PredictionPlanPreparation.Input planInput() {
        var layout = VssLodLayout.of(4096, 6, true, false);
        var axes = new ArrayList<Integer>();
        for (int level = 0; level < layout.levelCount(); level++) axes.add(PredictionWorkOrder.initialCellAxis(level));
        return new PredictionPlanPreparation.Input(Level.OVERWORLD, layout, null, null, 0, 80, 0, 20,
                0, 768, -64, 320, false, List.copyOf(axes), 5, 7, 11,
                new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(), Map.of()), Map.of(), Set.of(), false,
                Set.of(), Set.of(), Set.of(), 0, 0, PredictionVisibilityFeedback.Snapshot.EMPTY, Set.of(), Map.of(), Set.of(),
                Map.of(), Map.of(), new PredictionIdleRefinement(), true, true, false, System.nanoTime(), false);
    }

    static PredictionSchedulePreparation.Input schedulingInput(PredictionPlanPreparation.Input i) {
        return new PredictionSchedulePreparation.Input(i.layout(), i.focus(), i.view(), i.x(), i.y(), i.z(),
                i.pixels(), i.minY(), i.maxY(), i.revision(), List.of(), i.residents(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), PredictionMediumCoverage.EMPTY,
                false, false, false, false, Map.of(), Set.of(), Set.of(), i.initialAxes(), null,
                Map.of(), Map.of(), Map.of(), i.now(), false);
    }
}
