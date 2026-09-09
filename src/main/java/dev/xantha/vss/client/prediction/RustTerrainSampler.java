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
    static final String ALGORITHM = "vanilla-rust-abi2-r3";
    private static boolean attempted, loaded;
    private volatile long world;
    private volatile boolean cancelled;
    private final ClientTerrainSampler context;
    private final BlockState[] states;
    private final int[] blocks;
    private final String[][] featureOrder;
    private final Map<BlockState,Integer> stateIds = new IdentityHashMap<>();
    private final Map<String,Boolean> support = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long,int[]> tints = new LinkedHashMap<>(1024,.75f,true);
    private volatile long appearance;
    private volatile long colorFingerprint = Long.MIN_VALUE;
    private final java.util.concurrent.atomic.LongAdder nativeFeatures = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder javaFeatures = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder gridCacheHits = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder gridComputedPoints = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder fullChunkLoads = new java.util.concurrent.atomic.LongAdder();
    private final LinkedHashMap<Long, int[]> columns = new LinkedHashMap<>(128, .75f, true);
    private final LinkedHashMap<Long, int[]> points = new LinkedHashMap<>(1024, .75f, true);
    private final Object[] generationLocks = new Object[32];
    private final ThreadLocal<ByteBuffer> scratch = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(256 * 40).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<ByteBuffer> positions = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(64 * 8).order(ByteOrder.LITTLE_ENDIAN));

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
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS source-built Rust load failed: " + failure);
        }
        return loaded;
    }

    static ClientTerrainSampler open(DimensionProfile profile, JsonObject generator, JsonObject registries, ClientTerrainSampler context) {
        if (!available() || context == null) return null;
        long handle = 0;
        try {
            JsonObject document = RustWorldgenDocument.create(generator, registries, context);
            handle = RustWorldgenBackend.create(profile.seed(), BiomeManager.obfuscateSeed(profile.seed()), document.toString());
            RustTerrainSampler sampler = new RustTerrainSampler(handle, profile, context);
            sampler.colorFingerprint = colormapFingerprint(document);
            handle = 0;
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction backend=" + ALGORITHM + ", dimension=" + profile.dimension());
            return sampler;
        } catch (Exception | LinkageError failure) {
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS source-built Rust snapshot rejected: " + failure);
            return null;
        } finally {
            if (handle != 0) RustWorldgenBackend.close(handle);
        }
    }

    RustTerrainSampler(long world, DimensionProfile profile, ClientTerrainSampler context) {
        super(profile.seed(), profile);
        this.world = world; this.context = context;
        var table = JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject().getAsJsonArray("states");
        states = new BlockState[table.size()]; blocks = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            states[i] = BlockState.CODEC.parse(JsonOps.INSTANCE, table.get(i)).getOrThrow();
            blocks[i] = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(states[i].getBlock());
            stateIds.put(states[i],i);
        }
        featureOrder = new Gson().fromJson(RustWorldgenBackend.schedule(world), String[][].class);
        Arrays.setAll(generationLocks, i -> new Object());
    }
    long handle() { long id = world; if (id == 0 || cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Native sampler closed/cancelled"); return id; }
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
        int[] c=point(x,z);
        if(y>=c[0]) {
            if(c[2]==0||y>=c[1]) return Blocks.AIR.defaultBlockState();
            if((c[3]&2)!=0&&y==c[1]-1) return Blocks.ICE.defaultBlockState();
            return (c[2]==2?Blocks.LAVA:Blocks.WATER).defaultBlockState();
        }
        if((c[3]&(1<<29))!=0) return Blocks.AIR.defaultBlockState();
        return states[c[y==c[0]-1?4:c[0]-1-y<4?5:6]];
    }
    private int[] colors(int x,int y,int z) {
        long generation=appearance;
        long key=BlockPos.asLong(x,y,z);
        synchronized(tints) { int[] value=tints.get(key);if(value!=null)return value; }
        ByteBuffer out=scratch.get();
        if(RustWorldgenBackend.tints(handle(),x,y,z,out)!=3) throw new IllegalStateException("Incomplete native tint");
        int[] value={out.getInt(0),out.getInt(4),out.getInt(8)};
        synchronized(tints) {
            if(generation==appearance)tints.put(key,value);
            while(tints.size()>65536)tints.remove(tints.keySet().iterator().next());
        }
        return value;
    }
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
        synchronized(tints) {appearance++;colorFingerprint=colormapFingerprint(maps);tints.clear();}
        synchronized(columns) {columns.clear();} synchronized(points) {points.clear();}
    }
    void nativeFeatureCompleted() {nativeFeatures.increment();}
    void javaFeatureCompleted() {javaFeatures.increment();}
    String diagnostics() {return "backend="+ALGORITHM+",nativeFeatures="+nativeFeatures.sum()+",compatibilityFeatures="+javaFeatures.sum()
            +",gridCacheHits="+gridCacheHits.sum()+",gridSubmittedPoints="+gridComputedPoints.sum()+",fullChunkLoads="+fullChunkLoads.sum();}

    private int[] chunk(int x, int z) {
        long key = (long) x << 32 | z & 0xffffffffL;
        synchronized (columns) { int[] found = columns.get(key); if (found != null) return found; }
        synchronized (generationLocks[PredictionChunkLocks.stripe(key,generationLocks.length)]) {
            synchronized (columns) { int[] found = columns.get(key); if (found != null) return found; }
            ByteBuffer out = scratch.get();
            if (RustWorldgenBackend.surfaceColumns(handle(), x, z, out) != 256) throw new IllegalStateException("Incomplete native surface chunk");
            fullChunkLoads.increment();
            int[] data = new int[256 * 10];
            out.asIntBuffer().get(data);
            synchronized (columns) {
                columns.put(key, data);
                while (columns.size() > 1024) columns.remove(columns.keySet().iterator().next());
            }
            return data;
        }
    }
    private static int offset(int x, int z) { return ((x & 15) * 16 + (z & 15)) * 10; }
    private int[] point(int x, int z) {
        long key = (long) x << 32 | z & 0xffffffffL;
        synchronized (points) { int[] found = points.get(key); if (found != null) return found; }
        synchronized (columns) {
            int[] full = columns.get((long) (x >> 4) << 32 | (z >> 4) & 0xffffffffL);
            if (full != null) { int i = offset(x,z); return Arrays.copyOfRange(full,i,i+10); }
        }
        ByteBuffer input = positions.get(), output = scratch.get();
        input.putInt(0,x).putInt(4,z);
        if (RustWorldgenBackend.surfacePoints(handle(),input,output,1) != 1) throw new IllegalStateException("Incomplete native point");
        int[] data = new int[10]; for (int i=0;i<10;i++) data[i]=output.getInt(i*4);
        rememberPoint(key,data); return data;
    }
    private void rememberPoint(long key,int[] data) {
        synchronized(points) { points.put(key,data); while(points.size()>65536) points.remove(points.keySet().iterator().next()); }
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int axis) {
        return sampleGrid(x, z, spacing, axis, axis);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height) {
        return sampleGrid(x,z,spacing,width,height,new ClientColumnSample[width*height]);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained) {
        if(width<1||width>8||height<1||height>8||spacing<1) throw new IllegalArgumentException("Native grid dimensions");
        if(retained.length!=width*height) throw new IllegalArgumentException("Retained grid dimensions");
        handle(); // Cached requests must still honor world cancellation.
        ClientColumnSample[] result=retained.clone();
        ByteBuffer input=positions.get(),output=scratch.get();input.clear();
        int[] missing=new int[result.length];
        int count=0;
        Map<Long,Integer> nearMisses=spacing<4 ? new HashMap<>() : null;
        for(int i=0;i<result.length;i++) {
            if(result[i]!=null) continue;
            int xx=x+i%width*spacing,zz=z+i/width*spacing;
            long key=(long)xx<<32|zz&0xffffffffL;
            int[] cached;
            synchronized(points) { cached=points.get(key); }
            if(cached!=null) { result[i]=sampleRecord(cached,0); continue; }
            synchronized(columns) {
                int[] full=columns.get((long)(xx>>4)<<32|(zz>>4)&0xffffffffL);
                if(full!=null) { result[i]=sampleRecord(full,offset(xx,zz)); continue; }
            }
            missing[count++]=i;
            if(nearMisses!=null) nearMisses.merge((long)(xx>>4)<<32|(zz>>4)&0xffffffffL,1,Integer::sum);
        }
        // Refinement grids share aligned columns with their parents. Only
        // submit cache misses; previously every stage recomputed those columns.
        gridCacheHits.add(result.length-count);
        if(count==0) return result;
        // Full chunks amortize dense near-field work. A thin border may use
        // only one or two columns of a neighbouring chunk; do not generate
        // all 256 columns there. Keep mixed outputs in the caller's order.
        int sparse=0;
        for(int n=0;n<count;n++) {
            int i=missing[n],xx=x+i%width*spacing,zz=z+i/width*spacing;
            long key=(long)(xx>>4)<<32|(zz>>4)&0xffffffffL;
            if(nearMisses!=null && nearMisses.get(key)>=16) {
                int[] full=chunk(xx>>4,zz>>4);
                result[i]=sampleRecord(full,offset(xx,zz));
            } else {
                missing[sparse++]=i;
                input.putInt(xx).putInt(zz);
            }
        }
        count=sparse;
        if(count==0) return result;
        if(RustWorldgenBackend.surfacePoints(handle(),input,output,count)!=count) throw new IllegalStateException("Incomplete native grid");
        gridComputedPoints.add(count);
        for(int n=0;n<count;n++) {
            int i=missing[n];
            int[] data=new int[10];for(int j=0;j<10;j++) data[j]=output.getInt((n*10+j)*4);
            int xx=x+i%width*spacing,zz=z+i/width*spacing;rememberPoint((long)xx<<32|zz&0xffffffffL,data);
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
            synchronized(columns) { columns.clear(); } synchronized(points) { points.clear(); } synchronized(tints) { tints.clear(); }
            if (context instanceof AutoCloseable owned) {
                try { owned.close(); }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Exception failure) { throw new IllegalStateException("Could not release native prediction context", failure); }
            }
        }
    }
}
