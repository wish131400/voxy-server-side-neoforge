package dev.xantha.vss.networking.server.compat;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.util.*;

/** Greedy rectangles of the outer envelope, excluding rooms and underground detail. */
final class LostCityExterior {
    private LostCityExterior() { }

    static LostCityPreview.Model box(int x, int z, int width, int depth, int height, int state) {
        List<Integer> words = new ArrayList<>(18);
        add(words, x, height, z, width, 0, depth, 0, state);
        add(words, x, 0, z, width, 0, depth, 5, state);
        add(words, x, 0, z, width, height, 0, 1, state);
        add(words, x, 0, z + depth, width, height, 0, 2, state);
        add(words, x, 0, z, 0, height, depth, 3, state);
        add(words, x + width, 0, z, 0, height, depth, 4, state);
        return new LostCityPreview.Model(words.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Public spaces need every exposed hedge/prop face, including faces inside a hollow border. */
    static LostCityPreview.Model surface(int[] states, int height, int spacing) {
        return surface(states, height, spacing, LostCityPreview.MAX_MODEL_QUADS);
    }

    static LostCityPreview.Model surface(int[] states, int height, int spacing, int budget) {
        return surface(states, height, spacing, budget, false);
    }

    /** Detached structures need bottom faces and chunk-edge walls even at their lowest layer. */
    static LostCityPreview.Model infrastructure(int[] states, int height, int spacing, int budget) {
        return surface(states, height, spacing, budget, true);
    }

    private static LostCityPreview.Model surface(int[] states, int height, int spacing, int budget, boolean detached) {
        int side = 16 / spacing;
        int[] cells = new int[side * side * height];
        for (int y = 0; y < height; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++)
            cells[(y * side + z) * side + x] = representative(states, height, x, y, z, spacing);
        List<Integer> words = new ArrayList<>();
        for (int d = 1; d <= 4; d++) for (int plane = 0; plane <= side; plane++) {
            int[] mask = new int[side * height]; Arrays.fill(mask, -1);
            for (int y = 0; y < height; y++) for (int u = 0; u < side; u++) {
                int pos = plane - (d == 2 || d == 4 ? 1 : 0);
                int x = d <= 2 ? u : pos, z = d <= 2 ? pos : u;
                int state = at(cells, side, height, x, y, z);
                int nx = x + (d == 3 ? -1 : d == 4 ? 1 : 0);
                int nz = z + (d == 1 ? -1 : d == 2 ? 1 : 0);
                // The terrain owns foundation walls at chunk edges. Keeping
                // them here too would create coplanar faces at city seams.
                if (state < 0 || !detached && y == 0 && (plane == 0 || plane == side)
                        || at(cells, side, height, nx, y, nz) >= 0) continue;
                mask[y * side + u] = state;
            }
            final int direction = d, p = plane;
            rectangles(mask, side, height, (u, y, du, dy, state) ->
                    add(words, direction <= 2 ? u * spacing : p * spacing, y,
                            direction <= 2 ? p * spacing : u * spacing,
                            direction <= 2 ? du * spacing : 0, dy,
                            direction <= 2 ? 0 : du * spacing, direction, state));
        }
        for (int y = 0; y < height; y++) {
            int[] mask = new int[side * side]; Arrays.fill(mask, -1);
            for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
                int state = at(cells, side, height, x, y, z);
                if (state >= 0 && at(cells, side, height, x, y + 1, z) < 0) mask[z * side + x] = state;
            }
            final int top = y + 1;
            rectangles(mask, side, side, (x, z, dx, dz, state) ->
                    add(words, x * spacing, top, z * spacing, dx * spacing, 0, dz * spacing, 0, state));
        }
        if (detached) for (int y = 0; y < height; y++) {
            int[] mask = new int[side * side]; Arrays.fill(mask, -1);
            for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
                int state = at(cells, side, height, x, y, z);
                if (state >= 0 && at(cells, side, height, x, y - 1, z) < 0) mask[z * side + x] = state;
            }
            final int bottom = y;
            rectangles(mask, side, side, (x, z, dx, dz, state) ->
                    add(words, x * spacing, bottom, z * spacing, dx * spacing, 0, dz * spacing, 5, state));
        }
        if (words.size() / 3 > budget && spacing < 16) return surface(states, height, spacing * 2, budget, detached);
        return new LostCityPreview.Model(words.stream().mapToInt(Integer::intValue).toArray());
    }

    private static int at(int[] cells, int side, int height, int x, int y, int z) {
        return x < 0 || z < 0 || y < 0 || x >= side || z >= side || y >= height
                ? -1 : cells[(y * side + z) * side + x];
    }

