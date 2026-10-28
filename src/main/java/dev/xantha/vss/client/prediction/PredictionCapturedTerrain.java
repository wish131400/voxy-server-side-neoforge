package dev.xantha.vss.client.prediction;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Worker-only authoritative fallback. Immutable columns and area mips share a bounded decode cache. */
final class PredictionCapturedTerrain {
    static final int LEVELS = 12;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final int MAX_ENTRIES = 4096;
    private final PredictionDiskCache disk;
    private final long limit;
    private final Map<PredictionDiskCache.Key, Node> cache = new LinkedHashMap<>(128, .75F, true);
    private final Map<PredictionDiskCache.Key, Boolean> regions = new LinkedHashMap<>(32, .75F, true);
    private final Map<PredictionDiskCache.Key, java.util.concurrent.CompletableFuture<Node>> decoding = new java.util.HashMap<>();
    private volatile boolean available;
    private long bytes;
    private long updates, reads, evictions, coalescedReads, generation;

    PredictionCapturedTerrain(PredictionDiskCache disk) { this(disk, MAX_BYTES); }
    PredictionCapturedTerrain(PredictionDiskCache disk, long limit) {
        this.disk = disk; this.limit = limit; this.available = disk != null && disk.hasCapturedTerrain();
    }

    /** Exact integer totals avoid accumulating rounding error through repeated mip reductions. */
    record Summary(long count, long surface, long fluid, long wet, long solid, ClientColumnSample material) {
        static Summary of(ClientColumnSample sample) {
            return new Summary(1, sample.surfaceY(), sample.fluidY(), sample.hasFluid() ? 1 : 0,
                    sample.hasSurface() ? 1 : 0, material(sample));
        }
        private static ClientColumnSample material(ClientColumnSample s) {
            return material(s, s);
        }
        private static ClientColumnSample material(ClientColumnSample s, ClientColumnSample liquid) {
            return new ClientColumnSample(s.surfaceY(), Math.max(s.fluidY(), liquid.fluidY()), s.biomeIndex(), s.topBlockIndex(), 0, 0, 0, 0,
                    liquid.fluid(), (s.flags() & (ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE
                            | ClientColumnSample.FLAG_NO_SURFACE)) | ClientColumnSample.FLAG_CAPTURED
                            | (liquid.flags() & ClientColumnSample.FLAG_ICE) | ClientColumnSample.FLAG_SURFACE_ONLY, 0, s.underBlockIndex(), s.deepBlockIndex(),
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN);
        }
        static Summary combine(Summary[] values) {
            long count = 0, surface = 0, fluid = 0, wet = 0, solid = 0;
            ClientColumnSample material = null;
            ClientColumnSample liquid = null;
            for (Summary value : values) if (value != null) {
                count += value.count; surface += value.surface; fluid += value.fluid;
                wet += value.wet; solid += value.solid;
                if (material == null || value.material.surfaceY() < material.surfaceY()) material = value.material;
                if (liquid == null && value.wet > 0 && value.material.fluid() != 0) liquid = value.material;
            }
            return count == 0 ? null : new Summary(count, surface, fluid, wet, solid,
                    liquid == null ? material : material(material, liquid));
        }
        static Summary columns(ClientColumnSample[] columns) {
            long surface=0,fluid=0,wet=0,solid=0;
            ClientColumnSample material=null,liquid=null;
            for(var sample:columns) {
                surface+=sample.surfaceY();fluid+=sample.fluidY();
                wet+=sample.hasFluid()?1:0;solid+=sample.hasSurface()?1:0;
                if(material==null || sample.surfaceY()<material.surfaceY())material=sample;
                if(liquid==null && sample.hasFluid() && sample.fluid()!=0)liquid=sample;
            }
            return material==null?null:new Summary(columns.length,surface,fluid,wet,solid,
                    material(material,liquid==null?material:liquid));
        }
        ClientColumnSample sample(ClientColumnSample fallback, long area) {
            long missing = Math.max(0, area - count);
            int y = (int) Math.floorDiv(surface + missing * fallback.surfaceY() + area / 2, area);
            int waterY = (int) Math.floorDiv(fluid + missing * fallback.fluidY() + area / 2, area);
            boolean water = wet + (fallback.hasFluid() ? missing : 0) > area / 2;
            boolean ground = solid + (fallback.hasSurface() ? missing : 0) != 0;
            var source = material.hasSurface() || missing == 0 ? material : fallback;
            int flags = ClientColumnSample.FLAG_CAPTURED | ClientColumnSample.FLAG_SURFACE_ONLY
                    | ClientColumnSample.FLAG_CAPTURE_MIP | (ground ? 0 : ClientColumnSample.FLAG_NO_SURFACE);
            if (water && source.ice()) flags |= ClientColumnSample.FLAG_ICE;
            return new ClientColumnSample(y, water ? Math.max(y, waterY) : y, source.biomeIndex(),
                    ground ? source.topBlockIndex() : ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                    water ? (source.fluid() == 0 ? fallback.fluid() : source.fluid()) : 0, flags, 0,
                    source.underBlockIndex(), source.deepBlockIndex(), ClientColumnSample.NO_SPAN,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        }
    }

