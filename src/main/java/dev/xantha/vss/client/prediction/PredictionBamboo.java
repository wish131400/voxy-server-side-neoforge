package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Display repair for repeated placement above a completed bamboo stalk. */
final class PredictionBamboo {
    private PredictionBamboo() { }

    static Map<BlockPos, BlockState> normalize(Map<BlockPos, BlockState> source) {
        Map<BlockPos, BlockState> result = null;
        for (var entry : source.entrySet()) {
            if (!entry.getValue().is(Blocks.BAMBOO)) continue;
            BlockPos base = entry.getKey();
            BlockState below = source.get(base.below());
            if (below != null && below.is(Blocks.BAMBOO)) continue;
            // Walk once from each connected stalk's base. Keep its original
            // terminal leaves and height, rather than imposing an arbitrary
            // cap on a resource pack/mod's legitimately tall bamboo.
            boolean complete = false;
            BlockPos.MutableBlockPos pos = base.mutable();
            for (BlockState state = source.get(pos); state != null && state.is(Blocks.BAMBOO);
                    state = source.get(pos.move(0, 1, 0))) {
                if (complete) {
                    if (result == null) result = new HashMap<>(source);
                    result.remove(pos);
                } else if (state.getValue(BambooStalkBlock.STAGE) == 1) {
                    complete = true;
                }
            }
        }
        return result == null ? source : Map.copyOf(result);
    }
}
