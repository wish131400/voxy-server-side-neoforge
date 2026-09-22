package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Mesh-build-only consolidation; never changes snow coverage or height. */
final class PredictionSnowSurfaces {
    record Roof(int x, int z, float y, int width, int depth, BlockState state) { }

    static float height(BlockState state) {
        return state != null && state.is(Blocks.SNOW) ? state.getValue(SnowLayerBlock.LAYERS) / 8F : 0;
    }

    static List<Roof> roofs(PredictionVegetation.Tile tile, int cell,
            java.util.function.IntBinaryOperator floor) {
        // Restrict merges to one coverage cell. Handoff can then discard the
        // whole rectangle without erasing snow in an adjacent uncovered cell.
        Set<BlockPos> pending = null;
        for (var voxel : tile.cell(cell)) {
            if (PredictionVegetation.mergeable(voxel.state(), voxel.size())) continue;
            if (height(voxel.state()) == 0 || voxel.y() + height(voxel.state()) <= floor.applyAsInt(voxel.x(), voxel.z())) continue;
            if (height(voxel.state()) == 1 && tile.occupied(voxel.x(), voxel.y() + 1, voxel.z())) continue;
            if (pending == null) pending = new HashSet<>();
            pending.add(new BlockPos(voxel.x(), voxel.y(), voxel.z()));
        }
        if (pending == null) return List.of();
        List<Roof> result = new ArrayList<>();
        for (var voxel : tile.cell(cell)) {
            if (height(voxel.state()) == 0) continue;
            BlockPos p = new BlockPos(voxel.x(), voxel.y(), voxel.z());
            if (!pending.remove(p)) continue;
            BlockState state = voxel.state();
            int width = 1, depth = 1;
            while (matches(tile, pending, p.offset(width, 0, 0), state)) width++;
            rows: while (true) {
                for (int x = 0; x < width; x++) if (!matches(tile, pending, p.offset(x, 0, depth), state)) break rows;
                depth++;
            }
            for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) pending.remove(p.offset(x, 0, z));
            result.add(new Roof(p.getX(), p.getZ(), p.getY() + height(state), width, depth, state));
        }
        return result;
    }

    private static boolean matches(PredictionVegetation.Tile tile, Set<BlockPos> pending, BlockPos p, BlockState state) {
        return pending.contains(p) && state.equals(tile.blocks().get(p.offset(tile.baseX(), 0, tile.baseZ())));
    }

    static float sideBottom(PredictionVegetation.Tile tile, int x, int y, int z, float bottom) {
        return Math.max(bottom, y + height(tile.blocks().get(new BlockPos(tile.baseX() + x, y, tile.baseZ() + z))));
    }

    private PredictionSnowSurfaces() { }
}
