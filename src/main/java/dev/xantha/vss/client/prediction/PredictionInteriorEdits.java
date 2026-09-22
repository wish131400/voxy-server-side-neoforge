package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exact footprints for rooms, foundations and liquids inside a coarser cave grid.
 * Only cells touching edits subdivide; unchanged terrain keeps its original spacing. */
final class PredictionInteriorEdits {
    private final ClientColumnSample[] samples;
    private final int spacing, grid;
    private final Map<Long, Map<Integer, BlockState>> edits = new HashMap<>();
    private final Map<Long, PredictionColumnVolume> geometry = new HashMap<>(), occlusion = new HashMap<>();

    PredictionInteriorEdits(ClientColumnSample[] samples, PredictionVegetation.Tile tile,
                            int baseX, int baseZ, int spacing, int grid) {
        this.samples = samples; this.spacing = spacing; this.grid = grid;
        if (tile == null || samples.length == 0 || !PredictionExteriorColumns.interiorVolume(samples[0])) return;
        tile.blocks().forEach((p, state) -> edits.computeIfAbsent(key(p.getX() - baseX, p.getZ() - baseZ),
                ignored -> new HashMap<>()).put(p.getY(), state));
    }

    boolean affects(int x, int z, int step) {
        if (edits.isEmpty()) return false;
        for (int dz = -1; dz <= step; dz++) for (int dx = -1; dx <= step; dx++)
            if (edits.containsKey(key(x + dx, z + dz))) return true;
        return false;
    }

    PredictionColumnVolume column(int x, int z, boolean blockers) {
        if (x < 0 || z < 0 || x >= grid * spacing || z >= grid * spacing) return null;
        var base = samples[z / spacing * grid + x / spacing].volume();
        if (base == null) return null;
        long key = key(x, z);
        var changes = edits.get(key);
        if (changes == null) return base;
        return (blockers ? occlusion : geometry).computeIfAbsent(key, ignored -> {
            int min = base.minY(), max = base.maxY();
            for (int y : changes.keySet()) { min = Math.min(min, y); max = Math.max(max, y + 1); }
            return PredictionColumnVolume.sample(min, max - min, y -> {
                var state = changes.get(y);
                if (state != null) {
                    if (!blockers || !(PredictionVegetation.mergeable(state, 1)
                            || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) return -1;
                    return BuiltInRegistries.BLOCK.getId(state.getBlock());
                }
                int id = base.blockAt(y);
                return id == ClientColumnSample.NO_BLOCK ? -1 : id;
            }, id -> {
                var state = BuiltInRegistries.BLOCK.byId(id).defaultBlockState();
                return state.getFluidState().isEmpty() ? 0 : state.is(Blocks.LAVA) ? 2 : 1;
            });
        });
    }

    private static long key(int x, int z) { return (long)x << 32 | z & 0xffffffffL; }
}
