package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;

/** Connects the selected surface heights at ownership boundaries. No world-bottom skirts. */
final class PredictionLodSeams {
    record Surface(PredictionTile tile, boolean[] allowed) { }
    record Patch(Surface surface, PredictionPackedMesh mesh, net.minecraft.world.phys.AABB bounds) {
        Patch(Surface surface, PredictionPackedMesh mesh) {
            this(surface, mesh, bounds(surface.tile(), mesh));
        }

        private static net.minecraft.world.phys.AABB bounds(PredictionTile tile, PredictionPackedMesh mesh) {
            double min = tile.depthBound().minY(), max = tile.depthBound().maxY();
            int[] words = mesh.quads();
            // Seam records use quarter-block Y coordinates; include both ends
            // even when the owning (finer) terrain is entirely below the view.
            for (int q = 0; q < words.length; q += PredictionPackedMesh.STRIDE_INTS) {
                for (int row = 4; row <= 5; row++) {
                    double a = ((words[q + row] & 65535) - 32768) / 4D;
                    double b = ((words[q + row] >>> 16) - 32768) / 4D;
                    min = Math.min(min, Math.min(a, b));
                    max = Math.max(max, Math.max(a, b));
                }
            }
            return new net.minecraft.world.phys.AABB(tile.baseBlockX(), min, tile.baseBlockZ(),
                    tile.baseBlockX() + tile.spanBlocks(), max, tile.baseBlockZ() + tile.spanBlocks()).inflate(2);
        }
    }
    private final Map<PredictionTileKey, Surface> previous = new HashMap<>();
    private List<Patch> patches = List.of();
    private final Map<PredictionTileKey, Patch> patchByKey = new LinkedHashMap<>();
    private final Map<PredictionTileKey, byte[]> boundaryMasks = new HashMap<>();
    private final Index index = new Index(List.of());
    private final Map<PredictionTileKey, Cached> cache = new HashMap<>();
    private long localReuses, updatedSurfaces;
    long localReuses() { return localReuses; }
    long updatedSurfaces() { return updatedSurfaces; }
    byte[] boundaryMask(PredictionTileKey key) { return boundaryMasks.get(key); }
    Index index() { return index; }
    Surface surface(PredictionTileKey key) { return previous.get(key); }
    private record Cached(Surface source, int[] edges, Surface[] neighbors, PredictionPackedMesh mesh, Patch patch) { }
    record Changes(Map<PredictionTileKey, Patch> patches, Set<PredictionTileKey> removed,
                   Set<PredictionTileKey> boundaries, Set<PredictionTileKey> surfaces) {
        static final Changes EMPTY = new Changes(Map.of(),Set.of(),Set.of(),Set.of());
    }

    // Full-input adapter for callers without a change journal. The live renderer
    // uses apply() and never rebuilds a list of every resident surface here.
    List<Patch> update(List<Surface> surfaces) {
        var changed = new ArrayList<Surface>();
        var removed = new HashSet<>(previous.keySet());
        for (var surface:surfaces) {
            removed.remove(surface.tile().key());
            var old=previous.get(surface.tile().key());
            if(old==null || old.tile()!=surface.tile() || !Arrays.equals(old.allowed(),surface.allowed())) changed.add(surface);
        }
        if(changed.isEmpty() && removed.isEmpty()) return patches;
        apply(changed,removed);
        var result=new ArrayList<Patch>();
        for(var surface:surfaces) {
            var patch=patchByKey.get(surface.tile().key());
            if(patch!=null) result.add(patch);
        }
        return patches=List.copyOf(result);
    }

