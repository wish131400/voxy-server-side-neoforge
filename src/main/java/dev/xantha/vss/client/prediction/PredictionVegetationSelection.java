package dev.xantha.vss.client.prediction;

import dev.xantha.vss.config.PredictionVegetationDensity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Display-only selection from complete placement maps, before tile clipping or voxel reduction. */
final class PredictionVegetationSelection {
    private PredictionVegetationSelection() { }

    static Map<BlockPos, BlockState> select(Map<BlockPos, BlockState> original,
                                            PredictionVegetationDensity density) {
        if (density == PredictionVegetationDensity.HIGH || original.isEmpty()) return original;
        var selected = new HashMap<BlockPos, BlockState>();
        var visited = new LongOpenHashSet();
        var component = new ArrayList<BlockPos>();
        var probe = new BlockPos.MutableBlockPos();
        for (var entry : original.entrySet()) {
            var pos = entry.getKey();
            var state = entry.getValue();
            var traits = PredictionVegetationTraits.of(state);
            if (!traits.woody()) {
                // All vertical segments of a plant share its X/Z decision.
                // Terrain edits, fluids, weather and structural blocks stay intact.
                if (!traits.vegetation() || density.keep(pos.getX(), pos.getZ())) selected.put(pos, state);
                continue;
            }
            if (!visited.add(pos.asLong())) continue;
            component.clear();
            component.add(pos);
            BlockPos root = null;
            boolean canopy = false, structural = false;
            for (int cursor = 0; cursor < component.size(); cursor++) {
                var current = component.get(cursor);
                var value = original.get(current);
                if (stem(value)) {
                    if (root == null || rootOrder(current, root) < 0) root = current;
                } else canopy = true;
                // Diagonally touching branches/leaves belong to one complete display group.
                // Looking at the full source map keeps clipped canopy pieces on the same choice.
                for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    probe.set(current.getX() + dx, current.getY() + dy, current.getZ() + dz);
                    var neighbour = original.get(probe);
                    if (neighbour == null) continue;
                    if (PredictionVegetationTraits.of(neighbour).woody()) {
                        if (visited.add(probe.asLong())) component.add(probe.immutable());
                    } else if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1
                            && (neighbour.is(BlockTags.PLANKS) || neighbour.is(BlockTags.STAIRS)
                            || neighbour.is(BlockTags.SLABS) || neighbour.is(BlockTags.DOORS)
                            || neighbour.is(BlockTags.FENCES))) structural = true;
                }
            }
            // Wood without a canopy and wood joined to building parts are not proven trees.
            if (root == null || !canopy || structural || density.keep(root.getX(), root.getZ())) {
                for (var block : component) selected.put(block, original.get(block));
            }
        }
        return java.util.Collections.unmodifiableMap(selected);
    }

    private static boolean stem(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.CRIMSON_STEM) || state.is(Blocks.WARPED_STEM);
    }

    private static int rootOrder(BlockPos first, BlockPos second) {
        int result = Integer.compare(first.getY(), second.getY());
        if (result == 0) result = Integer.compare(first.getX(), second.getX());
        return result == 0 ? Integer.compare(first.getZ(), second.getZ()) : result;
    }

    static PredictionSimpleVegetation.Result select(PredictionSimpleVegetation.Result original,
            int baseX, int baseZ, PredictionVegetationDensity density) {
        if (density == PredictionVegetationDensity.HIGH || original.forms().isEmpty()) return original;
        var forms = new ArrayList<PredictionSimpleVegetation.Form>();
        int maxY = Integer.MIN_VALUE;
        for (var form : original.forms()) {
            if (!density.keep(baseX + form.x(), baseZ + form.z())) continue;
            forms.add(form);
            maxY = Math.max(maxY, form.y() + form.height());
        }
        return new PredictionSimpleVegetation.Result(List.copyOf(forms), original.forestTints(), maxY);
    }
}
