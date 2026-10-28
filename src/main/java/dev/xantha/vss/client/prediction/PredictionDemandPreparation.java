package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/**
 * 纯视角需求计算：按安全快照分类并补齐父级与相邻覆盖依赖，不管理任务或发布状态。
 * <p>仅由串行规划工作线程调用。路径缓存只取决于目标集合和层级数量；相机变化可复用，
 * 返回集合均不可变。异步任务、失效和错误处理统一由 PredictionClientPreparation 承担。
 */
final class PredictionDemandPreparation {
    record Input(VssLodLayout layout, PredictionWorkView view, VssLodFocus focus,
                 Set<PredictionTileKey> desired, Map<PredictionTileKey, PredictionTile> tiles,
                 Map<PredictionTileKey, Integer> ordinary, Map<PredictionTileKey, Integer> terrain,
                 Set<PredictionTileKey> surfaceDesired, Set<PredictionTileKey> surfaceReady,
                 Map<PredictionTileKey, Long> recent, double minY, double maxY, long now) { }
    record Result(Input input, Map<PredictionTileKey, Integer> bands,
                  Set<PredictionTileKey> dependencies, Map<PredictionTileKey, List<PredictionTileKey>> paths,
                  Map<PredictionTileKey, Long> recent) { }
    // Only the shared serial planning worker mutates this cache. Published paths are immutable.
    private Map<PredictionTileKey, List<PredictionTileKey>> paths = Map.of();
    private int pathLevels;
    private static final int[][] NEIGHBOURS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    Result build(Input input) {
        if (pathLevels != input.layout().levelCount() || !paths.keySet().equals(input.desired())) {
            var next = new HashMap<PredictionTileKey, List<PredictionTileKey>>();
            for (var key : input.desired()) {
                var dependencies = new ArrayList<PredictionTileKey>();
                for (var parent = key; parent.lod() < input.layout().levelCount(); parent = new PredictionTileKey(
                        key.dimension(), parent.tileX() >> 1, parent.tileZ() >> 1, parent.lod() + 1))
                    if (input.desired().contains(parent)) dependencies.add(parent);
                for (int[] direction : NEIGHBOURS) {
                    var neighbour = new PredictionTileKey(key.dimension(), key.tileX() + direction[0], key.tileZ() + direction[1], key.lod());
                    for (; neighbour.lod() < input.layout().levelCount(); neighbour = new PredictionTileKey(
                            key.dimension(), neighbour.tileX() >> 1, neighbour.tileZ() >> 1, neighbour.lod() + 1))
                        if (input.desired().contains(neighbour)) { dependencies.add(neighbour); break; }
                }
                next.put(key, List.copyOf(dependencies));
            }
            paths = Map.copyOf(next); pathLevels = input.layout().levelCount();
        }
        var bands = new HashMap<PredictionTileKey, Integer>();
        var dependencies = new HashSet<PredictionTileKey>();
        var recent = new HashMap<>(input.recent());
        recent.entrySet().removeIf(e -> !input.desired().contains(e.getKey()) || input.now() - e.getValue() > 1_000_000_000L);
        for (var key : input.desired()) {
            double minY = input.minY(), maxY = input.maxY();
            var tile = input.tiles().get(key);
            if (tile != null && tile.data() != null && tile.cellAxis() > 8
                    && tile.cellAxis() >= Math.max(input.ordinary().getOrDefault(key, tile.cellAxis()),
                            input.terrain().getOrDefault(key, tile.cellAxis()))
                    && (!input.surfaceDesired().contains(key) || input.surfaceReady().contains(key))) {
                double margin = Math.max(32, tile.spacingBlocks() * 2D);
                minY = Math.min(tile.depthBound().minY(), Math.max(minY, tile.depthBound().minY() - margin));
                maxY = tile.depthBound().maxY() + margin + 64;
            }
            int band = input.view() == null || key.lod() < 0 || key.lod() >= input.layout().levelCount()
                    ? PredictionWorkView.VISIBLE : input.view().band(key, input.layout(), input.focus(), minY, maxY);
            if (band == PredictionWorkView.VISIBLE) recent.put(key, input.now());
            else if (band == PredictionWorkView.BACKGROUND && recent.containsKey(key)) band = PredictionWorkView.RECENT;
            bands.put(key, band);
            if (band <= PredictionWorkView.BUFFER) dependencies.addAll(paths.get(key));
        }
        return new Result(input, Map.copyOf(bands), Set.copyOf(dependencies), paths, Map.copyOf(recent));
    }
}
