package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;

/** Connects the selected surface heights at ownership boundaries. No world-bottom skirts. */
final class PredictionLodSeams {
    record Surface(PredictionTile tile, boolean[] allowed) { }
    record Patch(Surface surface, PredictionPackedMesh mesh) { }
    private Map<PredictionTileKey, Surface> previous = Map.of();
    private List<Patch> patches = List.of();
    private final Map<PredictionTileKey, Cached> cache = new HashMap<>();
    private long localReuses;
    long localReuses() { return localReuses; }
    private record Cached(Surface source, int[] edges, Surface[] neighbors, PredictionPackedMesh mesh) { }

    List<Patch> update(List<Surface> surfaces) {
        boolean unchanged = surfaces.size() == previous.size();
        if (unchanged) {
            for (var surface:surfaces) {
                var old=previous.get(surface.tile().key());
                if(old==null || old.tile()!=surface.tile() || !Arrays.equals(old.allowed(),surface.allowed())) {
                    unchanged=false;break;
                }
            }
            if(unchanged) return patches;
        }
        var inputs = new HashMap<PredictionTileKey, Surface>();
        for (Surface surface : surfaces) {
            Surface old = previous.get(surface.tile().key());
            boolean same = old != null && old.tile() == surface.tile()
                    && Arrays.equals(old.allowed(), surface.allowed());
            unchanged &= same;
            // Canonicalize once per tile. Hundreds of boundary edges can
            // reference this same coverage array during neighbor validation.
            inputs.put(surface.tile().key(), same ? old : surface);
        }
        if (unchanged) return patches;
        // Changes elsewhere in the frustum cannot alter this tile's border queries.
        // Bound the extra region comparisons; large changes use the complete path.
        var changed = new ArrayList<Surface>();
        for (var old : previous.values()) if (inputs.get(old.tile().key()) != old) changed.add(old);
        for (var current : inputs.values()) if (previous.get(current.tile().key()) != current) changed.add(current);
        var index = new Index(inputs.values());
        var next = new ArrayList<Patch>();
        for (Surface requested : surfaces) {
            Surface surface = inputs.get(requested.tile().key());
            Cached old = cache.get(surface.tile().key());
            Cached item;
            if (old != null && old.source() == surface && changed.size() <= 64
                    && !touchesChangedRegion(surface.tile(), changed)) {
                item = old;
                localReuses++;
            } else item = stitch(surface, index, old);
            cache.put(surface.tile().key(), item);
            PredictionPackedMesh mesh = item.mesh();
            if (mesh.quadCount() != 0) next.add(new Patch(surface, mesh));
        }
        previous = inputs;
        cache.keySet().retainAll(inputs.keySet());
        patches = List.copyOf(next);
        return patches;
    }

    String diagnostics() { return "mode=mesh-owned,builds=" + PredictionSeamMesh.builds()
            + ",localReuses=" + localReuses; }
    long wallIndexBuilds() { return PredictionSeamMesh.builds(); }

    void clear() { previous = Map.of(); patches = List.of(); cache.clear(); }

    private static boolean touchesChangedRegion(PredictionTile source, List<Surface> changes) {
        for (var change : changes) if (touchesBorderRegion(source, change.tile())) return true;
        return false;
    }

    private static boolean touchesBorderRegion(PredictionTile source, PredictionTile changed) {
        long sx = source.baseBlockX(), sz = source.baseBlockZ();
        long cx = changed.baseBlockX(), cz = changed.baseBlockZ();
        // Index.at reads at most one block outside the owning footprint. Include
        // parent/child overlap, negative coordinates and tiles sharing just an edge.
        return cx <= sx + source.spanBlocks() && cx + changed.spanBlocks() > sx - 1
                && cz <= sz + source.spanBlocks() && cz + changed.spanBlocks() > sz - 1;
    }

    private static Cached stitch(Surface surface, Index index, Cached old) {
        boolean sameSource = old != null && old.source() == surface;
        int[] edges = sameSource ? old.edges() : boundaryEdges(surface);
        Surface[] neighbors = sameSource ? old.neighbors() : new Surface[edges.length];
        boolean sameNeighbors = sameSource;
        int axis = surface.tile().cellAxis(), step = surface.tile().spacingBlocks();
        for (int i = 0; i < edges.length; i++) {
            int edge = edges[i], cell = edge >>> 2, direction = edge & 3;
            int nx = direction == 0 ? -1 : direction == 1 ? 1 : 0;
            int nz = direction == 2 ? -1 : direction == 3 ? 1 : 0;
            int wx = surface.tile().baseBlockX() + (cell % axis) * step;
            int wz = surface.tile().baseBlockZ() + (cell / axis) * step;
            Surface neighbor = index.at(wx + (nx < 0 ? -1 : nx > 0 ? step : step / 2),
                    wz + (nz < 0 ? -1 : nz > 0 ? step : step / 2));
            if (sameSource && neighbor != old.neighbors()[i]) {
                if (sameNeighbors) neighbors = neighbors.clone();
                sameNeighbors = false;
            }
            if (!sameNeighbors) neighbors[i] = neighbor;
        }
        if (sameNeighbors) return old;
        var words = new IntArrayList();
        for (int i = 0; i < edges.length; i++) {
            int cell = edges[i] >>> 2, direction = edges[i] & 3;
            edge(surface, neighbors[i], index, words, cell, cell % axis, cell / axis,
                    direction == 0 ? -1 : direction == 1 ? 1 : 0,
                    direction == 2 ? -1 : direction == 3 ? 1 : 0);
        }
        return new Cached(surface, edges, neighbors, PredictionPackedMesh.terrainRecords(words.toIntArray(), axis));
    }

