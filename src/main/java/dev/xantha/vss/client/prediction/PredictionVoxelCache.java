package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Stable CPU voxel records. Renderer mappings, source proofs and GPU handles are session-local. */
final class PredictionVoxelCache {
    // Version 12 retains staged surface progress, including the sampling halo.
    static final int VERSION = 12;
    static final int FEATURE_PAGE_SIZE = 4096, MAX_FEATURES = 2_097_152;
    private static final int MAX_STATES = 262_144, MAX_RUNS = 1_048_576;
    private static long tagGeneration = -1;
    private static byte[] tags;

    static byte[] identity(ClientTerrainSampler sampler) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            var p = sampler.profile();
            hash.update((p.seed()+":"+p.dimension()+":"+p.minY()+":"+p.height()+":"
                    +p.generatorType()+":"+p.generatorSettings()+":"+p.fingerprint())
                    .getBytes(StandardCharsets.UTF_8));
            byte[] generator=p.generatorData();
            if (generator.length>0) {
                try {
                    var raw=dev.xantha.vss.common.processing.LodByteCompression.decompress(generator,
                            p.generatorCompression(),p.generatorRawSize(),
                            dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES);
                    var json=com.google.gson.JsonParser.parseString(new String(raw,StandardCharsets.UTF_8));
                    if (json.isJsonObject() && json.getAsJsonObject().has("vss_freeterraforged_runtime"))
                        generator=dev.xantha.vss.common.worldgen.WorldgenJson.cacheSha256(json).getBytes(StandardCharsets.UTF_8);
                } catch (IOException | com.google.gson.JsonParseException invalid) {
                    throw new IllegalArgumentException("Invalid generator cache identity",invalid);
                }
            }
            hash.update(generator);
            hash.update(tagIdentity());
            // Old End heightfields were closed down to minY. Rebuild finished voxels only here;
            // terrain grids and full decoration caches remain reusable and gain column occupancy.
            if (sampler.floatingTerrain())
                hash.update(":floating-columns-r1".getBytes(StandardCharsets.UTF_8));
            return hash.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static synchronized byte[] tagIdentity() throws java.security.NoSuchAlgorithmException {
        long generation = PredictionVegetationTraits.generation();
        if (tags == null || generation != tagGeneration) {
            var hash = MessageDigest.getInstance("SHA-256");
            // Sort names, never registry IDs. This runs once per tag reload on a worker.
            BuiltInRegistries.BLOCK.getTags().sorted(Comparator.comparing(p -> p.getFirst().location().toString()))
                    .forEach(pair -> {
                        hash.update((pair.getFirst().location()+"\n").getBytes(StandardCharsets.UTF_8));
                        pair.getSecond().stream().map(h -> BuiltInRegistries.BLOCK.getKey(h.value()).toString())
                                .sorted().forEach(name -> hash.update((name+"\n").getBytes(StandardCharsets.UTF_8)));
                    });
            tags = hash.digest(); tagGeneration = generation;
        }
        return tags;
    }

    /** Compare live immutable publications without serializing or retaining retired payloads. */
    static boolean sameContent(PredictionTile left,PredictionTile right) {
        if(left==null)return false;
        var a=left.data().voxyTile;var b=right.data().voxyTile;
        return a.axis()==b.axis() && a.span()==b.span() && a.surfaceComplete()==b.surfaceComplete()
                && a.cityFeatures()==b.cityFeatures() && left.depthBound().equals(right.depthBound())
                && (left.heights()==left.groundHeights())==(right.heights()==right.groundHeights())
                && Arrays.equals(left.heights(),right.heights()) && Arrays.equals(left.groundHeights(),right.groundHeights())
                && Arrays.equals(left.samples(),right.samples())
                && Arrays.equals(left.data().surfaceRefinement,right.data().surfaceRefinement)
                && Arrays.equals(a.columns(),b.columns())
                && a.boxes().equals(b.boxes());
    }

