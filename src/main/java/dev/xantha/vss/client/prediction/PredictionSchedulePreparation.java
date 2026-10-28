package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Priority hints and sorted candidates. Admission always rechecks live resource limits. */
final class PredictionSchedulePreparation {
    record Request(PredictionTileKey key, boolean surface, int priority, double distance) { }
    record Hint(int terrain, int surface, double distance) { }
    record Input(VssLodLayout layout, VssLodFocus focus, PredictionWorkView view, double x, double y, double z,
                 double pixels, double minY, double maxY, long revision, List<PredictionTileKey> plan,
                 PredictionTileManager.RenderSnapshot residents, Set<PredictionTileKey> desired,
                 Set<PredictionTileKey> surfaceDesired, Set<PredictionTileKey> surfaceReady,
                 Set<PredictionTileKey> dirty, Set<PredictionTileKey> fallback, Set<PredictionTileKey> pending,
                 Map<PredictionTileKey, Integer> ordinary, Map<PredictionTileKey, Integer> terrain,
                 Map<PredictionTileKey, Integer> transitions, Map<PredictionTileKey, Integer> idle,
                 PredictionMediumCoverage medium, boolean mediumPending, boolean previewPending,
                 boolean localTurn, boolean moving, Map<PredictionTileKey, Integer> bands,
                 Set<PredictionTileKey> dependencies, Set<PredictionTileKey> hidden,
                 List<Integer> initialAxes, PredictionDiskCache disk, Map<PredictionTileKey, Long> cacheMiss,
                 Map<PredictionTileKey, Long> failed, Map<PredictionTileKey, Long> deferred, long now, boolean reclaimNeeded) {
        Facts facts(PredictionTileKey key){return new Facts(this,key);}
        int axis(PredictionTileKey key){var tile=residents.tiles().get(key);return tile==null?0:tile.cellAxis();}
        boolean scoped(PredictionTileKey key){return PredictionWorkOrder.scoped(key,layout,focus);}
        int band(PredictionTileKey key){return facts(key).band();}
        int mediumAxis(PredictionTileKey key) {
            int axis=medium.frontier().contains(key)?PredictionWorkOrder.PREVIEW_CELL_AXIS:initialAxes.get(key.lod());
            return view!=null&&terrain.containsKey(key)?Math.min(axis,ordinary.getOrDefault(key,axis)):axis;
        }
        boolean mediumWork(PredictionTileKey key){return facts(key).mediumWork();}
        boolean required(PredictionTileKey key,boolean surface){return facts(key).required(surface);}
        boolean relevant(PredictionTileKey key){return facts(key).relevant();}
        boolean idleWork(PredictionTileKey key){return facts(key).idleWork();}
        int offset(PredictionTileKey key,boolean surface){return facts(key).offset(surface);}
        int priority(PredictionTileKey key,boolean surface){return facts(key).priority(surface);}
        int target(PredictionTileKey key){return facts(key).target();}
        boolean surfaceTerrainReady(PredictionTileKey key){return facts(key).surfaceTerrainReady();}
        boolean retry(PredictionTileKey key) {
            return now-deferred.getOrDefault(key,Long.MIN_VALUE/2)>=0 && now-failed.getOrDefault(key,Long.MIN_VALUE/2)>30_000_000_000L;
        }
        boolean defer(PredictionTileKey key,boolean surface){return facts(key).defer(surface);}
    }

