package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.AABB;

/** Clips sampled fluid faces against the same placed shapes used by the solid mesh. */
final class PredictionFluidOcclusion {
    private final Map<Integer, List<AABB>> cells = new HashMap<>();

    PredictionFluidOcclusion(PredictionVegetation.Tile tile, int axis, int step) {
        this(tile, axis, step, null);
    }

    PredictionFluidOcclusion(PredictionVegetation.Tile tile, int axis, int step, ClientColumnSample[] samples) {
        if (tile == null) return;
        int minWater = Integer.MAX_VALUE, maxWater = Integer.MIN_VALUE;
        if (samples != null) {
            for (var sample : samples) if (sample.hasFluid()) {
                minWater = Math.min(minWater, sample.fluidY() - 1);
                maxWater = Math.max(maxWater, sample.fluidY());
            }
            if (maxWater == Integer.MIN_VALUE) return;
        }
        for (var voxels : tile.cells().values()) {
            for (var voxel : voxels) {
                if (!PredictionVegetation.solid(voxel.state())) continue;
                if (samples != null && (voxel.y() > maxWater || voxel.y() + voxel.size() < minWater)) continue;
                // A dry placed solid replaces the fluid block completely.
                // Only waterlogged shapes retain water in their open volume.
                var shapes = voxel.state().isAir()
                        || voxel.state().getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                        || voxel.state().getFluidState().isEmpty()
                        ? List.of(new AABB(0, 0, 0, voxel.size(), voxel.size(), voxel.size()))
                        : PredictionSurfaceShapes.boxes(voxel.state(), voxel.size());
                for (AABB shape : shapes) {
                    AABB box = shape.move(voxel.x(), voxel.y(), voxel.z());
                    // Include adjacent cells for faces exactly on a block boundary.
                    int x0 = Math.max(0, (int) Math.floor((box.minX - .001) / step));
                    int z0 = Math.max(0, (int) Math.floor((box.minZ - .001) / step));
                    int x1 = Math.min(axis - 1, (int) Math.floor(box.maxX / step));
                    int z1 = Math.min(axis - 1, (int) Math.floor(box.maxZ / step));
                    for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                        if (samples != null) {
                            var sample = samples[z * (axis + 1) + x];
                            if (!sample.hasFluid() || box.minY > sample.fluidY() || box.maxY < sample.fluidY() - 1) continue;
                        }
                        cells.computeIfAbsent(z * axis + x, ignored -> new ArrayList<>()).add(box);
                    }
                }
            }
        }
    }

    /** axis: 0 = horizontal (X/Z), 1 = X-facing (Z/Y), 2 = Z-facing (X/Y). */
    List<Rect> visible(int cell, int axis, double plane, Rect face) {
        var boxes = cells.get(cell);
        if (boxes == null) return List.of(face);
        List<Rect> remaining = List.of(face);
        for (AABB box : boxes) {
            double min = axis == 0 ? box.minY : axis == 1 ? box.minX : box.minZ;
            double max = axis == 0 ? box.maxY : axis == 1 ? box.maxX : box.maxZ;
            if (plane < min - .001 || plane > max + .001) continue;
            Rect cut = axis == 0 ? new Rect(box.minX, box.minZ, box.maxX, box.maxZ)
                    : axis == 1 ? new Rect(box.minZ, box.minY, box.maxZ, box.maxY)
                    : new Rect(box.minX, box.minY, box.maxX, box.maxY);
            var next = new ArrayList<Rect>();
            for (Rect r : remaining) subtract(r, cut, next);
            remaining = next;
            if (remaining.isEmpty()) break;
        }
        return remaining;
    }

    private static void subtract(Rect r, Rect cut, List<Rect> out) {
        double a = Math.max(r.u0, cut.u0), b = Math.max(r.v0, cut.v0);
        double c = Math.min(r.u1, cut.u1), d = Math.min(r.v1, cut.v1);
        if (a >= c || b >= d) { out.add(r); return; }
        if (r.u0 < a) out.add(new Rect(r.u0, r.v0, a, r.v1));
        if (c < r.u1) out.add(new Rect(c, r.v0, r.u1, r.v1));
        if (r.v0 < b) out.add(new Rect(a, r.v0, c, b));
        if (d < r.v1) out.add(new Rect(a, d, c, r.v1));
    }

    record Rect(double u0, double v0, double u1, double v1) { }
}
