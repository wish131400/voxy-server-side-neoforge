package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** City ground and exterior faces indexed once per build, using the renderer's existing cell ownership. */
final class PredictionCityGeometry {
    record Face(int x, int y, int z, int dx, int dy, int dz, int direction, int state) { }
    record Ground(ClientColumnSample[] samples, int[] colors) { }
    private final List<Face>[] faces;
    private final List<Face>[] overlays;
    private final LostCityPreview.Tile cities;
    private final int baseX, baseZ, step;

    @SuppressWarnings("unchecked")
    PredictionCityGeometry(LostCityPreview.Tile cities, int baseX, int baseZ, int axis, int step) {
        this(cities, baseX, baseZ, axis, step, null);
    }

    @SuppressWarnings("unchecked")
    PredictionCityGeometry(LostCityPreview.Tile cities, int baseX, int baseZ, int axis, int step, ClientColumnSample[] samples) {
        this.cities = cities; this.baseX = baseX; this.baseZ = baseZ; this.step = step;
        faces = cities == null ? null : (List<Face>[]) new List<?>[axis * axis];
        overlays = cities == null ? null : (List<Face>[]) new List<?>[axis * axis];
        if (cities == null) return;
        int span = axis * step;
        for (int cz = 0; cz < cities.side(); cz++) for (int cx = 0; cx < cities.side(); cx++) {
            var chunk = cities.chunks().get(cz * cities.side() + cx);
            if (chunk.kind() == 0 && chunk.overlays().isEmpty()) continue;
            int x = (cities.minChunkX() + cx) * 16 - baseX, z = (cities.minChunkZ() + cz) * 16 - baseZ;
            if (x + 16 <= 0 || z + 16 <= 0 || x >= span || z >= span) continue;
            if (step <= 4 && !chunk.floors().isEmpty()) {
                for (var floor : chunk.floors()) index(floor.model(), x, chunk.ground() + floor.y(), z, axis);
            } else if (chunk.silhouette() != null) index(chunk.silhouette(), x, chunk.ground(), z, axis);
            for (var overlay : chunk.overlays()) {
                boolean water = overlay.water() != null && submerged(samples, axis + 1, step, x, z, overlay.y());
                var model = water ? step <= 4 ? overlay.water() : overlay.waterDistant()
                        : step <= 4 ? overlay.model() : overlay.distant();
                index(model, x, overlay.y(), z, axis, overlays);
            }
        }
    }

    List<Face> faces(int cell) { return faces == null || faces[cell] == null ? List.of() : faces[cell]; }
    List<Face> overlays(int cell) { return overlays == null || overlays[cell] == null ? List.of() : overlays[cell]; }

    // Lost Cities tests six liquid locations before choosing its glass railway shell.
    // Use existing prediction columns; never generate chunks or sample noise again.
    static boolean submerged(ClientColumnSample[] samples, int grid, int step, int bx, int bz, int y) {
        if (samples == null) return false;
        for (int[] probe : new int[][]{{3,2,3},{12,2,3},{3,2,12},{12,2,12},{3,4,7},{12,4,8}}) {
            int x = Math.floorDiv(bx + probe[0], step), z = Math.floorDiv(bz + probe[2], step);
            if (x < 0 || z < 0 || x >= grid || z >= grid) return false;
            var sample = samples[z * grid + x]; int py = y + probe[1];
            if (sample.fluid() != 1 || sample.surfaceY() > py || sample.fluidY() <= py) return false;
        }
        return true;
    }
    LostCityPreview.Chunk chunk(int x, int z) {
        return cities == null ? LostCityPreview.EMPTY : cities.atBlock(baseX + x * step, baseZ + z * step);
    }

    static boolean surface(LostCityPreview.Chunk chunk) {
        if (chunk.kind() == 0 || chunk.building()) return false;
        if (chunk.silhouette() != null && chunk.silhouette().quadCount() != 0) return true;
        for (var placement : chunk.floors()) if (placement.model().quadCount() != 0) return true;
        return false;
    }

    static boolean vegetationOwner(LostCityPreview.Chunk chunk) { return chunk.building() || surface(chunk); }

    /** Filter before terrain cuts and fluid occlusion, so rejected natural plants leave no holes behind. */
    static PredictionVegetation.Tile vegetation(PredictionVegetation.Tile original, LostCityPreview.Tile cities,
                                                ClientColumnSample[] samples, int baseX, int baseZ, int step, int grid) {
        if (original == null || cities == null || original.blocks().isEmpty()) return original;
        Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> kept = null;
        for (var entry : original.blocks().entrySet()) {
            var pos = entry.getKey();
            int size = PredictionVegetation.voxelSize(entry.getValue(), original.voxelSize());
            boolean owned = false;
            // Coarse proxies are snapped to their voxel grid (a divisor of 16).
            // Check their entire footprint, including any city/captured boundary.
            for (int z = pos.getZ(); z < pos.getZ() + size && !owned;
                 z = baseZ + (Math.floorDiv(z - baseZ, step) + 1) * step) {
                for (int x = pos.getX(); x < pos.getX() + size;
                     x = baseX + (Math.floorDiv(x - baseX, step) + 1) * step) {
                    int cx = Math.floorDiv(x - baseX, step), cz = Math.floorDiv(z - baseZ, step);
                    boolean captured = cx >= 0 && cz >= 0 && cx < grid && cz < grid && samples[cz * grid + cx].captured();
                    if (!captured && vegetationOwner(cities.atBlock(x, z))) { owned = true; break; }
                }
            }
            if (owned) {
                if (kept == null) kept = new HashMap<>(original.blocks());
                kept.remove(pos);
            }
        }
        return kept == null ? original : PredictionVegetation.Tile.of(kept, original.baseX(), original.baseZ(),
                (grid - 1) * step, step, original.voxelSize());
    }

