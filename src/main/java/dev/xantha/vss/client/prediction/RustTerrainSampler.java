package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;

/** Source-built Rust terrain, surface and tint backend. Work runs on prediction builders. */
final class RustTerrainSampler extends ClientTerrainSampler implements AutoCloseable {
    static final String ALGORITHM = "vanilla-rust-abi6-interior-r1";
    private static boolean attempted, loaded;
    private volatile long world;
    private volatile boolean cancelled;
    private final ClientTerrainSampler context;
    private final boolean terrablenderRouting;
    private final boolean signedSqrt;
    private final BlockState[] states;
    private final int[] blocks;
    private final String[][] featureOrder;
    private final Map<BlockState,Integer> stateIds = new IdentityHashMap<>();
    private final Map<String,Boolean> support = new ConcurrentHashMap<>();
    private final PredictionTintCache tints = new PredictionTintCache(65536);
    private final java.util.concurrent.atomic.LongAdder tintNativeCalls = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder tintSeeded = new java.util.concurrent.atomic.LongAdder();
    private volatile long colorFingerprint = Long.MIN_VALUE;
    private final java.util.concurrent.atomic.LongAdder nativeFeatures = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder javaFeatures = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder gridCacheHits = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder gridComputedPoints = new java.util.concurrent.atomic.LongAdder();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<int[]> points =
            new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>(1024);
    private final ThreadLocal<ByteBuffer> scratch = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(256 * 40).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<ByteBuffer> positions = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(64 * 8).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<ByteBuffer> wallColumn = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(16 + profile().height() * 4).order(ByteOrder.LITTLE_ENDIAN));

    static synchronized boolean available() {
        if (attempted) return loaded;
        attempted = true;
        VssNativePlatform platform = VssNativePlatform.current();
        if (platform == null) return false;
        String file = platform.fileName();
        String resource = "META-INF/vss-natives/" + platform.id() + "/" + file;
        try (var input = RustTerrainSampler.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) return false;
            Path dir = Files.createTempDirectory("vss-worldgen-");
            Path path = dir.resolve(file);
            Files.copy(input, path);
            path.toFile().deleteOnExit(); dir.toFile().deleteOnExit();
            RustWorldgenBackend.load(path);
            loaded = true;
        } catch (Exception | LinkageError failure) {
            VSSLogger.warn("VSS native terrain library unavailable; prediction keeps the Java sampler: " + failure);
        }
        return loaded;
    }

    static ClientTerrainSampler open(DimensionProfile profile, JsonObject generator, JsonObject registries, ClientTerrainSampler context) {
        try (var shared = new RustWorldgenDocument.SharedInputs()) {
            return open(profile, generator, registries, context, shared);
        }
    }

    static ClientTerrainSampler open(DimensionProfile profile, JsonObject generator, JsonObject registries,
            ClientTerrainSampler context, RustWorldgenDocument.SharedInputs shared) {
        if (!available() || context == null) return null;
        var timing = new PredictionInitializationTiming(profile.dimension() + " native");
        long handle = 0;
        try {
            JsonObject document = RustWorldgenDocument.create(generator, registries, context, shared);
            timing.mark("sharedInputsAndDocument");
            String serialized = document.toString();
            timing.mark("serialize");
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS native document for "
                    + profile.dimension() + ": chars=" + serialized.length());
            handle = RustWorldgenBackend.create(profile.seed(), BiomeManager.obfuscateSeed(profile.seed()), serialized);
            timing.mark("nativeCreate");
            RustTerrainSampler sampler = new RustTerrainSampler(handle, profile, context, shared.canonicalStates());
            if (generator.has("vss_blueprint")
                    && generator.getAsJsonObject("vss_blueprint").has("original_terrablender")
                    && !sampler.terrablenderRouting())
                throw new IllegalStateException("Native library lacks wrapped TerraBlender routing");
            timing.mark("stateMapping");
            sampler.colorFingerprint = colormapFingerprint(document);
            handle = 0;
            timing.finish();
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction backend=" + ALGORITHM + ", dimension=" + profile.dimension());
            return sampler;
        } catch (Exception | LinkageError failure) {
            VSSLogger.warn("VSS native terrain snapshot rejected for " + profile.dimension()
                    + "; prediction keeps the Java sampler: " + failure);
            return null;
        } finally {
            if (handle != 0) RustWorldgenBackend.close(handle);
        }
    }

    RustTerrainSampler(long world, DimensionProfile profile, ClientTerrainSampler context) {
        this(world, profile, context, Map.of(), null);
    }

    RustTerrainSampler(long world, DimensionProfile profile, ClientTerrainSampler context,
            Map<JsonElement, BlockState> stateLookup) {
        this(world, profile, context, stateLookup, null);
    }
    RustTerrainSampler(long world, DimensionProfile profile, ClientTerrainSampler context, BlockState[] canonical) {
        this(world, profile, context, Map.of(), canonical);
    }
    private RustTerrainSampler(long world, DimensionProfile profile, ClientTerrainSampler context,
            Map<JsonElement, BlockState> stateLookup, BlockState[] canonical) {
        super(profile.seed(), profile);
        this.world = world; this.context = context;
        var timing = new PredictionInitializationTiming(profile.dimension() + " stateMapping");
        String encoded = canonical == null ? RustWorldgenBackend.describe(world) : RustWorldgenBackend.describeCompact(world);
        timing.mark("nativeDescribe");
        JsonObject described = JsonParser.parseString(encoded).getAsJsonObject();
        timing.mark("parse");
        this.signedSqrt = described.has("signed_sqrt") && described.get("signed_sqrt").getAsBoolean();
        // The native reports whether it actually replayed the TerraBlender
        // region routing; older native libraries silently ignore the section,
        // so the backend must not trust their surface materials.
        this.terrablenderRouting = described.has("terrablender_routing")
                && described.get("terrablender_routing").getAsBoolean();
        var table = described.getAsJsonArray(canonical == null ? "states" : "state_sources");
        states = new BlockState[table.size()]; blocks = new int[states.length];
        if (canonical != null) {
            for (int i = 0; i < states.length; i++) {
                int source = table.get(i).getAsInt();
                if (source < -1 || source >= canonical.length) throw new IllegalArgumentException("Invalid native state source");
                if (source >= 0) states[i] = canonical[source];
            }
            for (var entry : described.getAsJsonArray("extra_states")) {
                var row = entry.getAsJsonArray(); int index = row.get(0).getAsInt();
                if (index < 0 || index >= states.length || states[index] != null)
                    throw new IllegalArgumentException("Invalid native extra state");
                states[index] = decodeState(row.get(1));
            }
        }
        for (int i = 0; i < states.length; i++) {
            if (canonical == null) {
                states[i] = stateLookup.get(table.get(i));
                if (states[i] == null) states[i] = decodeState(table.get(i));
            }
            if (states[i] == null) throw new IllegalArgumentException("Missing native state mapping");
            blocks[i] = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(states[i].getBlock());
            stateIds.put(states[i], i);
        }
        timing.mark("resolve");
        featureOrder = new Gson().fromJson(RustWorldgenBackend.schedule(world), String[][].class);
        timing.mark("schedule"); timing.finish();
    }
    private static BlockState decodeState(JsonElement state) {
        return BlockState.CODEC.parse(JsonOps.INSTANCE, state).getOrThrow();
    }
    long handle() { long id = world; if (id == 0 || cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Native sampler closed/cancelled"); return id; }
    /** Whether this native context replays TerraBlender positional region routing. */
    boolean terrablenderRouting() { return terrablenderRouting; }
    BlockState[] states() { return states; }
    String[][] featureOrder() { return featureOrder; }
    int stateId(BlockState state) { return stateIds.getOrDefault(state,-1); }
    boolean supports(String name) {
        return support.computeIfAbsent(name,key -> JsonParser.parseString(
                RustWorldgenBackend.support(handle(),new Gson().toJson(key),1))
                .getAsJsonObject().get("transactional").getAsBoolean());
    }
    /** Same immutable surface proxy used by the Rust vegetation volume. */
    BlockState proxyBlock(int x,int y,int z) {
        return proxyBlock(point(x,z), y);
    }
    /** Read-only native record; a bounded decoration job may retain it across shared-cache eviction. */
    int[] surfaceRecord(int x, int z) { return point(x, z); }
    /** One aligned 4x4 page in x-major order, shared with native display queries.
     * Never insert these records in the exact Java point cache. */
    int[][] decorationDisplayPage(int x, int z) {
        long id = handle();
        ByteBuffer input = positions.get(), output = scratch.get(); input.clear();
        for (int i=0;i<16;i++) input.putInt(x+i/4).putInt(z+i%4);
        if (RustWorldgenBackend.decorationPoints(id,input,output,16)!=16)
            throw new IllegalStateException("Incomplete display decoration page");
        int[][] rows = new int[16][10];
        for (int i=0;i<16;i++) for (int j=0;j<10;j++) rows[i][j]=output.getInt((i*10+j)*4);
        return rows;
    }
    ClientColumnSample surfaceSample(int[] record) { return sampleRecord(record, 0); }
    BlockState proxyBlock(int[] c, int y) {
        if(y>=c[0]) {
            if(c[2]==0||y>=c[1]) return Blocks.AIR.defaultBlockState();
            if((c[3]&2)!=0&&y==c[1]-1) return Blocks.ICE.defaultBlockState();
            return (c[2]==2?Blocks.LAVA:Blocks.WATER).defaultBlockState();
        }
        if((c[3]&(1<<29))!=0) return Blocks.AIR.defaultBlockState();
        return states[c[y==c[0]-1?4:c[0]-1-y<4?5:6]];
    }
    private int[] colors(int x,int y,int z) {
        handle();
        long generation = tints.generation();
        long key = BlockPos.asLong(x,y,z);
        int[] cached = tints.get(key);
        if (cached != null) return cached;
        ByteBuffer out = scratch.get();
        tintNativeCalls.increment();
        if(RustWorldgenBackend.tints(handle(),x,y,z,out)!=3) throw new IllegalStateException("Incomplete native tint");
        int[] value={out.getInt(0),out.getInt(4),out.getInt(8)};
        tints.put(key, value, generation);
        return value;
    }
    /** Native record colors belong to fluidY-1 on wet columns, surfaceY otherwise.
     * Seed only that exact position: surface/foliage below water and water at fluidY
     * must still query their own biome. Never alias those two heights. */
    void rememberColors(int x, int z, int[] data, long generation) {
        // Display grids may interpolate interior height/color; those records must
        // never populate the exact-position tint cache used by fine terrain.
        if ((data[3] & ClientColumnSample.FLAG_APPROXIMATE) != 0) return;
        int y = data[2] != 0 ? data[1] - 1 : data[0];
        if (tints.put(BlockPos.asLong(x,y,z), new int[]{data[7],data[8],data[9]}, generation)) tintSeeded.increment();
    }
    long tintGeneration() { return tints.generation(); }
    long tintNativeCalls() { return tintNativeCalls.sum(); }
    @Override long colorCacheFingerprint() { return colorFingerprint; }
    private static long colormapFingerprint(JsonObject maps) {
        return ((long) maps.get("grass_colormap").hashCode() << 32)
                ^ Integer.toUnsignedLong(maps.get("foliage_colormap").hashCode());
    }
    synchronized void reloadColormaps() throws java.io.IOException {
        replaceColormaps(RustWorldgenDocument.colormaps());
    }
    synchronized void replaceColormaps(JsonObject maps) {
        RustWorldgenBackend.colormaps(handle(),maps.toString());
        colorFingerprint=colormapFingerprint(maps);tints.clear();
        synchronized(points) {points.clear();}
    }
    void nativeFeatureCompleted() {nativeFeatures.increment();}
    void javaFeatureCompleted() {javaFeatures.increment();}
    // Exact records and vegetation are unchanged; keep existing world caches.
    // Display records carry explicit quality flags within the terrain payload.
    String cacheAlgorithm() { return "vanilla-rust-abi2-r3" + (signedSqrt ? ":signed-sqrt-r1" : ""); }

    String diagnostics() {return "backend="+ALGORITHM+",nativeFeatures="+nativeFeatures.sum()+",compatibilityFeatures="+javaFeatures.sum()
            +",tintCache={"+tints.diagnostics()+",nativeCalls="+tintNativeCalls.sum()+",seeded="+tintSeeded.sum()+"}"
            +",gridCacheHits="+gridCacheHits.sum()+",gridSubmittedPoints="+gridComputedPoints.sum()
            +","+RustVegetationStage.diagnostics()+",groundQueries="+groundDiagnostics();}
    private String groundDiagnostics() {
        long id=world;
        if(id==0 || cancelled) return "closed";
        try { return RustWorldgenBackend.decorationQueryStats(id); }
        catch (IllegalArgumentException closed) { return "closed"; }
    }

    private int[] point(int x, int z) {
        long key = (long) x << 32 | z & 0xffffffffL;
        synchronized (points) { int[] found = points.getAndMoveToLast(key); if (found != null) return found; }
        long tintGeneration = tints.generation();
        ByteBuffer input = positions.get(), output = scratch.get();
        input.putInt(0,x).putInt(4,z);
        if (RustWorldgenBackend.surfacePoints(handle(),input,output,1) != 1) throw new IllegalStateException("Incomplete native point");
        int[] data = new int[10]; for (int i=0;i<10;i++) data[i]=output.getInt(i*4);
        rememberColors(x,z,data,tintGeneration);
        rememberPoint(key,data); return data;
    }
    private void rememberPoint(long key,int[] data) {
        synchronized(points) { points.putAndMoveToLast(key,data); while(points.size()>65536) points.removeFirst(); }
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int axis) {
        return sampleGrid(x, z, spacing, axis, axis);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height) {
        return sampleGrid(x,z,spacing,width,height,new ClientColumnSample[width*height]);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained) {
        return sampleGrid(x,z,spacing,width,height,retained,false);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained,boolean preview) {
        return sampleGrid(x,z,spacing,width,height,retained,preview,false);
    }
    ClientColumnSample[] sampleDisplayGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained) {
        return sampleGrid(x,z,spacing,width,height,retained,false,true);
    }
    private ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained,boolean preview,boolean display) {
        if(width<1||width>8||height<1||height>8||spacing<1) throw new IllegalArgumentException("Native grid dimensions");
        if(retained.length!=width*height) throw new IllegalArgumentException("Retained grid dimensions");
        handle(); // Cached requests must still honor world cancellation.
        ClientColumnSample[] result=retained.clone();
        ByteBuffer input=positions.get(),output=scratch.get();input.clear();
        int[] missing=new int[result.length];
        int count=0;
        for(int i=0;i<result.length;i++) {
            if(result[i]!=null && !(display ? result[i].reusableForDisplay() : result[i].reusableFor(preview))) result[i]=null;
            if(result[i]!=null) continue;
            int xx=x+i%width*spacing,zz=z+i/width*spacing;
            long key=(long)xx<<32|zz&0xffffffffL;
            int[] cached;
            synchronized(points) { cached=points.getAndMoveToLast(key); }
            if(cached!=null) { result[i]=sampleRecord(cached,0); continue; }
            missing[count++]=i;
        }
        // Refinement grids share aligned columns with their parents. Only
        // submit cache misses; previously every stage recomputed those columns.
        gridCacheHits.add(result.length-count);
        if(count==0) return result;
        // Surface refinement computes only requested columns. The native
        // batch shares noise/aquifer context without generating a full chunk.
        for(int n=0;n<count;n++) {
            int i=missing[n],xx=x+i%width*spacing,zz=z+i/width*spacing;
            input.putInt(xx).putInt(zz);
        }
        long tintGeneration = tints.generation();
        int written=display ? RustWorldgenBackend.displayPoints(handle(),input,output,count)
                : preview ? RustWorldgenBackend.previewPoints(handle(),input,output,count)
                : RustWorldgenBackend.surfacePoints(handle(),input,output,count);
        if(written!=count) throw new IllegalStateException("Incomplete native grid");
        gridComputedPoints.add(count);
        for(int n=0;n<count;n++) {
            int i=missing[n];
            int[] data=new int[10];for(int j=0;j<10;j++) data[j]=output.getInt((n*10+j)*4);
            int xx=x+i%width*spacing,zz=z+i/width*spacing;
            rememberColors(xx,zz,data,tintGeneration);
            if(!preview && (data[3] & ClientColumnSample.FLAG_APPROXIMATE)==0) rememberPoint((long)xx<<32|zz&0xffffffffL,data);
            result[i]=sampleRecord(data,0);
        }
        return result;
    }
    @Override public ClientColumnSample sample(int x, int z) {
        return sampleRecord(point(x,z),0);
    }
    private ClientColumnSample sampleRecord(int[] data,int i) {
        return new ClientColumnSample(data[i], data[i+1], -1, blocks[data[i+4]], 0, 0, 0, 0,
                data[i+2], data[i+3], 0, blocks[data[i+5]], blocks[data[i+6]],
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    @Override public ClientColumnSample sampleSurface(int x, int z) { return sample(x, z); }
    @Override PredictionColumnVolume exteriorColumn(int x, int z) {
        // Occupancy needs no biome/surface-rule pass or four neighboring surface queries.
        ByteBuffer input = positions.get(), output = wallColumn.get();
        input.putInt(0, x).putInt(4, z);
        if (RustWorldgenBackend.columns(handle(), input, output, 1) != 1)
            throw new IllegalStateException("Incomplete native exterior column");
        int min = context.interiorMinY(), height = output.getInt(12);
        if (height < 1 || height > profile().height()) throw new IllegalStateException("Exterior column height mismatch");
        int rock = PredictionMaterialPalette.stoneIndex();
        return PredictionColumnVolume.sample(min, height,
                y -> states[output.getInt(16 + (y - min) * 4)].isAir() ? -1 : rock, ignored -> 0);
    }
    private volatile boolean legacyExteriorBackend;
    @Override boolean[] exteriorFootprint(int x, int z, int step, int bottom, int top,
                                          java.util.function.BooleanSupplier valid) {
        if (top <= bottom) return null;
        if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        if (legacyExteriorBackend) return super.exteriorFootprint(x, z, step, bottom, top, valid);
        ByteBuffer output = wallColumn.get();
        final int count;
        try { count = RustWorldgenBackend.exteriorFootprint(handle(), x, z, step, bottom, top, output); }
        catch (UnsatisfiedLinkError oldLibrary) {
            legacyExteriorBackend = true;
            return super.exteriorFootprint(x, z, step, bottom, top, valid);
        }
        if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        if (count == -2) {
            legacyExteriorBackend = true;
            return super.exteriorFootprint(x, z, step, bottom, top, valid);
        }
        if (count == 0) return null;
        if (count != top - bottom) throw new IllegalStateException("Exterior footprint height mismatch");
        boolean[] occupied = new boolean[count];
        for (int i = 0; i < count; i++) occupied[i] = output.get(i) != 0;
        return occupied;
    }
    @Override ClientColumnSample sampleInterior(int x, int z) {
        ByteBuffer input = positions.get(), output = wallColumn.get();
        input.putInt(0, x).putInt(4, z);
        if (RustWorldgenBackend.interiorColumns(handle(), input, output, 1) != 1)
            throw new IllegalStateException("Incomplete native interior column");
        int min = context.interiorMinY(), height = output.getInt(12);
        if (height < 1 || height > profile().height()) throw new IllegalStateException("Interior column height mismatch");
        return PredictionColumnVolume.sample(min, height, y -> {
            int state = output.getInt(16 + (y - min) * 4);
            return states[state].isAir() ? -1 : blocks[state];
        }, block -> {
            var state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
            return state.getFluidState().isEmpty() ? 0 : state.is(Blocks.LAVA) ? 2 : 1;
        }).asSample();
    }
    @Override ClientColumnSample wallEvidence(int x, int z, ClientColumnSample sample) {
        if (cancelled || world == 0) return sample;
        ByteBuffer input = positions.get();
        ByteBuffer output = wallColumn.get();
        input.putInt(0, x).putInt(4, z);
        if (RustWorldgenBackend.columns(world, input, output, 1) != 1) return sample;
        return PredictionWallEvidence.inspect(sample, profile().minY(), y -> {
            if (y < profile().minY() || y >= profile().minY() + profile().height()) return false;
            var state = states[output.getInt(16 + (y - profile().minY()) * 4)];
            return !state.isAir() && state.getFluidState().isEmpty();
        });
    }
    @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return sample(x, z); }
    // Sparse points compute full-height density and surface neighbours. Publish
    // initial horizon coverage before paying for a complete 4,356-point grid.
    @Override int initialTerrainCellAxis(int lod) { return PredictionWorkOrder.initialCellAxis(lod); }
    @Override public int surfaceY(int x, int z) { return point(x,z)[0]; }
    @Override public int groundY(int x, int z) { return surfaceY(x,z); }
    @Override public int surfaceColor(int x, int y, int z) { return colors(x,y,z)[0]; }
    @Override public int foliageColor(int x, int y, int z) { return 0xff000000 | colors(x,y,z)[1]; }
    @Override public int waterTint(int x, int y, int z) { return 0xb2000000 | colors(x,y,z)[2]; }
    @Override public boolean exactWorldgen() { return true; }
    @Override public int seaLevel() { return context.seaLevel(); }
    @Override public int fluidColor() { return context.fluidColor(); }
    @Override ClientTerrainSampler decorationContext() { return context; }
    synchronized void cancelWork() {
        cancelled=true;
        if(world!=0) RustWorldgenBackend.cancel(world);
    }
    @Override public synchronized void close() {
        long id = world; world = 0; cancelled=true;
        if (id == 0) return;
        try {
            try { RustWorldgenBackend.cancel(id); } finally { RustWorldgenBackend.close(id); }
        } finally {
            synchronized(points) { points.clear(); } synchronized(tints) { tints.clear(); }
            if (context instanceof AutoCloseable owned) {
                try { owned.close(); }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Exception failure) { throw new IllegalStateException("Could not release native prediction context", failure); }
            }
        }
    }
}
