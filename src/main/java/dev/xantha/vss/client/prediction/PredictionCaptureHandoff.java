package dev.xantha.vss.client.prediction;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Manager-owned input barrier. Readers at build checkpoints only query the concurrent tile index. */
final class PredictionCaptureHandoff {
    private record Waiting(long timestamp, Set<PredictionTileKey> dependencies) { }
    private final Map<Long, Waiting> columns = new HashMap<>();
    private final Map<PredictionTileKey, Integer> tiles = new ConcurrentHashMap<>();

    boolean await(long column, long timestamp, Collection<PredictionTileKey> dependencies) {
        Waiting previous = columns.get(column);
        if (previous != null && previous.timestamp >= timestamp) return false;
        if (previous == null) {
            var keys = Set.copyOf(dependencies);
            keys.forEach(key -> tiles.merge(key, 1, Integer::sum));
            columns.put(column, new Waiting(timestamp, keys));
        } else columns.put(column, new Waiting(timestamp, previous.dependencies));
        return true;
    }

    boolean awaiting(long column) { return columns.containsKey(column); }
    boolean accepts(long column, long timestamp) {
        Waiting waiting = columns.get(column);
        return waiting == null || timestamp >= waiting.timestamp;
    }
    boolean committed(long column, long timestamp) {
        Waiting waiting = columns.get(column);
        if (waiting == null || timestamp < waiting.timestamp) return false;
        return retired(column);
    }
    boolean retired(long column) {
        Waiting waiting = columns.remove(column);
        if (waiting == null) return false;
        waiting.dependencies.forEach(key -> tiles.compute(key, (ignored, count) -> count == 1 ? null : count - 1));
        return true;
    }
    boolean blocked(PredictionTileKey key) { return tiles.containsKey(key); }

    void reindex(LongFunction<Set<PredictionTileKey>> dependencies) {
        tiles.clear();
        columns.replaceAll((column, waiting) -> {
            Set<PredictionTileKey> keys = dependencies.apply(column);
            keys.forEach(key -> tiles.merge(key, 1, Integer::sum));
            return new Waiting(waiting.timestamp, keys);
        });
    }
    String diagnostics() { return "captureHandoff={columns=" + columns.size() + ",tiles=" + tiles.size() + "}"; }
    void clear() { columns.clear(); tiles.clear(); }
}