    static PredictionTile persistable(PredictionTile tile, PredictionDecorationRange window) {
        var data = tile.data();
        var voxels = data.voxyTile;
        if (!voxels.approximateDecorations() && (!(voxels.surfaceComplete() || voxels.hasVegetation())
                || window.contains(voxels.baseX(), voxels.baseZ(), voxels.span()))) return tile;

        // Structure hints belong to the terrain snapshot. Vegetation at the window edge
        // and the far approximation only describe this session's display.
        var structures = new PredictionVoxyBoxes.Builder();
        structures.addRange(voxels.features(), 0, voxels.cityFeatures());
        var terrain = new PredictionVoxySource.Tile(voxels.key(), voxels.baseX(), voxels.baseZ(),
                voxels.span(), voxels.axis(), voxels.step(), voxels.columns(), structures.build(),
                voxels.terrainBytes(), false, voxels.cityFeatures());
        // Keep the terrain-only digest separate from the live decorated tile's identity.
        var payload = PredictionVoxelData.forVoxy(terrain);
        payload.surfaceRefinement = data.surfaceRefinement;
        payload.retainedSampleObjects = data.retainedSampleObjects;
        payload.retainedVolumeBytes = data.retainedVolumeBytes;
        return new PredictionTile(tile.key(), tile.heights(), tile.groundHeights(), tile.samples(),
                payload, tile.depthBound(), tile.generatedAtNanos(), tile.revision(), tile.cellAxis(),
                tile.spacingBlocks(), tile.scopeOnly());
    }

    static boolean improves(Data saved, PredictionTile resident) {
        if (resident == null) return true;
        if (saved.voxels().axis() != resident.cellAxis()) return saved.voxels().axis() > resident.cellAxis();
        return quality(saved) > quality(resident);
    }

    record Data(PredictionVoxySource.Tile voxels, int[] heights, int[] groundHeights,
                ClientColumnSample[] samples, PredictionDepthBound bounds, byte[] cacheIdentity,
                ClientColumnSample[] refinement) {
        Data(PredictionVoxySource.Tile voxels,int[] heights,int[] groundHeights,ClientColumnSample[] samples,PredictionDepthBound bounds,byte[] cacheIdentity) {
            this(voxels,heights,groundHeights,samples,bounds,cacheIdentity,null);
        }
        Data(PredictionVoxySource.Tile voxels,int[] heights,int[] groundHeights,ClientColumnSample[] samples,PredictionDepthBound bounds) {
            this(voxels,heights,groundHeights,samples,bounds,null);
        }
        static Data snapshot(PredictionTile tile) {
            return new Data(tile.data().voxyTile, tile.heights().clone(),
                    tile.groundHeights() == tile.heights() ? null : tile.groundHeights().clone(),
                    tile.samples().clone(), tile.depthBound(), null,
                    tile.data().surfaceRefinement == null ? null : tile.data().surfaceRefinement.clone());
        }
        PredictionTile restore(long revision, boolean scopeOnly) {
            return restore(revision,scopeOnly,null);
        }
        PredictionTile restore(long revision, boolean scopeOnly, PredictionTile previous) {
            var result = voxels.retainFeatures(previous == null ? null : previous.data().voxyTile);
            var depth = result == voxels ? bounds : new PredictionDepthBound(
                    Math.min(bounds.minY(),previous.depthBound().minY()),
                    Math.max(bounds.maxY(),previous.depthBound().maxY()));
            var payload = PredictionVoxelData.forVoxy(result);
            if(result==voxels)payload.cacheContentIdentity=cacheIdentity;
            payload.surfaceRefinement = refinement;
            var progress = refinement == null ? new ClientColumnSample[0] : refinement;
            payload.retainedSampleObjects = PredictionSampleCompaction.compact(samples,progress);
            payload.retainedVolumeBytes = PredictionSampleCompaction.volumeBytes(samples,progress);
            return new PredictionTile(voxels.key(), heights, groundHeights == null ? heights : groundHeights,
                    samples, payload, depth, System.nanoTime(), revision, voxels.axis(), voxels.step(), scopeOnly);
        }
    }

