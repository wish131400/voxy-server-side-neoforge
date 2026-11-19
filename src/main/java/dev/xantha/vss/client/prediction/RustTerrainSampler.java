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
    @Override boolean supportsFarDecorations() { return !context.interiorTerrain(); }
    static final String ALGORITHM = "vanilla-rust-abi6-chunk-surface-r5";
    private static boolean attempted, loaded;
    private volatile long world;
    private volatile boolean cancelled;
    private final ClientTerrainSampler context;
    private boolean javaSurface;
    private boolean javaBiomes;
    private PredictionSurfaceStage surfaceStage;
    private final java.util.concurrent.atomic.LongAdder javaSurfaceColumns = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder javaSurfaceNanos = new java.util.concurrent.atomic.LongAdder();
    private volatile boolean javaSurfaceFailed;
    private final ThreadLocal<ByteBuffer> terrainHeightInput = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<ByteBuffer> terrainHeightOutput = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(40).order(ByteOrder.LITTLE_ENDIAN));
    // JNI rows use the noise generator's bounds, which may differ from the dimension.
    private final int nativeMinY, nativeHeight;
    private final boolean terrablenderRouting;
    private final boolean signedSqrt;
    private final BlockState[] states;
    private final int[] blocks;
    private final int[] nativeBiomeMapping;
    private volatile boolean legacyBiomeIndices;
    private final java.util.concurrent.atomic.LongAdder biomeNativeBatches = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder biomeNativePoints = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder biomeJavaFallbacks = new java.util.concurrent.atomic.LongAdder();
    private final ThreadLocal<ByteBuffer> biomePositions = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(256 * 12).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<ByteBuffer> biomeResults = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.LITTLE_ENDIAN));
    private final String[][] featureOrder;
    private final Map<BlockState,Integer> stateIds = new IdentityHashMap<>();
    private final Map<String,Boolean> support = new ConcurrentHashMap<>();
    private final RustVegetationDescriptors.Cache featureDescriptors = new RustVegetationDescriptors.Cache();
    private final PredictionTintCache tints = new PredictionTintCache(65536);
    private final java.util.concurrent.atomic.LongAdder tintNativeCalls = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder tintSeeded = new java.util.concurrent.atomic.LongAdder();
    private volatile long colorFingerprint = Long.MIN_VALUE;
    private final java.util.concurrent.atomic.LongAdder nativeFeatures = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder javaFeatures = new java.util.concurrent.atomic.LongAdder();
    enum JavaFeatureReason { TREE_MODEL, ORDER_MISMATCH, UNSUPPORTED, TRANSACTION, PROXY, UNREGISTERED }
    private final java.util.concurrent.atomic.LongAdder[] javaFeatureReasons =
            java.util.Arrays.stream(JavaFeatureReason.values()).map(ignored -> new java.util.concurrent.atomic.LongAdder())
                    .toArray(java.util.concurrent.atomic.LongAdder[]::new);
    private final java.util.concurrent.atomic.LongAdder javaWithoutWrites = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder javaFailed = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder javaFeatureNanos = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder gridCacheHits = new java.util.concurrent.atomic.LongAdder();
    final java.util.concurrent.atomic.LongAdder gridComputedPoints = new java.util.concurrent.atomic.LongAdder();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<int[]> points =
            new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>(1024);
    private final ThreadLocal<ByteBuffer> scratch = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(256 * 40).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<ByteBuffer> positions = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(64 * 8).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<int[]> missingScratch = ThreadLocal.withInitial(() -> new int[64]);
    // Native ABI stays at ten ints; the Java cache appends the mapped biome identity.
    private final ThreadLocal<int[]> decodedScratch = ThreadLocal.withInitial(() -> new int[11]);
    private final ThreadLocal<int[]> surfaceBiomePositions = ThreadLocal.withInitial(() -> new int[64*3]);
    private final ThreadLocal<int[]> surfaceBiomes = ThreadLocal.withInitial(() -> new int[64]);
    private final ThreadLocal<ByteBuffer> wallColumn;

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
            sampler.configureRuntimeStages(generator,registries);
            if ((generator.has("vss_terrablender") || generator.has("vss_terrablender_end")
                    || generator.has("vss_blueprint")
                    && generator.getAsJsonObject("vss_blueprint").has("original_terrablender"))
                    && !sampler.terrablenderRouting())
                throw new IllegalStateException("Native library lacks required TerraBlender routing");
            timing.mark("stateMapping");
            sampler.colorFingerprint = colormapFingerprint(document);
            context.bindHorizontalClimate(handle, document);
            handle = 0;
            timing.finish();
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction backend=" + ALGORITHM + ", dimension=" + profile.dimension());
            return sampler;
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception | LinkageError failure) {
            VSSLogger.warn("VSS native terrain snapshot rejected for " + profile.dimension()
                    + "; prediction keeps the Java sampler: " + failure);
            return null;
        } finally {
            if (handle != 0) RustWorldgenBackend.close(handle);
        }
    }

    void configureRuntimeStages(JsonObject generator,JsonObject registries) {
            javaSurface = PredictionExecutionPlan.resolve(generator, registries).javaSurface();
            javaBiomes=generator.has("vss_java_biomes");
            if(generator.has("vss_surface_stage"))surfaceStage=new PredictionSurfaceStage(context,this::fillNativeChunk);
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
        nativeMinY = described.get("min_y").getAsInt();
        nativeHeight = described.get("height").getAsInt();
        wallColumn = ThreadLocal.withInitial(() ->
                ByteBuffer.allocateDirect(16 + nativeHeight * 4).order(ByteOrder.LITTLE_ENDIAN));
        footprintResults = ThreadLocal.withInitial(() ->
                ByteBuffer.allocateDirect(8 * (4 + nativeHeight)).order(ByteOrder.LITTLE_ENDIAN));
        var biomeNames = described.getAsJsonArray("biome_names");
        nativeBiomeMapping = biomeNames == null ? null : new int[biomeNames.size()];
        if (biomeNames != null) {
            var palette = cacheBiomeIndices();
            for (int i=0;i<nativeBiomeMapping.length;i++)
                nativeBiomeMapping[i] = palette.getOrDefault(biomeNames.get(i).getAsString(), -1);
        }
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
    RustVegetationDescriptors featureDescriptors(List<net.minecraft.world.level.levelgen.placement.PlacedFeature> features, int step) {
        handle();
        var registry = context.decorationAccess().registryOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE);
        return featureDescriptors.get(features, step, registry, featureOrder, this::supports);
    }
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
    int[] surfaceRecord(int x, int z) {
        handle();
        long key = (long)x << 32 | z & 0xffffffffL;
        synchronized(points) { var row=points.getAndMoveToLast(key); if(row!=null)return row; }
        return exactSurfacePage(x,z,1)[0];
    }
    /** Stable exact view, shared by neighboring placement attempts. No display metadata is prepared. */
    int[][] exactSurfacePage(int x,int z,int side) {
        handle();
        if(side!=1 && side!=4)throw new IllegalArgumentException("Decoration page dimensions");
        int count=side*side, pending=0;
        int[][] rows=new int[count][];
        int[] missing=missingScratch.get();
        ByteBuffer input=positions.get(),output=scratch.get();input.clear();
        synchronized(points) {
            for(int i=0;i<count;i++) {
                int xx=x+i/side,zz=z+i%side;
                rows[i]=points.getAndMoveToLast((long)xx<<32|zz&0xffffffffL);
                if(rows[i]==null) { missing[pending++]=i; input.putInt(xx).putInt(zz); }
            }
        }
        if(pending==0)return rows;
        if(RustWorldgenBackend.surfacePoints(handle(),input,output,pending)!=pending)
            throw new IllegalStateException("Incomplete exact decoration page");
        for(int n=0;n<pending;n++) {
            int i=missing[n];int[] row=rows[i]=new int[10];
            for(int j=0;j<10;j++)row[j]=output.getInt((n*10+j)*4);
            applyJavaSurface(row, x+i/side, z+i%side);
            rememberPoint((long)(x+i/side)<<32|(z+i%side)&0xffffffffL,row);
        }
        return rows;
    }
    /** One aligned 4x4 page in x-major order, shared with native display queries.
     * Never insert these records in the exact Java point cache. */
    int[][] decorationDisplayPage(int x, int z) {
        long id = handle();
        ByteBuffer input = positions.get(), output = scratch.get(); input.clear();
        for (int i=0;i<16;i++) input.putInt(x+i/4).putInt(z+i%4);
        if (RustWorldgenBackend.decorationPoints(id,input,output,16)!=16)
            throw new IllegalStateException("Incomplete display decoration page");
        int[][] rows = new int[16][10];
        for (int i=0;i<16;i++) {
            for (int j=0;j<10;j++) rows[i][j]=output.getInt((i*10+j)*4);
            applyJavaSurface(rows[i], x+i/4, z+i%4,false);
        }
        return rows;
    }
    boolean nativeDecorationCompatible() { return !javaSurface && !javaBiomes; }

    private void applyJavaSurface(int[] row, int x, int z) { applyJavaSurface(row,x,z,true); }
    private void applyJavaSurface(int[] row, int x, int z, boolean complete) {
        if (surfaceStage != null && !complete) {
            // Full runtime rules build a NoiseChunk per sampled chunk. Sparse previews
            // paint the basic biome material; the staged pass runs the original hooks.
            row[3] |= ClientColumnSample.FLAG_SURFACE_PENDING;
            if ((row[3] & ClientColumnSample.FLAG_NO_SURFACE) == 0 && row[2] == 0) {
                int block = context.previewGroundBlock(x, row[0], z);
                Integer state = stateIds.get(net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(block).defaultBlockState());
                if (state != null) row[4] = state;
            }
            return;
        }
        if (!javaSurface || javaSurfaceFailed || (surfaceStage==null && (row[3] & ClientColumnSample.FLAG_NO_SURFACE) != 0)) return;
        long started = System.nanoTime();
        try {
            if(surfaceStage!=null && complete) {
                var resolved=surfaceStage.sample(x,z);
                row[0]=resolved.surfaceY();row[1]=resolved.fluidY();row[2]=resolved.fluid();row[3]=resolved.flags();
                row[4]=surfaceState(resolved.topBlockIndex());row[5]=surfaceState(resolved.underBlockIndex());row[6]=surfaceState(resolved.deepBlockIndex());
                if(row.length>10)row[10]=resolved.biomeIndex();
                javaSurfaceColumns.increment();return;
            }
            var sample = sampleRecord(row, 0);
            // Sparse previews must not trigger full native columns via Java surface height probes.
            var resolved = complete ? context.resolveSurface(sample, x, z, this::terrainHeight)
                    : context.resolvePreviewSurface(sample, x, z, this::previewTerrainHeight);
            int top = surfaceState(resolved.topBlockIndex()), under = surfaceState(resolved.underBlockIndex()),
                    deep = surfaceState(resolved.deepBlockIndex());
            row[4] = top; row[5] = under; row[6] = deep; row[3] = resolved.flags();
            javaSurfaceColumns.increment();
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException failure) {
            synchronized (this) {
                if (!javaSurfaceFailed) VSSLogger.warn("VSS Java surface stage failed for " + profile().dimension()
                        + "; keeping completed Rust terrain; authoritative LOD remains preferred", failure);
                javaSurfaceFailed = true;
            }
        } finally { javaSurfaceNanos.add(System.nanoTime() - started); }
    }
    private int surfaceState(int block) {
        var state = block<0 ? Blocks.AIR.defaultBlockState() : net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
        Integer index = stateIds.get(state);
        if (index == null) throw new IllegalArgumentException("Java surface state absent from native palette: " + state);
        return index;
    }
    // Height neighbours for Java rules must never recurse into the surface stage.
    private int terrainHeight(int x, int z) {
        if(surfaceStage==null) synchronized (points) {
            int[] cached = points.get((long)x<<32|z&0xffffffffL);
            if (cached != null) return cached[0];
        }
        ByteBuffer input = terrainHeightInput.get(), output = terrainHeightOutput.get();
        input.putInt(0,x).putInt(4,z);
        if (RustWorldgenBackend.surfacePoints(handle(),input,output,1)!=1) throw new IllegalStateException("Incomplete terrain height");
        return output.getInt(0);
    }
    private int previewTerrainHeight(int x, int z) {
        ByteBuffer input = terrainHeightInput.get(), output = terrainHeightOutput.get();
        input.putInt(0, x).putInt(4, z);
        if (RustWorldgenBackend.previewPoints(handle(), input, output, 1) != 1)
            throw new IllegalStateException("Incomplete preview terrain height");
        return output.getInt(0);
    }

    @Override SamplingScope terrainRequest() { return context.terrainRequest(); }
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
    /** Same proxy states as proxyBlock, searched once per constant material band. */
    int proxyHeight(int[] c, net.minecraft.world.level.levelgen.Heightmap.Types type) {
        int floor = c[0], fluid = c[1], min = profile().minY();
        int y = Math.min(Math.max(floor, fluid) - 1, min + profile().height() - 1);
        var opaque = type.isOpaque();
        while (y >= min) {
            if (opaque.test(proxyBlock(c, y))) return y + 1;
            if (y >= floor) {
                if (c[2] == 0 || y >= fluid) y = floor - 1;
                else if ((c[3] & 2) != 0 && y == fluid - 1) y--;
                else y = floor - 1;
            } else if ((c[3] & (1 << 29)) != 0) break;
            else if (y == floor - 1) y--;
            else if ((long) floor - 1 - y < 4) y = floor - 5;
            else break;
        }
        return min;
    }
    private int[] colors(int x,int y,int z) {
        if(javaBiomes)return new int[]{context.surfaceColor(x,y,z),context.foliageColor(x,y,z),context.waterTint(x,y,z)};
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
        if (javaBiomes || (data[3] & ClientColumnSample.FLAG_APPROXIMATE) != 0) return;
        int y = data[2] != 0 ? data[1] - 1 : data[0];
        if (tints.put(BlockPos.asLong(x,y,z), new int[]{data[7],data[8],data[9]}, generation)) tintSeeded.increment();
    }
    long tintGeneration() { return tints.generation(); }
    long tintNativeCalls() { return tintNativeCalls.sum(); }
    private static void surfaceBiomePosition(int[] queries,int at,int x,int z,int floor,int water,int fluid) {
        queries[at*3]=x>>2;
        queries[at*3+1]=((fluid!=0?water:floor)-1)>>2;
        queries[at*3+2]=z>>2;
    }
    private void surfaceBiomeIndices(int[] queries,int count,int[] indices) {
        if (javaBiomes) { context.sampleBiomeIndices(queries,count,indices);
        } else if (nativeBiomeMapping==null || legacyBiomeIndices) {
            Arrays.fill(indices,0,count,-1); // Preserve deferred Java resolution for older libraries.
        } else sampleBiomeIndices(queries,count,indices);
    }
    @Override void sampleBiomeIndices(int[] quartPositions, int count, int[] indices) {
        handle();
        if (javaBiomes || nativeBiomeMapping == null || legacyBiomeIndices) {
            biomeJavaFallbacks.add(count);
            context.sampleBiomeIndices(quartPositions, count, indices);
            return;
        }
        ByteBuffer input=biomePositions.get(), output=biomeResults.get();
        for (int start=0;start<count;start+=256) {
            int batch=Math.min(256,count-start);
            input.clear();
            for (int i=0;i<batch*3;i++) input.putInt(quartPositions[start*3+i]);
            try {
                if (RustWorldgenBackend.biomeIndices(handle(),input,output,batch)!=batch)
                    throw new IllegalStateException("Incomplete native biome batch");
            } catch (UnsatisfiedLinkError olderLibrary) {
                legacyBiomeIndices=true;
                biomeJavaFallbacks.add(count);
                context.sampleBiomeIndices(quartPositions,count,indices);
                return;
            }
            biomeNativeBatches.increment(); biomeNativePoints.add(batch);
            for (int i=0;i<batch;i++) {
                int nativeIndex=output.getInt(i*4);
                if (nativeIndex<0 || nativeIndex>=nativeBiomeMapping.length)
                    throw new IllegalStateException("Invalid native biome index");
                indices[start+i]=nativeBiomeMapping[nativeIndex];
            }
        }
        // A valid native biome may be absent from an external/legacy persisted palette.
        // Resolve only those entries through the compatibility context.
        for (int i=0;i<count;i++) if (indices[i]<0) {
            int[] point={quartPositions[i*3],quartPositions[i*3+1],quartPositions[i*3+2]};
            int[] fallback=new int[1];
            biomeJavaFallbacks.increment();
            context.sampleBiomeIndices(point,1,fallback); indices[i]=fallback[0];
        }
    }
    @Override long colorCacheFingerprint() { return colorFingerprint; }
    private volatile boolean legacyPossibleBiomes;
    private final ThreadLocal<ByteBuffer> possibleBiomeResults = new ThreadLocal<>();
    java.util.BitSet possibleBiomes(int minX, int minZ, int maxX, int maxZ) {
        long id = handle();
        if (javaBiomes || legacyPossibleBiomes || nativeBiomeMapping == null || legacyBiomeIndices) return null;
        long nx = (long) maxX - minX + 1, nz = (long) maxZ - minZ + 1;
        if (nx <= 0 || nz <= 0 || nx > 32 || nz > 32
                || minX < -7_500_000 || maxX > 7_500_000 || minZ < -7_500_000 || maxZ > 7_500_000)
            return null;
        ByteBuffer output = possibleBiomeResults.get();
        if (output == null) {
            output = ByteBuffer.allocateDirect(Math.max(1, nativeBiomeMapping.length) * Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            possibleBiomeResults.set(output);
        }
        int count;
        try { count = RustWorldgenBackend.possibleBiomes(id,minX,minZ,maxX,maxZ,output); }
        catch (UnsatisfiedLinkError olderLibrary) { legacyPossibleBiomes = true; return null; }
        if (count < 0) return null;
        if (count > nativeBiomeMapping.length) throw new IllegalStateException("Invalid biome certificate count");
        var found = new java.util.BitSet();
        for (int i=0;i<count;i++) {
            int nativeIndex = output.getInt(i*4);
            if (nativeIndex < 0 || nativeIndex >= nativeBiomeMapping.length)
                throw new IllegalStateException("Invalid biome certificate index");
            int mapped = nativeBiomeMapping[nativeIndex];
            if (mapped < 0) return null;
            found.set(mapped);
        }
        return found;
    }
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
    void javaFeatureCompleted(JavaFeatureReason reason, int writes, boolean success, long nanos) {
        javaFeatures.increment();
        javaFeatureReasons[reason.ordinal()].increment();
        if (writes == 0) javaWithoutWrites.increment();
        if (!success) javaFailed.increment();
        javaFeatureNanos.add(nanos);
    }

    private String javaFeatureDiagnostics() {
        var out = new StringBuilder("javaPlacement={ms=").append(javaFeatureNanos.sum()/1_000_000)
                .append(",withoutWrites=").append(javaWithoutWrites.sum()).append(",failed=").append(javaFailed.sum());
        for (var reason : JavaFeatureReason.values())
            out.append(',').append(reason.name()).append('=').append(javaFeatureReasons[reason.ordinal()].sum());
        return out.append('}').toString();
    }
    boolean signedSqrt() { return signedSqrt; }

    String diagnostics() {return "backend="+ALGORITHM+",nativeFeatures="+nativeFeatures.sum()+",compatibilityFeatures="+javaFeatures.sum()
            +","+javaFeatureDiagnostics()
            +",surfaceStage={java="+javaSurface+",runtime="+(surfaceStage!=null)+",runtimeChunks="+runtimeSurfaceChunks()+",runtimeFailures="+(surfaceStage==null?0:surfaceStage.failures())+",failed="+javaSurfaceFailed+",columns="+javaSurfaceColumns.sum()+",ms="+javaSurfaceNanos.sum()/1_000_000+"}"
            +",tintCache={"+tints.diagnostics()+",nativeCalls="+tintNativeCalls.sum()+",seeded="+tintSeeded.sum()+"}"
            +",gridCacheHits="+gridCacheHits.sum()+",gridSubmittedPoints="+gridComputedPoints.sum()
            +",biomeIdentity={nativeBatches="+biomeNativeBatches.sum()+",nativePoints="+biomeNativePoints.sum()
            +",javaFallbacks="+biomeJavaFallbacks.sum()+"}"
            +","+RustVegetationStage.diagnostics()+","+featureDescriptors.diagnostics()+",groundQueries="+groundDiagnostics();}
    private String groundDiagnostics() {
        long id=world;
        if(id==0 || cancelled) return "closed";
        try { return RustWorldgenBackend.decorationQueryStats(id); }
        catch (IllegalArgumentException closed) { return "closed"; }
    }

    private int[] point(int x, int z) {
        int[] raw=surfaceRecord(x,z);
        if(raw.length==11)return raw;
        long generation=tints.generation();
        int[] data=Arrays.copyOf(raw,11);
        int[] queries=surfaceBiomePositions.get(),indices=surfaceBiomes.get();
        surfaceBiomePosition(queries,0,x,z,data[0],data[1],data[2]);
        surfaceBiomeIndices(queries,1,indices);data[10]=indices[0];
        rememberColors(x,z,data,generation);
        rememberPoint((long)x<<32|z&0xffffffffL,data);
        return data;
    }
    private void rememberPoint(long key,int[] data) {
        synchronized(points) {
            // An in-flight raw page must not downgrade a concurrently completed record.
            int[] old=points.get(key);
            if(old==null || old.length<=data.length)points.putAndMoveToLast(key,data);
            while(points.size()>65536)points.removeFirst();
        }
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int axis) {
        return sampleGrid(x, z, spacing, axis, axis, new ClientColumnSample[axis * axis], false, false, false);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height) {
        return sampleGrid(x,z,spacing,width,height,new ClientColumnSample[width * height], false, false, false);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained) {
        return sampleGrid(x,z,spacing,width,height,retained,false,false,true);
    }
    ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained,boolean preview) {
        return sampleGrid(x,z,spacing,width,height,retained,preview,false,true);
    }
    ClientColumnSample[] sampleDisplayGrid(int x,int z,int spacing,int width,int height,ClientColumnSample[] retained) {
        return sampleGrid(x,z,spacing,width,height,retained,false,true,true);
    }
    /** Fills one window of the tile builder's task-owned grid without copying its references. */
    ClientColumnSample[] sampleGridInPlace(int x,int z,int spacing,int width,int height,
                                           ClientColumnSample[] retained,int offset,int rowStride,boolean preview) {
        return sampleGrid(x,z,spacing,width,height,retained,offset,rowStride,preview,false);
    }
    ClientColumnSample[] sampleDisplayGridInPlace(int x,int z,int spacing,int width,int height,
                                                  ClientColumnSample[] retained,int offset,int rowStride) {
        return sampleGrid(x,z,spacing,width,height,retained,offset,rowStride,false,true);
    }
    private ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,
                                            ClientColumnSample[] retained,boolean preview,boolean display,
                                            boolean copyRetained) {
        if(width<1||width>8||height<1||height>8||spacing<1) throw new IllegalArgumentException("Native grid dimensions");
        if(retained.length!=width*height) throw new IllegalArgumentException("Retained grid dimensions");
        return sampleGrid(x,z,spacing,width,height,copyRetained ? retained.clone() : retained,0,width,preview,display);
    }
    private ClientColumnSample[] sampleGrid(int x,int z,int spacing,int width,int height,
                                            ClientColumnSample[] result,int offset,int rowStride,
                                            boolean preview,boolean display) {
        if(width<1||width>8||height<1||height>8||spacing<1) throw new IllegalArgumentException("Native grid dimensions");
        if(offset<0||rowStride<width||(long)offset+(long)(height-1)*rowStride+width>result.length)
            throw new IllegalArgumentException("Retained grid window");
        handle(); // Cached requests must still honor world cancellation.
        ByteBuffer input=positions.get(),output=scratch.get();input.clear();
        int[] missing=missingScratch.get();
        int count=0;
        for(int i=0;i<width*height;i++) {
            int target=offset+i/width*rowStride+i%width;
            if(result[target]!=null && !(display ? result[target].reusableForDisplay() : result[target].reusableFor(preview))) result[target]=null;
            if(result[target]!=null) continue;
            int xx=x+i%width*spacing,zz=z+i/width*spacing;
            long key=(long)xx<<32|zz&0xffffffffL;
            int[] cached;
            // Display observations never depend on which exact columns decoration prefetched.
            synchronized(points) { cached=display ? null : points.getAndMoveToLast(key); }
            if (cached != null && (!preview || cached.length != 10)) {
                result[target] = sampleRecord(cached.length == 10 ? point(xx, zz) : cached, 0);
                continue;
            }
            missing[count++]=i;
        }
        // Refinement grids share aligned columns with their parents. Only
        // submit cache misses; previously every stage recomputed those columns.
        gridCacheHits.add(width*height-count);
        if(count==0) return result;
        // Surface refinement computes only requested columns. The native
        // batch shares noise/aquifer context without generating a full chunk.
        for(int n=0;n<count;n++) {
            int i=missing[n],xx=x+i%width*spacing,zz=z+i/width*spacing;
            input.putInt(xx).putInt(zz);
        }
        long tintGeneration = tints.generation();
        boolean gridResult = false;
        int written;
        if (display && width == 8 && height == 8 && spacing <= 4 && count < 64
                && count > (spacing >= 4 ? 36 : 32) && !legacyDisplayGrid) {
            long mask = 0;
            for (int n = 0; n < count; n++) mask |= 1L << missing[n];
            try {
                written = RustWorldgenBackend.displayGrid(handle(), x, z, spacing, mask, output);
                if (written != 64) throw new IllegalStateException("Incomplete native display grid");
                gridResult = true;
            } catch (UnsatisfiedLinkError olderLibrary) {
                legacyDisplayGrid = true;
            }
        }
        written=gridResult ? count : display ? RustWorldgenBackend.displayPoints(handle(),input,output,count)
                : preview ? RustWorldgenBackend.previewPoints(handle(),input,output,count)
                : RustWorldgenBackend.surfacePoints(handle(),input,output,count);
        if(written!=count) throw new IllegalStateException("Incomplete native grid");
        int[] queries=surfaceBiomePositions.get(), indices=surfaceBiomes.get();
        for (int n=0;n<count;n++) {
            int i=missing[n],record=(gridResult?i:n)*40;
            surfaceBiomePosition(queries,n,x+i%width*spacing,z+i/width*spacing,
                    output.getInt(record),output.getInt(record+4),output.getInt(record+8));
        }
        surfaceBiomeIndices(queries,count,indices);
        int[] decoded=decodedScratch.get();
        gridComputedPoints.add(count);
        for(int n=0;n<count;n++) {
            int i=missing[n];
            for(int j=0;j<10;j++) decoded[j]=output.getInt(((gridResult ? i : n)*10+j)*4);
            decoded[10]=indices[n];
            int xx=x+i%width*spacing,zz=z+i/width*spacing;
            applyJavaSurface(decoded,xx,zz,!preview && !display);
            rememberColors(xx,zz,decoded,tintGeneration);
            boolean cache = !preview && !display && (decoded[3] & ClientColumnSample.FLAG_APPROXIMATE)==0;
            int[] data = cache ? java.util.Arrays.copyOf(decoded, decoded.length) : decoded;
            if(cache) rememberPoint((long)xx<<32|zz&0xffffffffL,data);
            result[offset+i/width*rowStride+i%width]=sampleRecord(data,0);
        }
        return result;
    }
    /** Arbitrary stable display anchors, batched without regular-grid interpolation. */
    void sampleDisplayPoints(int[] xz, int count, ClientColumnSample[] result) {
        if(count<1 || count>64 || xz.length<count*2 || result.length<count)
            throw new IllegalArgumentException("Native display points");
        ByteBuffer input=positions.get(),output=scratch.get();input.clear();
        for(int i=0;i<count*2;i++)input.putInt(xz[i]);
        long tintGeneration=tints.generation();
        if(RustWorldgenBackend.displayPoints(handle(),input,output,count)!=count)
            throw new IllegalStateException("Incomplete native display points");
        int[] queries=surfaceBiomePositions.get(),indices=surfaceBiomes.get();
        for(int i=0;i<count;i++)surfaceBiomePosition(queries,i,xz[i*2],xz[i*2+1],
                output.getInt(i*40),output.getInt(i*40+4),output.getInt(i*40+8));
        surfaceBiomeIndices(queries,count,indices);
        int[] decoded=decodedScratch.get();
        gridComputedPoints.add(count);
        for(int i=0;i<count;i++) {
            for(int j=0;j<10;j++)decoded[j]=output.getInt((i*10+j)*4);
            decoded[10]=indices[i];
            applyJavaSurface(decoded,xz[i*2],xz[i*2+1],false);
            rememberColors(xz[i*2],xz[i*2+1],decoded,tintGeneration);
            result[i]=sampleRecord(decoded,0);
        }
    }
    private volatile boolean legacyDisplayGrid;
    @Override public ClientColumnSample sample(int x, int z) {
        return sampleRecord(point(x,z),0);
    }
    private ClientColumnSample sampleRecord(int[] data,int i) {
        return new ClientColumnSample(data[i], data[i+1], data.length>i+10?data[i+10]:-1, blocks[data[i+4]], 0, 0, 0, 0,
                data[i+2], data[i+3], 0, blocks[data[i+5]], blocks[data[i+6]],
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    @Override public ClientColumnSample sampleSurface(int x, int z) { return sample(x, z); }
    @Override public ClientColumnSample samplePreview(int x, int z) { return samplePreview(x, z, 1); }
    @Override public ClientColumnSample samplePreview(int x, int z, int step) {
        return sampleGrid(x, z, step, 1, 1, new ClientColumnSample[1], true, false, false)[0];
    }
    @Override PredictionColumnVolume exteriorColumn(int x, int z) {
        if(surfaceStage!=null)return surfaceStage.column(x,z);
        // Occupancy needs no biome/surface-rule pass or four neighboring surface queries.
        ByteBuffer input = positions.get(), output = wallColumn.get();
        input.putInt(0, x).putInt(4, z);
        if (RustWorldgenBackend.columns(handle(), input, output, 1) != 1)
            throw new IllegalStateException("Incomplete native exterior column");
        int min = nativeMinY, height = output.getInt(12);
        if (height != nativeHeight) throw new IllegalStateException("Exterior column height mismatch");
        int rock = PredictionMaterialPalette.stoneIndex();
        return PredictionColumnVolume.sample(min, height,
                y -> states[output.getInt(16 + (y - min) * 4)].isAir() ? -1 : rock, ignored -> 0);
    }
    private volatile boolean legacyExteriorBackend;
    private volatile boolean legacyExteriorBatch;
    private final ThreadLocal<ByteBuffer> footprintRequests = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(8 * 20).order(ByteOrder.LITTLE_ENDIAN));
    private final ThreadLocal<ByteBuffer> footprintResults;
    @Override boolean[][] exteriorFootprints(int[] x, int[] z, int step, int[] bottom, int[] top, int count,
                                               java.util.function.BooleanSupplier valid) {
        if (surfaceStage!=null || count < 2 || legacyExteriorBatch || legacyExteriorBackend)
            return super.exteriorFootprints(x, z, step, bottom, top, count, valid);
        if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        ByteBuffer input = footprintRequests.get(), output = footprintResults.get();
        for (int index = 0; index < count; index++) {
            int offset = index * 20;
            input.putInt(offset, x[index]).putInt(offset + 4, z[index]).putInt(offset + 8, step)
                    .putInt(offset + 12, bottom[index]).putInt(offset + 16, top[index]);
        }
        final int result;
        try { result = RustWorldgenBackend.exteriorFootprints(handle(), input, output, count); }
        catch (UnsatisfiedLinkError olderLibrary) {
            legacyExteriorBatch = true;
            return super.exteriorFootprints(x, z, step, bottom, top, count, valid);
        }
        if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (result == -2) return super.exteriorFootprints(x, z, step, bottom, top, count, valid);
        if (result != count) throw new IllegalStateException("Incomplete native exterior batch");
        boolean[][] footprints = new boolean[count][];
        int stride = 4 + nativeHeight;
        for (int index = 0; index < count; index++) {
            int start = index * stride, length = output.getInt(start);
            if (length == 0) continue;
            if (length != top[index] - bottom[index]) throw new IllegalStateException("Exterior batch height mismatch");
            footprints[index] = new boolean[length];
            for (int height = 0; height < length; height++) footprints[index][height] = output.get(start + 4 + height) != 0;
        }
        return footprints;
    }
    @Override boolean[] exteriorFootprint(int x, int z, int step, int bottom, int top,
                                          java.util.function.BooleanSupplier valid) {
        if (top <= bottom) return null;
        if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        if (surfaceStage!=null || legacyExteriorBackend) return super.exteriorFootprint(x, z, step, bottom, top, valid);
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
    @Override void refineSurfaceChunk(int baseX,int baseZ,int step,int grid,ClientColumnSample[] samples,
            int fromX,int toX,int fromZ,int toZ,java.util.function.BooleanSupplier current) {
        if(surfaceStage!=null)surfaceStage.refineChunk(baseX,baseZ,step,grid,samples,fromX,toX,fromZ,toZ,current);
        else super.refineSurfaceChunk(baseX,baseZ,step,grid,samples,fromX,toX,fromZ,toZ,current);
    }
    @Override ClientColumnSample sampleInterior(int x, int z) {
        if(surfaceStage!=null)return surfaceStage.sample(x,z);
        ByteBuffer input = positions.get(), output = wallColumn.get();
        input.putInt(0, x).putInt(4, z);
        if (RustWorldgenBackend.interiorColumns(handle(), input, output, 1) != 1)
            throw new IllegalStateException("Incomplete native interior column");
        int min = nativeMinY, height = output.getInt(12);
        if (height != nativeHeight) throw new IllegalStateException("Interior column height mismatch");
        var result = PredictionColumnVolume.sample(min, height, y -> {
            int state = output.getInt(16 + (y - min) * 4);
            return states[state].isAir() ? -1 : blocks[state];
        }, block -> {
            var state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
            return state.getFluidState().isEmpty() ? 0 : state.is(Blocks.LAVA) ? 2 : 1;
        }).asSample();
        return javaSurface && !javaSurfaceFailed ? context.resolveInterior(result,x,z,this::terrainHeight) : result;
    }
    @Override ClientColumnSample wallEvidence(int x, int z, ClientColumnSample sample) {
        if (surfaceStage!=null || cancelled || world == 0) return sample;
        ByteBuffer input = positions.get();
        ByteBuffer output = wallColumn.get();
        input.putInt(0, x).putInt(4, z);
        if (RustWorldgenBackend.columns(world, input, output, 1) != 1) return sample;
        return PredictionWallEvidence.inspect(sample, nativeMinY, y -> {
            if (y < nativeMinY || y >= nativeMinY + nativeHeight) return false;
            var state = states[output.getInt(16 + (y - nativeMinY) * 4)];
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
    @Override boolean stagedSurface() { return surfaceStage!=null; }
    @Override boolean basicInteriorTerrain() { return context.interiorTerrain(); }
    @Override ClientColumnSample sampleBasicInterior(int x,int z) {
        // Full occupancy is still required for ceiling worlds and floating islands.
        ByteBuffer input=positions.get(),output=wallColumn.get();input.putInt(0,x).putInt(4,z);
        if(RustWorldgenBackend.columns(handle(),input,output,1)!=1)throw new IllegalStateException("Incomplete basic column");
        if(output.getInt(12)!=nativeHeight)throw new IllegalStateException("Basic column height mismatch");
        var sample=PredictionColumnVolume.sample(nativeMinY,nativeHeight,y->{
            int state=output.getInt(16+(y-nativeMinY)*4);return states[state].isAir()?-1:blocks[state];
        },block->{var state=net.minecraft.core.registries.BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
            return state.getFluidState().isEmpty()?0:state.is(Blocks.LAVA)?2:1;}).asSample();
        return PredictionSurfaceRefinement.basic(context.resolveInterior(sample,x,z,this::terrainHeight));
    }
    @Override boolean interiorTerrain() { return surfaceStage!=null || context.interiorTerrain(); }
    long runtimeSurfaceChunks() { return surfaceStage==null?0:surfaceStage.builds(); }
    private final ThreadLocal<ByteBuffer[]> surfaceChunkBuffers=new ThreadLocal<>();
    private volatile boolean legacyRawChunk;
    private final ThreadLocal<ByteBuffer> rawChunkBuffer=new ThreadLocal<>();
    private void fillNativeChunk(net.minecraft.world.level.ChunkPos pos,net.minecraft.world.level.chunk.ProtoChunk chunk) {
        if(!legacyRawChunk) {
            ByteBuffer raw=rawChunkBuffer.get();
            if(raw==null){raw=ByteBuffer.allocateDirect(256*nativeHeight*4).order(ByteOrder.LITTLE_ENDIAN);rawChunkBuffer.set(raw);}
            try {
                if(RustWorldgenBackend.rawChunk(handle(),pos.getMinBlockX()>>4,pos.getMinBlockZ()>>4,raw)!=256)
                    throw new IllegalStateException("Incomplete native raw chunk");
                var position=new BlockPos.MutableBlockPos();
                for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<nativeHeight;y++) {
                    BlockState state=states[raw.getInt(((x*16+z)*nativeHeight+y)*4)];
                    if(!state.isAir())chunk.setBlockState(position.set(pos.getMinBlockX()+x,nativeMinY+y,pos.getMinBlockZ()+z),state,false);
                }
                return;
            } catch(UnsatisfiedLinkError olderLibrary){legacyRawChunk=true;}
        }

        ByteBuffer[] buffers=surfaceChunkBuffers.get();
        if(buffers==null) {
            buffers=new ByteBuffer[]{ByteBuffer.allocateDirect(64*8).order(ByteOrder.LITTLE_ENDIAN),
                    ByteBuffer.allocateDirect(64*(16+nativeHeight*4)).order(ByteOrder.LITTLE_ENDIAN)};
            surfaceChunkBuffers.set(buffers);
        }
        ByteBuffer input=buffers[0],output=buffers[1];
        var cursor=new BlockPos.MutableBlockPos();int stride=16+nativeHeight*4;
        for(int first=0;first<256;first+=64) {
            handle();input.clear();
            for(int i=first;i<first+64;i++)input.putInt(pos.getMinBlockX()+(i&15)).putInt(pos.getMinBlockZ()+(i>>4));
            if(RustWorldgenBackend.columns(handle(),input,output,64)!=64)throw new IllegalStateException("Incomplete native surface chunk");
            for(int i=0;i<64;i++) {
                int at=(first+i),x=pos.getMinBlockX()+(at&15),z=pos.getMinBlockZ()+(at>>4);
                if(output.getInt(i*stride+12)!=nativeHeight)throw new IllegalStateException("Native surface height mismatch");
                for(int y=0;y<nativeHeight;y++) {
                    BlockState state=states[output.getInt(i*stride+16+y*4)];
                    if(!state.isAir())chunk.setBlockState(cursor.set(x,nativeMinY+y,z),state,false);
                }
            }
        }
    }
    @Override boolean floatingTerrain() { return context.floatingTerrain(); }
    @Override public int seaLevel() { return context.seaLevel(); }
    @Override public int fluidColor() { return context.fluidColor(); }
    @Override ClientTerrainSampler decorationContext() { return context; }
    synchronized void cancelWork() {
        cancelled=true;
        context.detachHorizontalClimate();
        if(world!=0) RustWorldgenBackend.cancel(world);
    }
    @Override public synchronized void close() {
        context.detachHorizontalClimate();
        long id = world; world = 0; cancelled=true;
        if(surfaceStage!=null)surfaceStage.close();
        if (id == 0) return;
        try {
            try { RustWorldgenBackend.cancel(id); } finally { RustWorldgenBackend.close(id); }
        } finally {
            featureDescriptors.clear();
            synchronized(points) { points.clear(); } synchronized(tints) { tints.clear(); }
            context.close();
        }
    }
}
