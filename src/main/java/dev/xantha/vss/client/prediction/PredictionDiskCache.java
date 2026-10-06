package dev.xantha.vss.client.prediction;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Compressed generation results. Deferred snapshots are bounded and encoded on the serial IO worker. */
final class PredictionDiskCache implements AutoCloseable {
    private static final int MAGIC = 0x56535044, SCHEMA = 6;
    private static final int MAX_COLUMNS = 66 * 66, MAX_BLOCKS = 262_144;
    private static final ThreadPoolExecutor COMMITS = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "vss-prediction-disk");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final ScheduledExecutorService COMPACTIONS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "vss-prediction-compaction");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final ConcurrentMap<Path, Shared> ROOTS = new ConcurrentHashMap<>();
    private static final class Shared {
        volatile PredictionDiskCache owner;
        PredictionRegionStorage regions;
        final Set<Key> migrations = new HashSet<>();
        boolean maintenanceQueued;
        final Map<Key, Entry> active = new HashMap<>();
        final Map<Key, Long> invalidations = new HashMap<>();
        long revision;
        boolean deleting;
    }
    private static final class Entry { volatile boolean valid = true; int users; }
    record Key(int kind, int x, int z, int detail) {
        static Key terrain(int x, int z, int lod) { return new Key(0, x, z, lod); }
        static Key surface(int x, int z, int settings) { return new Key(1, x, z, settings); }
        static Key mesh(Key terrain) { return new Key(2, terrain.x, terrain.z, terrain.detail); }
    }
    // Schema 3 includes surface features from their actual decoration stages.
    // Old empty/vegetation-only surface entries must regenerate; terrain stays reusable.
    private static int schema(Key key) { return key.kind == 0 ? SCHEMA : 5; }
    final class Lease implements AutoCloseable {
        final Key key;
        final Entry entry;
        final boolean readable;
        private volatile boolean released;
        Lease(Key key, Entry entry, boolean readable) { this.key = key; this.entry = entry; this.readable = readable; }
        boolean valid() { return !released && entry.valid && !closed && shared.owner == PredictionDiskCache.this; }
        @Override public void close() {
            synchronized (shared) {
                if (released) return;
                released = true;
                if (--entry.users == 0) shared.active.remove(key, entry);
            }
        }
    }
    private final Path root;
    private final long fingerprint;
    private final PredictionCacheMappings mappings;
    private final Shared shared;
    private volatile boolean closed;
    private volatile long lastDemandNanos = System.nanoTime();
    private final LongAdder terrainRepairs = new LongAdder();
    static final long MAINTENANCE_IDLE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final LongAdder hits = new LongAdder(), misses = new LongAdder(), writes = new LongAdder(), errors = new LongAdder();
    // P2-12: one counter could not tell "no file yet" (normal on first visit)
    // from "file present but rejected" (a fingerprint or format bug). These
    // split the same misses so a low hit rate can be explained rather than
    // guessed at.
    private final LongAdder missAbsent = new LongAdder(), missStale = new LongAdder(),
            missCorrupt = new LongAdder(), missIdentity = new LongAdder(), missRaced = new LongAdder();
    private final Map<String, BlockState> decodedStates = new LinkedHashMap<>(64, .75F, true);
    private final Map<BlockState, String> encodedStates = new LinkedHashMap<>(64, .75F, true);
    private final LongAdder stateDecodes = new LongAdder();
    private final LongAdder meshHits = new LongAdder(), meshMisses = new LongAdder(), meshWrites = new LongAdder();
    static final int MAX_PENDING_TERRAIN_WRITES = 64;
    static final long MAX_PENDING_TERRAIN_BYTES = 32L * 1024 * 1024;
    static final int MAX_MESH_WRITE_ORDERS = 32768;
    private static final java.util.concurrent.atomic.AtomicInteger TERRAIN_PENDING = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong TERRAIN_PENDING_BYTES = new java.util.concurrent.atomic.AtomicLong();
    private final LongAdder terrainWriteDeferrals = new LongAdder();
    private static final Object WRITE_BUDGET = new Object();
    private final Object pendingLock = new Object();
    private final Map<Key, PendingWrite> pendingWrites = new HashMap<>();
    private final Map<Key, Integer> terrainWriteColumns = new LinkedHashMap<>(256, .75F, true);
    private final Map<Key, MeshWriteOrder> meshWriteOrders = new LinkedHashMap<>(256, .75F, true);
    private long meshWriteSequence;
    private static final class MeshWriteOrder {
        final long sequence;
        final int axis;
        final byte[] baseIdentity;
        final boolean surfaceCompleted;
        boolean pending = true;
        MeshWriteOrder(long sequence, int axis, byte[] baseIdentity, boolean surfaceCompleted) {
            this.sequence = sequence; this.axis = axis; this.baseIdentity = baseIdentity.clone();
            this.surfaceCompleted = surfaceCompleted;
        }
    }
    private final LongAdder coalescedWrites = new LongAdder(), deferredEncodes = new LongAdder(), deferredEncodeNanos = new LongAdder();
    private final class PendingWrite {
        final Lease lease;
        final int version;
        final Encoder encoder;
        final long bytes;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        PendingWrite(Lease lease, int version, Encoder encoder, long bytes) {
            this.lease = lease; this.version = version; this.encoder = encoder; this.bytes = bytes;
        }
    }
    private static final java.util.concurrent.atomic.AtomicLong MESH_QUEUED = new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadPoolExecutor MESH_WRITES = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "vss-finished-mesh-disk"); thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    PredictionMesh readMesh(Lease terrain, byte[] identity, int axis) {
        if (identity == null || !terrain.valid() || !terrain.readable) return null;
        try (var lease = lease(Key.mesh(terrain.key))) {
            var mesh = read(lease, (input, version) -> {
                int length = bounded(input.readInt(), PredictionMeshCodec.MAX_BYTES);
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new EOFException("mesh payload");
                return PredictionMeshCodec.decode(bytes, identity, axis);
            });
            if (!terrain.valid()) return null;
            if (mesh == null) meshMisses.increment(); else meshHits.increment();
            return mesh;
        }
    }

    /**
     * Restores finished geometry after the terrain record has been decoded,
     * before feature replay. The base identity is independent of vegetation;
     * dirty captures and resource fingerprints still invalidate it.
     */
    PredictionMesh readMeshBase(Lease terrain, byte[] baseIdentity, int axis) {
        var record = readMeshBaseRecord(terrain, baseIdentity, axis);
        return record == null ? null : record.mesh();
    }

    PredictionMeshCodec.MeshRecord readMeshBaseRecord(Lease terrain, byte[] baseIdentity, int axis) {
        return readMeshBaseRecord(terrain, baseIdentity, axis, null, null);
    }

    PredictionMeshCodec.MeshRecord readMeshBaseRecord(Lease terrain, byte[] baseIdentity, int axis,
            byte[] rawIdentity, java.util.function.Predicate<dev.xantha.vss.common.worldgen.LostCityPreview.Tile> validate) {
        if (baseIdentity == null || !terrain.valid() || !terrain.readable) return null;
        try (var lease = lease(Key.mesh(terrain.key))) {
            var record = read(lease, (input, version) -> {
                int length = bounded(input.readInt(), PredictionMeshCodec.MAX_BYTES);
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new EOFException("mesh payload");
                return rawIdentity == null ? PredictionMeshCodec.decodeBaseRecord(bytes, baseIdentity, axis)
                        : PredictionMeshCodec.decodeCityBaseRecord(bytes, rawIdentity, baseIdentity, axis, validate);
            });
            if (!terrain.valid()) return null;
            if (record == null) meshMisses.increment(); else {
                meshHits.increment();
                if (record.surfaceCompleted()) synchronized (shared) {
                    if (terrain.valid() && !meshWriteOrders.containsKey(lease.key) && makeMeshOrderRoom()) {
                        var order = new MeshWriteOrder(0, axis, record.baseIdentity(), true);
                        order.pending = false;
                        meshWriteOrders.put(lease.key, order);
                    }
                }
            }
            return record;
        }
    }

    /** Bounded detached bytes only; never retain a tile/vegetation graph in an IO queue. */
    void writeMeshLater(Lease terrain, byte[] identity, PredictionMesh mesh) {
        writeMeshLater(terrain, identity, mesh, identity, true);
    }

    void writeMeshLater(Lease terrain, byte[] identity, PredictionMesh mesh, byte[] baseIdentity) {
        writeMeshLater(terrain, identity, mesh, baseIdentity, true);
    }

    void writeMeshLater(Lease terrain, byte[] identity, PredictionMesh mesh, byte[] baseIdentity,
                        boolean baseSafe) {
        writeMeshLater(terrain, identity, mesh, baseIdentity, baseSafe, false);
    }

    void writeMeshLater(Lease terrain, byte[] identity, PredictionMesh mesh, byte[] baseIdentity,
                        boolean baseSafe, boolean surfaceCompleted) {
        writeMeshLater(terrain, identity, mesh, baseIdentity, baseSafe, surfaceCompleted, null, null);
    }

    void writeMeshLater(Lease terrain, byte[] identity, PredictionMesh mesh, byte[] baseIdentity,
                        boolean baseSafe, boolean surfaceCompleted, byte[] rawIdentity,
                        dev.xantha.vss.common.worldgen.LostCityPreview.Tile cities) {
        if (identity == null || baseIdentity == null || !terrain.valid()
                || MESH_WRITES.getQueue().remainingCapacity() == 0) return;
        long sequence;
        synchronized (shared) {
            if (!terrain.valid()) return;
            sequence = ++meshWriteSequence;
        }
        byte[] bytes;
        try { bytes = PredictionMeshCodec.encode(mesh, identity, baseIdentity, baseSafe, surfaceCompleted, rawIdentity, cities); }
        catch (IOException | RuntimeException unavailable) { return; }
        if (MESH_QUEUED.addAndGet(bytes.length) > 32L * 1024 * 1024) { MESH_QUEUED.addAndGet(-bytes.length); return; }
        Key meshKey = Key.mesh(terrain.key);
        MeshWriteOrder order = new MeshWriteOrder(sequence, mesh.cellAxis(), baseIdentity, surfaceCompleted);
        synchronized (shared) {
            MeshWriteOrder previous = meshWriteOrders.get(meshKey);
            if (!terrain.valid() || previous != null && (previous.axis > order.axis
                    || previous.axis == order.axis && (previous.sequence > sequence
                        || previous.surfaceCompleted && !surfaceCompleted && Arrays.equals(previous.baseIdentity, baseIdentity)))
                    || previous == null && !makeMeshOrderRoom()) {
                MESH_QUEUED.addAndGet(-bytes.length);
                return;
            }
            Lease owned = lease(meshKey);
            try {
                MESH_WRITES.execute(() -> {
                    try (owned) {
                        if (meshWriteCurrent(owned, order) && write(owned, schema(meshKey),
                                output -> { output.writeInt(bytes.length); output.write(bytes); },
                                () -> meshWriteCurrent(owned, order))) meshWrites.increment();
                    } finally {
                        synchronized (shared) { order.pending = false; }
                        MESH_QUEUED.addAndGet(-bytes.length);
                    }
                });
                // Publish only after admission succeeds. A full queue must not revoke an accepted fine result.
                meshWriteOrders.put(meshKey, order);
            } catch (RejectedExecutionException busy) { owned.close(); MESH_QUEUED.addAndGet(-bytes.length); }
        }
    }

    private boolean meshWriteCurrent(Lease lease, MeshWriteOrder order) {
        synchronized (shared) { return lease.valid() && meshWriteOrders.get(lease.key) == order; }
    }

    private boolean makeMeshOrderRoom() {
        if (meshWriteOrders.size() < MAX_MESH_WRITE_ORDERS) return true;
        var iterator = meshWriteOrders.entrySet().iterator();
        while (iterator.hasNext()) {
            var candidate = iterator.next();
            Key key = candidate.getKey();
            // An older encoder retains its terrain lease before it has a mesh token.
            // Keep that key's accepted order until no such producer can finish late.
            if (!candidate.getValue().pending && !shared.active.containsKey(Key.terrain(key.x, key.z, key.detail))) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }
    private final java.util.concurrent.atomic.AtomicBoolean loggedError = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<Key, Integer> terrainHints = new LinkedHashMap<>(256, .75F, true);
    private final ArrayDeque<Key> probeQueue = new ArrayDeque<>();
    private boolean probing;

    /** Header-only batch lookup; hints never replace the validated read or its lease. */
    void probeTerrain(Collection<Key> candidates) {
        synchronized (shared) {
            if (closed || shared.owner != this) return;
            probeQueue.clear();
            int count = 0;
            for (Key key : candidates) {
                if (key.kind == 0 && !terrainHints.containsKey(key)) probeQueue.add(key);
                if (++count >= 32768) break;
            }
            probeNextBatch();
        }
    }

    private void probeNextBatch() {
        List<Lease> batch = new ArrayList<>();
        synchronized (shared) {
            if (closed || shared.owner != this || probing) return;
            while (!probeQueue.isEmpty()) {
                Key key = probeQueue.removeFirst();
                if (key.kind != 0 || terrainHints.containsKey(key) || shared.invalidations.containsKey(key)) continue;
                batch.add(lease(key));
                if (batch.size() == 128) break;
            }
            if (batch.isEmpty()) return;
            probing = true;
        }
        COMMITS.execute(() -> {
            try {
                for (Lease lease : batch) try (lease) {
                    int axis = 0;
                    if (lease.valid() && lease.readable) {
                        try {
                            byte[] header = shared.regions.header(lease.key);
                            if (header != null) try (var input = new DataInputStream(new ByteArrayInputStream(header))) {
                                int magic = input.readInt(), version = input.readInt();
                                if (magic == MAGIC && version >= 1 && version <= SCHEMA && input.readLong() == fingerprint
                                        && input.readInt() == 0 && input.readInt() == lease.key.x
                                        && input.readInt() == lease.key.z && input.readInt() == lease.key.detail) {
                                    int count = input.readInt(), candidate = (int) Math.sqrt(count) - 2;
                                    if (candidate > 0 && candidate <= 64 && (candidate & (candidate - 1)) == 0
                                            && (candidate + 2) * (candidate + 2) == count) axis = candidate;
                                }
                            }
                        } catch (IOException | RuntimeException ignored) { }
                    }
                    synchronized (shared) {
                        if (lease.valid() && lease.readable) rememberTerrain(lease.key, axis);
                    }
                }
            } finally {
                synchronized (shared) {
                    probing = false;
                    // Yield between batches to writes/invalidations, without waiting for a planner tick.
                    probeNextBatch();
                }
            }
        });
    }

    int cachedTerrainAxis(Key key) {
        synchronized (shared) {
            return closed || shared.owner != this || shared.invalidations.containsKey(key)
                    ? 0 : terrainHints.getOrDefault(key, 0);
        }
    }

    void forgetTerrain(Key key) { synchronized (shared) { rememberTerrain(key, 0); } }

    private void rememberTerrain(Key key, int axis) {
        if (closed || shared.owner != this) return;
        terrainHints.put(key, axis);
        while (terrainHints.size() > 32768) terrainHints.remove(terrainHints.keySet().iterator().next());
    }

    PredictionDiskCache(Path root, long fingerprint) {
        this(root, fingerprint, null);
    }

    PredictionDiskCache(Path root, long fingerprint, PredictionCacheMappings mappings) {
        this.root = root.toAbsolutePath().normalize();
        this.fingerprint = fingerprint;
        this.mappings = mappings;
        shared = ROOTS.computeIfAbsent(this.root, ignored -> new Shared());
        synchronized (shared) {
            if (shared.regions == null) shared.regions = new PredictionRegionStorage(this.root);
            shared.active.values().forEach(entry -> entry.valid = false);
            shared.active.clear();
            shared.owner = this;
        }
        // Inspect existing regions after the initial world load, without adding to login latency.
        COMPACTIONS.schedule(() -> {
            if (closed || shared.owner != this) return;
            try { shared.regions.discoverMaintenance(); }
            catch (IOException | RuntimeException failure) { error(failure); }
            scheduleMaintenance();
        }, 30, TimeUnit.SECONDS);
    }

    PredictionCacheMappings mappings() { return mappings; }

    Lease lease(Key key) {
        lastDemandNanos = System.nanoTime();
        synchronized (shared) {
            Entry entry = shared.active.computeIfAbsent(key, ignored -> new Entry());
            entry.users++;
            return new Lease(key, entry, !shared.invalidations.containsKey(key));
        }
    }

    record TerrainData(ClientColumnSample[] samples, long colorFingerprint,
                       int[] surfaceTints, int[] foliageTints, int[] waterTints) {
        int cellAxis() { return (int) Math.sqrt(samples.length) - VssLodLayout.SAMPLE_MARGIN * 2; }
        boolean colorsMatch(long fingerprint) {
            return fingerprint != Long.MIN_VALUE && fingerprint == colorFingerprint
                    && surfaceTints != null && foliageTints != null && waterTints != null
                    && surfaceTints.length == samples.length && foliageTints.length == samples.length
                    && waterTints.length == samples.length;
        }
    }

    ClientColumnSample[] readTerrain(Lease lease, int count) {
        TerrainData data = readTerrainData(lease, count);
        return data == null ? null : data.samples();
    }

    TerrainData readTerrainData(Lease lease, int count) {
        TerrainData data = read(lease, (input, version) -> {
            int size = bounded(input.readInt(), MAX_COLUMNS);
            if (count > 0 && size != count) throw new IOException("grid size changed");
            int paletteSize = bounded(input.readInt(), MAX_COLUMNS * 3);
            int[] palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                String name = input.readUTF();
                ResourceLocation id = name.isEmpty() ? null : ResourceLocation.tryParse(name);
                if (!name.isEmpty() && (id == null || !BuiltInRegistries.BLOCK.containsKey(id))) throw new IOException("block unavailable: " + name);
                palette[i] = name.isEmpty() ? ClientColumnSample.NO_BLOCK : BuiltInRegistries.BLOCK.getId(BuiltInRegistries.BLOCK.get(id));
            }
            int[] biomes = null;
            if (version >= 6) {
                if (mappings == null) throw new IOException("Biome mapping unavailable");
                int biomeCount = bounded(input.readInt(), MAX_COLUMNS);
                biomes = new int[biomeCount];
                for (int i = 0; i < biomeCount; i++) biomes[i] = mappings.biomeId(input.readUTF());
            }
            ClientColumnSample[] samples = new ClientColumnSample[size];
            for (int i = 0; i < size; i++) {
                int y = input.readInt(), fluidY = input.readInt(), biome = input.readInt();
                biome = biomes != null ? biomes[index(biome, biomes.length)]
                        : mappings == null ? biome : mappings.legacyBiome(biome);
                int top = palette[index(input.readInt(), palette.length)];
                int structure = input.readInt(), tree = input.readInt(), density = input.readInt(), height = input.readInt();
                int fluid = input.readInt(), flags = input.readInt(), ground = input.readInt();
                int under = palette[index(input.readInt(), palette.length)], deep = palette[index(input.readInt(), palette.length)];
                int bottom = input.readInt(), lowerTop = input.readInt(), lowerBottom = input.readInt(), floor = input.readInt();
                PredictionColumnVolume volume = null;
                if (version >= 3 && input.readBoolean()) {
                    int runs = bounded(input.readInt(), PredictionColumnVolume.MAX_RUNS);
                    int[] intervals = new int[runs * 4];
                    for (int r = 0; r < intervals.length; r += 4) {
                        intervals[r] = input.readInt(); intervals[r + 1] = input.readInt();
                        intervals[r + 2] = palette[index(input.readInt(), palette.length)];
                        intervals[r + 3] = input.readUnsignedByte();
                    }
                    volume = new PredictionColumnVolume(intervals);
                }
                samples[i] = new ClientColumnSample(y, fluidY, biome, top, structure, tree, density, height, fluid, flags,
                        ground, under, deep, bottom, lowerTop, lowerBottom, floor, volume);
            }
            long colors = Long.MIN_VALUE;
            int[] surface = null, foliage = null, water = null;
            if (version >= 2) {
                colors = input.readLong();
                if (input.readBoolean()) {
                    surface=new int[size];foliage=new int[size];water=new int[size];
                    for(int i=0;i<size;i++) {surface[i]=input.readInt();foliage[i]=input.readInt();water[i]=input.readInt();}
                }
            }
            return new TerrainData(samples, colors, surface, foliage, water);
        });
        if (data != null && Arrays.stream(data.samples()).anyMatch(ClientColumnSample::cityGround)) {
            // Only contaminated terrain is rebuilt; captures and decoration
            // records stay intact. Do not let its old fine grid block repair.
            synchronized (shared) { terrainWriteColumns.remove(lease.key); }
            terrainRepairs.increment();
            return null;
        }
        if (data != null) synchronized (shared) {
            if (lease.valid()) rememberWriteColumns(lease.key, data.samples().length);
        }
        return data;
    }

    boolean writeTerrain(Lease lease, ClientColumnSample[] samples) {
        return writeTerrain(lease, new TerrainData(samples, Long.MIN_VALUE, null, null, null));
    }

    boolean writeTerrain(Lease lease, TerrainData terrain) {
        if (terrain.samples().length > MAX_COLUMNS
                || Arrays.stream(terrain.samples()).anyMatch(ClientColumnSample::cityGround)) return false;
        boolean volumes = java.util.Arrays.stream(terrain.samples()).anyMatch(sample -> sample.volume() != null);
        return write(lease, mappings != null ? 6 : volumes ? 3 : 2, terrainEncoder(terrain));
    }

    CompletableFuture<Boolean> writeTerrainLater(Lease lease, TerrainData terrain) {
        if (terrain.samples().length > MAX_COLUMNS
                || Arrays.stream(terrain.samples()).anyMatch(ClientColumnSample::cityGround))
            return CompletableFuture.completedFuture(false);
        long bytes = bytesOf(terrain);
        boolean colors = terrain.colorsMatch(terrain.colorFingerprint());
        return defer(lease, bytes, terrain.samples().length, () -> {
            var snapshot = new TerrainData(terrain.samples().clone(), terrain.colorFingerprint(),
                    colors ? terrain.surfaceTints().clone() : null, colors ? terrain.foliageTints().clone() : null,
                    colors ? terrain.waterTints().clone() : null);
            boolean volumes = Arrays.stream(snapshot.samples()).anyMatch(sample -> sample.volume() != null);
            return new PendingWrite(retain(lease), mappings != null ? 6 : volumes ? 3 : 2, terrainEncoder(snapshot), bytes);
        });
    }

    private static long bytesOf(TerrainData terrain) {
        long bytes = 128L + terrain.samples().length * 112L;
        for (var sample : terrain.samples()) if (sample.volume() != null) bytes += sample.volume().bytes();
        if (terrain.colorsMatch(terrain.colorFingerprint())) bytes += 48L + terrain.samples().length * 12L;
        return bytes;
    }

    private Encoder terrainEncoder(TerrainData terrain) {
        ClientColumnSample[] samples = terrain.samples();
        boolean volumes = mappings != null || java.util.Arrays.stream(samples).anyMatch(sample -> sample.volume() != null);
        return output -> {
            Map<Integer, Integer> palette = new LinkedHashMap<>();
            for (var sample : samples) for (int block : new int[]{sample.topBlockIndex(), sample.underBlockIndex(), sample.deepBlockIndex()}) {
                palette.computeIfAbsent(block, ignored -> palette.size());
            }
            for (var sample : samples) if (sample.volume() != null) {
                for (int i = 0; i < sample.volume().size(); i++)
                    palette.computeIfAbsent(sample.volume().block(i), ignored -> palette.size());
            }
            output.writeInt(samples.length);
            output.writeInt(palette.size());
            for (int block : palette.keySet()) output.writeUTF(block == ClientColumnSample.NO_BLOCK ? ""
                    : BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(block)).toString());
            Map<Integer, Integer> biomes = new LinkedHashMap<>();
            if (mappings != null) {
                for (var sample : samples) biomes.computeIfAbsent(sample.biomeIndex(), ignored -> biomes.size());
                output.writeInt(biomes.size());
                for (int biome : biomes.keySet()) output.writeUTF(mappings.biomeName(biome));
            }
            for (var sample : samples) {
                output.writeInt(sample.surfaceY()); output.writeInt(sample.fluidY());
                output.writeInt(mappings == null ? sample.biomeIndex() : biomes.get(sample.biomeIndex()));
                output.writeInt(palette.get(sample.topBlockIndex())); output.writeInt(sample.structureIndex());
                output.writeInt(sample.treeKind()); output.writeInt(sample.treeDensity()); output.writeInt(sample.treeHeight());
                output.writeInt(sample.fluid()); output.writeInt(sample.flags()); output.writeInt(sample.groundFeatureKind());
                output.writeInt(palette.get(sample.underBlockIndex())); output.writeInt(palette.get(sample.deepBlockIndex()));
                output.writeInt(sample.surfaceBottom()); output.writeInt(sample.lowerTop()); output.writeInt(sample.lowerBottom()); output.writeInt(sample.spanFloor());
                var volume = sample.volume();
                if (volumes) output.writeBoolean(volume != null);
                if (volume != null) {
                    output.writeInt(volume.size());
                    for (int i = 0; i < volume.size(); i++) {
                        output.writeInt(volume.bottom(i)); output.writeInt(volume.top(i));
                        output.writeInt(palette.get(volume.block(i))); output.writeByte(volume.fluid(i));
                    }
                }
            }
            output.writeLong(terrain.colorFingerprint());
            boolean colors=terrain.colorsMatch(terrain.colorFingerprint());
            output.writeBoolean(colors);
            if(colors)for(int i=0;i<samples.length;i++) {
                output.writeInt(terrain.surfaceTints()[i]);output.writeInt(terrain.foliageTints()[i]);output.writeInt(terrain.waterTints()[i]);
            }
        };
    }

    Map<BlockPos, BlockState> readSurface(Lease lease) {
        SurfaceData data = readSurfaceData(lease);
        return data == null ? null : data.blocks();
    }

    record SurfaceData(Map<BlockPos, BlockState> blocks, boolean canonical, boolean weatherChecked) { }

    SurfaceData readSurfaceData(Lease lease) {
        return read(lease, (input, version) -> {
            int count = bounded(input.readInt(), MAX_BLOCKS);
            int paletteSize = bounded(input.readInt(), MAX_BLOCKS);
            BlockState[] palette = new BlockState[paletteSize];
            for (int i = 0; i < paletteSize; i++) palette[i] = decodeState(input.readUTF());
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(input.readInt(), input.readInt(), input.readInt());
                blocks.put(pos, palette[index(input.readInt(), palette.length)]);
            }
            return new SurfaceData(Map.copyOf(blocks), version >= 4, version >= 5);
        });
    }

    boolean writeSurface(Lease lease, Map<BlockPos, BlockState> blocks) {
        return writeSurface(lease, blocks, false);
    }

    boolean writeSurface(Lease lease, Map<BlockPos, BlockState> blocks, boolean canonical) {
        if (blocks.size() > MAX_BLOCKS) return false;
        return write(lease, canonical ? 5 : 3, surfaceEncoder(blocks));
    }

    CompletableFuture<Boolean> writeSurfaceLater(Lease lease, Map<BlockPos, BlockState> blocks, boolean canonical) {
        if (blocks.size() > MAX_BLOCKS) return CompletableFuture.completedFuture(false);
        long bytes = 128L + blocks.size() * 112L;
        return defer(lease, bytes, 0, () -> {
            Map<BlockPos, BlockState> snapshot = new LinkedHashMap<>();
            blocks.forEach((pos, state) -> snapshot.put(pos.immutable(), state));
            return new PendingWrite(retain(lease), canonical ? 5 : 3, surfaceEncoder(snapshot), bytes);
        });
    }

    private Lease retain(Lease lease) {
        synchronized (shared) {
            lease.entry.users++;
            return new Lease(lease.key, lease.entry, lease.readable);
        }
    }

    private CompletableFuture<Boolean> defer(Lease lease, long bytes, int columns,
                                             java.util.function.Supplier<PendingWrite> snapshot) {
        PendingWrite superseded = null;
        try {
            synchronized (pendingLock) {
                synchronized (shared) {
                    if (!lease.valid() || Thread.currentThread().isInterrupted()
                            || columns > 0 && columns < terrainWriteColumns.getOrDefault(lease.key, 0))
                        return CompletableFuture.completedFuture(false);
                }
                PendingWrite previous = pendingWrites.get(lease.key);
                int count = previous == null ? 1 : 0;
                if (!reserveWrites(count, bytes)) {
                    terrainWriteDeferrals.increment();
                    return CompletableFuture.completedFuture(false);
                }
                PendingWrite write = null;
                boolean accepted = false;
                try {
                    // Samples, states and volumes are immutable; only their mutable containers need copying.
                    // This lock belongs to the writer queue, so snapshotting never holds the cache metadata lock.
                    write = snapshot.get();
                    synchronized (shared) {
                        if (!write.lease.valid()) return CompletableFuture.completedFuture(false);
                        if (columns > 0) rememberWriteColumns(lease.key, columns);
                    }
                    pendingWrites.put(lease.key, write);
                    if (previous != null) {
                        previous.lease.close();
                        superseded = previous;
                        releaseWrites(0, previous.bytes);
                        coalescedWrites.increment();
                    } else {
                        Key key = lease.key;
                        COMMITS.execute(() -> drainWrite(key));
                    }
                    accepted = true;
                    return write.result;
                } finally {
                    if (!accepted) {
                        if (write != null) pendingWrites.remove(lease.key, write);
                        if (write != null) write.lease.close();
                        releaseWrites(count, bytes);
                    }
                }
            }
        } catch (RuntimeException failure) {
            error(failure);
            return CompletableFuture.completedFuture(false);
        } finally { if (superseded != null) superseded.result.complete(false); }
    }

    private void rememberWriteColumns(Key key, int columns) {
        terrainWriteColumns.merge(key, columns, Math::max);
        while (terrainWriteColumns.size() > 32768)
            terrainWriteColumns.remove(terrainWriteColumns.keySet().iterator().next());
    }

    private static boolean reserveWrites(int count, long bytes) {
        synchronized (WRITE_BUDGET) {
            if (TERRAIN_PENDING.get() + count > MAX_PENDING_TERRAIN_WRITES
                    || TERRAIN_PENDING_BYTES.get() + bytes > MAX_PENDING_TERRAIN_BYTES) return false;
            TERRAIN_PENDING.addAndGet(count);
            TERRAIN_PENDING_BYTES.addAndGet(bytes);
            return true;
        }
    }

    private static void releaseWrites(int count, long bytes) {
        synchronized (WRITE_BUDGET) {
            TERRAIN_PENDING.addAndGet(-count);
            TERRAIN_PENDING_BYTES.addAndGet(-bytes);
        }
    }

    private void drainWrite(Key key) {
        PendingWrite write;
        synchronized (pendingLock) {
            write = pendingWrites.get(key);
            if (write == null) return;
            synchronized (shared) {
                // A new capture may replace an invalid queued write before its tombstone is drained.
                // Keep the latest snapshot and put it behind the already queued deletion.
                if (write.lease.valid() && shared.deleting && shared.invalidations.containsKey(key)) {
                    COMMITS.execute(() -> drainWrite(key));
                    return;
                }
            }
            pendingWrites.remove(key);
        }
        if (write == null) return;
        boolean committed = false;
        Path temporary = null;
        try (write.lease) {
            if (!write.lease.valid()) return;
            long started = System.nanoTime();
            deferredEncodes.increment();
            try { temporary = encodeTemporary(write.lease, write.version, write.encoder); }
            finally { deferredEncodeNanos.add(System.nanoTime() - started); }
            committed = commit(write.lease, temporary);
        } catch (IOException | RuntimeException failure) { error(failure); }
        finally {
            deleteTemporary(temporary);
            releaseWrites(1, write.bytes);
            write.result.complete(committed);
        }
    }

    private Encoder surfaceEncoder(Map<BlockPos, BlockState> blocks) {
        return output -> {
            Map<BlockState, Integer> palette = new LinkedHashMap<>();
            blocks.values().forEach(state -> palette.computeIfAbsent(state, ignored -> palette.size()));
            output.writeInt(blocks.size()); output.writeInt(palette.size());
            for (var state : palette.keySet()) output.writeUTF(encodeState(state));
            for (var entry : blocks.entrySet()) {
                BlockPos p = entry.getKey();
                output.writeInt(p.getX()); output.writeInt(p.getY()); output.writeInt(p.getZ()); output.writeInt(palette.get(entry.getValue()));
            }
        };
    }

    private BlockState decodeState(String json) {
        synchronized (decodedStates) {
            BlockState state = decodedStates.get(json);
            if (state != null) return state;
            state = BlockState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
            stateDecodes.increment();
            if (decodedStates.size() >= 4096) decodedStates.remove(decodedStates.keySet().iterator().next());
            if (!closed) decodedStates.put(json, state);
            return state;
        }
    }

    private String encodeState(BlockState state) {
        synchronized (encodedStates) {
            String json = encodedStates.get(state);
            if (json != null) return json;
            json = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().toString();
            if (encodedStates.size() >= 4096) encodedStates.remove(encodedStates.keySet().iterator().next());
            if (!closed) encodedStates.put(state, json);
            return json;
        }
    }

    long stateDecodes() { return stateDecodes.sum(); }

    private <T> T read(Lease lease, Decoder<T> decoder) {
        if (!lease.readable || !lease.valid()) { missStale.increment(); misses.increment(); return null; }
        // Buffer decompressed bytes too: DataInputStream.readInt otherwise
        // enters the inflater once per byte for every column field.
        try {
            var record = shared.regions.read(lease.key);
            if (record == null) { missAbsent.increment(); misses.increment(); return null; }
            try (var input = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(new ByteArrayInputStream(record.bytes())), 32 * 1024))) {
            if (input.readInt() != MAGIC) { missCorrupt.increment(); throw new IOException("cache magic mismatch"); }
            int version=input.readInt();
            if ((version != schema(lease.key) && version != 5 && version != 4 && version != 3 && !(lease.key.kind == 0 && (version == 1 || version == 2))) || input.readLong() != fingerprint
                    || input.readInt() != lease.key.kind || input.readInt() != lease.key.x || input.readInt() != lease.key.z
                    || input.readInt() != lease.key.detail) { missIdentity.increment(); throw new IOException("cache identity mismatch"); }
            T result = decoder.read(input, version);
            if (input.read() != -1) { missCorrupt.increment(); throw new IOException("trailing cache data"); } // also validates zlib checksum
            if (!lease.valid()) { missRaced.increment(); misses.increment(); return null; }
            hits.increment();
            if (record.legacy()) migrateLater(lease);
            return result;
            }
        } catch (NoSuchFileException absent) {
            missAbsent.increment();
            misses.increment();
        } catch (IOException | RuntimeException failure) {
            error(failure);
            misses.increment();
        }
        return null;
    }

    private boolean write(Lease lease, Encoder encoder) {
        return write(lease, schema(lease.key), encoder);
    }

    private boolean write(Lease lease, int version, Encoder encoder) {
        return write(lease, version, encoder, () -> true);
    }

    private boolean write(Lease lease, int version, Encoder encoder, java.util.function.BooleanSupplier current) {
        if (!lease.valid() || Thread.currentThread().isInterrupted() || !current.getAsBoolean()) return false;
        Path temporary = null;
        try {
            temporary = encodeTemporary(lease, version, encoder);
            Path completed = temporary;
            var result = new CompletableFuture<Boolean>();
            COMMITS.execute(() -> {
                boolean committed = false;
                try { committed = commit(lease, completed, current); }
                catch (IOException | RuntimeException failure) { error(failure); }
                finally { deleteTemporary(completed); result.complete(committed); }
            });
            temporary = null;
            return result.get();
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException failure) { error(failure); }
        catch (IOException | RuntimeException failure) { error(failure); }
        finally { deleteTemporary(temporary); }
        return false;
    }

    private Path encodeTemporary(Lease lease, int version, Encoder encoder) throws IOException {
        Path temporary = null;
        try {
            Path target = file(lease.key);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "pending-", ".tmp");
            // Batch primitive writes before crossing into zlib, without changing
            // the on-disk schema or retaining a whole decoded tile in the IO queue.
            var compressor = new java.util.zip.Deflater(lease.key.kind == 2 ? 1 : java.util.zip.Deflater.DEFAULT_COMPRESSION);
            try (var output = new DataOutputStream(new BufferedOutputStream(
                    new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)), compressor), 32 * 1024))) {
                output.writeInt(MAGIC); output.writeInt(version); output.writeLong(fingerprint);
                output.writeInt(lease.key.kind); output.writeInt(lease.key.x); output.writeInt(lease.key.z); output.writeInt(lease.key.detail);
                encoder.write(output);
            } finally { compressor.end(); }
            Path completed = temporary;
            temporary = null;
            return completed;
        } finally { deleteTemporary(temporary); }
    }

    private boolean commit(Lease token, Path completed) throws IOException {
        return commit(token, completed, () -> true);
    }

    private boolean commit(Lease token, Path completed, java.util.function.BooleanSupplier current) throws IOException {
        synchronized (shared) {
            if (!token.valid() || shared.invalidations.containsKey(token.key) || !current.getAsBoolean()) return false;
        }
        shared.regions.write(token.key, completed);
        if (!token.valid() || !current.getAsBoolean()) {
            shared.regions.delete(token.key);
            return false;
        }
        writes.increment();
        synchronized (shared) { terrainHints.remove(token.key); }
        scheduleMaintenance();
        return token.valid();
    }

    private void deleteTemporary(Path temporary) {
        if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException failure) { error(failure); }
    }

    /** Marks leases immediately; deletions coalesce on the IO thread. */
    void invalidate(Collection<Key> keys) {
        synchronized (shared) {
            if (closed || shared.owner != this) return;
            var expanded = new HashSet<>(keys);
            for (Key key : keys) if (key.kind == 0) expanded.add(Key.mesh(key));
            for (Key key : expanded) {
                terrainHints.remove(key);
                terrainWriteColumns.remove(key);
                meshWriteOrders.remove(key);
                Entry entry = shared.active.remove(key);
                if (entry != null) entry.valid = false;
                shared.invalidations.put(key, ++shared.revision);
            }
            if (!shared.deleting && !shared.invalidations.isEmpty()) {
                shared.deleting = true;
                COMMITS.execute(this::drainInvalidations);
            }
        }
    }

    private void drainInvalidations() {
        Map<Key, Long> pending;
        synchronized (shared) { pending = new HashMap<>(shared.invalidations); }
        var failures = shared.regions.deleteAll(pending.keySet());
        for (var entry : pending.entrySet()) {
            IOException failure = failures.get(entry.getKey());
            if (failure == null) {
                synchronized (shared) { shared.invalidations.remove(entry.getKey(), entry.getValue()); }
            } else {
                // Leave a tombstone in memory: a failed deletion must not let
                // stale data re-enter the current world. No hot retry loop.
                error(failure);
            }
        }
        synchronized (shared) {
            shared.deleting = false;
            boolean newWork = shared.invalidations.entrySet().stream().anyMatch(entry -> !Objects.equals(pending.get(entry.getKey()), entry.getValue()));
            if (newWork) { shared.deleting = true; COMMITS.execute(this::drainInvalidations); }
        }
        scheduleMaintenance();
    }

    private void migrateLater(Lease lease) {
        synchronized (shared) {
            if (!lease.valid() || shared.migrations.size() >= 32 || !shared.migrations.add(lease.key)) return;
            Entry token = lease.entry;
            long revision = shared.revision;
            Key key = lease.key;
            COMMITS.execute(() -> {
                try {
                    synchronized (shared) {
                        if (closed || shared.owner != this || !token.valid || shared.revision != revision
                                || shared.invalidations.containsKey(key)) return;
                    }
                    shared.regions.migrate(key);
                } catch (IOException failure) { error(failure); }
                finally { synchronized (shared) { shared.migrations.remove(key); } }
            });
        }
    }

    private void scheduleMaintenance() {
        if (!shared.regions.hasMaintenance()) return;
        synchronized (shared) {
            if (shared.maintenanceQueued || closed || shared.owner != this) return;
            shared.maintenanceQueued = true;
            COMPACTIONS.schedule(() -> {
                try {
                    if (maintenanceIdle()) shared.regions.compactOne(this::maintenanceIdle);
                } catch (IOException | RuntimeException failure) { error(failure); }
                finally {
                    synchronized (shared) { shared.maintenanceQueued = false; }
                    if (!closed && shared.owner == this) scheduleMaintenance();
                }
            }, 1, TimeUnit.SECONDS);
        }
    }

    private boolean maintenanceIdle() {
        return !closed && shared.owner == this
                && System.nanoTime() - lastDemandNanos >= MAINTENANCE_IDLE_NANOS
                && COMMITS.getActiveCount() == 0 && COMMITS.getQueue().isEmpty()
                && TERRAIN_PENDING.get() == 0 && MESH_QUEUED.get() == 0;
    }

    private static void addAffectedTerrain(Set<Key> keys, int chunkX, int chunkZ, boolean capture) {
        for (int lod = 0; lod <= PredictionTileManager.MAX_LOD_LEVEL; lod++) {
            long spacing = 1L << lod, span = 64 * spacing, margin = Math.max(16, span / 8);
            for (long z = Math.floorDiv(chunkZ * 16L - margin, span); z <= Math.floorDiv(chunkZ * 16L + 15 + margin, span); z++) {
                for (long x = Math.floorDiv(chunkX * 16L - margin, span); x <= Math.floorDiv(chunkX * 16L + 15 + margin, span); x++) {
                    Key key = Key.terrain((int) x, (int) z, lod);
                    if (!capture || captureIntersects(key, chunkX, chunkZ)) keys.add(key);
                }
            }
        }
    }

    static Set<Key> affected(int chunkX, int chunkZ) {
        return affected(it.unimi.dsi.fastutil.longs.LongSets.singleton((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL));
    }

    static Set<Key> affected(it.unimi.dsi.fastutil.longs.LongSet columns) {
        Set<Key> keys = new HashSet<>();
        var sources = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        // Decoration can read 32 blocks beyond its source chunk and place
        // into neighbors. Invalidate every source whose bounded reads overlap.
        for (long column : columns) {
            int chunkX = (int) (column >> 32), chunkZ = (int) column;
            addAffectedTerrain(keys, chunkX, chunkZ, false);
            for (int z = chunkZ - 2; z <= chunkZ + 2; z++) for (int x = chunkX - 2; x <= chunkX + 2; x++)
                sources.add((long) x << 32 | z & 0xFFFFFFFFL);
        }
        for (long source : sources) {
            int x = (int) (source >> 32), z = (int) source;
            // Bit 2 separates reusable visual trees from exact feature replay.
            for (int settings = 0; settings < 32; settings++) {
                keys.add(Key.surface(x, z, settings));
                keys.add(Key.surface(x, z, settings | 96));
                // Corrected predicate semantics have a separate surface identity.
                keys.add(Key.surface(x, z, settings | PredictionVegetation.PREDICATE_SETTINGS_VERSION));
                keys.add(Key.surface(x, z, settings | 96 | PredictionVegetation.PREDICATE_SETTINGS_VERSION));
            }
        }
        return keys;
    }

    void invalidateChunk(int x, int z) { invalidate(affected(x, z)); }

    void invalidateChunks(it.unimi.dsi.fastutil.longs.LongSet columns) { invalidate(affected(columns)); }

    void invalidateCapture(int x, int z) {
        invalidateCaptures(it.unimi.dsi.fastutil.longs.LongSets.singleton((long) x << 32 | z & 0xFFFFFFFFL));
    }

    void invalidateCaptures(it.unimi.dsi.fastutil.longs.LongSet columns) {
        Set<Key> keys = new HashSet<>();
        // Authoritative arrivals only replace sampled columns. Match the live
        // manager's sparse-grid dependency check for unloaded tiles too; otherwise
        // a chunk between far grid points deletes useful persisted ancestors.
        // Explicit world edits still use the conservative invalidateChunk path.
        for (long column : columns)
            addAffectedTerrain(keys, (int) (column >> 32), (int) column, true);
        invalidate(keys);
    }

    private static boolean captureIntersects(Key key, int x, int z) {
        int spacing = 1 << key.detail, span = VssLodLayout.BASE_TILE_BLOCKS << key.detail;
        return PredictionTileManager.captureIntersectsAxis(x * 16L, key.x * (long) span, span, spacing)
                && PredictionTileManager.captureIntersectsAxis(z * 16L, key.z * (long) span, span, spacing);
    }
    Path file(Key key) {
        return shared.regions.legacy(key);
    }
    String diagnostics() { return "disk={hits=" + hits.sum() + ",misses=" + misses.sum() + ",writes=" + writes.sum()
            + ",meshHits=" + meshHits.sum() + ",meshMisses=" + meshMisses.sum() + ",meshWrites=" + meshWrites.sum()
            + ",meshQueuedBytes=" + MESH_QUEUED.get()
            + ",terrainPending=" + TERRAIN_PENDING.get() + ",terrainPendingBytes=" + TERRAIN_PENDING_BYTES.get()
            + ",terrainWriteDeferrals=" + terrainWriteDeferrals.sum()
            + ",terrainRepairs=" + terrainRepairs.sum()
            + ",coalescedWrites=" + coalescedWrites.sum() + ",deferredEncodes=" + deferredEncodes.sum()
            + ",deferredEncodeMs=" + deferredEncodeNanos.sum() / 1_000_000
            + ",stateDecodes=" + stateDecodes.sum() + ",errors=" + errors.sum() + "}"
            // Why the misses happened. `absent` is the healthy case (nothing
            // written yet); `identity`/`corrupt` mean a stored entry was
            // rejected, which is the state that silently destroys a warm cache.
            + ",missReason={absent=" + missAbsent.sum() + ",stale=" + missStale.sum()
            + ",identity=" + missIdentity.sum() + ",corrupt=" + missCorrupt.sum()
            + ",raced=" + missRaced.sum() + "}"; }
    Path root() { return root; }
    long hits() { return hits.sum(); }
    void flushMeshes() {
        var barrier = new FutureTask<Void>(() -> null);
        try {
            MESH_WRITES.prestartCoreThread();
            MESH_WRITES.getQueue().put(barrier);
            barrier.get();
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException failure) { error(failure); }
    }
    void flush() {
        List<CompletableFuture<Boolean>> pending;
        synchronized (pendingLock) { pending = pendingWrites.values().stream().map(write -> write.result).toList(); }
        try {
            COMMITS.submit(() -> { }).get();
            for (var result : pending) result.get();
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException failure) { error(failure); }
    }
    @Override public void close() {
        closed = true;
        List<PendingWrite> cancelled;
        synchronized (pendingLock) {
            cancelled = new ArrayList<>(pendingWrites.values());
            pendingWrites.clear();
        }
        for (PendingWrite write : cancelled) {
            write.lease.close();
            releaseWrites(1, write.bytes);
            write.result.complete(false);
        }
        synchronized (decodedStates) { decodedStates.clear(); }
        synchronized (encodedStates) { encodedStates.clear(); }
        synchronized (shared) {
            terrainHints.clear();
            terrainWriteColumns.clear();
            meshWriteOrders.clear();
            probeQueue.clear();
            if (shared.owner == this) {
                shared.active.values().forEach(entry -> entry.valid = false);
                shared.active.clear();
                shared.owner = null;
                COMMITS.execute(() -> {
                    // Storage methods synchronize with builders and a newly opened session.
                    synchronized (shared) { if (shared.owner != null) return; }
                    try { shared.regions.close(); } catch (IOException failure) { error(failure); }
                });
            }
        }
    }
    private void error(Throwable failure) {
        errors.increment();
        if (VSSClientConfig.CONFIG.debugLogging && loggedError.compareAndSet(false, true)) VSSLogger.debug("VSS prediction disk cache miss/write failure: " + failure);
    }
    private static int bounded(int value, int max) throws IOException { if (value < 0 || value > max) throw new IOException("cache count out of range"); return value; }
    private static int index(int value, int size) throws IOException { if (value < 0 || value >= size) throw new IOException("cache palette index out of range"); return value; }
    @FunctionalInterface private interface Decoder<T> { T read(DataInputStream input, int version) throws IOException; }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream output) throws IOException; }
}