    static int quality(Data data) {
        return quality(data.voxels(),data.refinement() == null ? data.samples() : data.refinement());
    }
    static int quality(PredictionTile tile) {
        return quality(tile.data().voxyTile,tile.data().surfaceRefinement == null ? tile.samples() : tile.data().surfaceRefinement);
    }
    private static int quality(PredictionVoxySource.Tile tile, ClientColumnSample[] progress) {
        int complete = 0;
        for (var sample : progress) if (sample != null && !sample.surfacePending()) complete++;
        // Finished publications release their halo. Compare them as a complete full grid,
        // otherwise a nearly finished saved halo could outrank the cropped final samples.
        if (!PredictionSurfaceRefinement.pending(progress)) complete=(tile.axis()+2)*(tile.axis()+2);
        return tile.axis() * (66*66+1)*2 + complete*2
                + (tile.surfaceComplete() && !tile.approximateDecorations() ? 1 : 0);
    }

    static void write(DataOutputStream out, Data data, int settings, byte[] identity, byte[] catalog,
                      PredictionDiskCache disk) throws IOException {
        var tile = data.voxels();
        if(tile.approximateDecorations())throw new IOException("Approximate decorations are session-local");
        out.writeInt(tile.axis()); // The region index can probe this without decompressing the record.
        out.writeInt(settings); out.write(identity);
        out.writeBoolean(catalog != null); if (catalog != null) out.write(catalog);
        writeBody(out, data, disk);
    }

