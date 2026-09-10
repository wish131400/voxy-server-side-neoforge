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
    private final WallCache wallCache = new WallCache();
    private record Cached(Surface source, int[] edges, Surface[] neighbors, PredictionPackedMesh mesh) { }

    List<Patch> update(List<Surface> surfaces) {
        boolean unchanged = surfaces.size() == previous.size();
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
        wallCache.retain(inputs);
        var index = new Index(inputs.values(), wallCache);
        var next = new ArrayList<Patch>();
        for (Surface requested : surfaces) {
            Surface surface = inputs.get(requested.tile().key());
            Cached item = stitch(surface, index, cache.get(surface.tile().key()));
            cache.put(surface.tile().key(), item);
            PredictionPackedMesh mesh = item.mesh();
            if (mesh.quadCount() != 0) next.add(new Patch(surface, mesh));
        }
        previous = inputs;
        cache.keySet().retainAll(inputs.keySet());
        patches = List.copyOf(next);
        return patches;
    }

    void clear() { previous = Map.of(); patches = List.of(); cache.clear(); wallCache.clear(); }

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
        var ownQuads = tile.mesh().packed();
        var neighborQuads = neighbor.mesh().packed();
        int ownTop = ownQuads.quadForCell(cell), otherTop = neighborQuads.quadForCell(neighborCell);
        if (ownTop < 0 || otherTop < 0) return;
        int ownY = Math.round(ownQuads.y(ownTop, 0)), otherY = Math.round(neighborQuads.y(otherTop, 0));
        if (ownY == otherY) return;
        boolean ownHigher = ownY > otherY;
        PredictionTile higher = ownHigher ? tile : neighbor;
        int higherCell = ownHigher ? cell : neighborCell;
        var higherMesh = higher.mesh().packed();
        int tint = higherMesh.color(ownHigher ? ownTop : otherTop, 0);
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
        var gaps = new ArrayList<HeightSpan>(); gaps.add(new HeightSpan(bottom, top));
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

    /** Index integer terrain walls once per consulted tile/update. */
    private static final class WallIndex {
        private final Long2ObjectOpenHashMap<IntArrayList> planes = new Long2ObjectOpenHashMap<>();
        private final PredictionQuadMesh mesh;
        WallIndex(PredictionTile tile) {
            mesh = tile.mesh().packed();
            for (int q = 0; q < mesh.quadCount(); q++) {
                int nx = Math.round(mesh.normalX(q, 0)), nz = Math.round(mesh.normalZ(q, 0));
                if (mesh.normalY(q, 0) != 0 || Math.abs(nx) + Math.abs(nz) != 1) continue;
                int sprite = mesh.color(q, 0) >>> 24;
                // Leaves and baked model faces can contain holes. They do not
                // establish an opaque terrain wall behind which to omit a seam.
                if (VssLodSpriteTable.modelFace(sprite) || VssLodSpriteTable.isCutout(sprite)) continue;
                float plane = nx != 0 ? mesh.x(q, 0) : mesh.z(q, 0);
                boolean rectangle = true;
                for (int c = 0; c < 4; c++) {
                    float v = nx != 0 ? mesh.x(q, c) : mesh.z(q, c);
                    rectangle &= v == plane && mesh.y(q, c) == Math.rint(mesh.y(q, c));
                }
                if (!rectangle || plane != Math.rint(plane) || mesh.y(q, 0) != mesh.y(q, 1)
                        || mesh.y(q, 2) != mesh.y(q, 3) || mesh.y(q, 0) <= mesh.y(q, 2)) continue;
                planes.computeIfAbsent(planeKey((int) plane, nx, nz), ignored -> new IntArrayList()).add(q);
            }
        }
        private static long planeKey(int plane, int nx, int nz) {
            return (long) plane << 3 | (nx != 0 ? 0 : 2) | (nx + nz > 0 ? 1 : 0);
        }
        void subtract(List<HeightSpan> gaps, Surface surface, int wx, int wz, int length, int nx, int nz) {
            PredictionTile tile = surface.tile();
            int lx = wx - tile.baseBlockX(), lz = wz - tile.baseBlockZ();
            var candidates = planes.get(planeKey(nx != 0 ? lx : lz, nx, nz));
            if (candidates == null) return;
            int start = nx != 0 ? lz : lx;
            for (int q : candidates) {
                float a = nx != 0 ? mesh.z(q, 0) : mesh.x(q, 0);
                float b = nx != 0 ? mesh.z(q, 1) : mesh.x(q, 1);
                if (Math.min(a,b) > start || Math.max(a,b) < start + length) continue;
                int owner = mesh.coverageCell(q);
                if (mesh.coverageUsesLocalPosition(q)) {
                    int along = Math.floorDiv(start + length / 2, tile.spacingBlocks());
                    owner = nx != 0 ? along * tile.cellAxis() + owner % tile.cellAxis()
                            : owner / tile.cellAxis() * tile.cellAxis() + along;
                }
                if (owner < 0 || owner >= surface.allowed().length || !surface.allowed()[owner]) continue;
                int bottom = Math.round(mesh.y(q,2)), top = Math.round(mesh.y(q,0));
                for (int i = gaps.size() - 1; i >= 0; i--) {
                    HeightSpan gap = gaps.get(i);
                    if (top <= gap.bottom() || bottom >= gap.top()) continue;
                    gaps.remove(i);
                    if (gap.bottom() < bottom) gaps.add(new HeightSpan(gap.bottom(), bottom));
                    if (top < gap.top()) gaps.add(new HeightSpan(top, gap.top()));
                }
            }
        }
    }

    /** Immutable geometry indices survive coverage changes, with bounded retention. */
    static final class WallCache {
        private record Entry(PredictionTile tile, WallIndex index, int weight) { }
        private final LinkedHashMap<PredictionTileKey, Entry> entries = new LinkedHashMap<>(16, .75F, true);
        private int weight;
        WallIndex get(PredictionTile tile) {
            Entry old = entries.get(tile.key());
            if (old != null && old.tile() == tile) return old.index();
            if (old != null) { entries.remove(tile.key()); weight -= old.weight(); }
            var index = new WallIndex(tile);
            int cost = tile.mesh().packed().quadCount();
            if (cost <= 262144) {
                while (!entries.isEmpty() && (weight + cost > 262144 || entries.size() >= 64)) {
                    weight -= entries.pollFirstEntry().getValue().weight();
                }
                entries.put(tile.key(), new Entry(tile, index, cost));
                weight += cost;
            }
            return index;
        }
        void retain(Map<PredictionTileKey, Surface> surfaces) {
            var iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var surface = surfaces.get(entry.tile().key());
                if (surface == null || surface.tile() != entry.tile()) {
                    weight -= entry.weight(); iterator.remove();
                }
            }
        }
        void clear() { entries.clear(); weight = 0; }
    }

    private static int pair(int a, int b) { return (a & 65535) | (b & 65535) << 16; }
    private static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
    static int cellAt(PredictionTile tile, int x, int z) {
        return Math.floorDiv(z - tile.baseBlockZ(), tile.spacingBlocks()) * tile.cellAxis()
                + Math.floorDiv(x - tile.baseBlockX(), tile.spacingBlocks());
    }

    static final class Index {
        private final SortedMap<Integer, Long2ObjectOpenHashMap<Surface>> levels = new TreeMap<>();
        private final Map<PredictionTile, WallIndex> walls = new IdentityHashMap<>();
        private final WallCache wallCache;
        private final int[] spans;
        private final Long2ObjectOpenHashMap<Surface>[] levelTables;
        void subtractWalls(List<HeightSpan> gaps, Surface surface, int x, int z, int length, int nx, int nz) {
            if (surface == null || gaps.isEmpty()) return;
            walls.computeIfAbsent(surface.tile(), tile -> wallCache == null ? new WallIndex(tile) : wallCache.get(tile))
                    .subtract(gaps, surface, x, z, length, nx, nz);
        }
        Index(Collection<Surface> surfaces) { this(surfaces, null); }
        @SuppressWarnings("unchecked")
        Index(Collection<Surface> surfaces, WallCache wallCache) {
            this.wallCache = wallCache;
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
