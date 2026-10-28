package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;

/**
 * 后台规划结果的纯校验边界，不缓存版本，也不发布状态。
 * <p>调用方在客户端线程提供当前标量、已发布快照身份和 Voxy 上传证据。不同通道保留
 * 原有精度：普通计划允许小幅移动，加载准入校验实际读取的居民/目标/视角，避免旧结果恢复就绪。
 * 带年龄的诊断显示可以观察普通移动中的旧快照，但不能发布准入、目标或遮挡状态。
 */
final class PredictionPlanningValidity {
    private PredictionPlanningValidity() { }

    record Current(VssLodLayout layout, PredictionWorkView view, VssLodFocus focus,
                   double x, double y, double z, int settings, long revision, long removalRevision,
                   long loadingVersion, boolean closed, boolean paused) {
        boolean active() { return !closed && !paused; }
        boolean noViewCut(PredictionWorkView requested) { return view == null || !view.cutFrom(requested); }
    }

    static boolean view(PredictionDemandPreparation.Input input, Current current, Set<PredictionTileKey> desired) {
        return current.active() && input.layout().equals(current.layout()) && input.desired().equals(desired)
                && Objects.equals(input.focus(), current.focus()) && current.noViewCut(input.view());
    }

    static boolean plan(PredictionPlanPreparation.Result result, Current current, Predicate<PredictionTileKey> uploaded) {
        var input = result.input();
        if (!current.active() || !input.layout().equals(current.layout()) || input.settings() != current.settings()
                || input.revision() != current.revision() || input.removalRevision() != current.removalRevision()
                || !Objects.equals(input.focus(), current.focus())
                || Math.hypot(input.x() - current.x(), input.z() - current.z()) > 128
                || Math.abs(input.y() - current.y()) > 128
                || !input.nativeDemand() && !current.noViewCut(input.view())) return false;
        int radius = PredictionVoxyOwnership.radiusBlocks(input.voxyRadius());
        for (var key : result.owned()) {
            int span = current.layout().tileBlocks(key.lod()), margin = input.previousOwned().contains(key) ? 32 : 64;
            double x = key.tileX() * (double) span, z = key.tileZ() * (double) span;
            if (!PredictionVoxyOwnership.containsBox(current.x(), current.y(), current.z(), Math.max(0, radius - margin),
                    x, input.minY(), z, x + span, input.maxY(), z + span) || !uploaded.test(key)) return false;
        }
        return true;
    }

    static boolean schedule(PredictionSchedulePreparation.Input input, Current current, List<PredictionTileKey> plan) {
        return current.active() && input.layout().equals(current.layout()) && input.revision() == current.revision()
                && input.plan() == plan && Objects.equals(input.focus(), current.focus()) && current.noViewCut(input.view());
    }

    static boolean loading(PredictionLoadingPreparation.Input request, Current current,
            List<PredictionTileKey> plan, RenderSnapshot residents,
            Map<PredictionTileKey, Integer> bands) {
        var input = request.scheduling();
        return schedule(input, current, plan) && request.stateVersion() == current.loadingVersion()
                && input.residents() == residents && input.bands() == bands
                && (current.view() == null || !current.view().changedFrom(input.view()));
    }

    /** Unrelated tile publications do not revoke evidence read by the loading worker. */
    static boolean loading(PredictionLoadingPreparation.Result result, Current current,
                           PredictionLoadingPreparation.Input live) {
        var request=result.input();var before=request.scheduling();var after=live.scheduling();
        if(!loadingDisplay(request,current,after.plan(),after.now())
                || !before.residents().dimension().equals(after.residents().dimension())
                || request.settings()!=live.settings() || request.surfaceRadius()!=live.surfaceRadius()
                || before.minY()!=after.minY() || before.maxY()!=after.maxY()
                || !request.leaves().equals(live.leaves()) || !request.terrainLeaves().equals(live.terrainLeaves())
                || !request.owned().equals(live.owned())
                || !request.goals().equals(live.goals())
                || !before.surfaceDesired().equals(after.surfaceDesired())
                || !before.ordinary().keySet().equals(after.ordinary().keySet())
                || !before.transitions().keySet().equals(after.transitions().keySet())
                || before.mediumPending()!=after.mediumPending()
                || !before.initialAxes().equals(after.initialAxes())
                || !before.medium().frontier().equals(after.medium().frontier())
                || !before.medium().paths().equals(after.medium().paths())
                || !request.visibility().sameHints(live.visibility(),before.now(),after.now()))return false;
        if(!request.visibility().proofs().isEmpty() && (!before.desired().equals(after.desired())
                || (after.view()!=null && after.view().changedFrom(before.view()))))return false;
        double nearRadius=PredictionDetailBands.fineRadius(before.layout().maxDistanceBlocks(),before.residents().dimension());
        for(var key:result.reads()) {
            double oldDistance=PredictionWorkOrder.distanceSquared(key,before.layout(),before.x(),before.z());
            double newDistance=PredictionWorkOrder.distanceSquared(key,after.layout(),after.x(),after.z());
            if(before.residents().tiles().get(key)!=after.residents().tiles().get(key)
                    || (oldDistance<=nearRadius*nearRadius)!=(newDistance<=nearRadius*nearRadius)
                    || (oldDistance<=(double)request.surfaceRadius()*request.surfaceRadius())
                        !=(newDistance<=(double)live.surfaceRadius()*live.surfaceRadius())
                    || !Objects.equals(request.captureEpochs().get(key),live.captureEpochs().get(key))
                    || !Objects.equals(before.residents().epochs().get(key),after.residents().epochs().get(key))
                    || before.dirty().contains(key)!=after.dirty().contains(key)
                    || before.surfaceReady().contains(key)!=after.surfaceReady().contains(key)
                    || before.surfaceDesired().contains(key)!=after.surfaceDesired().contains(key)
                    || before.fallback().contains(key)!=after.fallback().contains(key)
                    || before.dependencies().contains(key)!=after.dependencies().contains(key)
                    || before.band(key)!=after.band(key)
                    || before.retry(key)!=after.retry(key)
                    || !Objects.equals(before.ordinary().get(key),after.ordinary().get(key))
                    || !Objects.equals(before.terrain().get(key),after.terrain().get(key))
                    || !Objects.equals(before.transitions().get(key),after.transitions().get(key)))return false;
        }
        return true;
    }

    /** A recent observation may be shown during ordinary movement/churn. It
     * never proves current readiness or publishes scheduling flags/goals. */
    static boolean loadingDisplay(PredictionLoadingPreparation.Input request, Current current,
                                  List<PredictionTileKey> plan, long now) {
        var input = request.scheduling();
        return schedule(input, current, plan) && request.settings() == current.settings()
                && now >= input.now() && now - input.now() <= 1_000_000_000L
                && Math.hypot(input.x() - current.x(), input.z() - current.z()) <= 128
                && Math.abs(input.y() - current.y()) <= 128;
    }
}