    /** Raw noise samples remain intact in the disk cache; only the display and seam summaries use city ground. */
    static Ground ground(ClientColumnSample[] original, LostCityPreview.Tile cities,
                                      int baseX, int baseZ, int step, int grid, int[] colors) {
        if (cities == null) return new Ground(original, colors);
        ClientColumnSample[] result = null;
        int[] displayColors = colors;
        Map<LostCityPreview.Chunk, int[]> surfaceStates = new IdentityHashMap<>();
        for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) {
            int i = z * grid + x, wx = baseX + x * step, wz = baseZ + z * step;
            var hint = cities.atBlock(wx, wz); var sample = original[i];
            if ((!hint.flatten() && !surface(hint)) || hint.kind() == 0 || sample.captured()) continue;
            if (result == null) {
                result = original.clone();
                displayColors = colors == null ? null : colors.clone();
            }
            int stateId = groundState(hint, wx, wz);
            if (surface(hint)) {
                int[] states = surfaceStates.computeIfAbsent(hint, PredictionCityGeometry::surfaceStates);
                stateId = states[Math.floorMod(wz, 16) * 16 + Math.floorMod(wx, 16)];
            }
            var state = safeState(stateId);
            int block = BuiltInRegistries.BLOCK.getId(state.getBlock());
            int stone = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
            result[i] = new ClientColumnSample(hint.ground() + 1, ClientColumnSample.NO_SPAN,
                    sample.biomeIndex(), block, sample.structureIndex(), 0, 0, 0, 0,
                    ClientColumnSample.FLAG_SURFACE_ONLY | ClientColumnSample.FLAG_CITY_GROUND, 0, stone, stone,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN);
            if (displayColors != null) displayColors[i] = PredictionMaterialPalette.colorForState(state, 0xff808080, 0, 0);
        }
        return new Ground(result == null ? original : result, displayColors);
    }

    private static net.minecraft.world.level.block.state.BlockState safeState(int stateId) {
        try {
            var state = Block.stateById(stateId);
            return state == null || state.isAir() ? Blocks.STONE.defaultBlockState() : state;
        } catch (RuntimeException invalidId) {
            return Blocks.STONE.defaultBlockState();
        }
    }

    private static int groundState(LostCityPreview.Chunk chunk, int wx, int wz) {
        if (chunk.kind() != LostCityPreview.ROAD) return chunk.surfaceState();
        int x = Math.floorMod(wx, 16), z = Math.floorMod(wz, 16), lo = (16 - chunk.roadWidth()) / 2;
        int hi = lo + chunk.roadWidth(), c = chunk.connections();
        boolean middleX = x >= lo && x < hi, middleZ = z >= lo && z < hi;
        boolean road = middleX && middleZ || middleZ && (x < lo && (c & 1) != 0 || x >= hi && (c & 2) != 0)
                || middleX && (z < lo && (c & 4) != 0 || z >= hi && (c & 8) != 0);
        return road ? chunk.surfaceState() : chunk.pavementState();
    }

    private static int[] surfaceStates(LostCityPreview.Chunk chunk) {
        int[] states = new int[256]; Arrays.fill(states, chunk.surfaceState());
        var model = chunk.floors().isEmpty() ? chunk.silhouette() : chunk.floors().get(0).model();
        if (model != null) for (int q = 0; q < model.quadCount(); q++) if (model.direction(q) == 0 && model.y(q) == 1) {
            for (int z = model.z(q); z < model.z(q) + model.dz(q); z++)
                Arrays.fill(states, z * 16 + model.x(q), z * 16 + model.x(q) + model.dx(q), model.state(q));
        }
        return states;
    }

    private void index(LostCityPreview.Model model, int bx, int by, int bz, int axis) {
        index(model, bx, by, bz, axis, faces);
    }

    private void index(LostCityPreview.Model model, int bx, int by, int bz, int axis, List<Face>[] target) {
        for (int q = 0; q < model.quadCount(); q++) {
            int d = model.direction(q), x0 = bx + model.x(q), z0 = bz + model.z(q);
            int x1 = x0 + model.dx(q), z1 = z0 + model.dz(q), y = by + model.y(q);
            int minX = Math.floorDiv(x0 - (d == 4 ? 1 : 0), step);
            int minZ = Math.floorDiv(z0 - (d == 2 ? 1 : 0), step);
            int maxX = d == 3 || d == 4 ? minX : Math.floorDiv(x1 - 1, step);
            int maxZ = d == 1 || d == 2 ? minZ : Math.floorDiv(z1 - 1, step);
            for (int cz = Math.max(0, minZ); cz <= Math.min(axis - 1, maxZ); cz++)
                for (int cx = Math.max(0, minX); cx <= Math.min(axis - 1, maxX); cx++) {
                    int ax = d == 3 || d == 4 ? x0 : Math.max(x0, cx * step);
                    int az = d == 1 || d == 2 ? z0 : Math.max(z0, cz * step);
                    int dx = d == 3 || d == 4 ? 0 : Math.min(x1, (cx + 1) * step) - ax;
                    int dz = d == 1 || d == 2 ? 0 : Math.min(z1, (cz + 1) * step) - az;
                    int cell = cz * axis + cx;
                    if (target[cell] == null) target[cell] = new ArrayList<>();
                    target[cell].add(new Face(ax, y, az, dx, model.dy(q), dz, d, model.state(q)));
                }
        }
    }
}
