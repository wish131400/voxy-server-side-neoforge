package dev.xantha.vss.client.prediction;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

/** Pure loading/foreground/visibility accounting on the existing control worker. */
final class PredictionLoadingPreparation {
    record DisplayGoal(long revision, long captureEpoch, long meshRevision, int axis, boolean empty, boolean uploaded) { }
    record Input(PredictionSchedulePreparation.Input scheduling, List<PredictionTileKey> leaves,
                 Set<PredictionTileKey> terrainLeaves, Set<PredictionTileKey> owned,
                 Map<PredictionTileKey,Long> captureEpochs, Map<PredictionTileKey,DisplayGoal> goals,
                 int surfaceRadius, PredictionVisibilityFeedback.Snapshot visibility,
                 int settings, long stateVersion) { }
    record Result(Input input, PredictionLoadingProgress progress, Map<PredictionTileKey,DisplayGoal> goals,
                  boolean foreground, boolean preview, boolean surfacePreview, Set<PredictionTileKey> hidden,
                  Set<PredictionTileKey> reads) { }
    static Result build(Input input) { return new Calculation(input).build(); }
    private static final class Calculation {
        final Input request;
        final PredictionSchedulePreparation.Input input;
        final VssLodLayout layout;
        final PredictionWorkView workView;
        final Map<PredictionTileKey,PredictionTile> ready;
        final Set<PredictionTileKey> dirtyTiles, voxyOwnedTiles, terrainLeaves, surfaceDesired, surfaceReady;
        final Map<PredictionTileKey,Integer> ordinaryTargets;
        final PredictionMediumCoverage mediumCoverage;
        final Map<PredictionTileKey,Long> captureEpochs;
        Map<PredictionTileKey,DisplayGoal> displayGoals;
        boolean goalsChanged;
        final Set<PredictionTileKey> reads = new HashSet<>();
        final Map<PredictionTileKey,PredictionSchedulePreparation.Facts> facts=new HashMap<>();
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        PredictionLoadingProgress loadingProgress;
        Calculation(Input request) {
            this.request=request;input=request.scheduling();layout=input.layout();workView=input.view();
            var residents=input.residents().tiles();
            ready=new AbstractMap<>() {
                @Override public PredictionTile get(Object key) {
                    if(key instanceof PredictionTileKey tile)reads.add(tile);
                    return residents.get(key);
                }
                @Override public Set<Entry<PredictionTileKey,PredictionTile>> entrySet() {return residents.entrySet();}
            };
            dirtyTiles=input.dirty();voxyOwnedTiles=request.owned();
            terrainLeaves=request.terrainLeaves();surfaceDesired=input.surfaceDesired();surfaceReady=input.surfaceReady();
            ordinaryTargets=input.ordinary();mediumCoverage=input.medium();
            captureEpochs=request.captureEpochs();displayGoals=request.goals();
            dimension=input.residents().dimension();
        }
        PredictionSchedulePreparation.Facts facts(PredictionTileKey key) {
            reads.add(key);
            return facts.computeIfAbsent(key,k->new PredictionSchedulePreparation.Facts(input,k));
        }
        int viewBand(PredictionTileKey key){return facts(key).band();}
        void removeGoal(PredictionTileKey key) {
            if (!displayGoals.containsKey(key)) return;
            mutableGoals(); displayGoals.remove(key);
        }
        void mutableGoals() {
            if (!goalsChanged) { displayGoals=new HashMap<>(displayGoals);goalsChanged=true; }
        }
        void putGoal(PredictionTileKey key,DisplayGoal goal) { mutableGoals();displayGoals.put(key,goal); }
        int mediumAxis(PredictionTileKey key){return facts(key).mediumAxis();}
        boolean relevant(PredictionTileKey key){return facts(key).relevant();}
        boolean retry(PredictionTileKey key) { return input.retry(key); }
        boolean foreground() {
            for(var entry:input.ordinary().entrySet()) {
                var key=entry.getKey();if(!relevant(key)||voxyOwnedTiles.contains(key)||!retry(key))continue;
                var tile=ready.get(key);if(tile==null||tile.scopeOnly()||dirtyTiles.contains(key)||tile.cellAxis()<entry.getValue())return true;
            }
            for(var entry:input.transitions().entrySet())if(relevant(entry.getKey())&&retry(entry.getKey())) {
                var tile=ready.get(entry.getKey());if(tile==null||tile.cellAxis()<entry.getValue())return true;
            }
            for(var key:surfaceDesired)if(viewBand(key)<=PredictionWorkView.BUFFER&&retry(key)
                    &&!voxyOwnedTiles.contains(key)&&!surfaceReady.contains(key))return true;
            return false;
        }
        boolean preview(PredictionTileKey key) {
            var tile=ready.get(key);
            return retry(key) && (tile==null||tile.cellAxis()<Math.min(PredictionWorkOrder.PREVIEW_CELL_AXIS,facts(key).target()));
        }
        Result build() {
            progress(request.leaves(),input.x(),input.z());
            boolean preview=false,surfacePreview=false;
            var hidden=new HashSet<PredictionTileKey>();
            for(var key:input.plan())if(preview(key)) {
                if(!facts(key).defer(false))preview=true;
                if(relevant(key)&&PredictionWorkOrder.distanceSquared(key,layout,input.x(),input.z())
                        <=(double)request.surfaceRadius()*request.surfaceRadius())surfacePreview=true;
            }
            if(!request.visibility().proofs().isEmpty())for(var key:input.desired()) {
                reads.add(key);
                if(!input.scoped(key)&&request.visibility().hidden(key,layout,workView,input.now(),
                        ready,input.residents().epochs(),dirtyTiles))hidden.add(key);
            }
            return new Result(request,loadingProgress,goalsChanged ? Map.copyOf(displayGoals) : displayGoals,
                    foreground(),preview,surfacePreview,Set.copyOf(hidden),Set.copyOf(reads));
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
        for (var key : request.goals().keySet()) if (!terrainLeaves.contains(key) || voxyOwnedTiles.contains(key)) removeGoal(key);
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
                DisplayGoal goal = displayGoals.get(key);
                if (!complete || surface && !surfaceReady.contains(key)) removeGoal(key);
                else {
                    long epoch = captureEpochs.getOrDefault(key, 0L), revision = input.revision();
                    if (goal == null || goal.captureEpoch() != epoch || goal.meshRevision() != revision || goal.axis() != target) {
                        boolean empty = tile.data().voxyTile.features().isEmpty()
                                && java.util.Arrays.stream(tile.data().voxyTile.columns()).allMatch(column -> column.runs().isEmpty());
                        goal = new DisplayGoal(tile.revision(), epoch, revision, target, empty, false);
                        putGoal(key, goal);
                    }
                    var voxel=tile.data().voxyTile;
                    boolean uploadedGoal = goal.uploaded()
                            ? dev.xantha.vss.compat.StrictLodVisibility.overlayResident(dimension,
                                Math.floorDiv(voxel.baseX(),16),tile.depthBound().minY(),Math.floorDiv(voxel.baseZ(),16),
                                Math.floorDiv(voxel.baseX()+voxel.span()-1,16),tile.depthBound().maxY(),
                                Math.floorDiv(voxel.baseZ()+voxel.span()-1,16))
                            : dev.xantha.vss.compat.StrictLodVisibility.overlayUploaded(dimension,
                                Math.floorDiv(voxel.baseX(),16),tile.depthBound().minY(),Math.floorDiv(voxel.baseZ(),16),
                                Math.floorDiv(voxel.baseX()+voxel.span()-1,16),tile.depthBound().maxY(),
                                Math.floorDiv(voxel.baseZ()+voxel.span()-1,16));
                    if (uploadedGoal != goal.uploaded()) {
                        goal = new DisplayGoal(goal.revision(),goal.captureEpoch(),goal.meshRevision(),goal.axis(),goal.empty(),uploadedGoal);
                        putGoal(key,goal);
                    }
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