    private static int[] boundaryEdges(Surface surface) {
        PredictionTile tile = surface.tile();
        int axis = tile.cellAxis();
        var edges = new IntArrayList();
        for (int z = 0; z < axis; z++) for (int x = 0; x < axis; x++) {
            int cell = z * axis + x;
            if (!surface.allowed()[cell]) continue;
            if (x == 0 || !surface.allowed()[cell - 1]) edges.add(cell * 4);
            if (x == axis - 1 || !surface.allowed()[cell + 1]) edges.add(cell * 4 + 1);
            if (z == 0 || !surface.allowed()[cell - axis]) edges.add(cell * 4 + 2);
            if (z == axis - 1 || !surface.allowed()[cell + axis]) edges.add(cell * 4 + 3);
        }
        return edges.toIntArray();
    }

    private static void edge(Surface source, Surface adjacent, Index index, IntArrayList words,
                             int cell, int x, int z, int nx, int nz) {
        PredictionTile tile = source.tile();
        PredictionTile neighbor = adjacent == null ? null : adjacent.tile();
        int step = tile.spacingBlocks();
        int wx = tile.baseBlockX() + x * step, wz = tile.baseBlockZ() + z * step;
        int adjacentX = wx + (nx < 0 ? -1 : nx > 0 ? step : step / 2);
        int adjacentZ = wz + (nz < 0 ? -1 : nz > 0 ? step : step / 2);
        if (neighbor == null || neighbor == tile) return;
        // The finer side enumerates the seam, splitting every coarse edge at
        // its actual fine-cell endpoints. Equal levels emit each seam once.
        if (step > neighbor.spacingBlocks() || step == neighbor.spacingBlocks() && nx + nz < 0) return;
        int neighborCell = cellAt(neighbor, adjacentX, adjacentZ);
        if (!tile.samples()[PredictionGpuTile.sampleIndexForCell(cell, tile.cellAxis())].hasSurface()
                || !neighbor.samples()[PredictionGpuTile.sampleIndexForCell(neighborCell, neighbor.cellAxis())].hasSurface()) return;
        var ownQuads = tile.mesh().seamMesh();
        var neighborQuads = neighbor.mesh().seamMesh();
        if (!ownQuads.hasTop(cell) || !neighborQuads.hasTop(neighborCell)) return;
        int ownY = Math.round(ownQuads.topY(cell)), otherY = Math.round(neighborQuads.topY(neighborCell));
        if (ownY == otherY) return;
        boolean ownHigher = ownY > otherY;
        PredictionTile higher = ownHigher ? tile : neighbor;
        int higherCell = ownHigher ? cell : neighborCell;
        int tint = higher.mesh().seamMesh().topColor(higherCell);
        ClientColumnSample sample = higher.samples()[PredictionGpuTile.sampleIndexForCell(higherCell, higher.cellAxis())];
        int normalX = ownHigher ? nx : -nx, normalZ = ownHigher ? nz : -nz;
        int face = normalX > 0 ? 4 : normalX < 0 ? 3 : normalZ > 0 ? 2 : 1;
        int topBlock = PredictionMaterialPalette.groundBlock(sample);
        int under = PredictionMaterialPalette.wallUnderBlock(sample);
        int deep = PredictionMaterialPalette.wallDeepBlock(sample);
        int top = Math.max(ownY, otherY), bottom = Math.min(ownY, otherY);
        int ax = (x + (nx > 0 ? 1 : 0)) * step, az = (z + (nz > 0 ? 1 : 0)) * step;
        int bx = ax + (nz != 0 ? step : 0), bz = az + (nx != 0 ? step : 0);
        // Only fill missing wall intervals. Coplanar copies fight for depth.
        var gaps = new ArrayList<>(PredictionWallEvidence.intervals(sample, bottom, top, higher.spacingBlocks()));
        int worldX = tile.baseBlockX() + ax, worldZ = tile.baseBlockZ() + az;
        index.subtractWalls(gaps, source, worldX, worldZ, step, normalX, normalZ);
        index.subtractWalls(gaps, adjacent, worldX, worldZ, step, normalX, normalZ);
        int middle = Math.max(bottom, top - 1), low = Math.max(bottom, top - 2);
        for (HeightSpan gap : gaps) {
            band(words, tile, cell, ax, az, bx, bz, Math.min(top, gap.top()), Math.max(middle, gap.bottom()),
                    normalX, normalZ, topBlock, face, tint, sample);
            band(words, tile, cell, ax, az, bx, bz, Math.min(middle, gap.top()), Math.max(low, gap.bottom()),
                    normalX, normalZ, under, face, tint, sample);
            band(words, tile, cell, ax, az, bx, bz, Math.min(low, gap.top()), Math.max(bottom, gap.bottom()),
                    normalX, normalZ, deep, face, tint, sample);
        }
    }