    static LostCityPreview.Model mesh(int[] states, int height, int spacing) {
        int side = 16 / spacing;
        int[] cells = new int[side * side * height];
        for (int y = 0; y < height; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++)
            cells[(y * side + z) * side + x] = representative(states, height, x, y, z, spacing);
        List<Integer> words = new ArrayList<>();
        for (int d = 1; d <= 4; d++) {
            int[][] masks = new int[side + 1][side * height];
            for (int[] mask : masks) Arrays.fill(mask, -1);
            for (int y = 0; y < height; y++) for (int u = 0; u < side; u++) {
                for (int v = 0; v < side; v++) {
                    int pos = d == 2 || d == 4 ? side - v - 1 : v;
                    int x = d <= 2 ? u : pos, z = d <= 2 ? pos : u;
                    int state = cells[(y * side + z) * side + x];
                    if (state < 0) continue;
                    masks[pos + (d == 2 || d == 4 ? 1 : 0)][y * side + u] = state;
                    break;
                }
            }
            for (int plane = 0; plane <= side; plane++) {
                final int direction = d, p = plane;
                rectangles(masks[plane], side, height, (u, y, du, dy, state) -> {
                    int x = direction <= 2 ? u * spacing : p * spacing;
                    int z = direction <= 2 ? p * spacing : u * spacing;
                    add(words, x, y, z, direction <= 2 ? du * spacing : 0, dy,
                            direction <= 2 ? 0 : du * spacing, direction, state);
                });
            }
        }
        // One uppermost surface per column. Interior floor/ceiling geometry is never replayed.
        int[][] roofs = new int[height + 1][side * side];
        for (int[] mask : roofs) Arrays.fill(mask, -1);
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
            for (int y = height - 1; y >= 0; y--) {
                int state = cells[(y * side + z) * side + x];
                if (state < 0) continue;
                roofs[y + 1][z * side + x] = state;
                break;
            }
        }
        for (int plane = 1; plane <= height; plane++) {
            final int py = plane;
            rectangles(roofs[plane], side, side, (x, z, dx, dz, state) ->
                    add(words, x * spacing, py, z * spacing, dx * spacing, 0, dz * spacing, 0, state));
        }
        int[] result = words.stream().mapToInt(Integer::intValue).toArray();
        if (result.length / 3 > LostCityPreview.MAX_MODEL_QUADS && spacing < 4) return mesh(states, height, spacing * 2);
        return new LostCityPreview.Model(result);
    }

    /** Four-block columns retain stepped footprints/roof heights at a small distant draw cost. */
    static LostCityPreview.Model silhouette(List<LostCityPreview.Placement> floors) {
        int height = floors.stream().mapToInt(p -> p.y() + p.model().height()).max().orElse(0);
        if (height == 0) return null;
        int[] states = new int[16 * 16 * height]; Arrays.fill(states, -1);
        for (var floor : floors) {
            var model = floor.model();
            for (int q = 0; q < model.quadCount(); q++) {
                // Surface rectangles carry the actual template footprint, never a fixed inset box.
                if (model.direction(q) != 0) continue;
                int top = floor.y() + model.y(q);
                for (int z = model.z(q); z < model.z(q) + model.dz(q); z++)
                    for (int x = model.x(q); x < model.x(q) + model.dx(q); x++)
                        for (int y = 0; y < top; y++) states[y * 256 + z * 16 + x] = model.state(q);
            }
        }
        // Use façade materials for walls, leaving the roof's own palette on the top layer.
        for (var floor : floors) {
            var model = floor.model();
            for (int q = 0; q < model.quadCount(); q++) if (model.direction(q) != 0) {
                int d = model.direction(q), x0 = Math.min(15, model.x(q) - (d == 4 ? 1 : 0));
                int z0 = Math.min(15, model.z(q) - (d == 2 ? 1 : 0));
                for (int y = floor.y() + model.y(q); y < floor.y() + model.y(q) + model.dy(q); y++)
                    for (int z = z0; z < z0 + Math.max(1, model.dz(q)); z++)
                        for (int x = x0; x < x0 + Math.max(1, model.dx(q)); x++)
                            if (x >= 0 && x < 16 && z >= 0 && z < 16 && y < height)
                                states[y * 256 + z * 16 + x] = model.state(q);
            }
        }
        return mesh(states, height, 4);
    }

    private static int representative(int[] states, int height, int x, int y, int z, int spacing) {
        if (y < 0 || y >= height) return -1;
        int best = -1, bestCount = 0;
        // Bounded (at most 16 states) majority preserves facade/window bands without random palette reads.
        for (int dz = 0; dz < spacing; dz++) for (int dx = 0; dx < spacing; dx++) {
            int state = states[y * 256 + (z * spacing + dz) * 16 + x * spacing + dx];
            if (state < 0 || state == best) continue;
            int count = 0;
            for (int oz = 0; oz < spacing; oz++) for (int ox = 0; ox < spacing; ox++)
                if (states[y * 256 + (z * spacing + oz) * 16 + x * spacing + ox] == state) count++;
            if (count > bestCount) { best = state; bestCount = count; }
        }
        return best;
    }

    private interface Rectangle { void add(int u, int v, int du, int dv, int state); }

    private static void rectangles(int[] mask, int width, int height, Rectangle out) {
        for (int v = 0; v < height; v++) for (int u = 0; u < width; u++) {
            int state = mask[v * width + u]; if (state < 0) continue;
            int du = 1; while (u + du < width && mask[v * width + u + du] == state) du++;
            int dv = 1;
            rows: while (v + dv < height) {
                for (int x = 0; x < du; x++) if (mask[(v + dv) * width + u + x] != state) break rows;
                dv++;
            }
            out.add(u, v, du, dv, state);
            for (int z = 0; z < dv; z++) Arrays.fill(mask, (v + z) * width + u, (v + z) * width + u + du, -1);
        }
    }

    private static void add(List<Integer> words, int x, int y, int z, int dx, int dy, int dz, int d, int state) {
        words.add(LostCityPreview.origin(x, y, z)); words.add(LostCityPreview.extent(dx, dy, dz, d)); words.add(state);
    }
}
