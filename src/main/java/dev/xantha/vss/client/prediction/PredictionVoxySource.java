package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.compat.XaeroPredictionBridge;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable column runs shared by Voxy's mesh workers. Air/unknown never become stored world data. */
final class PredictionVoxySource implements VoxyPredictionBridge.Source, XaeroPredictionBridge.Source, AutoCloseable {
    record Run(int bottom, int top, BlockState state) { }
    record Column(List<Run> runs, String biome) { }
    record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, BlockState state) { }
    record Tile(PredictionTileKey key, int baseX, int baseZ, int span, int axis, int step,
                Column[] columns, List<Box> features, long bytes, boolean surfaceComplete, int cityFeatures,
                PredictionVoxyFeatures featureIndex) {
        Tile(PredictionTileKey key,int baseX,int baseZ,int span,int axis,int step,Column[] columns,
             List<Box> features,long bytes,boolean surfaceComplete,int cityFeatures) {
            this(key,baseX,baseZ,span,axis,step,columns,features,bytes,surfaceComplete,cityFeatures,PredictionVoxyFeatures.of(features));
        }
        @Override public long bytes() {return bytes+featureIndex.bytes();}
        Tile(PredictionTileKey key, int baseX, int baseZ, int span, int axis, int step,
             Column[] columns, List<Box> features, long bytes) {
            this(key, baseX, baseZ, span, axis, step, columns, features, bytes, false, 0);
        }

        /** Terrain refinement does not prove that previously visible plants disappeared.
         * The caller excludes dirty/content-invalidated records before retaining them. */
        Tile retainFeatures(Tile previous) {
            if (surfaceComplete || previous == null || !key.equals(previous.key)
                    || baseX != previous.baseX || baseZ != previous.baseZ || span != previous.span
                    || !previous.surfaceComplete && (hasVegetation() || !previous.hasVegetation())) return this;
            var retained = new ArrayList<>(features.subList(0, cityFeatures));
            retained.addAll(previous.features.subList(previous.cityFeatures, previous.features.size()));
            return new Tile(key, baseX, baseZ, span, axis, step, columns, List.copyOf(retained),
                    bytes + (retained.size() - (long) features.size()) * 64L, previous.surfaceComplete, cityFeatures);
        }

        boolean hasVegetation() { return features.size() > cityFeatures; }

        Tile withVegetation(PredictionVegetation.Tile plants, dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities) {
            var completed = new ArrayList<>(features.subList(0, cityFeatures));
            vegetation(completed, plants, cities, baseX, baseZ);
            return new Tile(key, baseX, baseZ, span, axis, step, columns, List.copyOf(completed),
                    bytes + (completed.size() - (long) features.size()) * 64L, true, cityFeatures);
        }
    }
    private final Map<PredictionTileKey, Tile> tiles = new HashMap<>();
    private final Map<Integer, Map<Long, Tile>> spatial = new HashMap<>();
    private long mapRevision;
    private long tileBytes;
    private final Map<PredictionTileKey, Long> mapVersions = new HashMap<>();

    @Override public synchronized long mapRevision() { return mapRevision; }
    @Override public synchronized List<XaeroPredictionBridge.Area> mapAreas() {
        return tiles.values().stream().sorted(Comparator.comparingInt(Tile::step)
                .thenComparingDouble(t -> Math.pow(t.baseX()+t.span()*.5-cameraX,2)
                        + Math.pow(t.baseZ()+t.span()*.5-cameraZ,2))).map(t ->
                new XaeroPredictionBridge.Area(t.key(), Math.floorDiv(t.baseX(),16), Math.floorDiv(t.baseZ(),16),
                        Math.floorDiv(t.baseX()+t.span()-1,16)+1, Math.floorDiv(t.baseZ()+t.span()-1,16)+1,
                        mapVersions.get(t.key()))).toList();
    }

    /** Snapshot under the source lock; projecting the surface never holds it or maps Voxy IDs. */
    @Override public XaeroPredictionBridge.Surface mapChunk(int chunkX, int chunkZ) {
        int bx=chunkX*16, bz=chunkZ*16;
        List<Tile> snapshot;
        synchronized (this) { if (closed) return null; snapshot=intersect(bx,bz,16); }
        if (snapshot.isEmpty()) return null;
        var featureTiles=snapshot.stream().filter(t -> t.surfaceComplete() || t.hasVegetation())
                .sorted(Comparator.comparing(t -> !t.surfaceComplete())).toList();
        // Cull boxes once per chunk; do not walk an entire distant forest for each map pixel.
        record Feature(Box box, boolean city) { }
        var boxes=new IdentityHashMap<Tile,List<Feature>>();
        for (Tile t:snapshot) {
            var selected=new ArrayList<Feature>();
            for (int i=0;i<t.features().size();i++) {
                Box b=t.features().get(i);
                if (t.baseX()+b.minX()<bx+16 && t.baseX()+b.maxX()>bx
                        && t.baseZ()+b.minZ()<bz+16 && t.baseZ()+b.maxZ()>bz)
                    selected.add(new Feature(b,i<t.cityFeatures()));
            }
            boxes.put(t,selected);
        }
        var pixels=new XaeroPredictionBridge.Pixel[256];
        var terrainPixels=new IdentityHashMap<Column,XaeroPredictionBridge.Pixel>();
        int terrainStep=0, featureStep=0; boolean complete=true;
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) {
            int wx=bx+x,wz=bz+z;
            Tile owner=null,plants=null;
            for (Tile t:snapshot) if (covers(t,wx,wz)) { owner=t; break; }
            if (owner==null) return null; // never turn unknown portions of a chunk into saved air
            for (Tile t:featureTiles) if (covers(t,wx,wz)) { plants=t; break; }
            int cx=Math.floorDiv(wx-owner.baseX(),owner.step()), cz=Math.floorDiv(wz-owner.baseZ(),owner.step());
            Column column=owner.columns()[cz*owner.axis()+cx];
            var base=terrainPixels.computeIfAbsent(column,c -> new XaeroPredictionBridge.Pixel(c.runs().stream()
                    .map(r -> new XaeroPredictionBridge.Run(r.bottom(),r.top(),r.state())).toList(),c.biome()));
            ArrayList<XaeroPredictionBridge.Run> runs=null;
            // Match Voxy's terrain/city owner and independent completed vegetation owner.
            for (Tile t:snapshot) for (Feature f:boxes.get(t)) {
                Box b=f.box();
                if ((f.city() ? owner : plants)!=t) continue;
                if (wx>=t.baseX()+b.minX() && wx<t.baseX()+b.maxX()
                        && wz>=t.baseZ()+b.minZ() && wz<t.baseZ()+b.maxZ()) {
                    if(runs==null) runs=new ArrayList<>(base.runs());
                    runs.add(new XaeroPredictionBridge.Run(b.minY(),b.maxY(),b.state()));
                }
            }
            pixels[x*16+z]=runs==null ? base : new XaeroPredictionBridge.Pixel(List.copyOf(runs),column.biome());
            terrainStep=Math.max(terrainStep,owner.step());
            featureStep=Math.max(featureStep,plants==null ? Integer.MAX_VALUE : plants.step());
            complete &= plants!=null && plants.surfaceComplete();
        }
        return new XaeroPredictionBridge.Surface(pixels,
                new XaeroPredictionBridge.Quality(terrainStep,featureStep,complete), () -> {
                    synchronized (this) { return !closed && snapshot.equals(intersect(bx,bz,16)); }
                });
    }
    private static boolean covers(Tile t,int x,int z) {
        return x>=t.baseX() && z>=t.baseZ() && x<(long)t.baseX()+t.span() && z<(long)t.baseZ()+t.span();
    }
    record Detail(int x, int z, int lod) {
        @Override public int hashCode() { return PredictionKeyHash.hash(x,z,lod); }
    }
    // Requests come from the node thread, never call the manager while holding this lock.
    // Y requests share one horizontal sampling job. Keep detail until leaving the horizon.
    private final PredictionVoxyDetailQueue details = new PredictionVoxyDetailQueue(4096);
    private Set<Detail> detailSnapshot = Set.of();
    private boolean detailChanged;
    private double cameraX, cameraZ;
    private int radius = VssLodLayout.MAX_DISTANCE_BLOCKS;
    private PredictionWorkView detailView;
    private VssLodLayout detailLayout;
    private VssLodFocus detailFocus;
    private double detailMinY,detailMaxY;
    private double detailPriorityX=Double.NaN, detailPriorityZ=Double.NaN;
    // Mapped snapshots are disposable, bounded and scoped to the actual mapper identity.
    // Compact sections are governed by bytes; a low entry ceiling needlessly evicts tiny runs.
    private static final int MAX_MAPPED = 32768;
    private static final long MAX_MAPPED_BYTES = 128L * 1024 * 1024;
    private static final java.util.concurrent.ArrayBlockingQueue<long[]> SECTION_BUFFERS =
            new java.util.concurrent.ArrayBlockingQueue<>(4);
    private long mappedBytes;
    private static final long MAX_COLUMN_BYTES=16L*1024*1024;
    private record ColumnKey(Column[] columns,int axis,VoxyPredictionBridge.Mapper mapper,int step,int minX,int minZ,int maxX,int maxZ) {
        @Override public boolean equals(Object other) {
            return other instanceof ColumnKey k && columns==k.columns && axis==k.axis && mapper==k.mapper && step==k.step && minX==k.minX
                    && minZ==k.minZ && maxX==k.maxX && maxZ==k.maxZ;
        }
        @Override public int hashCode() {
            int footprint=PredictionKeyHash.hash(minX,minZ,step)
                    ^ Integer.rotateLeft(PredictionKeyHash.hash(maxX,maxZ,axis),13);
            return footprint ^ (System.identityHashCode(columns)*31+System.identityHashCode(mapper));
        }
    }
    private record CachedColumn(PredictionVoxyColumnRuns runs,VoxyPredictionBridge.Retention lease) {
        long bytes() {return runs.bytes()+384L;}
    }
    private final java.util.concurrent.ConcurrentHashMap<ColumnKey,CachedColumn> reducedColumns=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<ColumnKey,java.util.concurrent.CompletableFuture<PredictionVoxyColumnRuns>>
            reducingColumns=new java.util.concurrent.ConcurrentHashMap<>();
    // Admission/retirement owns the source lock; hits read immutable entries without it.
    private final LinkedHashSet<ColumnKey> columnOrder=new LinkedHashSet<>();
    private final IdentityHashMap<Column[],Set<ColumnKey>> tileColumns=new IdentityHashMap<>();
    private long columnBytes;
    private static final long MAX_FACE_BYTES=8L*1024*1024,FACE_BYTES=8448;
    private record FaceKey(long position,int face) { }
    private static final class FaceBuild {
        final java.util.concurrent.CompletableFuture<long[]> result=new java.util.concurrent.CompletableFuture<>();
        VoxyPredictionBridge.Retention retention;
    }
    private final LinkedHashMap<FaceKey,FaceBuild> mappedFaces=new LinkedHashMap<>(64,.75f,true);
    private long faceBytes,faceCacheHits,faceCacheEvictions;
    private final java.util.concurrent.atomic.LongAdder columnBuilds=new java.util.concurrent.atomic.LongAdder(),
            columnHits=new java.util.concurrent.atomic.LongAdder(), faceBuilds=new java.util.concurrent.atomic.LongAdder(),
            directWrites=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder columnBuildNanos=new java.util.concurrent.atomic.LongAdder(),
            columnWaitNanos=new java.util.concurrent.atomic.LongAdder(), sectionBuildNanos=new java.util.concurrent.atomic.LongAdder(),
            sectionWaitNanos=new java.util.concurrent.atomic.LongAdder(), faceBuildNanos=new java.util.concurrent.atomic.LongAdder();
    private long invalidatedBuilds, mapperRetirements, columnEvictions;
    private long unchangedPublications, retainedColumnPublications;
    private static final dev.xantha.vss.compat.VoxySectionData DARK_EMPTY = dev.xantha.vss.compat.VoxySectionData.compact(new long[32768]);
    private static final dev.xantha.vss.compat.VoxySectionData EMPTY;
    static { long[] data = new long[32768]; Arrays.fill(data, 15L << 56);
        EMPTY = dev.xantha.vss.compat.VoxySectionData.compact(data); }
    private static final class SectionBuild {
        final java.util.concurrent.CompletableFuture<dev.xantha.vss.compat.VoxySectionData> result = new java.util.concurrent.CompletableFuture<>();
        long bytes;
        VoxyPredictionBridge.Retention retention;
    }
    private final Map<Long, SectionBuild> mapped = new HashMap<>();
    // One-shot traversal/notification reads cannot evict the repeatedly consumed
    // center and border sections. Both segments share the existing byte limit.
    private final LinkedHashMap<Long, SectionBuild> probation = new LinkedHashMap<>();
    private final LinkedHashMap<Long, SectionBuild> protectedMapped = new LinkedHashMap<>(64,.75f,true);
    private final int mappedLimit;
    PredictionVoxySource() {this(MAX_MAPPED);}
    PredictionVoxySource(int mappedLimit) {
        if(mappedLimit<2 || mappedLimit>MAX_MAPPED)throw new IllegalArgumentException("mappedLimit");
        this.mappedLimit=mappedLimit;
    }
    private void touchMapped(long pos,SectionBuild build) {
        if(protectedMapped.get(pos)!=null)return;
        probation.remove(pos);protectedMapped.put(pos,build);
        if(protectedMapped.size()>mappedLimit/2) {
            var iterator=protectedMapped.entrySet().iterator();var oldest=iterator.next();iterator.remove();
            probation.put(oldest.getKey(),oldest.getValue());
        }
    }
    private void evictMapped() {
        var segment=probation.isEmpty()?protectedMapped:probation;
        var iterator=segment.entrySet().iterator();var oldest=iterator.next();iterator.remove();
        mapped.remove(oldest.getKey());mappedEvictions++;releaseMapped(oldest.getValue());
    }
    private void removeMapped(long pos) {
        probation.remove(pos);protectedMapped.remove(pos);releaseMapped(mapped.remove(pos));
    }
    private record Extent(int terrainMin, int terrainMax, int featureMin, int featureMax, int lightMin, int lightMax) {
        int min() { return Math.min(terrainMin, featureMin); }
        int max() { return Math.max(terrainMax, featureMax); }
    }
    private final Map<PredictionTileKey, Extent> extents = new HashMap<>();
    private final java.util.concurrent.atomic.LongAdder cacheHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder sectionBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder lightingBuilds = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder coalesced = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder emptyReads = new java.util.concurrent.atomic.LongAdder();
    private long mappedEvictions, mappedInvalidations;
    private long meshDetailChecks, meshDetailMisses;
    // Mesh-only demand cannot rely on another subdivision/readback (especially native LOD 0).
    // Keep its horizontal identity through pending-queue pressure, until publication or horizon retirement.
    private final LinkedHashSet<Detail> meshDetails = new LinkedHashSet<>();
    private VoxyPredictionBridge.Mapper lastMapper;
    private boolean closed;
    private volatile boolean detailLoading = true;
    void detailLoading(boolean allowed) { detailLoading = allowed; }
    private PredictionMemoryBudget budget;
    synchronized void budget(PredictionMemoryBudget value) { budget = value; }

    synchronized void window(double x, double z, int distance) {
        if (x == cameraX && z == cameraZ && distance == radius) return;
        boolean boundaryChanged = distance != radius
                || Math.floor((x-distance)/64) != Math.floor((cameraX-radius)/64)
                || Math.floor((x+distance)/64) != Math.floor((cameraX+radius)/64)
                || Math.floor((z-distance)/64) != Math.floor((cameraZ-radius)/64)
                || Math.floor((z+distance)/64) != Math.floor((cameraZ+radius)/64);
        cameraX = x; cameraZ = z; radius = distance;
        if (boundaryChanged) {
            java.util.function.Predicate<Detail> expired=d -> outside((long)d.x()*(64L<<d.lod()),
                    (long)d.z()*(64L<<d.lod()),64L<<d.lod());
            if (details.removeIf(expired)) detailChanged=true;
            meshDetails.removeIf(expired);
        }
    }

    synchronized void detailPriority(PredictionWorkView view,VssLodLayout layout,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            VssLodFocus focus,double minY,double maxY) {
        details.pollPriority();
        double x=Math.floor(cameraX/64)*64,z=Math.floor(cameraZ/64)*64;
        if(x==detailPriorityX && z==detailPriorityZ
                && layout.equals(detailLayout) && Objects.equals(focus,detailFocus)
                && minY==detailMinY && maxY==detailMaxY
                && (view==null ? detailView==null : !view.changedFrom(detailView)))return;
        detailView=view;detailPriorityX=x;detailPriorityZ=z;
        detailLayout=layout;detailFocus=focus;detailMinY=minY;detailMaxY=maxY;
        details.priorityAsync(cameraX,cameraZ,key -> view==null ? PredictionWorkView.VISIBLE
                : view.band(new PredictionTileKey(dimension,key.x(),key.z(),key.lod()),layout,focus,minY,maxY));
    }

    synchronized Set<Detail> detailRequests() {
        if (detailChanged) { detailSnapshot = details.keys(); detailChanged = false; }
        return detailSnapshot;
    }

    synchronized PredictionVoxyDetailQueue.Changes detailChanges() {
        // Retry a bounded round-robin slice even while stationary, without remeshing to get a retry.
        var batch=new ArrayList<Detail>(Math.min(32,meshDetails.size()));
        var iterator=meshDetails.iterator();
        while(iterator.hasNext() && batch.size()<32) {batch.add(iterator.next());iterator.remove();}
        for(var key:batch) {if(detailLoading && details.request(key,false))detailChanged=true;meshDetails.add(key);}
        return details.drainChanges();
    }

    private boolean outside(long bx, long bz, long span) {
        return bx > cameraX + radius || bz > cameraZ + radius
                || bx + span < cameraX - radius || bz + span < cameraZ - radius;
    }

    @Override public synchronized boolean requestDetail(long position, VoxyPredictionBridge.RealColumn real) {
        int lod = VoxyPredictionBridge.level(position);
        if (lod < 1 || lod > 4) return true;
        return requestDetail(lod,VoxyPredictionBridge.x(position),VoxyPredictionBridge.z(position),real,false);
    }

    @Override public synchronized void requestMeshDetail(long position, VoxyPredictionBridge.RealColumn real) {
        int lod=VoxyPredictionBridge.level(position);
        if (closed || lod<0 || lod>4) return;
        meshDetailChecks++;
        // Two native sections per axis share a 64-cell prediction tile. Native leaves can
        // already exist before this overlay arrives, so subdivision is not the only demand source.
        if (!requestDetail(lod+1,VoxyPredictionBridge.x(position)>>1,VoxyPredictionBridge.z(position)>>1,real,true))
            meshDetailMisses++;
    }

    private boolean requestDetail(int lod,int sectionX,int sectionZ,VoxyPredictionBridge.RealColumn real,boolean meshRequest) {
        if (closed) return true;
        int span = 32 << lod, spacing = 1 << (lod - 1);
        long bx = (long)sectionX * span, bz = (long)sectionZ * span;
        if (outside(bx, bz, span)) return true;
        var key = new Detail(sectionX, sectionZ, lod - 1);
        var cells=spatial.get(span);
        Tile exact=cells==null ? null : cells.get(pack(sectionX,sectionZ));
        if (exact!=null && exact.step()<=spacing) {
            meshDetails.remove(key);
            if(details.request(key,true))detailChanged=true;
            return true;
        }
        var candidates = intersect(bx, bz, span);
        if (candidates.isEmpty()) return true;
        // Tile boundaries align to chunks. A complete precise tile is the common fast path.
        boolean ready = false;
        for (Tile t : candidates) if (t.step() <= spacing && t.baseX() <= bx && t.baseZ() <= bz
                && t.baseX() + (long)t.span() >= bx + span && t.baseZ() + (long)t.span() >= bz + span) {
            ready = true; break;
        }
        if (!ready) {
            ready = true;
            for (long z = bz; ready && z < bz + span; z += 16) for (long x = bx; x < bx + span; x += 16) {
                boolean covered = false;
                for (Tile t : candidates) if (t.step() <= spacing && x >= t.baseX() && z >= t.baseZ()
                        && x < t.baseX() + (long)t.span() && z < t.baseZ() + (long)t.span()) {
                    covered = true; break;
                }
                if (!covered && !real.contains((int)Math.floorDiv(x, 16), (int)Math.floorDiv(z, 16))) { ready = false; break; }
            }
        }
        if (!ready && !detailLoading) {
            if (meshRequest) meshDetails.add(key);
            return false;
        }
        if (ready) meshDetails.remove(key);
        else if (meshRequest) meshDetails.add(key);
        if (details.request(key,ready)) detailChanged = true;
        return ready;
    }

    static Tile build(PredictionTileKey key, ClientColumnSample[] samples, int grid, int step,
                      ClientTerrainSampler sampler, PredictionVegetation.Tile plants,
                      dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities) {
        return build(key, samples, grid, step, sampler, plants, cities, false);
    }

    static Tile build(PredictionTileKey key, ClientColumnSample[] samples, int grid, int step,
                      ClientTerrainSampler sampler, PredictionVegetation.Tile plants,
                      dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities, boolean surfaceComplete) {
        int axis = grid - 2, span = axis * step, bx = key.tileX() * span, bz = key.tileZ() * span;
        Column[] columns = new Column[axis * axis];
        var features = new ArrayList<Box>();
        var names = sampler.cacheBiomes();
        var resolvedBiomes = new HashMap<Holder<Biome>, String>();
        var palette = new PredictionVoxyColumns();
        ClientColumnSample[] display = new ClientColumnSample[(axis+1)*(axis+1)];
        for (int z=0;z<=axis;z++) for(int x=0;x<=axis;x++) display[z*(axis+1)+x]=samples[(z+1)*grid+x+1];
        display = PredictionCityGeometry.ground(display,cities,bx,bz,step,axis+1,null).samples();
        PredictionCityGeometry city = cities == null ? null : new PredictionCityGeometry(cities, bx, bz, axis, step, display);
        for (int z = 0; z < axis; z++) for (int x = 0; x < axis; x++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int cell = z * axis + x;
            var sample = display[z*(axis+1)+x];
            var runs = new ArrayList<Run>();
            terrain(runs, sample, step, sampler.profile().minY());
            if (city != null && !sample.captured()) {
                for (var face : city.faces(cell)) features.add(cityBox(face));
                for (var face : city.overlays(cell)) features.add(cityBox(face));
            }
            String biome = biome(sample, names, sampler, bx + x * step, bz + z * step, resolvedBiomes);
            columns[cell] = palette.column(runs, biome);
        }
        int cityFeatures = features.size();
        vegetation(features, plants, cities, bx, bz);
        return new Tile(key, bx, bz, span, axis, step, columns, List.copyOf(features), palette.bytes(columns.length) + features.size()*64L, surfaceComplete, cityFeatures);
    }

    private static String biome(ClientColumnSample sample, List<String> names, ClientTerrainSampler sampler,
                                int x, int z, Map<Holder<Biome>, String> resolved) {
        int index = sample.biomeIndex();
        if (index >= 0 && index < names.size() && !names.get(index).isEmpty()) return names.get(index);
        // Legacy or external records may lack a biome index. Resolve identity once on
        // the publication worker; Voxy's mapper/model applies the actual biome tint.
        int y = (sample.hasFluid() ? sample.fluidY() : sample.surfaceY()) - 1;
        var holder = sampler.decorationContext().noiseBiome(
                QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
        if (holder == null) return "minecraft:plains";
        return resolved.computeIfAbsent(holder, biome -> biome.unwrapKey()
                .map(key -> key.location().toString()).orElse("minecraft:plains"));
    }

    private static void vegetation(List<Box> features, PredictionVegetation.Tile plants,
            dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities, int bx, int bz) {
        for (var cell : plants.cells().values()) for (var plant : cell) {
            if (cities != null && PredictionCityGeometry.vegetationOwner(cities.atBlock(bx+plant.x(),bz+plant.z()))) continue;
            features.add(new Box(plant.x(),plant.y(),plant.z(),plant.x()+plant.size(),plant.y()+plant.size(),
                    plant.z()+plant.size(),plant.state()));
        }
    }

    static void terrain(List<Run> runs, ClientColumnSample s, int step, int minY) {
        if (s == null) return;
        if (s.volume() != null) {
            for (int i = 0; i < s.volume().size(); i++) {
                int block = s.volume().block(i);
                runs.add(new Run(s.volume().bottom(i), s.volume().top(i), state(block)));
            }
            // Exterior occupancy columns contain generic rock. Keep the actual sampled cap material.
            if (PredictionExteriorColumns.profiled(s) && s.hasSurface())
                runs.add(new Run(s.surfaceY() - 1, s.surfaceY(), state(s.topBlockIndex())));
        } else if (s.hasSurface()) {
            int top = s.surfaceY();
            // SURFACE_ONLY describes sampling provenance, not air below the surface.
            // The exterior heightfield is closed, as in PredictionWallEvidence.intervals.
            // Only explicit spans/volumes prove an underside or an underground air gap.
            // This approximation belongs to the render overlay, never the real world store.
            int bottom = s.floating() ? s.surfaceBottom() : minY;
            add(runs, bottom, Math.max(bottom, top - 4), state(s.deepBlockIndex()));
            add(runs, Math.max(bottom, top - 4), top - 1, state(s.underBlockIndex()));
            add(runs, Math.max(bottom, top - 1), top, state(s.topBlockIndex()));
            if (s.hasLowerSpan()) add(runs, s.lowerBottom(), s.lowerTop(), state(s.deepBlockIndex()));
        }
        if (s.hasFluid()) {
            add(runs, Math.max(minY, s.surfaceY()), s.fluidY(),
                    (s.fluid() == 2 ? Blocks.LAVA : Blocks.WATER).defaultBlockState());
            if (s.ice()) add(runs, s.fluidY() - 1, s.fluidY(), Blocks.ICE.defaultBlockState());
        }
        if (s.snow() && s.hasSurface()) add(runs, s.surfaceY(), s.surfaceY() + 1, Blocks.SNOW.defaultBlockState());
    }
    private static void add(List<Run> target, int bottom, int top, BlockState state) {
        if (top > bottom && state != null && !state.isAir()) target.add(new Run(bottom, top, state));
    }
    private static BlockState state(int id) {
        return id == ClientColumnSample.NO_BLOCK || id < 0 ? Blocks.STONE.defaultBlockState()
                : BuiltInRegistries.BLOCK.byId(id).defaultBlockState();
    }
    private static Box cityBox(PredictionCityGeometry.Face face) {
        int bottom = face.y() - (face.direction() == 0 ? 1 : 0);
        int x = face.x() - (face.direction() == 4 ? 1 : 0), z = face.z() - (face.direction() == 2 ? 1 : 0);
        return new Box(x,bottom,z,x+Math.max(1,face.dx()),bottom+Math.max(1,face.dy()),
                z+Math.max(1,face.dz()),Block.stateById(face.state()));
    }

    private static Extent extent(Tile tile, Tile old, Extent previous) {
        int terrainMin = Integer.MAX_VALUE, terrainMax = Integer.MIN_VALUE;
        if (old != null && previous != null && old.columns() == tile.columns()) {
            terrainMin = previous.terrainMin(); terrainMax = previous.terrainMax();
        } else for (Column column : tile.columns()) for (Run run : column.runs()) {
            terrainMin = Math.min(terrainMin, run.bottom()); terrainMax = Math.max(terrainMax, run.top());
        }
        int featureMin = Integer.MAX_VALUE, featureMax = Integer.MIN_VALUE;
        for (Box box : tile.features()) { featureMin = Math.min(featureMin, box.minY()); featureMax = Math.max(featureMax, box.maxY()); }
        int lightMin = Integer.MAX_VALUE, lightMax = Integer.MIN_VALUE;
        // Summarize emitters once per publication, rather than scan all materials for every section.
        var seen = Collections.newSetFromMap(new IdentityHashMap<Column,Boolean>());
        for (Column column : tile.columns()) if (seen.add(column)) for (Run run : column.runs())
            if (run.state().getLightEmission() > 1) {
                lightMin = Math.min(lightMin,run.bottom()); lightMax = Math.max(lightMax,run.top());
            }
        for (Box box : tile.features()) if (box.state().getLightEmission() > 1) {
            lightMin = Math.min(lightMin,box.minY()); lightMax = Math.max(lightMax,box.maxY());
        }
        return new Extent(terrainMin, terrainMax, featureMin, featureMax, lightMin, lightMax);
    }
    // Ownership changes can reveal a coarser source, including its known air and taller plants.
    private int[] changeHeight(Tile tile, Extent before, Extent after, boolean featuresOnly) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Tile candidate : intersect(tile.baseX(), tile.baseZ(), tile.span())) {
            Extent e = extents.get(candidate.key());
            min = Math.min(min, featuresOnly ? e.featureMin() : e.min());
            max = Math.max(max, featuresOnly ? e.featureMax() : e.max());
        }
        for (Extent e : new Extent[]{before, after}) if (e != null) {
            min = Math.min(min, featuresOnly ? e.featureMin() : e.min());
            max = Math.max(max, featuresOnly ? e.featureMax() : e.max());
        }
        return new int[]{min, max};
    }
    void put(Tile tile) { put(tile, () -> true); }
    private record Footprint(int minX,int minZ,int maxX,int maxZ) {
        static Footprint of(Tile tile) {return new Footprint(tile.baseX(),tile.baseZ(),tile.baseX()+tile.span(),tile.baseZ()+tile.span());}
    }
    private static Footprint changedTerrain(Tile old,Tile tile) {
        int minX=tile.axis(),minZ=tile.axis(),maxX=-1,maxZ=-1;
        for(int i=0;i<tile.columns().length;i++)if(!old.columns()[i].equals(tile.columns()[i])) {
            // A changed anchor biome can recolor a feature beyond the terrain column's footprint.
            if(!old.columns()[i].biome().equals(tile.columns()[i].biome()) && !tile.features().isEmpty())return Footprint.of(tile);
            int x=i%tile.axis(),z=i/tile.axis();
            minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z);
        }
        return new Footprint(tile.baseX()+minX*tile.step(),tile.baseZ()+minZ*tile.step(),
                tile.baseX()+(maxX+1)*tile.step(),tile.baseZ()+(maxZ+1)*tile.step());
    }
    void put(Tile tile, java.util.function.BooleanSupplier current) {
        // Compare immutable input outside the lock. Equal republished samples must not cancel valid builds.
        for(;;) {
        Tile observed;Extent before;
        synchronized(this) {
            if(closed || !current.getAsBoolean())return;
            observed=tiles.get(tile.key());if(observed==tile) {unchangedPublications++;return;}
            before=extents.get(tile.key());
        }
        boolean sameShape=observed!=null && observed.baseX()==tile.baseX() && observed.baseZ()==tile.baseZ()
                && observed.span()==tile.span() && observed.axis()==tile.axis() && observed.step()==tile.step();
        boolean sameColumns=sameShape && Arrays.equals(observed.columns(),tile.columns());
        boolean sameFeatures=sameShape && observed.surfaceComplete()==tile.surfaceComplete()
                && observed.cityFeatures()==tile.cityFeatures() && observed.features().equals(tile.features());
        boolean sameContent=sameColumns && sameFeatures;
        Footprint affected=!sameColumns && sameFeatures ? changedTerrain(observed,tile) : Footprint.of(tile);
        // Preserve reduced-column identities when only decoration/completion changed.
        if(sameColumns && observed.columns()!=tile.columns())tile=new Tile(tile.key(),tile.baseX(),tile.baseZ(),tile.span(),
                tile.axis(),tile.step(),observed.columns(),tile.features(),tile.bytes,tile.surfaceComplete(),tile.cityFeatures(),tile.featureIndex());
        Extent prepared = sameContent ? before : extent(tile, observed, before);
        int[] height;
        synchronized (this) {
            if (closed || !current.getAsBoolean()) return;
            Tile old = tiles.get(tile.key()); if(old!=observed)continue;
            if(sameContent) {unchangedPublications++;return;}
            if(sameColumns)retainedColumnPublications++;
            if(old!=null && old.columns()!=tile.columns())retireColumns(old);
            Extent after = prepared;
            height = changeHeight(tile, before, after, old != null && old.columns() == tile.columns());
            tiles.put(tile.key(), tile); extents.put(tile.key(), after);
            // Publication is the completion event. Waiting for a later native retry keeps
            // finished work in the bounded pending queue and evicts useful new demand.
            int lod=tile.key().lod();
            if (tile.span()==(64L<<lod) && tile.step()<=(1<<lod)) {
                meshDetails.remove(new Detail(tile.key().tileX(),tile.key().tileZ(),lod));
                details.request(new Detail(tile.key().tileX(),tile.key().tileZ(),lod),true);
            }
            tileBytes += tile.bytes() - (old == null ? 0 : old.bytes());
            mapVersions.put(tile.key(), ++mapRevision);
            spatial.computeIfAbsent(tile.span(), ignored -> new HashMap<>()).put(pack(tile.key().tileX(),tile.key().tileZ()),tile);
            invalidate(affected, height[0], height[1]);
        }
        notifyChange(affected, height);
        return;
        }
    }
    void remove(PredictionTileKey key) {
        Tile old; int[] height;
        synchronized (this) {
            old = tiles.get(key); if (old == null) return;
            retireColumns(old);
            height = changeHeight(old, extents.get(key), null, false);
            tiles.remove(key); tileBytes -= old.bytes(); extents.remove(key); invalidate(old, height[0], height[1]);
            if(details.releaseResident(new Detail(key.tileX(),key.tileZ(),key.lod())))detailChanged=true;
            mapVersions.remove(key); ++mapRevision;
            var cells=spatial.get(old.span()); cells.remove(pack(key.tileX(),key.tileZ()));
            if(cells.isEmpty()) spatial.remove(old.span());
        }
        notifyChange(old, height);
    }
    private void notifyChange(Tile tile, int[] height) {
        notifyChange(Footprint.of(tile),height);
    }
    private void notifyChange(Footprint area, int[] height) {
        if (height[0] < height[1]) VoxyPredictionBridge.changed(this,
                area.minX() - 16, height[0] - 16, area.minZ() - 16,
                area.maxX() + 16, height[1] + 16, area.maxZ() + 16);
    }
    synchronized long bytes() { return tileBytes + mappedBytes + columnBytes + faceBytes; }
    synchronized Map<String, Object> diagnostics() {
        return Map.ofEntries(Map.entry("cachedSections", mapped.size()), Map.entry("cacheHits", cacheHits.sum()), Map.entry("sectionBuilds", sectionBuilds.sum()), Map.entry("lightingBuilds", lightingBuilds.sum()),
                Map.entry("coalescedReads", coalesced.sum()), Map.entry("emptyReads", emptyReads.sum()),
                Map.entry("mappedBytes", mappedBytes), Map.entry("cacheEvictions", mappedEvictions), Map.entry("cacheInvalidations", mappedInvalidations),
                Map.entry("columnCacheBytes",columnBytes),Map.entry("columnBuilds",columnBuilds.sum()),
                Map.entry("columnCacheHits",columnHits.sum()),Map.entry("faceBuilds",faceBuilds.sum()),
                Map.entry("directSectionWrites",directWrites.sum()),
                Map.entry("columnBuildNanos",columnBuildNanos.sum()),Map.entry("columnWaitNanos",columnWaitNanos.sum()),
                Map.entry("sectionBuildNanos",sectionBuildNanos.sum()),Map.entry("sectionWaitNanos",sectionWaitNanos.sum()),
                Map.entry("faceBuildNanos",faceBuildNanos.sum()),Map.entry("invalidatedBuilds",invalidatedBuilds),
                Map.entry("mapperRetirements",mapperRetirements),Map.entry("columnEvictions",columnEvictions),
                Map.entry("unchangedPublications",unchangedPublications),Map.entry("retainedColumnPublications",retainedColumnPublications),
                Map.entry("cachedFaces",mappedFaces.size()),Map.entry("faceCacheBytes",faceBytes),
                Map.entry("faceCacheHits",faceCacheHits),Map.entry("faceCacheEvictions",faceCacheEvictions),
                Map.entry("detailRequests", details.pending()+details.residents()), Map.entry("detailEvictions", details.evicted()),
                Map.entry("detailDeferred", details.deferred()), Map.entry("detailPending",details.pending()),
                Map.entry("detailResidents",details.residents()),Map.entry("protectedSections",protectedMapped.size()),
                Map.entry("meshDetailChecks",meshDetailChecks),Map.entry("meshDetailMisses",meshDetailMisses),
                Map.entry("meshDetailPending",meshDetails.size()));
    }
    @Override public synchronized VoxyPredictionBridge.Retention retainSection(long bytes) {
        if (closed) return null;
        if (budget == null) return () -> { };
        var lease = budget.tryReserve(bytes);
        if (lease == null) return null;
        lease.retain(bytes); return lease::close;
    }
    private void invalidate(Tile tile, int minY, int maxY) {
        invalidate(Footprint.of(tile),minY,maxY);
    }
    private void invalidate(Footprint area, int minY, int maxY) {
        int reach = lastMapper != null && !lastMapper.hasSkyLight() ? PredictionVoxyLighting.REACH : 0;
        var faces=mappedFaces.entrySet().iterator();
        while(faces.hasNext()) {
            var entry=faces.next();long pos=entry.getKey().position();int span=32<<VoxyPredictionBridge.level(pos);
            long bx=(long)VoxyPredictionBridge.x(pos)*span,by=(long)VoxyPredictionBridge.y(pos)*span,bz=(long)VoxyPredictionBridge.z(pos)*span;
            if(bx<(long)area.maxX()+reach && bz<(long)area.maxZ()+reach && bx+span>(long)area.minX()-reach
                    && bz+span>(long)area.minZ()-reach && by<(long)maxY+reach && by+span>(long)minY-reach) {
                faces.remove();releaseFace(entry.getValue());
            }
        }
        var iterator = mapped.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); long pos = entry.getKey();
            int span = 32 << VoxyPredictionBridge.level(pos);
            long bx=(long)VoxyPredictionBridge.x(pos)*span, by=(long)VoxyPredictionBridge.y(pos)*span,
                    bz=(long)VoxyPredictionBridge.z(pos)*span;
            if (bx < (long)area.maxX()+reach && bz < (long)area.maxZ()+reach
                    && bx+span > (long)area.minX()-reach && bz+span > (long)area.minZ()-reach
                    && by < (long)maxY+reach && by+span > (long)minY-reach) {
                iterator.remove(); probation.remove(pos); protectedMapped.remove(pos);
                if(!entry.getValue().result.isDone())invalidatedBuilds++;
                mappedInvalidations++; releaseMapped(entry.getValue());
            }
        }
    }
    private static long pack(int x,int z) { return (long)x<<32 | z&0xffffffffL; }
    private void releaseMapped(SectionBuild build) { if (build != null && build.retention != null) {
        mappedBytes -= build.bytes; build.retention.close(); build.retention = null; } }
    private void clearMapped() { mapped.values().forEach(this::releaseMapped); mapped.clear(); probation.clear(); protectedMapped.clear();
        mappedFaces.values().forEach(this::releaseFace);mappedFaces.clear();
        while(!columnOrder.isEmpty())removeColumn(columnOrder.iterator().next()); }
    private void releaseFace(FaceBuild face) {
        if(face.retention!=null) {face.retention.close();face.retention=null;faceBytes-=FACE_BYTES;}
    }
    private void evictFace() {
        var iterator=mappedFaces.entrySet().iterator();var entry=iterator.next();iterator.remove();
        releaseFace(entry.getValue());faceCacheEvictions++;
    }
    private void removeColumn(ColumnKey key) {
        columnOrder.remove(key);var entry=reducedColumns.remove(key);
        if(entry!=null) {columnBytes-=entry.bytes();entry.lease.close();}
        var keys=tileColumns.get(key.columns);
        if(keys!=null) {keys.remove(key);if(keys.isEmpty())tileColumns.remove(key.columns);}
    }
    private void retireColumns(Tile tile) {
        var keys=tileColumns.get(tile.columns());
        if(keys!=null)for(var key:List.copyOf(keys))removeColumn(key);
    }
    private PredictionVoxyColumnRuns reducedColumn(Tile tile,int step,int minX,int minZ,int maxX,int maxZ,
            VoxyPredictionBridge.Mapper mapper,PredictionVoxyMaterials materials) {
        var key=new ColumnKey(tile.columns(),tile.axis(),mapper,step,minX,minZ,maxX,maxZ);
        var hit=reducedColumns.get(key);
        if(hit!=null) {columnHits.increment();return hit.runs;}
        var future=new java.util.concurrent.CompletableFuture<PredictionVoxyColumnRuns>();
        var pending=reducingColumns.putIfAbsent(key,future);
        if(pending!=null) {columnHits.increment();long started=System.nanoTime();
            try {return pending.join();}finally {columnWaitNanos.add(System.nanoTime()-started);} }
        long started=System.nanoTime();
        try {
            var result=reduceColumn(tile,key,mapper,materials);future.complete(result);return result;
        } catch(Throwable failure) {future.completeExceptionally(failure);throw failure;}
        finally {columnBuildNanos.add(System.nanoTime()-started);reducingColumns.remove(key,future);}
    }
    private PredictionVoxyColumnRuns reduceColumn(Tile tile,ColumnKey key,VoxyPredictionBridge.Mapper mapper,PredictionVoxyMaterials materials) {
        var already=reducedColumns.get(key);if(already!=null)return already.runs;
        int step=key.step;
        var builder=new PredictionVoxyColumnRuns.Builder(15L<<56);
        Column previous=null;
        for(int z=key.minZ;z<=key.maxZ;z++)for(int x=key.minX;x<=key.maxX;x++) {
            var column=tile.columns()[z*tile.axis()+x];
            if(column==previous)continue;
            previous=column;
            for(var run:column.runs()) {
                int bottom=Math.floorDiv(run.bottom(),step),top=-Math.floorDiv(-run.top(),step);
                long value=dev.xantha.vss.compat.VoxyFluidSurface.body(materials.map(run.state(),column.biome()),run.state(),step);
                long surface=dev.xantha.vss.compat.VoxyFluidSurface.top(value,run.state(),run.top(),step);
                if(surface==value)builder.put(bottom,top,value);
                else {builder.put(bottom,top-1,value);builder.put(top-1,top,surface);}
            }
        }
        var result=builder.build();columnBuilds.increment();
        synchronized(this) {
            var hit=reducedColumns.get(key);if(hit!=null)return hit.runs;
            Tile resident=tiles.get(tile.key());
            if(closed || lastMapper!=mapper || resident==null || resident.columns()!=key.columns)return result;
            long bytes=result.bytes()+384L;
            while(!columnOrder.isEmpty() && (columnBytes+bytes>MAX_COLUMN_BYTES
                    || columnBytes+mappedBytes+faceBytes+bytes>MAX_MAPPED_BYTES))evictColumn();
            if(bytes<=MAX_COLUMN_BYTES && columnBytes+mappedBytes+faceBytes+bytes<=MAX_MAPPED_BYTES) {
                var lease=retainSection(bytes);
                if(lease!=null) {
                    reducedColumns.put(key,new CachedColumn(result,lease));columnOrder.add(key);columnBytes+=bytes;
                    tileColumns.computeIfAbsent(tile.columns(),ignored->new HashSet<>()).add(key);
                }
            }
        }
        return result;
    }
    @Override public synchronized void releaseMapper(VoxyPredictionBridge.Mapper mapper) {
        if (lastMapper != mapper) return;
        clearMapped(); lastMapper = null; mapperRetirements++;
    }
    private void evictColumn() {columnEvictions++;removeColumn(columnOrder.iterator().next());}
    private dev.xantha.vss.compat.VoxySectionData awaitSection(SectionBuild build) {
        if(build.result.isDone())return build.result.join();
        long started=System.nanoTime();
        try {return build.result.join();}finally {sectionWaitNanos.add(System.nanoTime()-started);}
    }
    private List<Tile> intersect(long bx,long bz,int span) {
        var result = new ArrayList<Tile>();
        spatial.forEach((size,cells) -> {
            int minX=(int)Math.floorDiv(bx,size), minZ=(int)Math.floorDiv(bz,size);
            int maxX=(int)Math.floorDiv(bx+span-1,size), maxZ=(int)Math.floorDiv(bz+span-1,size);
            if((long)(maxX-minX+1)*(maxZ-minZ+1)>cells.size()) {
                for(Tile t:cells.values()) if(t.baseX()<bx+span && t.baseZ()<bz+span && t.baseX()+(long)size>bx && t.baseZ()+(long)size>bz) result.add(t);
            } else for(int z=minZ;z<=maxZ;z++) for(int x=minX;x<=maxX;x++) {
                Tile t=cells.get(pack(x,z)); if(t!=null)result.add(t);
            }
        });
        result.sort(Comparator.comparingInt(Tile::step).thenComparingInt(t -> t.key().lod()));
        return result;
    }

    public long[] section(long pos, VoxyPredictionBridge.Mapper mapper) {
        var data = mappedSection(pos, mapper); return data == null ? null : data.array();
    }

    @Override public dev.xantha.vss.compat.VoxySectionData mappedSection(long pos, VoxyPredictionBridge.Mapper mapper) {
        return mappedSection(pos,mapper,true);
    }
    @Override public dev.xantha.vss.compat.VoxySectionData mappedSection(long pos, VoxyPredictionBridge.Mapper mapper, boolean promote) {
        return mappedSection(pos,mapper,promote,null);
    }
    @Override public boolean copySection(long pos,VoxyPredictionBridge.Mapper mapper,boolean promote,
            java.util.function.Supplier<long[]> target) {
        return mappedSection(pos,mapper,promote,target)!=null;
    }
    private dev.xantha.vss.compat.VoxySectionData mappedSection(long pos,VoxyPredictionBridge.Mapper mapper,boolean promote,
            java.util.function.Supplier<long[]> target) {
        List<Tile> snapshot = null; SectionBuild build; boolean owner = false, illuminate = false;
        synchronized (this) {
            if (closed || !mapper.isOpen()) return null;
            if (lastMapper != mapper) { if(lastMapper!=null)mapperRetirements++;clearMapped(); lastMapper = mapper; }
            build = mapped.get(pos);
            if (build == null) {
                int span = 32 << VoxyPredictionBridge.level(pos);
                long bx=(long)VoxyPredictionBridge.x(pos)*span, by=(long)VoxyPredictionBridge.y(pos)*span,
                        bz=(long)VoxyPredictionBridge.z(pos)*span;
                int reach = mapper.hasSkyLight() ? 0 : PredictionVoxyLighting.REACH;
                // Capture neighboring light sources with the geometry; the build never reads live world data.
                snapshot = intersect(bx-reach,bz-reach,span+2*reach);
                if (snapshot.isEmpty() || reach != 0 && intersect(bx,bz,span).isEmpty()) return null;
                boolean occupied = false;
                for (Tile tile : snapshot) {
                    Extent e = extents.get(tile.key());
                    if (by < (long)e.max()+reach && by + span > (long)e.min()-reach) occupied = true;
                    if (reach != 0 && by < (long)e.lightMax()+reach && by+span > (long)e.lightMin()-reach)
                        illuminate = true;
                }
                if (!occupied) { emptyReads.increment();var empty=mapper.hasSkyLight()?EMPTY:DARK_EMPTY;
                    if(target!=null)empty.copyTo(target.get());return empty; }
                while (mapped.size() >= mappedLimit) evictMapped();
                build = new SectionBuild(); mapped.put(pos, build); probation.put(pos,build); owner = true;
            } else { if(promote)touchMapped(pos,build); cacheHits.increment(); if (!build.result.isDone()) coalesced.increment(); }
        }
        if (!owner) {var result=awaitSection(build);if(target!=null)result.copyTo(target.get());return result;}
        long started=System.nanoTime();
        try {
            sectionBuilds.increment();
            long[] scratch = target==null ? SECTION_BUFFERS.poll() : target.get();
            if (scratch == null) scratch = new long[32768];
            dev.xantha.vss.compat.VoxySectionData result;
            try {
                result = dev.xantha.vss.compat.VoxySectionData.compactCopy(buildSection(pos,mapper,snapshot,scratch,illuminate));
                if(target!=null)directWrites.increment();
            } finally { if(target==null)SECTION_BUFFERS.offer(scratch); }
            synchronized (this) {
                // Only a local invalidation or mapper replacement can revoke this build.
                if (mapped.get(pos) == build) {
                    build.bytes = result.bytes();
                    while(!mappedFaces.isEmpty() && mappedBytes+columnBytes+faceBytes+build.bytes>MAX_MAPPED_BYTES)evictFace();
                    while(!columnOrder.isEmpty() && mappedBytes+columnBytes+build.bytes>MAX_MAPPED_BYTES)
                        evictColumn();
                    while (mappedBytes + build.bytes > MAX_MAPPED_BYTES) {
                        evictMapped();
                        if(mapped.get(pos)!=build)break;
                    }
                    if(mapped.get(pos)==build) {
                        build.retention = retainSection(build.bytes);
                        if (build.retention == null) removeMapped(pos);
                        else mappedBytes += build.bytes;
                    }
                }
            }
            build.result.complete(result); return result;
        } catch (Throwable failure) {
            synchronized (this) { if (mapped.get(pos) == build) removeMapped(pos); }
            build.result.completeExceptionally(failure); throw failure;
        } finally {sectionBuildNanos.add(System.nanoTime()-started);}
    }
    private long[] buildSection(long pos, VoxyPredictionBridge.Mapper mapper, List<Tile> snapshot, long[] result, boolean illuminate) {
        int lod = VoxyPredictionBridge.level(pos), step = 1 << lod, span = 32 << lod;
        int bx = VoxyPredictionBridge.x(pos) * span, by = VoxyPredictionBridge.y(pos) * span, bz = VoxyPredictionBridge.z(pos) * span;
        if (!illuminate) {
            buildVolume(bx,by,bz,step,32,mapper,snapshot,result,null);
            return result;
        }
        // One bounded halo avoids dark seams when a lamp is just across a section/tile border.
        int halo = (PredictionVoxyLighting.REACH + step - 1) / step, width = 32 + 2*halo;
        long[] volume = new long[width*width*width];
        var lighting = new PredictionVoxyLighting(width,step);
        buildVolume(bx-halo*step,by-halo*step,bz-halo*step,step,width,mapper,snapshot,volume,lighting);
        lighting.propagate(volume); lightingBuilds.increment();
        for(int y=0;y<32;y++) for(int z=0;z<32;z++)
            System.arraycopy(volume,((y+halo)*width+z+halo)*width+halo,result,(y*32+z)*32,32);
        return result;
    }
    @Override public long[] face(long pos,VoxyPredictionBridge.Mapper mapper,int face) {
        if(face<0 || face>=6)throw new IllegalArgumentException("face");
        // Block-light propagation needs its complete halo; retain the proven volume path there.
        if(!mapper.hasSkyLight())return VoxyPredictionBridge.Source.super.face(pos,mapper,face);
        List<Tile> snapshot=null;SectionBuild cached;FaceBuild faceBuild=null;boolean owner=false;
        var key=new FaceKey(pos,face);
        synchronized(this) {
            if(closed || !mapper.isOpen())return null;
            if(lastMapper!=mapper) {if(lastMapper!=null)mapperRetirements++;clearMapped();lastMapper=mapper;}
            cached=mapped.get(pos);
            if(cached!=null)touchMapped(pos,cached);
            else {
                faceBuild=mappedFaces.get(key);
                if(faceBuild!=null)faceCacheHits++;
                else {
                int span=32<<VoxyPredictionBridge.level(pos);
                snapshot=intersect((long)VoxyPredictionBridge.x(pos)*span,(long)VoxyPredictionBridge.z(pos)*span,span);
                if(snapshot.isEmpty())return null;
                long minY=(long)VoxyPredictionBridge.y(pos)*span,maxY=minY+span;
                if(face>>1==1) {minY+=(long)(face&1)*31*(span/32);maxY=minY+span/32;}
                boolean occupied=false;
                for(Tile tile:snapshot) {var extent=extents.get(tile.key());
                    if(minY<extent.max() && maxY>extent.min()) {occupied=true;break;} }
                if(!occupied) {emptyReads.increment();long[] empty=new long[1024];Arrays.fill(empty,15L<<56);return empty;}
                while(mappedFaces.size()>=MAX_FACE_BYTES/FACE_BYTES)evictFace();
                faceBuild=new FaceBuild();mappedFaces.put(key,faceBuild);owner=true;
                }
            }
        }
        if(cached!=null) {long[] result=new long[1024];awaitSection(cached).copyFace(face,result);cacheHits.increment();return result;}
        if(!owner)return faceBuild.result.join().clone();
        long[] result=new long[1024];
        int lod=VoxyPredictionBridge.level(pos),span=32<<lod;
        long started=System.nanoTime();
        try {
        buildVolume(VoxyPredictionBridge.x(pos)*span,VoxyPredictionBridge.y(pos)*span,VoxyPredictionBridge.z(pos)*span,
                1<<lod,32,mapper,snapshot,result,null,face);
        faceBuilds.increment();
        synchronized(this) {
            if(mappedFaces.get(key)==faceBuild) {
                if(faceBytes+FACE_BYTES<=MAX_FACE_BYTES && mappedBytes+columnBytes+faceBytes+FACE_BYTES<=MAX_MAPPED_BYTES)
                    faceBuild.retention=retainSection(FACE_BYTES);
                if(faceBuild.retention==null)mappedFaces.remove(key);
                else faceBytes+=FACE_BYTES;
            }
        }
        faceBuild.result.complete(result.clone());return result;
        } catch(Throwable failure) {
            synchronized(this) {if(mappedFaces.get(key)==faceBuild) {mappedFaces.remove(key);releaseFace(faceBuild);} }
            faceBuild.result.completeExceptionally(failure);throw failure;
        } finally {faceBuildNanos.add(System.nanoTime()-started);}
    }
    private static Tile[] selectOwners(List<Tile> tiles,int bx,int bz,int step,int width,int x0,int x1,int z0,int z1) {
        Tile[] result=new Tile[width*width];
        for(Tile tile:tiles) {
            // Match the original center-point ownership, including negative coordinates and odd steps.
            int minX=Math.max(x0,-Math.floorDiv(bx+step/2-tile.baseX(),step));
            int minZ=Math.max(z0,-Math.floorDiv(bz+step/2-tile.baseZ(),step));
            int maxX=Math.min(x1,-Math.floorDiv(bx+step/2-tile.baseX()-tile.span(),step));
            int maxZ=Math.min(z1,-Math.floorDiv(bz+step/2-tile.baseZ()-tile.span(),step));
            for(int z=minZ;z<maxZ;z++)for(int x=minX;x<maxX;x++)
                if(result[z*width+x]==null)result[z*width+x]=tile;
        }
        return result;
    }
    private void buildVolume(int bx, int by, int bz, int step, int width, VoxyPredictionBridge.Mapper mapper,
                             List<Tile> snapshot, long[] result, PredictionVoxyLighting lighting) {
        buildVolume(bx,by,bz,step,width,mapper,snapshot,result,lighting,-1);
    }
    private static int outputIndex(int width,int face,int x,int y,int z) {
        if(face<0)return (y*width+z)*width+x;
        return switch(face>>1) {case 0 -> y*32+z;case 1 -> z*32+x;default -> y*32+x;};
    }
    private void buildVolume(int bx, int by, int bz, int step, int width, VoxyPredictionBridge.Mapper mapper,
                             List<Tile> snapshot, long[] result, PredictionVoxyLighting lighting,int face) {
        Arrays.fill(result, mapper.hasSkyLight() ? 15L << 56 : 0);
        var cachedMapper = new PredictionVoxyMaterials(mapper);
        var mappedColumns = new IdentityHashMap<Column,long[]>();
        // Filter and order once per section, not once for each of its 1024 columns.
        // Stable ordering keeps the existing fine-to-coarse order inside each group.
        var featureTiles = snapshot.stream().filter(t -> t.surfaceComplete() || t.hasVegetation())
                .sorted(Comparator.comparing(t -> !t.surfaceComplete())).toList();
        int plane=(face&1)*31;
        int x0=face>=0 && face>>1==0 ? plane : 0,x1=face>=0 && face>>1==0 ? plane+1 : width;
        int z0=face>=0 && face>>1==2 ? plane : 0,z1=face>=0 && face>>1==2 ? plane+1 : width;
        int y0=face>=0 && face>>1==1 ? plane : 0,y1=face>=0 && face>>1==1 ? plane+1 : width;
        Tile[] owners=selectOwners(snapshot,bx,bz,step,width,x0,x1,z0,z1);
        Tile[] featureOwners=selectOwners(featureTiles,bx,bz,step,width,x0,x1,z0,z1);
        for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++) {
            Tile tile=owners[z*width+x];
            if(tile!=null) {
                // Reduce the complete voxel footprint so narrow peaks survive native coarse LODs.
                int firstX=Math.max(0,Math.floorDiv(bx+x*step-tile.baseX(),tile.step()));
                int firstZ=Math.max(0,Math.floorDiv(bz+z*step-tile.baseZ(),tile.step()));
                int lastX=Math.min(tile.axis()-1,Math.floorDiv(bx+(x+1)*step-1-tile.baseX(),tile.step()));
                int lastZ=Math.min(tile.axis()-1,Math.floorDiv(bz+(z+1)*step-1-tile.baseZ(),tile.step()));
                if(lighting==null && mapper.hasSkyLight() && (firstX!=lastX || firstZ!=lastZ)) {
                    var runs=reducedColumn(tile,step,firstX,firstZ,lastX,lastZ,mapper,cachedMapper);
                    runs.write(result,x,z,Math.floorDiv(by,step),width,face);
                } else {
                for(int cz=firstZ;cz<=lastZ;cz++) for(int cx=firstX;cx<=lastX;cx++) {
                Column column = tile.columns()[cz * tile.axis() + cx];
                long[] materials = mappedColumns.get(column);
                if (materials == null) {
                    materials = new long[column.runs().size()];
                    for (int r=0;r<materials.length;r++) materials[r]=cachedMapper.map(column.runs().get(r).state(),column.biome());
                    mappedColumns.put(column,materials);
                }
                for (int r = 0; r < column.runs().size(); r++) {
                    Run run = column.runs().get(r);
                    int bottom = Math.max(y0, Math.floorDiv(run.bottom() - by, step));
                    int top = Math.min(y1, -Math.floorDiv(by - run.top(), step));
                    if (bottom >= top) continue;
                    long value = dev.xantha.vss.compat.VoxyFluidSurface.body(materials[r],run.state(),step);
                    int surfaceY=Math.floorDiv(run.top()-1-by,step);
                    long surface=dev.xantha.vss.compat.VoxyFluidSurface.top(value,run.state(),run.top(),step);
                    byte opacity = lighting == null ? 0 : lighting.opacity(run.state());
                    for (int y = bottom; y < top; y++) {
                        int index = outputIndex(width,face,x,y,z); result[index] = y==surfaceY ? surface : value;
                        if (lighting != null) lighting.opacity[index] = opacity;
                    }
                }
                }
                }
            }
        }
        // Features retain their horizontal footprint; a one-block trunk must not fill a whole coarse sampling cell.
        for (Tile tile : snapshot) for (int featureIndex : tile.featureIndex().intersect(
                bx+x0*step-tile.baseX(),by+y0*step,bz+z0*step-tile.baseZ(),
                bx+x1*step-tile.baseX(),by+y1*step,bz+z1*step-tile.baseZ())) {
            Box feature = tile.features().get(featureIndex);
            Tile[] selectedOwners = featureIndex < tile.cityFeatures() ? owners : featureOwners;
            int minX=Math.max(x0,Math.floorDiv(tile.baseX()+feature.minX()-bx,step));
            int minZ=Math.max(z0,Math.floorDiv(tile.baseZ()+feature.minZ()-bz,step));
            int maxX=Math.min(x1,-Math.floorDiv(bx-tile.baseX()-feature.maxX(),step));
            int maxZ=Math.min(z1,-Math.floorDiv(bz-tile.baseZ()-feature.maxZ(),step));
            int minY=Math.max(y0,Math.floorDiv(feature.minY()-by,step)), maxY=Math.min(y1,-Math.floorDiv(by-feature.maxY(),step));
            if(minX>=maxX || minZ>=maxZ || minY>=maxY) continue;
            int tx=Math.max(0,Math.min(tile.axis()-1,Math.floorDiv(feature.minX(),tile.step())));
            int tz=Math.max(0,Math.min(tile.axis()-1,Math.floorDiv(feature.minZ(),tile.step())));
            long value=dev.xantha.vss.compat.VoxyFluidSurface.body(cachedMapper.map(feature.state(),tile.columns()[tz*tile.axis()+tx].biome()),feature.state(),step);
            int surfaceY=Math.floorDiv(feature.maxY()-1-by,step);
            long surface=dev.xantha.vss.compat.VoxyFluidSurface.top(value,feature.state(),feature.maxY(),step);
            byte opacity = lighting == null ? 0 : lighting.opacity(feature.state());
            for(int z=minZ;z<maxZ;z++) for(int x=minX;x<maxX;x++) if(selectedOwners[z*width+x]==tile) {
                for(int y=minY;y<maxY;y++) {
                    int index=outputIndex(width,face,x,y,z); result[index]=y==surfaceY ? surface : value;
                    if(lighting!=null) lighting.opacity[index]=opacity;
                }
            }
        }
    }
    public void close() {
        synchronized (this) { closed = true; tiles.clear(); tileBytes = 0; spatial.clear(); extents.clear(); details.clear(); meshDetails.clear();
            detailSnapshot = Set.of(); detailChanged = false; clearMapped(); lastMapper = null;
            mapVersions.clear(); ++mapRevision; }
        VoxyPredictionBridge.detach(this);
        XaeroPredictionBridge.detach(this);
    }
}