    record Result(Input input, List<Request> work, List<PredictionTileKey> cached, List<PredictionTileKey> reclaim,
                  Map<PredictionTileKey, Hint> hints) { }
    /** One evaluation per immutable input/key. No live admission decision is cached here. */
    static final class Facts {
        final Input input;
        final PredictionTileKey key;
        final PredictionTileManager.PredictionTile tile;
        final int axis;
        final boolean scoped,dirty,dependency,mediumPath,frontier;
        private int band=-1;
        private double distance=Double.NaN,projected=Double.NaN;
        Facts(Input input,PredictionTileKey key) {
            this.input=input;this.key=key;tile=input.residents().tiles().get(key);axis=tile==null?0:tile.cellAxis();
            scoped=input.scoped(key);dirty=input.dirty().contains(key);dependency=input.dependencies().contains(key);
            mediumPath=input.medium().paths().contains(key);frontier=input.medium().frontier().contains(key);
        }
        int band() {
            if(band<0) {Integer known=input.bands().get(key);band=scoped?PredictionWorkView.VISIBLE:known!=null?known
                    :input.view()==null?PredictionWorkView.VISIBLE:input.view().band(key,input.layout(),input.focus(),input.minY(),input.maxY());}
            return band;
        }
        double distance() {
            if(Double.isNaN(distance))distance=PredictionWorkOrder.orderingDistance(key,input.layout(),input.x(),input.z(),input.focus());
            return distance;
        }
        int mediumAxis() {return input.mediumAxis(key);}
        boolean mediumWork(){return input.mediumPending()&&mediumPath&&axis<mediumAxis();}
        boolean required(boolean surface) {
            if(dirty||input.fallback().contains(key)||scoped)return true;
            return !surface&&(tile==null||(mediumPath||dependency)&&tile.scopeOnly()
                    ||dependency&&input.transitions().containsKey(key)&&axis<input.transitions().get(key));
        }
        boolean relevant(){return band()<=PredictionWorkView.BUFFER||dependency;}
        boolean idleWork(){return input.idle().containsKey(key)&&!dirty&&!scoped
                &&(!input.ordinary().containsKey(key)||axis>=input.ordinary().get(key));}
        int offset(boolean surface) {
            if(input.hidden().contains(key)&&!required(surface))return band()==PredictionWorkView.VISIBLE?350_000:650_000;
            if(band()==PredictionWorkView.VISIBLE||!surface&&dependency)return 0;
            return band()==PredictionWorkView.BUFFER?300_000:band()==PredictionWorkView.RECENT?600_000:PredictionWorkOrder.BACKGROUND_PRIORITY;
        }
        int priority(boolean surface) {
            if(dirty)return Integer.MIN_VALUE+1+key.lod();
            if(!surface&&idleWork())return input.view()==null?2_000_000:400_000+offset(false);
            if(scoped)return PredictionWorkOrder.priority(key,input.layout(),distance(),axis,surface,input.focus());
            int screen=0;
            if(input.view()!=null&&band()<=PredictionWorkView.BUFFER&&!mediumWork()) {
                if(Double.isNaN(projected))projected=PredictionScreenDetail.projected(input.view(),key,input.layout(),input.x(),input.y(),input.z(),input.pixels(),input.minY(),input.maxY());
                double perCell=input.layout().pixelThreshold()/VssLodLayout.TILE_QUADS;
                double error=surface?projected*8/input.layout().tileBlocks(key.lod())/Math.max(1.5,Math.min(3,perCell))
                        :projected/Math.max(1,axis==0?8:axis)/perCell;
                screen=(int)(6000/(1+Math.max(0,error)));
            }
            return offset(surface)+PredictionWorkOrder.coveragePriority(distance(),axis,surface,screen);
        }
        int target() {
            int target=input.terrain().getOrDefault(key,input.initialAxes().get(key.lod()));
            if(frontier)target=Math.max(mediumAxis(),target);
            if(input.mediumPending()&&!scoped&&mediumPath&&(!frontier||mediumWork()))return mediumAxis();
            return Math.max(target,input.transitions().getOrDefault(key,target));
        }
        boolean retry(){return input.retry(key);}
        boolean defer(boolean surface){return input.view()!=null&&band()>=PredictionWorkView.RECENT&&(surface||axis>0)
                &&!required(surface)&&!(!surface&&dependency);}
        boolean surfaceTerrainReady(){return tile!=null&&(tile.data()!=null&&tile.data().voxyTile!=null||axis==input.layout().cellAxis(key.lod()));}
    }
    private record Reclaim(PredictionTileKey key,int priority,double distance) { }
    static Result build(Input i) {
        var work = new ArrayList<Request>();
        var keys = new LinkedHashSet<>(i.dirty()); keys.addAll(i.plan()); keys.addAll(i.surfaceDesired());
        var cached = new ArrayList<Facts>();
        var facts = new HashMap<PredictionTileKey,Facts>();
        var hints = new HashMap<PredictionTileKey, Hint>();
        for (var key : keys) {
            if (!i.desired().contains(key) || key.lod() < 0 || key.lod() >= i.layout().levelCount()) continue;
            var f=facts.computeIfAbsent(key,k->new Facts(i,k));double distance=f.distance();
            var hint = new Hint(f.priority(false), f.priority(true), distance); hints.put(key, hint);
            if (i.pending().contains(key)) continue;
            boolean retry=f.retry();
            if (retry && !f.defer(false) && (f.dirty || f.axis < f.target()))
                work.add(new Request(key, false, hint.terrain(), distance));
            if (retry && !f.defer(true) && i.surfaceDesired().contains(key)
                    && f.surfaceTerrainReady() && !f.dirty && !i.surfaceReady().contains(key))
                work.add(new Request(key, true, hint.surface(), distance));
        }
        // Native node demand can be outside the ordinary plan. Restore every desired
        // cached upgrade independently of generation priority and CPU admission.
        if (i.disk() != null) for (var key : i.desired()) {
            if (key.lod() < 0 || key.lod() >= i.layout().levelCount() || i.pending().contains(key)) continue;
            if (i.now() - i.cacheMiss().getOrDefault(key, Long.MIN_VALUE / 2) >= 0
                    && i.disk().cachedTerrainAxis(PredictionDiskCache.Key.terrain(key.tileX(), key.tileZ(), key.lod())) > i.axis(key))
                cached.add(facts.computeIfAbsent(key,k->new Facts(i,k)));
        }
        work.sort(Comparator.comparingInt(Request::priority).thenComparingDouble(Request::distance)
                .thenComparingInt(request -> -request.key().lod()));
        cached.forEach(f->{f.band();f.distance();});
        cached.sort(Comparator.comparingInt(Facts::band).thenComparingDouble(Facts::distance)
                .thenComparingInt(f -> f.axis > 0 ? 1 : 0).thenComparingInt(f -> -f.key.lod()));
        var reclaim = new ArrayList<PredictionTileKey>();
        if (i.reclaimNeeded()) {
        reclaim.addAll(i.residents().tiles().keySet());
        reclaim.removeIf(key -> key.lod() < 0 || key.lod() >= i.layout().levelCount() || i.scoped(key)
                || i.medium().paths().contains(key) || i.pending().contains(key)
                || !PredictionTileResidencyPolicy.coveredByAncestor(i.residents().tiles().keySet(), i.layout(), key));
        var ranked=new ArrayList<Reclaim>(reclaim.size());
        for(var key:reclaim)ranked.add(new Reclaim(key,PredictionWorkOrder.priority(key,i.layout(),
                PredictionWorkOrder.distanceSquared(key,i.layout(),i.x(),i.z()),0,false,i.focus()),
                facts.computeIfAbsent(key,k->new Facts(i,k)).distance()));
        ranked.sort(Comparator.comparingInt(Reclaim::priority).thenComparingDouble(Reclaim::distance).reversed());
        reclaim.clear();ranked.forEach(r->reclaim.add(r.key()));
        }
        return new Result(i, List.copyOf(work), cached.stream().map(f->f.key).toList(), List.copyOf(reclaim), Map.copyOf(hints));
    }
}
