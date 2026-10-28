package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionLoadingReadSetTest {
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static PredictionTile tile(PredictionTileKey key) {
        var tile=mock(PredictionTile.class);when(tile.key()).thenReturn(key);return tile;
    }
    private static final class State {
        final PredictionPlanPreparation.Input base=PredictionPlanningPipelineTest.planInput();
        final PredictionTileKey key=new PredictionTileKey(Level.OVERWORLD,0,0,0);
        final PredictionTileKey far=new PredictionTileKey(Level.OVERWORLD,999,999,0);
        final List<PredictionTileKey> plan=List.of(key);
        Map<PredictionTileKey,PredictionTile> tiles=new HashMap<>();
        Map<PredictionTileKey,Long> epochs=new HashMap<>(),captures=new HashMap<>(),failed=new HashMap<>(),deferred=new HashMap<>();
        Map<PredictionTileKey,Integer> ordinary=new HashMap<>(Map.of(key,32)),terrain=new HashMap<>(Map.of(key,32)),transitions=new HashMap<>(),bands=new HashMap<>();
        Set<PredictionTileKey> dirty=new HashSet<>(),surface=new HashSet<>(),surfaceReady=new HashSet<>();
        long now=base.now(),version;
        double x;
        boolean moving;
        net.minecraft.resources.ResourceKey<Level> dimension=Level.OVERWORLD;
        PredictionLoadingPreparation.Input input(){
            var scheduling=new PredictionSchedulePreparation.Input(base.layout(),base.focus(),base.view(),x,80,0,
                    base.pixels(),-64,320,base.revision(),plan,new RenderSnapshot(dimension,base.layout(),Map.copyOf(tiles),Map.copyOf(epochs)),
                    Set.of(key),Set.copyOf(surface),Set.copyOf(surfaceReady),Set.copyOf(dirty),Set.of(),Set.of(),Map.copyOf(ordinary),Map.copyOf(terrain),
                    Map.copyOf(transitions),Map.of(),PredictionMediumCoverage.EMPTY,false,false,false,moving,Map.copyOf(bands),Set.of(),Set.of(),
                    base.initialAxes(),null,Map.of(),Map.copyOf(failed),Map.copyOf(deferred),now,false);
            return new PredictionLoadingPreparation.Input(scheduling,plan,Set.of(key),Set.of(),Map.copyOf(captures),Map.of(),768,
                    PredictionVisibilityFeedback.Snapshot.EMPTY,base.settings(),version);
        }
        PredictionPlanningValidity.Current current(){return new PredictionPlanningValidity.Current(base.layout(),base.view(),base.focus(),x,80,0,
                base.settings(),base.revision(),base.removalRevision(),version,false,false);}
        boolean accepts(PredictionLoadingPreparation.Result result){return PredictionPlanningValidity.loading(result,current(),input());}
    }
    @Test void anUnrelatedPublicationAndItsDirtyEpochAndBandDoNotRevokeReadEvidence(){
        var s=new State();var result=PredictionLoadingPreparation.build(s.input());
        assertTrue(result.reads().contains(s.key));assertFalse(result.reads().contains(s.far));
        s.tiles.put(s.far,tile(s.far));s.dirty.add(s.far);s.epochs.put(s.far,1L);s.captures.put(s.far,2L);
        s.bands.put(s.far,PredictionWorkView.BACKGROUND);s.version++;
        assertTrue(s.accepts(result));
    }
    @Test void referencedReplacementCaptureDirtyAndEpochChangesAreRejected(){
        for(int mutation=0;mutation<4;mutation++) {
            var s=new State();var result=PredictionLoadingPreparation.build(s.input());
            switch(mutation){case 0->s.tiles.put(s.key,tile(s.key));case 1->s.captures.put(s.key,1L);
                case 2->s.dirty.add(s.key);case 3->s.epochs.put(s.key,1L);}
            assertFalse(s.accepts(result),"mutation "+mutation);
        }
    }
    @Test void newlyAddedObligationsCannotHideBehindAnOldReadSet(){
        for(int mutation=0;mutation<4;mutation++) {
            var s=new State();var result=PredictionLoadingPreparation.build(s.input());
            switch(mutation){case 0->s.surface.add(s.far);case 1->s.ordinary.put(s.far,32);
                case 2->s.transitions.put(s.far,64);case 3->s.terrain.put(s.key,64);}
            assertFalse(s.accepts(result),"mutation "+mutation);
        }
    }
    @Test void retryExpiryAndWorldReplacementRequireFreshAccounting(){
        var s=new State();s.deferred.put(s.key,s.now+10);var result=PredictionLoadingPreparation.build(s.input());
        s.now+=11;assertFalse(s.accepts(result));
        s=new State();result=PredictionLoadingPreparation.build(s.input());
        s.dimension=Level.NETHER;assertFalse(s.accepts(result));
        s=new State();result=PredictionLoadingPreparation.build(s.input());s.now+=1_000_000_001L;
        assertFalse(s.accepts(result));
    }
    @Test void equivalentRetryTimestampsAndMotionDoNotRevokeLoadingEvidence(){
        var s=new State();s.failed.put(s.key,s.now-60_000_000_000L);s.deferred.put(s.key,s.now-10);
        var result=PredictionLoadingPreparation.build(s.input());
        s.failed.put(s.key,s.now-61_000_000_000L);s.deferred.put(s.key,s.now-20);s.moving=true;
        assertTrue(s.accepts(result),"Loading reads retry availability, not the timestamp or movement flag itself");
    }
    @Test void crossingTheSurfaceAccountingBoundaryRequiresARefresh(){
        var s=new State();s.x=-767;var result=PredictionLoadingPreparation.build(s.input());
        s.x-=2;assertFalse(s.accepts(result));
    }
}
