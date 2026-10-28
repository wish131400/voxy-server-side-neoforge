package dev.xantha.vss.client.prediction;
import java.util.*;
import net.minecraft.world.level.Level;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
final class PredictionLoadingTestSupport {
    static PredictionSchedulePreparation.Input statusFixture(PredictionTileManager manager,int size) throws Exception {
        var layout=manager.layout();var keys=new ArrayList<PredictionTileKey>();
        @SuppressWarnings("unchecked") var ready=(Map<PredictionTileKey,PredictionTile>)get(manager,"ready");
        var targets=new HashMap<PredictionTileKey,Integer>();var bands=new HashMap<PredictionTileKey,Integer>();
        for(int i=0;i<size;i++) {var tile=PredictionVoxelFixtures.tile(i % 128,i / 128,0,layout);keys.add(tile.key());ready.put(tile.key(),tile);targets.put(tile.key(),64);bands.put(tile.key(),PredictionWorkView.VISIBLE);}
        @SuppressWarnings("unchecked") var captureEpochs=(Map<PredictionTileKey,Long>)get(manager,"captureEpochs");
        for(int i=0;i<size;i+=16)captureEpochs.put(keys.get(i),1L);
        var plan=List.copyOf(keys);var goals=Map.copyOf(targets);var view=PredictionWorkView.of(0,80,0,1,0,0,70,1);
        set(manager,"scheduledPlan",plan);set(manager,"scheduledLeaves",plan);set(manager,"ordinaryTargets",goals);set(manager,"terrainTargets",goals);
        set(manager,"workView",view);set(manager,"viewBands",Map.copyOf(bands));
        @SuppressWarnings("unchecked") var leaves=(Set<PredictionTileKey>)get(manager,"terrainLeaves");leaves.addAll(keys);
        var residents=manager.renderSnapshot();
        var axes=new ArrayList<Integer>();for(int l=0;l<manager.layout().levelCount();l++)axes.add(PredictionWorkOrder.initialCellAxis(l));
        return new PredictionSchedulePreparation.Input(manager.layout(),null,view,0,80,0,20,-64,384,0,plan,
                residents,Set.copyOf(keys),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),goals,goals,Map.of(),Map.of(),
                PredictionMediumCoverage.EMPTY,false,false,false,false,Map.copyOf(bands),Set.of(),Set.of(),
                List.copyOf(axes),null,Map.of(),Map.of(),Map.of(),System.nanoTime(),false);
    }
    static Object get(Object owner,String name)throws Exception{return PredictionPlanningTestSupport.get(owner,name);}
    static void set(Object owner,String name,Object value)throws Exception{PredictionPlanningTestSupport.set(owner,name,value);}
}
