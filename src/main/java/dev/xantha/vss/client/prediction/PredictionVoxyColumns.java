package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Column;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Run;

/** Lossless tile-local palettes. Fine flat columns share immutable runs and lists. */
final class PredictionVoxyColumns {
    private final Map<Run, Run> runs = new HashMap<>();
    private final Map<Column, Column> columns = new HashMap<>();
    private long listEntries;
    Column column(List<Run> input, String biome) {
        var shared = new ArrayList<Run>(input.size());
        for (var run : input) shared.add(runs.computeIfAbsent(run, k -> k));
        var candidate = new Column(List.copyOf(shared), biome);
        var previous = columns.putIfAbsent(candidate, candidate);
        if (previous != null) return previous;
        listEntries += shared.size();
        return candidate;
    }
    long bytes(int cells) { return 256L + cells * 8L + columns.size() * 64L + listEntries * 8L + runs.size() * 40L; }
}
