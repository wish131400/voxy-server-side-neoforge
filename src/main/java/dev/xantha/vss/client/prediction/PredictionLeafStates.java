package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Settle supported leaves without running world ticks or querying ungenerated chunks. */
final class PredictionLeafStates {
    private PredictionLeafStates() { }

    static Map<BlockPos, BlockState> settle(Map<BlockPos, BlockState> source) {
        List<List<BlockPos>> distances = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) distances.add(new ArrayList<>());
        source.forEach((pos, state) -> {
            if (state.is(BlockTags.LOGS)) distances.get(0).add(pos);
            else if (leaf(state)) {
                int distance = state.getValue(LeavesBlock.DISTANCE);
                if (distance < 7) distances.get(distance).add(pos);
            }
        });
        Map<BlockPos, BlockState> result = null;
        Direction[] directions = Direction.values();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int distance = 1; distance < 7; distance++) {
            for (BlockPos pos : distances.get(distance - 1)) {
                for (Direction direction : directions) {
                    cursor.setWithOffset(pos, direction);
                    BlockState state = (result == null ? source : result).get(cursor);
                    if (!leaf(state) || state.getValue(LeavesBlock.DISTANCE) <= distance) continue;
                    if (result == null) result = new HashMap<>(source);
                    BlockPos target = cursor.immutable();
                    result.put(target, state.setValue(LeavesBlock.DISTANCE, distance));
                    distances.get(distance).add(target);
                }
            }
        }
        // Existing supported states also seed the boundary: absent neighbouring
        // chunks are unknown, not proof of decay. Preserve persistence, species,
        // waterlogging and genuinely unsupported leaves at distance seven.
        return result == null ? source : Map.copyOf(result);
    }

    private static boolean leaf(BlockState state) {
        return state != null && state.getBlock() instanceof LeavesBlock && state.hasProperty(LeavesBlock.DISTANCE);
    }
}