    static void band(IntArrayList out, PredictionTile tile, int cell, int ax, int az, int bx, int bz,
                             int top, int bottom, int nx, int nz, int block, int face, int tint, ClientColumnSample sample) {
        if (bottom >= top) return;
        int shift = 0;
        while ((tile.spanBlocks() >> shift) > 65535) shift++;
        int sprite = block == ClientColumnSample.NO_BLOCK ? 0 : VssLodSpriteTable.sideIndexForBlock(block, face);
        if (sprite == VssLodSpriteTable.FLAT) sprite = 0;
        int color = PredictionMaterialPalette.colorForIndex(block, tint, face) & 0xFFFFFF;
        int attr = sprite | shift << PredictionPackedMesh.XZ_SHIFT_BITS
                | (nx != 0 ? 1 : 2) << PredictionPackedMesh.FLAGS_AXIS_SHIFT;
        if (nx > 0 || nz > 0) attr |= PredictionPackedMesh.FLAG_FACE_POSITIVE;
        out.add(pair(ax >> shift, bx >> shift)); out.add(pair(bx >> shift, ax >> shift));
        out.add(pair(az >> shift, bz >> shift)); out.add(pair(bz >> shift, az >> shift));
        out.add(pair(top * 4 + 32768, top * 4 + 32768));
        out.add(pair(bottom * 4 + 32768, bottom * 4 + 32768));
        int upperColor = color | PredictionPackedMesh.waterLightLoss(sample, top) << 28;
        int lowerColor = color | PredictionPackedMesh.waterLightLoss(sample, bottom) << 28;
        out.add(attr); out.add(upperColor); out.add(cell);
        // Coverage belongs to the finer source; light belongs to the higher
        // surface, which can be in the neighbouring tile.
        out.add(upperColor | 1 << 24); out.add(lowerColor); out.add(lowerColor);
    }

    record HeightSpan(int bottom, int top) { }

    private static int pair(int a, int b) { return (a & 65535) | (b & 65535) << 16; }
    private static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
    static int cellAt(PredictionTile tile, int x, int z) {
        return Math.floorDiv(z - tile.baseBlockZ(), tile.spacingBlocks()) * tile.cellAxis()
                + Math.floorDiv(x - tile.baseBlockX(), tile.spacingBlocks());
    }

    static final class Index {
        private final SortedMap<Integer, Long2ObjectOpenHashMap<Surface>> levels = new TreeMap<>();
        private final int[] spans;
        private final Long2ObjectOpenHashMap<Surface>[] levelTables;
        void subtractWalls(List<HeightSpan> gaps, Surface surface, int x, int z, int length, int nx, int nz) {
            if (surface == null || gaps.isEmpty()) return;
            surface.tile().mesh().seamMesh().subtract(gaps, surface, x, z, length, nx, nz);
        }
        @SuppressWarnings("unchecked")
        Index(Collection<Surface> surfaces) {
            for (Surface surface : surfaces) {
                PredictionTile tile = surface.tile();
                levels.computeIfAbsent(tile.spanBlocks(), ignored -> new Long2ObjectOpenHashMap<>())
                        .put(key(tile.key().tileX(), tile.key().tileZ()), surface);
            }
            spans = levels.keySet().stream().mapToInt(Integer::intValue).toArray();
            levelTables = levels.values().toArray(new Long2ObjectOpenHashMap[0]);
        }
        Surface at(int x, int z) {
            for (int i = 0; i < spans.length; i++) {
                int span = spans[i];
                Surface surface = levelTables[i].get(key(Math.floorDiv(x, span), Math.floorDiv(z, span)));
                if (surface != null && surface.allowed()[cellAt(surface.tile(), x, z)]) return surface;
            }
            return null;
        }
    }
}
