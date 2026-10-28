package dev.xantha.vss.client.prediction;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
class PredictionLoadingBenefitTest {
    @BeforeAll static void bootstrap(){ ClientTerrainSamplerTest.bootstrapMinecraft(); }





    @Test void backgroundLoadingAccountingMatchesTheSynchronousPolicyAndRejectsStalePublication() throws Exception {
        boolean remember=dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=false;
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        try(var manager=new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,profile,(x,z)->64),
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
            var schedule=PredictionLoadingTestSupport.statusFixture(manager,80);
            var snapshot=PredictionTileManager.class.getDeclaredMethod("loadingInput",PredictionSchedulePreparation.Input.class);snapshot.setAccessible(true);
            var request=(PredictionLoadingPreparation.Input)snapshot.invoke(manager,schedule);
            var expected=PredictionLoadingPreparation.build(request);
            var stableRequest=new PredictionLoadingPreparation.Input(schedule,request.leaves(),request.terrainLeaves(),request.owned(),
                    request.captureEpochs(),expected.goals(),request.surfaceRadius(),request.visibility(),request.settings(),request.stateVersion());
            assertSame(expected.goals(),PredictionLoadingPreparation.build(stableRequest).goals(),
                    "unchanged progress must reuse the immutable display goals");
            var limitedKey=schedule.plan().get(0);
            var absent=new PredictionTileTable<PredictionTile>(Level.OVERWORLD)
                    .changed(schedule.residents().tiles(),List.of()).changed(Map.of(),List.of(limitedKey));
            var changedSchedule=new PredictionSchedulePreparation.Input(schedule.layout(),schedule.focus(),schedule.view(),
                    schedule.x(),schedule.y(),schedule.z(),schedule.pixels(),schedule.minY(),schedule.maxY(),schedule.revision(),
                    schedule.plan(),new RenderSnapshot(Level.OVERWORLD,schedule.layout(),absent,Map.of(),Set.of()),
                    schedule.desired(),schedule.surfaceDesired(),schedule.surfaceReady(),schedule.dirty(),schedule.fallback(),schedule.pending(),
                    schedule.ordinary(),schedule.terrain(),schedule.transitions(),schedule.idle(),schedule.medium(),schedule.mediumPending(),
                    schedule.previewPending(),schedule.localTurn(),schedule.moving(),schedule.bands(),schedule.dependencies(),schedule.hidden(),
                    schedule.initialAxes(),schedule.disk(),schedule.cacheMiss(),schedule.failed(),schedule.deferred(),schedule.now(),schedule.reclaimNeeded());
            var withoutLimit=new PredictionLoadingPreparation.Input(changedSchedule,request.leaves(),request.terrainLeaves(),request.owned(),
                    request.captureEpochs(),request.goals(),request.surfaceRadius(),request.visibility(),request.settings(),request.stateVersion());
            assertTrue(PredictionLoadingPreparation.build(withoutLimit).foreground());
            var reference = new LoadingLegacyAccounting.Calculation(request, schedule.residents().tiles());
            reference.build(); assertEquals(reference.loadingProgress, expected.progress());
            var desired=PredictionTileManager.class.getDeclaredMethod("desiredSnapshot");desired.setAccessible(true);
            @SuppressWarnings("unchecked") var desiredKeys=(Set<PredictionTileKey>)PredictionLoadingTestSupport.get(manager,"desiredKeys");
            desiredKeys.addAll(schedule.plan());
            @SuppressWarnings("unchecked") var original=(Set<PredictionTileKey>)desired.invoke(manager);
            assertSame(original,desired.invoke(manager));
            var withdrawn=schedule.plan().get(0);var fallback=new PredictionTileKey(Level.OVERWORLD,-101,-92,1);
            desiredKeys.remove(withdrawn);desiredKeys.add(fallback);
            @SuppressWarnings("unchecked") var replaced=(Set<PredictionTileKey>)desired.invoke(manager);
            assertEquals(original.size(),replaced.size());assertFalse(replaced.contains(withdrawn));assertTrue(replaced.contains(fallback));
            assertTrue(original.contains(withdrawn),"a queued worker keeps an immutable earlier demand snapshot");
            var jobs=new ArrayDeque<Runnable>();
            var preparation=new PredictionClientPreparation<PredictionLoadingPreparation.Input,PredictionLoadingPreparation.Result>(jobs::add,PredictionLoadingPreparation::build);
            PredictionLoadingTestSupport.set(manager,"loadingPreparation",preparation);
            preparation.request(request);jobs.removeFirst().run();
            PredictionLoadingTestSupport.set(manager,"scheduledPlan",List.of());
            var publish=PredictionTileManager.class.getDeclaredMethod("publishLoadingWork");publish.setAccessible(true);publish.invoke(manager);
            assertEquals(1L,PredictionLoadingTestSupport.get(manager,"loadingDiscards"));
        }finally{dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=remember;}
    }
    @Test void captureDirtinessRejectsLoadingCompletionBeforeTheNewMeshPublishes() throws Exception {
        boolean remember=dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain;
        dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=false;
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        try(var manager=new PredictionTileManager(Level.OVERWORLD,ClientTerrainSampler.custom(42,profile,(x,z)->64),
                new PredictionMemoryBudget(512L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime),null)) {
            var schedule=PredictionLoadingTestSupport.statusFixture(manager,80);
            var snapshot=PredictionTileManager.class.getDeclaredMethod("loadingInput",PredictionSchedulePreparation.Input.class);snapshot.setAccessible(true);
            var request=(PredictionLoadingPreparation.Input)snapshot.invoke(manager,schedule);
            var jobs=new ArrayDeque<Runnable>();
            var preparation=new PredictionClientPreparation<PredictionLoadingPreparation.Input,PredictionLoadingPreparation.Result>(jobs::add,PredictionLoadingPreparation::build);
            PredictionLoadingTestSupport.set(manager,"loadingPreparation",preparation);
            preparation.request(request);jobs.removeFirst().run();
            manager.capturedTerrainChanged(0,0);
            var publish=PredictionTileManager.class.getDeclaredMethod("publishLoadingWork");publish.setAccessible(true);publish.invoke(manager);
            assertEquals(1L,PredictionLoadingTestSupport.get(manager,"loadingReadinessDiscards"),"capture must still reject stale readiness");
            assertEquals(0L,PredictionLoadingTestSupport.get(manager,"loadingReadinessCommits"));
            assertEquals(1L,PredictionLoadingTestSupport.get(manager,"loadingDisplayCommits"),"recent dated counts may remain observable");
            assertEquals(Boolean.FALSE,manager.loadingObservation().get("fresh"));
            assertFalse(manager.loadingProgress().planned(),"capture cannot publish stale 100% readiness");
            assertEquals(Boolean.TRUE,PredictionLoadingTestSupport.get(manager,"foregroundWorkPending"));
        }finally{dev.xantha.vss.config.VSSClientConfig.CONFIG.rememberTerrain=remember;}
    }
}
