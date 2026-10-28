package dev.xantha.vss.client.prediction;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Fixtures publish the same versioned worker results used in game. */
final class PredictionDemandTestSupport {
    private PredictionDemandTestSupport() { }

    static void refresh(PredictionTileManager manager) throws Exception {
        invalidateFixtureInput(manager);
        invoke(manager, "requestViewWork", new Class<?>[]{long.class}, System.nanoTime());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!(boolean) invoke(manager, "publishViewWork", new Class<?>[0])) {
            checkDeadline(deadline, "view");
            Thread.sleep(1);
        }
        loading(manager, System.nanoTime());
    }

    @SuppressWarnings("unchecked")
    static void loading(PredictionTileManager manager, long now) throws Exception {
        loading(manager, List.copyOf((Set<PredictionTileKey>) field("terrainLeaves").get(manager)),
                field("cameraBlockX").getDouble(manager), field("cameraBlockZ").getDouble(manager), now);
    }

    @SuppressWarnings("unchecked")
    static void loading(PredictionTileManager manager, List<PredictionTileKey> leaves, double x, double z, long now) throws Exception {
        invalidateFixtureInput(manager);
        field("cameraBlockX").setDouble(manager, x);
        field("cameraBlockZ").setDouble(manager, z);
        field("scheduledLeaves").set(manager, List.copyOf(leaves));
        var scheduling = (PredictionSchedulePreparation.Input) invoke(manager, "schedulingInput",
                new Class<?>[]{long.class}, now);
        var input = (PredictionLoadingPreparation.Input) invoke(manager, "loadingInput",
                new Class<?>[]{PredictionSchedulePreparation.Input.class}, scheduling);
        var preparation = (PredictionClientPreparation<PredictionLoadingPreparation.Input, PredictionLoadingPreparation.Result>)
                PredictionPlanningTestSupport.get(manager, "loadingPreparation");
        long commits = (long) PredictionPlanningTestSupport.get(manager, "loadingCommits");
        if (!preparation.request(input)) throw new AssertionError("fixture already has a loading job");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((long) PredictionPlanningTestSupport.get(manager, "loadingCommits") == commits) {
            invoke(manager, "publishLoadingWork", new Class<?>[0]);
            if ((long) PredictionPlanningTestSupport.get(manager, "loadingCommits") != commits) return;
            if (!preparation.pending()) throw new AssertionError("loading fixture result was rejected");
            checkDeadline(deadline, "loading");
            Thread.sleep(1);
        }
    }

    static boolean foreground(PredictionTileManager manager) throws Exception {
        loading(manager, System.nanoTime());
        return field("foregroundWorkPending").getBoolean(manager);
    }

    static PredictionPlanPreparation.Input planningInput(PredictionTileManager manager, int voxyRadius) throws Exception {
        invalidateFixtureInput(manager);
        var captured = new java.util.concurrent.atomic.AtomicReference<PredictionPlanPreparation.Input>();
        var preparation = new PredictionClientPreparation<PredictionPlanPreparation.Input, PredictionPlanPreparation.Result>(
                Runnable::run, input -> { captured.set(input); return null; });
        var previous = PredictionPlanningTestSupport.get(manager, "planPreparation");
        try {
            PredictionPlanningTestSupport.set(manager, "planPreparation", preparation);
            invoke(manager, "requestPlan", new Class<?>[]{int.class, long.class}, 0, System.nanoTime());
        } finally { PredictionPlanningTestSupport.set(manager, "planPreparation", previous); }
        var i = Objects.requireNonNull(captured.get());
        var candidates = new HashSet<>(i.ownershipCandidates()); candidates.addAll(i.residents().tiles().keySet());
        candidates.addAll(i.previousOwned());
        var uploaded = uploaded(manager, candidates, PredictionVoxyOwnership.radiusBlocks(voxyRadius));
        return new PredictionPlanPreparation.Input(i.dimension(), i.layout(), i.view(), i.focus(), i.x(), i.y(), i.z(),
                i.pixels(), i.vanillaRadius(), i.surfaceExtension(), i.minY(), i.maxY(), i.vegetation(), i.initialAxes(),
                i.settings(), i.revision(), i.removalRevision(), i.residents(), i.relief(), i.edited(), i.mixedEdits(),
                i.ownershipCandidates(), i.previousOwned(), uploaded, voxyRadius, i.mediumBias(), i.visibility(), i.dirty(),
                i.viewBands(), i.viewDependencies(), i.failed(), i.deferred(), i.idle(), i.ordinaryBusy(),
                i.cpuReady(), i.memoryReady(), i.idlePending(), i.now(), i.nativeDemand());
    }

    static boolean fullyAuthoritative(PredictionTileManager manager, PredictionTileKey key) throws Exception {
        var input = planningInput(manager, 0);
        return PredictionPlanPreparation.fullyAuthoritative(key, input,
                ClientPredictionState.planningCoverage(input.dimension(), input.now()));
    }

    static boolean fullyVoxyOwned(PredictionTileManager manager, PredictionTileKey key, int radius) throws Exception {
        var input = planningInput(manager, Math.max(4, radius / 16 + 2));
        input = withUploaded(input, uploaded(manager, List.of(key), radius));
        return PredictionPlanPreparation.fullyVoxyOwned(key, input,
                ClientPredictionState.planningCoverage(input.dimension(), input.now()), radius);
    }

    static void refreshVoxyOwnership(PredictionTileManager manager, List<PredictionTileKey> candidates, int radius) throws Exception {
        var i = planningInput(manager, radius);
        i = withUploaded(i, uploaded(manager, candidates, PredictionVoxyOwnership.radiusBlocks(radius)));
        var input = new PredictionPlanPreparation.Input(i.dimension(), i.layout(), i.view(), i.focus(), i.x(), i.y(), i.z(),
                i.pixels(), i.vanillaRadius(), i.surfaceExtension(), i.minY(), i.maxY(), i.vegetation(), i.initialAxes(),
                i.settings(), i.revision(), i.removalRevision(), i.residents(), i.relief(), i.edited(), i.mixedEdits(),
                Set.copyOf(candidates), i.previousOwned(), i.uploaded(), i.voxyRadius(), i.mediumBias(), i.visibility(), i.dirty(),
                i.viewBands(), i.viewDependencies(), i.failed(), i.deferred(), i.idle(), i.ordinaryBusy(),
                i.cpuReady(), i.memoryReady(), i.idlePending(), i.now(), i.nativeDemand());
        var preparation = new PredictionClientPreparation<PredictionPlanPreparation.Input, PredictionPlanPreparation.Result>(
                Runnable::run, source -> {
                    var result = PredictionPlanningTestSupport.buildPlan(source);
                    // This fixture pins its authored region; the production worker
                    // still determines ownership and the manager commits that proof.
                    var retained = candidates.stream().filter(key -> !result.owned().contains(key)).toList();
                    return new PredictionPlanPreparation.Result(source, retained, retained, Set.of(), result.owned(),
                            result.surfaceRadius(), result.ordinary(), result.terrain(), result.transitions(),
                            result.medium(), Map.of(), result.idle(), false, result.idleState());
                });
        var previous = PredictionPlanningTestSupport.get(manager, "planPreparation");
        try {
            PredictionPlanningTestSupport.set(manager, "planPreparation", preparation);
            if (!preparation.request(input)) throw new AssertionError("fixture already has a plan job");
            long commits = (long) PredictionPlanningTestSupport.get(manager, "planCommits");
            invoke(manager, "publishPlan", new Class<?>[]{long.class}, System.nanoTime());
            if ((long) PredictionPlanningTestSupport.get(manager, "planCommits") == commits)
                throw new AssertionError("ownership fixture result was rejected");
        } finally { PredictionPlanningTestSupport.set(manager, "planPreparation", previous); }
    }

    @SuppressWarnings("unchecked")
    private static Set<PredictionTileKey> uploaded(PredictionTileManager manager, Collection<PredictionTileKey> keys,
                                                    int radius) throws Exception {
        var owned = new HashSet<PredictionTileKey>();
        for (var key : keys) if (Boolean.TRUE.equals(invoke(manager, "uploadedOwnership",
                new Class<?>[]{PredictionTileKey.class}, key))) owned.add(key);
        return Set.copyOf(owned);
    }

    static PredictionPlanPreparation.Input withUploaded(PredictionPlanPreparation.Input i, Set<PredictionTileKey> uploaded) {
        return new PredictionPlanPreparation.Input(i.dimension(), i.layout(), i.view(), i.focus(), i.x(), i.y(), i.z(),
                i.pixels(), i.vanillaRadius(), i.surfaceExtension(), i.minY(), i.maxY(), i.vegetation(), i.initialAxes(),
                i.settings(), i.revision(), i.removalRevision(), i.residents(), i.relief(), i.edited(), i.mixedEdits(),
                i.ownershipCandidates(), i.previousOwned(), uploaded, i.voxyRadius(), i.mediumBias(), i.visibility(), i.dirty(),
                i.viewBands(), i.viewDependencies(), i.failed(), i.deferred(), i.idle(), i.ordinaryBusy(),
                i.cpuReady(), i.memoryReady(), i.idlePending(), i.now(), i.nativeDemand());
    }

    static boolean hiddenChanged(PredictionTileManager manager, long now) throws Exception {
        var previous = field("scheduledOccluded").get(manager);
        loading(manager, now);
        return !previous.equals(field("scheduledOccluded").get(manager));
    }

    static Object paths(PredictionTileManager manager) throws Exception {
        return PredictionPlanningTestSupport.paths(manager);
    }

    static int ordinaryAxis(PredictionTileManager manager, PredictionTileKey key,
                            double x, double y, double z, double pixels, VssLodFocus focus) throws Exception {
        var sampler = (ClientTerrainSampler) field("sampler").get(manager);
        return PredictionPlanPreparation.ordinaryAxis(manager.layout(),
                (PredictionWorkView) field("workView").get(manager), focus, key, x, y, z, pixels,
                sampler.profile().minY(), sampler.profile().minY() + sampler.profile().height());
    }

    @SuppressWarnings("unchecked")
    static boolean reclaim(PredictionTileManager manager, int cx, int cz) throws Exception {
        invalidateFixtureInput(manager);
        field("scheduledPlan").set(manager, List.copyOf((Set<PredictionTileKey>) field("desiredKeys").get(manager)));
        var input = (PredictionSchedulePreparation.Input) invoke(manager, "schedulingInput",
                new Class<?>[]{long.class}, System.nanoTime());
        var result = PredictionSchedulePreparation.build(input);
        var work = (Deque<PredictionSchedulePreparation.Request>) field("preparedWork").get(manager);
        var reclaim = (Deque<PredictionTileKey>) field("preparedReclaim").get(manager);
        work.clear(); work.addAll(result.work());
        reclaim.clear(); reclaim.addAll(result.reclaim());
        return (boolean) invoke(manager, "reclaimPreparedWork", new Class<?>[]{int.class, int.class, long.class}, cx, cz, Long.MAX_VALUE);
    }

    private static void invalidateFixtureInput(PredictionTileManager manager) throws Exception {
        // Fixtures edit maps directly; normal publication invalidates these by version.
        field("renderSnapshot").set(manager, null);
        // Keep the previous immutable table and publish direct fixture edits as
        // a normal bounded delta. This preserves old residents while replacing
        // or removing the keys changed by the fixture.
        @SuppressWarnings("unchecked") var changes = (Set<PredictionTileKey>) field("snapshotChanges").get(manager);
        changes.addAll(((Map<PredictionTileKey, ?>) field("ready").get(manager)).keySet());
        var published = field("publishedTiles").get(manager);
        if (published instanceof Map<?, ?> table) {
            for (var entry : table.entrySet()) if (entry.getKey() instanceof PredictionTileKey key) changes.add(key);
        }
        // Demand mutations invalidate their tracked snapshots automatically.
    }

    private static void checkDeadline(long deadline, String stage) {
        if (System.nanoTime() >= deadline)
            throw new AssertionError(stage + " preparation did not publish a current fixture snapshot");
    }

    private static Object invoke(PredictionTileManager manager, String name, Class<?>[] types, Object... args) throws Exception {
        var method = PredictionTileManager.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(manager, args);
    }

    private static Field field(String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
