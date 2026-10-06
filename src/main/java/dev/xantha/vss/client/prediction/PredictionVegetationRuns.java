package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Merge only adjacent exposed faces. Air gaps and one-block canopy outlines stay intact. */
final class PredictionVegetationRuns {
    record Face(int x, int z, int bottom, int top, int direction, BlockState state) { }

    private PredictionVegetationRuns() { }

    static List<Face> faces(PredictionVegetation.Tile tile, int cell, IntBinaryOperator floor) {
        return faces(tile, cell, floor, false);
    }

    static List<Face> faces(PredictionVegetation.Tile tile, int cell, IntBinaryOperator floor, boolean bottoms) {
        var voxels = tile.cell(cell);
        if (voxels.isEmpty()) return List.of();
        List<Face> faces = new ArrayList<>();
        var probe = new BlockPos.MutableBlockPos();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var voxel : voxels) {
            minX = Math.min(minX, voxel.x()); maxX = Math.max(maxX, voxel.x());
            minZ = Math.min(minZ, voxel.z()); maxZ = Math.max(maxZ, voxel.z());
        }
        int width = maxX - minX + 1;
        // Each cell contains a small, bounded horizontal grid. Track the last
        // side directly by column and direction, without hashing face keys or
        // querying the same vertical neighbours again to discover a run.
        int[] last = new int[width * (maxZ - minZ + 1) * 4];
        for (var voxel : voxels) {
            if (!PredictionVegetation.mergeable(voxel.state(), voxel.size())) continue;
            int x = voxel.x(), z = voxel.z(), y = voxel.y();
            int bottom = floor.applyAsInt(x, z);
            if (y < bottom) continue;
            if (bottoms && !tile.occupied(x, y - 1, z, probe))
                faces.add(new Face(x, z, y, y + 1, 5, voxel.state()));
            if (!tile.occupied(x, y + 1, z, probe))
                faces.add(new Face(x, z, y, y + 1, 0, voxel.state()));
            int column = ((z - minZ) * width + x - minX) * 4;
            for (int direction = 1; direction <= 4; direction++) {
                int dx = direction == 3 ? -1 : direction == 4 ? 1 : 0;
                int dz = direction == 1 ? -1 : direction == 2 ? 1 : 0;
                if (tile.occupied(x + dx, y, z + dz, probe)) continue;
                int slot = column + direction - 1, index = last[slot] - 1;
                if (index >= 0) {
                    Face previous = faces.get(index);
                    if (previous.top() == y && previous.state() == voxel.state()) {
                        faces.set(index, new Face(x, z, previous.bottom(), y + 1, direction, voxel.state()));
                        continue;
                    }
                }
                last[slot] = faces.size() + 1;
                faces.add(new Face(x, z, y, y + 1, direction, voxel.state()));
            }
        }
        return faces;
    }
}
