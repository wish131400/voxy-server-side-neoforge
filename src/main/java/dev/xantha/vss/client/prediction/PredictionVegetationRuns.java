package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.level.block.state.BlockState;

/** Merge only adjacent exposed faces. Air gaps and one-block canopy outlines stay intact. */
final class PredictionVegetationRuns {
    record Face(int x, int z, int bottom, int top, int direction, BlockState state) { }
    private record Key(int x, int z, int direction, BlockState state) { }

    private PredictionVegetationRuns() { }

    static List<Face> faces(PredictionVegetation.Tile tile, int cell, IntBinaryOperator floor) {
        List<Face> faces = new ArrayList<>();
        Map<Key, Integer> last = new HashMap<>();
        // Tile cells are sorted by Y, so a run can only grow into its immediate next block.
        for (var voxel : tile.cell(cell)) {
            if (voxel.size() != 1 || !PredictionVegetation.woody(voxel.state())) continue;
            int x = voxel.x(), z = voxel.z(), y = voxel.y();
            if (y < floor.applyAsInt(x, z)) continue;
            if (!tile.occupied(x, y + 1, z))
                faces.add(new Face(x, z, y, y + 1, 0, voxel.state()));
            for (int direction = 1; direction <= 4; direction++) {
                int dx = direction == 3 ? -1 : direction == 4 ? 1 : 0;
                int dz = direction == 1 ? -1 : direction == 2 ? 1 : 0;
                if (tile.occupied(x + dx, y, z + dz)) continue;
                var key = new Key(x, z, direction, voxel.state());
                Integer index = last.get(key);
                if (index != null && faces.get(index).top() == y) {
                    Face old = faces.get(index);
                    faces.set(index, new Face(x, z, old.bottom(), y + 1, direction, voxel.state()));
                } else {
                    last.put(key, faces.size());
                    faces.add(new Face(x, z, y, y + 1, direction, voxel.state()));
                }
            }
        }
        return faces;
    }
}
