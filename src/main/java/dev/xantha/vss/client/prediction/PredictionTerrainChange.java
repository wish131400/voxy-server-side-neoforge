package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Column;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Run;

/** Exact input differences for grids with unchanged ownership and decoration. */
final class PredictionTerrainChange {
    private int bottom = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
    private final java.util.IdentityHashMap<Column, java.util.Set<Column>> compared = new java.util.IdentityHashMap<>();

    void compare(Column before, Column after) {
        if (before.equals(after)) return;
        if (!compared.computeIfAbsent(before, ignored -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())).add(after)) return;
        var a = before.runs(); var b = after.runs();
        // Overlapping/out-of-order runs can overwrite each other differently
        // after coarse rounding. Their complete ranges remain authoritative.
        if (!ordered(a) || !ordered(b)) {
            for (var runs : List.of(a, b)) for (Run run : runs) include(run.bottom(), run.top());
            return;
        }
        int[] edges = new int[(a.size() + b.size()) * 2]; int count = 0;
        for (var runs : List.of(a, b)) for (Run run : runs) {
            edges[count++] = run.bottom(); edges[count++] = run.top();
        }
        Arrays.sort(edges);
        for (int i = 1; i < count; i++) {
            int lo = edges[i-1], hi = edges[i];
            if (lo == hi) continue;
            BlockState old = at(a, lo), next = at(b, lo);
            if (!java.util.Objects.equals(old, next)
                    || old != null && !before.biome().equals(after.biome())) include(lo, hi);
        }
        // Coarse fluid voxels also encode the absolute surface remainder.
        // Splitting a liquid run can change that marker without changing occupancy.
        for (var runs : List.of(a, b)) for (Run run : runs)
            if (run.top() > run.bottom() && run.state().getBlock() instanceof LiquidBlock)
                include(run.top()-1, run.top());
    }

    private static BlockState at(List<Run> runs, int y) {
        BlockState state = null;
        // Match rasterization order, including explicit overwrites by air.
        for (Run run : runs) if (y >= run.bottom() && y < run.top())
            state = run.state().isAir() ? null : run.state();
        return state;
    }
    private static boolean ordered(List<Run> runs) {
        int end = Integer.MIN_VALUE;
        for (Run run : runs) {
            if (run.top() <= run.bottom() || run.bottom() < end || run.state().isAir()) return false;
            end = run.top();
        }
        return true;
    }
    private void include(int lo, int hi) { bottom = Math.min(bottom, lo); top = Math.max(top, hi); }
    int bottom() { return bottom; }
    int top() { return top; }
}
