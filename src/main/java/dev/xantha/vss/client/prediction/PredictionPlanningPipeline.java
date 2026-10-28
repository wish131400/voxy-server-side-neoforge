package dev.xantha.vss.client.prediction;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 一个维度的预测规划通道：只拥有后台准备任务及其统计，不拥有需求、瓦片或 GPU 状态。
 * <p>客户端线程提交、轮询和失效；计算沿用共享的低优先级执行器。每条通道最多保留
 * 一个任务，失效后仍等待旧任务退出才允许重用槽位，避免连续传送把队列堆满。
 * 结果须经调用方提供的当前版本校验后才计为交接成功，状态更新仍由 TileManager 完成。
 */
final class PredictionPlanningPipeline {
    final Lane<PredictionPlanPreparation.Input, PredictionPlanPreparation.Result> plan;
    final Lane<PredictionDemandPreparation.Input, PredictionDemandPreparation.Result> view;
    final Lane<PredictionSchedulePreparation.Input, PredictionSchedulePreparation.Result> schedule;
    final Lane<PredictionLoadingPreparation.Input, PredictionLoadingPreparation.Result> loading;
    final Lane<PredictionVoxyWorkPreparation.Input, PredictionVoxyWorkPreparation.Result> details;
    final PredictionPlanPreparation.Calculation planCalculation = new PredictionPlanPreparation.Calculation();
    final PredictionDemandPreparation viewCalculation = new PredictionDemandPreparation();

    PredictionPlanningPipeline(BiFunction<ResourceKey<Level>, Long, PredictionExactCoverageIndex.View> coverage) {
        this(PredictionClientPreparation.WORKER, coverage);
    }

    PredictionPlanningPipeline(Executor executor,
            BiFunction<ResourceKey<Level>, Long, PredictionExactCoverageIndex.View> coverage) {
        Objects.requireNonNull(coverage, "coverage");
        // 稀疏覆盖快照仍在后台获取。算法只接收快照，不查询全局客户端或 Voxy 活节点。
        plan = new Lane<>(executor, input -> planCalculation.build(input,
                coverage.apply(input.dimension(), input.now())));
        view = new Lane<>(executor, viewCalculation::build);
        schedule = new Lane<>(executor, PredictionSchedulePreparation::build);
        loading = new Lane<>(executor, PredictionLoadingPreparation::build);
        details = new Lane<>(executor, PredictionVoxyWorkPreparation::build);
    }

    /** 传送、暂停及关闭共用一个失效入口；不撤销已经发布的居民瓦片。 */
    void invalidate() {
        plan.clear();
        view.clear();
        schedule.clear();
        loading.clear();
        details.clear();
    }

    /** 单一异步状态机的规划适配；统计与任务槽位都仅在客户端线程更新。 */
    static final class Lane<I, O> {
        private final PredictionClientPreparation<I, O> preparation;
        private long jobs;
        private long commits;
        private long discards;

        Lane(Executor executor, Function<I, O> build) {
            preparation = new PredictionClientPreparation<>(executor, build);
        }

        boolean pending() { return preparation.pending(); }

        boolean request(I input) {
            if (!preparation.request(input)) return false;
            jobs++;
            return true;
        }

        /** 不等待工作线程。校验必须无副作用，拒绝结果不得改变已发布状态。 */
        O poll(Predicate<O> current) {
            O result = preparation.poll();
            if (result == null) return null;
            if (!current.test(result)) {
                discards++;
                return null;
            }
            commits++;
            return result;
        }

        void clear() { preparation.clear(); }
        long jobs() { return jobs; }
        long commits() { return commits; }
        long discards() { return discards; }
        long preparationNanos() { return preparation.preparationNanos(); }
    }
}