    static final class Node {
        final ClientColumnSample[] columns;
        final Summary[] children;
        final Summary summary;
        final long bytes;
        Node(ClientColumnSample[] columns, Summary[] children) {
            this.columns = columns; this.children = children;
            if (columns != null) {
                long size = 64L + columns.length * 112L;
                for (var column : columns) {
                    if (column.volume() != null) size += column.volume().bytes();
                }
                summary = Summary.columns(columns); bytes = size;
            } else { summary = Summary.combine(children); bytes = 1024; }
        }
        void write(DataOutputStream output, PredictionCacheMappings mappings) throws IOException {
            output.writeBoolean(columns != null);
            ClientColumnSample[] samples = columns != null ? columns
                    : Arrays.stream(children).filter(java.util.Objects::nonNull).map(Summary::material)
                            .toArray(ClientColumnSample[]::new);
            writeSamples(output, samples, mappings);
            if (columns == null) for (Summary child : children) {
                output.writeBoolean(child != null);
                if (child != null) {
                    output.writeLong(child.count); output.writeLong(child.surface); output.writeLong(child.fluid);
                    output.writeLong(child.wet); output.writeLong(child.solid);
                }
            }
        }
        static Node read(DataInputStream input, PredictionCacheMappings mappings) throws IOException {
            boolean leaf = input.readBoolean();
            ClientColumnSample[] columns = readSamples(input, mappings);
            if (leaf) {
                if (columns.length != 256) throw new IOException("capture column count");
                return new Node(columns, null);
            }
            if (columns.length > 4) throw new IOException("capture children count");
            Summary[] children = new Summary[4];
            int index = 0;
            for (int i = 0; i < 4; i++) if (input.readBoolean()) {
                if (index >= columns.length) throw new IOException("capture child palette");
                long count = input.readLong(), y = input.readLong(), water = input.readLong();
                long wet = input.readLong(), solid = input.readLong();
                if (count <= 0 || count > (256L << (LEVELS * 2)) || wet < 0 || wet > count
                        || solid < 0 || solid > count || Math.abs(y) > count * 1_000_000L
                        || Math.abs(water) > count * 1_000_000L) throw new IOException("capture summary range");
                children[i] = new Summary(count, y, water, wet, solid, columns[index++]);
            }
            if (index != columns.length) throw new IOException("capture child count");
            return new Node(null, children);
        }
    }

    /** One serialized capture lane calls this. No manager/render lock is held during IO. */
    boolean put(int chunkX, int chunkZ, ClientColumnSample[] samples) {
        if (samples.length != 256 || Arrays.stream(samples).anyMatch(s -> s == null || !s.captured()))
            throw new IllegalArgumentException("complete authoritative capture required");
        available = true;
        var key = key(chunkX, chunkZ, 0);
        Node previous = get(key);
        boolean changed = previous == null || !Arrays.equals(previous.columns, samples);
        Node node = changed ? new Node(samples.clone(), null) : previous;
        if (changed) save(key, node);
        for (int level = 1; level <= LEVELS; level++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int child = ((chunkX >> (level - 1)) & 1) | (((chunkZ >> (level - 1)) & 1) << 1);
            key = key(chunkX >> level, chunkZ >> level, level);
            Node parent = get(key);
            if (parent != null && java.util.Objects.equals(parent.children[child], node.summary)) {
                node = parent;
                continue;
            }
            Summary[] children = parent == null ? new Summary[4] : parent.children.clone();
            children[child] = node.summary;
            node = new Node(null, children);
            save(key, node); changed = true;
        }
        synchronized (this) { if (changed) updates++; }
        return changed;
    }