    Changes apply(Collection<Surface> replacements, Collection<PredictionTileKey> removals) {
        if(replacements.isEmpty() && removals.isEmpty()) return Changes.EMPTY;
        var changes=new LinkedHashMap<PredictionTileKey,Surface>();
        var removed=new HashSet<PredictionTileKey>();
        var footprints=new ArrayList<PredictionTile>();
        for(var key:removals) {
            var old=previous.get(key);
            if(old!=null) { removed.add(key);footprints.add(old.tile()); }
        }
        for(var surface:replacements) {
            var key=surface.tile().key();
            var old=previous.get(key);
            removed.remove(key);
            if(old!=null && old.tile()==surface.tile() && Arrays.equals(old.allowed(),surface.allowed())) continue;
            changes.put(key,surface);
            if(old!=null) footprints.add(old.tile());
            footprints.add(surface.tile());
        }
        if(changes.isEmpty() && removed.isEmpty()) return Changes.EMPTY;
        var affected=new HashSet<PredictionTileKey>();
        for(var tile:footprints) affected(tile,affected);
        for(var key:removed) {
            previous.remove(key); index.remove(key); cache.remove(key);
            boundaryMasks.remove(key);patchByKey.remove(key);
        }
        for(var entry:changes.entrySet()) { previous.put(entry.getKey(),entry.getValue());index.put(entry.getValue()); }
        for(var tile:footprints) affected(tile,affected);
        localReuses+=Math.max(0,cache.size()-affected.stream().filter(cache::containsKey).count());
        // Publish all changed masks before rebuilding any seam that consults a neighbor.
        index.boundaryMasks=boundaryMasks;
        for(var key:affected) {
            var surface=previous.get(key);
            if(surface!=null) boundaryMasks.put(key,PredictionBoundaryWalls.build(surface,index));
        }
        var upserts=new LinkedHashMap<PredictionTileKey,Patch>();
        var retired=new HashSet<>(removed);
        for(var key:affected) {
            var surface=previous.get(key);
            if(surface==null) continue;
            updatedSurfaces++;
            var old=cache.get(key);
            var item=stitch(surface,index,old);
            cache.put(key,item);
            if(item.patch()==null) { if(patchByKey.remove(key)!=null) retired.add(key); }
            else { patchByKey.put(key,item.patch());upserts.put(key,item.patch()); }
        }
        var changedKeys=new HashSet<>(removed);changedKeys.addAll(changes.keySet());
        return new Changes(upserts,retired,affected,changedKeys);
    }

    private void affected(PredictionTile tile,Set<PredictionTileKey> keys) {
        index.spatial.intersect((long)tile.baseBlockX()-1,(long)tile.baseBlockZ()-1,
                (long)tile.baseBlockX()+tile.spanBlocks()+1,(long)tile.baseBlockZ()+tile.spanBlocks()+1,
                surface->keys.add(surface.tile().key()));
    }

