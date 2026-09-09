package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;

/** Local terrain cuts underneath actual surface placements; no live-world or mask access. */
final class PredictionSurfaceEdits {
    private final Map<Long, Integer> floors = new HashMap<>();
    private final boolean[] affected;

    PredictionSurfaceEdits(PredictionVegetation.Tile tile, int axis, int step, int[] heights) {
        affected = new boolean[axis * axis];
        if (tile == null) return;
        tile.blocks().forEach((pos, state) -> {
            int x = pos.getX() - tile.baseX(), z = pos.getZ() - tile.baseZ();
            // Only exact footprints can replace terrain. Coarse tree cells are visual proxies.
            if (PredictionVegetation.voxelSize(state, tile.voxelSize()) != 1) return;
            int cx = Math.floorDiv(x, step), cz = Math.floorDiv(z, step);
            if (cx < -1 || cz < -1 || cx > axis || cz > axis) return;
            int height = heights[Math.clamp(cz, 0, axis) * (axis + 1) + Math.clamp(cx, 0, axis)];
            if (pos.getY() >= height) return;
            floors.merge(key(x, z), pos.getY(), Math::min);
            // Neighbours also subdivide so their inward walls join each cut at block resolution.
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int nx = cx + dx, nz = cz + dz;
                if (nx >= 0 && nz >= 0 && nx < axis && nz < axis) affected[nz * axis + nx] = true;
            }
        });
    }

    boolean affects(int cell) { return affected[cell]; }
    int floor(int x, int z, int original) { return Math.min(original, floors.getOrDefault(key(x, z), original)); }
    private static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
}
