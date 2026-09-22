package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

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
    private record Cached(Input input, PredictionPackedMesh mesh, PredictionLodSeams.Patch patch) { }
    private final Map<PredictionTileKey,Cached> cache=new LinkedHashMap<>();
    private final Map<PredictionTileKey,PredictionLodSeams.Patch> patchByKey=new LinkedHashMap<>();
    private final Map<PredictionTileKey,PredictionLodSeams.Surface> previous=new HashMap<>();
    private final PredictionLodSeams.Index adapterIndex=new PredictionLodSeams.Index(List.of());
    private final PredictionSpatialIndex<List<Edge>> edgeIndex=new PredictionSpatialIndex<>();
    private final Map<PredictionTileKey,LinkedHashSet<Edge>> grouped=new HashMap<>();
    private final IdentityHashMap<Edge,PredictionTileKey> owners=new IdentityHashMap<>();
    private final IdentityHashMap<Edge,Integer> edgeOrder=new IdentityHashMap<>();
    private List<Edge> previousEdges=List.of();
    private List<PredictionLodSeams.Patch> patches=List.of();
    private long examinedEdges;
    long examinedEdges() { return examinedEdges; }

    List<PredictionLodSeams.Patch> update(List<PredictionLodSeams.Surface> surfaces,List<Edge> edges) {
        var changed=new HashSet<PredictionTileKey>();
        var removed=new HashSet<>(previous.keySet());
        for(var surface:surfaces) {
            var key=surface.tile().key();removed.remove(key);
            var old=previous.get(key);
            if(old==null || old.tile()!=surface.tile() || !Arrays.equals(old.allowed(),surface.allowed())) {
                previous.put(key,surface);adapterIndex.put(surface);changed.add(key);
            }
        }
        for(var key:removed) { previous.remove(key);adapterIndex.remove(key);changed.add(key); }
        var delta=apply(adapterIndex,changed,edges);
        if(!delta.patches().isEmpty() || !delta.removed().isEmpty()) patches=List.copyOf(patchByKey.values());
        return patches;
    }

    PredictionLodSeams.Changes apply(PredictionLodSeams.Index index,Set<PredictionTileKey> changed,List<Edge> edges) {
        boolean newEdges=edges!=previousEdges && !edges.equals(previousEdges);
        if(changed.isEmpty() && !newEdges) return PredictionLodSeams.Changes.EMPTY;
        var touched=new LinkedHashSet<PredictionTileKey>();
        Collection<Edge> pending;
        if(newEdges) {
            touched.addAll(grouped.keySet());grouped.clear();owners.clear();edgeIndex.clear();edgeOrder.clear();
            var cells=new HashMap<PredictionTileKey,List<Edge>>();
            for(var edge:edges) {
                edgeOrder.put(edge,edgeOrder.size());
                var key=new PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,
                        Math.floorDiv(edge.x()+edge.nx(),64),Math.floorDiv(edge.z()+edge.nz(),64),0);
                cells.computeIfAbsent(key,unused->new ArrayList<>()).add(edge);
            }
            cells.forEach(edgeIndex::put);pending=edges;previousEdges=edges;
        } else {
            var nearby=Collections.newSetFromMap(new IdentityHashMap<Edge,Boolean>());
            for(var key:changed) {
                long span=64L<<key.lod(),x=key.tileX()*span,z=key.tileZ()*span;
                edgeIndex.intersect(x,z,x+span,z+span,nearby::addAll);
            }
            pending=nearby;
        }
        for(var edge:pending) {
            examinedEdges++;
            var old=owners.remove(edge);
            if(old!=null) {
                touched.add(old);
                var group=grouped.get(old);
                if(group!=null) { group.remove(edge);if(group.isEmpty()) grouped.remove(old); }
            }
            var surface=index.at(edge.x()+edge.nx(),edge.z()+edge.nz());
            if(surface==null || surface.tile().spanBlocks()>65535) continue;
            var key=surface.tile().key();owners.put(edge,key);touched.add(key);
            grouped.computeIfAbsent(key,unused->new LinkedHashSet<>()).add(edge);
        }
        var upserts=new LinkedHashMap<PredictionTileKey,PredictionLodSeams.Patch>();
        var removed=new HashSet<PredictionTileKey>();
        for(var key:touched) {
            var group=grouped.get(key);
            if(group==null || group.isEmpty()) {
                cache.remove(key);if(patchByKey.remove(key)!=null) removed.add(key);continue;
            }
            var first=group.iterator().next();
            var surface=index.at(first.x()+first.nx(),first.z()+first.nz());
            var ordered=new ArrayList<>(group);ordered.sort(Comparator.comparingInt(edgeOrder::get));
            var boundary=List.copyOf(ordered);
            var old=cache.get(key);
            // Mask changes can alter which existing wall interval must be subtracted.
            if(old!=null && old.input().surface()==surface && old.input().edges().equals(boundary)
                    && !changed.contains(key)) continue;
            var words=new IntArrayList();
            for(var edge:boundary) emit(words,surface,edge,index);
            var mesh=PredictionPackedMesh.terrainRecords(words.toIntArray(),surface.tile().cellAxis());
            var patch=mesh.quadCount()==0?null:new PredictionLodSeams.Patch(surface,mesh);
            cache.put(key,new Cached(new Input(surface,boundary),mesh,patch));
            if(patch==null) { if(patchByKey.remove(key)!=null) removed.add(key); }
            else { patchByKey.put(key,patch);upserts.put(key,patch); }
        }
        return new PredictionLodSeams.Changes(upserts,removed,Set.of(),Set.of());
    }

    void clear() {
        cache.clear();patchByKey.clear();previous.clear();adapterIndex.clear();edgeIndex.clear();
        grouped.clear();owners.clear();edgeOrder.clear();previousEdges=List.of();patches=List.of();
    }

    private static void emit(IntArrayList words, PredictionLodSeams.Surface surface, Edge edge, PredictionLodSeams.Index index) {
        var tile = surface.tile();
        int cell = PredictionLodSeams.cellAt(tile, edge.x() + edge.nx(), edge.z() + edge.nz());
        var predicted = tile.samples()[PredictionGpuTile.sampleIndexForCell(cell, tile.cellAxis())];
        var seams = tile.mesh().seamMesh();
        if (!seams.hasTop(cell) || !predicted.hasSurface() || !edge.ground().hasSurface()) return;
        int predictedY = Math.round(seams.topY(cell));
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
        int tint = seams.topColor(cell);
        int first = words.size();
        var gaps = new ArrayList<>(PredictionWallEvidence.intervals(sample, bottom, top,
                realHigher ? 1 : tile.spacingBlocks()));
        if (!realHigher) index.subtractWalls(gaps, surface, x + tile.baseBlockX(), z + tile.baseBlockZ(), 1, nx, nz);
        for (var gap : gaps) {
            PredictionLodSeams.band(words, tile, cell, x, z, x + (nz != 0 ? 1 : 0), z + (nx != 0 ? 1 : 0),
                    Math.min(top, gap.top()), Math.max(gap.bottom(), top - 1), nx, nz, block, face, tint, sample, true);
            PredictionLodSeams.band(words, tile, cell, x, z, x + (nz != 0 ? 1 : 0), z + (nx != 0 ? 1 : 0),
                    Math.min(top - 1, gap.top()), Math.max(gap.bottom(), top - 2), nx, nz, under, face, tint, sample);
            PredictionLodSeams.band(words, tile, cell, x, z, x + (nz != 0 ? 1 : 0), z + (nx != 0 ? 1 : 0),
                    Math.min(top - 2, gap.top()), gap.bottom(), nx, nz, deep, face, tint, sample);
        }
        for (int i = first; i < words.size(); i += 12)
            words.set(i + 9, words.getInt(i + 9) | REAL_BOUNDARY | (realHigher ? 0 : REAL_LOWER));
    }
}
