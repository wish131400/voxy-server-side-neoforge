package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production planner's published loading contract with fixed, non-running residents. */
class PredictionLoadingProgressIntegrationTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(ResourceLocation.parse("minecraft:overworld"),
            42L, -64, 384, "noise", "minecraft:overworld", 123L);

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void fullNearPreviewDoesNotCompleteARealTargetAbove64() throws Exception {
        try (var manager = manager()) {
            var key = nearKey();
            plan(manager, key, 128);
            residents(manager).put(key, tile(manager, key, 64, false));
            var progress = refresh(manager, List.of(key));
            assertEquals(1, progress.coverageReady());
            assertEquals(1, progress.nearReady());
            assertEquals(1, progress.targetTotal());
            assertEquals(0, progress.targetReady());
            assertFalse(progress.ready(95));
            assertEquals("near-target", progress.waitingFor(95));

            residents(manager).put(key, tile(manager, key, 128, false));
            progress = refresh(manager, List.of(key));
            assertEquals(1, progress.targetReady());
            assertTrue(progress.ready(95));
        }
    }

    @Test void observationsSurviveChurnWithoutPublishingStaleReadinessOrGoals() throws Exception {
        try (var manager = manager()) {
            var queue = new java.util.ArrayDeque<Runnable>();
            var pipeline = new PredictionPlanningPipeline(queue::add,
                    (dimension,now) -> new PredictionExactCoverageIndex.View(Map.of()));
            field("planning").set(manager,pipeline);
            var key = nearKey(); plan(manager,key,64);
            field("scheduledLeaves").set(manager,List.of(key));
            residents(manager).put(key,tile(manager,key,64,false));
            var input = loadingInput(manager);
            assertTrue(pipeline.loading.request(input));
            call(manager,"invalidateLoadingWork",new Class<?>[0]);
            keys(manager,"dirtyTiles").add(key); // Actual read evidence changed, not only the global counter.
            queue.removeFirst().run();
            call(manager,"publishLoadingWork",new Class<?>[0]);
            assertEquals(1L,field("loadingDisplayCommits").getLong(manager));
            assertEquals(0L,field("loadingReadinessCommits").getLong(manager));
            assertEquals(1,manager.loadingObservation().get("targetTotal"));
            assertEquals(false,manager.loadingObservation().get("fresh"));
            assertEquals("refreshing",manager.loadingObservation().get("waitingFor"));
            assertFalse(manager.loadingProgress().planned());
            assertTrue(field("foregroundWorkPending").getBoolean(manager));
            assertEquals(input.goals(),field("displayGoals").get(manager));
            keys(manager,"dirtyTiles").remove(key);
            assertTrue(pipeline.loading.request(loadingInput(manager)));
            queue.removeFirst().run(); call(manager,"publishLoadingWork",new Class<?>[0]);
            assertEquals(1L,field("loadingReadinessCommits").getLong(manager));
            assertTrue(manager.loadingProgress().ready(95));
            assertEquals(true,manager.loadingObservation().get("fresh"));
            assertTrue(pipeline.loading.request(loadingInput(manager,System.nanoTime()-2_000_000_000L)));
            queue.removeFirst().run(); call(manager,"publishLoadingWork",new Class<?>[0]);
            assertEquals(2L,field("loadingReadinessCommits").getLong(manager),"unchanged exact evidence remains valid despite a slow worker");
            assertTrue(manager.loadingProgress().ready(95));
            call(manager,"invalidateLoadingWork",new Class<?>[0]);
            assertEquals(1,manager.loadingObservation().get("targetTotal"),"invalidating readiness preserves dated counts");
            assertFalse(manager.loadingProgress().planned());
            call(manager,"abandonOldViewWork",new Class<?>[0]);
            assertEquals(0,manager.loadingObservation().get("targetTotal"),"teleport discards the previous view observation");
            assertEquals("initializing",manager.loadingObservation().get("waitingFor"));
        }
    }

    @Test void unrelatedLoadingInvalidationCanCommitUnchangedReadEvidence() throws Exception {
        try(var manager=manager()) {
            var queue=new java.util.ArrayDeque<Runnable>();
            var pipeline=new PredictionPlanningPipeline(queue::add,(dimension,now)->new PredictionExactCoverageIndex.View(Map.of()));
            field("planning").set(manager,pipeline);
            var key=nearKey();plan(manager,key,64);field("scheduledLeaves").set(manager,List.of(key));
            residents(manager).put(key,tile(manager,key,64,false));
            assertTrue(pipeline.loading.request(loadingInput(manager)));
            call(manager,"invalidateLoadingWork",new Class<?>[0]);
            queue.removeFirst().run();call(manager,"publishLoadingWork",new Class<?>[0]);
            assertEquals(1L,field("loadingReadinessCommits").getLong(manager));
            assertEquals(0L,field("loadingReadinessDiscards").getLong(manager));
            assertTrue(manager.loadingProgress().ready(95));
            assertEquals(true,manager.loadingObservation().get("fresh"));
        }
    }

    private static PredictionLoadingPreparation.Input loadingInput(PredictionTileManager manager) throws Exception {
        return loadingInput(manager,System.nanoTime());
    }
    private static PredictionLoadingPreparation.Input loadingInput(PredictionTileManager manager,long now) throws Exception {
        var scheduling = (PredictionSchedulePreparation.Input)call(manager,"schedulingInput",new Class<?>[]{long.class},now);
        return (PredictionLoadingPreparation.Input)call(manager,"loadingInput",new Class<?>[]{PredictionSchedulePreparation.Input.class},scheduling);
    }
    private static Object call(PredictionTileManager manager,String name,Class<?>[] types,Object... args) throws Exception {
        var method = PredictionTileManager.class.getDeclaredMethod(name,types); method.setAccessible(true); return method.invoke(manager,args);
    }

    @Test void surfaceFlagNeedsCompleteCurrentTerrainEvenOutsideNearTargetRadius() throws Exception {
        try (var manager = manager()) {
            int radius = PredictionDetailBands.fineRadius(manager.layout().maxDistanceBlocks(), PROFILE.levelKey());
            var key = new PredictionTileKey(PROFILE.levelKey(), radius / manager.layout().tileBlocks(1) + 2, 0, 1);
            plan(manager, key, 128);
            keys(manager, "surfaceDesired").add(key);
            keys(manager, "surfaceReady").add(key);
            residents(manager).put(key, tile(manager, key, 64, false));
            var progress = refresh(manager, List.of(key));
            assertEquals(0, progress.targetTotal(), "near target debt must use its defined range");
            assertEquals(1, progress.surfaceTotal());
            assertEquals(0, progress.surfaceReady(), "a stale decoration flag cannot complete coarse terrain");
            assertFalse(progress.ready(95));
            assertEquals("surface", progress.waitingFor(95));

            residents(manager).put(key, tile(manager, key, 128, false));
            assertTrue(refresh(manager, List.of(key)).ready(95));
            keys(manager, "dirtyTiles").add(key);
            progress = refresh(manager, List.of(key));
            assertEquals(0, progress.surfaceReady(), "invalidated terrain must renew its surface before admission");
            assertFalse(progress.ready(95));
            keys(manager, "dirtyTiles").remove(key);
            assertTrue(refresh(manager, List.of(key)).ready(95));
        }
    }

    @Test void scopedOrDirtyResidentsCannotSatisfyOrdinaryNearCoverage() throws Exception {
        try (var manager = manager()) {
            var key = nearKey();
            plan(manager, key, 128);
            keys(manager, "surfaceDesired").add(key);
            keys(manager, "surfaceReady").add(key);
            residents(manager).put(key, tile(manager, key, 128, true));
            var scoped = refresh(manager, List.of(key));
            assertEquals(0, scoped.coverageReady());
            assertEquals(0, scoped.nearReady());
            assertEquals(0, scoped.targetReady());
            assertEquals(0, scoped.surfaceReady());
            assertFalse(scoped.ready(95));

            residents(manager).put(key, tile(manager, key, 128, false));
            keys(manager, "dirtyTiles").add(key);
            var dirty = refresh(manager, List.of(key));
            assertEquals(0, dirty.nearReady());
            assertEquals(0, dirty.targetReady());
            assertEquals(0, dirty.surfaceReady());
            assertFalse(dirty.ready(95));
            keys(manager, "dirtyTiles").remove(key);
            assertTrue(refresh(manager, List.of(key)).ready(95));
        }
    }

    @Test void voxyRemovalProducesCompletedEmptyPlanAndRevocationRestoresDebt() throws Exception {
        try (var manager = manager()) {
            var key = nearKey();
            plan(manager, key, 128);
            keys(manager, "surfaceDesired").add(key);
            assertFalse(refresh(manager, List.of(key)).ready(95));

            // The normal selection path filters Voxy-owned leaves before rebuilding the frontier.
            field("voxyOwnedTiles").set(manager, Set.of(key));
            field("mediumCoverage").set(manager, PredictionMediumCoverage.EMPTY);
            var empty = refresh(manager, List.of());
            assertTrue(empty.planned());
            assertEquals(0, empty.coverageTotal());
            assertEquals(0, empty.nearTotal());
            assertEquals(0, empty.targetTotal());
            assertEquals(0, empty.surfaceTotal(), "old surface candidates already owned by Voxy do not hold admission");
            assertTrue(empty.ready(95));

            field("voxyOwnedTiles").set(manager, Set.of());
            field("mediumCoverage").set(manager, new PredictionMediumCoverage(Set.of(key), Set.of(key)));
            var restored = refresh(manager, List.of(key));
            assertEquals(1, restored.targetTotal());
            assertEquals(0, restored.targetReady());
            assertEquals(1, restored.surfaceTotal());
            assertFalse(restored.ready(95), "ownership revocation must restore missing prediction debt immediately");
        }
    }

    @Test void optionalIdleTargetDoesNotReacquireTheOrdinaryPredictionTurn() throws Exception {
        try (var manager = manager()) {
            var key = nearKey();
            plan(manager, key, 64);
            residents(manager).put(key, tile(manager, key, 64, false));
            field("terrainTargets").set(manager, Map.of(key, 128));
            field("idleTargets").set(manager, Map.of(key, 128));
            var progress = refresh(manager, List.of(key));
            assertEquals(1, progress.nearReady());
            assertEquals(1, progress.targetReady());
            assertTrue(progress.ready(95), "optional improvements cannot replace the ordinary admission target");
        }
    }

    @Test void oldSurfaceCandidateOutsideCurrentTerrainPlanDoesNotCreateDebt() throws Exception {
        try (var manager = manager()) {
            keys(manager, "surfaceDesired").add(nearKey());
            var progress = refresh(manager, List.of());
            assertEquals(0, progress.surfaceTotal());
            assertTrue(progress.ready(95));
        }
    }

    private static PredictionTileKey nearKey() { return new PredictionTileKey(PROFILE.levelKey(), 0, 0, 1); }

    private static PredictionTileManager manager() {
        return new PredictionTileManager(PROFILE.levelKey(), new ClientTerrainSampler(PROFILE.seed(), PROFILE),
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0,
                        () -> Long.MAX_VALUE, System::nanoTime, 1), null);
    }

    private static void plan(PredictionTileManager manager, PredictionTileKey key, int target) throws Exception {
        keys(manager, "terrainLeaves").add(key);
        keys(manager, "desiredKeys").add(key);
        field("mediumCoverage").set(manager, new PredictionMediumCoverage(Set.of(key), Set.of(key)));
        field("ordinaryTargets").set(manager, Map.of(key, target));
        field("terrainTargets").set(manager, Map.of(key, target));
    }

    private static PredictionTile tile(PredictionTileManager manager, PredictionTileKey key,
                                       int axis, boolean scopeOnly) {
        int spacing = manager.layout().tileBlocks(key.lod()) / axis;
        int[] heights = new int[(axis + 1) * (axis + 1)];
        Arrays.fill(heights, 64);
        var samples = new ClientColumnSample[heights.length];
        Arrays.fill(samples, PredictionVoxelFixtures.sample(64));
        var mesh = PredictionVoxelFixtures.flat(key,axis,spacing,64);
        return new PredictionTile(key, heights, heights, samples, mesh,
                new PredictionDepthBound(64, 64), 0, 0, axis, spacing, scopeOnly);
    }

    private static PredictionLoadingProgress refresh(PredictionTileManager manager,
                                                     List<PredictionTileKey> leaves) throws Exception {
        PredictionDemandTestSupport.loading(manager, leaves, 8.0D, 8.0D, System.nanoTime());
        return manager.loadingProgress();
    }

    private static Field field(String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Set<PredictionTileKey> keys(PredictionTileManager manager, String name) throws Exception {
        return (Set<PredictionTileKey>) field(name).get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<PredictionTileKey, PredictionTile> residents(PredictionTileManager manager) throws Exception {
        return (Map<PredictionTileKey, PredictionTile>) field("ready").get(manager);
    }
}
