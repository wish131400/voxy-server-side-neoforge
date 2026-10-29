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
                Column[] columns, List<Box> features, long terrainBytes, boolean surfaceComplete, int cityFeatures,
                PredictionVoxyFeatures featureIndex) {
        Tile {
            features=PredictionVoxyBoxes.of(features);
            if(featureIndex==null)featureIndex=PredictionVoxyFeatures.of(features);
        }
        PredictionVoxyBoxes boxes() {return (PredictionVoxyBoxes)features;}
        Tile(PredictionTileKey key,int baseX,int baseZ,int span,int axis,int step,Column[] columns,
             List<Box> features,long terrainBytes,boolean surfaceComplete,int cityFeatures) {
            this(key,baseX,baseZ,span,axis,step,columns,features,terrainBytes,surfaceComplete,cityFeatures,null);
        }
        public long bytes() {return Math.max(256L+columns.length*4L,terrainBytes)+boxes().bytes()+featureIndex.bytes();}
        Tile(PredictionTileKey key, int baseX, int baseZ, int span, int axis, int step,
             Column[] columns, List<Box> features, long terrainBytes) {
            this(key, baseX, baseZ, span, axis, step, columns, features, terrainBytes, false, 0);
        }

        /** Terrain refinement does not prove that previously visible plants disappeared.
         * The caller excludes placement-invalidated records, independently of ground dirtiness. */
        Tile retainFeatures(Tile previous) {
            if (surfaceComplete || previous == null || !key.equals(previous.key)
                    || baseX != previous.baseX || baseZ != previous.baseZ || span != previous.span
                    || !previous.surfaceComplete && !previous.hasVegetation()) return this;
            if (!previous.surfaceComplete && hasVegetation()) {
                var retained=boxes().retainVegetation(cityFeatures,previous.boxes(),previous.cityFeatures);
                if (retained==features) return this;
                return new Tile(key,baseX,baseZ,span,axis,step,columns,retained,terrainBytes,false,cityFeatures);
            }
            var builder = new PredictionVoxyBoxes.Builder();
            builder.addRange(features,0,cityFeatures);
            builder.addRange(previous.features,previous.cityFeatures,previous.features.size());
            var retained=builder.build();
            return new Tile(key, baseX, baseZ, span, axis, step, columns, retained,
                    terrainBytes, previous.surfaceComplete, cityFeatures);
        }

        boolean hasVegetation() { return features.size() > cityFeatures; }

        /** Content identity is decided before manager versions or parent dependencies change. */
        Tile reuseContent(Tile previous) {
            if (previous == null || !key.equals(previous.key) || baseX != previous.baseX || baseZ != previous.baseZ
                    || span != previous.span || axis != previous.axis || step != previous.step) return this;
            boolean ground = Arrays.equals(columns, previous.columns);
            boolean plants = surfaceComplete == previous.surfaceComplete && cityFeatures == previous.cityFeatures
                    && features.equals(previous.features);
            if (ground && plants) return previous;
            return new Tile(key,baseX,baseZ,span,axis,step,ground ? previous.columns : columns,
                    plants ? previous.features : features,ground ? previous.terrainBytes : terrainBytes,
                    surfaceComplete,cityFeatures,plants ? previous.featureIndex : featureIndex);
        }

        Tile withVegetation(PredictionVegetation.Tile plants, dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities) {
            var builder = new PredictionVoxyBoxes.Builder();
            builder.addRange(features,0,cityFeatures);
            vegetation(builder, plants, cities, baseX, baseZ);
            var completed=builder.build();
            return new Tile(key, baseX, baseZ, span, axis, step, columns, completed,
                    terrainBytes, true, cityFeatures);
        }
    }
    private final Map<PredictionTileKey, Tile> tiles = new HashMap<>();
    @Override public boolean compareNotificationFaces() { return true; }
    private final Map<Integer, Map<Long, Tile>> spatial = new HashMap<>();
    private long mapRevision;
    private long terrainRangeReductions;
    private long tileBytes;
    private long featureBytes,featureIndexBytes,featureCount;
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
        var featureTiles=snapshot.stream().filter(Tile::surfaceComplete).toList();
        // Cull boxes once per chunk; do not walk an entire distant forest for each map pixel.
        record Feature(Box box, boolean city) { }
        var boxes=new IdentityHashMap<Tile,List<Feature>>();
        for (Tile t:snapshot) {
            var selected=new ArrayList<Feature>();
            for (int i:t.featureIndex().intersect(bx-t.baseX(),Integer.MIN_VALUE,bz-t.baseZ(),
                    bx+16-t.baseX(),Integer.MAX_VALUE,bz+16-t.baseZ()))
                selected.add(new Feature(t.features().get(i),i<t.cityFeatures()));
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
            // Incomplete cache snapshots cannot establish absence. Merge their known
            // plants coarse-to-fine until a completed surface owns this column.
            int partialStep=Integer.MAX_VALUE;
            for (int i=snapshot.size()-1;i>=0;i--) {
            Tile t=snapshot.get(i);
            boolean fallback=plants==null && !t.surfaceComplete() && t.hasVegetation() && covers(t,wx,wz);
            if(fallback)partialStep=Math.min(partialStep,t.step());
            for (Feature f:boxes.get(t)) {
                Box b=f.box();
                if (f.city() ? owner!=t : plants!=t && !fallback) continue;
                if (wx>=t.baseX()+b.minX() && wx<t.baseX()+b.maxX()
                        && wz>=t.baseZ()+b.minZ() && wz<t.baseZ()+b.maxZ()) {
                    if(runs==null) runs=new ArrayList<>(base.runs());
                    runs.add(new XaeroPredictionBridge.Run(b.minY(),b.maxY(),b.state()));
                }
            }
            }
            pixels[x*16+z]=runs==null ? base : new XaeroPredictionBridge.Pixel(List.copyOf(runs),column.biome());
            terrainStep=Math.max(terrainStep,owner.step());
            featureStep=Math.max(featureStep,plants!=null ? plants.step() : partialStep);
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
    private static boolean vegetationVisible(Tile tile,Tile completed,int x,int z) {
        return completed==tile || completed==null && !tile.surfaceComplete() && covers(tile,x,z);
    }
    record Detail(int x, int z, int lod) {
        @Override public int hashCode() { return PredictionKeyHash.hash(x,z,lod); }
    }
    // Requests come from the node thread, never call the manager while holding this lock.
    // Y requests share one horizontal sampling job. Keep detail until leaving the horizon.
    private final PredictionVoxyDetailQueue details = new PredictionVoxyDetailQueue(4096);
    private Set<Detail> detailSnapshot = Set.of();
    private boolean detailChanged;
    private long detailSnapshotRevision = -1;
    private double cameraX, cameraZ;
    private int radius = VssLodLayout.MAX_DISTANCE_BLOCKS;
    private volatile PredictionVoxyHorizon horizon;
    private final Object windowUpdates=new Object();
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

    private record ColumnKey(Column[] columns,int axis,VoxyPredictionBridge.Mapper mapper,int step,int minX,int minZ,int maxX,int maxZ,boolean paged) {
        @Override public boolean equals(Object other) {
            return other instanceof ColumnKey k && columns==k.columns && axis==k.axis && mapper==k.mapper && step==k.step && minX==k.minX
                    && minZ==k.minZ && maxX==k.maxX && maxZ==k.maxZ && paged==k.paged;
        }
        @Override public int hashCode() {
            int footprint=PredictionKeyHash.hash(minX,minZ,step)
                    ^ Integer.rotateLeft(PredictionKeyHash.hash(maxX,maxZ,axis),13);
            return footprint ^ (System.identityHashCode(columns)*31+System.identityHashCode(mapper));
        }
    }
    private static final class CachedColumn {
        final ColumnKey key;
        final PredictionVoxyColumnRuns.Page runs;
        final VoxyPredictionBridge.Retention lease;
        CachedColumn previous,next;
        volatile boolean referenced;
        CachedColumn(ColumnKey key,PredictionVoxyColumnRuns.Page runs,VoxyPredictionBridge.Retention lease) {
            this.key=key;this.runs=runs;this.lease=lease;
        }
        long bytes() {return runs.bytes()+384L;}
    }
    private static final class ColumnProbe {
        Column[] columns;VoxyPredictionBridge.Mapper mapper;int axis,step,minX,minZ,maxX,maxZ;boolean paged;
        @Override public int hashCode() {
            return PredictionKeyHash.hash(minX,minZ,step)^Integer.rotateLeft(PredictionKeyHash.hash(maxX,maxZ,axis),13)
                    ^(System.identityHashCode(columns)*31+System.identityHashCode(mapper));
        }
        @Override public boolean equals(Object other) {
            return other instanceof ColumnKey k && columns==k.columns && mapper==k.mapper && axis==k.axis && step==k.step
                    && minX==k.minX && minZ==k.minZ && maxX==k.maxX && maxZ==k.maxZ && paged==k.paged;
        }
    }
    private static final ThreadLocal<ColumnProbe> COLUMN_PROBE=ThreadLocal.withInitial(ColumnProbe::new);
    private static final class ColumnScratch {
        boolean busy;
        final PredictionVoxyColumnRuns.Builder builder=new PredictionVoxyColumnRuns.Builder(15L<<56);
        final PredictionVoxyColumnRuns.Coverage coverage=new PredictionVoxyColumnRuns.Coverage();
    }
    private static final ThreadLocal<ColumnScratch> COLUMN_SCRATCH=ThreadLocal.withInitial(ColumnScratch::new);
    private static final class VolumeScratch {
        boolean busy;
        Tile[] owners=new Tile[1024],featureOwners=new Tile[1024];
        final ArrayList<Tile> featureTiles=new ArrayList<>();
        final PredictionVoxyMaterials materials=new PredictionVoxyMaterials();
        final it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<Column> mappedColumns=
                new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>();
        long[] mappedRuns=new long[4096];
        int mappedRunCount;
        final IdentityHashMap<Tile,Integer> featureContributors=new IdentityHashMap<>();
        final HashMap<ColumnKey,PredictionVoxyColumnRuns.Page> columnPages=new HashMap<>();
        final it.unimi.dsi.fastutil.ints.IntArrayList hits=new it.unimi.dsi.fastutil.ints.IntArrayList();
        VolumeScratch() {mappedColumns.defaultReturnValue(-1);}
        int mapColumn(Column column) {
            int offset=mappedColumns.getInt(column);
            if(offset>=0)return offset;
            offset=mappedRunCount;int end=offset+column.runs().size();
            if(end>mappedRuns.length)mappedRuns=Arrays.copyOf(mappedRuns,Math.max(end,mappedRuns.length*2));
            for(int r=0;r<column.runs().size();r++)
                mappedRuns[offset+r]=materials.map(column.runs().get(r).state(),column.biome());
            mappedColumns.put(column,offset);mappedRunCount=end;return offset;
        }
        void clear() {
            int columns=mappedColumns.size();
            Arrays.fill(owners,null);Arrays.fill(featureOwners,null);featureTiles.clear();mappedColumns.clear();featureContributors.clear();columnPages.clear();
            materials.clear();mappedRunCount=0;
            if(mappedRuns.length>65536)mappedRuns=new long[4096];
            if(columns>4096)mappedColumns.trim(4096);
            hits.clear();if(hits.elements().length>65536)hits.trim(65536);busy=false;
        }
    }
    private static final ThreadLocal<VolumeScratch> VOLUME_SCRATCH=ThreadLocal.withInitial(VolumeScratch::new);
    private final java.util.concurrent.ConcurrentHashMap<ColumnKey,CachedColumn> reducedColumns=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<ColumnKey,java.util.concurrent.CompletableFuture<PredictionVoxyColumnRuns.Page>>
            reducingColumns=new java.util.concurrent.ConcurrentHashMap<>();
    // Admission/retirement owns the source lock; hits read immutable entries without it.
    private CachedColumn columnClock;
    private final IdentityHashMap<Column[],Set<ColumnKey>> tileColumns=new IdentityHashMap<>();
    private long columnBytes,columnCachedCount;
    private static final int MAX_FACES=32768;
    private static final java.util.concurrent.ArrayBlockingQueue<long[]> FACE_BUFFERS=
            new java.util.concurrent.ArrayBlockingQueue<>(8);
    private record FaceKey(long position,int face) { }
    private static final class FaceBuild {
        final java.util.concurrent.CompletableFuture<dev.xantha.vss.compat.VoxySectionData> result=new java.util.concurrent.CompletableFuture<>();
        VoxyPredictionBridge.Retention retention;
        long bytes;
    }
    private final LinkedHashMap<FaceKey,FaceBuild> mappedFaces=new LinkedHashMap<>(64,.75f,true);
    private long faceBytes,faceCacheHits,faceCacheEvictions;
    private final java.util.concurrent.atomic.LongAdder columnBuilds=new java.util.concurrent.atomic.LongAdder(),
            columnHits=new java.util.concurrent.atomic.LongAdder(), columnPageBuilds=new java.util.concurrent.atomic.LongAdder(), faceBuilds=new java.util.concurrent.atomic.LongAdder(),
            directWrites=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder columnBuildNanos=new java.util.concurrent.atomic.LongAdder(),
            columnWaitNanos=new java.util.concurrent.atomic.LongAdder(), sectionBuildNanos=new java.util.concurrent.atomic.LongAdder(),
            sectionWaitNanos=new java.util.concurrent.atomic.LongAdder(), faceBuildNanos=new java.util.concurrent.atomic.LongAdder();
    private long invalidatedBuilds, mapperRetirements, columnEvictions, columnSecondChances;
    private long unchangedPublications, retainedColumnPublications;
    private final java.util.concurrent.atomic.LongAdder featureQueries=new java.util.concurrent.atomic.LongAdder(),
            skippedFeatureTiles=new java.util.concurrent.atomic.LongAdder();
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
    private final PredictionSectionIndex cachedPositions=new PredictionSectionIndex();
    // One-shot traversal/notification reads cannot evict the repeatedly consumed
    // center and border sections. Both segments share the existing byte limit.
    private final LinkedHashMap<Long, SectionBuild> probation = new LinkedHashMap<>();
    private final LinkedHashMap<Long, SectionBuild> protectedMapped = new LinkedHashMap<>(64,.75f,true);
    private final int mappedLimit;
    private final long sectionLimit,columnLimit,faceLimit;
    PredictionVoxySource() {this(MAX_MAPPED);}
    PredictionVoxySource(int mappedLimit) {this(mappedLimit,MAX_MAPPED_BYTES);}
    PredictionVoxySource(int mappedLimit,long cacheBytes) {
        if(mappedLimit<2 || mappedLimit>MAX_MAPPED)throw new IllegalArgumentException("mappedLimit");
        if(cacheBytes<256L*1024 || cacheBytes>MAX_MAPPED_BYTES)throw new IllegalArgumentException("cacheBytes");
        this.mappedLimit=mappedLimit;
        columnLimit=cacheBytes/8;faceLimit=cacheBytes/16;sectionLimit=cacheBytes-columnLimit-faceLimit;
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
        mapped.remove(oldest.getKey());cachedPositions.remove(oldest.getKey());mappedEvictions++;releaseMapped(oldest.getValue());
    }
    private void removeMapped(long pos) {
        probation.remove(pos);protectedMapped.remove(pos);var build=mapped.remove(pos);
        if(build!=null) {cachedPositions.remove(pos);releaseMapped(build);}
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
    private DetailInput observedView;
    private long viewDemandBuilds,viewDemandTargets;
    private volatile boolean detailLoading = true;
    void detailLoading(boolean allowed) { detailLoading = allowed; }
    private PredictionMemoryBudget budget;
    synchronized void budget(PredictionMemoryBudget value) { budget = value; }

    record DetailInput(double x,double z,VssLodLayout layout,PredictionWorkView view,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            VssLodFocus focus,double minY,double maxY) { }
    /** Runs on the preparation worker. No caller holds the manager monitor while acquiring this source. */
    PredictionVoxyDetailQueue.Changes refreshDetails(DetailInput input) {
        boolean changed;
        synchronized(this) {
            if(closed)return new PredictionVoxyDetailQueue.Changes(List.of(),List.of());
            changed=detailLoading && (observedView==null || !input.layout().equals(observedView.layout())
                    || input.minY()!=observedView.minY() || input.maxY()!=observedView.maxY()
                    || (input.view()==null ? observedView.view()!=null : input.view().changedFrom(observedView.view())));
            if(!detailLoading)observedView=null;
        }
        // Traversal is pure and can be larger during a zoom. Never hold the
        // source monitor while walking it; mesh workers and publication share it.
        var visible=changed ? PredictionVoxyViewDemand.build(input) : List.<PredictionTileKey>of();
        window(input.x(),input.z(),input.layout().maxDistanceBlocks());
        synchronized(this) {
            if(closed)return new PredictionVoxyDetailQueue.Changes(List.of(),List.of());
            detailPriority(input.view(),input.layout(),input.dimension(),input.focus(),input.minY(),input.maxY());
            if(changed && detailLoading) {
                observedView=input;viewDemandBuilds++;viewDemandTargets=visible.size();
                for(var key:visible) {
                    int chunks=input.layout().tileBlocks(key.lod())/16;
                    if(ClientPredictionState.hasSettledExactCoverageBox(input.dimension(),key.tileX()*chunks,key.tileZ()*chunks,
                            (key.tileX()+1)*chunks-1,(key.tileZ()+1)*chunks-1))continue;
                    boolean ready=requestDetail(key.lod()+1,key.tileX(),key.tileZ(),
                            (x,z)->ClientPredictionState.hasSettledExactCoverage(input.dimension(),x,z),false);
                    // A complete finer cover already supplies this region. Do not
                    // pin a missing parent and make the manager regenerate it.
                    if(ready && !tiles.containsKey(key))continue;
                    if(details.observe(new Detail(key.tileX(),key.tileZ(),key.lod()),ready))detailChanged=true;
                }
            }
            return detailChanges();
        }
    }

    void window(double x, double z, int distance) {
        synchronized(windowUpdates) {updateWindow(x,z,distance);}
    }
    private void updateWindow(double x,double z,int distance) {
        var next=PredictionVoxyHorizon.of(x,z,distance);
        PredictionVoxyHorizon before;List<PredictionVoxyHorizon.Box> boxes;
        synchronized(this) {
            if(closed || horizon!=null && x==cameraX && z==cameraZ && distance==radius)return;
            before=horizon;
        }
        // Pure boundary traversal runs outside the source monitor, like view traversal.
        boxes=before==null ? List.of() : before.changes(next);
        synchronized(this) {
        if(closed)return;
        if(!next.equals(before)) {
            horizon=next;
            var changed=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            if(before!=null) {
                for(var box:boxes) {
                    // Include the largest native voxel border for face readers.
                    int minX=box.x()-16,minZ=box.z()-16,maxX=box.x()+box.span()+16,maxZ=box.z()+box.span()+16;
                    for(long pos:cachedPositions.intersect(minX,Integer.MIN_VALUE,minZ,maxX,Integer.MAX_VALUE,maxZ))
                        if(before.affected(pos,next))changed.add(pos);
                }
            } else {
                // Initial attachment may already have cached startup sections.
                for(long pos:cachedPositions.intersect(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,
                        Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE))if(next.clips(pos))changed.add(pos);
            }
            for(long pos:changed) {
                for(int face=0;face<6;face++)removeFace(new FaceKey(pos,face));
                removeMapped(pos);
            }
        }
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
        // Bridge snapshot retention can acquire the source monitor. Never call
        // bridge invalidation while owning it, or mesh readers can deadlock.
        if(!next.equals(before)) {
            if(before==null)VoxyPredictionBridge.changed(this,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,
                    Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,next::clips);
            else {
                var notified=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                for(var box:boxes)VoxyPredictionBridge.changed(this,box.x()-16,Integer.MIN_VALUE,box.z()-16,
                        box.x()+box.span()+16,Integer.MAX_VALUE,box.z()+box.span()+16,
                        pos->before.affected(pos,next) && notified.add(pos));
            }
        }
    }

    synchronized void detailPriority(PredictionWorkView view,VssLodLayout layout,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            VssLodFocus focus,double minY,double maxY) {
        details.pollPriority();
        details.maintainResidency(key -> {
            var tile=tiles.get(new PredictionTileKey(dimension,key.x(),key.z(),key.lod()));
            return tile!=null && tile.step()<=(1<<key.lod());
        });
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
        if (detailChanged || detailSnapshotRevision != details.revision()) {
            detailSnapshot = details.keys(); detailChanged = false; detailSnapshotRevision = details.revision();
        }
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
        double dx=Math.max(0,Math.max(bx-cameraX,cameraX-(bx+span)));
        double dz=Math.max(0,Math.max(bz-cameraZ,cameraZ-(bz+span)));
        return dx*dx+dz*dz>(double)radius*radius;
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
        if (candidates.isEmpty() && observedView==null) return true;
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
        var features = new PredictionVoxyBoxes.Builder();
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
                for (var face : city.faces(cell)) cityBox(features,face);
                for (var face : city.overlays(cell)) cityBox(features,face);
            }
            String biome = biome(sample, names, sampler, bx + x * step, bz + z * step, resolvedBiomes);
            columns[cell] = palette.column(runs, biome);
        }
        int cityFeatures = features.size();
        vegetation(features, plants, cities, bx, bz);
        return new Tile(key, bx, bz, span, axis, step, columns, features.build(), palette.bytes(columns.length), surfaceComplete, cityFeatures);
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

    private static void vegetation(PredictionVoxyBoxes.Builder features, PredictionVegetation.Tile plants,
            dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities, int bx, int bz) {
        plants.forEachVoxel((x,y,z,size,state)->{
            if(cities==null || !PredictionCityGeometry.vegetationOwner(cities.atBlock(bx+x,bz+z)))
                features.add(x,y,z,x+size,y+size,z+size,state);
        });
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
    private static void cityBox(PredictionVoxyBoxes.Builder features, PredictionCityGeometry.Face face) {
        int bottom = face.y() - (face.direction() == 0 ? 1 : 0);
        int x = face.x() - (face.direction() == 4 ? 1 : 0), z = face.z() - (face.direction() == 2 ? 1 : 0);
        features.add(x,bottom,z,x+Math.max(1,face.dx()),bottom+Math.max(1,face.dy()),
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
        var boxes=tile.boxes();
        for (int i=0;i<boxes.size();i++) {featureMin=Math.min(featureMin,boxes.coordinate(i,1));featureMax=Math.max(featureMax,boxes.coordinate(i,4));}
        int lightMin = Integer.MAX_VALUE, lightMax = Integer.MIN_VALUE;
        // Summarize emitters once per publication, rather than scan all materials for every section.
        var seen = Collections.newSetFromMap(new IdentityHashMap<Column,Boolean>());
        for (Column column : tile.columns()) if (seen.add(column)) for (Run run : column.runs())
            if (run.state().getLightEmission() > 1) {
                lightMin = Math.min(lightMin,run.bottom()); lightMax = Math.max(lightMax,run.top());
            }
        for (int i=0;i<boxes.size();i++) if (boxes.state(i).getLightEmission()>1) {
            lightMin=Math.min(lightMin,boxes.coordinate(i,1));lightMax=Math.max(lightMax,boxes.coordinate(i,4));
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
    Tile put(Tile tile) { return put(tile, () -> true); }
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
    private static boolean sameFeatureBiomes(Tile old,Tile tile) {
        if(tile.features().isEmpty())return true;
        for(int i=0;i<tile.columns().length;i++)
            if(!old.columns()[i].biome().equals(tile.columns()[i].biome()))return false;
        return true;
    }
    Tile put(Tile tile, java.util.function.BooleanSupplier current) {
        // Compare immutable input outside the lock. Equal republished samples must not cancel valid builds.
        for(;;) {
        Tile observed;Extent before;
        synchronized(this) {
            if(closed || !current.getAsBoolean())return null;
            observed=tiles.get(tile.key());if(observed==tile) {unchangedPublications++;return observed;}
            before=extents.get(tile.key());
        }
        boolean sameShape=observed!=null && observed.baseX()==tile.baseX() && observed.baseZ()==tile.baseZ()
                && observed.span()==tile.span() && observed.axis()==tile.axis() && observed.step()==tile.step();
        boolean sameColumns=sameShape && Arrays.equals(observed.columns(),tile.columns());
        boolean sameFeatures=sameShape && observed.surfaceComplete()==tile.surfaceComplete()
                && observed.cityFeatures()==tile.cityFeatures() && observed.features().equals(tile.features());
        boolean sameContent=sameColumns && sameFeatures;
        Footprint affected=!sameColumns && sameFeatures ? changedTerrain(observed,tile) : Footprint.of(tile);
        PredictionTerrainChange terrainChange=null;
        // Stable grids do not change ownership. Compare run intervals before
        // composing any native sections, rather than invalidating solid ground
        // from the dimension floor for a one-block surface edit.
        if(!sameColumns && sameFeatures && sameFeatureBiomes(observed,tile)) {
            terrainChange=new PredictionTerrainChange();
            for(int i=0;i<tile.columns().length;i++)terrainChange.compare(observed.columns()[i],tile.columns()[i]);
        }
        int[] featureChanges=null;
        // Stable vegetation ownership permits a local update. Completion, first
        // coverage and city/vegetation ownership transitions still cover the tile.
        if(sameColumns && !sameFeatures && observed.surfaceComplete()==tile.surfaceComplete()
                && observed.cityFeatures()==tile.cityFeatures() && observed.hasVegetation()==tile.hasVegetation()) {
            featureChanges=tile.boxes().changedBounds(observed.boxes());
            if(featureChanges[0]<featureChanges[3])affected=new Footprint(tile.baseX()+featureChanges[0],tile.baseZ()+featureChanges[2],
                    tile.baseX()+featureChanges[3],tile.baseZ()+featureChanges[5]);
        }
        // Preserve reduced-column identities when only decoration/completion changed.
        if(sameColumns && observed.columns()!=tile.columns())tile=new Tile(tile.key(),tile.baseX(),tile.baseZ(),tile.span(),
                tile.axis(),tile.step(),observed.columns(),tile.features(),tile.terrainBytes,tile.surfaceComplete(),tile.cityFeatures(),tile.featureIndex());
        if(sameFeatures && observed.features()!=tile.features())tile=new Tile(tile.key(),tile.baseX(),tile.baseZ(),tile.span(),
                tile.axis(),tile.step(),tile.columns(),observed.features(),tile.terrainBytes,tile.surfaceComplete(),tile.cityFeatures(),observed.featureIndex());
        Extent prepared = sameContent ? before : extent(tile, observed, before);
        int[] height;ChangeInput input;boolean rangeReduced;
        synchronized(this) {
            if(closed || !current.getAsBoolean())return null;
            if(tiles.get(tile.key())!=observed)continue;
            if(sameContent) {unchangedPublications++;return observed;}
            height=changeHeight(tile,before,prepared,sameColumns);
            rangeReduced=terrainChange!=null && lastMapper!=null && lastMapper.hasSkyLight()
                    && (terrainChange.bottom()>height[0] || terrainChange.top()<height[1]);
            if(terrainChange!=null && lastMapper!=null && lastMapper.hasSkyLight())
                height=new int[]{terrainChange.bottom(),terrainChange.top()};
            if(featureChanges!=null && featureChanges[0]<featureChanges[3])height=new int[]{featureChanges[1],featureChanges[4]};
            input=changeInput(tile,affected,height);
        }
        var change=publicationChange(observed,tile,!sameColumns,input);
        var invalidation=prepareInvalidation(input,change);
        synchronized (this) {
            if (closed || !current.getAsBoolean()) return null;
            Tile old = tiles.get(tile.key()); if(old!=observed || !changeCurrent(input,tile))continue;
            if(sameColumns)retainedColumnPublications++;
            if(rangeReduced)terrainRangeReductions++;
            if(old!=null && old.columns()!=tile.columns())retireColumns(old);
            Extent after = prepared;
            tiles.put(tile.key(), tile); extents.put(tile.key(), after);
            // Publication is the completion event. Waiting for a later native retry keeps
            // finished work in the bounded pending queue and evicts useful new demand.
            int lod=tile.key().lod();
            if (tile.span()==(64L<<lod) && tile.step()<=(1<<lod)) {
                meshDetails.remove(new Detail(tile.key().tileX(),tile.key().tileZ(),lod));
                if(details.request(new Detail(tile.key().tileX(),tile.key().tileZ(),lod),true))detailChanged=true;
            }
            tileBytes += tile.bytes() - (old == null ? 0 : old.bytes());
            featureBytes+=tile.boxes().bytes()-(old==null?0:old.boxes().bytes());
            featureIndexBytes+=tile.featureIndex().bytes()-(old==null?0:old.featureIndex().bytes());
            featureCount+=tile.features().size()-(old==null?0:old.features().size());
            mapVersions.put(tile.key(), ++mapRevision);
            spatial.computeIfAbsent(tile.span(), ignored -> new HashMap<>()).put(pack(tile.key().tileX(),tile.key().tileZ()),tile);
            invalidate(affected,height[0],height[1],invalidation);
        }
        notifyChange(affected, height,change);
        return tile;
        }
    }
    void remove(PredictionTileKey key) {
        for(;;) {
        Tile old;int[] height;ChangeInput input;
        synchronized (this) {
            old = tiles.get(key); if (old == null) return;
            height = changeHeight(old, extents.get(key), null, false);
            input=changeInput(old,Footprint.of(old),height);
        }
        var change=publicationChange(old,null,true,input);
        var invalidation=prepareInvalidation(input,change);
        synchronized(this) {
            if(tiles.get(key)!=old || !changeCurrent(input,old))continue;
            retireColumns(old);
            tiles.remove(key); tileBytes -= old.bytes();
            featureBytes-=old.boxes().bytes();featureIndexBytes-=old.featureIndex().bytes();featureCount-=old.features().size();
            extents.remove(key);
            invalidate(Footprint.of(old),height[0],height[1],invalidation);
            if(details.releaseResident(new Detail(key.tileX(),key.tileZ(),key.lod())))detailChanged=true;
            mapVersions.remove(key); ++mapRevision;
            var cells=spatial.get(old.span()); cells.remove(pack(key.tileX(),key.tileZ()));
            if(cells.isEmpty()) spatial.remove(old.span());
        }
        notifyChange(Footprint.of(old), height,change);
        return;
        }
    }
    private record ChangeInput(long revision,VoxyPredictionBridge.Mapper mapper,boolean precise,List<Tile> peers,
            it.unimi.dsi.fastutil.longs.LongArrayList positions) { }
    private ChangeInput changeInput(Tile tile,Footprint area,int[] height) {
        int reach=lastMapper!=null && !lastMapper.hasSkyLight()?PredictionVoxyLighting.REACH:0;
        return new ChangeInput(mapRevision,lastMapper,lastMapper!=null && lastMapper.hasSkyLight(),
                intersect(tile.baseX(),tile.baseZ(),tile.span()),cachedPositions.intersect((long)area.minX()-reach,
                (long)height[0]-reach,(long)area.minZ()-reach,(long)area.maxX()+reach,(long)height[1]+reach,(long)area.maxZ()+reach));
    }
    private boolean changeCurrent(ChangeInput input,Tile tile) {
        if(lastMapper!=input.mapper())return false;
        if(mapRevision==input.revision())return true;
        // Unrelated publications do not revoke local evidence or force it to rebuild.
        var peers=intersect(tile.baseX(),tile.baseZ(),tile.span());
        if(peers.size()!=input.peers().size())return false;
        // Hash bucket order can change when distant tiles arrive; membership
        // and immutable tile identity are the evidence, not iteration order.
        for(Tile peer:input.peers())if(tiles.get(peer.key())!=peer)return false;
        return true;
    }
    private java.util.function.LongPredicate publicationChange(Tile before,Tile after,boolean terrain,ChangeInput input) {
        // Darkness uses a propagated block-light halo; retain its complete
        // invalidation until the lighting owner can prove a narrower change.
        if(!input.precise())return pos->true;
        return new PredictionVoxyChange(before,after,input.peers(),terrain);
    }
    private static it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap prepareInvalidation(ChangeInput input,java.util.function.LongPredicate change) {
        var decisions=new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
        decisions.defaultReturnValue(true);
        for(long pos:input.positions())decisions.put(pos,change.test(pos));
        return decisions;
    }
    private void notifyChange(Footprint area, int[] height,java.util.function.LongPredicate change) {
        if (height[0] < height[1]) VoxyPredictionBridge.changed(this,
                area.minX() - 16, height[0] - 16, area.minZ() - 16,
                area.maxX() + 16, height[1] + 16, area.maxZ() + 16,change);
    }
    synchronized long bytes() { return tileBytes + mappedBytes + columnBytes + faceBytes; }
    synchronized Map<String, Object> diagnostics() {
        return Map.ofEntries(Map.entry("voxelTiles",tiles.size()),Map.entry("terrainBytes",tileBytes-featureBytes-featureIndexBytes),
                Map.entry("featureBytes",featureBytes),Map.entry("featureIndexBytes",featureIndexBytes),Map.entry("featureCount",featureCount),
                Map.entry("terrainRangeReductions",terrainRangeReductions),
                Map.entry("cachedSections", mapped.size()), Map.entry("cacheHits", cacheHits.sum()), Map.entry("sectionBuilds", sectionBuilds.sum()), Map.entry("lightingBuilds", lightingBuilds.sum()),
                Map.entry("coalescedReads", coalesced.sum()), Map.entry("emptyReads", emptyReads.sum()),
                Map.entry("mappedBytes", mappedBytes),Map.entry("sectionCacheLimit",sectionLimit),
                Map.entry("cachedColumns",columnCachedCount),Map.entry("columnCacheLimit",columnLimit),Map.entry("faceCacheLimit",faceLimit), Map.entry("cacheEvictions", mappedEvictions), Map.entry("cacheInvalidations", mappedInvalidations),
                Map.entry("columnSecondChances",columnSecondChances),Map.entry("columnCacheBytes",columnBytes),Map.entry("columnBuilds",columnBuilds.sum()),
                Map.entry("invalidationCandidates",cachedPositions.candidates()),Map.entry("indexedCachedPositions",cachedPositions.size()),
                Map.entry("columnCacheHits",columnHits.sum()),Map.entry("faceBuilds",faceBuilds.sum()),
                Map.entry("directSectionWrites",directWrites.sum()),
                Map.entry("columnBuildNanos",columnBuildNanos.sum()),Map.entry("columnWaitNanos",columnWaitNanos.sum()),
                Map.entry("sectionBuildNanos",sectionBuildNanos.sum()),Map.entry("sectionWaitNanos",sectionWaitNanos.sum()),
                Map.entry("faceBuildNanos",faceBuildNanos.sum()),Map.entry("invalidatedBuilds",invalidatedBuilds),
                Map.entry("mapperRetirements",mapperRetirements),Map.entry("columnEvictions",columnEvictions),
                Map.entry("featureQueries",featureQueries.sum()),Map.entry("skippedFeatureTiles",skippedFeatureTiles.sum()),
                Map.entry("columnCachePages",reducedColumns.size()),Map.entry("columnPageBuilds",columnPageBuilds.sum()),Map.entry("unchangedPublications",unchangedPublications),Map.entry("retainedColumnPublications",retainedColumnPublications),
                Map.entry("cachedFaces",mappedFaces.size()),Map.entry("faceCacheBytes",faceBytes),
                Map.entry("faceCacheHits",faceCacheHits),Map.entry("faceCacheEvictions",faceCacheEvictions),
                Map.entry("detailRequests", details.pending()+details.residents()), Map.entry("detailEvictions", details.evicted()),
                Map.entry("detailDeferred", details.deferred()), Map.entry("detailPending",details.pending()),
                Map.entry("viewDemandBuilds",viewDemandBuilds),Map.entry("viewDemandTargets",viewDemandTargets),
                Map.entry("detailWaiting",details.waiting()),
                Map.entry("detailResidents",details.residents()),Map.entry("detailDormant",details.dormant()),Map.entry("protectedSections",protectedMapped.size()),
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
    private void invalidate(Footprint area,int minY,int maxY,it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap decisions) {
        int reach = lastMapper != null && !lastMapper.hasSkyLight() ? PredictionVoxyLighting.REACH : 0;
        // New builds can arrive during preparation. Retire uncaptured candidates
        // conservatively too; they must never cache pre-publication geometry.
        for(long pos:cachedPositions.intersect((long)area.minX()-reach,(long)minY-reach,(long)area.minZ()-reach,
                (long)area.maxX()+reach,(long)maxY+reach,(long)area.maxZ()+reach))if(decisions.get(pos)) {
            for(int face=0;face<6;face++)removeFace(new FaceKey(pos,face));
            var build=mapped.get(pos);
            if(build!=null) {
                if(!build.result.isDone())invalidatedBuilds++;
                mappedInvalidations++;removeMapped(pos);
            }
        }
    }
    private static long pack(int x,int z) { return (long)x<<32 | z&0xffffffffL; }
    private void releaseMapped(SectionBuild build) { if (build != null && build.retention != null) {
        mappedBytes -= build.bytes; build.retention.close(); build.retention = null; } }
    private void clearMapped() { mapped.values().forEach(this::releaseMapped); mapped.clear(); probation.clear(); protectedMapped.clear();
        mappedFaces.values().forEach(this::releaseFace);mappedFaces.clear();cachedPositions.clear();
        while(columnClock!=null)removeColumn(columnClock.key); }
    private void releaseFace(FaceBuild face) {
        if(face.retention!=null) {face.retention.close();face.retention=null;faceBytes-=face.bytes;}
    }
    private void evictFace() {
        var iterator=mappedFaces.entrySet().iterator();var entry=iterator.next();iterator.remove();
        cachedPositions.remove(entry.getKey().position());releaseFace(entry.getValue());faceCacheEvictions++;
    }
    private void removeFace(FaceKey key) {
        var face=mappedFaces.remove(key);if(face!=null) {cachedPositions.remove(key.position());releaseFace(face);}
    }
    private void removeColumn(ColumnKey key) {
        var entry=reducedColumns.remove(key);
        if(entry!=null) {
            if(entry.next==entry)columnClock=null;
            else {entry.previous.next=entry.next;entry.next.previous=entry.previous;
                if(columnClock==entry)columnClock=entry.next;}
            entry.previous=null;entry.next=null;
            columnBytes-=entry.bytes();columnCachedCount-=entry.runs.columns;entry.lease.close();
        }
        var keys=tileColumns.get(key.columns);
        if(keys!=null) {keys.remove(key);if(keys.isEmpty())tileColumns.remove(key.columns);}
    }
    private void retireColumns(Tile tile) {
        var keys=tileColumns.get(tile.columns());
        if(keys!=null)for(var key:List.copyOf(keys))removeColumn(key);
    }
    private PredictionVoxyColumnRuns.Page reducedColumn(Tile tile,int step,int minX,int minZ,int maxX,int maxZ,
            VoxyPredictionBridge.Mapper mapper,PredictionVoxyMaterials materials,boolean paged,
            Map<ColumnKey,PredictionVoxyColumnRuns.Page> local) {
        var probe=COLUMN_PROBE.get();probe.columns=tile.columns();probe.axis=tile.axis();probe.mapper=mapper;
        probe.step=step;probe.minX=minX;probe.minZ=minZ;probe.maxX=maxX;probe.maxZ=maxZ;probe.paged=paged;
        CachedColumn hit;
        try {var page=local.get(probe);if(page!=null) {columnHits.increment();return page;}hit=reducedColumns.get(probe);}
        finally {probe.columns=null;probe.mapper=null;}
        if(hit!=null) {hit.referenced=true;columnHits.increment();return hit.runs;}
        var key=new ColumnKey(tile.columns(),tile.axis(),mapper,step,minX,minZ,maxX,maxZ,paged);
        var future=new java.util.concurrent.CompletableFuture<PredictionVoxyColumnRuns.Page>();
        var pending=reducingColumns.putIfAbsent(key,future);
        if(pending!=null) {columnHits.increment();long started=System.nanoTime();
            try {var result=pending.join();local.put(key,result);return result;}finally {columnWaitNanos.add(System.nanoTime()-started);} }
        long started=System.nanoTime();
        try {
            var result=reduceColumn(tile,key,mapper,materials);local.put(key,result);future.complete(result);return result;
        } catch(Throwable failure) {future.completeExceptionally(failure);throw failure;}
        finally {columnBuildNanos.add(System.nanoTime()-started);reducingColumns.remove(key,future);}
    }
    private PredictionVoxyColumnRuns.Page reduceColumn(Tile tile,ColumnKey key,VoxyPredictionBridge.Mapper mapper,PredictionVoxyMaterials materials) {
        var already=reducedColumns.get(key);if(already!=null)return already.runs;
        int step=key.step;
        var scratch=COLUMN_SCRATCH.get();if(scratch.busy)scratch=new ColumnScratch();scratch.busy=true;
        var builder=scratch.builder;builder.reset();
        try {
        int ratio=step/tile.step();
        // Exact grid-aligned pages use up to 8 x 8 output columns. A partial or
        // unaligned footprint is still reduced exactly as a one-column page.
        boolean paged=key.paged;
        int stride=paged ? ratio : Math.max(key.maxX-key.minX+1,key.maxZ-key.minZ+1);
        int across=paged ? (key.maxX-key.minX)/stride+1 : 1;
        int down=paged ? (key.maxZ-key.minZ)/stride+1 : 1;
        var page=new PredictionVoxyColumnRuns.PageBuilder(across*down);
        for(int z=0;z<down;z++)for(int x=0;x<across;x++) {
            int minX=key.minX+x*stride,minZ=key.minZ+z*stride;
            int maxX=paged ? Math.min(key.maxX,minX+stride-1) : key.maxX;
            int maxZ=paged ? Math.min(key.maxZ,minZ+stride-1) : key.maxZ;
            builder.reset();
        if(minX==maxX && minZ==maxZ) {
            var column=tile.columns()[minZ*tile.axis()+minX];
            for(var run:column.runs()) {
                int bottom=Math.floorDiv(run.bottom(),step),top=-Math.floorDiv(-run.top(),step);
                long value=dev.xantha.vss.compat.VoxyFluidSurface.body(materials.map(run.state(),column.biome()),run.state(),step);
                long surface=dev.xantha.vss.compat.VoxyFluidSurface.top(value,run.state(),run.top(),step);
                if(surface==value)builder.put(bottom,top,value);
                else {builder.put(bottom,top-1,value);builder.put(top-1,top,surface);}
            }
        } else {
            var coverage=scratch.coverage;coverage.reset();
            Column previous=null;
            int width=maxX-minX+1,height=maxZ-minZ+1,count=width*height;
            for(int n=0;n<count;n++) {
                int at=count-1-n;
                var column=tile.columns()[(minZ+at/width)*tile.axis()+minX+at%width];
                if(column==previous)continue;
                previous=column;
                var runs=column.runs();
                for(int i=0;i<runs.size();i++) {
                    var run=runs.get(runs.size()-1-i);
                    int bottom=Math.floorDiv(run.bottom(),step),top=-Math.floorDiv(-run.top(),step);
                    if(coverage.covers(bottom,top))continue;
                    long value=dev.xantha.vss.compat.VoxyFluidSurface.body(materials.map(run.state(),column.biome()),run.state(),step);
                    long surface=dev.xantha.vss.compat.VoxyFluidSurface.top(value,run.state(),run.top(),step);
                    if(surface==value)coverage.put(builder,bottom,top,value);
                    else {coverage.put(builder,top-1,top,surface);coverage.put(builder,bottom,top-1,value);}
                }
            }
        }
            page.append(builder);
        }
        var result=page.build();columnBuilds.add(result.columns);columnPageBuilds.increment();

        synchronized(this) {
            var hit=reducedColumns.get(key);if(hit!=null)return hit.runs;
            Tile resident=tiles.get(tile.key());
            if(closed || lastMapper!=mapper || resident==null || resident.columns()!=key.columns)return result;
            long bytes=result.bytes()+384L;
            if(bytes<=columnLimit) {
                while(columnClock!=null && columnBytes+bytes>columnLimit)evictColumn();
                var lease=retainSection(bytes);
                if(lease!=null) {
                    var entry=new CachedColumn(key,result,lease);
                    if(columnClock==null) {entry.previous=entry;entry.next=entry;columnClock=entry;}
                    else {entry.next=columnClock;entry.previous=columnClock.previous;
                        columnClock.previous.next=entry;columnClock.previous=entry;}
                    reducedColumns.put(key,entry);columnBytes+=bytes;columnCachedCount+=result.columns;
                    tileColumns.computeIfAbsent(tile.columns(),ignored->new HashSet<>()).add(key);
                }
            }
        }
        return result;
        } finally {scratch.busy=false;}
    }
    @Override public synchronized void releaseMapper(VoxyPredictionBridge.Mapper mapper) {
        if (lastMapper != mapper) return;
        clearMapped(); lastMapper = null; mapperRetirements++;
    }
    private void evictColumn() {
        // Bound the scan even when concurrent readers keep refreshing every entry.
        int remaining=reducedColumns.size();
        while(remaining-->0) {
            var entry=columnClock;
            if(!entry.referenced) {columnEvictions++;removeColumn(entry.key);return;}
            entry.referenced=false;columnClock=entry.next;columnSecondChances++;
        }
        columnEvictions++;removeColumn(columnClock.key);
    }
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
                build = new SectionBuild(); mapped.put(pos, build);cachedPositions.add(pos);probation.put(pos,build); owner = true;
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
                    while (mappedBytes + build.bytes > sectionLimit) {
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
        long[] target=new long[1024];return copyFace(pos,mapper,face,target)?target:null;
    }
    @Override public boolean copyFace(long pos,VoxyPredictionBridge.Mapper mapper,int face,long[] target) {
        if(face<0 || face>=6)throw new IllegalArgumentException("face");
        // Block-light propagation needs its complete halo; retain the proven volume path there.
        if(!mapper.hasSkyLight()) {
            var data=VoxyPredictionBridge.Source.super.face(pos,mapper,face);if(data==null)return false;
            System.arraycopy(data,0,target,0,1024);return true;
        }
        List<Tile> snapshot=null;SectionBuild cached;FaceBuild faceBuild=null;boolean owner=false;
        var key=new FaceKey(pos,face);
        synchronized(this) {
            if(closed || !mapper.isOpen())return false;
            if(lastMapper!=mapper) {if(lastMapper!=null)mapperRetirements++;clearMapped();lastMapper=mapper;}
            cached=mapped.get(pos);
            if(cached!=null)touchMapped(pos,cached);
            else {
                faceBuild=mappedFaces.get(key);
                if(faceBuild!=null)faceCacheHits++;
                else {
                int span=32<<VoxyPredictionBridge.level(pos);
                snapshot=intersect((long)VoxyPredictionBridge.x(pos)*span,(long)VoxyPredictionBridge.z(pos)*span,span);
                if(snapshot.isEmpty())return false;
                long minY=(long)VoxyPredictionBridge.y(pos)*span,maxY=minY+span;
                if(face>>1==1) {minY+=(long)(face&1)*31*(span/32);maxY=minY+span/32;}
                boolean occupied=false;
                for(Tile tile:snapshot) {var extent=extents.get(tile.key());
                    if(minY<extent.max() && maxY>extent.min()) {occupied=true;break;} }
                if(!occupied) {emptyReads.increment();Arrays.fill(target,15L<<56);return true;}
                while(mappedFaces.size()>=MAX_FACES)evictFace();
                faceBuild=new FaceBuild();mappedFaces.put(key,faceBuild);cachedPositions.add(pos);owner=true;
                }
            }
        }
        if(cached!=null) {awaitSection(cached).copyFace(face,target);cacheHits.increment();return true;}
        if(!owner) {faceBuild.result.join().copyTo(target);return true;}
        long[] result=FACE_BUFFERS.poll();if(result==null)result=new long[1024];
        int lod=VoxyPredictionBridge.level(pos),span=32<<lod;
        long started=System.nanoTime();
        try {
        buildVolume(VoxyPredictionBridge.x(pos)*span,VoxyPredictionBridge.y(pos)*span,VoxyPredictionBridge.z(pos)*span,
                1<<lod,32,mapper,snapshot,result,null,face);
        faceBuilds.increment();
        var compact=dev.xantha.vss.compat.VoxySectionData.compactFaceCopy(result);
        long bytes=compact.bytes()+256;
        synchronized(this) {
            if(mappedFaces.get(key)==faceBuild) {
                while(!mappedFaces.isEmpty() && faceBytes+bytes>faceLimit)evictFace();
                if(mappedFaces.get(key)==faceBuild && bytes<=faceLimit)
                    faceBuild.retention=retainSection(bytes);
                if(faceBuild.retention==null)removeFace(key);
                else {faceBuild.bytes=bytes;faceBytes+=bytes;}
            }
        }
        faceBuild.result.complete(compact);System.arraycopy(result,0,target,0,1024);return true;
        } catch(Throwable failure) {
            synchronized(this) {if(mappedFaces.get(key)==faceBuild)removeFace(key);}
            faceBuild.result.completeExceptionally(failure);throw failure;
        } finally {FACE_BUFFERS.offer(result);faceBuildNanos.add(System.nanoTime()-started);}
    }
    private static void selectOwners(List<Tile> tiles,int bx,int bz,int step,int width,int x0,int x1,int z0,int z1,Tile[] result) {
        int stride=x1-x0;
        Arrays.fill(result,0,stride*(z1-z0),null);
        for(Tile tile:tiles) {
            // Match the original center-point ownership, including negative coordinates and odd steps.
            int minX=Math.max(x0,-Math.floorDiv(bx+step/2-tile.baseX(),step));
            int minZ=Math.max(z0,-Math.floorDiv(bz+step/2-tile.baseZ(),step));
            int maxX=Math.min(x1,-Math.floorDiv(bx+step/2-tile.baseX()-tile.span(),step));
            int maxZ=Math.min(z1,-Math.floorDiv(bz+step/2-tile.baseZ()-tile.span(),step));
            for(int z=minZ;z<maxZ;z++)for(int x=minX;x<maxX;x++)
                if(result[(z-z0)*stride+x-x0]==null)result[(z-z0)*stride+x-x0]=tile;
        }
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
        var window=horizon;
        var scratch=VOLUME_SCRATCH.get();if(scratch.busy)scratch=new VolumeScratch();scratch.busy=true;
        try {
        var cachedMapper = scratch.materials;cachedMapper.reset(mapper);
        // Only completed placement can own known air. Partial cache results
        // contribute their actual boxes without hiding other known vegetation.
        var featureTiles=scratch.featureTiles;
        for(var tile:snapshot)if(tile.surfaceComplete())featureTiles.add(tile);
        int plane=(face&1)*31;
        int x0=face>=0 && face>>1==0 ? plane : 0,x1=face>=0 && face>>1==0 ? plane+1 : width;
        int z0=face>=0 && face>>1==2 ? plane : 0,z1=face>=0 && face>>1==2 ? plane+1 : width;
        int y0=face>=0 && face>>1==1 ? plane : 0,y1=face>=0 && face>>1==1 ? plane+1 : width;
        int area=(x1-x0)*(z1-z0);
        if(scratch.owners.length<area) {scratch.owners=new Tile[area];scratch.featureOwners=new Tile[area];}
        Tile[] owners=scratch.owners,featureOwners=scratch.featureOwners;
        selectOwners(snapshot,bx,bz,step,width,x0,x1,z0,z1,owners);
        selectOwners(featureTiles,bx,bz,step,width,x0,x1,z0,z1,featureOwners);
        for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++) {
            Tile tile=owners[(z-z0)*(x1-x0)+x-x0];
            if(tile!=null) {
                // Reduce the complete voxel footprint so narrow peaks survive native coarse LODs.
                int firstX=Math.max(0,Math.floorDiv(bx+x*step-tile.baseX(),tile.step()));
                int firstZ=Math.max(0,Math.floorDiv(bz+z*step-tile.baseZ(),tile.step()));
                int lastX=Math.min(tile.axis()-1,Math.floorDiv(bx+(x+1)*step-1-tile.baseX(),tile.step()));
                int lastZ=Math.min(tile.axis()-1,Math.floorDiv(bz+(z+1)*step-1-tile.baseZ(),tile.step()));
                if(lighting==null && mapper.hasSkyLight() && (firstX!=lastX || firstZ!=lastZ)) {
                    int minX=firstX,minZ=firstZ,maxX=lastX,maxZ=lastZ,index=0;
                    int ratio=step/tile.step();
                    boolean aligned=ratio>0 && step%tile.step()==0
                            && Math.floorMod(bx-tile.baseX(),step)==0 && Math.floorMod(bz-tile.baseZ(),step)==0;
                    if(aligned) {
                        int pageStep=ratio*8;
                        minX=firstX/pageStep*pageStep;minZ=firstZ/pageStep*pageStep;
                        maxX=Math.min(tile.axis()-1,minX+pageStep-1);maxZ=Math.min(tile.axis()-1,minZ+pageStep-1);
                        index=(firstZ-minZ)/ratio*((maxX-minX)/ratio+1)+(firstX-minX)/ratio;
                    }
                    var runs=reducedColumn(tile,step,minX,minZ,maxX,maxZ,mapper,cachedMapper,aligned,scratch.columnPages);
                    runs.write(index,result,x,z,Math.floorDiv(by,step),width,face);
                } else {
                for(int cz=firstZ;cz<=lastZ;cz++) for(int cx=firstX;cx<=lastX;cx++) {
                Column column = tile.columns()[cz * tile.axis() + cx];
                int materialOffset=scratch.mapColumn(column);
                for (int r = 0; r < column.runs().size(); r++) {
                    Run run = column.runs().get(r);
                    int bottom = Math.max(y0, Math.floorDiv(run.bottom() - by, step));
                    int top = Math.min(y1, -Math.floorDiv(by - run.top(), step));
                    if (bottom >= top) continue;
                    long value = dev.xantha.vss.compat.VoxyFluidSurface.body(scratch.mappedRuns[materialOffset+r],run.state(),step);
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
        // Establish contribution before querying/decompressing a forest inherited by
        // a parent that no longer owns any of this section's sample columns.
        var contributors=scratch.featureContributors;
        for(int i=0;i<area;i++) {
            var terrain=owners[i];var plants=featureOwners[i];
            if(terrain!=null && terrain.cityFeatures()>0)contributors.merge(terrain,1,(a,b)->a|b);
            if(plants!=null && plants.hasVegetation())contributors.merge(plants,2,(a,b)->a|b);
        }
        for(Tile tile:snapshot)if(!tile.surfaceComplete() && tile.hasVegetation()) {
            int minX=Math.max(x0,-Math.floorDiv(bx+step/2-tile.baseX(),step));
            int minZ=Math.max(z0,-Math.floorDiv(bz+step/2-tile.baseZ(),step));
            int maxX=Math.min(x1,-Math.floorDiv(bx+step/2-tile.baseX()-tile.span(),step));
            int maxZ=Math.min(z1,-Math.floorDiv(bz+step/2-tile.baseZ()-tile.span(),step));
            boolean contributes=false;
            for(int z=minZ;z<maxZ && !contributes;z++)for(int x=minX;x<maxX;x++)
                if(featureOwners[(z-z0)*(x1-x0)+x-x0]==null) {contributes=true;break;}
            if(contributes)contributors.merge(tile,2,(a,b)->a|b);
        }
        // Features retain their horizontal footprint; a one-block trunk must not fill a whole coarse sampling cell.
        // Finer partial blocks override overlapping coarse blocks, never their unknown air.
        for (int tileIndex=snapshot.size()-1;tileIndex>=0;tileIndex--) {
            Tile tile=snapshot.get(tileIndex);
            int contribution=contributors.getOrDefault(tile,0);
            if(contribution==0) {if(!tile.features().isEmpty())skippedFeatureTiles.increment();continue;}
            featureQueries.increment();
            tile.featureIndex().intersect(
                bx+x0*step-tile.baseX(),by+y0*step,bz+z0*step-tile.baseZ(),
                bx+x1*step-tile.baseX(),by+y1*step,bz+z1*step-tile.baseZ(),scratch.hits);
            for(int selected=0;selected<scratch.hits.size();selected++) {
            int featureIndex=scratch.hits.getInt(selected);
            if((contribution & (featureIndex<tile.cityFeatures()?1:2))==0)continue;
            var features=tile.boxes();
            int fx=features.coordinate(featureIndex,0),fy=features.coordinate(featureIndex,1),fz=features.coordinate(featureIndex,2);
            int ex=features.coordinate(featureIndex,3),ey=features.coordinate(featureIndex,4),ez=features.coordinate(featureIndex,5);
            BlockState state=features.state(featureIndex);
            boolean city=featureIndex<tile.cityFeatures();
            Tile[] selectedOwners = city ? owners : featureOwners;
            int minX=Math.max(x0,Math.floorDiv(tile.baseX()+fx-bx,step));
            int minZ=Math.max(z0,Math.floorDiv(tile.baseZ()+fz-bz,step));
            int maxX=Math.min(x1,-Math.floorDiv(bx-tile.baseX()-ex,step));
            int maxZ=Math.min(z1,-Math.floorDiv(bz-tile.baseZ()-ez,step));
            int minY=Math.max(y0,Math.floorDiv(fy-by,step)), maxY=Math.min(y1,-Math.floorDiv(by-ey,step));
            if(minX>=maxX || minZ>=maxZ || minY>=maxY) continue;
            int tx=Math.max(0,Math.min(tile.axis()-1,Math.floorDiv(fx,tile.step())));
            int tz=Math.max(0,Math.min(tile.axis()-1,Math.floorDiv(fz,tile.step())));
            long value=dev.xantha.vss.compat.VoxyFluidSurface.body(cachedMapper.map(state,tile.columns()[tz*tile.axis()+tx].biome()),state,step);
            int surfaceY=Math.floorDiv(ey-1-by,step);
            long surface=dev.xantha.vss.compat.VoxyFluidSurface.top(value,state,ey,step);
            byte opacity = lighting == null ? 0 : lighting.opacity(state);
            for(int z=minZ;z<maxZ;z++) for(int x=minX;x<maxX;x++) {
                int columnIndex=(z-z0)*(x1-x0)+x-x0;
                Tile selectedOwner=selectedOwners[columnIndex];
                if(city ? selectedOwner!=tile : !vegetationVisible(tile,selectedOwner,
                        bx+x*step+step/2,bz+z*step+step/2))continue;
                for(int y=minY;y<maxY;y++) {
                    int index=outputIndex(width,face,x,y,z); result[index]=y==surfaceY ? surface : value;
                    if(lighting!=null) lighting.opacity[index]=opacity;
                }
            }
        }
        }
        // Ancestors cover much larger squares than the requested circle. Clip
        // their terrain and features together, including border-only reads.
        if(window!=null)for(int z=z0;z<z1;z++)for(int x=x0;x<x1;x++)
            if(!window.intersects((double)bx+x*step,(double)bz+z*step,step))for(int y=y0;y<y1;y++) {
                int index=outputIndex(width,face,x,y,z);result[index]=mapper.hasSkyLight()?15L<<56:0;
                if(lighting!=null)lighting.opacity[index]=0;
            }
        } finally {scratch.clear();}
    }
    public void close() {
        synchronized (this) { closed = true; tiles.clear(); tileBytes = 0;featureBytes=0;featureIndexBytes=0;featureCount=0; spatial.clear(); extents.clear(); details.clear(); meshDetails.clear();
            detailSnapshot = Set.of(); detailChanged = false; clearMapped(); lastMapper = null;
            mapVersions.clear(); ++mapRevision; }
        VoxyPredictionBridge.detach(this);
        XaeroPredictionBridge.detach(this);
    }
}