    String diagnostics() { return "mode=incremental,builds=" + PredictionSeamMesh.builds()
            + ",localReuses=" + localReuses + ",updated=" + updatedSurfaces; }
    long wallIndexBuilds() { return PredictionSeamMesh.builds(); }
    void clear() { previous.clear();patches=List.of();patchByKey.clear();cache.clear();boundaryMasks.clear();index.clear(); }

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
        // An edge midpoint can remain unchanged while another segment changes.
        // Regional reuse above is the safe cache check for mixed-resolution edges.
        var words = new IntArrayList();
        for (int i = 0; i < edges.length; i++) {
            int cell = edges[i] >>> 2, direction = edges[i] & 3;
            edge(surface, neighbors[i], index, words, cell, cell % axis, cell / axis,
                    direction == 0 ? -1 : direction == 1 ? 1 : 0,
                    direction == 2 ? -1 : direction == 3 ? 1 : 0);
        }
        var mesh = PredictionPackedMesh.terrainRecords(words.toIntArray(), axis);
        return new Cached(surface, edges, neighbors, mesh, mesh.quadCount() == 0 ? null : new Patch(surface, mesh));
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
        var ownSample = tile.samples()[PredictionGpuTile.sampleIndexForCell(cell, tile.cellAxis())];
        var otherSample = neighbor.samples()[PredictionGpuTile.sampleIndexForCell(neighborCell, neighbor.cellAxis())];
        if (PredictionExteriorColumns.interiorVolume(ownSample) || PredictionExteriorColumns.interiorVolume(otherSample)) {
            if (PredictionExteriorColumns.interiorVolume(ownSample) && PredictionExteriorColumns.interiorVolume(otherSample)) {
                volumeEdge(source, adjacent, index, words, cell, x, z, nx, nz, ownSample, otherSample, true);
                volumeEdge(source, adjacent, index, words, cell, x, z, nx, nz, otherSample, ownSample, false);
            }
            return;
        }
        if (!tile.samples()[PredictionGpuTile.sampleIndexForCell(cell, tile.cellAxis())].hasSurface()
                || !neighbor.samples()[PredictionGpuTile.sampleIndexForCell(neighborCell, neighbor.cellAxis())].hasSurface()) return;
        var ownQuads = tile.mesh().seamMesh();
        var neighborQuads = neighbor.mesh().seamMesh();
        if (!ownQuads.hasTop(cell) || !neighborQuads.hasTop(neighborCell)) return;
        int ownY = Math.round(ownQuads.topY(cell)), otherY = Math.round(neighborQuads.topY(neighborCell));
        if (PredictionExteriorColumns.profiled(ownSample) || PredictionExteriorColumns.profiled(otherSample)) {
            exteriorEdge(source, adjacent, index, words, cell, x, z, nx, nz, ownY, otherY, true);
            exteriorEdge(source, adjacent, index, words, cell, x, z, nx, nz, otherY, ownY, false);
        } else if (ownY != otherY) {
            exteriorEdge(source, adjacent, index, words, cell, x, z, nx, nz,
                    Math.max(ownY, otherY), Math.min(ownY, otherY), ownY > otherY);
        }
    }

    private static void exteriorEdge(Surface source, Surface adjacent, Index index, IntArrayList words,
            int cell, int x, int z, int nx, int nz, int top, int neighborY, boolean own) {
        var tile = source.tile();
        int step = tile.spacingBlocks();
        var higher = own ? tile : adjacent.tile();
        var other = own ? adjacent.tile() : tile;
        int wx = tile.baseBlockX() + x * step, wz = tile.baseBlockZ() + z * step;
        int neighborCell = cellAt(adjacent.tile(), wx + (nx < 0 ? -1 : nx > 0 ? step : step / 2),
                wz + (nz < 0 ? -1 : nz > 0 ? step : step / 2));
        int higherCell = own ? cell : neighborCell, otherCell = own ? neighborCell : cell;
        var sample = higher.samples()[PredictionGpuTile.sampleIndexForCell(higherCell, higher.cellAxis())];
        var otherSample = other.samples()[PredictionGpuTile.sampleIndexForCell(otherCell, other.cellAxis())];
        int tint = higher.mesh().seamMesh().topColor(higherCell);
        int normalX = own ? nx : -nx, normalZ = own ? nz : -nz;
        int face = normalX > 0 ? 4 : normalX < 0 ? 3 : normalZ > 0 ? 2 : 1;
        int ax = (x + (nx > 0 ? 1 : 0)) * step, az = (z + (nz > 0 ? 1 : 0)) * step;
        int bx = ax + (nz != 0 ? step : 0), bz = az + (nx != 0 ? step : 0);
        var gaps = new ArrayList<>(PredictionWallEvidence.exposed(sample, otherSample, top, neighborY,
                higher.spacingBlocks(), other.spacingBlocks()));
        index.subtractWalls(gaps, source, tile.baseBlockX() + ax, tile.baseBlockZ() + az, step, normalX, normalZ);
        index.subtractWalls(gaps, adjacent, tile.baseBlockX() + ax, tile.baseBlockZ() + az, step, normalX, normalZ);
        for (HeightSpan gap : gaps) {
            band(words, tile, cell, ax, az, bx, bz, Math.min(top, gap.top()), Math.max(top - 1, gap.bottom()),
                    normalX, normalZ, PredictionMaterialPalette.groundBlock(sample), face, tint, sample, true);
            band(words, tile, cell, ax, az, bx, bz, Math.min(top - 1, gap.top()), Math.max(top - 2, gap.bottom()),
                    normalX, normalZ, PredictionMaterialPalette.wallUnderBlock(sample), face, tint, sample);
            band(words, tile, cell, ax, az, bx, bz, Math.min(top - 2, gap.top()), gap.bottom(),
                    normalX, normalZ, PredictionMaterialPalette.wallDeepBlock(sample), face, tint, sample);
        }
    }

    static void band(IntArrayList out, PredictionTile tile, int cell, int ax, int az, int bx, int bz,
                             int top, int bottom, int nx, int nz, int block, int face, int tint, ClientColumnSample sample) {
        band(out, tile, cell, ax, az, bx, bz, top, bottom, nx, nz, block, face, tint, sample, false);
    }

    static void band(IntArrayList out, PredictionTile tile, int cell, int ax, int az, int bx, int bz,
            int top, int bottom, int nx, int nz, int block, int face, int tint, ClientColumnSample sample,
            boolean groundBand) {
        if (bottom >= top) return;
        int shift = 0;
        while ((tile.spanBlocks() >> shift) > 65535) shift++;
        var state = groundBand ? PredictionMaterialPalette.groundSideState(sample) : null;
        int sprite = groundBand ? VssLodSpriteTable.indexForState(state, face)
                : block == ClientColumnSample.NO_BLOCK ? 0 : VssLodSpriteTable.sideIndexForBlock(block, face);
        if (sprite == VssLodSpriteTable.FLAT) sprite = 0;
        int color = (groundBand ? PredictionMaterialPalette.colorForState(state, tint, 0, face)
                : PredictionMaterialPalette.colorForIndex(block, tint, face)) & 0xFFFFFF;
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

    private static void volumeEdge(Surface source, Surface adjacent, Index index, IntArrayList words,
            int cell, int x, int z, int nx, int nz, ClientColumnSample solid, ClientColumnSample other, boolean own) {
        var tile = source.tile();
        int step = tile.spacingBlocks(), dx = own ? nx : -nx, dz = own ? nz : -nz;
        int ax = (x + (nx > 0 ? 1 : 0)) * step, az = (z + (nz > 0 ? 1 : 0)) * step;
        int bx = ax + (nz != 0 ? step : 0), bz = az + (nx != 0 ? step : 0);
        var volume = solid.volume();
        for (int i = 0; i < volume.size(); i++) {
            if (volume.fluid(i) != 0) continue;
            var gaps = new ArrayList<HeightSpan>();
            int cursor = volume.bottom(i), top = volume.top(i);
            for (int j = 0; j < other.volume().size() && cursor < top; j++) {
                var v = other.volume();
                if (v.fluid(j) != 0 || v.top(j) <= cursor) continue;
                if (v.bottom(j) >= top) break;
                if (v.bottom(j) > cursor) gaps.add(new HeightSpan(cursor, v.bottom(j)));
                cursor = Math.max(cursor, v.top(j));
            }
            if (cursor < top) gaps.add(new HeightSpan(cursor, top));
            int worldX = tile.baseBlockX() + ax, worldZ = tile.baseBlockZ() + az;
            index.subtractWalls(gaps, source, worldX, worldZ, step, dx, dz);
            index.subtractWalls(gaps, adjacent, worldX, worldZ, step, dx, dz);
            for (var gap : gaps) band(words, tile, cell, ax, az, bx, bz, gap.top(), gap.bottom(), dx, dz,
                    volume.block(i), dx > 0 ? 4 : dx < 0 ? 3 : dz > 0 ? 2 : 1, 0xff804030, solid);
        }
    }

    private static int pair(int a, int b) { return (a & 65535) | (b & 65535) << 16; }
    private static long key(int x, int z) { return (long) x << 32 | z & 0xFFFFFFFFL; }
    static int cellAt(PredictionTile tile, int x, int z) {
        return Math.floorDiv(z - tile.baseBlockZ(), tile.spacingBlocks()) * tile.cellAxis()
                + Math.floorDiv(x - tile.baseBlockX(), tile.spacingBlocks());
    }

    static final class Index {
        private Map<PredictionTileKey, byte[]> boundaryMasks = Map.of();
        private final SortedMap<Integer, Long2ObjectOpenHashMap<Surface>> levels = new TreeMap<>();
        private int[] spans = new int[0];
        private Long2ObjectOpenHashMap<Surface>[] levelTables;
        private boolean levelsChanged=true;
        private final PredictionSpatialIndex<Surface> spatial=new PredictionSpatialIndex<>();
        void subtractWalls(List<HeightSpan> gaps, Surface surface, int x, int z, int length, int nx, int nz) {
            if (surface == null || gaps.isEmpty()) return;
            surface.tile().mesh().seamMesh().subtract(gaps, surface, x, z, length, nx, nz,
                    boundaryMasks.get(surface.tile().key()));
        }
        Index(Collection<Surface> surfaces) { for(var surface:surfaces) put(surface); }
        void put(Surface surface) {
            var tile=surface.tile();
            var table=levels.get(tile.spanBlocks());
            if(table==null) { table=new Long2ObjectOpenHashMap<>();levels.put(tile.spanBlocks(),table);levelsChanged=true; }
            table.put(key(tile.key().tileX(),tile.key().tileZ()),surface);
            spatial.put(tile.key(),surface);
        }
        void remove(PredictionTileKey tile) {
            int span=VssLodLayout.BASE_TILE_BLOCKS<<tile.lod();
            var table=levels.get(span);
            if(table!=null) {
                table.remove(key(tile.tileX(),tile.tileZ()));
                if(table.isEmpty()) { levels.remove(span);levelsChanged=true; }
            }
            spatial.remove(tile);
        }
        void clear() {
            levels.clear();spatial.clear();spans=new int[0];levelTables=null;
            levelsChanged=true;boundaryMasks=Map.of();
        }
        @SuppressWarnings("unchecked")
        private void refreshLevels() {
            if(!levelsChanged) return;
            spans=levels.keySet().stream().mapToInt(Integer::intValue).toArray();
            levelTables=levels.values().toArray(new Long2ObjectOpenHashMap[0]);
            levelsChanged=false;
        }
        Surface at(int x, int z) {
            refreshLevels();
            for (int i = 0; i < spans.length; i++) {
                int span = spans[i];
                Surface surface = levelTables[i].get(key(Math.floorDiv(x, span), Math.floorDiv(z, span)));
                if (surface != null && surface.allowed()[cellAt(surface.tile(), x, z)]) return surface;
            }
            return null;
        }
    }
}