    /** Hash the canonical body while writing it once on the disk worker. */
    static void writeAndDigest(DataOutputStream out, Data data, int settings, byte[] identity, byte[] catalog,
                               PredictionDiskCache disk, PredictionVoxelData payload) throws IOException {
        var tile = data.voxels();
        if (tile.approximateDecorations()) throw new IOException("Approximate decorations are session-local");
        out.writeInt(tile.axis()); out.writeInt(settings); out.write(identity);
        out.writeBoolean(catalog != null); if (catalog != null) out.write(catalog);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(java.nio.ByteBuffer.allocate(41).putInt(tile.axis()).array());
            var body = new DataOutputStream(new BufferedOutputStream(
                    new java.security.DigestOutputStream(out, digest), 32 * 1024));
            writeBody(body, data, disk);
            body.flush();
            payload.cacheContentIdentity = digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void writeBody(DataOutputStream out, Data data, PredictionDiskCache disk) throws IOException {
        var tile = data.voxels();
        out.writeInt(tile.span()); out.writeBoolean(tile.surfaceComplete()); out.writeInt(tile.cityFeatures());
        out.writeInt(data.bounds().minY()); out.writeInt(data.bounds().maxY());
        var states = new LinkedHashMap<BlockState, Integer>();
        var biomes = new LinkedHashMap<String, Integer>();
        for (var column : tile.columns()) {
            biomes.computeIfAbsent(column.biome(), ignored -> biomes.size());
            for (var run : column.runs()) states.computeIfAbsent(run.state(), ignored -> states.size());
        }
        var boxes=tile.boxes();
        for (int i=0;i<boxes.size();i++) states.computeIfAbsent(boxes.state(i), ignored -> states.size());
        out.writeInt(states.size()); for (var state : states.keySet()) out.writeUTF(disk.encodeState(state));
        out.writeInt(biomes.size()); for (var biome : biomes.keySet()) out.writeUTF(biome);
        for (var column : tile.columns()) {
            out.writeInt(biomes.get(column.biome())); out.writeInt(column.runs().size());
            for (var run : column.runs()) {
                out.writeInt(run.bottom()); out.writeInt(run.top()); out.writeInt(states.get(run.state()));
            }
        }
        int features = count(boxes.size(), MAX_FEATURES);
        out.writeInt(features);
        for (int start=0;start<features;start+=FEATURE_PAGE_SIZE) {
            int end=Math.min(features,start+FEATURE_PAGE_SIZE);
            out.writeInt(end-start);
            for (int i=start;i<end;i++) {
                for(int a=0;a<6;a++)out.writeInt(boxes.coordinate(i,a));
                out.writeInt(states.get(boxes.state(i)));
            }
        }
        for (int y : data.heights()) out.writeInt(y);
        out.writeBoolean(data.groundHeights() != null);
        if (data.groundHeights() != null) for (int y : data.groundHeights()) out.writeInt(y);
        int sampleVersion = disk.mappings() != null ? 6
                : Arrays.stream(data.samples()).anyMatch(sample -> sample.volume() != null) ? 3 : 2;
        out.writeInt(sampleVersion);
        disk.encodeTerrain(out, new PredictionDiskCache.TerrainData(data.samples(), Long.MIN_VALUE, null, null, null));
        out.writeBoolean(data.refinement() != null);
        if (data.refinement() != null) {
            int grid = tile.axis() + 2;
            if (data.refinement().length != grid * grid) throw new IOException("voxel refinement grid invalid");
            int refinementVersion = disk.mappings() != null ? 6
                    : Arrays.stream(data.refinement()).anyMatch(sample -> sample.volume() != null) ? 3 : 2;
            out.writeInt(refinementVersion);
            disk.encodeTerrain(out,new PredictionDiskCache.TerrainData(data.refinement(),Long.MIN_VALUE,null,null,null));
        }
    }

    static Data read(DataInputStream in, PredictionTileKey key, int settings, byte[] identity, byte[] catalog,
                     PredictionDiskCache disk, int version) throws IOException {
        int axis = count(in.readInt(), 64);
        if (axis == 0 || (axis & (axis-1)) != 0 || in.readInt() != settings
                || !Arrays.equals(identity, in.readNBytes(32))) throw new IOException("voxel content identity changed");
        byte[] storedCatalog = in.readBoolean() ? in.readNBytes(32) : null;
        if (!Arrays.equals(catalog, storedCatalog)) throw new IOException("voxel template catalog changed");
        // Hash the canonical body while decoding it. Settings, world identity and catalog
        // are separate write namespaces, so their canonical prefix contains zeroes.
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        digest.update(java.nio.ByteBuffer.allocate(41).putInt(axis).array());
        in = new DataInputStream(new BufferedInputStream(new java.security.DigestInputStream(in, digest),32*1024));
        int span = in.readInt(), expected = VssLodLayout.BASE_TILE_BLOCKS << key.lod();
        if (span != expected || span % axis != 0) throw new IOException("voxel layout changed");
        boolean complete = in.readBoolean(); int cities = in.readInt();
        var bounds = new PredictionDepthBound(in.readInt(), in.readInt());
        if (bounds.maxY() < bounds.minY()) throw new IOException("voxel bounds invalid");
        var states = new BlockState[count(in.readInt(), MAX_STATES)];
        for (int i=0;i<states.length;i++) states[i] = disk.decodeState(in.readUTF());
        var biomes = new String[count(in.readInt(), axis*axis)];
        for (int i=0;i<biomes.length;i++) {
            biomes[i] = in.readUTF();
            if (net.minecraft.resources.ResourceLocation.tryParse(biomes[i]) == null)
                throw new IOException("voxel biome name invalid");
        }
        var columns = new PredictionVoxySource.Column[axis*axis];
        var palette = new PredictionVoxyColumns();
        int remainingRuns = MAX_RUNS;
        for (int i=0;i<columns.length;i++) {
            String biome = biomes[index(in.readInt(),biomes.length)];
            int runs = count(in.readInt(), remainingRuns); remainingRuns -= runs;
            var list = new ArrayList<PredictionVoxySource.Run>(runs);
            for (int r=0;r<runs;r++) {
                int bottom = in.readInt(), top = in.readInt();
                if (top <= bottom) throw new IOException("voxel interval invalid");
                list.add(new PredictionVoxySource.Run(bottom,top,states[index(in.readInt(),states.length)]));
            }
            columns[i] = palette.column(list,biome);

        }
        int features = count(in.readInt(), version >= 11 ? MAX_FEATURES : 262_144);
        if (cities < 0 || cities > features) throw new IOException("voxel feature range invalid");
        var boxes = new PredictionVoxyBoxes.Builder();
        for (int start=0;start<features;) {
            int page=version >= 11 ? count(in.readInt(),FEATURE_PAGE_SIZE) : features;
            if (page == 0 || page > features-start) throw new IOException("voxel feature page invalid");
            for (int i=0;i<page;i++) {
                int x=in.readInt(),y=in.readInt(),z=in.readInt(),ex=in.readInt(),ey=in.readInt(),ez=in.readInt();
                if (ex<=x || ey<=y || ez<=z) throw new IOException("voxel box invalid");
                boxes.add(x,y,z,ex,ey,ez,states[index(in.readInt(),states.length)]);
            }
            start+=page;
        }
        int size = (axis+1)*(axis+1);
        int[] heights = new int[size]; for (int i=0;i<size;i++) heights[i]=in.readInt();
        int[] ground = null;
        if (in.readBoolean()) { ground = new int[size]; for(int i=0;i<size;i++) ground[i]=in.readInt(); }
        int sampleVersion = in.readInt();
        if (sampleVersion != 2 && sampleVersion != 3 && sampleVersion != 6) throw new IOException("voxel sample version invalid");
        var samples = disk.decodeTerrain(in,sampleVersion,size).samples();
        ClientColumnSample[] refinement = null;
        if (version >= 12 && in.readBoolean()) {
            int refinementVersion = in.readInt();
            if (refinementVersion != 2 && refinementVersion != 3 && refinementVersion != 6)
                throw new IOException("voxel refinement version invalid");
            refinement = disk.decodeTerrain(in,refinementVersion,(axis+2)*(axis+2)).samples();
        }
        var tile = new PredictionVoxySource.Tile(key,key.tileX()*span,key.tileZ()*span,span,axis,span/axis,
                columns,boxes.build(),palette.bytes(columns.length),complete,cities);
        var data = new Data(tile,heights,ground,samples,bounds,null,refinement);
        if (in.read() != -1) throw new IOException("voxel record has trailing data");
        int canonicalSampleVersion = disk.mappings() != null ? 6
                : Arrays.stream(samples).anyMatch(sample -> sample.volume() != null) ? 3 : 2;
        // Version 10 and legacy numeric palettes need normalization once. Modern
        // records already contain the canonical bytes; no second object traversal.
        byte[] content = version >= 12 && sampleVersion == canonicalSampleVersion ? digest.digest()
                : contentIdentity(data, 0, new byte[32], null, disk);
        return new Data(tile,heights,ground,samples,bounds,content,refinement);
    }

    private static int count(int value, int max) throws IOException {
        if (value<0 || value>max) throw new IOException("voxel count out of range"); return value;
    }
    static byte[] contentIdentity(Data data, int settings, byte[] identity, byte[] catalog,
                                  PredictionDiskCache disk) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try (var out=new DataOutputStream(new BufferedOutputStream(
                    new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest),32*1024))) {
                write(out,data,settings,identity,catalog,disk);
            }
            return digest.digest();
        } catch (IOException | java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static int index(int value, int size) throws IOException {
        if (value<0 || value>=size) throw new IOException("voxel palette index out of range"); return value;
    }
    private PredictionVoxelCache() { }
}
