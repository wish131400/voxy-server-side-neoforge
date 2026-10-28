package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionPlanningValidityTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static final class Fixture {
        final PredictionPlanPreparation.Input input = PredictionPlanningPipelineTest.planInput();
        VssLodLayout layout = input.layout();
        PredictionWorkView view = input.view();
        VssLodFocus focus = input.focus();
        double x = input.x(), y = input.y(), z = input.z();
        int settings = input.settings();
        long revision = input.revision(), removal = input.removalRevision(), loadingVersion;
        boolean closed, paused;

        PredictionPlanningValidity.Current current() {
            return new PredictionPlanningValidity.Current(layout, view, focus, x, y, z, settings,
                    revision, removal, loadingVersion, closed, paused);
        }

        PredictionPlanPreparation.Result plan() {
            return PredictionPlanPreparation.build(input, new PredictionExactCoverageIndex().planningView(Level.OVERWORLD, input.now()));
        }
    }

    @Test void planAcceptsSmallMotionButRejectsTeleportAndVerticalRelocation() {
        var f = new Fixture(); var result = f.plan();
        f.x += 128;
        assertTrue(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        f.x += .01;
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        f.x = 0; f.y += 129;
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
    }

    @Test void planRejectsContentConfigCoverageAndFocusChangesIndependently() {
        var f = new Fixture(); var result = f.plan();
        f.revision++;
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        f.revision--; f.settings++;
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        f.settings--; f.removal++;
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        f.removal--; f.focus = new VssLodFocus(0, 0, 128);
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        f.focus = null; f.layout = VssLodLayout.of(8192, 6, true, false);
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
    }

    @Test void allPublicationChannelsRejectPausedAndClosedOwners() {
        var f = new Fixture(); var result = f.plan();
        var scheduling = PredictionPlanningPipelineTest.schedulingInput(f.input);
        var view = new PredictionDemandPreparation.Input(f.layout, null, null, Set.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), -64, 320, 0);
        f.paused = true;
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        assertFalse(PredictionPlanningValidity.view(view, f.current(), Set.of()));
        assertFalse(PredictionPlanningValidity.schedule(scheduling, f.current(), scheduling.plan()));
        f.paused = false; f.closed = true;
        assertFalse(PredictionPlanningValidity.plan(result, f.current(), key -> true));
        assertFalse(PredictionPlanningValidity.view(view, f.current(), Set.of()));
        assertFalse(PredictionPlanningValidity.schedule(scheduling, f.current(), scheduling.plan()));
    }

    @Test void viewResultRequiresTheSameDesiredMembersAndRejectsACameraCut() {
        var f = new Fixture();
        var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
        var request = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1);
        var input = new PredictionDemandPreparation.Input(f.layout, request, null, Set.of(key), Map.of(),
                Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), -64, 320, 0);
        f.view = PredictionWorkView.of(0, 80, 0, 1, 0, .05, 70, 1);
        assertTrue(PredictionPlanningValidity.view(input, f.current(), Set.of(key)));
        assertFalse(PredictionPlanningValidity.view(input, f.current(), Set.of()));
        f.view = PredictionWorkView.of(0, 80, 0, -1, 0, 0, 70, 1);
        assertFalse(PredictionPlanningValidity.view(input, f.current(), Set.of(key)));
    }

    @Test void loadingRequiresSnapshotIdentityAndItsOwnStateVersion() {
        var f = new Fixture();
        var scheduling = PredictionPlanningPipelineTest.schedulingInput(f.input);
        var residents = scheduling.residents();
        var bands = scheduling.bands();
        var loading = new PredictionLoadingPreparation.Input(scheduling, List.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), 768, PredictionVisibilityFeedback.Snapshot.EMPTY, f.settings, 0);
        assertTrue(PredictionPlanningValidity.loading(loading, f.current(), scheduling.plan(), residents, bands));
        var equivalent = new RenderSnapshot(Level.OVERWORLD, f.layout, Map.of(), Map.of());
        assertFalse(PredictionPlanningValidity.loading(loading, f.current(), scheduling.plan(), equivalent, bands));
        f.loadingVersion++;
        assertFalse(PredictionPlanningValidity.loading(loading, f.current(), scheduling.plan(), residents, bands));
        f.loadingVersion--; f.paused = true;
        assertFalse(PredictionPlanningValidity.loading(loading, f.current(), scheduling.plan(), residents, bands));
    }

    @Test void displayMayObserveChurnButRejectsOldConfigTeleportAndStoppedOwners() {
        var f = new Fixture();
        var scheduling = PredictionPlanningPipelineTest.schedulingInput(f.input);
        var loading = new PredictionLoadingPreparation.Input(scheduling, List.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), 768, PredictionVisibilityFeedback.Snapshot.EMPTY, f.settings, 0);
        long now = scheduling.now() + 1;
        f.loadingVersion++;
        assertTrue(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),now));
        assertFalse(PredictionPlanningValidity.loading(loading,f.current(),scheduling.plan(),scheduling.residents(),
                scheduling.bands()),"observation must not relax readiness");
        assertFalse(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),scheduling.now()+1_000_000_001L));
        assertFalse(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),scheduling.now()-1));
        f.x += 129;
        assertFalse(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),now));
        f.x -= 129; f.settings++;
        assertFalse(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),now));
        f.settings--; f.paused = true;
        assertFalse(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),now));
        f.paused = false; f.closed = true;
        assertFalse(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),now));
        f.closed = false; f.y += 129;
        assertFalse(PredictionPlanningValidity.loadingDisplay(loading,f.current(),scheduling.plan(),now));
    }
}
