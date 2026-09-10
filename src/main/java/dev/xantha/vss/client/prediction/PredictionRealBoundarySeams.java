package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.*;

/** Joins compiled vanilla ground to the currently selected prediction surface. */
final class PredictionRealBoundarySeams {
    // This bridges small boundary disagreement, not arbitrary worldgen error.
    // Larger differences must be corrected in terrain data, never filled by
    // an invented cliff. Keep independent of zoom and coarse cell spacing.
    static final int MAX_HEIGHT_DIFFERENCE = 4;
    // Metadata in corner 1; RGB and skylight keep their existing layout.
    static final int REAL_BOUNDARY = 1 << 25;
    static final int REAL_LOWER = 1 << 26;
    record Edge(int x, int z, int nx, int nz, ClientColumnSample ground) { }
    private record Input(PredictionLodSeams.Surface surface, List<Edge> edges) { }
    private record Cached(Input input, PredictionPackedMesh mesh) { }
    private final Map<PredictionTileManager.PredictionTileKey, Cached> cache = new HashMap<>();
    private Map<PredictionTileManager.PredictionTileKey, PredictionLodSeams.Surface> previous = Map.of();
    private List<Edge> previousEdges = List.of();
    private List<PredictionLodSeams.Patch> patches = List.of();

    List<PredictionLodSeams.Patch> update(List<PredictionLodSeams.Surface> surfaces, List<Edge> edges) {
        boolean unchanged = surfaces.size() == previous.size() && edges.equals(previousEdges);
        for (var surface : surfaces) {
            var old = previous.get(surface.tile().key());
            unchanged &= old != null && old.tile() == surface.tile() && Arrays.equals(old.allowed(), surface.allowed());
        }
        if (unchanged) return patches;
        var index = new PredictionLodSeams.Index(surfaces);
        var grouped = new LinkedHashMap<PredictionLodSeams.Surface, List<Edge>>();
        for (var edge : edges) {
            var surface = index.at(edge.x() + edge.nx(), edge.z() + edge.nz());
            // Integer block edges cannot be encoded by the scaled far-tile format.
            if (surface != null && surface.tile().spanBlocks() <= 65535)
                grouped.computeIfAbsent(surface, ignored -> new ArrayList<>()).add(edge);
        }
        var result = new ArrayList<PredictionLodSeams.Patch>();
        var active = new HashSet<PredictionTileManager.PredictionTileKey>();
        grouped.forEach((surface, boundary) -> {
            var key = surface.tile().key();
            active.add(key);
            var old = cache.get(key);
            if (old == null || old.input().surface().tile() != surface.tile()
                    || !Arrays.equals(old.input().surface().allowed(), surface.allowed())
                    || !old.input().edges().equals(boundary)) {
                var words = new IntArrayList();
                for (var edge : boundary) emit(words, surface, edge, index);
                old = new Cached(new Input(surface, List.copyOf(boundary)),
                        PredictionPackedMesh.terrainRecords(words.toIntArray(), surface.tile().cellAxis()));
                cache.put(key, old);
            }
            if (old.mesh().quadCount() != 0) result.add(new PredictionLodSeams.Patch(surface, old.mesh()));
        });
        cache.keySet().retainAll(active);
        previous = new HashMap<>();
        for (var surface : surfaces) previous.put(surface.tile().key(), surface);
        previousEdges = List.copyOf(edges);
        patches = List.copyOf(result);
        return patches;
    }

    void clear() { cache.clear(); previous = Map.of(); previousEdges = List.of(); patches = List.of(); }

    private static void emit(IntArrayList words, PredictionLodSeams.Surface surface, Edge edge, PredictionLodSeams.Index index) {
        var tile = surface.tile();
        int cell = PredictionLodSeams.cellAt(tile, edge.x() + edge.nx(), edge.z() + edge.nz());
        var predicted = tile.samples()[PredictionGpuTile.sampleIndexForCell(cell, tile.cellAxis())];
        int q = tile.mesh().packed().quadForCell(cell);
        if (q < 0 || !predicted.hasSurface() || !edge.ground().hasSurface()) return;
        int predictedY = Math.round(tile.mesh().packed().y(q, 0));
        int realY = edge.ground().surfaceY();
        if (realY == predictedY || Math.abs((long) realY - predictedY) > MAX_HEIGHT_DIFFERENCE) return;
        boolean realHigher = realY > predictedY;
        var sample = realHigher ? edge.ground() : predicted;
        int nx = realHigher ? edge.nx() : -edge.nx(), nz = realHigher ? edge.nz() : -edge.nz();
        int x = edge.x() + (edge.nx() > 0 ? 1 : 0) - tile.baseBlockX();
        int z = edge.z() + (edge.nz() > 0 ? 1 : 0) - tile.baseBlockZ();
        int top = Math.max(realY, predictedY), bottom = Math.min(realY, predictedY);
        int face = nx > 0 ? 4 : nx < 0 ? 3 : nz > 0 ? 2 : 1;
        int block = PredictionMaterialPalette.groundBlock(sample);
        int under = PredictionMaterialPalette.wallUnderBlock(sample);
        int deep = PredictionMaterialPalette.wallDeepBlock(sample);
        int tint = tile.mesh().packed().color(q, 0);
        int first = words.size();
        var gaps = new ArrayList<PredictionLodSeams.HeightSpan>();
        gaps.add(new PredictionLodSeams.HeightSpan(bottom, top));
        if (!realHigher) index.subtractWalls(gaps, surface, x + tile.baseBlockX(), z + tile.baseBlockZ(), 1, nx, nz);
        for (var gap : gaps) {
            PredictionLodSeams.band(words, tile, cell, x, z, x + (nz != 0 ? 1 : 0), z + (nx != 0 ? 1 : 0),
                    Math.min(top, gap.top()), Math.max(gap.bottom(), top - 1), nx, nz, block, face, tint, sample);
            PredictionLodSeams.band(words, tile, cell, x, z, x + (nz != 0 ? 1 : 0), z + (nx != 0 ? 1 : 0),
                    Math.min(top - 1, gap.top()), Math.max(gap.bottom(), top - 2), nx, nz, under, face, tint, sample);
            PredictionLodSeams.band(words, tile, cell, x, z, x + (nz != 0 ? 1 : 0), z + (nx != 0 ? 1 : 0),
                    Math.min(top - 2, gap.top()), gap.bottom(), nx, nz, deep, face, tint, sample);
        }
        for (int i = first; i < words.size(); i += 12)
            words.set(i + 9, words.getInt(i + 9) | REAL_BOUNDARY | (realHigher ? 0 : REAL_LOWER));
    }
}