    ClientColumnSample exact(int x, int z) {
        if (!available) return null;
        Node node = get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16), 0));
        return node == null ? null : node.columns[Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16)];
    }

    /** Area ownership follows the mesher's north-west column, including negative coordinates. */
    ClientColumnSample sample(int x, int z, int step, ClientColumnSample fallback) {
        if (!available) return fallback;
        if ((fallback.flags() & ClientColumnSample.FLAG_CAPTURE_MIP) != 0) return fallback;
        if (step <= 1) { var exact = exact(x, z); return exact == null ? fallback : exact; }
        if ((step & (step - 1)) != 0 || step > (16 << LEVELS)) return fallback;
        long area = (long) step * step;
        Summary summary;
        if (step < 16) {
            Node leaf = get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16), 0));
            if (leaf == null) return fallback;
            int sx = Math.floorMod(Math.floorDiv(x, step) * step, 16);
            int sz = Math.floorMod(Math.floorDiv(z, step) * step, 16);
            long y = 0, water = 0, wet = 0, solid = 0;
            ClientColumnSample material = leaf.columns[sz * 16 + sx];
            ClientColumnSample liquid = null;
            for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++) {
                var s = leaf.columns[(sz + dz) * 16 + sx + dx];
                y += s.surfaceY(); water += s.fluidY(); wet += s.hasFluid() ? 1 : 0; solid += s.hasSurface() ? 1 : 0;
                if (s.surfaceY() < material.surfaceY()) material = s;
                if (liquid == null && s.hasFluid()) liquid = s;
            }
            summary = new Summary(area, y, water, wet, solid,
                    Summary.material(material, liquid == null ? material : liquid));
        } else {
            int level = Integer.numberOfTrailingZeros(step) - 4;
            Node node = get(key(Math.floorDiv(x, step), Math.floorDiv(z, step), level));
            summary = node == null ? null : node.summary;
        }
        return summary == null ? fallback : summary.sample(fallback, area);
    }

    private static PredictionDiskCache.Key key(int x, int z, int level) {
        return new PredictionDiskCache.Key(3, x, z, level);
    }
    private Node get(PredictionDiskCache.Key key) {
        if (!available) return null;
        synchronized (this) { if (cache.containsKey(key)) return cache.get(key); }
        // An absent region proves all its 1024 cells absent. Do not fill the node
        // cache with thousands of redundant misses and evict actual captures.
        if (disk == null || !regionPresent(key)) return null;
        java.util.concurrent.CompletableFuture<Node> flight;boolean owner=false;long stamp;
        synchronized (this) {
            if (cache.containsKey(key)) return cache.get(key);
            stamp=generation;flight=decoding.get(key);
            if(flight==null) {flight=new java.util.concurrent.CompletableFuture<>();decoding.put(key,flight);owner=true;}
            else coalescedReads++;
        }
        if(!owner)return flight.join();
        try {
            Node value;
            try(var lease=disk.lease(key)) {value=disk.readCapturedNode(lease);}
            synchronized(this) {
                if(stamp!=generation)value=null;
                else if(flight.isDone())value=flight.getNow(null); // a newer capture wins even after eviction
                else if(cache.containsKey(key))value=cache.get(key);
                else {reads++;retain(key,value);}
                flight.complete(value);
            }
            return value;
        } catch(Throwable failure) {flight.completeExceptionally(failure);throw failure;}
        finally {synchronized(this) {decoding.remove(key,flight);}}
    }
    private void save(PredictionDiskCache.Key key, Node node) {
        // Persist before eviction: dropping a decoded leaf must not drop the only real copy.
        if (disk != null) try (var lease = disk.lease(key)) {
            if (!disk.writeCapturedNode(lease, node)) throw new IllegalStateException("capture persistence failed");
        }
        synchronized (this) {
            regions.put(regionKey(key), true);
            trimRegions();
            retain(key, node);
            var flight=decoding.get(key);
            if(flight!=null)flight.complete(node);
        }
    }
    private static PredictionDiskCache.Key regionKey(PredictionDiskCache.Key key) {
        return key(key.x() >> 5, key.z() >> 5, key.detail());
    }
    private boolean regionPresent(PredictionDiskCache.Key key) {
        var region = regionKey(key);
        synchronized (this) { if (regions.containsKey(region)) return regions.get(region); }
        boolean found = disk.hasCapturedRegion(key);
        synchronized (this) {
            // A capture can create the file while a reader probes it.
            if (Boolean.TRUE.equals(regions.get(region))) return true;
            regions.put(region, found); trimRegions();
        }
        return found;
    }
    private void trimRegions() {
        while (regions.size() > 256) regions.remove(regions.keySet().iterator().next());
    }
    private void retain(PredictionDiskCache.Key key, Node node) {
        Node old = cache.put(key, node);
        bytes += (node == null ? 0 : node.bytes) - (old == null ? 0 : old.bytes);
        while (bytes > limit || cache.size() > MAX_ENTRIES) {
            var iterator = cache.entrySet().iterator();
            var entry = iterator.next();
            bytes -= entry.getValue() == null ? 0 : entry.getValue().bytes;
            iterator.remove(); evictions++;
        }
    }
    synchronized long bytes() { return bytes; }
    synchronized String diagnostics() {
        return "captures={bytes=" + bytes + ",entries=" + cache.size() + ",updates=" + updates
                + ",reads=" + reads + ",evictions=" + evictions + ",coalescedReads="+coalescedReads+",decoding="+decoding.size()+"}";
    }
    synchronized void clear() { generation++;cache.clear(); regions.clear();decoding.clear(); bytes = 0; }

    private static void writeSamples(DataOutputStream out, ClientColumnSample[] samples,
                                     PredictionCacheMappings mappings) throws IOException {
        out.writeInt(samples.length);
        Map<Integer, Integer> blocks = new LinkedHashMap<>(), biomes = new LinkedHashMap<>();
        for (var s : samples) {
            biomes.computeIfAbsent(s.biomeIndex(), ignored -> biomes.size());
            for (int b : new int[]{s.topBlockIndex(), s.underBlockIndex(), s.deepBlockIndex()})
                blocks.computeIfAbsent(b, ignored -> blocks.size());
            if (s.volume() != null) for (int i = 0; i < s.volume().size(); i++)
                blocks.computeIfAbsent(s.volume().block(i), ignored -> blocks.size());
        }
        out.writeBoolean(mappings != null); out.writeInt(biomes.size());
        for (int biome : biomes.keySet()) { if (mappings == null) out.writeInt(biome); else out.writeUTF(mappings.biomeName(biome)); }
        out.writeInt(blocks.size());
        for (int block : blocks.keySet()) out.writeUTF(PredictionCacheMappings.blockName(block));
        for (var sample : samples) PredictionSampleStore.writeSample(out,
                PredictionSampleStore.remap(sample, biomes::get, blocks::get));
    }
    private static ClientColumnSample[] readSamples(DataInputStream in, PredictionCacheMappings mappings) throws IOException {
        int count = bound(in.readInt(), 256);
        boolean named = in.readBoolean();
        if (named && mappings == null) throw new IOException("capture biome mapping unavailable");
        int[] biomes = new int[bound(in.readInt(), 256)];
        for (int i = 0; i < biomes.length; i++) biomes[i] = named ? mappings.biomeId(in.readUTF()) : in.readInt();
        int[] blocks = new int[bound(in.readInt(), 65536)];
        for (int i = 0; i < blocks.length; i++) blocks[i] = PredictionCacheMappings.blockId(in.readUTF());
        ClientColumnSample[] result = new ClientColumnSample[count];
        PredictionSampleStore.IdMapper biome=id->biomes[index(id,biomes.length)],block=id->blocks[index(id,blocks.length)];
        for (int i = 0; i < count; i++) {
            result[i] = PredictionSampleStore.readSample(in,true,biome,block);
            if (!result[i].captured()) throw new IOException("non-authoritative capture");
        }
        return result;
    }
    private static int bound(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("capture palette size");
        return value;
    }
    private static int index(int value, int size) throws IOException {
        if (value < 0 || value >= size) throw new IOException("capture palette index");
        return value;
    }
}
