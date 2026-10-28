package dev.xantha.vss.client.prediction;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
final class LoadingLegacyAccounting {
    static final class Calculation {
        final PredictionLoadingPreparation.Input request;
        final PredictionSchedulePreparation.Input input;
        final VssLodLayout layout;
        final PredictionWorkView workView;
        final Map<PredictionTileKey,PredictionTile> ready;
        final Set<PredictionTileKey> dirtyTiles, voxyOwnedTiles, terrainLeaves, surfaceDesired, surfaceReady;
        final Map<PredictionTileKey,Integer> ordinaryTargets;
        final PredictionMediumCoverage mediumCoverage;
        final Map<PredictionTileKey,Long> captureEpochs;
        final Map<PredictionTileKey,PredictionLoadingPreparation.DisplayGoal> displayGoals;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        PredictionLoadingProgress loadingProgress;
        Calculation(PredictionLoadingPreparation.Input request, Map<PredictionTileKey,PredictionTile> ready) {
            this.request=request;input=request.scheduling();layout=input.layout();workView=input.view();
            this.ready=ready;dirtyTiles=input.dirty();voxyOwnedTiles=request.owned();
            terrainLeaves=request.terrainLeaves();surfaceDesired=input.surfaceDesired();surfaceReady=input.surfaceReady();
            ordinaryTargets=input.ordinary();mediumCoverage=input.medium();
            captureEpochs=request.captureEpochs();displayGoals=new HashMap<>(request.goals());
            dimension=input.residents().dimension();
        }
        int viewBand(PredictionTileKey key){return input.band(key);}
        int mediumAxis(PredictionTileKey key){return input.mediumAxis(key);}
        boolean relevant(PredictionTileKey key){return input.relevant(key);}
        boolean foreground() {
            for(var entry:input.ordinary().entrySet()) {
                var key=entry.getKey();if(!relevant(key)||voxyOwnedTiles.contains(key)||!input.retry(key))continue;
                var tile=ready.get(key);if(tile==null||tile.scopeOnly()||dirtyTiles.contains(key)||tile.cellAxis()<entry.getValue())return true;
            }
            for(var entry:input.transitions().entrySet())if(relevant(entry.getKey())&&input.retry(entry.getKey())) {
                var tile=ready.get(entry.getKey());if(tile==null||tile.cellAxis()<entry.getValue())return true;
            }
            for(var key:surfaceDesired)if(input.band(key)<=PredictionWorkView.BUFFER&&input.retry(key)
                    &&!voxyOwnedTiles.contains(key)&&!surfaceReady.contains(key))return true;
            return false;
        }
        boolean preview(PredictionTileKey key) {
            var tile=ready.get(key);
            return input.retry(key) && (tile==null||tile.cellAxis()<Math.min(PredictionWorkOrder.PREVIEW_CELL_AXIS,input.target(key)));
        }
        void build() {
            progress(request.leaves(),input.x(),input.z());
            boolean preview=input.plan().stream().anyMatch(key -> preview(key) && !input.defer(key,false));
            boolean surfacePreview=input.plan().stream().anyMatch(key -> preview(key) && relevant(key)
                    && PredictionWorkOrder.distanceSquared(key,layout,input.x(),input.z())
                        <=(double)request.surfaceRadius()*request.surfaceRadius());
            var hidden=new HashSet<PredictionTileKey>();
            if(!request.visibility().proofs().isEmpty())for(var key:input.desired())
                if(!input.scoped(key)&&request.visibility().hidden(key,layout,workView,input.now(),
                        ready,input.residents().epochs(),dirtyTiles))hidden.add(key);
            foreground();
        }
    private void progress(List<PredictionTileKey> leaves, double x, double z) {
        int covered = 0, near = 0, nearReady = 0, targetReady = 0, importantSurfaces = 0, surfacesReady = 0;
        boolean tracking = workView != null;
        for (var key : mediumCoverage.frontier()) {
            var tile = ready.get(key);
            if (tracking) {
                for (var ancestor = key; ancestor.lod() < layout.levelCount(); ancestor = new PredictionTileKey(
                        dimension, ancestor.tileX() >> 1, ancestor.tileZ() >> 1, ancestor.lod() + 1)) {
                    var coarse = ready.get(ancestor);
                    if (coarse != null && !coarse.scopeOnly() && !dirtyTiles.contains(ancestor)) { covered++; break; }
                }
            } else if (tile != null && !tile.scopeOnly() && tile.cellAxis() >= 32) covered++;
        }
        int visible = 0, visibleTerrain = 0, visibleSurface = 0, visibleSurfaceReady = 0, drawable = 0, deferred = 0;
        displayGoals.keySet().removeIf(key -> !terrainLeaves.contains(key) || voxyOwnedTiles.contains(key));
        double radius = PredictionDetailBands.fineRadius(layout.maxDistanceBlocks(), dimension);
        for (var key : leaves) {
            if (voxyOwnedTiles.contains(key)) continue;
            var tile = ready.get(key);
            int target = ordinaryTargets.getOrDefault(key, layout.cellAxis(key.lod()));
            boolean current = tile != null && !tile.scopeOnly() && !dirtyTiles.contains(key);
            boolean complete = current && tile.cellAxis() >= target;
            if (PredictionWorkOrder.distanceSquared(key, layout, x, z) <= radius * radius) {
                near++;
                if (current && tile.cellAxis() >= Math.min(64, target)) nearReady++;
                if (complete) targetReady++;
            }
            if (tracking && viewBand(key) == PredictionWorkView.VISIBLE) {
                visible++;
                if (complete) visibleTerrain++;
                boolean surface = surfaceDesired.contains(key);
                if (surface) {
                    visibleSurface++;
                    if (complete && surfaceReady.contains(key)) visibleSurfaceReady++;
                }
                // Optional upgrades preserve the ordinary goal; content changes require a new revision.
                PredictionLoadingPreparation.DisplayGoal goal = displayGoals.get(key);
                if (!complete || surface && !surfaceReady.contains(key)) displayGoals.remove(key);
                else {
                    long epoch = captureEpochs.getOrDefault(key, 0L), revision = input.revision();
                    if (goal == null || goal.captureEpoch() != epoch || goal.meshRevision() != revision || goal.axis() != target) {
                        boolean empty = tile.data() != null && tile.data().voxyTile != null
                                && tile.data().voxyTile.features().isEmpty()
                                && java.util.Arrays.stream(tile.data().voxyTile.columns()).allMatch(c -> c.runs().isEmpty());
                        goal = new PredictionLoadingPreparation.DisplayGoal(tile.revision(), epoch, revision, target, empty, false);
                        displayGoals.put(key, goal);
                    }
                    var source = tile.data().voxyTile;
                    boolean uploadedGoal = goal.uploaded()
                            ? dev.xantha.vss.compat.StrictLodVisibility.overlayResident(dimension,
                                Math.floorDiv(source.baseX(),16),tile.depthBound().minY(),Math.floorDiv(source.baseZ(),16),
                                Math.floorDiv(source.baseX()+source.span()-1,16),tile.depthBound().maxY(),Math.floorDiv(source.baseZ()+source.span()-1,16))
                            : dev.xantha.vss.compat.StrictLodVisibility.overlayUploaded(dimension,
                                Math.floorDiv(source.baseX(),16),tile.depthBound().minY(),Math.floorDiv(source.baseZ(),16),
                                Math.floorDiv(source.baseX()+source.span()-1,16),tile.depthBound().maxY(),Math.floorDiv(source.baseZ()+source.span()-1,16));
                    if (goal.empty() || uploadedGoal) drawable++;
                }
            } else if (tracking && viewBand(key) == PredictionWorkView.BACKGROUND
                    && (!complete || surfaceDesired.contains(key) && !surfaceReady.contains(key))) deferred++;
        }
        for (var key : surfaceDesired) {
            if (voxyOwnedTiles.contains(key) || !terrainLeaves.contains(key)) continue;
            importantSurfaces++;
            var tile = ready.get(key);
            if (tile != null && !tile.scopeOnly() && !dirtyTiles.contains(key)
                    && tile.cellAxis() >= ordinaryTargets.getOrDefault(key, layout.cellAxis(key.lod()))
                    && surfaceReady.contains(key)) surfacesReady++;
        }
        var view = tracking ? new PredictionLoadingProgress.ViewProgress(
                visible, visibleTerrain, visibleSurface, visibleSurfaceReady, drawable, deferred) : null;
        loadingProgress = new PredictionLoadingProgress(mediumCoverage.frontier().size(), covered,
                near, nearReady, near, targetReady, importantSurfaces, surfacesReady, true, view);
    }

    }
}
