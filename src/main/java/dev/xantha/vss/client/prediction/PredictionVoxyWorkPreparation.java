package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;

/** Pure candidate ranking. Completed resident demand stays pinned, but never enters the sort. */
final class PredictionVoxyWorkPreparation {
    record Input(VssLodLayout layout, VssLodFocus focus, PredictionWorkView view,
                 double x, double y, double z, double pixels, double minY, double maxY,
                 int surfaceRadius, long revision, long navigation,
                 Set<PredictionTileKey> targets, Set<PredictionTileKey> surfaces,
                 Set<PredictionTileKey> surfaceReady, Set<PredictionTileKey> dirty,
                 RenderSnapshot residents, Map<PredictionTileKey,Integer> bands) {
        int band(PredictionTileKey key) {
            if (PredictionWorkOrder.scoped(key,layout,focus)) return PredictionWorkView.VISIBLE;
            // Native requests outlive a planning snapshot. Rank against the current projection.
            return view == null ? PredictionWorkView.VISIBLE
                    : view.band(key,layout,focus,minY,maxY);
        }
    }
    private record Ranked(PredictionTileKey key, int band, int priority, double distance) { }
    record Result(Input input, List<PredictionTileKey> terrain, List<PredictionTileKey> surfaces) { }
    static Result build(Input input) {
        var terrain = new ArrayList<Ranked>(); var surfaces = new ArrayList<Ranked>();
        var candidates = new HashSet<>(input.targets()); candidates.addAll(input.surfaces());
        for (var key : candidates) {
            if (key.lod()<0 || key.lod()>=input.layout().levelCount()) continue;
            var tile=input.residents().tiles().get(key);
            boolean dirty=input.dirty().contains(key), detail=input.targets().contains(key);
            boolean needsTerrain=detail && (tile==null || dirty || tile.cellAxis()<input.layout().cellAxis(key.lod()));
            boolean needsSurface=tile!=null && !dirty && !input.surfaceReady().contains(key)
                    && (input.surfaces().contains(key) || detail && PredictionWorkOrder.surfaceEligible(key,
                        input.layout(),input.view(),input.focus(),input.x(),input.y(),input.z(),
                        input.surfaceRadius(),input.pixels(),input.minY(),input.maxY()));
            if (!needsTerrain && !needsSurface) continue;
            int band=input.band(key);
            double distance=PredictionWorkOrder.distanceSquared(key,input.layout(),input.x(),input.z());
            var ranked=new Ranked(key,band,PredictionWorkOrder.coveragePriority(distance,
                    tile==null ? 0 : tile.cellAxis(),false,0),distance);
            if(needsTerrain)terrain.add(ranked);
            if(needsSurface && band<=PredictionWorkView.BUFFER)surfaces.add(ranked);
        }
        var comparator=Comparator.comparingInt(Ranked::band).thenComparingInt(Ranked::priority).thenComparingDouble(Ranked::distance)
                .thenComparingInt(r->r.key().lod()).thenComparingInt(r->r.key().tileX()).thenComparingInt(r->r.key().tileZ());
        terrain.sort(comparator);surfaces.sort(comparator);
        return new Result(input,terrain.stream().map(Ranked::key).toList(),surfaces.stream().map(Ranked::key).toList());
    }
}
