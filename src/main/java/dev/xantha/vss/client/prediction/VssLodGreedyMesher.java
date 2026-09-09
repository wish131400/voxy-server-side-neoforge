package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.List;

/**
 * Small allocation-free-in-the-hot-loop rectangle merger matching the
 * LodGreedyMesher contract. Mesh builders can use the returned rectangles to
 * collapse equal material cells before emitting triangles.
 */
public final class VssLodGreedyMesher {
    private VssLodGreedyMesher() {
    }

    public static List<Rectangle> merge(int[] cells, int size) {
        return merge(cells, size, size, size);
    }

    /**
     * Merges equal cells without allowing one representative material to
     * cover an unbounded screen-sized slab. The cap is expressed in cells so
     * callers can choose a stable world-space footprint for each LOD spacing.
     */
    public static List<Rectangle> merge(int[] cells, int size, int maxWidth, int maxHeight) {
        if (cells == null || size <= 0 || cells.length != size * size) {
            throw new IllegalArgumentException("cells must be a square grid");
        }
        if (maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException("merge bounds must be positive");
        }
        boolean[] used = new boolean[cells.length];
        List<Rectangle> result = new ArrayList<>();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = z * size + x;
                if (used[index]) continue;
                int width = 1;
                while (width < maxWidth && x + width < size && !used[index + width]
                        && cells[index + width] == cells[index]) width++;
                int height = 1;
                while (height < maxHeight && z + height < size && rowMatches(cells, used, size,
                        x, z + height, width, cells[index])) height++;
                for (int dz = 0; dz < height; dz++) {
                    for (int dx = 0; dx < width; dx++) {
                        used[(z + dz) * size + x + dx] = true;
                    }
                }
                result.add(new Rectangle(x, z, width, height, cells[index]));
            }
        }
        return List.copyOf(result);
    }

    private static boolean rowMatches(int[] cells, boolean[] used, int size,
                                      int x, int z, int width, int value) {
        int row = z * size + x;
        for (int dx = 0; dx < width; dx++) {
            if (used[row + dx] || cells[row + dx] != value) return false;
        }
        return true;
    }

    public record Rectangle(int x, int z, int width, int height, int material) {
    }
}
